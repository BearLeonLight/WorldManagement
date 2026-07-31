package io.github.bearl.worldmanagement.warp;

import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Player-affine teleport operation; implementations must execute on the player's scheduler. */
public interface WarpTeleportGateway {

    CompletableFuture<Boolean> teleport(UUID playerId, VerifiedWorldRef world, WorldWarp warp);

    default CompletableFuture<Void> beginShutdown() {
        return CompletableFuture.completedFuture(null);
    }
}