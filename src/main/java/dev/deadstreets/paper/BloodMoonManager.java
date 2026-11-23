package dev.deadstreets.paper;

import dev.deadstreets.core.Temperament;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Periodic, world-scoped Blood Moon event. Hordes remain ordinary persistent
 * shared HordeState groups; this manager only schedules and announces the waves.
 */
final class BloodMoonManager {
    private static final long TICKS_PER_DAY = 24_000L;
    private static final long SUNRISE_TICK = 23_000L;

    private enum DisplayMode { BOSSBAR, SCOREBOARD }

    private final DeadStreetsPlugin plugin;
    private final HordeManager hordes;
    private final Map<UUID, WorldEvent> worlds = new HashMap<>();
    private static final UUID MOON_PACK_ID = UUID.fromString("7c95a6b4-1f2d-4c3b-9a10-6d5e2f8b4c71");
    private static final String DEFAULT_MOON_PACK_URL = "https://cdn.modrinth.com/data/p9sqIz3S/versions/M6uWxUGK/BloodMoon%20-%20Texture%281.21.9-1.21.11%29.zip";
    private final Set<UUID> moonPackPlayers = new HashSet<>();
    private BukkitTask task;

    private static final class WorldEvent {
        long warnedDay = Long.MIN_VALUE;
        long activeDay = Long.MIN_VALUE;
        long nextWaveAtWorldTime;
        long startsAtWorldTime;
        long endsAtWorldTime;
        int wavesSent;
        BossBar bar;

        Scoreboard scoreboard;
        Objective objective;
        final List<String> scoreboardEntries = new ArrayList<>();
        final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();
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
        for (Player player : Bukkit.getOnlinePlayers()) removeMoonPack(player);
        moonPackPlayers.clear();
        for (WorldEvent state : worlds.values()) clearDisplays(state);
        worlds.clear();
    }

    boolean shouldBlockSleep(World world) {
        if (!plugin.getConfig().getBoolean("blood-moon.enabled", true)) return false;
        long fullTime = world.getFullTime();
        long day = Math.floorDiv(fullTime, TICKS_PER_DAY);
        long dayTime = Math.floorMod(fullTime, TICKS_PER_DAY);
        int interval = intervalDays();
        boolean scheduledNight = day > 0 && Math.floorMod(day, interval) == 0
                && dayTime >= 12_542L && dayTime < SUNRISE_TICK;
        // Block sleep before the event starts, then only while the event is actually active.
        return scheduledNight && (dayTime <= 13_000L || active(world));
    }

    boolean active(World world) {
        WorldEvent state = worlds.get(world.getUID());
        return state != null && state.activeDay == Math.floorDiv(world.getFullTime(), TICKS_PER_DAY)
                && world.getFullTime() < state.endsAtWorldTime;
    }

    String status() {
        int active = 0;
        int waves = 0;
        for (WorldEvent state : worlds.values()) {
            if (state.activeDay != Long.MIN_VALUE) active++;
            waves += state.wavesSent;
        }
        return "blood-moons-active=" + active + ", waves-sent=" + waves
                + ", display=" + displayMode().name();
    }

    String displayModeName() {
        return displayMode().name();
    }

    boolean setDisplayMode(String requested) {
        DisplayMode selected;
        try {
            selected = DisplayMode.valueOf(requested.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        plugin.getConfig().set("blood-moon.display-mode", selected.name());
        plugin.saveConfig();
        refreshActiveDisplays();
        return true;
    }

    void onPlayerQuit(Player player) {
        moonPackPlayers.remove(player.getUniqueId());
        UUID id = player.getUniqueId();
        for (WorldEvent state : worlds.values()) {
            Scoreboard previous = state.previousScoreboards.remove(id);
            if (previous != null && state.scoreboard != null && player.getScoreboard() == state.scoreboard) {
                player.setScoreboard(previous);
            }
        }
    }

    private DisplayMode displayMode() {
        String configured = plugin.getConfig().getString("blood-moon.display-mode", "BOSSBAR");
        try {
            return DisplayMode.valueOf(configured == null ? "BOSSBAR" : configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Invalid blood-moon.display-mode '" + configured + "'; using BOSSBAR.");
            return DisplayMode.BOSSBAR;
        }
    }

    private int intervalDays() {
        return Math.max(2, Math.min(30, plugin.getConfig().getInt("blood-moon.every-days", 7)));
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("blood-moon.enabled", true)) {
            for (Player player : Bukkit.getOnlinePlayers()) removeMoonPack(player);
            return;
        }
        int interval = intervalDays();
        int maxWaves = Math.max(1, Math.min(8, plugin.getConfig().getInt("blood-moon.waves", 3)));
        long waveIntervalTicks = Math.max(30, Math.min(300,
                plugin.getConfig().getLong("blood-moon.wave-interval-seconds", 90))) * 20L;

        for (World world : Bukkit.getWorlds()) {
            WorldEvent state = worlds.computeIfAbsent(world.getUID(), ignored -> new WorldEvent());
            long fullTime = world.getFullTime();
            long day = Math.floorDiv(fullTime, TICKS_PER_DAY);
            long dayTime = Math.floorMod(fullTime, TICKS_PER_DAY);
            boolean eventDay = day > 0 && Math.floorMod(day, interval) == 0;
            if (state.activeDay != Long.MIN_VALUE) {
                long sunrise = state.activeDay * TICKS_PER_DAY + SUNRISE_TICK;
                if (state.endsAtWorldTime > sunrise) {
                    state.endsAtWorldTime = sunrise;
                    saveState();
                }
            }

            if (eventDay && dayTime >= 6_000L && dayTime < 13_000L && state.warnedDay != day) {
                state.warnedDay = day;
                saveState();
                for (Player player : world.getPlayers()) {
                    plugin.showTitleNotice(player, ChatColor.RED + "BLOOD MOON APPROACHING",
                            ChatColor.DARK_RED + "Secure shelter before nightfall.");
                }
            }

            if (eventDay && dayTime >= 13_000L && dayTime < SUNRISE_TICK
                    && state.activeDay != day) {
                begin(world, state, day);
            }

            boolean withinBloodMoon = state.activeDay == day
                    || (state.activeDay == day - 1 && state.endsAtWorldTime > fullTime);
            if (withinBloodMoon) {
                if (state.endsAtWorldTime <= 0L || fullTime >= state.endsAtWorldTime) {
                    end(world, state);
                    continue;
                }
                updateDisplay(world, state, maxWaves);
                if (state.wavesSent < maxWaves && fullTime >= state.nextWaveAtWorldTime) {
                    int created = sendWave(world, state);
                    if (created > 0) {
                        state.wavesSent++;
                        state.nextWaveAtWorldTime = fullTime + waveIntervalTicks;
                        saveState();
                    } else {
                        state.nextWaveAtWorldTime = fullTime + 300L;
                        saveState();
                    }
                }
            } else if (state.activeDay != Long.MIN_VALUE
                    && (state.endsAtWorldTime == 0L || fullTime >= state.endsAtWorldTime
                    || day > state.activeDay)) {
                end(world, state);
            }
        }
        syncMoonPackForOnlinePlayers();
    }

    private void syncMoonPackForOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            WorldEvent state = worlds.get(player.getWorld().getUID());
            boolean shouldHave = state != null && state.activeDay != Long.MIN_VALUE && player.getWorld().getFullTime() < state.endsAtWorldTime;
            if (shouldHave) applyMoonPack(player); else removeMoonPack(player);
        }
    }
    private void applyMoonPack(Player player) {
        if (!plugin.getConfig().getBoolean("blood-moon.moon-pack.enabled", true) || !moonPackPlayers.add(player.getUniqueId())) return;
        String url = plugin.getConfig().getString("blood-moon.moon-pack.url", DEFAULT_MOON_PACK_URL);
        if (url == null || url.isBlank()) { moonPackPlayers.remove(player.getUniqueId()); return; }
        String hash = plugin.getConfig().getString("blood-moon.moon-pack.sha1", "");
        byte[] sha1 = null;
        if (hash != null && !hash.isBlank()) {
            try {
                if (!hash.matches("(?i)[0-9a-f]{40}")) throw new IllegalArgumentException("SHA-1 must be 40 hex characters");
                sha1 = java.util.HexFormat.of().parseHex(hash);
            } catch (IllegalArgumentException invalid) {
                moonPackPlayers.remove(player.getUniqueId());
                plugin.getLogger().warning("Invalid blood-moon.moon-pack.sha1: " + invalid.getMessage());
                return;
            }
        }
        try { player.addResourcePack(MOON_PACK_ID, url, sha1, "Optional Dead Streets Blood Moon moon texture. Other server packs are preserved.", false); }
        catch (RuntimeException failure) {
            moonPackPlayers.remove(player.getUniqueId());
            plugin.getLogger().warning("Could not request Blood Moon texture pack for " + player.getName() + ": " + failure.getMessage());
        }
    }
    private void removeMoonPack(Player player) {
        if (!moonPackPlayers.remove(player.getUniqueId())) return;
        try { player.removeResourcePack(MOON_PACK_ID); }
        catch (RuntimeException failure) { plugin.getLogger().fine("Could not remove Blood Moon texture pack for " + player.getName() + ": " + failure.getMessage()); }
    }

    private void begin(World world, WorldEvent state, long day) {
        state.activeDay = day;
        state.wavesSent = 0;
        state.nextWaveAtWorldTime = world.getFullTime();
        state.startsAtWorldTime = world.getFullTime();
        long sunrise = day * TICKS_PER_DAY + SUNRISE_TICK;
        state.endsAtWorldTime = Math.min(world.getFullTime() + durationTicks(), sunrise);
        saveState();

        for (Player player : world.getPlayers()) {
            plugin.showTitleNotice(player, ChatColor.DARK_RED + "BLOOD MOON",
                    ChatColor.RED + "The dead are coming. Lock down and survive the waves.");
        }
    }

    private int sendWave(World world, WorldEvent state) {
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) return 0;
        int groups = Math.max(1, Math.min(4, plugin.getConfig().getInt("blood-moon.groups-per-wave", 2)));
        int min = Math.max(30, Math.min(500,
                plugin.getConfig().getInt("blood-moon.group-min-population", 100)));
        int max = Math.max(min, Math.min(500,
                plugin.getConfig().getInt("blood-moon.group-max-population", 180)));
        int created = 0;
        int assignedPopulation = 0;
        for (int i = 0; i < groups; i++) {
            Player target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
            try {
                int population = ThreadLocalRandom.current().nextInt(min, max + 1);
                Temperament temperament = ThreadLocalRandom.current().nextBoolean()
                        ? Temperament.AGITATED : Temperament.FRENZIED;
                hordes.spawnManual(world, target.getLocation().getX(), target.getLocation().getZ(),
                        population, temperament);
                created++;
                assignedPopulation += population;
            } catch (IllegalStateException noApproach) {
                plugin.getLogger().fine("Blood Moon wave could not find a safe horde approach: "
                        + noApproach.getMessage());
            }
        }
        if (created > 0) {
            int wave = state.wavesSent + 1;
            String subtitle = created + " large groups approaching"
                    + " | Estimated combined population: " + assignedPopulation;
            for (Player player : players) {
                plugin.showTitleNotice(player, ChatColor.RED + "BLOOD MOON • HORDE WAVE " + wave,
                        ChatColor.DARK_RED + subtitle);
            }
        }
        return created;
    }

    private long durationTicks() {
        long minutes = Math.max(3, Math.min(30,
                plugin.getConfig().getLong("blood-moon.duration-minutes", 10)));
        return minutes * 60L * 20L;
    }

    private void updateDisplay(World world, WorldEvent state, int maxWaves) {
        if (displayMode() == DisplayMode.SCOREBOARD) {
            removeBossBar(state);
            updateScoreboard(world, state, maxWaves);
        } else {
            removeScoreboard(state);
            updateBossBar(world, state, maxWaves);
        }
    }

    private void updateBossBar(World world, WorldEvent state, int maxWaves) {
        if (state.bar == null) {
            state.bar = Bukkit.createBossBar(ChatColor.DARK_RED + "BLOOD MOON",
                    BarColor.RED, BarStyle.SEGMENTED_10);
        }
        for (Player player : world.getPlayers()) {
            if (!state.bar.getPlayers().contains(player)) state.bar.addPlayer(player);
        }
        for (Player viewer : new ArrayList<>(state.bar.getPlayers())) {
            if (!viewer.getWorld().getUID().equals(world.getUID())) state.bar.removePlayer(viewer);
        }

        long remainingTicks = Math.max(0L, state.endsAtWorldTime - world.getFullTime());
        long remainingSeconds = (remainingTicks + 19L) / 20L;
        String remaining = formatDuration(remainingSeconds);
        state.bar.setTitle(ChatColor.DARK_RED + "BLOOD MOON  •  WAVE "
                + Math.min(maxWaves, state.wavesSent) + "/" + maxWaves
                + ChatColor.RED + "  •  ENDS IN " + remaining);
        long total = Math.max(1L, state.endsAtWorldTime - state.startsAtWorldTime);
        state.bar.setProgress(Math.max(0.0, Math.min(1.0, (double) remainingTicks / total)));
    }

    private void updateScoreboard(World world, WorldEvent state, int maxWaves) {
        if (state.scoreboard == null || state.objective == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                plugin.getLogger().warning("Scoreboard manager unavailable; falling back to Blood Moon BossBar.");
                plugin.getConfig().set("blood-moon.display-mode", DisplayMode.BOSSBAR.name());
                plugin.saveConfig();
                updateBossBar(world, state, maxWaves);
                return;
            }
            state.scoreboard = manager.getNewScoreboard();
            state.objective = state.scoreboard.registerNewObjective(
                    "ds_bloodmoon", Criteria.DUMMY,
                    Component.text("BLOOD MOON", NamedTextColor.DARK_RED), RenderType.INTEGER);
            state.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // Detach players who left this world. Restore only if this scoreboard is
        // still the visible one; another plugin may have intentionally taken over.
        Iterator<Map.Entry<UUID, Scoreboard>> iterator = state.previousScoreboards.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Scoreboard> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (!player.getWorld().getUID().equals(world.getUID())) {
                if (player.getScoreboard() == state.scoreboard) player.setScoreboard(entry.getValue());
                iterator.remove();
            } else if (player.getScoreboard() != state.scoreboard) {
                // Do not fight a scoreboard assigned by another plugin during the event.
                iterator.remove();
            }
        }

        for (Player player : world.getPlayers()) {
            if (!state.previousScoreboards.containsKey(player.getUniqueId())) {
                Scoreboard previous = player.getScoreboard();
                state.previousScoreboards.put(player.getUniqueId(), previous);
                player.setScoreboard(state.scoreboard);
            }
        }

        long remainingTicks = Math.max(0L, state.endsAtWorldTime - world.getFullTime());
        long remainingSeconds = (remainingTicks + 19L) / 20L;
        long nextWaveTicks = Math.max(0L, state.nextWaveAtWorldTime - world.getFullTime());
        long nextWaveSeconds = (nextWaveTicks + 19L) / 20L;
        String nextWave = state.wavesSent >= maxWaves ? "ALL WAVES SENT" : formatDuration(nextWaveSeconds);
        int wavesRemaining = Math.max(0, maxWaves - state.wavesSent);

        for (String entry : state.scoreboardEntries) state.scoreboard.resetScores(entry);
        state.scoreboardEntries.clear();
        addScoreboardLine(state, ChatColor.RED + "WAVE: " + ChatColor.WHITE
                + Math.min(maxWaves, state.wavesSent) + "/" + maxWaves, 8);
        addScoreboardLine(state, ChatColor.GRAY + "WAVES LEFT: " + ChatColor.WHITE + wavesRemaining, 7);
        addScoreboardLine(state, ChatColor.GOLD + "NEXT WAVE: " + ChatColor.WHITE + nextWave, 5);
        addScoreboardLine(state, ChatColor.DARK_GRAY.toString(), 4);
        addScoreboardLine(state, ChatColor.RED + "EVENT ENDS: " + ChatColor.YELLOW
                + formatDuration(remainingSeconds), 3);
        addScoreboardLine(state, ChatColor.GRAY + "Hold the shelter", 1);
    }

    private void addScoreboardLine(WorldEvent state, String line, int score) {
        state.scoreboardEntries.add(line);
        state.objective.getScore(line).setScore(score);
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        return String.format(Locale.ROOT, "%02d:%02d", safe / 60L, safe % 60L);
    }

    private void refreshActiveDisplays() {
        int maxWaves = Math.max(1, Math.min(8, plugin.getConfig().getInt("blood-moon.waves", 3)));
        for (Map.Entry<UUID, WorldEvent> entry : worlds.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            WorldEvent state = entry.getValue();
            if (world == null || state.activeDay == Long.MIN_VALUE
                    || world.getFullTime() >= state.endsAtWorldTime) continue;
            updateDisplay(world, state, maxWaves);
        }
    }

    private void removeBossBar(WorldEvent state) {
        if (state.bar != null) {
            state.bar.removeAll();
            state.bar = null;
        }
    }

    private void removeScoreboard(WorldEvent state) {
        if (state.scoreboard == null) return;
        for (Map.Entry<UUID, Scoreboard> entry : new ArrayList<>(state.previousScoreboards.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getScoreboard() == state.scoreboard) {
                player.setScoreboard(entry.getValue());
            }
        }
        state.previousScoreboards.clear();
        if (state.objective != null) {
            try { state.objective.unregister(); }
            catch (IllegalStateException ignored) { }
        }
        state.objective = null;
        state.scoreboard = null;
        state.scoreboardEntries.clear();
    }

    private void clearDisplays(WorldEvent state) {
        removeBossBar(state);
        removeScoreboard(state);
    }

    private void end(World world, WorldEvent state) {
        clearDisplays(state);
        if (state.activeDay != Long.MIN_VALUE) {
            for (Player player : world.getPlayers()) {
                plugin.showTitleNotice(player, ChatColor.GRAY + "THE BLOOD MOON HAS PASSED",
                        ChatColor.DARK_RED + "The remaining dead are still out there.");
            }
        }
        state.activeDay = Long.MIN_VALUE;
        state.startsAtWorldTime = 0L;
        state.endsAtWorldTime = 0L;
        state.wavesSent = 0;
        state.nextWaveAtWorldTime = 0L;
        saveState();
    }

    private Path stateFile() {
        return plugin.getDataFolder().toPath().resolve("blood-moon-state-v3.txt");
    }

    private void loadState() {
        Path current = stateFile();
        Path legacyV2 = plugin.getDataFolder().toPath().resolve("blood-moon-state-v2.txt");
        Path legacyV1 = plugin.getDataFolder().toPath().resolve("blood-moon-state-v1.txt");
        boolean usesWorldClock = Files.isRegularFile(current);
        Path file = usesWorldClock ? current
                : (Files.isRegularFile(legacyV2) ? legacyV2 : legacyV1);
        if (!Files.isRegularFile(file)) return;
        try {
            int count = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (++count > 10_000) throw new IOException("Blood Moon state exceeds record limit");
                String[] p = line.split("\\|", -1);
                if (p.length != 6 && p.length != 7) continue;
                try {
                    UUID id = UUID.fromString(p[0]);
                    WorldEvent state = worlds.computeIfAbsent(id, ignored -> new WorldEvent());
                    state.warnedDay = Long.parseLong(p[1]);
                    state.activeDay = Long.parseLong(p[2]);
                    long savedNextWave = Long.parseLong(p[3]);
                    if (usesWorldClock) {
                        state.nextWaveAtWorldTime = savedNextWave;
                    } else {
                        World savedWorld = Bukkit.getWorld(id);
                        long currentWorldTime = savedWorld == null ? 0L : savedWorld.getFullTime();
                        long remainingMillis = Math.max(0L, savedNextWave - System.currentTimeMillis());
                        state.nextWaveAtWorldTime = currentWorldTime + (remainingMillis + 49L) / 50L;
                    }
                    if (p.length == 7) {
                        state.startsAtWorldTime = Long.parseLong(p[4]);
                        state.endsAtWorldTime = Long.parseLong(p[5]);
                        state.wavesSent = Math.max(0, Math.min(8, Integer.parseInt(p[6])));
                    } else {
                        state.endsAtWorldTime = Long.parseLong(p[4]);
                        state.startsAtWorldTime = state.activeDay == Long.MIN_VALUE
                                ? 0L : state.activeDay * TICKS_PER_DAY + 13_000L;
                        state.wavesSent = Math.max(0, Math.min(8, Integer.parseInt(p[5])));
                    }
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
            Path tmp = file.resolveSibling("blood-moon-state-v3.txt.tmp");
            List<String> lines = new ArrayList<>();
            lines.add("# world UUID | warned day | active day | next wave world tick | start world tick | end world tick | waves sent");
            for (Map.Entry<UUID, WorldEvent> entry : worlds.entrySet()) {
                WorldEvent state = entry.getValue();
                lines.add(entry.getKey() + "|" + state.warnedDay + "|" + state.activeDay + "|"
                        + state.nextWaveAtWorldTime + "|" + state.startsAtWorldTime + "|"
                        + state.endsAtWorldTime + "|" + state.wavesSent);
            }
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            plugin.getLogger().severe("Could not save Blood Moon state: " + error.getMessage());
        }
    }
}
