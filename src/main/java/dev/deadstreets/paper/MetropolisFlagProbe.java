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
        Path directory = metro.getDataFolder().toPath().resolve("deadstreets-build-flags-v1")
                .resolve(player.getWorld().getUID().toString());
        int valid = 0, duplicates = 0, bad = 0, eligible = 0, estimate = 0, interiorCandidates = 0;
        boolean foundAny=false;
        HashSet<String> ids = new HashSet<>(); // bounded to a 3x3 region window for this admin probe
        try {
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                Path file=directory.resolve((rx+dx)+"."+(rz+dz)+".tsv");
                if(!Files.isRegularFile(file)||Files.size(file)>6*1024*1024L)continue;
                foundAny=true;
                try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for(int rows=0;rows<12000;rows++) {
                        String line=in.readLine();if(line==null)break;
                        try {
                            MetropolisFlag flag=MetropolisFlag.parse(line);
                            if(!flag.worldId().equals(player.getWorld().getUID().toString())||flag.worldSeed()!=player.getWorld().getSeed()){bad++;continue;}
                            if(!ids.add(flag.buildId())){duplicates++;continue;}
                            valid++;interiorCandidates+=flag.interiorPoints().size();
                            String root="population.ranges."+flag.districtType();
                            if(!ds.getConfig().contains(root))continue;
                            int lo=ds.getConfig().getInt(root+".min",0),hi=ds.getConfig().getInt(root+".max",0);
                            if(lo<0||hi<lo){bad++;continue;}
                            estimate+=flag.deterministicPopulation(new PopulationRoller.Range(lo,hi));eligible++;
                        } catch(RuntimeException badRow){bad++;}
                    }
                }
            }
        } catch(IOException e) {
            player.sendMessage(ChatColor.RED+"Error reading Metropolis flags: "+e.getMessage());
            return;
        }
        // Also inspect the current per-world JSONL contract. Legacy region TSV files above
        // remain supported for old exporter builds and saved test worlds.
        Path jsonl=directory.resolve("buildings.jsonl");
        if(Files.isRegularFile(jsonl)) {
            try {
                long size=Files.size(jsonl);
                if(size<=Math.max(1024*1024L,ds.getConfig().getLong("population.activation.max-world-file-bytes",134217728L))) {
                    foundAny=true;
                    try(BufferedReader in=Files.newBufferedReader(jsonl,StandardCharsets.UTF_8)) {
                        for(int rows=0;rows<Math.max(100,Math.min(500000,ds.getConfig().getInt("population.activation.max-world-records",200000)));rows++) {
                            String line=in.readLine();if(line==null)break;
                            try {
                                MetropolisFlag flag=MetropolisFlagJson.parse(line);
                                if(!flag.worldId().equals(player.getWorld().getUID().toString())||flag.worldSeed()!=player.getWorld().getSeed()){bad++;continue;}
                                if(!ids.add(flag.buildId())){duplicates++;continue;}
                                valid++;interiorCandidates+=flag.interiorPoints().size();
                                String root="population.ranges."+flag.districtType();
                                if(!ds.getConfig().contains(root))continue;
                                int lo=ds.getConfig().getInt(root+".min",0),hi=ds.getConfig().getInt(root+".max",0);
                                if(lo<0||hi<lo){bad++;continue;}
                                estimate+=flag.deterministicPopulation(new PopulationRoller.Range(lo,hi));eligible++;
                            } catch(RuntimeException malformed){bad++;}
                        }
                    }
                } else {
                    player.sendMessage(ChatColor.RED+"Metropolis JSONL sidecar exceeds configured safety limit: "+size+" bytes.");
                }
            } catch(IOException e) {
                player.sendMessage(ChatColor.RED+"Error reading Metropolis JSONL flags: "+e.getMessage());
                return;
            }
        }
        if(!foundAny) {
            player.sendMessage(ChatColor.YELLOW+"No Metropolis build flags in the surrounding 3x3 region window ("+rx+","+rz+").");
            return;
        }
        player.sendMessage(ChatColor.AQUA + "Dead Streets / Metropolis marker probe: region " + rx + "," + rz);
        player.sendMessage(ChatColor.GRAY + "Build flags " + valid + " | configured " + eligible
                + " | indoor candidate columns " + interiorCandidates + " | potential initial walkers " + estimate + " | duplicates " + duplicates + " | invalid " + bad);
        player.sendMessage(ChatColor.GRAY + "Read-only marker diagnostic. Activation is controlled by population.activation.enabled.");
    }
}
