package dev.deadstreets.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Injects Dead Streets' texture alias and model JSON overrides into BetterModel's
 * own generated pack. It never reads client files and never ships a zombie PNG.
 *
 * BetterModel may obfuscate generated item/model filenames. We therefore identify
 * our 18 model parts by their deliberately unique zombie-skin UV signatures rather
 * than by filename prefixes.
 */
final class BetterModelPackHook {
    static final String ZOMBIE_TEXTURE_RESOURCE = "minecraft:entity/zombie/zombie";
    private static final String ZOMBIE_TEXTURE_SPRITE = "deadstreets:item/zombie";
    private static final String CRAWLER_MODEL = "bettermodel/models/deadstreets_crawler.bbmodel";
    private static final String HULK_MODEL = "bettermodel/models/deadstreets_hulk.bbmodel";
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

            Set<String> expectedUv = expectedUvSignatures();
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
                Set<String> itemPaths = new HashSet<>();
                Map<String, Integer> matchedUvCounts = new java.util.HashMap<>();
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    String itemPath = entry.getKey();
                    if (!itemPath.startsWith("assets/") || !itemPath.contains("/items/")
                            || !itemPath.endsWith(".json")) continue;
                    JsonObject item = JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
                    String modelRef = modelReference(item);
                    if (modelRef == null) continue;
                    String modelPath = modelPath(modelRef, itemPath);
                    byte[] modelBytes = files.get(modelPath);
                    if (modelBytes == null) continue;
                    JsonObject model = JsonParser.parseString(new String(modelBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    String uvSignature = matchingUvSignature(model, expectedUv);
                    if (uvSignature == null) continue;

                    if (!model.has("textures") || !model.get("textures").isJsonObject())
                        throw new IOException("UV-matched Dead Streets model has no texture map: " + modelPath);
                    JsonObject textures = model.getAsJsonObject("textures");
                    boolean rewritten = false;
                    for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                        if (!texture.getValue().isJsonPrimitive()) throw new IOException("Invalid texture value in " + modelPath);
                        String value = texture.getValue().getAsString();
                        if (value.startsWith("bettermodel:item/")) {
                            textures.addProperty(texture.getKey(), ZOMBIE_TEXTURE_SPRITE);
                            rewritten = true;
                        } else if (!value.equals(ZOMBIE_TEXTURE_SPRITE)) {
                            throw new IOException("Unexpected texture reference in UV-matched model " + modelPath + ": " + value);
                        }
                    }
                    if (!rewritten && textures.entrySet().stream().noneMatch(e -> e.getValue().getAsString().equals(ZOMBIE_TEXTURE_SPRITE)))
                        throw new IOException("No zombie texture alias found in " + modelPath);

                    modelPaths.add(modelPath);
                    itemPaths.add(itemPath);
                    matchedUvCounts.merge(uvSignature, 1, Integer::sum);
                    addAsset(assets, modelPath, (model.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                }

                if (itemPaths.size() != 18 || modelPaths.size() != 18) {
                    throw new IOException("Expected 18 Dead Streets item/model parts identified by zombie UV signatures; found items="
                            + itemPaths.size() + ", models=" + modelPaths.size()
                            + ". BetterModel obfuscation is supported; this is a geometry-match failure.");
                }
                for (String signature : expectedUv) {
                    int count = matchedUvCounts.getOrDefault(signature, 0);
                    if (count != 2) throw new IOException("Expected one Crawler and one Hulk model for UV signature "
                            + signature + "; found " + count);
                }

                // Java 26.2 has no general mob atlas. BetterModel's generated item
                // models resolve through the items atlas, so expose the client's
                // active zombie texture there without shipping or overriding its PNG.
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
                            && ZOMBIE_TEXTURE_RESOURCE.equals(source.getAsJsonObject().has("resource") ? source.getAsJsonObject().get("resource").getAsString() : "")
                            && ZOMBIE_TEXTURE_SPRITE.equals(source.getAsJsonObject().has("sprite") ? source.getAsJsonObject().get("sprite").getAsString() : "")) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    JsonObject source = new JsonObject();
                    source.addProperty("type", "minecraft:single");
                    source.addProperty("resource", ZOMBIE_TEXTURE_RESOURCE);
                    source.addProperty("sprite", ZOMBIE_TEXTURE_SPRITE);
                    sources.add(source);
                }
                addAsset(assets, atlasPath, (atlas.toString() + "\n").getBytes(StandardCharsets.UTF_8));

                // Remove the alias from blocks.json if an earlier build registered it there.
                String oldAtlasPath = "assets/minecraft/atlases/blocks.json";
                if (files.containsKey(oldAtlasPath)) {
                    JsonObject oldAtlas = JsonParser.parseString(new String(files.get(oldAtlasPath), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (oldAtlas.has("sources") && oldAtlas.get("sources").isJsonArray()) {
                        JsonArray cleaned = new JsonArray();
                        for (JsonElement source : oldAtlas.getAsJsonArray("sources")) {
                            boolean zombieAlias = source.isJsonObject()
                                    && (ZOMBIE_TEXTURE_RESOURCE.equals(source.getAsJsonObject().has("resource")
                                            ? source.getAsJsonObject().get("resource").getAsString() : "")
                                    || ZOMBIE_TEXTURE_SPRITE.equals(source.getAsJsonObject().has("sprite")
                                            ? source.getAsJsonObject().get("sprite").getAsString() : ""));
                            if (!zombieAlias) cleaned.add(source);
                        }
                        oldAtlas.add("sources", cleaned);
                        addAsset(assets, oldAtlasPath, (oldAtlas.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                injected = true;
                plugin.getLogger().info("Injected zombie texture atlas registration and " + modelPaths.size()
                        + " UV-matched infected model definitions into BetterModel's own pack (filename obfuscation supported).");
            }
        } catch (Throwable failure) {
            plugin.getLogger().severe("BetterModel pack injection failed: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }

    private Set<String> expectedUvSignatures() throws IOException {
        Set<String> signatures = new HashSet<>();
        for (String resource : Set.of(CRAWLER_MODEL, HULK_MODEL)) {
            try (InputStream input = plugin.getResource(resource)) {
                if (input == null) throw new IOException("Missing embedded Blockbench model " + resource);
                JsonObject model = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject resolution = model.getAsJsonObject("resolution");
                double divisorX = resolution.get("width").getAsDouble() / 16.0;
                double divisorY = resolution.get("height").getAsDouble() / 16.0;
                for (JsonElement element : model.getAsJsonArray("elements")) {
                    JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
                    JsonElement north = faces.get("north");
                    if (north != null && north.isJsonObject() && north.getAsJsonObject().has("uv")) {
                        signatures.add(uvSignature(north.getAsJsonObject().getAsJsonArray("uv"), divisorX, divisorY));
                    }
                }
            }
        }
        if (signatures.size() != 9) throw new IOException("Expected 9 unique Dead Streets zombie UV signatures; found " + signatures.size());
        return signatures;
    }

    private static String matchingUvSignature(JsonObject model, Set<String> expected) {
        if (!model.has("elements") || !model.get("elements").isJsonArray()) return null;
        for (JsonElement element : model.getAsJsonArray("elements")) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has("faces")) continue;
            JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
            JsonElement north = faces.get("north");
            if (north == null || !north.isJsonObject() || !north.getAsJsonObject().has("uv")) continue;
            String signature = uvSignature(north.getAsJsonObject().getAsJsonArray("uv"), 1.0, 1.0);
            if (expected.contains(signature)) return signature;
        }
        return null;
    }

    private static String uvSignature(JsonArray uv, double divisorX, double divisorY) {
        if (uv == null || uv.size() != 4) return "";
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f",
                uv.get(0).getAsDouble() / divisorX,
                uv.get(1).getAsDouble() / divisorY,
                uv.get(2).getAsDouble() / divisorX,
                uv.get(3).getAsDouble() / divisorY);
    }

    private static String modelReference(JsonObject item) {
        if (!item.has("model") || !item.get("model").isJsonObject()) return null;
        JsonElement reference = item.getAsJsonObject("model").get("model");
        return reference != null && reference.isJsonPrimitive() ? reference.getAsString() : null;
    }

    private static String modelPath(String modelRef, String itemPath) throws IOException {
        int colon = modelRef.indexOf(':');
        if (colon < 1) throw new IOException("Invalid BetterModel item model reference: " + itemPath + " -> " + modelRef);
        return "assets/" + modelRef.substring(0, colon) + "/models/" + modelRef.substring(colon + 1) + ".json";
    }

    /**
     * PackAssets.add(String, ...) is namespace-relative (normally assets/bettermodel/...).
     * These integrations must also write into assets/minecraft/atlases and the generated
     * model namespace, so add the PackResource at its exact pack-root path instead.
     *
     * BetterModel exposes PackResource.of and PackPath publicly; only the resource map
     * and overlay are package-private. Reflect those two implementation details while
     * keeping the actual resource creation on BetterModel's own API.
     */
    @SuppressWarnings("unchecked")
    private static void addAsset(Object assets, String path, byte[] bytes) throws ReflectiveOperationException {
        ClassLoader loader = assets.getClass().getClassLoader();
        Class<?> packPathType = Class.forName("kr.toxicity.model.api.pack.PackPath", true, loader);
        Object packPath = packPathType.getConstructor(String.class).newInstance(path);
        Class<?> overlayType = Class.forName("kr.toxicity.model.api.pack.PackOverlay", true, loader);
        var overlayField = assets.getClass().getDeclaredField("overlay");
        overlayField.setAccessible(true);
        Object overlay = overlayField.get(assets);

        Class<?> resourceType = Class.forName("kr.toxicity.model.api.pack.PackResource", true, loader);
        Object resource = resourceType.getMethod("of", overlayType, packPathType, long.class, Supplier.class)
                .invoke(null, overlay, packPath, (long) bytes.length, (Supplier<byte[]>) () -> bytes.clone());

        var resourceMapField = assets.getClass().getDeclaredField("resourceMap");
        resourceMapField.setAccessible(true);
        Map<Object, Object> resourceMap = (Map<Object, Object>) resourceMapField.get(assets);
        resourceMap.put(packPath, resource);
    }

    boolean injectedOnReload() {
        return injected;
    }

    String auditGeneratedPack(Path zipPath) throws IOException {
        if (!injected) throw new IOException("BetterModel's reload hook did not inject any assets.");
        Set<String> expectedUv = expectedUvSignatures();
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
                    if (source.isJsonObject() && source.getAsJsonObject().has("type")
                            && "minecraft:single".equals(source.getAsJsonObject().get("type").getAsString())
                            && source.getAsJsonObject().has("resource")
                            && ZOMBIE_TEXTURE_RESOURCE.equals(source.getAsJsonObject().get("resource").getAsString())
                            && source.getAsJsonObject().has("sprite")
                            && ZOMBIE_TEXTURE_SPRITE.equals(source.getAsJsonObject().get("sprite").getAsString())) found = true;
                }
            }
            if (!found) throw new IOException("Injected zombie texture sprite is absent from BetterModel final items atlas.");
            ZipEntry oldBlocksAtlas = zip.getEntry("assets/minecraft/atlases/blocks.json");
            if (oldBlocksAtlas != null) {
                try (var in = zip.getInputStream(oldBlocksAtlas)) {
                    JsonObject oldAtlas = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (oldAtlas.has("sources") && oldAtlas.get("sources").isJsonArray()) {
                        for (JsonElement source : oldAtlas.getAsJsonArray("sources")) {
                            if (source.isJsonObject()
                                    && ((source.getAsJsonObject().has("resource") && ZOMBIE_TEXTURE_RESOURCE.equals(source.getAsJsonObject().get("resource").getAsString()))
                                        || (source.getAsJsonObject().has("sprite") && ZOMBIE_TEXTURE_SPRITE.equals(source.getAsJsonObject().get("sprite").getAsString()))))
                                throw new IOException("Zombie texture alias is incorrectly registered in the blocks atlas.");
                        }
                    }
                }
            }
            if (zip.getEntry("assets/minecraft/textures/entity/zombie/zombie.png") != null)
                throw new IOException("Dead Streets must not replace the client's zombie texture PNG.");

            Set<String> referencedModels = new HashSet<>();
            Set<String> referencedItems = new HashSet<>();
            Map<String, Integer> matchedUvCounts = new java.util.HashMap<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String itemPath = entry.getName();
                if (entry.isDirectory() || !itemPath.startsWith("assets/") || !itemPath.contains("/items/")
                        || !itemPath.endsWith(".json")) continue;
                JsonObject item;
                try (var in = zip.getInputStream(entry)) {
                    item = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
                String modelRef = modelReference(item);
                if (modelRef == null) continue;
                String modelPath = modelPath(modelRef, itemPath);
                ZipEntry modelEntry = zip.getEntry(modelPath);
                if (modelEntry == null) continue;
                JsonObject model;
                try (var in = zip.getInputStream(modelEntry)) {
                    model = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
                String uvSignature = matchingUvSignature(model, expectedUv);
                if (uvSignature == null) continue;
                if (!model.has("textures") || !model.get("textures").isJsonObject())
                    throw new IOException("UV-matched final model has no texture map: " + modelPath);
                for (Map.Entry<String, JsonElement> texture : model.getAsJsonObject("textures").entrySet()) {
                    if (!texture.getValue().isJsonPrimitive() || !ZOMBIE_TEXTURE_SPRITE.equals(texture.getValue().getAsString()))
                        throw new IOException("Final UV-matched model still points at a private/missing texture: "
                                + modelPath + " -> " + texture.getValue());
                }
                referencedItems.add(itemPath);
                referencedModels.add(modelPath);
                matchedUvCounts.merge(uvSignature, 1, Integer::sum);
            }
            if (referencedItems.size() != 18 || referencedModels.size() != 18)
                throw new IOException("Final BetterModel pack has UV-matched item/model references "
                        + referencedItems.size() + "/" + referencedModels.size() + ", expected 18/18.");
            for (String signature : expectedUv) {
                int count = matchedUvCounts.getOrDefault(signature, 0);
                if (count != 2) throw new IOException("Final pack has " + count + " models for UV signature "
                        + signature + "; expected one Crawler and one Hulk.");
            }
            return "items=" + referencedItems.size() + ", models=" + referencedModels.size()
                    + ", zombie entity texture exposed as deadstreets:item/zombie in the items atlas, obfuscated filenames supported, no zombie PNG shipped";
        }
    }
}
