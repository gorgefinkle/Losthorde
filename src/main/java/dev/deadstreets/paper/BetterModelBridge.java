package dev.deadstreets.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
    private boolean packReady;
    private final BetterModelPackHook packHook;
    private final java.util.Set<UUID> packSentPlayers = new java.util.HashSet<>();
    private final java.util.Set<UUID> packLoadedPlayers = new java.util.HashSet<>();
    private static final UUID DEAD_STREETS_PACK_ID = UUID.fromString("b9c1d6e7-2f43-4b83-9a11-5d72e6c0a422");

    BetterModelBridge(DeadStreetsPlugin plugin) { this.plugin = plugin; this.packHook = new BetterModelPackHook(plugin); }

    void installAssetsAndReload() {
        if (!plugin.getConfig().getBoolean("models.bettermodel.enabled", true)) return;
        Plugin dependency = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (dependency == null || !dependency.isEnabled()) {
            warnMissing("BetterModel is not installed/enabled; special infected will use their vanilla fallback appearance.");
            return;
        }
        try {
            packHook.register();
            Path models = dependency.getDataFolder().toPath().resolve("models");
            Files.createDirectories(models);
            copyIfDifferent("bettermodel/models/deadstreets_crawler.bbmodel", models.resolve("deadstreets_crawler.bbmodel"));
            copyIfDifferent("bettermodel/models/deadstreets_hulk.bbmodel", models.resolve("deadstreets_hulk.bbmodel"));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) return;
                boolean firstReload = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bettermodel reload");
                if (!firstReload) plugin.getLogger().warning("Could not dispatch initial BetterModel reload.");
                // The first reload imports our Blockbench files. The second reload hook
                // can then rewrite the generated item models before BetterModel zips them.
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!assetsLoaded()) {
                        plugin.getLogger().severe("BetterModel did not register both Dead Streets model keys after initial reload.");
                        return;
                    }
                    boolean secondReload = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bettermodel reload");
                    if (!secondReload) plugin.getLogger().warning("Could not dispatch BetterModel pack-injection reload.");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            String report = packHook.auditGeneratedPack(dependency.getDataFolder().toPath().resolve("build.zip"));
                            packReady = true;
                            plugin.getLogger().info("BetterModel integrated texture-pack audit passed: " + report);
                            if (Boolean.getBoolean("deadstreets.bettermodel-smoke")) plugin.getLogger().info("BetterModel integrated pack smoke test passed: " + report);
                            plugin.getLogger().info("BetterModel model assets and texture alias are integrated in the generated server-delivered pack; no zombie PNG is shipped.");
                            for (Player online : Bukkit.getOnlinePlayers()) Bukkit.getScheduler().runTaskLater(plugin, () -> sendModelResourcePack(online), 20L);
                        } catch (IOException failure) {
                            plugin.getLogger().severe("BetterModel integrated pack audit failed: " + failure.getMessage());
                        }
                        plugin.refreshLoadedZombies();
                    }, 120L);
                }, 100L);
            }, 20L);
            plugin.getLogger().info("Installed Dead Streets Blockbench assets and registered BetterModel's native pack injection hook.");
        } catch (IOException | ReflectiveOperationException failure) {
            plugin.getLogger().severe("Could not initialize BetterModel integration: " + failure.getMessage());
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

    boolean assetsLoaded() {
        try {
            Class<?> api = Class.forName("kr.toxicity.model.api.BetterModel");
            Object keys = api.getMethod("modelKeys").invoke(null);
            return keys instanceof java.util.Set<?> set
                    && set.contains("deadstreets_crawler") && set.contains("deadstreets_hulk");
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    boolean attach(Zombie zombie, String modelName, String animation) {
        if (!plugin.getConfig().getBoolean("models.bettermodel.enabled", true)) return false;
        Object existing = trackers.get(zombie.getUniqueId());
        if (existing != null) {
            try {
                if (Boolean.TRUE.equals(existing.getClass().getMethod("isClosed").invoke(existing))) {
                    trackers.remove(zombie.getUniqueId());
                    existing = null;
                }
            } catch (ReflectiveOperationException ignored) {}
            if (existing != null) {
                showToCurrentViewers(existing, zombie);
                return true;
            }
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
            // BetterModel's own EntityHideOption hides the vanilla body. Do not call
            // Bukkit hideEntity here: the model tracker depends on the source spawn lifecycle.
            showToCurrentViewers(tracker, zombie);
            // Also retry on the next server tick, after Bukkit has completed the
            // source entity's spawn lifecycle for this tick (important for Popper offspring).
            UUID entityId = zombie.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (zombie.isValid() && trackers.get(entityId) == tracker) {
                    showToCurrentViewers(tracker, zombie);
                }
            });
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

    void forgetEntity(Zombie zombie) {
        // BetterModel owns death/unload animation and closes its tracker; only discard our handle.
        trackers.remove(zombie.getUniqueId());
    }

    void forgetChunk(org.bukkit.Chunk chunk) {
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (entity instanceof Zombie zombie) forgetEntity(zombie);
        }
    }

    void refreshPlayer(Player player) {
        if (!player.isOnline()) return;
        for (Map.Entry<UUID, Object> entry : trackers.entrySet()) {
            var entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Zombie zombie && zombie.isValid() && zombie.getWorld().equals(player.getWorld())) {
                showToPlayer(entry.getValue(), player);
            }
        }
    }

    void sendModelResourcePack(Player player) {
        if (!packReady || !player.isOnline() || !plugin.getConfig().getBoolean("models.bettermodel.resource-pack.enabled", true)) return;
        String url = plugin.getConfig().getString("models.bettermodel.resource-pack.url", "").trim();
        if (url.isEmpty() || !url.startsWith("https://")) {
            plugin.getLogger().severe("Integrated BetterModel model pack URL is missing or is not HTTPS.");
            return;
        }
        if (!packSentPlayers.add(player.getUniqueId())) return;
        try {
            player.addResourcePack(DEAD_STREETS_PACK_ID, url, null,
                    "Dead Streets infected geometry (uses your active zombie texture pack)", false);
            plugin.getLogger().info("Requested the integrated BetterModel pack for " + player.getName() + ".");
        } catch (IllegalArgumentException failure) {
            packSentPlayers.remove(player.getUniqueId());
            plugin.getLogger().severe("Could not request the integrated BetterModel pack for " + player.getName() + ": " + failure.getMessage());
        }
    }

    void handleResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!DEAD_STREETS_PACK_ID.equals(event.getID())) return;
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                packLoadedPlayers.add(player.getUniqueId());
                refreshPlayer(player);
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                if (packLoadedPlayers.remove(player.getUniqueId())) {
                    for (Object tracker : trackers.values()) hideFromPlayer(tracker, player);
                }
            }
            default -> { }
        }
    }

    void forgetPackPlayer(UUID playerId) {
        packSentPlayers.remove(playerId);
        packLoadedPlayers.remove(playerId);
    }

    private void showToCurrentViewers(Object tracker, Zombie zombie) {
        for (Player player : zombie.getWorld().getPlayers()) showToPlayer(tracker, player);
    }

    private void showToPlayer(Object tracker, Player player) {
        if (!packLoadedPlayers.contains(player.getUniqueId())) return;
        try {
            Class<?> adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            Method adaptPlayer = null;
            for (Method candidate : adapterClass.getMethods()) {
                if (candidate.getName().equals("adapt") && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == Player.class) {
                    adaptPlayer = candidate;
                    break;
                }
            }
            if (adaptPlayer == null) throw new NoSuchMethodException("BukkitAdapter.adapt(Player)");
            Object platformPlayer = adaptPlayer.invoke(null, player);
            Method show = null;
            for (Method candidate : tracker.getClass().getMethods()) {
                if (candidate.getName().equals("show") && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isInstance(platformPlayer)) {
                    show = candidate;
                    break;
                }
            }
            if (show == null) throw new NoSuchMethodException("Tracker.show(PlatformPlayer)");
            Object result = show.invoke(tracker, platformPlayer);
            if (Boolean.FALSE.equals(result)) {
                plugin.getLogger().warning("BetterModel declined to show a tracker to " + player.getName()
                        + "; check model spawn events and client resource-pack status.");
            }
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().warning("Could not send BetterModel tracker to " + player.getName() + ": " + failure.getMessage());
        }
    }

    private void hideFromPlayer(Object tracker, Player player) {
        try {
            Class<?> adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            Method adaptPlayer = null;
            for (Method candidate : adapterClass.getMethods()) {
                if (candidate.getName().equals("adapt") && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == Player.class) {
                    adaptPlayer = candidate;
                    break;
                }
            }
            if (adaptPlayer == null) throw new NoSuchMethodException("BukkitAdapter.adapt(Player)");
            Object platformPlayer = adaptPlayer.invoke(null, player);
            Method hide = null;
            for (Method candidate : tracker.getClass().getMethods()) {
                if (candidate.getName().equals("hide") && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isInstance(platformPlayer)) {
                    hide = candidate;
                    break;
                }
            }
            if (hide == null) throw new NoSuchMethodException("Tracker.hide(PlatformPlayer)");
            hide.invoke(tracker, platformPlayer);
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().warning("Could not restore vanilla infected rendering for " + player.getName()
                    + ": " + failure.getMessage());
        }
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
