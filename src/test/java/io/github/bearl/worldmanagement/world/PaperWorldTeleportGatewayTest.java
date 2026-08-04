package io.github.bearl.worldmanagement.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class PaperWorldTeleportGatewayTest {

    private static final UUID WORLD_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final VerifiedWorldRef TARGET = new VerifiedWorldRef(
        "creative", "minecraft:creative", WORLD_UUID
    );

    @Test
    void shutdownWaitsForAnAlreadySubmittedPaperTeleport() {
        final CompletableFuture<Boolean> paperTeleport = new CompletableFuture<>();
        final PaperWorldTeleportGateway gateway = new PaperWorldTeleportGateway(
            new ImmediateDispatcher(), resolver(world(), player(paperTeleport))
        );

        final CompletableFuture<Boolean> result = gateway.teleport(PLAYER_UUID, TARGET, Optional.empty());
        final CompletableFuture<Void> shutdown = gateway.beginShutdown();

        assertTrue(result.isDone());
        assertFalse(result.resultNow());
        assertFalse(shutdown.isDone());

        paperTeleport.complete(true);

        assertTrue(shutdown.isDone());
    }

    @Test
    void shutdownRejectsQueuedAndNewTeleportsWithoutSubmittingThem() {
        final DeferredGlobalDispatcher dispatcher = new DeferredGlobalDispatcher();
        final AtomicInteger submissions = new AtomicInteger();
        final PaperWorldTeleportGateway gateway = new PaperWorldTeleportGateway(
            dispatcher, resolver(world(), player(new CompletableFuture<>(), submissions))
        );

        final CompletableFuture<Boolean> queued = gateway.teleport(PLAYER_UUID, TARGET, Optional.empty());
        final CompletableFuture<Void> shutdown = gateway.beginShutdown();

        assertTrue(queued.isDone());
        assertFalse(queued.resultNow());
        assertTrue(shutdown.isDone());
        assertFalse(gateway.teleport(PLAYER_UUID, TARGET, Optional.empty()).join());

        dispatcher.runGlobal();

        assertTrue(shutdown.isDone());
        assertTrue(submissions.get() == 0);
    }

    @Test
    void synchronousSchedulerFailuresCompleteWithoutLeakingPendingOperations() {
        final PaperWorldTeleportGateway globalFailure = new PaperWorldTeleportGateway(
            new ThrowingDispatcher(true), resolver(world(), player(new CompletableFuture<>()))
        );
        final PaperWorldTeleportGateway entityFailure = new PaperWorldTeleportGateway(
            new ThrowingDispatcher(false), resolver(world(), player(new CompletableFuture<>()))
        );

        assertFalse(globalFailure.teleport(PLAYER_UUID, TARGET, Optional.empty()).join());
        assertTrue(globalFailure.beginShutdown().isDone());
        assertFalse(entityFailure.teleport(PLAYER_UUID, TARGET, Optional.empty()).join());
        assertTrue(entityFailure.beginShutdown().isDone());
    }

    private static PaperWorldTeleportGateway.RuntimeResolver resolver(final World world, final Player player) {
        return new PaperWorldTeleportGateway.RuntimeResolver() {
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
                case "getSpawnLocation" -> new Location((World) proxy, 0, 64, 0);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Player player(final CompletableFuture<Boolean> paperTeleport) {
        return player(paperTeleport, new AtomicInteger());
    }

    private static Player player(
        final CompletableFuture<Boolean> paperTeleport,
        final AtomicInteger submissions
    ) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "teleportAsync" -> {
                    submissions.incrementAndGet();
                    yield paperTeleport;
                }
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
        public void cancelOwnedTasks() { }
    }

    private static final class DeferredGlobalDispatcher implements WorldThreadDispatcher {
        private Runnable globalTask;

        @Override
        public void executeGlobal(final Runnable task) {
            globalTask = task;
        }

        @Override
        public void executeGlobal(final Runnable task, final Runnable cancelledTask) {
            globalTask = task;
        }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() { }

        private void runGlobal() {
            globalTask.run();
        }
    }

    private static final class ThrowingDispatcher implements WorldThreadDispatcher {
        private final boolean failGlobal;

        private ThrowingDispatcher(final boolean failGlobal) {
            this.failGlobal = failGlobal;
        }

        @Override
        public void executeGlobal(final Runnable task) {
            executeGlobal(task, () -> { });
        }

        @Override
        public void executeGlobal(final Runnable task, final Runnable cancelledTask) {
            if (failGlobal) {
                throw new IllegalStateException("global scheduler failure");
            }
            task.run();
        }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            throw new IllegalStateException("entity scheduler failure");
        }

        @Override
        public void cancelOwnedTasks() { }
    }
}