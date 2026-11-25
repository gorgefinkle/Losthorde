package dev.deadstreets.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Injects Dead Streets' texture alias and model JSON overrides into BetterModel's
 * own generated pack. It never reads client files and never ships a zombie PNG.
 */
final class BetterModelPackHook {
    static final String ZOMBIE_TEXTURE = "minecraft:entity/zombie/zombie";
    private final DeadStreetsPlugin plugin;
    private Object subscription;
    private boolean registered;
    private boolean injected;

    BetterModelPackHook(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
    }

    void register() throws ReflectiveOperationException {
        if (registered) return;
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> betterModel = Class.forName("kr.toxicity.model.api.BetterModel", true, loader);
        Object eventBus = betterModel.getMethod("eventBus").invoke(null);
        Class<?> eventBusType = Class.forName("kr.toxicity.model.api.BetterModelEventBus", true, loader);
        Class<?> applicationType = Class.forName("kr.toxicity.model.api.event.ModelEventApplication", true, loader);
        Class<?> eventType = Class.forName("kr.toxicity.model.api.event.PluginStartReloadEvent", true, loader);
        Object application = Proxy.newProxyInstance(loader, new Class<?>[]{applicationType}, (proxy, method, args) -> {
            if (method.getName().equals("isEnabled")) return plugin.isEnabled();
            if (method.getName().equals("toString")) return "DeadStreetsBetterModelPackHook";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
        Consumer<Object> callback = this::onReloadStart;
        Method subscribe = eventBusType.getMethod("subscribe", applicationType, Class.class, Consumer.class);
        subscription = subscribe.invoke(eventBus, application, eventType, callback);
        registered = true;
        plugin.getLogger().info("Registered BetterModel's official pack-reload injection hook.");
    }

    private void onReloadStart(Object event) {
        injected = false;
        try {
            Object zipper = event.getClass().getMethod("zipper").invoke(event);
            Object assets = zipper.getClass().getMethod("assets").invoke(zipper);
            Path generated = plugin.getServer().getPluginManager().getPlugin("BetterModel")
                    .getDataFolder().toPath().resolve("build.zip");
            if (!Files.isRegularFile(generated)) {
                plugin.getLogger().info("BetterModel pack hook is waiting for its first generated build; it will inject on the next reload.");
                return;
            }

            try (ZipFile zip = new ZipFile(generated.toFile())) {
                Map<String, byte[]> files = new java.util.HashMap<>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        try (var in = zip.getInputStream(entry)) {
                            files.put(entry.getName(), in.readAllBytes());
                        }
                    }
                }

                Set<String> modelPaths = new HashSet<>();
                int crawlerItems = 0;
                int hulkItems = 0;
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    String path = entry.getKey();
                    if (!path.startsWith("assets/") || !path.contains("/items/")
                            || !path.contains("deadstreets_") || !path.endsWith(".json")) continue;
                    JsonObject item = JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonElement modelRefElement = item.has("model") && item.get("model").isJsonObject()
                            ? item.getAsJsonObject("model").get("model") : null;
                    if (modelRefElement == null || !modelRefElement.isJsonPrimitive()) continue;
                    String modelRef = modelRefElement.getAsString();
                    int colon = modelRef.indexOf(':');
                    if (colon < 1) throw new IOException("Invalid BetterModel item model reference: " + path + " -> " + modelRef);
                    String modelPath = "assets/" + modelRef.substring(0, colon) + "/models/" + modelRef.substring(colon + 1) + ".json";
                    byte[] modelBytes = files.get(modelPath);
                    if (modelBytes == null) throw new IOException("Generated item references missing model JSON: " + modelPath);
                    JsonObject model = JsonParser.parseString(new String(modelBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!model.has("textures") || !model.get("textures").isJsonObject())
                        throw new IOException("Generated model has no texture map: " + modelPath);
                    JsonObject textures = model.getAsJsonObject("textures");
                    boolean rewritten = false;
                    for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                        if (!texture.getValue().isJsonPrimitive()) throw new IOException("Invalid texture value in " + modelPath);
                        String value = texture.getValue().getAsString();
                        if (value.startsWith("bettermodel:item/")) {
                            textures.addProperty(texture.getKey(), ZOMBIE_TEXTURE);
                            rewritten = true;
                        } else if (!value.equals(ZOMBIE_TEXTURE)) {
                            throw new IOException("Unexpected generated texture reference in " + modelPath + ": " + value);
                        }
                    }
                    if (!rewritten && textures.entrySet().stream().noneMatch(e -> e.getValue().getAsString().equals(ZOMBIE_TEXTURE)))
                        throw new IOException("No zombie texture alias found in " + modelPath);
                    modelPaths.add(modelPath);
                    if (path.contains("deadstreets_crawler_")) crawlerItems++;
                    if (path.contains("deadstreets_hulk_")) hulkItems++;
                    addAsset(assets, modelPath, (model.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                if (crawlerItems != 9 || hulkItems != 9 || modelPaths.size() != 18)
                    throw new IOException("Expected 9 crawler + 9 hulk model parts; found crawler=" + crawlerItems
                            + ", hulk=" + hulkItems + ", models=" + modelPaths.size());

                String atlasPath = "assets/minecraft/atlases/items.json";
                JsonObject atlas = files.containsKey(atlasPath)
                        ? JsonParser.parseString(new String(files.get(atlasPath), StandardCharsets.UTF_8)).getAsJsonObject()
                        : new JsonObject();
                JsonArray sources = atlas.has("sources") && atlas.get("sources").isJsonArray()
                        ? atlas.getAsJsonArray("sources") : new JsonArray();
                atlas.add("sources", sources);
                boolean present = false;
                for (JsonElement source : sources) {
                    if (source.isJsonObject()
                            && "minecraft:single".equals(source.getAsJsonObject().has("type") ? source.getAsJsonObject().get("type").getAsString() : "")
                            && ZOMBIE_TEXTURE.equals(source.getAsJsonObject().has("resource") ? source.getAsJsonObject().get("resource").getAsString() : "")) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    JsonObject source = new JsonObject();
                    source.addProperty("type", "minecraft:single");
                    source.addProperty("resource", ZOMBIE_TEXTURE);
                    sources.add(source);
                }
                addAsset(assets, atlasPath, (atlas.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                injected = true;
                plugin.getLogger().info("Injected zombie texture atlas registration and " + modelPaths.size()
                        + " rewritten infected model definitions into BetterModel's own pack.");
            }
        } catch (Throwable failure) {
            plugin.getLogger().severe("BetterModel pack injection failed: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }

    private static void addAsset(Object assets, String path, byte[] bytes) throws ReflectiveOperationException {
        Method add = assets.getClass().getMethod("add", String.class, long.class, Supplier.class);
        Supplier<byte[]> supplier = () -> bytes.clone();
        add.invoke(assets, path, (long) bytes.length, supplier);
    }

    boolean injectedOnReload() {
        return injected;
    }

    String auditGeneratedPack(Path zipPath) throws IOException {
        if (!injected) throw new IOException("BetterModel's reload hook did not inject any assets.");
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry atlasEntry = zip.getEntry("assets/minecraft/atlases/items.json");
            if (atlasEntry == null) throw new IOException("BetterModel output pack is missing the injected items atlas.");
            JsonObject atlas;
            try (var in = zip.getInputStream(atlasEntry)) {
                atlas = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            boolean found = false;
            if (atlas.has("sources") && atlas.get("sources").isJsonArray()) {
                for (JsonElement source : atlas.getAsJsonArray("sources")) {
                    if (source.isJsonObject() && source.getAsJsonObject().has("resource")
                            && ZOMBIE_TEXTURE.equals(source.getAsJsonObject().get("resource").getAsString())) found = true;
                }
            }
            if (!found) throw new IOException("Injected zombie texture sprite is absent from BetterModel's final items atlas.");
            if (zip.getEntry("assets/minecraft/textures/entity/zombie/zombie.png") != null)
                throw new IOException("Dead Streets must not replace the client's zombie texture PNG.");
            Set<String> referencedModels = new HashSet<>();
            int itemCount = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                if (entry.isDirectory() || !path.startsWith("assets/") || !path.contains("/items/")
                        || !path.contains("deadstreets_") || !path.endsWith(".json")) continue;
                itemCount++;
                try (var in = zip.getInputStream(entry)) {
                    JsonObject item = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonElement ref = item.has("model") && item.get("model").isJsonObject()
                            ? item.getAsJsonObject("model").get("model") : null;
                    if (ref == null || !ref.isJsonPrimitive()) throw new IOException("Final item has no model reference: " + path);
                    String modelRef = ref.getAsString();
                    int colon = modelRef.indexOf(':');
                    if (colon < 1) throw new IOException("Invalid final model reference: " + modelRef);
                    referencedModels.add("assets/" + modelRef.substring(0, colon) + "/models/"
                            + modelRef.substring(colon + 1) + ".json");
                }
            }
            if (itemCount != 18 || referencedModels.size() != 18)
                throw new IOException("Final BetterModel pack has item/model references " + itemCount + "/" + referencedModels.size() + ", expected 18/18.");
            for (String modelPath : referencedModels) {
                ZipEntry modelEntry = zip.getEntry(modelPath);
                if (modelEntry == null) throw new IOException("Final item model is missing: " + modelPath);
                try (var in = zip.getInputStream(modelEntry)) {
                    JsonObject model = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!model.has("textures") || !model.get("textures").isJsonObject())
                        throw new IOException("Final model has no texture map: " + modelPath);
                    for (Map.Entry<String, JsonElement> texture : model.getAsJsonObject("textures").entrySet()) {
                        if (!ZOMBIE_TEXTURE.equals(texture.getValue().getAsString()))
                            throw new IOException("Final model still points at a private/missing texture: " + modelPath + " -> " + texture.getValue());
                    }
                }
            }
            return "items=" + itemCount + ", models=" + referencedModels.size() + ", client zombie texture alias registered, no zombie PNG shipped";
        }
    }
}
