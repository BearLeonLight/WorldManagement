package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.UUID;

/** Evaluates world access exclusively from immutable in-memory metadata. */
public final class WorldAccessPolicy {

    public boolean allowsEntry(final WorldMetadata metadata, final UUID playerId, final boolean bypass) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(playerId, "playerId");
        if (metadata.identityState() != IdentityVerificationState.VERIFIED) {
            return false;
        }
        if (bypass || metadata.accessControl().mode() == AccessMode.NONE) {
            return true;
        }
        final boolean listed = metadata.accessControl().entries().contains(playerId);
        return metadata.accessControl().mode() == AccessMode.WHITELIST ? listed : !listed;
    }

    public boolean allows(final WorldMetadata metadata, final UUID playerId, final RankPermission permission, final boolean bypass) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(permission, "permission");
        if (metadata.identityState() != IdentityVerificationState.VERIFIED) {
            return false;
        }
        if (bypass || !metadata.rankSystemEnabled() || WorldMetadata.SERVER_OWNER.equals(metadata.owner())
            || metadata.owner().equals(playerId.toString())) {
            return true;
        }
        final String rankId = metadata.playerRanks().getOrDefault(playerId, WorldMetadata.GUEST_RANK);
        return metadata.ranks().get(rankId).permissions().contains(permission);
    }

    public boolean allowsWarp(
        final WorldMetadata metadata,
        final WorldWarp warp,
        final UUID playerId,
        final boolean bypass,
        final boolean hasRequiredPermission
    ) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(warp, "warp");
        Objects.requireNonNull(playerId, "playerId");
        if (metadata.identityState() != IdentityVerificationState.VERIFIED) {
            return false;
        }
        if (!warp.requiredPermission().isEmpty() && !hasRequiredPermission) {
            return false;
        }
        if (bypass || WorldMetadata.SERVER_OWNER.equals(metadata.owner())) {
            return true;
        }
        final RankPermission rankPermission = warp.visibility() == WarpVisibility.PUBLIC
            ? RankPermission.USE_PUBLIC_WARP
            : RankPermission.USE_PRIVATE_WARP;
        if (!allows(metadata, playerId, rankPermission, false)) {
            return false;
        }
        if (warp.visibility() == WarpVisibility.PUBLIC) {
            return true;
        }
        final String rankId = metadata.playerRanks().getOrDefault(playerId, WorldMetadata.GUEST_RANK);
        return warp.trustedPlayers().contains(playerId) || warp.trustedRanks().contains(rankId)
            || WorldMetadata.OWNER_RANK.equals(rankId);
    }
}