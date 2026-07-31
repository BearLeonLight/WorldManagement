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

final class WorldIdentityAutoSynchronizerTest {

    private final PluginIoExecutor executor = new PluginIoExecutor("IdentityAutoSyncTest");

    @AfterEach
    void closeExecutor() {
        executor.shutdown(Duration.ofSeconds(1));
    }

    @Test
    void retriesPersistenceFailuresWithBoundedDelays() {
        final WorldRuntimeGateway.LifecycleWorld observed = observed(99L);
        final WorldManagementService metadata = pendingMetadata(observed);
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final LoadedWorldCatalog.Observation generation = catalog.loaded(observed);
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger attempts = new AtomicInteger();
        final Function<WorldRuntimeGateway.LifecycleWorld, CompletableFuture<WorldManagementService.IdentitySyncResult>> sync =
            ignored -> attempts.incrementAndGet() < 4
                ? CompletableFuture.failedFuture(new IllegalStateException("simulated persistence failure"))
                : CompletableFuture.completedFuture(new WorldManagementService.IdentitySyncResult(
                    WorldManagementService.IdentitySyncStatus.SYNCHRONIZED,
                    metadata.metadataWorld("creative").orElseThrow()
                ));
        final WorldIdentityAutoSynchronizer synchronizer = new WorldIdentityAutoSynchronizer(
            metadata, sync, catalog, dispatcher
        );

        final CompletableFuture<Void> completion = synchronizer.synchronize(generation, observed);

        assertEquals(1, attempts.get());
        assertEquals(List.of(Duration.ofSeconds(1)), dispatcher.delays());
        assertFalse(completion.isDone());

        dispatcher.runNext();
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(5)), dispatcher.delays());
        dispatcher.runNext();
        assertEquals(List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)
        ), dispatcher.delays());
        dispatcher.runNext();

        assertEquals(4, attempts.get());
        assertTrue(completion.isDone());
        assertFalse(completion.isCompletedExceptionally());
    }

    @Test
    void newerGenerationInvalidatesOlderRetryAndShutdownRejectsFurtherWork() {
        final WorldRuntimeGateway.LifecycleWorld older = observed(88L);
        final WorldRuntimeGateway.LifecycleWorld newer = observed(99L);
        final WorldManagementService metadata = pendingMetadata(older);
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final LoadedWorldCatalog.Observation olderGeneration = catalog.loaded(older);
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger attempts = new AtomicInteger();
        final WorldIdentityAutoSynchronizer synchronizer = new WorldIdentityAutoSynchronizer(
            metadata,
            ignored -> attempts.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("retry"))
                : CompletableFuture.completedFuture(new WorldManagementService.IdentitySyncResult(
                    WorldManagementService.IdentitySyncStatus.STALE,
                    metadata.metadataWorld("creative").orElseThrow()
                )),
            catalog,
            dispatcher
        );

        final CompletableFuture<Void> stale = synchronizer.synchronize(olderGeneration, older);
        metadata.classifyLoadedIdentity(newer.identity(), newer.lifecycleCapability()).join();
        final LoadedWorldCatalog.Observation newerGeneration = catalog.loaded(newer);
        final CompletableFuture<Void> current = synchronizer.synchronize(newerGeneration, newer);
        dispatcher.runNext();
        synchronizer.beginShutdown().join();
        synchronizer.synchronize(newerGeneration, newer).join();

        assertTrue(stale.isDone());
        assertTrue(current.isDone());
        assertEquals(2, attempts.get());
    }

    @Test
    void startupSubmitsEveryCurrentLoadedSyncPendingWorld() {
        final WorldRuntimeGateway.LifecycleWorld observed = observed(99L);
        final WorldManagementService metadata = pendingMetadata(observed);
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        catalog.replaceAll(List.of(observed));
        final AtomicInteger attempts = new AtomicInteger();
        final WorldIdentityAutoSynchronizer synchronizer = new WorldIdentityAutoSynchronizer(
            metadata,
            ignored -> {
                attempts.incrementAndGet();
                return CompletableFuture.completedFuture(new WorldManagementService.IdentitySyncResult(
                    WorldManagementService.IdentitySyncStatus.SYNCHRONIZED,
                    metadata.metadataWorld("creative").orElseThrow()
                ));
            },
            catalog,
            new RecordingDispatcher()
        );

        synchronizer.synchronizePendingLoadedWorlds().join();

        assertEquals(1, attempts.get());
    }

    private WorldManagementService pendingMetadata(final WorldRuntimeGateway.LifecycleWorld observed) {
        final WorldManagementService metadata = new WorldManagementService(
            executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
        );
        metadata.load().join();
        metadata.adopt(
            new WorldIdentitySnapshot(
                observed.identity().paperKey(),
                observed.identity().worldUuid(),
                observed.identity().environment(),
                42L,
                observed.identity().generateStructures()
            ),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true,
            null
        ).join();
        metadata.classifyLoadedIdentity(observed.identity(), observed.lifecycleCapability()).join();
        return metadata;
    }

    private static WorldRuntimeGateway.LifecycleWorld observed(final long seed) {
        return new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                WorldEnvironment.NORMAL,
                seed,
                true
            ),
            LifecycleCapability.MANAGED
        );
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