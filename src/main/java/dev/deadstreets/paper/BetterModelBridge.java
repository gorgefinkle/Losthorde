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
    private final java.util.Set<UUID> packSentPlayers = new java.util.HashSet<>();
    private static final UUID DEAD_STREETS_PACK_ID = UUID.fromString("a2b8fef3-3a90-4f15-9a9b-1e4c0b8d1919");

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
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!assetsLoaded()) {
                            if (Boolean.getBoolean("deadstreets.bettermodel-smoke"))
                                throw new IllegalStateException("BetterModel did not import both Dead Streets Blockbench models.");
                            plugin.getLogger().severe("BetterModel did not register both Dead Streets model keys after reload.");
                        } else {
                            try {
                                String packReport = auditGeneratedPack(dependency);
                                plugin.getLogger().info("BetterModel generated-pack audit passed: " + packReport);
                                packReady = true;
                                plugin.getLogger().info("BetterModel generated pack is ready for automatic client delivery: " + packReport);
                                for (Player online : Bukkit.getOnlinePlayers()) {
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> sendModelResourcePack(online), 40L);
                                }
                                if (Boolean.getBoolean("deadstreets.bettermodel-smoke"))
                                    plugin.getLogger().info("BetterModel asset smoke test passed: both models registered and generated pack contains their texture assets.");
                            } catch (IOException failure) {
                                if (Boolean.getBoolean("deadstreets.bettermodel-smoke"))
                                    throw new IllegalStateException("BetterModel generated resource-pack audit failed: " + failure.getMessage(), failure);
                                plugin.getLogger().severe("BetterModel generated resource-pack audit failed: " + failure.getMessage());
                            }
                        }
                        plugin.refreshLoadedZombies();
                    }, 80L);
                }
            }, 20L);
            plugin.getLogger().info("Installed Dead Streets Blockbench assets into BetterModel/models; requesting a resource-pack reload.");
        } catch (IOException failure) {
            plugin.getLogger().severe("Could not install BetterModel assets: " + failure.getMessage());
        }
    }


    private String auditGeneratedPack(Plugin dependency) throws IOException {
        Path data = dependency.getDataFolder().toPath();
        Path zip = data.resolve("build.zip");
        Path folder = data.resolve("build");
        long pngCount = 0;
        boolean hasPackMeta = false;
        if (Files.isRegularFile(zip)) {
            try (ZipFile archive = new ZipFile(zip.toFile())) {
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.endsWith(".png")) pngCount++;
                    if (name.equals("pack.mcmeta")) hasPackMeta = true;
                }
            }
            String digest;
            try {
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(zip)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-1 is unavailable", impossible);
            }
            if (!hasPackMeta || pngCount < 2) {
                throw new IOException("BetterModel build.zip is incomplete: pack.mcmeta=" + hasPackMeta
                        + ", PNG assets=" + pngCount + ", path=" + zip);
            }
            return "path=" + zip + ", PNG assets=" + pngCount + ", SHA-1=" + digest
                    + ", bytes=" + Files.size(zip);
        }
        if (Files.isDirectory(folder)) {
            try (var files = Files.walk(folder)) {
                var paths = files.filter(Files::isRegularFile).toList();
                pngCount = paths.stream().filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")).count();
                hasPackMeta = paths.stream().anyMatch(p -> p.getFileName().toString().equals("pack.mcmeta"));
            }
            if (!hasPackMeta || pngCount < 2) {
                throw new IOException("BetterModel build folder is incomplete: pack.mcmeta=" + hasPackMeta
                        + ", PNG assets=" + pngCount + ", path=" + folder);
            }
            return "path=" + folder + ", PNG assets=" + pngCount + ", pack.mcmeta present";
        }
        throw new IOException("No generated BetterModel pack found at " + zip + " or " + folder);
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
            plugin.getLogger().severe("Dead Streets model resource pack URL is missing or is not HTTPS; custom infected textures cannot be delivered.");
            return;
        }
        if (!packSentPlayers.add(player.getUniqueId())) return;
        try {
            // Additive Paper API keeps existing packs (including QualityArmory) active.
            player.addResourcePack(DEAD_STREETS_PACK_ID, url, null, "Dead Streets: infected model textures", false);
            plugin.getLogger().info("Requested the Dead Streets model resource pack for " + player.getName() + ".");
        } catch (IllegalArgumentException failure) {
            packSentPlayers.remove(player.getUniqueId());
            plugin.getLogger().severe("Could not request the Dead Streets model resource pack for " + player.getName() + ": " + failure.getMessage());
        }
    }

    void forgetPackPlayer(UUID playerId) {
        packSentPlayers.remove(playerId);
    }

    private void showToCurrentViewers(Object tracker, Zombie zombie) {
        for (Player player : zombie.getWorld().getPlayers()) showToPlayer(tracker, player);
    }

    private void showToPlayer(Object tracker, Player player) {
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
