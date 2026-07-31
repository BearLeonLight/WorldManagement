package io.github.bearl.worldmanagement.warp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.WorldWarp;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class PaperWarpTeleportGatewayTest {

    private static final UUID WORLD_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final VerifiedWorldRef TARGET = new VerifiedWorldRef("creative", "minecraft:creative", WORLD_UUID);
    private static final WorldWarp WARP = new WorldWarp(
        "spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
    );

    @Test
    void shutdownCompletesPendingTeleportAndRejectsNewOperations() {
        final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
        final PaperWarpTeleportGateway gateway = new PaperWarpTeleportGateway(plugin(), dispatcher);

        final CompletableFuture<Boolean> pending = gateway.teleport(PLAYER_UUID, TARGET, WARP);
        assertFalse(pending.isDone());

        gateway.beginShutdown();

        assertTrue(pending.isDone());
        assertFalse(pending.resultNow());
        assertFalse(gateway.teleport(PLAYER_UUID, TARGET, WARP).resultNow());
    }

    @Test
    void shutdownWaitsForAnAlreadySubmittedPaperTeleport() {
        final CompletableFuture<Boolean> paperTeleport = new CompletableFuture<>();
        final Player player = player(paperTeleport);
        final World world = world();
        final ImmediateDispatcher dispatcher = new ImmediateDispatcher();
        final PaperWarpTeleportGateway gateway = new PaperWarpTeleportGateway(
            plugin(), dispatcher, resolver(world, player)
        );

        final CompletableFuture<Boolean> result = gateway.teleport(PLAYER_UUID, TARGET, WARP);
        final CompletableFuture<Void> shutdown = gateway.beginShutdown();

        assertTrue(result.isDone());
        assertFalse(result.resultNow());
        assertFalse(shutdown.isDone());

        paperTeleport.complete(true);

        assertTrue(shutdown.isDone());
    }

    @Test
    void synchronousEntitySchedulingFailureCompletesTheOperation() {
        final ThrowingEntityDispatcher dispatcher = new ThrowingEntityDispatcher();
        final PaperWarpTeleportGateway gateway = new PaperWarpTeleportGateway(
            plugin(), dispatcher, resolver(world(), player(new CompletableFuture<>()))
        );

        final CompletableFuture<Boolean> result = gateway.teleport(PLAYER_UUID, TARGET, WARP);

        assertTrue(result.isDone());
        assertFalse(result.resultNow());
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, arguments) -> null
        );
    }

    private static PaperWarpTeleportGateway.RuntimeResolver resolver(final World world, final Player player) {
        return new PaperWarpTeleportGateway.RuntimeResolver() {
            @Override
            public World world(final NamespacedKey key) {
                return world;
            }

            @Override
            public Player player(final UUID playerId) {
                return player;
            }
        };
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_UUID;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static Player player(final CompletableFuture<Boolean> paperTeleport) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "teleportAsync" -> paperTeleport;
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

    private static final class ManualGlobalDispatcher implements WorldThreadDispatcher {

        private final List<Runnable> globalTasks = new ArrayList<>();

        @Override
        public void executeGlobal(final Runnable task) {
            globalTasks.add(task);
        }

        @Override
        public void executeAt(final Location location, final Runnable task) { }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            return false;
        }

        @Override
        public void cancelOwnedTasks() { }
    }

    private static class ImmediateDispatcher implements WorldThreadDispatcher {
        @Override
        public void executeGlobal(final Runnable task) {
            task.run();
        }

        @Override
        public void executeAt(final Location location, final Runnable task) { }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() { }
    }

    private static final class ThrowingEntityDispatcher extends ImmediateDispatcher {
        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            throw new IllegalStateException("scheduler rejected");
        }
    }
}