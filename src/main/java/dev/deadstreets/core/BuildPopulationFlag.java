package dev.deadstreets.core;

import java.util.Objects;

/** Metropolis contract v1: ONE roll per eligible build, ranges keyed by REAL district type. */
public record BuildPopulationFlag(String uniqueBuildId, String districtType, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ, int population, long placementSeed) {
    public BuildPopulationFlag {
        Objects.requireNonNull(uniqueBuildId); Objects.requireNonNull(districtType);
        if (uniqueBuildId.isBlank() || districtType.isBlank() || population < 0 ||
                minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid build marker");
    }
}
