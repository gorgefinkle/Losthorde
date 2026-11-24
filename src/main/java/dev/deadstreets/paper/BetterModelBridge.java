package dev.deadstreets.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Optional BetterModel 3.x bridge. Reflection keeps Paper-only CI and fallback servers loadable. */
final class BetterModelBridge {
    private final DeadStreetsPlugin plugin;
    private final Map<UUID, Object> trackers = new HashMap<>();
    private boolean warnedMissing;
    private boolean warnedApi;

    BetterModelBridge(DeadStreetsPlugin plugin) { this.plugin = plugin; }

    void installAssetsAndReload() {
        if (!plugin.getConfig().getBoolean("models.bettermodel.enabled", true)) return;
        Plugin dependency = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (dependency == null || !dependency.isEnabled()) {
            warnMissing("BetterModel is not installed/enabled; special infected will use their vanilla fallback appearance.");
            return;
        }
        try {
            Path models = dependency.getDataFolder().toPath().resolve("models");
            Files.createDirectories(models);
            copyIfDifferent("bettermodel/models/deadstreets_crawler.bbmodel", models.resolve("deadstreets_crawler.bbmodel"));
            copyIfDifferent("bettermodel/models/deadstreets_hulk.bbmodel", models.resolve("deadstreets_hulk.bbmodel"));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
                    boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bettermodel reload");
                    if (!accepted) plugin.getLogger().warning("Could not dispatch BetterModel reload; run /bettermodel reload once.");
                    Bukkit.getScheduler().runTaskLater(plugin, plugin::refreshLoadedZombies, 80L);
                }
            }, 20L);
            plugin.getLogger().info("Installed Dead Streets Blockbench assets into BetterModel/models; requesting a resource-pack reload.");
        } catch (IOException failure) {
            plugin.getLogger().severe("Could not install BetterModel assets: " + failure.getMessage());
        }
    }

    private void copyIfDifferent(String resource, Path destination) throws IOException {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) throw new IOException("Missing embedded asset " + resource);
            byte[] incoming = in.readAllBytes();
            if (Files.exists(destination) && java.util.Arrays.equals(Files.readAllBytes(destination), incoming)) return;
            Files.createDirectories(destination.getParent());
            Files.copy(new java.io.ByteArrayInputStream(incoming), destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    boolean attach(Zombie zombie, String modelName, String animation) {
        if (!plugin.getConfig().getBoolean("models.bettermodel.enabled", true)) return false;
        Object existing = trackers.get(zombie.getUniqueId());
        if (existing != null) {
            hideBase(zombie);
            return true;
        }
        try {
            Class<?> api = Class.forName("kr.toxicity.model.api.BetterModel");
            Object result = api.getMethod("model", String.class).invoke(null, modelName);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) return false;
            Object renderer = optional.get();
            Class<?> adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            Method adapt = null;
            for (Method candidate : adapterClass.getMethods()) {
                if (candidate.getName().equals("adapt") && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isAssignableFrom(zombie.getClass())) {
                    adapt = candidate;
                    break;
                }
            }
            if (adapt == null) throw new NoSuchMethodException("BukkitAdapter.adapt(LivingEntity)");
            Object platformEntity = adapt.invoke(null, zombie);
            Method getOrCreate = null;
            for (Method candidate : renderer.getClass().getMethods()) {
                if (candidate.getName().equals("getOrCreate") && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isInstance(platformEntity)) {
                    getOrCreate = candidate;
                    break;
                }
            }
            if (getOrCreate == null) throw new NoSuchMethodException("ModelRenderer.getOrCreate(PlatformEntity)");
            Object tracker = getOrCreate.invoke(renderer, platformEntity);
            try {
                Class<?> scalerClass = Class.forName("kr.toxicity.model.api.tracker.ModelScaler");
                Object entityScaler = scalerClass.getMethod("entity").invoke(null);
                tracker.getClass().getMethod("scaler", scalerClass).invoke(tracker, entityScaler);
            } catch (ReflectiveOperationException ignored) {
                // Older BetterModel API builds may not expose entity scaling.
            }
            try { tracker.getClass().getMethod("animate", String.class).invoke(tracker, animation); }
            catch (ReflectiveOperationException missingAnimation) {
                plugin.getLogger().warning("BetterModel asset '" + modelName + "' loaded but animation '" + animation + "' could not be started.");
            }
            trackers.put(zombie.getUniqueId(), tracker);
            hideBase(zombie);
            return true;
        } catch (ClassNotFoundException absent) {
            if (!warnedApi) {
                warnedApi = true;
                plugin.getLogger().warning("BetterModel is enabled but its 3.x Bukkit API is unavailable; vanilla fallback remains visible.");
            }
            return false;
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().warning("BetterModel attachment failed for " + zombie.getUniqueId() + ": " + failure.getMessage());
            return false;
        }
    }

    void refreshPlayer(Player player) {
        for (UUID id : trackers.keySet()) {
            var entity = Bukkit.getEntity(id);
            if (entity instanceof Zombie zombie && zombie.isValid()) player.hideEntity(plugin, zombie);
        }
    }

    private void hideBase(Zombie zombie) {
        for (Player player : zombie.getWorld().getPlayers()) player.hideEntity(plugin, zombie);
    }

    void detachAll() {
        for (Map.Entry<UUID, Object> entry : trackers.entrySet()) {
            Object tracker = entry.getValue();
            try { tracker.getClass().getMethod("close").invoke(tracker); }
            catch (ReflectiveOperationException ignored) {}
            var entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Zombie zombie) for (Player player : Bukkit.getOnlinePlayers()) player.showEntity(plugin, zombie);
        }
        trackers.clear();
    }

    private void warnMissing(String message) {
        if (warnedMissing) return;
        warnedMissing = true;
        plugin.getLogger().warning(message);
    }
}
