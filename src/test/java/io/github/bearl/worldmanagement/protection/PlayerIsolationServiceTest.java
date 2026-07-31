package io.github.bearl.worldmanagement.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldRuntimeResolution;
import io.github.bearl.worldmanagement.world.WorldTeleportGateway;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class PlayerIsolationServiceTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FALLBACK_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void relocatesAnIsolatedPlayerOnlyToTheExactVerifiedFallback() {
        final PluginIoExecutor executor = new PluginIoExecutor("PlayerIsolationTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final WorldIdentitySnapshot fallbackIdentity = new WorldIdentitySnapshot(
                "minecraft:lobby", FALLBACK_UUID, WorldEnvironment.NORMAL, 42L, true
            );
            metadata.adopt(fallbackIdentity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            loadedWorlds.loaded(new WorldRuntimeGateway.LifecycleWorld(
                fallbackIdentity, LifecycleCapability.MANAGED
            ));
            final VerifiedWorldRef fallback = new VerifiedWorldRef(
                "lobby", "minecraft:lobby", FALLBACK_UUID
            );
            final TeleportBypassTokens tokens = new TeleportBypassTokens();
            final AtomicReference<VerifiedWorldRef> requestedTarget = new AtomicReference<>();
            final CompletableFuture<Boolean> teleport = new CompletableFuture<>();
            final WorldTeleportGateway gateway = (playerId, target, coordinates) -> {
                requestedTarget.set(target);
                return teleport;
            };
            final PlayerIsolationService isolation = new PlayerIsolationService(
                metadata, loadedWorlds, Optional.of(fallback), gateway, tokens, ignored -> { }
            );
            final WorldMetadata conflicted = WorldMetadata.createDefault("isolated", true).withObservedIdentity(
                new WorldIdentitySnapshot(
                    "minecraft:isolated", UUID.randomUUID(), WorldEnvironment.NORMAL, 42L, true
                )
            );

            assertEquals(
                PlayerIsolationService.RelocationStatus.SCHEDULED,
                isolation.relocateIfNeeded(PLAYER_ID, WorldRuntimeResolution.isolated(conflicted), false)
            );
            assertEquals(fallback, requestedTarget.get());
            assertTrue(tokens.consume(PLAYER_ID, fallback));

            teleport.complete(true);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void bypassSkipsRelocationAndUnavailableFallbackWarningsAreRateLimited() {
        final PluginIoExecutor executor = new PluginIoExecutor("PlayerIsolationTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final WorldIdentitySnapshot fallbackIdentity = new WorldIdentitySnapshot(
                "minecraft:lobby", FALLBACK_UUID, WorldEnvironment.NORMAL, 42L, true
            );
            metadata.adopt(fallbackIdentity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            loadedWorlds.loaded(new WorldRuntimeGateway.LifecycleWorld(
                new WorldIdentitySnapshot(
                    fallbackIdentity.paperKey(), fallbackIdentity.worldUuid(), fallbackIdentity.environment(),
                    99L, fallbackIdentity.generateStructures()
                ),
                LifecycleCapability.MANAGED
            ));
            final AtomicInteger teleportCalls = new AtomicInteger();
            final ArrayList<String> warnings = new ArrayList<>();
            final AtomicLong now = new AtomicLong(100L);
            final PlayerIsolationService isolation = new PlayerIsolationService(
                metadata,
                loadedWorlds,
                Optional.of(new VerifiedWorldRef("lobby", "minecraft:lobby", FALLBACK_UUID)),
                (playerId, target, coordinates) -> {
                    teleportCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                },
                new TeleportBypassTokens(),
                warnings::add,
                now::get,
                Duration.ofNanos(10L)
            );
            final WorldMetadata conflicted = WorldMetadata.createDefault("isolated", true).withObservedIdentity(
                new WorldIdentitySnapshot(
                    "minecraft:isolated", UUID.randomUUID(), WorldEnvironment.NORMAL, 42L, true
                )
            );
            final WorldRuntimeResolution isolated = WorldRuntimeResolution.isolated(conflicted);

            assertEquals(PlayerIsolationService.RelocationStatus.BYPASSED,
                isolation.relocateIfNeeded(PLAYER_ID, isolated, true));
            assertEquals(PlayerIsolationService.RelocationStatus.FALLBACK_UNAVAILABLE,
                isolation.relocateIfNeeded(PLAYER_ID, isolated, false));
            assertEquals(PlayerIsolationService.RelocationStatus.FALLBACK_UNAVAILABLE,
                isolation.relocateIfNeeded(PLAYER_ID, isolated, false));
            now.set(110L);
            assertEquals(PlayerIsolationService.RelocationStatus.FALLBACK_UNAVAILABLE,
                isolation.relocateIfNeeded(PLAYER_ID, isolated, false));

            assertEquals(0, teleportCalls.get());
            assertEquals(2, warnings.size());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void existingPlayerScanRechecksPinnedWorldOnEachEntityScheduler() {
        final PluginIoExecutor executor = new PluginIoExecutor("PlayerIsolationTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final WorldIdentitySnapshot fallbackIdentity = new WorldIdentitySnapshot(
                "minecraft:lobby", FALLBACK_UUID, WorldEnvironment.NORMAL, 42L, true
            );
            final WorldIdentitySnapshot accepted = new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                WorldEnvironment.NORMAL,
                42L,
                true
            );
            final WorldIdentitySnapshot replacement = new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                WorldEnvironment.NORMAL,
                42L,
                true
            );
            metadata.adopt(fallbackIdentity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.adopt(accepted, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.classifyLoadedIdentity(replacement).join();
            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            loadedWorlds.loaded(new WorldRuntimeGateway.LifecycleWorld(
                fallbackIdentity, LifecycleCapability.MANAGED
            ));
            loadedWorlds.loaded(new WorldRuntimeGateway.LifecycleWorld(
                replacement, LifecycleCapability.MANAGED
            ));
            final AtomicReference<List<Player>> scannedPlayers = new AtomicReference<>(List.of());
            final World isolatedWorld = paperWorld(replacement, scannedPlayers::get);
            final World fallbackWorld = paperWorld(fallbackIdentity, List::of);
            final Player stillIsolated = player(
                UUID.fromString("55555555-5555-5555-5555-555555555555"), isolatedWorld
            );
            final Player alreadyMoved = player(
                UUID.fromString("66666666-6666-6666-6666-666666666666"), fallbackWorld
            );
            scannedPlayers.set(List.of(stillIsolated, alreadyMoved));
            final RecordingDispatcher dispatcher = new RecordingDispatcher();
            final AtomicInteger teleportCalls = new AtomicInteger();
            final PlayerIsolationService isolation = new PlayerIsolationService(
                metadata,
                loadedWorlds,
                Optional.of(new VerifiedWorldRef("lobby", "minecraft:lobby", FALLBACK_UUID)),
                (playerId, target, coordinates) -> {
                    teleportCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                },
                new TeleportBypassTokens(),
                ignored -> { }
            );

            isolation.relocatePlayersInWorld(
                isolatedWorld,
                new WorldRuntimeGateway.LifecycleWorld(replacement, LifecycleCapability.MANAGED),
                dispatcher
            );

            assertEquals(0, teleportCalls.get());
            assertEquals(2, dispatcher.pendingEntityTasks());
            dispatcher.runAll();
            assertEquals(1, teleportCalls.get());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static World paperWorld(final WorldIdentitySnapshot identity, final Supplier<List<Player>> players) {
        return (World) java.lang.reflect.Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> identity.keyValue();
                case "getKey" -> NamespacedKey.fromString(identity.paperKey());
                case "getUID" -> identity.worldUuid();
                case "getEnvironment" -> World.Environment.valueOf(identity.environment().name());
                case "getSeed" -> identity.seed();
                case "canGenerateStructures" -> identity.generateStructures();
                case "getGenerator", "getBiomeProvider" -> null;
                case "getPlayers" -> players.get();
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Player player(final UUID playerId, final World world) {
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getWorld" -> world;
                case "hasPermission" -> false;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }

    private static final class RecordingDispatcher implements WorldThreadDispatcher {
        private final ArrayDeque<Runnable> entityTasks = new ArrayDeque<>();

        @Override
        public void executeGlobal(final Runnable task) { task.run(); }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            entityTasks.add(task);
            return true;
        }

        @Override
        public void cancelOwnedTasks() { entityTasks.clear(); }

        int pendingEntityTasks() { return entityTasks.size(); }

        void runAll() {
            while (!entityTasks.isEmpty()) entityTasks.removeFirst().run();
        }
    }
}
