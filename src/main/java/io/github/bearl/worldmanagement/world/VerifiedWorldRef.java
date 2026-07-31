package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Minimal durable reference permitted at identity-sensitive runtime boundaries. */
public record VerifiedWorldRef(String worldId, String paperKey, UUID worldUuid) {

    public VerifiedWorldRef {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldUuid, "worldUuid");
        new WorldIdentitySnapshot(paperKey, worldUuid, WorldEnvironment.NORMAL, 0L, false)
            .requireWorldId(worldId);
    }

    public static Optional<VerifiedWorldRef> from(final WorldMetadata metadata) {
        final WorldMetadata requiredMetadata = Objects.requireNonNull(metadata, "metadata");
        if (requiredMetadata.identityState() != IdentityVerificationState.VERIFIED) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedWorldRef(
            requiredMetadata.worldName(),
            requiredMetadata.identity().paperKey(),
            requiredMetadata.identity().worldUuid()
        ));
    }
}