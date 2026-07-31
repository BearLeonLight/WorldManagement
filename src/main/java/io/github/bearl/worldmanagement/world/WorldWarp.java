package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, world-relative teleport point and its private access grants. */
public record WorldWarp(
    String name,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    WarpVisibility visibility,
    Set<UUID> trustedPlayers,
    Set<String> trustedRanks,
    String requiredPermission
) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    public WorldWarp {
        if (!NAME_PATTERN.matcher(Objects.requireNonNull(name, "name")).matches()) {
            throw new IllegalArgumentException("Invalid warp name.");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Warp coordinates must be finite.");
        }
        Objects.requireNonNull(visibility, "visibility");
        trustedPlayers = Set.copyOf(trustedPlayers);
        trustedRanks = Set.copyOf(trustedRanks);
        requiredPermission = Objects.requireNonNull(requiredPermission, "requiredPermission");
    }

    public WorldWarp withTrustedPlayer(final UUID playerId, final boolean trusted) {
        final Set<UUID> updated = new java.util.LinkedHashSet<>(trustedPlayers);
        if (trusted) {
            updated.add(Objects.requireNonNull(playerId, "playerId"));
        } else {
            updated.remove(Objects.requireNonNull(playerId, "playerId"));
        }
        return new WorldWarp(name, x, y, z, yaw, pitch, visibility, updated, trustedRanks, requiredPermission);
    }
}