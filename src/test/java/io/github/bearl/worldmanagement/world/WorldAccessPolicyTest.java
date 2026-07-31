package io.github.bearl.worldmanagement.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WorldAccessPolicyTest {

    private final WorldAccessPolicy policy = new WorldAccessPolicy();

    @Test
    void evaluatesWhitelistAndBlacklistFromSnapshots() {
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata whitelist = WorldMetadata.createDefault("creative", true)
            .withAccessControl(new AccessControl(AccessMode.WHITELIST, Set.of(playerId)));
        final WorldMetadata blacklist = WorldMetadata.createDefault("creative", true)
            .withAccessControl(new AccessControl(AccessMode.BLACKLIST, Set.of(playerId)));

        assertTrue(policy.allowsEntry(whitelist, playerId, false));
        assertFalse(policy.allowsEntry(blacklist, playerId, false));
        assertTrue(policy.allowsEntry(blacklist, playerId, true));
    }

    @Test
    void evaluatesAssignedRankPermissionWithoutStorage() {
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true)
            .withOwner("owner")
            .withRank("BUILDER", new Rank("Builder", Set.of(RankPermission.BUILD)))
            .withPlayerRank(playerId, "BUILDER");

        assertTrue(policy.allows(metadata, playerId, RankPermission.BUILD, false));
        assertFalse(policy.allows(metadata, playerId, RankPermission.CONTAINER, false));
    }

    @Test
    void evaluatesPrivateWarpAgainstRankTrustAndRequiredPermission() {
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true)
            .withOwner("owner")
            .withRank("MEMBER", new Rank("Member", Set.of(RankPermission.USE_PRIVATE_WARP)))
            .withPlayerRank(playerId, "MEMBER");
        final WorldWarp warp = new WorldWarp(
            "vault", 0, 64, 0, 0, 0, WarpVisibility.PRIVATE, Set.of(playerId), Set.of(), "worldmanagement.warp.vault"
        );

        assertFalse(policy.allowsWarp(metadata, warp, playerId, false, false));
        assertTrue(policy.allowsWarp(metadata, warp, playerId, false, true));
        assertFalse(policy.allowsWarp(metadata, warp, playerId, true, false));
    }

    @Test
    void requiresExternalPermissionForPublicWarpsAndBypassRequests() {
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true)
            .withOwner("owner")
            .withRank("MEMBER", new Rank("Member", Set.of(RankPermission.USE_PUBLIC_WARP)))
            .withPlayerRank(playerId, "MEMBER");
        final WorldWarp warp = new WorldWarp(
            "vip", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), "worldmanagement.warp.vip"
        );

        assertFalse(policy.allowsWarp(metadata, warp, playerId, false, false));
        assertFalse(policy.allowsWarp(metadata, warp, playerId, true, false));
        assertTrue(policy.allowsWarp(metadata, warp, playerId, false, true));
    }

    @Test
    void identityIsolationPrecedesAclOwnerAndGeneralBypass() {
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata conflicted = WorldMetadata.createDefault("creative", true)
            .withObservedIdentity(new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.randomUUID(),
                WorldEnvironment.NORMAL,
                0L,
                true
            ));
        final WorldWarp warp = new WorldWarp(
            "spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
        );

        assertFalse(policy.allowsEntry(conflicted, playerId, true));
        assertFalse(policy.allows(conflicted, playerId, RankPermission.BUILD, true));
        assertFalse(policy.allowsWarp(conflicted, warp, playerId, true, true));
    }
}