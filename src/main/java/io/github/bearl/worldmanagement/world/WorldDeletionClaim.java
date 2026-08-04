package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.UUID;

/** Exact metadata and quarantine identity admitted to a deletion transaction. */
public record WorldDeletionClaim(
    VerifiedWorldRef world,
    long metadataVersion,
    UUID transactionId,
    WorldManagementState originalManagementState
) {

    public WorldDeletionClaim {
        Objects.requireNonNull(world, "world");
        if (metadataVersion < 0) {
            throw new IllegalArgumentException("metadataVersion must not be negative.");
        }
        Objects.requireNonNull(transactionId, "transactionId");
        if (originalManagementState != WorldManagementState.ACTIVE
            && originalManagementState != WorldManagementState.DETACHED) {
            throw new IllegalArgumentException("Deletion may start only from ACTIVE or DETACHED metadata.");
        }
    }
}
