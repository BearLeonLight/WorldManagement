package io.github.bearl.worldmanagement.world;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Teleports a player on their Paper entity scheduler without exposing Bukkit objects to command handling. */
public interface WorldTeleportGateway {

    CompletableFuture<Boolean> teleport(
        UUID playerId,
        VerifiedWorldRef target,
        Optional<WorldCoordinates> coordinates
    );

    default CompletableFuture<Void> beginShutdown() {
        return CompletableFuture.completedFuture(null);
    }

    record WorldCoordinates(double x, double y, double z) {
        public WorldCoordinates {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("World coordinates must be finite.");
            }
        }
    }
}