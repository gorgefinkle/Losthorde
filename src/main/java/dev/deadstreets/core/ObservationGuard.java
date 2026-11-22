package dev.deadstreets.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded recent-visibility envelope shared by EVERY player in a world. This is a conservative
 * protection zone, not a claim to know the client's exact line of sight or packet arrival time.
 * Keeps recently observed corridors protected during sharp turns, flight and short teleports.
 * No Bukkit types are used, so multiplayer overlap can be tested without a Minecraft server.
 */
public final class ObservationGuard {
    private static final long HISTORY_MS = 12_000L;
    private static final int MAX_SAMPLES = 16;
    private static final double MAX_AHEAD = 320.0;
    private record Sample(UUID world, double x, double z, double dx, double dz,
                          int viewChunks, long time) {}
    private final Map<UUID, ArrayDeque<Sample>> tracks = new HashMap<>();

    public void observe(UUID player, UUID world, double x, double z, double velocityX,
                        double velocityZ, int viewChunks, long atMillis) {
        insert(player,world,x,z,velocityX,velocityZ,viewChunks,atMillis,true);
    }
    /** Preserve pre-teleport history even when a teleport occurs within the same second. */
    public void observeTeleport(UUID player, UUID world, double x, double z,
                                int viewChunks, long atMillis) {
        insert(player,world,x,z,0,0,viewChunks,atMillis,false);
    }
    private void insert(UUID player, UUID world, double x, double z, double velocityX,
                        double velocityZ, int viewChunks, long atMillis, boolean coalesce) {
        if (player == null || world == null || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(velocityX) || !Double.isFinite(velocityZ)) return;
        ArrayDeque<Sample> list = tracks.computeIfAbsent(player, unused -> new ArrayDeque<>());
        Sample latest = list.peekLast();
        Sample sample = new Sample(world, x, z, velocityX, velocityZ,
                Math.max(2, Math.min(48, viewChunks)), atMillis);
        // Each player's most recent sample is always current; history is sampled every second.
        if (coalesce && latest != null && latest.world.equals(world) && atMillis - latest.time < 1_000L) {
            list.removeLast();
        }
        list.addLast(sample);
        while (list.size() > MAX_SAMPLES) list.removeFirst();
    }

    public boolean protectedChunk(UUID world, int cx, int cz, long nowMillis) {
        return protectedPoint(world, cx * 16.0 + 8.0, cz * 16.0 + 8.0, nowMillis);
    }

    public boolean protectedPoint(UUID world, double x, double z, long nowMillis) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(z)) return true;
        for (var e : tracks.entrySet()) {
            for (Sample s : e.getValue()) {
                if (!s.world.equals(world) || s.time > nowMillis || nowMillis - s.time > HISTORY_MS) continue;
                double radius = Math.max(128.0, s.viewChunks * 16.0 + 64.0);
                double aheadX = s.dx * 40.0, aheadZ = s.dz * 40.0;
                double norm = Math.hypot(aheadX, aheadZ);
                if (norm > MAX_AHEAD) {aheadX *= MAX_AHEAD / norm; aheadZ *= MAX_AHEAD / norm;}
                if (segmentDistanceSquared(x, z, s.x, s.z, s.x + aheadX, s.z + aheadZ)
                        <= radius * radius) return true;
            }
        }
        return false;
    }

    private static double segmentDistanceSquared(double px, double pz, double ax, double az,
                                                 double bx, double bz) {
        double dx=bx-ax, dz=bz-az, denom=dx*dx+dz*dz;
        double t=denom==0?0:Math.max(0,Math.min(1,((px-ax)*dx+(pz-az)*dz)/denom));
        double ox=px-(ax+t*dx), oz=pz-(az+t*dz);
        return ox*ox+oz*oz;
    }
    public void forget(UUID player) {tracks.remove(player);}
    public void prune(long nowMillis) {
        tracks.values().forEach(list -> list.removeIf(s -> s.time > nowMillis || nowMillis - s.time > HISTORY_MS));
        tracks.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    public int trackedPlayers() {return tracks.size();}
}
