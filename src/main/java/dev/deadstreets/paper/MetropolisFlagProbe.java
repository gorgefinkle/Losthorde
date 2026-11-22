package dev.deadstreets.paper;

import dev.deadstreets.core.MetropolisFlag;
import dev.deadstreets.core.PopulationRoller;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Read-only end-to-end Metropolis sidecar contract probe, no zombie spawning. */
final class MetropolisFlagProbe {
    private MetropolisFlagProbe() {}

    static void show(Player player, DeadStreetsPlugin ds) {
        Plugin metro = Bukkit.getPluginManager().getPlugin("Metropolis");
        if (metro == null) {
            player.sendMessage(ChatColor.YELLOW + "Metropolis plugin not loaded; no population markers to inspect.");
            return;
        }
        int rx = Math.floorDiv(player.getLocation().getBlockX() >> 4, 32);
        int rz = Math.floorDiv(player.getLocation().getBlockZ() >> 4, 32);
        Path file = metro.getDataFolder().toPath().resolve("deadstreets-build-flags-v1")
                .resolve(player.getWorld().getUID().toString()).resolve(rx + "." + rz + ".tsv");
        if (!Files.isRegularFile(file)) {
            player.sendMessage(ChatColor.YELLOW + "No Metropolis build flags for current 32x32-chunk region (" + rx + "," + rz + ").");
            return;
        }
        int valid = 0, duplicates = 0, bad = 0, eligible = 0, estimate = 0;
        HashSet<String> ids = new HashSet<>(); // bounded to ONE region for this admin probe
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null;) {
                try {
                    MetropolisFlag flag = MetropolisFlag.parse(line);
                    if (!flag.worldId().equals(player.getWorld().getUID().toString()) || flag.worldSeed() != player.getWorld().getSeed()) {
                        bad++; continue;
                    }
                    if (!ids.add(flag.buildId())) { duplicates++; continue; }
                    valid++;
                    String root = "population.ranges." + flag.districtType();
                    if (!ds.getConfig().contains(root)) continue;
                    int lo = ds.getConfig().getInt(root + ".min", 0);
                    int hi = ds.getConfig().getInt(root + ".max", 0);
                    if (lo < 0 || hi < lo) { bad++; continue; }
                    estimate += flag.deterministicPopulation(new PopulationRoller.Range(lo, hi));
                    eligible++;
                } catch (RuntimeException badRow) { bad++; }
            }
        } catch (IOException e) {
            player.sendMessage(ChatColor.RED + "Error reading Metropolis flags: " + e.getMessage());
            return;
        }
        player.sendMessage(ChatColor.AQUA + "Dead Streets / Metropolis marker probe: region " + rx + "," + rz);
        player.sendMessage(ChatColor.GRAY + "Build flags " + valid + " | configured " + eligible
                + " | potential initial walkers " + estimate + " | duplicates " + duplicates + " | invalid " + bad);
        player.sendMessage(ChatColor.YELLOW + "Read-only diagnostic. Flag-to-zombie activation is NOT yet implemented.");
    }
}
