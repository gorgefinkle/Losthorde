package dev.deadstreets.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Pure, immutable per-build placement planner. No chunk loads, Bukkit objects or random server state. */
public final class PopulationPlan {
    private PopulationPlan() {}

    public record Candidate(String buildId, int index, int chunkX, int chunkZ,
                            int x, int z, long salt) {}

    /** Assign each of the build's walkers to exactly one footprint chunk deterministically. */
    public static List<Candidate> inChunk(MetropolisFlag flag, PopulationRoller.Range range,
                                           int chunkX, int chunkZ, int maxPerBuild) {
        if (maxPerBuild < 1) throw new IllegalArgumentException("maxPerBuild must be positive");
        int population = flag.deterministicPopulation(range);
        if (population < 0 || population > maxPerBuild) {
            throw new IllegalArgumentException("population exceeds per-build safety limit");
        }
        int west = Math.floorDiv(flag.minX(),16), east = Math.floorDiv(flag.maxX(),16);
        int north = Math.floorDiv(flag.minZ(),16), south = Math.floorDiv(flag.maxZ(),16);
        long width = (long)east - west + 1L, depth = (long)south - north + 1L;
        if (width < 1 || depth < 1 || width > 32 || depth > 32) {
            throw new IllegalArgumentException("build footprint spans more than 32 chunks on an axis");
        }
        if (chunkX < west || chunkX > east || chunkZ < north || chunkZ > south) return List.of();

        long seed = mix64(flag.worldSeed() ^ hash(flag.worldId() + ":" + flag.buildId()));
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < population; i++) {
            long a = mix64(seed + i * 0x9e3779b97f4a7c15L);
            int cx = west + (int)Math.floorMod(a, width);
            int cz = north + (int)Math.floorMod(mix64(a), depth);
            if (cx != chunkX || cz != chunkZ) continue;
            int minX = Math.max(flag.minX(), cx * 16), maxX = Math.min(flag.maxX(), cx * 16 + 15);
            int minZ = Math.max(flag.minZ(), cz * 16), maxZ = Math.min(flag.maxZ(), cz * 16 + 15);
            if (maxX < minX || maxZ < minZ) continue;
            long b = mix64(a ^ 0xd1b54a32d192ed03L);
            int x = minX + (int)Math.floorMod(b, (long)maxX - minX + 1);
            int z = minZ + (int)Math.floorMod(mix64(b), (long)maxZ - minZ + 1);
            out.add(new Candidate(flag.buildId(),i,cx,cz,x,z,b));
        }
        return List.copyOf(out);
    }

    public static String activationKey(MetropolisFlag flag) {
        return "build_" + Long.toUnsignedString(hash(flag.worldId() + ":" + flag.worldSeed()
                + ":" + flag.buildId() + ":" + flag.districtType()), 36);
    }

    private static long hash(String text) {
        long result = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            result ^= b & 255;
            result *= 0x100000001b3L;
        }
        return result;
    }
    public static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
