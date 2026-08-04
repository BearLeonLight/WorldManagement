package io.github.bearl.worldmanagement.world.lifecycle;

/** Immutable spawn override applied while Paper creates a world. */
public record WorldSpawnPosition(double x, double y, double z, float yaw, float pitch) {

    public WorldSpawnPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Spawn position values must be finite.");
        }
        if (pitch < -90.0f || pitch > 90.0f) {
            throw new IllegalArgumentException("Spawn pitch must be between -90 and 90 degrees.");
        }
    }
}