package io.github.bearl.worldmanagement.ownership;

import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRuntimeResolution;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Applies the complete cache-only authorization order for managed worlds. */
public final class WorldAuthorizationService {

    private final WorldAccessPolicy accessPolicy;

    public WorldAuthorizationService(final WorldAccessPolicy accessPolicy) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    public boolean allows(
        final Optional<WorldMetadata> metadata,
        final UUID playerId,
        final RankPermission permission,
        final boolean bypass
    ) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(permission, "permission");
        if (metadata.isEmpty()) {
            return true;
        }
        final WorldMetadata world = metadata.orElseThrow();
        return accessPolicy.allowsEntry(world, playerId, bypass)
            && accessPolicy.allows(world, playerId, permission, bypass);
    }

    public boolean allows(
        final WorldRuntimeResolution resolution,
        final UUID playerId,
        final RankPermission permission,
        final boolean bypass
    ) {
        final WorldRuntimeResolution requiredResolution = Objects.requireNonNull(resolution, "resolution");
        return requiredResolution.status() == WorldRuntimeResolution.Status.UNMANAGED
            || requiredResolution.status() == WorldRuntimeResolution.Status.VERIFIED
                && allows(requiredResolution.metadata(), playerId, permission, bypass);
    }

    public boolean allowsEntry(final Optional<WorldMetadata> metadata, final UUID playerId, final boolean bypass) {
        return metadata.map(world -> accessPolicy.allowsEntry(world, playerId, bypass)).orElse(true);
    }

    public boolean allowsEntry(
        final WorldRuntimeResolution resolution,
        final UUID playerId,
        final boolean bypass
    ) {
        final WorldRuntimeResolution requiredResolution = Objects.requireNonNull(resolution, "resolution");
        return requiredResolution.status() == WorldRuntimeResolution.Status.UNMANAGED
            || requiredResolution.status() == WorldRuntimeResolution.Status.VERIFIED
                && allowsEntry(requiredResolution.metadata(), playerId, bypass);
    }
}