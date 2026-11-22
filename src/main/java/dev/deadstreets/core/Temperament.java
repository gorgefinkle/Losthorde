package dev.deadstreets.core;

import java.util.Locale;

/** Displayed temperament is atmosphere; hearing sensitivity is its primary mechanical effect. */
public enum Temperament {
    DOCILE(0.65, 24), RESTLESS(1.00, 48), AGITATED(1.40, 80), FRENZIED(1.90, 128);
    private final double dangerMultiplier;
    private final int baseHearingRange;
    Temperament(double dangerMultiplier, int baseHearingRange) {
        this.dangerMultiplier = dangerMultiplier;
        this.baseHearingRange = baseHearingRange;
    }
    public double dangerMultiplier() { return dangerMultiplier; }
    public int baseHearingRange() { return baseHearingRange; }
    public String label() { var s=name().toLowerCase(Locale.ROOT); return Character.toUpperCase(s.charAt(0))+s.substring(1); }
    public static Temperament parse(String input) { return valueOf(input.trim().toUpperCase(Locale.ROOT)); }
}
