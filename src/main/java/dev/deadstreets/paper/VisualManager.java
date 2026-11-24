package dev.deadstreets.paper;

import com.magmaguy.freeminecraftmodels.api.FmmReloadedEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Attaches FreeMinecraftModels' textured, articulated Blockbench models to the
 * real server-side zombies. The zombies retain AI, targeting, navigation,
 * collision, health, damage and persistence; FMM owns only their client visuals.
 */
final class VisualManager implements Listener {
    private static final Map<String, String> MODEL_IDS = Map.of(
            "runner_husk", "deadstreets_runner",
            "popper_drowned", "deadstreets_popper",
            "crawler_lowprofile", "deadstreets_crawler",
            "hulk_brute", "deadstreets_hulk"
    );
    private static final String[] MODEL_FILES = {
            "deadstreets_runner.bbmodel",
            "deadstreets_popper.bbmodel",
            "deadstreets_crawler.bbmodel",
            "deadstreets_hulk.bbmodel"
    };
    private static final Map<String, String> VANILLA_TEXTURES = Map.of(
            "deadstreets_runner", "minecraft:entity/zombie/husk",
            "deadstreets_popper", "minecraft:entity/zombie/drowned",
            "deadstreets_crawler", "minecraft:entity/zombie/zombie",
            "deadstreets_hulk", "minecraft:entity/zombie/zombie"
    );
    private static final Map<String, String> TEXTURE_NAMES = Map.of(
            "deadstreets_runner", "husk",
            "deadstreets_popper", "drowned",
            "deadstreets_crawler", "zombie",
            "deadstreets_hulk", "zombie"
    );

    /**
     * FMM normally removes a DynamicEntity's backing entity when FMM itself
     * shuts down. That is correct for FMM-owned creatures but not for Dead
     * Streets' persistent zombie drivers. This subclass keeps the zombie and
     * restores its vanilla visibility whenever FMM reloads or Dead Streets
     * detaches the visual.
     */
    private record AppearanceState(boolean invisible, boolean visibleByDefault, boolean glowing) {}

    private static final class PersistentDynamicEntity extends DynamicEntity {
        private final Zombie zombie;
        private final Plugin fmmPlugin;
        private final boolean originalInvisible;
        private final boolean originalVisibleByDefault;
        private final boolean originalGlowing;
        private final String modelId;
        private final NamespacedKey originalInvisibleKey;
        private final NamespacedKey originalVisibleKey;
        private final NamespacedKey originalGlowingKey;
        private final NamespacedKey fmmDynamicKey;

        PersistentDynamicEntity(String modelId, Zombie zombie, Plugin fmmPlugin,
                                AppearanceState appearance, NamespacedKey originalInvisibleKey,
                                NamespacedKey originalVisibleKey, NamespacedKey originalGlowingKey,
                                NamespacedKey fmmDynamicKey) {
            super(modelId, zombie.getLocation());
            this.modelId = modelId;
            this.zombie = zombie;
            this.fmmPlugin = fmmPlugin;
            this.originalInvisible = appearance.invisible();
            this.originalVisibleByDefault = appearance.visibleByDefault();
            this.originalGlowing = appearance.glowing();
            this.originalInvisibleKey = originalInvisibleKey;
            this.originalVisibleKey = originalVisibleKey;
            this.originalGlowingKey = originalGlowingKey;
            this.fmmDynamicKey = fmmDynamicKey;
        }

        boolean isAttachedTo(Zombie candidate, String expectedModelId) {
            return zombie == candidate && modelId.equals(expectedModelId)
                    && !isRemoved() && getUnderlyingEntity() == candidate;
        }

        void restoreOriginalGlow() {
            zombie.setGlowing(originalGlowing);
        }

        void hideBackingEntity() {
            zombie.getPersistentDataContainer().set(fmmDynamicKey, PersistentDataType.BYTE, (byte) 0);
            // Keep a real fallback body available per viewer. FMM's per-player
            // show/hide APIs select the model for pack-ready clients and the
            // native zombie for clients who decline or are still downloading.
            zombie.setVisibleByDefault(true);
            zombie.setInvisible(false);
        }

        private void restoreBackingEntity() {
            if (!zombie.isValid()) return;
            zombie.setVisibleByDefault(originalVisibleByDefault);
            zombie.setInvisible(originalInvisible);
            zombie.setGlowing(originalGlowing);
            // FMM applies the model hitbox directly to the backing mob. A pose
            // refresh asks Paper to rebuild the vanilla box after detaching.
            zombie.setPose(zombie.getPose(), true);
            zombie.getPersistentDataContainer().remove(originalInvisibleKey);
            zombie.getPersistentDataContainer().remove(originalVisibleKey);
            zombie.getPersistentDataContainer().remove(originalGlowingKey);
            zombie.getPersistentDataContainer().remove(fmmDynamicKey);
            for (Player player : zombie.getWorld().getPlayers()) {
                player.showEntity(fmmPlugin, zombie);
            }
        }

        void detachKeepingZombie() {
            if (!isRemoved()) {
                for (Player player : zombie.getWorld().getPlayers()) {
                    showTo(player);
                    showUnderlyingEntity(player);
                }
                // Protected FMM lifecycle hook, exposed only for this persistent
                // driver integration. Unlike remove(), this never despawns the mob.
                super.removeKeepingBody();
            }
            restoreBackingEntity();
        }

        @Override
        protected void shutdownRemove() {
            detachKeepingZombie();
        }
    }

    private static final class Active {
        final Zombie zombie;
        final String type;
        final PersistentDynamicEntity model;
        boolean dying;
        boolean inflating;
        boolean roared;

        Active(Zombie zombie, String type, PersistentDynamicEntity model) {
            this.zombie = zombie;
            this.type = type;
            this.model = model;
        }
    }

    private final DeadStreetsPlugin plugin;
    private final Map<UUID, Active> active = new HashMap<>();
    private final NamespacedKey originalInvisibleKey;
    private final NamespacedKey originalVisibleKey;
    private final NamespacedKey originalGlowingKey;
    private final NamespacedKey packReadyKey;
    private NamespacedKey fmmDynamicKey;
    private final Map<UUID, Boolean> packReadyByPlayer = new HashMap<>();
    private Plugin fmmPlugin;
    private BukkitTask task;
    private boolean fmmReady;
    private boolean reloadRequested;
    private boolean reloadFailed;
    private boolean modelAssetsChanged;
    private boolean patchingPack;
    private boolean patchPending;

    /**
     * Paper calls onLoad in dependency order. Stage the models before FMM's
     * onEnable starts its first asynchronous import, avoiding a needless full
     * FMM reload on every fresh server start.
     */
    static void stageBeforeFmmStartup(Plugin source) {
        Plugin fmm = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels");
        if (fmm == null) return;
        try {
            Path destination = fmm.getDataFolder().toPath().resolve("models").resolve("deadstreets");
            Files.createDirectories(destination);
            for (String file : MODEL_FILES) {
                try (InputStream input = source.getResource("fmm/" + file)) {
                    if (input == null) throw new IOException("Missing embedded model resource fmm/" + file);
                    Files.copy(input, destination.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException failure) {
            source.getLogger().severe("Could not stage FMM models before plugin startup: " + failure.getMessage());
        }
    }

    VisualManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        this.originalInvisibleKey = new NamespacedKey(plugin, "fmm_original_invisible");
        this.originalVisibleKey = new NamespacedKey(plugin, "fmm_original_visible_by_default");
        this.originalGlowingKey = new NamespacedKey(plugin, "fmm_original_glowing");
        this.packReadyKey = new NamespacedKey(plugin, "fmm_resource_pack_ready");
    }

    void start() {
        fmmPlugin = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels");
        if (fmmPlugin == null || !fmmPlugin.isEnabled()) {
            throw new IllegalStateException("FreeMinecraftModels is required for Dead Streets infected models.");
        }
        fmmDynamicKey = new NamespacedKey(fmmPlugin, "DynamicEntity");
        try {
            installModelAssets();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not install Dead Streets FMM model assets.", failure);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        plugin.getLogger().info("Staged four vanilla-textured infected models for FreeMinecraftModels.");
    }

    private void installModelAssets() throws IOException {
        Path destination = fmmPlugin.getDataFolder().toPath().resolve("models").resolve("deadstreets");
        Files.createDirectories(destination);
        modelAssetsChanged = false;
        for (String file : MODEL_FILES) {
            String resource = "fmm/" + file;
            Path target = destination.resolve(file);
            try (InputStream input = plugin.getResource(resource)) {
                if (input == null) throw new IOException("Missing embedded model resource " + resource);
                byte[] embedded = input.readAllBytes();
                boolean changed = !Files.exists(target)
                        || !Arrays.equals(Files.readAllBytes(target), embedded);
                if (changed) {
                    Files.write(target, embedded);
                    modelAssetsChanged = true;
                }
            }
        }
    }

    /**
     * FMM imports the placeholder atlas from the Blockbench file and writes
     * model JSON that references it. Repoint those references to the matching
     * texture already shipped by the vanilla client, then remove the placeholder
     * PNGs and republish the pack. This keeps Mojang textures out of our jar and
     * out of the remotely hosted pack.
     */
    private void patchVanillaTextureReferences() throws IOException {
        Path output = fmmPlugin.getDataFolder().toPath().resolve("output");
        Path packRoot = output.resolve("FreeMinecraftModels");
        Path modelsRoot = packRoot.resolve("assets/freeminecraftmodels/models");
        Path texturesRoot = packRoot.resolve("assets/freeminecraftmodels/textures/entity");
        if (!Files.isDirectory(modelsRoot)) throw new IOException("FMM model output directory is missing: " + modelsRoot);

        for (String modelId : VANILLA_TEXTURES.keySet()) {
            Path modelDirectory = modelsRoot.resolve(modelId);
            if (!Files.isDirectory(modelDirectory)) {
                throw new IOException("FMM did not generate model JSON files for " + modelId);
            }
            String oldReference = "freeminecraftmodels:entity/" + modelId + "/" + TEXTURE_NAMES.get(modelId);
            String newReference = VANILLA_TEXTURES.get(modelId);
            int references = 0;
            try (Stream<Path> paths = Files.walk(modelDirectory)) {
                for (Path file : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    int found = countOccurrences(json, oldReference);
                    if (found > 0) {
                        json = json.replace(oldReference, newReference);
                        Files.writeString(file, json, StandardCharsets.UTF_8);
                        references += found;
                    } else {
                        references += countOccurrences(json, newReference);
                    }
                }
            }
            if (references == 0) throw new IOException("No generated texture references found for " + modelId);
            deleteTree(texturesRoot.resolve(modelId));
        }

        // These models render through Minecraft's item/block display pipeline,
        // not the native mob renderer. Vanilla entity PNGs are not automatically
        // members of the blocks atlas, so explicitly stitch the three existing
        // client textures into that atlas before publishing the pack.
        patchVanillaBlocksAtlas(packRoot);

        Path published = output.resolve("FreeMinecraftModels.zip");
        republishPack(packRoot, published);
        if (!Files.isRegularFile(published) || Files.size(published) == 0) {
            throw new IOException("The patched FreeMinecraftModels resource pack was not published.");
        }
        plugin.getLogger().info("Patched FMM model textures to use the four vanilla zombie-family client textures and removed bundled placeholder PNGs.");
    }

    private static void patchVanillaBlocksAtlas(Path packRoot) throws IOException {
        Path atlasFile = packRoot.resolve("assets/minecraft/atlases/blocks.json");
        if (!Files.isRegularFile(atlasFile)) {
            throw new IOException("FMM blocks atlas is missing: " + atlasFile);
        }

        JsonObject atlas = JsonParser.parseString(
                Files.readString(atlasFile, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray sources = atlas.getAsJsonArray("sources");
        if (sources == null) throw new IOException("FMM blocks atlas has no sources array.");

        // FMM generated these from our temporary transparent Blockbench atlases.
        // They must not survive after those placeholder PNGs are removed.
        Set<String> deadStreetsTexturePrefixes = Set.of(
                "freeminecraftmodels:entity/deadstreets_runner/",
                "freeminecraftmodels:entity/deadstreets_popper/",
                "freeminecraftmodels:entity/deadstreets_crawler/",
                "freeminecraftmodels:entity/deadstreets_hulk/"
        );
        for (int i = sources.size() - 1; i >= 0; i--) {
            JsonElement source = sources.get(i);
            if (!source.isJsonObject()) continue;
            JsonObject object = source.getAsJsonObject();
            JsonElement resourceElement = object.get("resource");
            if (resourceElement == null || !resourceElement.isJsonPrimitive()) continue;
            String resource = resourceElement.getAsString();
            if (deadStreetsTexturePrefixes.stream().anyMatch(resource::startsWith)) {
                sources.remove(i);
            }
        }

        Set<String> existing = new HashSet<>();
        for (JsonElement source : sources) {
            if (!source.isJsonObject()) continue;
            JsonObject object = source.getAsJsonObject();
            JsonElement type = object.get("type");
            JsonElement resource = object.get("resource");
            if (type != null && resource != null && type.isJsonPrimitive() && resource.isJsonPrimitive()
                    && (type.getAsString().equals("single") || type.getAsString().equals("minecraft:single"))) {
                existing.add(resource.getAsString());
            }
        }

        Set<String> vanillaResources = Set.of(
                "minecraft:entity/zombie/zombie",
                "minecraft:entity/zombie/husk",
                "minecraft:entity/zombie/drowned"
        );
        for (String resource : vanillaResources) {
            if (existing.contains(resource)) continue;
            JsonObject single = new JsonObject();
            single.addProperty("type", "minecraft:single");
            single.addProperty("resource", resource);
            sources.add(single);
        }

        Files.writeString(atlasFile, new com.google.gson.GsonBuilder()
                .setPrettyPrinting().create().toJson(atlas), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void republishPack(Path packRoot, Path published) throws IOException {
        Path pending = published.resolveSibling("FreeMinecraftModels.zip.pending");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pending))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            try (Stream<Path> paths = Files.walk(packRoot)) {
                List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();
                for (Path file : files) {
                    String name = packRoot.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    ZipEntry entry = new ZipEntry(name);
                    // Stable timestamps keep RSPM's pack hash/cache stable between boots.
                    entry.setTime(315532800000L);
                    zip.putNextEntry(entry);
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException failure) {
            Files.deleteIfExists(pending);
            throw failure;
        }
        try {
            Files.move(pending, published, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(pending, published, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFmmReloaded(FmmReloadedEvent event) {
        if (!plugin.isEnabled() || reloadFailed) return;
        fmmReady = false;
        active.clear();
        boolean complete = MODEL_IDS.values().stream().allMatch(ModeledEntityManager::modelExists);
        if (!complete || modelAssetsChanged) {
            if (!reloadRequested) {
                reloadRequested = true;
                modelAssetsChanged = false;
                plugin.getLogger().info("FMM model files are new or changed; requesting one model reload.");
                Bukkit.getScheduler().runTask(plugin, ModeledEntityManager::reload);
            } else {
                reloadFailed = true;
                plugin.getLogger().severe("FreeMinecraftModels reloaded but one or more Dead Streets models are still missing. Native zombies remain visible; special model rendering is disabled.");
            }
            return;
        }
        beginPackPatch();
    }

    /** Rewrites and zips the complete FMM pack off-thread, then publishes it on the server thread. */
    private void beginPackPatch() {
        if (patchingPack) {
            patchPending = true;
            return;
        }
        patchingPack = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String failureMessage = null;
            try {
                patchVanillaTextureReferences();
            } catch (IOException failure) {
                failureMessage = failure.getMessage();
            }
            final String result = failureMessage;
            Bukkit.getScheduler().runTask(plugin, () -> {
                patchingPack = false;
                if (!plugin.isEnabled()) return;
                if (patchPending) {
                    patchPending = false;
                    beginPackPatch();
                    return;
                }
                if (result != null) {
                    reloadFailed = true;
                    plugin.getLogger().severe("Could not republish FMM models with vanilla client textures: " + result);
                    return;
                }
                boolean rspmReloaded = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rspm reload");
                if (!rspmReloaded) {
                    reloadFailed = true;
                    plugin.getLogger().severe("ResourcePackManager did not accept /rspm reload; native infected will remain visible.");
                    return;
                }
                fmmReady = true;
                reloadRequested = false;
                plugin.getLogger().info("FreeMinecraftModels loaded all four Dead Streets models; vanilla-textured pack republished through RSPM.");
                plugin.refreshLoadedZombies();
                if (Boolean.getBoolean("deadstreets.model-smoke")) selfTest();
            });
        });
    }

    private void selfTest() {
        if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("No loaded world for FMM model self-test");
        var world = Bukkit.getWorlds().getFirst();
        var base = world.getSpawnLocation().clone().add(0, 1, 0);
        int index = 0;
        for (Map.Entry<String, String> entry : MODEL_IDS.entrySet()) {
            var at = base.clone().add(index++ * 2.5, 0, 0);
            Zombie dummy = world.spawn(at, Zombie.class, zombie -> {
                zombie.setAI(false);
                zombie.setSilent(true);
                zombie.setInvulnerable(true);
            });
            PersistentDynamicEntity model = null;
            try {
                AppearanceState appearance = new AppearanceState(dummy.isInvisible(), dummy.isVisibleByDefault(), dummy.isGlowing());
                model = new PersistentDynamicEntity(entry.getValue(), dummy, fmmPlugin,
                        appearance, originalInvisibleKey, originalVisibleKey, originalGlowingKey, fmmDynamicKey);
                model.spawn(dummy);
                model.hideBackingEntity();
                String movement = switch (entry.getKey()) {
                    case "runner_husk" -> "run";
                    case "crawler_lowprofile" -> "crawl";
                    default -> "walk";
                };
                if (!model.hasAnimation("idle") || !model.hasAnimation(movement)
                        || !model.hasAnimation("damaged") || !model.hasAnimation("attack")
                        || !model.hasAnimation("death")) {
                    throw new IllegalStateException("Model " + entry.getValue()
                            + " is missing a required movement/combat/death animation.");
                }
                if (entry.getKey().equals("popper_drowned") && !model.hasAnimation("inflate")) {
                    throw new IllegalStateException("Popper is missing its inflate animation.");
                }
                if (entry.getKey().equals("hulk_brute") && !model.hasAnimation("roar")) {
                    throw new IllegalStateException("Hulk is missing its roar animation.");
                }
                if (entry.getKey().equals("crawler_lowprofile") && !model.hasAnimation("lunge")) {
                    throw new IllegalStateException("Crawler is missing its lunge animation.");
                }
                model.setClickSurface(true);
                model.setDamagesOnContact(false);
                model.playAnimation("damaged", true, false);
                model.playAnimation("attack", true, false);
            } finally {
                if (model != null && !model.isRemoved()) {
                    model.detachKeepingZombie();
                    if (!dummy.isValid() || dummy.isInvisible() || !dummy.isVisibleByDefault()
                            || dummy.getHeight() < 1.5) {
                        throw new IllegalStateException("FMM detach did not preserve and restore the native zombie driver for " + entry.getValue());
                    }
                }
                if (dummy.isValid()) dummy.remove();
            }
        }
        plugin.getLogger().info("FMM runtime smoke test passed: four textured models, idle/attack/damage/death states, entity attachment and click hitboxes.");
    }

    void attachSoon(Zombie zombie) {
        if (zombie == null || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> attach(zombie));
    }

    private AppearanceState rememberAppearance(Zombie zombie) {
        var pdc = zombie.getPersistentDataContainer();
        if (!pdc.has(originalInvisibleKey, PersistentDataType.BYTE)) {
            pdc.set(originalInvisibleKey, PersistentDataType.BYTE, (byte) (zombie.isInvisible() ? 1 : 0));
        }
        if (!pdc.has(originalVisibleKey, PersistentDataType.BYTE)) {
            pdc.set(originalVisibleKey, PersistentDataType.BYTE, (byte) (zombie.isVisibleByDefault() ? 1 : 0));
        }
        if (!pdc.has(originalGlowingKey, PersistentDataType.BYTE)) {
            pdc.set(originalGlowingKey, PersistentDataType.BYTE, (byte) (zombie.isGlowing() ? 1 : 0));
        }
        return new AppearanceState(
                pdc.getOrDefault(originalInvisibleKey, PersistentDataType.BYTE, (byte) 0) != 0,
                pdc.getOrDefault(originalVisibleKey, PersistentDataType.BYTE, (byte) 1) != 0,
                pdc.getOrDefault(originalGlowingKey, PersistentDataType.BYTE, (byte) 0) != 0);
    }

    private void restoreVanillaBodyIfMarked(Zombie zombie) {
        var pdc = zombie.getPersistentDataContainer();
        if (!pdc.has(originalInvisibleKey, PersistentDataType.BYTE)
                && !pdc.has(originalVisibleKey, PersistentDataType.BYTE)
                && !pdc.has(originalGlowingKey, PersistentDataType.BYTE)) return;
        boolean invisible = pdc.getOrDefault(originalInvisibleKey, PersistentDataType.BYTE, (byte) 0) != 0;
        boolean visible = pdc.getOrDefault(originalVisibleKey, PersistentDataType.BYTE, (byte) 1) != 0;
        boolean glowing = pdc.getOrDefault(originalGlowingKey, PersistentDataType.BYTE, (byte) 0) != 0;
        zombie.setVisibleByDefault(visible);
        zombie.setInvisible(invisible);
        zombie.setGlowing(glowing);
        zombie.setPose(zombie.getPose(), true);
        pdc.remove(originalInvisibleKey);
        pdc.remove(originalVisibleKey);
        pdc.remove(originalGlowingKey);
        pdc.remove(fmmDynamicKey);
        for (Player player : zombie.getWorld().getPlayers()) player.showEntity(fmmPlugin, zombie);
    }

    void attach(Zombie zombie) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) return;
        if (!fmmReady) {
            // A crash may have saved the driver's hidden flag before FMM could
            // rebuild its model. Restore the vanilla body until FMM is ready.
            restoreVanillaBodyIfMarked(zombie);
            return;
        }
        String type = plugin.specialVisualType(zombie);
        if (type == null) return;
        String modelId = MODEL_IDS.get(type);
        if (modelId == null) return;

        Active existing = active.get(zombie.getUniqueId());
        if (existing != null && existing.type.equals(type) && existing.model.isAttachedTo(zombie, modelId)) return;

        DynamicEntity previous = DynamicEntity.getDynamicEntity(zombie);
        if (previous instanceof PersistentDynamicEntity persistent) {
            if (persistent.isAttachedTo(zombie, modelId)) {
                Active visual = new Active(zombie, type, persistent);
                active.put(zombie.getUniqueId(), visual);
                restorePopperInflation(visual);
                for (Player player : zombie.getWorld().getPlayers()) applyPlayerVisibility(player);
                return;
            }
            persistent.detachKeepingZombie();
        } else if (previous != null) {
            plugin.getLogger().warning("Skipped " + zombie.getUniqueId()
                    + ": another plugin already owns its FMM model attachment.");
            return;
        }

        AppearanceState appearance = rememberAppearance(zombie);
        PersistentDynamicEntity model = new PersistentDynamicEntity(modelId, zombie, fmmPlugin,
                appearance, originalInvisibleKey, originalVisibleKey, originalGlowingKey, fmmDynamicKey);
        try {
            model.spawn(zombie);
            model.hideBackingEntity();
            model.setClickSurface(true);
            model.setDamagesOnContact(false);
            String walk = switch (type) {
                case "runner_husk" -> "run";
                case "crawler_lowprofile" -> "crawl";
                default -> "walk";
            };
            model.getAnimationComponent().setWalkAnimation(walk);
            Active visual = new Active(zombie, type, model);
            active.put(zombie.getUniqueId(), visual);
            restorePopperInflation(visual);
            for (Player player : zombie.getWorld().getPlayers()) applyPlayerVisibility(player);
        } catch (RuntimeException | Error failure) {
            if (!model.isRemoved()) model.detachKeepingZombie();
            plugin.getLogger().severe("Could not attach FMM model " + modelId + ": " + failure.getMessage());
            throw failure;
        }
    }

    private void restorePopperInflation(Active visual) {
        if (!visual.type.equals("popper_drowned") || visual.inflating) return;
        AttributeInstance maxHealth = visual.zombie.getAttribute(Attribute.MAX_HEALTH);
        double threshold = (maxHealth == null ? 32.0 : maxHealth.getValue()) * 0.4;
        if (visual.zombie.getHealth() <= threshold && visual.model.hasAnimation("inflate")) {
            visual.model.playAnimation("inflate", true, false);
            visual.inflating = true;
        }
    }

    void attack(Zombie zombie) {
        Active visual = active.get(zombie.getUniqueId());
        if (visual == null || visual.dying || visual.model.isRemoved()) return;
        visual.model.playAnimation("attack", true, false);
    }

    void onDamage(Zombie zombie, EntityDamageEvent event) {
        Active visual = active.get(zombie.getUniqueId());
        if (visual == null || visual.dying || visual.model.isRemoved()) return;
        visual.model.getSkeleton().tint();
        visual.model.playAnimation("damaged", true, false);
        restorePopperInflation(visual);
    }

    void onDeath(Zombie zombie) {
        Active visual = active.get(zombie.getUniqueId());
        if (visual == null || visual.dying) return;
        // FMM's own EntityDeathEvent listener starts removeWithDeathAnimation().
        visual.dying = true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean ready = player.getPersistentDataContainer()
                .getOrDefault(packReadyKey, PersistentDataType.BYTE, (byte) 0) != 0;
        packReadyByPlayer.put(player.getUniqueId(), ready);
        applyPlayerVisibility(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        boolean ready = event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        packReadyByPlayer.put(player.getUniqueId(), ready);
        player.getPersistentDataContainer().set(packReadyKey, PersistentDataType.BYTE, (byte) (ready ? 1 : 0));
        applyPlayerVisibility(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        packReadyByPlayer.remove(event.getPlayer().getUniqueId());
    }

    private void applyPlayerVisibility(Player player) {
        if (player == null || !player.isOnline()) return;
        boolean ready = packReadyByPlayer.computeIfAbsent(player.getUniqueId(), ignored ->
                player.getPersistentDataContainer().getOrDefault(packReadyKey, PersistentDataType.BYTE, (byte) 0) != 0);
        for (Active visual : active.values()) {
            if (visual.zombie.getWorld() != player.getWorld() || !visual.zombie.isValid() || visual.model.isRemoved()) continue;
            if (ready) {
                visual.model.showTo(player);
                visual.model.hideUnderlyingEntity(player);
                visual.model.restoreOriginalGlow();
            } else {
                visual.model.hideFrom(player);
                visual.model.showUnderlyingEntity(player);
                visual.model.restoreOriginalGlow();
            }
        }
    }

    void refreshPlayerVisibility(Player player) {
        applyPlayerVisibility(player);
    }

    void onChunkUnload(Chunk chunk) {
        Iterator<Map.Entry<UUID, Active>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Active visual = iterator.next().getValue();
            if (visual.zombie.getWorld().getUID().equals(chunk.getWorld().getUID())
                    && (visual.zombie.getLocation().getBlockX() >> 4) == chunk.getX()
                    && (visual.zombie.getLocation().getBlockZ() >> 4) == chunk.getZ()) {
                iterator.remove();
            }
        }
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Active>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Active visual = iterator.next().getValue();
            if (!visual.zombie.isValid() || visual.zombie.isDead()) {
                iterator.remove();
                continue;
            }
            if (visual.model.isRemoved()) {
                visual.model.detachKeepingZombie();
                iterator.remove();
                continue;
            }
            if (visual.type.equals("hulk_brute") && !visual.roared && visual.model.hasAnimation("roar")) {
                for (Player player : visual.zombie.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(visual.zombie.getLocation()) <= 144.0) {
                        visual.model.playAnimation("roar", true, false);
                        visual.roared = true;
                        break;
                    }
                }
            }
        }
    }

    void stop() {
        if (task != null) task.cancel();
        for (Active visual : active.values()) {
            visual.model.detachKeepingZombie();
        }
        active.clear();
        packReadyByPlayer.clear();
    }

    int activeCount() {
        return active.size();
    }
}
