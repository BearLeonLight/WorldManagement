package io.github.bearl.worldmanagement.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WorldMetadataTest {

    private static final UUID CREATIVE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static WorldIdentitySnapshot creativeIdentity() {
        return new WorldIdentitySnapshot(
            "minecraft:creative",
            CREATIVE_UUID,
            WorldEnvironment.NORMAL,
            42L,
            true
        );
    }

    @Test
    void rejectsChangesToOwnerRankPermissions() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);

        assertThrows(IllegalArgumentException.class,
            () -> metadata.withRankPermission(WorldMetadata.OWNER_RANK, RankPermission.BUILD, true));
    }

    @Test
    void defaultMetadataContainsTheSystemRanks() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);

        assertEquals(WorldMetadata.SERVER_OWNER, metadata.owner());
        assertEquals("minecraft:creative", metadata.worldKey());
        assertEquals(WorldManagementState.ACTIVE, metadata.managementState());
        assertEquals(WorldLoadState.LOADED, metadata.desiredState());
        assertTrue(metadata.ranks().containsKey(WorldMetadata.OWNER_RANK));
        assertTrue(metadata.ranks().containsKey(WorldMetadata.GUEST_RANK));
        assertEquals(0, metadata.version());
    }

    @Test
    void rejectsWorldIdsOutsideTheCanonicalFormat() {
        assertThrows(IllegalArgumentException.class, () -> WorldMetadata.createDefault("Creative", true));
    }

    @Test
    void rejectsPaperKeysWhoseValueDoesNotMatchTheWorldId() {
        final WorldMetadata defaults = WorldMetadata.createDefault("creative", true);

        assertThrows(IllegalArgumentException.class, () -> new WorldMetadata(
            "creative",
            "minecraft:survival",
            defaults.managementState(),
            defaults.desiredState(),
            defaults.owner(),
            defaults.rankSystemEnabled(),
            defaults.accessControl(),
            defaults.ranks(),
            defaults.playerRanks(),
            defaults.warps(),
            defaults.version()
        ));
    }

    @Test
    void classifiesObservedSnapshotDriftWithoutAcceptingIt() {
        final WorldMetadata metadata = WorldMetadata.createDefault(
            "creative",
            creativeIdentity(),
            LifecycleCapability.MANAGED,
            Optional.of(RequestedWorldType.FLAT),
            true
        );
        final WorldIdentitySnapshot observed = new WorldIdentitySnapshot(
            "minecraft:creative",
            CREATIVE_UUID,
            WorldEnvironment.NORMAL,
            99L,
            true
        );

        final WorldMetadata pending = metadata.withObservedIdentity(observed);

        assertEquals(IdentityVerificationState.SYNC_PENDING, pending.identityState());
        assertEquals(creativeIdentity(), pending.identity());
        assertEquals(Optional.of(observed), pending.pendingIdentity());
    }

    @Test
    void synchronizesOnlyPendingSnapshotDrift() {
        final WorldIdentitySnapshot observed = new WorldIdentitySnapshot(
            "minecraft:creative", CREATIVE_UUID, WorldEnvironment.NORMAL, 99L, false
        );
        final WorldMetadata pending = WorldMetadata.createDefault(
            "creative", creativeIdentity(), LifecycleCapability.MANAGED, Optional.empty(), true
        ).withObservedIdentity(observed);

        final WorldMetadata synchronizedMetadata = pending.synchronizePendingIdentity();

        assertEquals(IdentityVerificationState.VERIFIED, synchronizedMetadata.identityState());
        assertEquals(observed, synchronizedMetadata.identity());
        assertTrue(synchronizedMetadata.pendingIdentity().isEmpty());
        assertEquals(pending.version() + 1L, synchronizedMetadata.version());
        assertThrows(IllegalStateException.class, synchronizedMetadata::synchronizePendingIdentity);
    }

    @Test
    void neverSynchronizesAKeyOrUuidConflict() {
        final WorldMetadata conflict = WorldMetadata.createDefault(
            "creative", creativeIdentity(), LifecycleCapability.MANAGED, Optional.empty(), true
        ).withObservedIdentity(new WorldIdentitySnapshot(
            "minecraft:creative",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            WorldEnvironment.NORMAL,
            42L,
            true
        ));

        assertThrows(IllegalStateException.class, conflict::synchronizePendingIdentity);
    }

    @Test
    void acceptsOnlyAConflictReplacementWithExplicitWarpPolicy() {
        final WorldWarp warp = new WorldWarp(
            "spawn", 1, 64, 2, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
        );
        final WorldIdentitySnapshot replacement = new WorldIdentitySnapshot(
            "minecraft:creative",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            WorldEnvironment.NORMAL,
            99L,
            false
        );
        final WorldMetadata conflict = WorldMetadata.createDefault(
            "creative", creativeIdentity(), LifecycleCapability.MANAGED, Optional.empty(), true
        ).withWarp(warp).withObservedIdentity(replacement);

        final WorldMetadata kept = conflict.acceptPendingReplacement(true);
        final WorldMetadata cleared = conflict.acceptPendingReplacement(false);

        assertEquals(replacement, kept.identity());
        assertEquals(IdentityVerificationState.VERIFIED, kept.identityState());
        assertTrue(kept.pendingIdentity().isEmpty());
        assertEquals(Set.of("spawn"), kept.warps().keySet());
        assertTrue(cleared.warps().isEmpty());
        assertThrows(IllegalStateException.class, () -> kept.acceptPendingReplacement(true));
    }

    @Test
    void abandonsOnlyNonVerifiedMetadataWithoutChangingAuthorityPayload() {
        final WorldMetadata conflict = WorldMetadata.createDefault(
            "creative", creativeIdentity(), LifecycleCapability.MANAGED, Optional.empty(), true
        ).withWarp(new WorldWarp(
            "spawn", 1, 64, 2, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
        )).withObservedIdentity(new WorldIdentitySnapshot(
            "minecraft:creative",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            WorldEnvironment.NORMAL,
            42L,
            true
        ));

        final WorldMetadata abandoned = conflict.abandonNonVerifiedIdentity();

        assertEquals(WorldManagementState.DETACHED, abandoned.managementState());
        assertEquals(WorldLoadState.UNLOADED, abandoned.desiredState());
        assertEquals(conflict.identity(), abandoned.identity());
        assertEquals(conflict.pendingIdentity(), abandoned.pendingIdentity());
        assertEquals(conflict.accessControl(), abandoned.accessControl());
        assertEquals(conflict.warps(), abandoned.warps());
        assertThrows(
            IllegalStateException.class,
            () -> WorldMetadata.createDefault("creative", true).abandonNonVerifiedIdentity()
        );
    }

    @Test
    void classifiesKeyOrUuidReplacementAsConflict() {
        final WorldMetadata metadata = WorldMetadata.createDefault(
            "creative",
            creativeIdentity(),
            LifecycleCapability.EXTERNAL_ONLY,
            Optional.empty(),
            true
        );
        final WorldIdentitySnapshot replacement = new WorldIdentitySnapshot(
            "external:creative",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            WorldEnvironment.CUSTOM,
            42L,
            true
        );

        final WorldMetadata conflict = metadata.withObservedIdentity(replacement);

        assertEquals(IdentityVerificationState.CONFLICT, conflict.identityState());
        assertEquals(LifecycleCapability.EXTERNAL_ONLY, conflict.lifecycleCapability());
        assertEquals(Optional.of(replacement), conflict.pendingIdentity());
    }

    @Test
    void promotesObservedCustomRuntimeToExternalOnlyWithoutAutomaticDowngrade() {
        final WorldMetadata metadata = WorldMetadata.createDefault(
            "creative",
            creativeIdentity(),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true
        );

        final WorldMetadata external = metadata.withObservedIdentity(
            creativeIdentity(), LifecycleCapability.EXTERNAL_ONLY
        );
        final WorldMetadata stillExternal = external.withObservedIdentity(
            creativeIdentity(), LifecycleCapability.MANAGED
        );

        assertEquals(LifecycleCapability.EXTERNAL_ONLY, external.lifecycleCapability());
        assertEquals(LifecycleCapability.EXTERNAL_ONLY, stillExternal.lifecycleCapability());
    }

    @Test
    void repeatedObservationOfTheSameDriftIsIdempotent() {
        final WorldIdentitySnapshot observed = new WorldIdentitySnapshot(
            "minecraft:creative",
            CREATIVE_UUID,
            WorldEnvironment.NORMAL,
            99L,
            true
        );
        final WorldMetadata pending = WorldMetadata.createDefault(
            "creative", creativeIdentity(), LifecycleCapability.MANAGED, Optional.empty(), true
        ).withObservedIdentity(observed, LifecycleCapability.MANAGED);

        final WorldMetadata repeated = pending.withObservedIdentity(observed, LifecycleCapability.MANAGED);

        assertTrue(pending == repeated);
        assertEquals(1, repeated.version());
    }

    @Test
    void aggregateMutationsPreserveIdentityAndCreationProvenance() {
        final WorldMetadata metadata = WorldMetadata.createDefault(
            "creative",
            creativeIdentity(),
            LifecycleCapability.MANAGED,
            Optional.of(RequestedWorldType.AMPLIFIED),
            true
        );

        final WorldMetadata updated = metadata.withOwner("owner");

        assertEquals(creativeIdentity(), updated.identity());
        assertEquals(LifecycleCapability.MANAGED, updated.lifecycleCapability());
        assertEquals(Optional.of(RequestedWorldType.AMPLIFIED), updated.requestedWorldType());
        assertEquals("creative", updated.displayName());
    }

    @Test
    void updatesAndResetsOnlyValidatedDisplayNames() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);
        final ValidatedDisplayName displayName = new DisplayNameValidator().validate(
            "<gradient:red:gold>創意 世界</gradient>"
        );

        final WorldMetadata named = metadata.withDisplayName(displayName);
        final WorldMetadata reset = named.resetDisplayName();

        assertEquals(displayName.miniMessage(), named.displayName());
        assertEquals("creative", reset.displayName());
        assertEquals(metadata.identity(), reset.identity());
        assertEquals(metadata.warps(), reset.warps());
    }

    @Test
    void updatesLifecycleIntentAndManagementState() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);

        final WorldMetadata detached = metadata
            .withDesiredState(WorldLoadState.UNLOADED)
            .withManagementState(WorldManagementState.DETACHED);

        assertEquals(WorldLoadState.UNLOADED, detached.desiredState());
        assertEquals(WorldManagementState.DETACHED, detached.managementState());
        assertEquals(2, detached.version());
    }

    @Test
    void preservesDeleteAutoRegistrationSourceAcrossLifecycleTransitions() {
        final WorldMetadata metadata = WorldMetadata.createDefault(
            "creative",
            creativeIdentity(),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            Optional.empty(),
            WorldManagementState.DETACHED,
            WorldRegistrationSource.DELETE_AUTO,
            true
        );

        final WorldMetadata deleting = metadata
            .withDesiredState(WorldLoadState.UNLOADED)
            .withDeleting(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

        assertEquals(WorldRegistrationSource.DELETE_AUTO, deleting.registrationSource());
        assertEquals(WorldRegistrationSource.STANDARD, WorldMetadata.createDefault("standard", true).registrationSource());
    }

    @Test
    void rejectsPlayerMappingsToUnknownRanks() {
        final UUID playerId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new WorldMetadata(
            "creative",
            WorldMetadata.SERVER_OWNER,
            true,
            AccessControl.unrestricted(),
            Map.of(
                WorldMetadata.OWNER_RANK, new Rank("Owner", Set.of()),
                WorldMetadata.GUEST_RANK, new Rank("Guest", Set.of())
            ),
            Map.of(playerId, "BUILDER"),
            Map.of(),
            0
        ));
    }

    @Test
    void updatesIncrementTheAggregateVersion() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);

        final WorldMetadata updated = metadata.withAccessControl(new AccessControl(AccessMode.WHITELIST, Set.of()));

        assertEquals(1, updated.version());
        assertEquals(AccessMode.WHITELIST, updated.accessControl().mode());
    }

    @Test
    void rankPermissionSetsAreImmutable() {
        final Rank rank = new Rank("Builder", Set.of(RankPermission.BUILD));

        assertThrows(UnsupportedOperationException.class, () -> rank.permissions().add(RankPermission.INTERACT));
        assertFalse(rank.permissions().contains(RankPermission.INTERACT));
    }

    @Test
    void reassignsDeletedCustomRankMembersToGuest() {
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true)
            .withRank("BUILDER", new Rank("Builder", Set.of(RankPermission.BUILD)))
            .withPlayerRank(playerId, "BUILDER");

        final WorldMetadata updated = metadata.withoutRank("BUILDER");

        assertEquals(WorldMetadata.GUEST_RANK, updated.playerRanks().get(playerId));
        assertFalse(updated.ranks().containsKey("BUILDER"));
        assertEquals(3, updated.version());
    }

    @Test
    void doesNotAllowSystemRankRemoval() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);

        assertThrows(IllegalArgumentException.class, () -> metadata.withoutRank(WorldMetadata.OWNER_RANK));
    }

    @Test
    void rejectsCustomRankBeyondConfiguredLimit() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true)
            .withRank("BUILDER", new Rank("Builder", Set.of()), 1);

        assertThrows(IllegalArgumentException.class,
            () -> metadata.withRank("MODERATOR", new Rank("Moderator", Set.of()), 1));
    }

    @Test
    void storesAndTrustsPrivateWarps() {
        final UUID playerId = UUID.randomUUID();
        final WorldWarp warp = new WorldWarp("vault", 1, 64, 2, 0, 0, WarpVisibility.PRIVATE, Set.of(), Set.of(), "");

        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true)
            .withWarp(warp)
            .withTrustedWarpPlayer("vault", playerId, true);

        assertTrue(metadata.warps().get("vault").trustedPlayers().contains(playerId));
        assertEquals(2, metadata.version());
    }
}
