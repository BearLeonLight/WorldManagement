package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldGeneratorReference;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

final class PaperWorldRuntimeGatewayTest {

    private static final UUID SOURCE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void mapsPaperCreationSettingsToWorldCreator() {
        final PaperWorldRuntimeGateway gateway = gateway(
            world("source", SOURCE_UUID, List.of()), world("target", TARGET_UUID, List.of())
        );
        final WorldCreationRequest request = new WorldCreationRequest(
            "creative",
            WorldRuntimeGateway.WorldEnvironment.NETHER,
            WorldRuntimeGateway.WorldType.FLAT,
            java.util.OptionalLong.of(8675309L),
            Optional.empty(),
            Optional.of("{\"layers\":[],\"biome\":\"plains\"}"),
            false,
            false,
            Optional.empty(),
            Optional.of(new WorldSpawnPosition(12.5, 80.0, -4.5, 90.0f, 15.0f)),
            false
        );

        final WorldCreator creator = gateway.worldCreator(request);

        assertEquals(NamespacedKey.minecraft("creative"), creator.key());
        assertEquals(World.Environment.NETHER, creator.environment());
        assertEquals(org.bukkit.WorldType.FLAT, creator.type());
        assertEquals(8675309L, creator.seed());
        assertEquals("{\"layers\":[],\"biome\":\"plains\"}", creator.generatorSettings());
        assertFalse(creator.generateStructures());
        assertFalse(creator.bonusChest());
        assertEquals(io.papermc.paper.math.Position.fine(12.5, 80.0, -4.5), creator.forcedSpawnPosition());
        assertEquals(90.0f, creator.forcedSpawnYaw());
        assertEquals(15.0f, creator.forcedSpawnPitch());
    }

    @Test
    void mapsBonusChestWhenForcedSpawnIsAbsent() {
        final PaperWorldRuntimeGateway gateway = gateway(
            world("source", SOURCE_UUID, List.of()), world("target", TARGET_UUID, List.of())
        );
        final WorldCreationRequest request = new WorldCreationRequest(
            "creative",
            WorldRuntimeGateway.WorldEnvironment.NORMAL,
            WorldRuntimeGateway.WorldType.NORMAL,
            java.util.OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            true,
            true,
            Optional.empty(),
            Optional.empty(),
            false
        );

        assertTrue(gateway.worldCreator(request).bonusChest());
    }

    @Test
    void rejectsBonusChestWithForcedSpawnBecausePaperCannotHonorBoth() {
        assertThrows(IllegalArgumentException.class, () -> new WorldCreationRequest(
            "creative",
            WorldRuntimeGateway.WorldEnvironment.NORMAL,
            WorldRuntimeGateway.WorldType.NORMAL,
            java.util.OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            true,
            true,
            Optional.empty(),
            Optional.of(new WorldSpawnPosition(0, 64, 0, 0, 0)),
            false
        ));
    }

    @Test
    void defaultRuntimeGatewayRejectsUnsupportedBiomeProvider() {
        final WorldRuntimeGateway gateway = (WorldRuntimeGateway) Proxy.newProxyInstance(
            WorldRuntimeGateway.class.getClassLoader(),
            new Class<?>[] {WorldRuntimeGateway.class},
            (proxy, method, arguments) -> method.isDefault()
                ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, arguments)
                : defaultValue(method.getReturnType())
        );
        final WorldCreationRequest request = new WorldCreationRequest(
            "creative",
            WorldRuntimeGateway.WorldEnvironment.NORMAL,
            WorldRuntimeGateway.WorldType.NORMAL,
            java.util.OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            true,
            false,
            Optional.of(WorldGeneratorReference.parse("Terra:climate")),
            Optional.empty(),
            false
        );

        assertThrows(UnsupportedOperationException.class, () -> gateway.create(request));
    }

    @Test
    void failsManagedLoadBeforeWorldCreationWhenBiomeProviderIsUnavailable() {
        final PaperWorldRuntimeGateway gateway = gateway(
            world("source", SOURCE_UUID, List.of()), world("target", TARGET_UUID, List.of())
        );
        final WorldStorageGateway.LoadClaim claim = new WorldStorageGateway.LoadClaim() {
            @Override
            public VerifiedWorldRef world() {
                return new VerifiedWorldRef("creative", "minecraft:creative", SOURCE_UUID);
            }

            @Override
            public long metadataVersion() {
                return 1L;
            }
        };

        final WorldRuntimeGateway.LoadResult result = gateway.load(
            claim,
            WorldRuntimeGateway.WorldEnvironment.NORMAL,
            Optional.empty(),
            Optional.of(WorldGeneratorReference.parse("MissingProvider:climate"))
        );

        assertTrue(result.world().isEmpty());
        assertFalse(result.newlyLoaded());
    }

    @Test
    void rejectsRacingLoadedRuntimeWhenProviderProvenanceCannotBeProven() {
        final PaperWorldRuntimeGateway gateway = gateway(
            world("source", SOURCE_UUID, List.of()), world("target", TARGET_UUID, List.of())
        );
        final WorldStorageGateway.LoadClaim claim = new WorldStorageGateway.LoadClaim() {
            @Override
            public VerifiedWorldRef world() {
                return new VerifiedWorldRef("source", "minecraft:source", SOURCE_UUID);
            }

            @Override
            public long metadataVersion() {
                return 1L;
            }
        };

        final WorldRuntimeGateway.LoadResult result = gateway.load(
            claim,
            WorldRuntimeGateway.WorldEnvironment.NORMAL,
            Optional.empty(),
            Optional.of(WorldGeneratorReference.parse("Terra:climate"))
        );

        assertTrue(result.world().isEmpty());
        assertFalse(result.newlyLoaded());
    }

    @Test
    void shutdownWaitsForAlreadySubmittedPaperTeleport() {
        final CompletableFuture<Boolean> paperTeleport = new CompletableFuture<>();
        final AtomicReference<World> sourceReference = new AtomicReference<>();
        final Player player = player(sourceReference, paperTeleport);
        final World source = world("source", SOURCE_UUID, List.of(player));
        final World target = world("target", TARGET_UUID, List.of());
        sourceReference.set(source);
        final PaperWorldRuntimeGateway gateway = gateway(source, target);

        final CompletableFuture<Boolean> result = gateway.teleportPlayersToWorld(
            lifecycleWorld("source", SOURCE_UUID), lifecycleWorld("target", TARGET_UUID)
        );
        final CompletableFuture<Void> shutdown = gateway.beginShutdown();

        assertTrue(result.isDone());
        assertFalse(result.resultNow());
        assertFalse(shutdown.isDone());

        paperTeleport.complete(true);

        assertTrue(shutdown.isDone());
    }

    private static PaperWorldRuntimeGateway gateway(final World source, final World target) {
        final Plugin plugin = plugin();
        final WorldGeneratorCatalog generators = new WorldGeneratorCatalog(
            proxy(PluginManager.class), ignored -> { }
        );
        return new PaperWorldRuntimeGateway(
            plugin,
            new LoadedWorldCatalog(),
            generators,
            new PaperWorldRuntimeGateway.RuntimeResolver() {
                @Override
                public World world(final NamespacedKey key) {
                    return switch (key.getKey()) {
                        case "source" -> source;
                        case "target" -> target;
                        default -> null;
                    };
                }

                @Override
                public Optional<World> primaryWorld() {
                    return Optional.of(target);
                }
            }
        );
    }

    private static WorldRuntimeGateway.LifecycleWorld lifecycleWorld(final String name, final UUID uuid) {
        return new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot(
                "minecraft:" + name, uuid, WorldEnvironment.NORMAL, 42L, true
            ),
            LifecycleCapability.MANAGED,
            name
        );
    }

    private static World world(final String name, final UUID uuid, final List<Player> players) {
        final NamespacedKey key = NamespacedKey.minecraft(name);
        final AtomicReference<World> self = new AtomicReference<>();
        final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> name;
                case "getKey" -> key;
                case "getUID" -> uuid;
                case "getEnvironment" -> World.Environment.NORMAL;
                case "getSeed" -> 42L;
                case "canGenerateStructures" -> true;
                case "getPlayers" -> players;
                case "getPlayerCount" -> players.size();
                case "getSpawnLocation" -> new Location(self.get(), 0.5, 64, 0.5);
                default -> defaultValue(method.getReturnType());
            }
        );
        self.set(world);
        return world;
    }

    private static Player player(
        final AtomicReference<World> source,
        final CompletableFuture<Boolean> paperTeleport
    ) {
        final EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
            EntityScheduler.class.getClassLoader(),
            new Class<?>[] {EntityScheduler.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("execute")) {
                    ((Runnable) arguments[1]).run();
                    return true;
                }
                return defaultValue(method.getReturnType());
            }
        );
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getWorld" -> source.get();
                case "getScheduler" -> scheduler;
                case "teleportAsync" -> paperTeleport;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static Plugin plugin() {
        return proxy(Plugin.class);
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
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}