package io.github.bearl.worldmanagement.warp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.hook.CachedPermissionLookup;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DestinationWorldPermissionResolverTest {

    private static final UUID WORLD_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final VerifiedWorldRef TARGET = new VerifiedWorldRef(
        "creative", "minecraft:creative", WORLD_UUID
    );

    @Test
    void resolvesAgainstThePinnedRuntimeBukkitWorldName() {
        final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
        loadedWorlds.loaded(runtimeWorld(TARGET, "Runtime_Creative"));
        final AtomicReference<String> resolvedWorld = new AtomicReference<>();
        final AtomicReference<String> resolvedPermission = new AtomicReference<>();
        final DestinationWorldPermissionResolver resolver = new DestinationWorldPermissionResolver(
            loadedWorlds,
            (playerId, bukkitWorldName, permission) -> {
                assertEquals(PLAYER_UUID, playerId);
                resolvedWorld.set(bukkitWorldName);
                resolvedPermission.set(permission);
                return true;
            }
        );

        assertTrue(resolver.hasPermission(PLAYER_UUID, TARGET, "worldmanagement.warp.vip"));
        assertEquals("Runtime_Creative", resolvedWorld.get());
        assertEquals("worldmanagement.warp.vip", resolvedPermission.get());
    }

    @Test
    void failsClosedWhenTargetOrCachedLookupIsUnavailable() {
        final LoadedWorldCatalog replacementOnly = new LoadedWorldCatalog();
        replacementOnly.loaded(runtimeWorld(
            new VerifiedWorldRef("creative", "minecraft:creative", UUID.randomUUID()),
            "Runtime_Creative"
        ));

        assertFalse(new DestinationWorldPermissionResolver(replacementOnly, CachedPermissionLookup.denyAll())
            .hasPermission(PLAYER_UUID, TARGET, "worldmanagement.warp.vip"));
        assertFalse(new DestinationWorldPermissionResolver(new LoadedWorldCatalog(), (player, world, permission) -> true)
            .hasPermission(PLAYER_UUID, TARGET, "worldmanagement.warp.vip"));
    }

    @Test
    void emptyPermissionDoesNotRequireLuckPerms() {
        final AtomicInteger lookups = new AtomicInteger();
        final DestinationWorldPermissionResolver resolver = new DestinationWorldPermissionResolver(
            new LoadedWorldCatalog(),
            (player, world, permission) -> {
                lookups.incrementAndGet();
                return false;
            }
        );

        assertTrue(resolver.hasPermission(PLAYER_UUID, TARGET, ""));
        assertEquals(0, lookups.get());
    }

    private static WorldRuntimeGateway.LifecycleWorld runtimeWorld(
        final VerifiedWorldRef reference,
        final String bukkitWorldName
    ) {
        return new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot(
                reference.paperKey(), reference.worldUuid(), WorldEnvironment.NORMAL, 42L, true
            ),
            LifecycleCapability.MANAGED,
            bukkitWorldName
        );
    }
}