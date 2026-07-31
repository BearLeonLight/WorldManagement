package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class WorldLifecycleReconcilerTest {

    private final PluginIoExecutor executor = new PluginIoExecutor("ReconcilerTest");

    @AfterEach
    void closeExecutor() {
        executor.shutdown(Duration.ofSeconds(1));
    }

    @Test
    void retriesWithBoundedDelaysUntilTheRuntimeMatchesIntent() {
        final WorldManagementService metadata = metadataService();
        adopt(metadata, "creative", LifecycleCapability.MANAGED);
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger loads = new AtomicInteger();
        final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> load = ignored ->
            CompletableFuture.completedFuture(result(loads.incrementAndGet() == 4
                ? WorldLifecycleCoordinator.LifecycleStatus.LOADED
                : WorldLifecycleCoordinator.LifecycleStatus.FAILED));
        final WorldLifecycleReconciler reconciler = new WorldLifecycleReconciler(
            metadata,
            load,
            ignored -> CompletableFuture.completedFuture(result(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED)),
            dispatcher
        );

        final CompletableFuture<Void> reconciliation = reconciler.reconcileWorld("creative");

        assertEquals(1, loads.get());
        assertEquals(List.of(Duration.ofSeconds(1)), dispatcher.delays());
        assertFalse(reconciliation.isDone());

        dispatcher.runNext();
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5)), dispatcher.delays());
        dispatcher.runNext();
        assertEquals(List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)
        ), dispatcher.delays());
        dispatcher.runNext();

        assertEquals(4, loads.get());
        assertTrue(reconciliation.isDone());
    }

    @Test
    void newerGenerationInvalidatesAnOlderScheduledRetry() {
        final WorldManagementService metadata = metadataService();
        adopt(metadata, "creative", LifecycleCapability.MANAGED);
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger loads = new AtomicInteger();
        final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> load = ignored ->
            CompletableFuture.completedFuture(result(loads.incrementAndGet() == 1
                ? WorldLifecycleCoordinator.LifecycleStatus.FAILED
                : WorldLifecycleCoordinator.LifecycleStatus.LOADED));
        final WorldLifecycleReconciler reconciler = new WorldLifecycleReconciler(
            metadata,
            load,
            ignored -> CompletableFuture.completedFuture(result(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED)),
            dispatcher
        );

        final CompletableFuture<Void> stale = reconciler.reconcileWorld("creative");
        final CompletableFuture<Void> current = reconciler.reconcileWorld("creative");
        dispatcher.runNext();

        assertEquals(2, loads.get());
        assertTrue(stale.isDone());
        assertTrue(current.isDone());
    }

    @Test
    void skipsNonVerifiedAndExternalOnlyMetadata() {
        final WorldManagementService metadata = metadataService();
        adopt(metadata, "external", LifecycleCapability.EXTERNAL_ONLY);
        adopt(metadata, "conflict", LifecycleCapability.MANAGED);
        metadata.classifyLoadedIdentity(new WorldIdentitySnapshot(
            "minecraft:conflict",
            UUID.fromString("99999999-9999-9999-9999-999999999999"),
            WorldEnvironment.NORMAL,
            42L,
            true
        )).join();
        final AtomicInteger operations = new AtomicInteger();
        final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> operation = ignored -> {
            operations.incrementAndGet();
            return CompletableFuture.completedFuture(result(WorldLifecycleCoordinator.LifecycleStatus.FAILED));
        };
        final WorldLifecycleReconciler reconciler = new WorldLifecycleReconciler(
            metadata, operation, operation, new RecordingDispatcher()
        );

        reconciler.reconcileWorld("external").join();
        reconciler.reconcileWorld("conflict").join();

        assertEquals(0, operations.get());
    }

    @Test
    void shutdownCancelsScheduledRetriesAndRejectsFurtherWork() {
        final WorldManagementService metadata = metadataService();
        adopt(metadata, "creative", LifecycleCapability.MANAGED);
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger loads = new AtomicInteger();
        final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> load = ignored -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(result(WorldLifecycleCoordinator.LifecycleStatus.FAILED));
        };
        final WorldLifecycleReconciler reconciler = new WorldLifecycleReconciler(
            metadata,
            load,
            ignored -> CompletableFuture.completedFuture(result(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED)),
            dispatcher
        );

        final CompletableFuture<Void> pending = reconciler.reconcileWorld("creative");
        reconciler.beginShutdown().join();
        dispatcher.runNext();
        reconciler.reconcileWorld("creative").join();

        assertTrue(pending.isDone());
        assertEquals(1, loads.get());
    }

    private WorldManagementService metadataService() {
        final WorldManagementService metadata = new WorldManagementService(
            executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
        );
        metadata.load().join();
        return metadata;
    }

    private static void adopt(
        final WorldManagementService metadata,
        final String worldId,
        final LifecycleCapability capability
    ) {
        metadata.adopt(
            new WorldIdentitySnapshot(
                "minecraft:" + worldId,
                UUID.nameUUIDFromBytes(worldId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                WorldEnvironment.NORMAL,
                42L,
                true
            ),
            capability,
            Optional.empty(),
            true,
            null
        ).join();
    }

    private static WorldLifecycleCoordinator.LifecycleResult result(
        final WorldLifecycleCoordinator.LifecycleStatus status
    ) {
        return new WorldLifecycleCoordinator.LifecycleResult(status);
    }

    private static final class RecordingDispatcher implements WorldThreadDispatcher {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public void executeGlobal(final Runnable task) {
            tasks.add(task);
        }

        @Override
        public void executeGlobalLater(final Duration delay, final Runnable task, final Runnable cancelledTask) {
            delays.add(delay);
            tasks.add(task);
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
            tasks.clear();
        }

        private List<Duration> delays() {
            return List.copyOf(delays);
        }

        private void runNext() {
            if (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }
}
