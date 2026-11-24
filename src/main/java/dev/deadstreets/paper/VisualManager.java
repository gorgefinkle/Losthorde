package dev.deadstreets.paper;

import com.magmaguy.freeminecraftmodels.api.FmmReloadedEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

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

    /**
     * FMM normally removes a DynamicEntity's backing entity when FMM itself
     * shuts down. That is correct for FMM-owned creatures but not for Dead
     * Streets' persistent zombie drivers. This subclass keeps the zombie and
     * restores its vanilla visibility whenever FMM reloads or Dead Streets
     * detaches the visual.
     */
    private record AppearanceState(boolean invisible, boolean visibleByDefault) {}

    private static final class PersistentDynamicEntity extends DynamicEntity {
        private final Zombie zombie;
        private final Plugin fmmPlugin;
        private final boolean originalInvisible;
        private final boolean originalVisibleByDefault;
        private final String modelId;
        private final NamespacedKey originalInvisibleKey;
        private final NamespacedKey originalVisibleKey;

        PersistentDynamicEntity(String modelId, Zombie zombie, Plugin fmmPlugin,
                                AppearanceState appearance, NamespacedKey originalInvisibleKey,
                                NamespacedKey originalVisibleKey) {
            super(modelId, zombie.getLocation());
            this.modelId = modelId;
            this.zombie = zombie;
            this.fmmPlugin = fmmPlugin;
            this.originalInvisible = appearance.invisible();
            this.originalVisibleByDefault = appearance.visibleByDefault();
            this.originalInvisibleKey = originalInvisibleKey;
            this.originalVisibleKey = originalVisibleKey;
        }

        boolean isAttachedTo(Zombie candidate, String expectedModelId) {
            return zombie == candidate && modelId.equals(expectedModelId)
                    && !isRemoved() && getUnderlyingEntity() == candidate;
        }

        void hideBackingEntity() {
            boolean bedrockUsesUnderlying = getBedrockModeledEntity() != null
                    && getBedrockModeledEntity().isAvailable()
                    && getBedrockModeledEntity().isUsingUnderlyingEntity();
            zombie.setVisibleByDefault(false);
            zombie.setInvisible(!bedrockUsesUnderlying);
            for (Player player : zombie.getWorld().getPlayers()) {
                player.hideEntity(fmmPlugin, zombie);
            }
        }

        private void restoreBackingEntity() {
            if (!zombie.isValid()) return;
            zombie.setVisibleByDefault(originalVisibleByDefault);
            zombie.setInvisible(originalInvisible);
            // FMM applies the model hitbox directly to the backing mob. A pose
            // refresh asks Paper to rebuild the vanilla box after detaching.
            zombie.setPose(zombie.getPose(), true);
            zombie.getPersistentDataContainer().remove(originalInvisibleKey);
            zombie.getPersistentDataContainer().remove(originalVisibleKey);
            for (Player player : zombie.getWorld().getPlayers()) {
                player.showEntity(fmmPlugin, zombie);
            }
        }

        void detachKeepingZombie() {
            if (!isRemoved()) {
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
    private Plugin fmmPlugin;
    private BukkitTask task;
    private boolean fmmReady;
    private boolean reloadRequested;
    private boolean reloadFailed;
    private boolean modelAssetsChanged;

    VisualManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        this.originalInvisibleKey = new NamespacedKey(plugin, "fmm_original_invisible");
        this.originalVisibleKey = new NamespacedKey(plugin, "fmm_original_visible_by_default");
    }

    void start() {
        fmmPlugin = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels");
        if (fmmPlugin == null || !fmmPlugin.isEnabled()) {
            throw new IllegalStateException("FreeMinecraftModels is required for Dead Streets infected models.");
        }
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

        // FMM's shutdown hook calls PersistentDynamicEntity.shutdownRemove(),
        // which detaches the old model but deliberately preserves each zombie.
        active.clear();
        fmmReady = true;
        reloadRequested = false;
        plugin.getLogger().info("FreeMinecraftModels loaded all four Dead Streets models.");
        plugin.refreshLoadedZombies();
        if (Boolean.getBoolean("deadstreets.model-smoke")) selfTest();
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
                AppearanceState appearance = new AppearanceState(dummy.isInvisible(), dummy.isVisibleByDefault());
                model = new PersistentDynamicEntity(entry.getValue(), dummy, fmmPlugin,
                        appearance, originalInvisibleKey, originalVisibleKey);
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
                if (model != null && !model.isRemoved()) model.remove();
                else if (dummy.isValid()) dummy.remove();
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
        return new AppearanceState(
                pdc.getOrDefault(originalInvisibleKey, PersistentDataType.BYTE, (byte) 0) != 0,
                pdc.getOrDefault(originalVisibleKey, PersistentDataType.BYTE, (byte) 1) != 0);
    }

    private void restoreVanillaBodyIfMarked(Zombie zombie) {
        var pdc = zombie.getPersistentDataContainer();
        if (!pdc.has(originalInvisibleKey, PersistentDataType.BYTE)
                && !pdc.has(originalVisibleKey, PersistentDataType.BYTE)) return;
        boolean invisible = pdc.getOrDefault(originalInvisibleKey, PersistentDataType.BYTE, (byte) 0) != 0;
        boolean visible = pdc.getOrDefault(originalVisibleKey, PersistentDataType.BYTE, (byte) 1) != 0;
        zombie.setVisibleByDefault(visible);
        zombie.setInvisible(invisible);
        zombie.setPose(zombie.getPose(), true);
        pdc.remove(originalInvisibleKey);
        pdc.remove(originalVisibleKey);
        for (Player player : zombie.getWorld().getPlayers()) player.showEntity(fmmPlugin, zombie);
    }

    void attach(Zombie zombie) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) return;
        String type = plugin.specialVisualType(zombie);
        if (type == null) return;
        if (!fmmReady) {
            // A crash may have saved the driver's hidden flag before FMM could
            // rebuild its model. Restore the vanilla body until FMM is ready.
            restoreVanillaBodyIfMarked(zombie);
            return;
        }
        String modelId = MODEL_IDS.get(type);
        if (modelId == null) return;

        Active existing = active.get(zombie.getUniqueId());
        if (existing != null && existing.type.equals(type) && existing.model.isAttachedTo(zombie, modelId)) return;

        DynamicEntity previous = DynamicEntity.getDynamicEntity(zombie);
        if (previous instanceof PersistentDynamicEntity persistent) {
            if (persistent.isAttachedTo(zombie, modelId)) {
                active.put(zombie.getUniqueId(), new Active(zombie, type, persistent));
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
                appearance, originalInvisibleKey, originalVisibleKey);
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
            active.put(zombie.getUniqueId(), new Active(zombie, type, model));
        } catch (RuntimeException | Error failure) {
            if (!model.isRemoved()) model.detachKeepingZombie();
            plugin.getLogger().severe("Could not attach FMM model " + modelId + ": " + failure.getMessage());
            throw failure;
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
    }

    void onDeath(Zombie zombie) {
        Active visual = active.get(zombie.getUniqueId());
        if (visual == null || visual.dying) return;
        // FMM's own EntityDeathEvent listener starts removeWithDeathAnimation().
        visual.dying = true;
    }

    void hideFrom(Player player) {
        if (fmmPlugin == null || player == null || !player.isOnline()) return;
        for (Active visual : active.values()) {
            if (visual.zombie.getWorld() == player.getWorld() && visual.zombie.isValid()) {
                player.hideEntity(fmmPlugin, visual.zombie);
            }
        }
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
            if (!visual.zombie.isValid() || visual.zombie.isDead() || visual.model.isRemoved()) {
                iterator.remove();
            }
        }
    }

    void stop() {
        if (task != null) task.cancel();
        for (Active visual : active.values()) {
            visual.model.detachKeepingZombie();
        }
        active.clear();
    }

    int activeCount() {
        return active.size();
    }
}
