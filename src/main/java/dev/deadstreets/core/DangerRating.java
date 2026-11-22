package dev.deadstreets.core;

/** Population-weighted score; thresholds intentionally kept configurable later. */
public record DangerRating(int population, Temperament temperament, double score, Level level) {
    public enum Level { LOW, MODERATE, HIGH, EXTREME }
    public static DangerRating calculate(int population, Temperament temperament) {
        if (population < 0) throw new IllegalArgumentException("population < 0");
        double score = population * temperament.dangerMultiplier();
        var level = score < 35 ? Level.LOW : score < 85 ? Level.MODERATE : score < 160 ? Level.HIGH : Level.EXTREME;
        return new DangerRating(population, temperament, score, level);
    }
    public String approximatePopulation() {
        if (population < 20) return "~"+population;
        int increment = population < 100 ? 5 : 10;
        return "~"+(Math.round(population / (float) increment)*increment);
    }
}
