package dev.deadstreets.paper;

import dev.deadstreets.core.Temperament;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Periodic, world-scoped Blood Moon event. Hordes remain ordinary persistent
 * shared HordeState groups; this manager only schedules and announces the waves.
 */
final class BloodMoonManager {
    private static final long TICKS_PER_DAY = 24_000L;
    private final DeadStreetsPlugin plugin;
    private final HordeManager hordes;
    private final Map<UUID, WorldEvent> worlds = new HashMap<>();
    private BukkitTask task;

    private static final class WorldEvent {
        long warnedDay = Long.MIN_VALUE;
        long activeDay = Long.MIN_VALUE;
        long nextWaveAt;
        int wavesSent;
        BossBar bar;
    }

    BloodMoonManager(DeadStreetsPlugin plugin, HordeManager hordes) {
        this.plugin = plugin;
        this.hordes = hordes;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("blood-moon.enabled", true)) return;
        for (World world : Bukkit.getWorlds()) worlds.put(world.getUID(), new WorldEvent());
        loadState();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (WorldEvent state : worlds.values()) if (state.bar != null) state.bar.removeAll();
        worlds.clear();
    }

    boolean shouldBlockSleep(World world) {
        long fullTime = world.getFullTime();
        long day = Math.floorDiv(fullTime, TICKS_PER_DAY);
        long dayTime = Math.floorMod(fullTime, TICKS_PER_DAY);
        int interval = Math.max(2, Math.min(30, plugin.getConfig().getInt("blood-moon.every-days", 7)));
        return Math.floorMod(day, interval) == 0 && dayTime >= 12_542L;
    }

    boolean active(World world) {
        WorldEvent state = worlds.get(world.getUID());
        return state != null && state.activeDay == Math.floorDiv(world.getFullTime(), TICKS_PER_DAY);
    }

    String status() {
        int active = 0;
        int waves = 0;
        for (WorldEvent state : worlds.values()) {
            if (state.activeDay != Long.MIN_VALUE) active++;
            waves += state.wavesSent;
        }
        return "blood-moons-active=" + active + ", waves-sent=" + waves;
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("blood-moon.enabled", true)) return;
        long now = System.currentTimeMillis();
        int interval = Math.max(2, Math.min(30, plugin.getConfig().getInt("blood-moon.every-days", 7)));
        int maxWaves = Math.max(1, Math.min(8, plugin.getConfig().getInt("blood-moon.waves", 3)));
        long waveInterval = Math.max(30, Math.min(300, plugin.getConfig().getLong("blood-moon.wave-interval-seconds", 90))) * 1000L;

        for (World world : Bukkit.getWorlds()) {
            WorldEvent state = worlds.computeIfAbsent(world.getUID(), ignored -> new WorldEvent());
            long fullTime = world.getFullTime();
            long day = Math.floorDiv(fullTime, TICKS_PER_DAY);
            long dayTime = Math.floorMod(fullTime, TICKS_PER_DAY);
            boolean eventDay = Math.floorMod(day, interval) == 0;

            if (eventDay && dayTime >= 6_000 && dayTime < 13_000 && state.warnedDay != day) {
                state.warnedDay = day;
                saveState();
                for (Player player : world.getPlayers()) {
                    player.sendTitle(ChatColor.RED + "BLOOD MOON APPROACHING",
                            ChatColor.DARK_RED + "Get inside. Nightfall is coming.",
                            10, 100, 30);
                    player.sendMessage(ChatColor.DARK_RED + "[Dead Streets] " + ChatColor.RED
                            + "A Blood Moon is approaching. Find shelter and secure your entrances.");
                }
            }

            if (eventDay && dayTime >= 13_000 && state.activeDay != day) {
                begin(world, state, day, now);
            }

            if (state.activeDay == day) {
                updateBar(world, state, maxWaves);
                if (state.wavesSent < maxWaves && now >= state.nextWaveAt) {
                    int created = sendWave(world, state);
                    if (created > 0) {
                        state.wavesSent++;
                        state.nextWaveAt = now + waveInterval;
                        saveState();
                    } else {
                        state.nextWaveAt = now + 15_000L;
                        saveState();
                    }
                }
                if (state.wavesSent >= maxWaves && dayTime < 13_000) end(world, state);
            } else if (state.activeDay != Long.MIN_VALUE && day != state.activeDay) {
                end(world, state);
            }
        }
    }

    private void begin(World world, WorldEvent state, long day, long now) {
        state.activeDay = day;
        state.wavesSent = 0;
        state.nextWaveAt = now;
        saveState();
        state.bar = Bukkit.createBossBar(ChatColor.DARK_RED + "BLOOD MOON  •  HORDES INBOUND",
                BarColor.RED, BarStyle.SEGMENTED_10);
        state.bar.setProgress(1.0);
        for (Player player : world.getPlayers()) {
            state.bar.addPlayer(player);
            player.sendTitle(ChatColor.DARK_RED + "BLOOD MOON",
                    ChatColor.RED + "Stay inside. The dead are coming.",
                    10, 80, 20);
            player.sendMessage(ChatColor.DARK_RED + "[Dead Streets] " + ChatColor.RED
                    + "The Blood Moon has begun. Lock down and survive the waves.");
        }
    }

    private int sendWave(World world, WorldEvent state) {
        if (world.getPlayers().isEmpty()) return 0;
        int groups = Math.max(1, Math.min(4, plugin.getConfig().getInt("blood-moon.groups-per-wave", 2)));
        int min = Math.max(30, Math.min(500, plugin.getConfig().getInt("blood-moon.group-min-population", 100)));
        int max = Math.max(min, Math.min(500, plugin.getConfig().getInt("blood-moon.group-max-population", 180)));
        int created = 0;
        for (int i = 0; i < groups; i++) {
            Player target = world.getPlayers().get(ThreadLocalRandom.current().nextInt(world.getPlayers().size()));
            try {
                int population = ThreadLocalRandom.current().nextInt(min, max + 1);
                Temperament temperament = ThreadLocalRandom.current().nextBoolean()
                        ? Temperament.AGITATED : Temperament.FRENZIED;
                hordes.spawnManual(world, target.getLocation().getX(), target.getLocation().getZ(),
                        population, temperament);
                created++;
            } catch (IllegalStateException noApproach) {
                plugin.getLogger().fine("Blood Moon wave could not find a safe horde approach: " + noApproach.getMessage());
            }
        }
        int wave = state.wavesSent + 1;
        for (Player player : world.getPlayers()) {
            player.sendTitle(ChatColor.RED + "HORDE WAVE " + wave,
                    ChatColor.DARK_RED + (created == 0 ? "No safe approach found" : created + " large groups detected"),
                    5, 65, 15);
            player.sendMessage(ChatColor.DARK_RED + "[Dead Streets] " + ChatColor.RED
                    + "Blood Moon wave " + wave + " of " + plugin.getConfig().getInt("blood-moon.waves", 3)
                    + " is moving in.");
        }
        return created;
    }

    private void updateBar(World world, WorldEvent state, int maxWaves) {
        if (state.bar == null) return;
        for (Player player : world.getPlayers()) if (!state.bar.getPlayers().contains(player)) state.bar.addPlayer(player);
        state.bar.setTitle(ChatColor.DARK_RED + "BLOOD MOON  •  WAVE " + Math.min(maxWaves, state.wavesSent)
                + "/" + maxWaves);
        state.bar.setProgress(Math.max(0.0, Math.min(1.0, 1.0 - (double) state.wavesSent / maxWaves)));
    }

    private void end(World world, WorldEvent state) {
        if (state.bar != null) {
            state.bar.removeAll();
            state.bar = null;
        }
        if (state.activeDay != Long.MIN_VALUE) {
            for (Player player : world.getPlayers()) {
                player.sendMessage(ChatColor.GRAY + "[Dead Streets] The Blood Moon has passed. The remaining dead are still out there.");
            }
        }
        state.activeDay = Long.MIN_VALUE;
        state.wavesSent = 0;
        saveState();
    }

    private Path stateFile() { return plugin.getDataFolder().toPath().resolve("blood-moon-state-v1.txt"); }

    private void loadState() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) return;
        try {
            int count = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (++count > 10_000) throw new IOException("Blood Moon state exceeds record limit");
                String[] p = line.split("\\|", -1);
                if (p.length != 5) continue;
                try {
                    UUID id = UUID.fromString(p[0]);
                    WorldEvent state = worlds.computeIfAbsent(id, ignored -> new WorldEvent());
                    state.warnedDay = Long.parseLong(p[1]);
                    state.activeDay = Long.parseLong(p[2]);
                    state.nextWaveAt = Long.parseLong(p[3]);
                    state.wavesSent = Math.max(0, Math.min(8, Integer.parseInt(p[4])));
                } catch (RuntimeException malformed) {
                    plugin.getLogger().warning("Ignoring malformed Blood Moon state record");
                }
            }
        } catch (IOException error) {
            plugin.getLogger().severe("Could not read Blood Moon state: " + error.getMessage());
        }
    }

    private void saveState() {
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("blood-moon-state-v1.txt.tmp");
            List<String> lines = new ArrayList<>();
            lines.add("# world UUID | warned day | active day | next wave epoch ms | waves sent");
            for (Map.Entry<UUID, WorldEvent> entry : worlds.entrySet()) {
                WorldEvent state = entry.getValue();
                lines.add(entry.getKey() + "|" + state.warnedDay + "|" + state.activeDay + "|"
                        + state.nextWaveAt + "|" + state.wavesSent);
            }
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            plugin.getLogger().severe("Could not save Blood Moon state: " + error.getMessage());
        }
    }
}
