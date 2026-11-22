package dev.deadstreets.core;

import java.util.random.RandomGenerator;

/** Single-threaded state machine. A spawner has ONE countdown for ALL nearby players. */
public final class SpawnerCycle {
    public record Rules(int waveMin, int waveMax, int intervalMinSeconds, int intervalMaxSeconds,
                        int nearbyCap, long resetCooldownMillis) {
        public Rules {
            if (waveMin < 1 || waveMax < waveMin || intervalMinSeconds < 1 || intervalMaxSeconds < intervalMinSeconds
                || nearbyCap < 1 || resetCooldownMillis < 0) throw new IllegalArgumentException("Invalid spawner rules");
        }
    }
    public record Result(boolean active, int spawnCount, long remainingSeconds, boolean capped, boolean firstWave) { }
    private boolean active;
    private boolean previouslyActivated;
    private long lastLeftAt = -1L;
    private long remainingMillis = 0L;
    private long nextWaveAt = -1L;

    public Result tick(long nowMillis, boolean hasNearbyPlayer, int nearbyZombies, Rules rules, RandomGenerator random) {
        if (!hasNearbyPlayer) {
            if (active) {
                active = false;
                lastLeftAt = nowMillis;
                remainingMillis = Math.max(0L, nextWaveAt - nowMillis);
            }
            return new Result(false, 0, 0, false, false);
        }
        if (!active) {
            active = true;
            boolean fresh = !previouslyActivated || (lastLeftAt >= 0 && nowMillis - lastLeftAt >= rules.resetCooldownMillis());
            if (fresh) {
                previouslyActivated = true;
                if (nearbyZombies >= rules.nearbyCap()) {
                    nextWaveAt = nowMillis; // first wave is pending, not lost
                    return new Result(true, 0, 0, true, true);
                }
                int size = Math.min(roll(random, rules.waveMin(), rules.waveMax()), rules.nearbyCap() - nearbyZombies);
                nextWaveAt = nowMillis + roll(random, rules.intervalMinSeconds(), rules.intervalMaxSeconds()) * 1000L;
                return new Result(true, size, ceilSeconds(nextWaveAt - nowMillis), false, true);
            }
            nextWaveAt = nowMillis + remainingMillis;
        }
        if (nowMillis >= nextWaveAt) {
            if (nearbyZombies >= rules.nearbyCap()) return new Result(true, 0, 0, true, false);
            int count = Math.min(roll(random, rules.waveMin(), rules.waveMax()), rules.nearbyCap() - nearbyZombies);
            nextWaveAt = nowMillis + roll(random, rules.intervalMinSeconds(), rules.intervalMaxSeconds()) * 1000L;
            return new Result(true, count, ceilSeconds(nextWaveAt-nowMillis), false, false);
        }
        return new Result(true, 0, ceilSeconds(nextWaveAt-nowMillis), false, false);
    }
    private static int roll(RandomGenerator random, int min, int max) { return min == max ? min : random.nextInt(min,max+1); }
    private static long ceilSeconds(long millis) { return Math.max(0L,(millis+999)/1000); }
    public boolean isActive() { return active; }
    public String save(long nowMillis) {
        long left = active ? nowMillis : lastLeftAt;
        long rem = active ? Math.max(0L,nextWaveAt - nowMillis) : remainingMillis;
        return (previouslyActivated ? "1" : "0")+","+left+","+rem;
    }
    public static SpawnerCycle restore(String saved) {
        var x = saved.split(",",-1);
        if (x.length != 3) throw new IllegalArgumentException("Bad spawner checkpoint");
        var c = new SpawnerCycle();
        c.previouslyActivated = x[0].equals("1");
        c.lastLeftAt = Long.parseLong(x[1]);
        c.remainingMillis = Math.max(0L, Long.parseLong(x[2]));
        return c;
    }
}
