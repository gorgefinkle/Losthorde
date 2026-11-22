package dev.deadstreets.core;

/** Sidecar marker contract produced by the optional Metropolis R14.5D adapter. */
public record MetropolisFlag(String worldId, long worldSeed, String buildId, String districtType,
                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public MetropolisFlag {
        if (worldId == null || worldId.isBlank() || buildId == null || buildId.isBlank()
                || districtType == null || districtType.isBlank()
                || minX > maxX || minY > maxY || minZ > maxZ)
            throw new IllegalArgumentException("Invalid Metropolis flag");
    }
    public static MetropolisFlag parse(String row) {
        String[] s = row.split("\t", -1);
        if (s.length != 11 || !s[0].equals("BUILD")) throw new IllegalArgumentException("Expected BUILD v1 with 11 columns");
        return new MetropolisFlag(s[1], Long.parseLong(s[2]), s[3], s[4],
                Integer.parseInt(s[5]), Integer.parseInt(s[6]), Integer.parseInt(s[7]),
                Integer.parseInt(s[8]), Integer.parseInt(s[9]), Integer.parseInt(s[10]));
    }
    public String format() {
        return "BUILD\t" + worldId + "\t" + worldSeed + "\t" + buildId + "\t" + districtType
            + "\t" + minX + "\t" + minY + "\t" + minZ + "\t" + maxX + "\t" + maxY + "\t" + maxZ;
    }
    public int deterministicPopulation(PopulationRoller.Range range) {
        return PopulationRoller.roll(worldSeed, buildId, districtType, range);
    }
}
