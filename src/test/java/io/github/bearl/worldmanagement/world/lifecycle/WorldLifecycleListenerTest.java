package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldLoadState;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class WorldLifecycleListenerTest {

    private final PluginIoExecutor executor = new PluginIoExecutor("ListenerTest");

    @AfterEach
    void closeExecutor() {
        executor.shutdown(Duration.ofSeconds(1));
    }

    @Test
    void rapidLoadThenUnloadInvalidatesTheScheduledUnloadReconcile() {
        final WorldRuntimeGateway.LifecycleWorld creative = lifecycleWorld(
            "minecraft:creative", "11111111-1111-1111-1111-111111111111"
        );
        final WorldManagementService metadata = metadata(creative);
        metadata.update("creative", current -> current.withDesiredState(WorldLoadState.UNLOADED)).join();
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger reconciliations = new AtomicInteger();
        final WorldLifecycleListener listener = listener(metadata, catalog, dispatcher, reconciliations);
        final World world = paperWorld(creative);

        listener.onWorldLoad(new WorldLoadEvent(world));
        listener.onWorldUnload(new WorldUnloadEvent(world));
        dispatcher.runAll();

        assertEquals(0, reconciliations.get());
        assertTrue(catalog.findUniqueByWorldId("creative").isEmpty());
    }

    @Test
    void replacementLoadInvalidatesTheScheduledReloadFromAnOlderUnload() {
        final WorldRuntimeGateway.LifecycleWorld original = lifecycleWorld(
            "minecraft:creative", "11111111-1111-1111-1111-111111111111"
        );
        final WorldRuntimeGateway.LifecycleWorld replacement = lifecycleWorld(
            "minecraft:creative", "22222222-2222-2222-2222-222222222222"
        );
        final WorldManagementService metadata = metadata(original);
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        catalog.replaceAll(java.util.List.of(original));
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger reconciliations = new AtomicInteger();
        final WorldLifecycleListener listener = listener(metadata, catalog, dispatcher, reconciliations);

        listener.onWorldUnload(new WorldUnloadEvent(paperWorld(original)));
        listener.onWorldLoad(new WorldLoadEvent(paperWorld(replacement)));
        dispatcher.runAll();

        assertEquals(0, reconciliations.get());
        assertEquals(replacement, catalog.findUniqueByWorldId("creative").orElseThrow());
    }

    @Test
    void currentExternalLoadReconcilesPersistedUnloadedIntent() {
        final WorldRuntimeGateway.LifecycleWorld creative = lifecycleWorld(
            "minecraft:creative", "11111111-1111-1111-1111-111111111111"
        );
        final WorldManagementService metadata = metadata(creative);
        metadata.update("creative", current -> current.withDesiredState(WorldLoadState.UNLOADED)).join();
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger reconciliations = new AtomicInteger();
        final WorldLifecycleListener listener = listener(metadata, catalog, dispatcher, reconciliations);

        listener.onWorldLoad(new WorldLoadEvent(paperWorld(creative)));
        dispatcher.runAll();

        assertEquals(1, reconciliations.get());
    }

    @Test
    void currentSnapshotDriftStartsIdentityAutoSyncAfterClassification() {
        final WorldRuntimeGateway.LifecycleWorld accepted = lifecycleWorld(
            "minecraft:creative", "11111111-1111-1111-1111-111111111111"
        );
        final WorldRuntimeGateway.LifecycleWorld drifted = new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot(
                accepted.identity().paperKey(),
                accepted.identity().worldUuid(),
                accepted.identity().environment(),
                99L,
                accepted.identity().generateStructures()
            ),
            accepted.lifecycleCapability()
        );
        final WorldManagementService metadata = metadata(accepted);
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final AtomicInteger synchronizations = new AtomicInteger();
        final WorldLifecycleListener listener = new WorldLifecycleListener(
            metadata,
            ignored -> { },
            ignored -> CompletableFuture.completedFuture(null),
            (observation, observed) -> {
                synchronizations.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            },
            new RecordingDispatcher(),
            catalog
        );

        listener.onWorldLoad(new WorldLoadEvent(paperWorld(drifted)));
        executor.submit(() -> null).join();

        assertEquals(1, synchronizations.get());
        assertEquals(
            io.github.bearl.worldmanagement.world.IdentityVerificationState.SYNC_PENDING,
            metadata.metadataWorld("creative").orElseThrow().identityState()
        );
    }

    @Test
    void currentConflictingLoadSchedulesExistingPlayerIsolationAfterClassification() {
        final WorldRuntimeGateway.LifecycleWorld accepted = lifecycleWorld(
            "minecraft:creative", "11111111-1111-1111-1111-111111111111"
        );
        final WorldRuntimeGateway.LifecycleWorld replacement = lifecycleWorld(
            "minecraft:creative", "22222222-2222-2222-2222-222222222222"
        );
        final WorldManagementService metadata = metadata(accepted);
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicInteger isolations = new AtomicInteger();
        final WorldLifecycleListener listener = new WorldLifecycleListener(
            metadata,
            ignored -> { },
            ignored -> CompletableFuture.completedFuture(null),
            (observation, observed) -> CompletableFuture.completedFuture(null),
            (world, observed) -> isolations.incrementAndGet(),
            dispatcher,
            catalog
        );

        listener.onWorldLoad(new WorldLoadEvent(paperWorld(replacement)));
        executor.submit(() -> null).join();

        assertEquals(0, isolations.get());
        dispatcher.runAll();
        assertEquals(1, isolations.get());
        assertEquals(
            io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
            metadata.metadataWorld("creative").orElseThrow().identityState()
        );
    }

    @Test
    void unloadHandlerRunsAtMonitorAndIgnoresCancelledEvents() throws Exception {
        final EventHandler handler = WorldLifecycleListener.class
            .getMethod("onWorldUnload", WorldUnloadEvent.class)
            .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    private WorldManagementService metadata(final WorldRuntimeGateway.LifecycleWorld world) {
        final WorldManagementService metadata = new WorldManagementService(
            executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
        );
        metadata.load().join();
        metadata.adopt(
            world.identity(),
            world.lifecycleCapability(),
            Optional.empty(),
            true,
            null
        ).join();
        return metadata;
    }

    private static WorldLifecycleListener listener(
        final WorldManagementService metadata,
        final LoadedWorldCatalog catalog,
        final RecordingDispatcher dispatcher,
        final AtomicInteger reconciliations
    ) {
        return new WorldLifecycleListener(
            metadata,
            ignored -> { },
            ignored -> {
                reconciliations.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            },
            dispatcher,
            catalog
        );
    }

    private static WorldRuntimeGateway.LifecycleWorld lifecycleWorld(final String paperKey, final String uuid) {
        return new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot(
                paperKey,
                UUID.fromString(uuid),
                WorldEnvironment.NORMAL,
                42L,
                true
            ),
            LifecycleCapability.MANAGED
        );
    }

    private static World paperWorld(final WorldRuntimeGateway.LifecycleWorld world) {
        final NamespacedKey key = NamespacedKey.fromString(world.identity().paperKey());
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> world.bukkitWorldName();
                case "getKey" -> key;
                case "getUID" -> world.identity().worldUuid();
                case "getEnvironment" -> World.Environment.valueOf(world.identity().environment().name());
                case "getSeed" -> world.identity().seed();
                case "canGenerateStructures" -> world.identity().generateStructures();
                case "getGenerator", "getBiomeProvider" -> null;
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

    private static final class RecordingDispatcher implements WorldThreadDispatcher {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void executeGlobal(final Runnable task) {
            tasks.add(task);
        }

        @Override
        public void executeGlobalLater(final Duration delay, final Runnable task, final Runnable cancelledTask) {
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

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }
}
