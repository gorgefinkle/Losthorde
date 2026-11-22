package dev.deadstreets.core;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/** Pure deterministic handoff. Metropolis supplies its REAL district ID and configured range.
 * No guessed landmark types or separate MegaMall rules. Does NOT modify world generation.
 */
public final class PopulationRoller {
    public record Range(int minimum, int maximum) {
        public Range { if(minimum<0 || maximum<minimum) throw new IllegalArgumentException("Invalid population range"); }
    }
    private PopulationRoller() {}
    public static int roll(long worldSeed, String uniqueBuildId, String actualDistrictType, Range range) {
        if(uniqueBuildId==null || uniqueBuildId.isBlank() || actualDistrictType==null || actualDistrictType.isBlank())
            throw new IllegalArgumentException("Build ID and district type required");
        // FNV-1a 64-bit, deterministic across server restarts and Java versions.
        long hash=0xcbf29ce484222325L;
        for(byte b:(actualDistrictType+"|"+uniqueBuildId).getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b&0xff);hash*=0x100000001b3L;
        }
        if(range.minimum()==range.maximum()) return range.minimum();
        return new SplittableRandom(worldSeed ^ hash).nextInt(range.minimum(),range.maximum()+1);
    }
}
