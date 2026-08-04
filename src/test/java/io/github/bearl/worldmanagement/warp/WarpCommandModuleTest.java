package io.github.bearl.worldmanagement.warp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldWarp;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

    @Test
    void reportsSpecificTeleportRejectionReasons() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("WarpCommandModuleTest");
        try {
            final UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final UUID worldUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final ImmediateDispatcher dispatcher = new ImmediateDispatcher();
            final WarpService warps = new WarpService(metadata, new WorldAccessPolicy());
            final WarpCommandModule module = new WarpCommandModule(
                metadata,
                new WorldNameValidator(),
                dispatcher,
                warps,
                (ignoredPlayer, ignoredTarget, ignoredWarp) -> CompletableFuture.completedFuture(false),
                true,
                new OnlinePlayerSnapshot(),
                messages(),
                new CommandMessageSender(dispatcher)
            );

            assertReplyContains(module, playerId, "missing", "spawn", "不在管理中");

            final WorldIdentitySnapshot identity = new WorldIdentitySnapshot(
                "minecraft:overworld", worldUuid, WorldEnvironment.NORMAL, 42L, true
            );
            metadata.adopt(identity, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            assertReplyContains(module, playerId, "overworld", "spawn", "找不到傳送點");

            final WorldWarp warp = new WorldWarp(
                "spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
            );
            warps.set("overworld", warp).join();
            metadata.update("overworld", world -> world.withOwner(UUID.randomUUID().toString())).join();
            assertReplyContains(module, playerId, "overworld", "spawn", "沒有使用傳送點");

            metadata.update("overworld", world -> world.withOwner(WorldMetadata.SERVER_OWNER)).join();
            assertReplyContains(module, playerId, "overworld", "spawn", "傳送失敗");
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reportsInvalidWarpNameRaisedInsideScheduledCallback() throws Exception {
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
            final DeferredDispatcher dispatcher = new DeferredDispatcher();
            final World runtimeWorld = world(worldUuid);
            final CapturingLocatedPlayer player = new CapturingLocatedPlayer(
                playerId, runtimeWorld, new Location(runtimeWorld, 0, 64, 0)
            );
            final WarpCommandModule module = new WarpCommandModule(
                metadata,
                new WorldNameValidator(),
                dispatcher,
                new WarpService(metadata, new WorldAccessPolicy()),
                (ignoredPlayer, ignoredTarget, ignoredWarp) -> CompletableFuture.completedFuture(false),
                true,
                new OnlinePlayerSnapshot(),
                messages(),
                new CommandMessageSender(dispatcher)
            );

            assertTrue(module.execute(player.sender(),
                new String[] {"warp", "set", "overworld", "invalid!name", "PUBLIC"}));
            assertDoesNotThrow(dispatcher::runNext);

            final String reply = PlainTextComponentSerializer.plainText().serialize(player.message());
            assertTrue(reply.contains("傳送點名稱或可見性無效"), reply);
            assertTrue(metadata.managedWorld("overworld").orElseThrow().warps().isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static void assertReplyContains(
        final WarpCommandModule module,
        final UUID playerId,
        final String worldName,
        final String warpName,
        final String expected
    ) throws Exception {
        final CapturingPlayer player = new CapturingPlayer(playerId);
        assertTrue(module.execute(player.sender(), new String[] {"warp", "tp", worldName, warpName}));
        final String reply = PlainTextComponentSerializer.plainText().serialize(player.message());
        assertTrue(reply.contains(expected), reply);
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
                case "isLoaded" -> true;
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

    private static final class CapturingPlayer {
        private final CompletableFuture<Component> message = new CompletableFuture<>();
        private final Player sender;

        private CapturingPlayer(final UUID playerId) {
            sender = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return playerId;
                    if (method.getName().equals("getName")) return "WarpFixture";
                    if (method.getName().equals("hasPermission")) {
                        return "worldmanagement.command.warp".equals(arguments[0]);
                    }
                    if (method.getName().equals("sendMessage") && arguments != null) {
                        for (final Object argument : arguments) {
                            if (argument instanceof Component component) message.complete(component);
                        }
                    }
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return defaultValue(method.getReturnType());
                }
            );
        }

        private Player sender() {
            return sender;
        }

        private Component message() throws Exception {
            return message.get(2, TimeUnit.SECONDS);
        }
    }

    private static final class CapturingLocatedPlayer {
        private final CompletableFuture<Component> message = new CompletableFuture<>();
        @SuppressWarnings("unused")
        private final World runtimeWorld;
        private final Player sender;

        private CapturingLocatedPlayer(final UUID playerId, final World runtimeWorld, final Location location) {
            this.runtimeWorld = runtimeWorld;
            sender = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return playerId;
                    if (method.getName().equals("getName")) return "WarpFixture";
                    if (method.getName().equals("getLocation")) return location;
                    if (method.getName().equals("hasPermission")) return true;
                    if (method.getName().equals("sendMessage") && arguments != null) {
                        for (final Object argument : arguments) {
                            if (argument instanceof Component component) message.complete(component);
                        }
                    }
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return defaultValue(method.getReturnType());
                }
            );
        }

        private Player sender() {
            return sender;
        }

        private Component message() throws Exception {
            return message.get(2, TimeUnit.SECONDS);
        }
    }

    private static final class DeferredDispatcher implements WorldThreadDispatcher {
        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

        @Override
        public void executeGlobal(final Runnable task) {
            tasks.addLast(task);
        }

        @Override
        public void executeAt(final Location location, final Runnable task) {
            tasks.addLast(task);
        }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            tasks.addLast(task);
            return true;
        }

        private void runNext() {
            tasks.removeFirst().run();
        }

        @Override
        public void cancelOwnedTasks() { }
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