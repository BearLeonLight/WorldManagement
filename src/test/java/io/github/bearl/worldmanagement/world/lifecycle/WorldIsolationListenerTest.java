package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.protection.PlayerIsolationService;
import io.github.bearl.worldmanagement.protection.TeleportBypassTokens;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

final class WorldIsolationListenerTest {

    @Test
    void rejectsIsolatedEntryAndRelocatesPlayersWithoutProtectionGovernance() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldIsolationListenerTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final WorldIdentitySnapshot accepted = identity(
                "creative", "11111111-1111-1111-1111-111111111111"
            );
            final WorldIdentitySnapshot replacement = identity(
                "creative", "22222222-2222-2222-2222-222222222222"
            );
            final WorldIdentitySnapshot fallbackIdentity = identity(
                "lobby", "33333333-3333-3333-3333-333333333333"
            );
            metadata.adopt(accepted, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.classifyLoadedIdentity(replacement, LifecycleCapability.MANAGED).join();
            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            loadedWorlds.loaded(new WorldRuntimeGateway.LifecycleWorld(
                fallbackIdentity, LifecycleCapability.MANAGED
            ));
            final AtomicInteger teleports = new AtomicInteger();
            final PlayerIsolationService isolation = new PlayerIsolationService(
                metadata,
                loadedWorlds,
                Optional.of(new VerifiedWorldRef("lobby", fallbackIdentity.paperKey(), fallbackIdentity.worldUuid())),
                (playerId, target, coordinates) -> {
                    teleports.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                },
                new TeleportBypassTokens(),
                ignored -> { }
            );
            final WorldIsolationListener listener = new WorldIsolationListener(metadata, isolation);
            final World source = world(fallbackIdentity);
            final World isolated = world(replacement);
            final Player player = player(isolated);

            final PlayerTeleportEvent entry = new PlayerTeleportEvent(
                player,
                new Location(source, 0.0, 64.0, 0.0),
                new Location(isolated, 0.0, 64.0, 0.0),
                PlayerTeleportEvent.TeleportCause.PLUGIN
            );
            listener.onTeleport(entry);
            listener.onChangedWorld(new PlayerChangedWorldEvent(player, source));

            assertTrue(entry.isCancelled());
            assertEquals(1, teleports.get());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void detachedWorldIsNotTreatedAsIdentityIsolation() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldIsolationListenerTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final WorldIdentitySnapshot detachedIdentity = identity(
                "creative", "44444444-4444-4444-4444-444444444444"
            );
            metadata.adopt(detachedIdentity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.update("creative", world -> world.withManagementState(WorldManagementState.DETACHED)).join();
            final AtomicInteger teleports = new AtomicInteger();
            final PlayerIsolationService isolation = new PlayerIsolationService(
                metadata,
                new LoadedWorldCatalog(),
                Optional.empty(),
                (playerId, target, coordinates) -> {
                    teleports.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                },
                new TeleportBypassTokens(),
                ignored -> { }
            );
            final WorldIsolationListener listener = new WorldIsolationListener(metadata, isolation);
            final World source = world(identity(
                "lobby", "55555555-5555-5555-5555-555555555555"
            ));
            final World detached = world(detachedIdentity);
            final Player player = player(detached);
            final PlayerTeleportEvent entry = new PlayerTeleportEvent(
                player,
                new Location(source, 0.0, 64.0, 0.0),
                new Location(detached, 0.0, 64.0, 0.0),
                PlayerTeleportEvent.TeleportCause.PLUGIN
            );

            listener.onTeleport(entry);
            listener.onChangedWorld(new PlayerChangedWorldEvent(player, source));

            assertFalse(entry.isCancelled());
            assertEquals(0, teleports.get());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static WorldIdentitySnapshot identity(final String worldId, final String uuid) {
        return new WorldIdentitySnapshot(
            "minecraft:" + worldId,
            UUID.fromString(uuid),
            WorldEnvironment.NORMAL,
            42L,
            true
        );
    }

    private static World world(final WorldIdentitySnapshot identity) {
        final NamespacedKey key = NamespacedKey.fromString(identity.paperKey());
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> identity.keyValue();
                case "getKey" -> key;
                case "getUID" -> identity.worldUuid();
                case "getEnvironment" -> World.Environment.NORMAL;
                case "getSeed" -> identity.seed();
                case "canGenerateStructures" -> identity.generateStructures();
                case "getGenerator", "getBiomeProvider" -> null;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Player player(final World world) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> UUID.fromString("66666666-6666-6666-6666-666666666666");
                case "getWorld" -> world;
                case "hasPermission" -> true;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}