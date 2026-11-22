package dev.deadstreets.core;

/** Pure shared policy for deciding when a virtual migration can safely become physical.
 * No client visibility is inferred from distance alone: the separate ObservationGuard
 * must also confirm the destination chunk is unobserved. */
public final class HordeArrivalPolicy {
    private HordeArrivalPolicy() {}
    public static final double MATERIALIZATION_NEAR_PLAYER = 1024.0;
    public static final double STEERING_NEAR_PLAYER = 1024.0;
    public static boolean advanceVirtual(boolean nextPositionProtected) {return !nextPositionProtected;}
    public static boolean eligibleMaterialization(double nearestPlayerDistance,boolean chunkUnobserved,boolean atBoundary) {
        return Double.isFinite(nearestPlayerDistance) && nearestPlayerDistance<=MATERIALIZATION_NEAR_PLAYER && chunkUnobserved && atBoundary;
    }
    public static boolean steerPhysical(double nearestPlayerDistance) {
        return Double.isFinite(nearestPlayerDistance) && nearestPlayerDistance<=STEERING_NEAR_PLAYER;
    }
}
