package io.github.bearl.worldmanagement.warp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.command.OnlinePlayerSnapshot;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WarpCommandModuleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void setsWarpWhenBukkitRuntimeNameDiffersFromCanonicalWorldId() {
        final PluginIoExecutor executor = new PluginIoExecutor("WarpCommandModuleTest");
        try {
            final UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final UUID worldUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final WorldIdentitySnapshot identity = new WorldIdentitySnapshot(
                "minecraft:overworld", worldUuid, WorldEnvironment.NORMAL, 42L, true
            );
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt(identity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            metadata.update("overworld", world -> world.withOwner(playerId.toString())).join();

            final World runtimeWorld = world(worldUuid);
            final Player player = player(playerId, new Location(runtimeWorld, 12.5, 80.0, -4.5));
            final WarpCommandModule module = new WarpCommandModule(
                metadata,
                new WorldNameValidator(),
                new ImmediateDispatcher(),
                new WarpService(metadata, new WorldAccessPolicy()),
                (ignoredPlayer, ignoredTarget, ignoredWarp) -> java.util.concurrent.CompletableFuture.completedFuture(false),
                true,
                new OnlinePlayerSnapshot(),
                messages(),
                new CommandMessageSender(new ImmediateDispatcher())
            );

            assertTrue(module.execute(player, new String[] {"warp", "set", "overworld", "spawn", "PUBLIC"}));
            executor.submit(() -> null).join();

            final var warp = metadata.managedWorld("overworld").orElseThrow().warps().get("spawn");
            assertEquals(WarpVisibility.PUBLIC, warp.visibility());
            assertEquals(12.5, warp.x());
            assertEquals(80.0, warp.y());
            assertEquals(-4.5, warp.z());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private MessageService messages() {
        final InputStream bundled = WarpCommandModuleTest.class.getResourceAsStream("/messages_zh_TW.yml");
        return MessageService.load(
            temporaryDirectory,
            "zh_TW",
            java.util.Objects.requireNonNull(bundled),
            ignored -> { }
        );
    }

    private static World world(final UUID worldUuid) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> "world";
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "getUID" -> worldUuid;
                case "getEnvironment" -> World.Environment.NORMAL;
                case "getSeed" -> 42L;
                case "canGenerateStructures" -> true;
                case "getGenerator", "getBiomeProvider" -> null;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Player player(final UUID playerId, final Location location) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "WarpFixture";
                case "getLocation" -> location;
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

    private static final class ImmediateDispatcher implements WorldThreadDispatcher {
        @Override
        public void executeGlobal(final Runnable task) {
            task.run();
        }

        @Override
        public void executeAt(final Location location, final Runnable task) {
            task.run();
        }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
        }
    }
}