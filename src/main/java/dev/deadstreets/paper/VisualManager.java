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
import org.bukkit.Chunk;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final class PersistentDynamicEntity extends DynamicEntity {
        private final Zombie zombie;
        private final Plugin fmmPlugin;
        private final boolean originalInvisible;
        private final boolean originalVisibleByDefault;
        private final String modelId;

        PersistentDynamicEntity(String modelId, Zombie zombie, Plugin fmmPlugin) {
            super(modelId, zombie.getLocation());
            this.modelId = modelId;
            this.zombie = zombie;
            this.fmmPlugin = fmmPlugin;
            this.originalInvisible = zombie.isInvisible();
            this.originalVisibleByDefault = zombie.isVisibleByDefault();
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
            for (Player player : zombie.getWorld().getPlayers()) {
                player.showEntity(fmmPlugin, zombie);
            }
        }

        void detachKeepingZombie() {
            if (isRemoved()) {
                restoreBackingEntity();
                return;
            }
            restoreBackingEntity();
            // Protected FMM lifecycle hook, exposed only for this persistent
            // driver integration. Unlike remove(), this never despawns the mob.
            super.removeKeepingBody();
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
    private Plugin fmmPlugin;
    private BukkitTask task;
    private boolean fmmReady;
    private boolean reloadRequested;
    private boolean reloadFailed;

    VisualManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
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
        for (String file : MODEL_FILES) {
            String resource = "fmm/" + file;
            Path target = destination.resolve(file);
            try (InputStream input = plugin.getResource(resource)) {
                if (input == null) throw new IOException("Missing embedded model resource " + resource);
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFmmReloaded(FmmReloadedEvent event) {
        if (!plugin.isEnabled() || reloadFailed) return;
        boolean complete = MODEL_IDS.values().stream().allMatch(ModeledEntityManager::modelExists);
        if (!complete) {
            if (!reloadRequested) {
                reloadRequested = true;
                plugin.getLogger().info("FMM has not imported Dead Streets models yet; requesting one model reload.");
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
                model = new PersistentDynamicEntity(entry.getValue(), dummy, fmmPlugin);
                model.spawn(dummy);
                model.hideBackingEntity();
                if (!model.hasAnimation("idle") || !model.hasAnimation("damaged")
                        || !model.hasAnimation("attack") || !model.hasAnimation("death")) {
                    throw new IllegalStateException("Model " + entry.getValue() + " is missing a required state animation.");
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

    void attach(Zombie zombie) {
        if (!fmmReady || zombie == null || !zombie.isValid() || zombie.isDead()) return;
        String type = plugin.specialVisualType(zombie);
        if (type == null) return;
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

        PersistentDynamicEntity model = new PersistentDynamicEntity(modelId, zombie, fmmPlugin);
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
