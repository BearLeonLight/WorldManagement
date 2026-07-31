package io.github.bearl.worldmanagement.ownership;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldRuntimeResolution;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WorldAuthorizationServiceTest {

    @Test
    void deniesPermissionsWhenWhitelistBlocksEntry() {
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true)
            .withAccessControl(new AccessControl(AccessMode.WHITELIST, Set.of()));
        final WorldAuthorizationService service = new WorldAuthorizationService(new WorldAccessPolicy());

        assertFalse(service.allows(Optional.of(metadata), playerId, RankPermission.BUILD, false));
        assertTrue(service.allows(Optional.of(metadata), playerId, RankPermission.BUILD, true));
        assertTrue(service.allows(Optional.empty(), playerId, RankPermission.BUILD, false));
    }

    @Test
    void grantsPermissionsToNamedOwnerAfterEntryIsAllowed() {
        final UUID ownerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true).withOwner(ownerId.toString());
        final WorldAuthorizationService service = new WorldAuthorizationService(new WorldAccessPolicy());

        assertTrue(service.allows(Optional.of(metadata), ownerId, RankPermission.BUILD, false));
    }

    @Test
    void isolatedRuntimeIdentityOverridesOwnerAndGeneralBypass() {
        final UUID ownerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true).withOwner(ownerId.toString());
        final WorldAuthorizationService service = new WorldAuthorizationService(new WorldAccessPolicy());
        final WorldRuntimeResolution isolated = WorldRuntimeResolution.isolated(metadata);

        assertFalse(service.allowsEntry(isolated, ownerId, true));
        assertFalse(service.allows(isolated, ownerId, RankPermission.BUILD, true));
    }
}