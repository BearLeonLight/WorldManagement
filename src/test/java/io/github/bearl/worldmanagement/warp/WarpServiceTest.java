package io.github.bearl.worldmanagement.warp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.Rank;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WarpServiceTest {

    @Test
    void teleportsThroughCacheWhenAuthorized() {
        final PluginIoExecutor executor = new PluginIoExecutor("WarpTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            final UUID playerId = UUID.randomUUID();
            metadata.adopt("creative", true).join();
            metadata.update("creative", world -> world
                .withOwner("owner")
                .withRank("MEMBER", new Rank("Member", Set.of(RankPermission.USE_PUBLIC_WARP)))
                .withPlayerRank(playerId, "MEMBER")
                .withWarp(new WorldWarp("spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""))
            ).join();
            final WarpService service = new WarpService(metadata, new WorldAccessPolicy());

            final WarpService.TeleportResult result = service.teleport(
                playerId,
                "creative",
                "spawn",
                false,
                (player, world, warp) -> CompletableFuture.completedFuture(true)
            ).join();

            assertEquals(WarpService.TeleportStatus.TELEPORTED, result.status());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void pinsVerifiedIdentityAndWaitsForTheActualTeleportResult() {
        final PluginIoExecutor executor = new PluginIoExecutor("WarpTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final UUID playerId = UUID.randomUUID();
            final WorldIdentitySnapshot identity = new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                WorldEnvironment.NORMAL,
                42L,
                true
            );
            metadata.adopt(identity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.update("creative", world -> world.withWarp(new WorldWarp(
                "spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
            ))).join();
            final WarpService service = new WarpService(metadata, new WorldAccessPolicy());
            final AtomicReference<VerifiedWorldRef> target = new AtomicReference<>();
            final CompletableFuture<Boolean> pendingTeleport = new CompletableFuture<>();

            final CompletableFuture<WarpService.TeleportResult> result = service.teleport(
                playerId,
                "creative",
                "spawn",
                true,
                (player, world, warp) -> {
                    target.set(world);
                    return pendingTeleport;
                }
            );

            assertFalse(result.isDone());
            assertEquals(new VerifiedWorldRef("creative", identity.paperKey(), identity.worldUuid()), target.get());
            pendingTeleport.complete(true);
            assertEquals(WarpService.TeleportStatus.TELEPORTED, result.join().status());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void resolvesRequiredPermissionAgainstThePinnedDestinationWorld() {
        final PluginIoExecutor executor = new PluginIoExecutor("WarpTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final UUID playerId = UUID.randomUUID();
            final WorldIdentitySnapshot identity = new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                WorldEnvironment.NORMAL,
                42L,
                true
            );
            metadata.adopt(identity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.update("creative", world -> world
                .withOwner("owner")
                .withRank("MEMBER", new Rank("Member", Set.of(RankPermission.USE_PUBLIC_WARP)))
                .withPlayerRank(playerId, "MEMBER")
                .withWarp(new WorldWarp(
                    "vip", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), "worldmanagement.warp.vip"
                ))
            ).join();
            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            final VerifiedWorldRef expected = new VerifiedWorldRef("creative", identity.paperKey(), identity.worldUuid());
            loadedWorlds.loaded(new WorldRuntimeGateway.LifecycleWorld(identity, LifecycleCapability.MANAGED, "runtime_creative"));
            final AtomicReference<String> resolvedWorld = new AtomicReference<>();
            final WarpService service = new WarpService(
                metadata,
                new WorldAccessPolicy(),
                new DestinationWorldPermissionResolver(loadedWorlds, (resolvedPlayer, bukkitWorldName, permission) -> {
                    assertEquals(playerId, resolvedPlayer);
                    assertEquals("worldmanagement.warp.vip", permission);
                    resolvedWorld.set(bukkitWorldName);
                    return true;
                })
            );
            final AtomicReference<VerifiedWorldRef> target = new AtomicReference<>();

            final WarpService.TeleportResult result = service.teleport(
                playerId,
                "creative",
                "vip",
                false,
                (teleportedPlayer, world, warp) -> {
                    target.set(world);
                    return CompletableFuture.completedFuture(true);
                }
            ).join();

            assertEquals(WarpService.TeleportStatus.TELEPORTED, result.status());
            assertEquals(expected, target.get());
            assertEquals("runtime_creative", resolvedWorld.get());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsNonVerifiedWorldBeforeInvokingTeleportGateway() {
        final PluginIoExecutor executor = new PluginIoExecutor("WarpTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final UUID playerId = UUID.randomUUID();
            metadata.adopt("creative", true).join();
            metadata.update("creative", world -> world.withWarp(new WorldWarp(
                "spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
            ))).join();
            metadata.classifyLoadedIdentity(new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.randomUUID(),
                WorldEnvironment.NORMAL,
                0L,
                true
            )).join();
            final AtomicInteger gatewayCalls = new AtomicInteger();
            final WarpService service = new WarpService(metadata, new WorldAccessPolicy());

            final WarpService.TeleportResult result = service.teleport(
                playerId,
                "creative",
                "spawn",
                true,
                (player, world, warp) -> {
                    gatewayCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                }
            ).join();

            assertEquals(WarpService.TeleportStatus.DENIED, result.status());
            assertEquals(0, gatewayCalls.get());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }
}