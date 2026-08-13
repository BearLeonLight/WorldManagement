package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.hook.WorldTrackingHook;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.WorldMetadataRepository;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldGeneratorReference;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldLoadState;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashSet;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldLifecycleCoordinatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void untracksExternalWorldManagerBeforeDetachingWithoutUnloadingRuntime() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final RecordingWorldTrackingHook trackingHook = new RecordingWorldTrackingHook();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                new ImmediateDispatcher(), Optional.empty(), trackingHook, null
            );

            final WorldLifecycleCoordinator.RemoveResult result = service.remove("creative").join();

            assertEquals(WorldLifecycleCoordinator.RemoveStatus.DETACHED, result.status());
            assertEquals(java.util.List.of("creative"), trackingHook.untrackedWorlds);
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(0, gateway.saveCalls);
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.DETACHED,
                metadata.metadataWorld("creative").orElseThrow().managementState()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void refusesDeleteBeforeQuarantineWhenExternalUntrackingFails() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final RecordingWorldTrackingHook trackingHook = new RecordingWorldTrackingHook();
            trackingHook.status = WorldTrackingHook.UntrackStatus.FAILED;
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                new FakeGateway(), metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                new ImmediateDispatcher(), Optional.empty(), trackingHook, null
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.TRACKING_REMOVAL_FAILED, result.status());
            assertEquals(java.util.List.of("creative"), trackingHook.untrackedWorlds);
            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));
            assertTrue(metadata.managedWorld("creative").isPresent());
            assertTrue(Files.notExists(temporaryDirectory.resolve(".worldmanagement-quarantine")));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void releasesDeleteOperationWhenExternalTrackingApiHasALinkageFailure() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldTrackingHook trackingHook = worldName -> {
                throw new NoClassDefFoundError(
                    "org/mvplugins/multiverse/core/world/options/RemoveWorldOptions"
                );
            };
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                new FakeGateway(), metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                new ImmediateDispatcher(), Optional.empty(), trackingHook, null
            );

            assertEquals(
                WorldLifecycleCoordinator.DeleteStatus.TRACKING_REMOVAL_FAILED,
                service.delete("creative").join().status()
            );
            assertEquals(
                WorldLifecycleCoordinator.DeleteStatus.TRACKING_REMOVAL_FAILED,
                service.delete("creative").join().status()
            );
            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));
            assertTrue(metadata.managedWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void createsWorldAndPersistsItsMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final var observedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 8675309L, true
            );
            gateway.nextCreateIdentity = observedIdentity;
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldCreationRequest request = new WorldCreationRequest(
                "creative",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                java.util.OptionalLong.of(8675309L),
                Optional.of(WorldGeneratorReference.parse("Terra:normal")),
                Optional.empty(),
                true,
                false,
                Optional.of(WorldGeneratorReference.parse("Terra:climate")),
                Optional.empty(),
                false
            );
            final WorldLifecycleCoordinator.CreateResult result = service.create(request, null).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, result.status());
            final WorldMetadata created = metadata.managedWorld("creative").orElseThrow();
            assertEquals(observedIdentity, created.identity());
            assertEquals(
                Optional.of(io.github.bearl.worldmanagement.world.RequestedWorldType.NORMAL),
                created.requestedWorldType()
            );
            assertEquals(request, gateway.lastCreateRequest);
            assertEquals(request.generator(), created.generator());
            assertEquals(request.biomeProvider(), created.biomeProvider());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void classifiesCreateReplacementBeforeReportingSuccess() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final BlockingCreateRepository repository = new BlockingCreateRepository();
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final var createdIdentity = identity(
                "creative", UUID.fromString("11111111-1111-1111-1111-111111111111")
            );
            final var replacementIdentity = identity(
                "creative", UUID.fromString("22222222-2222-2222-2222-222222222222")
            );
            final FakeGateway gateway = new FakeGateway();
            gateway.nextCreateIdentity = createdIdentity;
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final CompletableFuture<WorldLifecycleCoordinator.CreateResult> pending = service.create(
                "creative",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                null
            );
            assertTrue(repository.createStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            gateway.nextLookupIdentity = replacementIdentity;
            repository.releaseCreate.countDown();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.FAILED, pending.join().status());
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(null, gateway.lastIdentityUnload);
            final WorldMetadata conflicted = metadata.metadataWorld("creative").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(createdIdentity, conflicted.identity());
            assertEquals(replacementIdentity, conflicted.pendingIdentity().orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void dispatchesCreateRuntimeAccessToGlobalScheduler() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.createdWorldDirectory = temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative");
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.CreateResult> pending = service.create(
                "creative",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                null
            );

            assertEquals(0, gateway.runtimeAccesses);
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();
            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, pending.join().status());
            assertTrue(gateway.runtimeAccesses > 0);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void defersCreateUntilWorldsStopTicking() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.createdWorldDirectory = temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative");
            gateway.worldMutationsAllowed = false;
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.CreateResult> pending = service.create(
                "creative", WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL, null
            );
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();

            assertFalse(pending.isDone());
            assertFalse(gateway.loaded.contains("creative"));
            assertTrue(metadata.metadataWorld("creative").isEmpty());

            gateway.worldMutationsAllowed = true;
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, pending.join().status());
            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(metadata.metadataWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unloadsCreatedWorldAndRemovesItsStorageWhenMetadataPersistenceFails() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new FailingCreateMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.createdWorldDirectory = temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            assertThrows(java.util.concurrent.CompletionException.class, () -> service.create(
                "creative",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                null
            ).join());

            assertFalse(gateway.loaded.contains("creative"));
            assertFalse(Files.exists(gateway.createdWorldDirectory));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void removesCreatedWorldWhenMetadataIsNotReady() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            final FakeGateway gateway = new FakeGateway();
            gateway.createdWorldDirectory = temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.create(
                "creative",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                null
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.NOT_READY, result.status());
            assertFalse(gateway.loaded.contains("creative"));
            assertFalse(Files.exists(gateway.createdWorldDirectory));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void preservesCreatedRuntimeAndStorageWhenGatewayReturnsNullWithoutIdentity() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.createdWorldDirectory = temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative");
            gateway.createReturnsNull = true;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.create(
                "creative",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                null
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.FAILED, result.status());
            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(Files.exists(gateway.createdWorldDirectory));
            assertTrue(metadata.metadataWorld("creative").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void preservesCreatedRuntimeAndStorageWhenGatewayThrowsWithoutIdentity() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.createdWorldDirectory = temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative");
            gateway.createFailure = new IllegalStateException("simulated create failure");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            assertThrows(java.util.concurrent.CompletionException.class, () -> service.create(
                "creative",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                null
            ).join());

            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(Files.exists(gateway.createdWorldDirectory));
            assertTrue(metadata.metadataWorld("creative").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsCreateWhenPaperDimensionAlreadyExists() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            Files.createDirectories(temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive"));
            final WorldLifecycleCoordinator service = service(
                new FakeGateway(), metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.create(
                "archive",
                WorldRuntimeGateway.WorldEnvironment.NORMAL,
                WorldRuntimeGateway.WorldType.NORMAL,
                null
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.ALREADY_EXISTS, result.status());
            assertTrue(metadata.managedWorld("archive").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsAdoptWhenWorldIsNotLoaded() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.AdoptResult result = service.adoptLoadedWorld("creative", null).join();

            assertEquals(WorldLifecycleCoordinator.AdoptStatus.NOT_LOADED, result.status());
            assertTrue(metadata.metadataWorld("creative").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void adoptsLoadedCustomNamespaceWorldWithCapturedExternalIdentity() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final var observedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "external:creative", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                io.github.bearl.worldmanagement.world.WorldEnvironment.CUSTOM, 99L, false
            );
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.runtimeIdentities.put("creative", observedIdentity);
            gateway.runtimeCapabilities.put(
                "creative", io.github.bearl.worldmanagement.world.LifecycleCapability.EXTERNAL_ONLY
            );
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.AdoptResult result = service.adoptLoadedWorld("creative", null).join();

            assertEquals(WorldLifecycleCoordinator.AdoptStatus.ADOPTED, result.status());
            final WorldMetadata adopted = metadata.managedWorld("creative").orElseThrow();
            assertEquals(observedIdentity, adopted.identity());
            assertEquals(
                io.github.bearl.worldmanagement.world.LifecycleCapability.EXTERNAL_ONLY,
                adopted.lifecycleCapability()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void classifiesAdoptReplacementBeforeReportingSuccess() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final BlockingCreateRepository repository = new BlockingCreateRepository();
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final var adoptedIdentity = identity(
                "creative", UUID.fromString("11111111-1111-1111-1111-111111111111")
            );
            final var replacementIdentity = identity(
                "creative", UUID.fromString("22222222-2222-2222-2222-222222222222")
            );
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.runtimeIdentities.put("creative", adoptedIdentity);
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final CompletableFuture<WorldLifecycleCoordinator.AdoptResult> pending =
                service.adoptLoadedWorld("creative", null);
            assertTrue(repository.createStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            gateway.nextLookupIdentity = replacementIdentity;
            repository.releaseCreate.countDown();

            assertEquals(WorldLifecycleCoordinator.AdoptStatus.FAILED, pending.join().status());
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(null, gateway.lastIdentityUnload);
            final WorldMetadata conflicted = metadata.metadataWorld("creative").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(adoptedIdentity, conflicted.identity());
            assertEquals(replacementIdentity, conflicted.pendingIdentity().orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void loadsAndUnloadsOnlyManagedWorlds() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            final var netherIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                io.github.bearl.worldmanagement.world.WorldEnvironment.NETHER, 0L, true
            );
            metadata.adopt(
                netherIdentity, io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), Optional.empty(),
                Optional.of(WorldGeneratorReference.parse("Terra:climate")), true, null
            ).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.nextClaimLoadIdentity = netherIdentity;
            gateway.loaded.add("lobby");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.of("lobby"), temporaryDirectory
            );

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.LOADED, service.loadAsync("creative").join().status());
            assertEquals(WorldRuntimeGateway.WorldEnvironment.NETHER, gateway.lastManagedLoadEnvironment);
            assertEquals(Optional.empty(), gateway.lastManagedLoadGenerator);
            assertEquals(
                Optional.of(WorldGeneratorReference.parse("Terra:climate")),
                gateway.lastManagedLoadBiomeProvider
            );
            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED, service.unloadAsync("creative").join().status());
            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.NOT_MANAGED, service.loadAsync("unknown").join().status());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unloadsUnknownLoadedWorldWithoutCreatingMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("unknown");
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.LifecycleResult result =
                service.unloadAsync("unknown").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED, result.status());
            assertFalse(gateway.loaded.contains("unknown"));
            assertEquals("unknown", gateway.lastIdentityUnload.worldId());
            assertTrue(metadata.metadataWorld("unknown").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void refusesMissingLoadStorageBeforeChangingIntentOrCallingRuntime() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldMetadata unloaded = WorldMetadata.createDefault("creative", true)
                .withDesiredState(WorldLoadState.UNLOADED);
            repository.create(unloaded);
            final WorldManagementService metadata = new WorldManagementService(executor, repository, new WorldRegistry());
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final MissingLoadStorageGateway storage = new MissingLoadStorageGateway();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage, Duration.ZERO,
                new ImmediateDispatcher(), Optional.empty()
            );

            final WorldLifecycleCoordinator.LifecycleResult result = service.loadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.STORAGE_NOT_FOUND, result.status());
            assertEquals(1, gateway.runtimeAccesses);
            assertEquals(WorldLoadState.UNLOADED, metadata.metadataWorld("creative").orElseThrow().desiredState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void preservesDesiredStateWhenAsyncRuntimeOperationFails() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            metadata.adopt("archive", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loadSucceeds = false;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.LifecycleResult result = service.loadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.FAILED, result.status());
            assertEquals(WorldLoadState.LOADED, metadata.metadataWorld("creative").orElseThrow().desiredState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsAndCompensatesNewlyLoadedReplacementIdentity() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final UUID acceptedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID replacementUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final var acceptedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", acceptedUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final var replacementIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", replacementUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(WorldMetadata.createDefault(
                "creative", acceptedIdentity,
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), true
            ).withDesiredState(WorldLoadState.UNLOADED));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.nextClaimLoadIdentity = replacementIdentity;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.LifecycleResult result = service.loadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.FAILED, result.status());
            assertEquals(replacementUuid, gateway.lastIdentityUnload.worldUuid());
            assertEquals(Boolean.FALSE, gateway.lastUnloadSave);
            assertFalse(gateway.loaded.contains("creative"));
            final WorldMetadata conflicted = metadata.metadataWorld("creative").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(replacementIdentity, conflicted.pendingIdentity().orElseThrow());
            assertEquals(acceptedUuid, conflicted.identity().worldUuid());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reportsUnloadFailureWhenNewlyLoadedReplacementCannotBeCompensated() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final var acceptedIdentity = identity(
                "creative", UUID.fromString("11111111-1111-1111-1111-111111111111")
            );
            final var replacementIdentity = identity(
                "creative", UUID.fromString("22222222-2222-2222-2222-222222222222")
            );
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(WorldMetadata.createDefault(
                "creative", acceptedIdentity,
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), true
            ).withDesiredState(WorldLoadState.UNLOADED));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.nextClaimLoadIdentity = replacementIdentity;
            gateway.identityUnloadSucceeds = false;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.LifecycleResult result = service.loadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOAD_FAILED, result.status());
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(replacementIdentity.worldUuid(), gateway.lastIdentityUnload.worldUuid());
            assertEquals(Boolean.FALSE, gateway.lastUnloadSave);
            final WorldMetadata conflicted = metadata.metadataWorld("creative").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(replacementIdentity, conflicted.pendingIdentity().orElseThrow());
            assertEquals(WorldLoadState.UNLOADED, conflicted.desiredState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsAlreadyLoadedReplacementIdentityWithoutUnloadingIt() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final UUID acceptedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID replacementUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final var acceptedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", acceptedUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final var replacementIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", replacementUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(WorldMetadata.createDefault(
                "creative", acceptedIdentity,
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), true
            ).withDesiredState(WorldLoadState.UNLOADED));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.runtimeIdentities.put("creative", replacementIdentity);
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.LifecycleResult result = service.loadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.FAILED, result.status());
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(null, gateway.lastIdentityUnload);
            final WorldMetadata conflicted = metadata.metadataWorld("creative").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(replacementIdentity, conflicted.pendingIdentity().orElseThrow());
            assertEquals(acceptedUuid, conflicted.identity().worldUuid());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void defersLoadUntilWorldsStopTickingWithoutChangingIntent() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(WorldMetadata.createDefault("creative", true)
                .withDesiredState(WorldLoadState.UNLOADED));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.worldMutationsAllowed = false;
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.LifecycleResult> pending =
                service.loadAsync("creative");
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();

            assertFalse(pending.isDone());
            assertFalse(gateway.loaded.contains("creative"));
            assertEquals(
                WorldLoadState.UNLOADED,
                metadata.metadataWorld("creative").orElseThrow().desiredState()
            );

            gateway.worldMutationsAllowed = true;
            dispatcher.runNextGlobal();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.LOADED, pending.join().status());
            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(gateway.mutationReadinessChecks >= 2);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void doesNotCommitLoadIntentWhenNonTickingAdmissionIsExhausted() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(WorldMetadata.createDefault("creative", true)
                .withDesiredState(WorldLoadState.UNLOADED));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            for (int attempt = 0; attempt < 20; attempt++) {
                gateway.mutationReadinessResults.add(false);
            }
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.LifecycleResult> pending =
                service.loadAsync("creative");
            for (int task = 0; task < 21; task++) {
                dispatcher.runNextGlobal();
            }

            assertThrows(java.util.concurrent.CompletionException.class, pending::join);
            assertFalse(gateway.loaded.contains("creative"));
            assertEquals(
                WorldLoadState.UNLOADED,
                metadata.metadataWorld("creative").orElseThrow().desiredState()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsManagedLoadForExternalOnlyWorldBeforeRuntimeAccess() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(externalOnlyMetadata("creative"));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.LifecycleResult result = service.loadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.EXTERNAL_ONLY, result.status());
            assertEquals(0, gateway.runtimeAccesses);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unloadsAcceptedIdentityWithPaperManagedSave() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            metadata.adopt("archive", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.LifecycleResult result = service.unloadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED, result.status());
            assertEquals(1, gateway.saveCalls);
            assertEquals(Boolean.FALSE, gateway.lastUnloadSave);
            assertEquals(
                metadata.metadataWorld("creative").orElseThrow().identity().worldUuid(),
                gateway.lastIdentityUnload.worldUuid()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void retainsLoadedWorldWhenIdentityBoundUnloadFails() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.identityUnloadSucceeds = false;
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.LifecycleResult result = service.unloadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOAD_FAILED, result.status());
            assertEquals(1, gateway.saveCalls);
            assertEquals(Boolean.FALSE, gateway.lastUnloadSave);
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(WorldLoadState.LOADED, metadata.metadataWorld("creative").orElseThrow().desiredState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unloadDoesNotSaveOrUnloadReplacementIdentity() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final UUID acceptedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID replacementUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final var acceptedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", acceptedUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final var replacementIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", replacementUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(WorldMetadata.createDefault(
                "creative", acceptedIdentity,
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), true
            ));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.nextLookupIdentity = replacementIdentity;
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.LifecycleResult result = service.unloadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOAD_FAILED, result.status());
            assertEquals(0, gateway.saveCalls);
            assertEquals(null, gateway.lastIdentityUnload);
            assertTrue(gateway.loaded.contains("creative"));
            final WorldMetadata conflicted = metadata.metadataWorld("creative").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(replacementIdentity, conflicted.pendingIdentity().orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsManagedUnloadForExternalOnlyWorldBeforeRuntimeAccess() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(externalOnlyMetadata("creative"));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final WorldLifecycleCoordinator.LifecycleResult result = service.unloadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.EXTERNAL_ONLY, result.status());
            assertEquals(0, gateway.runtimeAccesses);
            assertTrue(gateway.loaded.contains("creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void doesNotCommitUnloadIntentWhenNonTickingAdmissionIsExhausted() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.worldMutationsAllowed = false;
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.LifecycleResult> pending =
                service.unloadAsync("creative");
            dispatcher.runNextGlobal();

            assertFalse(pending.isDone());
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(null, gateway.lastIdentityUnload);
            assertEquals(
                WorldLoadState.LOADED,
                metadata.metadataWorld("creative").orElseThrow().desiredState()
            );

            for (int task = 1; task < 20; task++) {
                dispatcher.runNextGlobal();
            }

            assertThrows(java.util.concurrent.CompletionException.class, pending::join);
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(null, gateway.lastIdentityUnload);
            assertEquals(
                WorldLoadState.LOADED,
                metadata.metadataWorld("creative").orElseThrow().desiredState()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reconcilesPersistedLoadedWorldDuringStartup() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.update("creative", current -> current.withDesiredState(WorldLoadState.LOADED)).join();
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator coordinator = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            new WorldLifecycleReconciler(metadata, coordinator, new ImmediateDispatcher())
                .reconcileStartup().join();

            assertTrue(gateway.loaded.contains("creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reconciliationDoesNotRewriteMetadataWhenRuntimeAlreadyMatchesIntent() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "creative");
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.update("creative", current -> current.withDesiredState(WorldLoadState.LOADED)).join();
            final long version = metadata.metadataWorld("creative").orElseThrow().version();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.runtimeIdentities.put(
                "creative", metadata.metadataWorld("creative").orElseThrow().identity()
            );
            final WorldLifecycleCoordinator coordinator = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            new WorldLifecycleReconciler(metadata, coordinator, new ImmediateDispatcher())
                .reconcileStartup().join();

            assertEquals(version, metadata.metadataWorld("creative").orElseThrow().version());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void importsLoadedGatewayWorldIntoMetadata() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final var observedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:archive", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                io.github.bearl.worldmanagement.world.WorldEnvironment.NETHER, 12345L, false
            );
            gateway.nextNameLoadIdentity = observedIdentity;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NETHER
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, result.status());
            final WorldMetadata imported = metadata.managedWorld("archive").orElseThrow();
            assertEquals(observedIdentity, imported.identity());
            assertEquals(Optional.empty(), imported.requestedWorldType());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reportsImportStorageConflictBeforeRuntimeAccess() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
            Files.writeString(legacy.resolve("level.dat"), "legacy");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.STORAGE_CONFLICT, result.status());
            assertEquals(0, gateway.runtimeAccesses);
            assertTrue(metadata.metadataWorld("archive").isEmpty());
            assertEquals("legacy", Files.readString(legacy.resolve("level.dat")));
            assertTrue(Files.isDirectory(
                temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive")
            ));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsDuplicateLegacyWorldUuidBeforePaperLoad() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final UUID duplicateUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
            Files.writeString(legacy.resolve("level.dat"), "world");
            try (final java.io.DataOutputStream output = new java.io.DataOutputStream(
                Files.newOutputStream(legacy.resolve("uid.dat"))
            )) {
                output.writeLong(duplicateUuid.getMostSignificantBits());
                output.writeLong(duplicateUuid.getLeastSignificantBits());
            }
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.uuidOwners.put(duplicateUuid, new WorldRuntimeGateway.LifecycleWorld(
                identity("existing", duplicateUuid),
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED
            ));
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.DUPLICATE_IDENTITY, result.status());
            assertEquals(0, gateway.unmanagedLoadCalls);
            assertTrue(metadata.metadataWorld("archive").isEmpty());
            assertTrue(Files.isRegularFile(legacy.resolve("uid.dat")));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsConcurrentImportForTheSameWorldUntilTheFirstCompletes() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.worldMutationsAllowed = false;
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.CreateResult> first = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL
            );
            dispatcher.runNextGlobal();

            assertEquals(
                WorldLifecycleCoordinator.CreateStatus.OPERATION_IN_PROGRESS,
                service.importWorld("archive", WorldRuntimeGateway.WorldEnvironment.NORMAL).join().status()
            );
            assertEquals(0, gateway.unmanagedLoadCalls);

            gateway.worldMutationsAllowed = true;
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();
            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, first.join().status());
            assertEquals(1, gateway.unmanagedLoadCalls);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void regeneratesDuplicateLegacyIdentityOnlyWhenExplicitlyRequested() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final UUID previousUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID regeneratedUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
            Files.writeString(legacy.resolve("level.dat"), "world");
            writeLegacyUuid(legacy.resolve("uid.dat"), previousUuid);
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.uuidOwners.put(previousUuid, new WorldRuntimeGateway.LifecycleWorld(
                identity("existing", previousUuid),
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED
            ));
            gateway.nextNameLoadIdentity = identity("archive", regeneratedUuid);
            gateway.unmanagedLoadSideEffect = () -> {
                try {
                    createPaperStorage(temporaryDirectory, "archive", regeneratedUuid);
                    try (final var paths = Files.walk(legacy)) {
                        for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.delete(path);
                        }
                    }
                } catch (final java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            };
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL, false, true, null
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, result.status());
            assertEquals(regeneratedUuid, metadata.managedWorld("archive").orElseThrow().identity().worldUuid());
            assertFalse(Files.exists(legacy.resolve("uid.dat")));
            assertFalse(Files.exists(legacy.resolve("uid.dat.worldmanagement-recovery")));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void restoresLegacyIdentityWhenRegenerationLoadFailsBeforeSideEffects() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final UUID previousUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
            Files.writeString(legacy.resolve("level.dat"), "world");
            writeLegacyUuid(legacy.resolve("uid.dat"), previousUuid);
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loadSucceeds = false;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL, false, true, null
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.FAILED, result.status());
            assertEquals(previousUuid, readLegacyUuid(legacy.resolve("uid.dat")));
            assertFalse(Files.exists(legacy.resolve("uid.dat.worldmanagement-recovery")));
            assertTrue(metadata.metadataWorld("archive").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void preservesRecoveryMarkerWhenRegenerationFailsAfterRuntimeLoad() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final UUID previousUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID regeneratedUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
            Files.writeString(legacy.resolve("level.dat"), "world");
            writeLegacyUuid(legacy.resolve("uid.dat"), previousUuid);
            final WorldManagementService metadata = new WorldManagementService(
                executor, new FailingCreateMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.nextNameLoadIdentity = identity("archive", regeneratedUuid);
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL, false, true, null
            ).join();

            assertEquals(
                WorldLifecycleCoordinator.CreateStatus.IDENTITY_REGENERATION_INCOMPLETE,
                result.status()
            );
            assertFalse(gateway.loaded.contains("archive"));
            assertFalse(Files.exists(legacy.resolve("uid.dat")));
            assertTrue(Files.exists(legacy.resolve("uid.dat.worldmanagement-recovery")));
            assertTrue(metadata.metadataWorld("archive").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reportsIncompleteWhenRuntimeKeepsPreviousIdentityAfterRegeneration() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final UUID previousUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
            Files.writeString(legacy.resolve("level.dat"), "world");
            writeLegacyUuid(legacy.resolve("uid.dat"), previousUuid);
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.nextNameLoadIdentity = identity("archive", previousUuid);
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL, false, true, null
            ).join();

            assertEquals(
                WorldLifecycleCoordinator.CreateStatus.IDENTITY_REGENERATION_INCOMPLETE,
                result.status()
            );
            assertFalse(gateway.loaded.contains("archive"));
            assertFalse(Files.exists(legacy.resolve("uid.dat")));
            assertTrue(Files.exists(legacy.resolve("uid.dat.worldmanagement-recovery")));
            assertTrue(metadata.metadataWorld("archive").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void importsUnknownStorageAsDetachedLifecycleMetadata() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NETHER, true, null
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, result.status());
            assertTrue(gateway.loaded.contains("archive"));
            assertEquals(WorldRuntimeGateway.WorldEnvironment.NETHER, gateway.lastUnmanagedLoadEnvironment);
            assertTrue(metadata.managedWorld("archive").isEmpty());
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.DETACHED,
                metadata.detachedWorld("archive").orElseThrow().managementState()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void passesRequiredEnvironmentHintWhenImportingWorld() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NETHER
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, result.status());
            assertEquals(WorldRuntimeGateway.WorldEnvironment.NETHER, gateway.lastUnmanagedLoadEnvironment);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void classifiesImportReplacementBeforeReportingSuccess() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final BlockingCreateRepository repository = new BlockingCreateRepository();
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final var importedIdentity = identity(
                "archive", UUID.fromString("11111111-1111-1111-1111-111111111111")
            );
            final var replacementIdentity = identity(
                "archive", UUID.fromString("22222222-2222-2222-2222-222222222222")
            );
            final FakeGateway gateway = new FakeGateway();
            gateway.nextNameLoadIdentity = importedIdentity;
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            final CompletableFuture<WorldLifecycleCoordinator.CreateResult> pending =
                service.importWorld("archive", WorldRuntimeGateway.WorldEnvironment.NORMAL);
            assertTrue(repository.createStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            gateway.nextLookupIdentity = replacementIdentity;
            repository.releaseCreate.countDown();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.FAILED, pending.join().status());
            assertTrue(gateway.loaded.contains("archive"));
            assertEquals(null, gateway.lastIdentityUnload);
            final WorldMetadata conflicted = metadata.metadataWorld("archive").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(importedIdentity, conflicted.identity());
            assertEquals(replacementIdentity, conflicted.pendingIdentity().orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void dispatchesImportRuntimeAccessToGlobalScheduler() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.CreateResult> pending = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL
            );

            assertEquals(0, gateway.runtimeAccesses);
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();
            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, pending.join().status());
            assertTrue(gateway.runtimeAccesses > 0);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void defersImportUntilWorldsStopTicking() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            createPaperStorage(temporaryDirectory, "archive");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.worldMutationsAllowed = false;
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.CreateResult> pending =
                service.importWorld("archive", WorldRuntimeGateway.WorldEnvironment.NORMAL);
            dispatcher.runNextGlobal();

            assertFalse(pending.isDone());
            assertFalse(gateway.loaded.contains("archive"));
            assertTrue(metadata.metadataWorld("archive").isEmpty());

            gateway.worldMutationsAllowed = true;
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.CREATED, pending.join().status());
            assertTrue(gateway.loaded.contains("archive"));
            assertTrue(metadata.metadataWorld("archive").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unloadsImportedWorldAndPreservesStorageWhenMetadataPersistenceFails() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new FailingCreateMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("archive"));
            final Path levelData = worldDirectory.resolve("level.dat");
            Files.writeString(levelData, "original-world");
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            assertThrows(java.util.concurrent.CompletionException.class, () -> service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL
            ).join());

            assertFalse(gateway.loaded.contains("archive"));
            assertEquals("original-world", Files.readString(levelData));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void importMetadataFailureDoesNotUnloadReplacementIdentity() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new FailingCreateMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final Path levelData = Files.createDirectories(temporaryDirectory.resolve("archive"))
                .resolve("level.dat");
            Files.writeString(levelData, "original-world");
            final var importedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:archive", UUID.fromString("11111111-1111-1111-1111-111111111111"),
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final var replacementIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:archive", UUID.fromString("22222222-2222-2222-2222-222222222222"),
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            final FakeGateway gateway = new FakeGateway();
            gateway.nextNameLoadIdentity = importedIdentity;
            gateway.replacementAfterNameLoadIdentity = replacementIdentity;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            assertThrows(java.util.concurrent.CompletionException.class, () ->
                service.importWorld("archive", WorldRuntimeGateway.WorldEnvironment.NORMAL).join()
            );

            assertTrue(gateway.loaded.contains("archive"));
            assertEquals(replacementIdentity, gateway.nextLookupIdentity);
            assertTrue(Files.isRegularFile(levelData));
            assertEquals("original-world", Files.readString(levelData));
            assertTrue(metadata.metadataWorld("archive").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unloadsImportedWorldWhenMetadataIsNotReadyWithoutDeletingStorage() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("archive"));
            final Path levelData = worldDirectory.resolve("level.dat");
            Files.writeString(levelData, "original-world");
            final FakeGateway gateway = new FakeGateway();
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.NOT_READY, result.status());
            assertFalse(gateway.loaded.contains("archive"));
            assertEquals("original-world", Files.readString(levelData));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void preservesPartialImportWhenGatewayReturnsNullWithoutIdentity() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final Path levelData = Files.createDirectories(temporaryDirectory.resolve("archive")).resolve("level.dat");
            Files.writeString(levelData, "original-world");
            final FakeGateway gateway = new FakeGateway();
            gateway.loadReturnsNullAfterLoading = true;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.CreateResult result = service.importWorld(
                "archive", WorldRuntimeGateway.WorldEnvironment.NORMAL
            ).join();

            assertEquals(WorldLifecycleCoordinator.CreateStatus.FAILED, result.status());
            assertTrue(gateway.loaded.contains("archive"));
            assertEquals("original-world", Files.readString(levelData));
            assertTrue(metadata.metadataWorld("archive").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void preservesPartialImportWhenGatewayThrowsWithoutIdentity() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final Path levelData = Files.createDirectories(temporaryDirectory.resolve("archive")).resolve("level.dat");
            Files.writeString(levelData, "original-world");
            final FakeGateway gateway = new FakeGateway();
            gateway.loadFailure = new IllegalStateException("simulated import failure");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.importWorld("archive", WorldRuntimeGateway.WorldEnvironment.NORMAL).join()
            );

            assertTrue(gateway.loaded.contains("archive"));
            assertEquals("original-world", Files.readString(levelData));
            assertTrue(metadata.metadataWorld("archive").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void permanentlyDeletesUnloadedWorldInTheSameRuntime() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway,
                metadata,
                true,
                executor,
                storage(temporaryDirectory),
                Duration.ZERO,
                new ImmediateDispatcher(),
                Optional.of("lobby")
            );
            gateway.loaded.add("lobby");

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.DELETED, result.status());
            assertTrue(metadata.metadataWorld("creative").isEmpty());
            assertTrue(Files.notExists(worldDirectory));
            final Path quarantineRoot = temporaryDirectory.resolve(".worldmanagement-quarantine");
            assertTrue(Files.notExists(quarantineRoot));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unloadsLoadedWorldForDeleteWithPaperManagedSave() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION, result.status());
            assertEquals(1, gateway.saveCalls);
            assertEquals(Boolean.FALSE, gateway.lastUnloadSave);
            assertEquals(
                metadata.metadataWorld("creative").orElseThrow().identity().worldUuid(),
                gateway.lastIdentityUnload.worldUuid()
            );
            assertTrue(Files.exists(worldDirectory));
            assertTrue(metadata.metadataWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsDeleteForExternalOnlyWorldBeforeRuntimeAccess() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(externalOnlyMetadata("creative"));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.EXTERNAL_ONLY, result.status());
            assertEquals(0, gateway.runtimeAccesses);
            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void autoAdoptsUnknownLoadedWorldForImmediateConfirmedDelete() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("unknown");
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("unknown"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult first = service.delete("unknown").join();

            assertEquals(
                WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION,
                first.status()
            );
            assertFalse(gateway.loaded.contains("unknown"));
            final WorldMetadata detached = metadata.detachedWorld("unknown").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldRegistrationSource.DELETE_AUTO,
                detached.registrationSource()
            );
            assertEquals(WorldLoadState.UNLOADED, detached.desiredState());
            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));

            final WorldLifecycleCoordinator.DeleteResult second = service.delete("unknown").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.DELETED, second.status());
            assertTrue(metadata.metadataWorld("unknown").isEmpty());
            assertFalse(Files.exists(worldDirectory));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsUnknownDeleteWhenRuntimeIdentityChangesAfterAutoAdoption() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("unknown");
            final var original = identity(
                "unknown", UUID.fromString("11111111-1111-1111-1111-111111111111")
            );
            final var replacement = identity(
                "unknown", UUID.fromString("22222222-2222-2222-2222-222222222222")
            );
            gateway.runtimeIdentities.put("unknown", original);
            gateway.replacementAfterLoadedLookupIdentity = replacement;
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("unknown"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("unknown").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.UNLOAD_FAILED, result.status());
            assertTrue(gateway.loaded.contains("unknown"));
            assertEquals(0, gateway.saveCalls);
            assertTrue(metadata.metadataWorld("unknown").isEmpty());
            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));
            assertFalse(Files.exists(temporaryDirectory.resolve(".worldmanagement-quarantine")));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void defersLoadedWorldDeleteUntilWorldsStopTicking() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.worldMutationsAllowed = false;
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final ManualGlobalDispatcher dispatcher = new ManualGlobalDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.DeleteResult> pending =
                service.delete("creative");
            dispatcher.runNextGlobal();

            assertFalse(pending.isDone());
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(null, gateway.lastIdentityUnload);
            assertEquals(
                WorldLoadState.LOADED,
                metadata.metadataWorld("creative").orElseThrow().desiredState()
            );

            gateway.worldMutationsAllowed = true;
            dispatcher.runNextGlobal();
            dispatcher.runNextGlobal();

            assertEquals(
                WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION,
                pending.join().status()
            );
            assertFalse(gateway.loaded.contains("creative"));
            assertEquals(Boolean.FALSE, gateway.lastUnloadSave);
            assertEquals(
                WorldLoadState.UNLOADED,
                metadata.metadataWorld("creative").orElseThrow().desiredState()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void retainsLoadedWorldMetadataAndStorageWhenIdentityBoundUnloadFailsBeforeDelete() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.identityUnloadSucceeds = false;
            final Path levelData = Files.createDirectories(temporaryDirectory.resolve("creative")).resolve("level.dat");
            Files.writeString(levelData, "world");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.UNLOAD_FAILED, result.status());
            assertEquals(1, gateway.saveCalls);
            assertEquals(Boolean.FALSE, gateway.lastUnloadSave);
            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(Files.isRegularFile(levelData));
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.ACTIVE,
                metadata.metadataWorld("creative").orElseThrow().managementState()
            );
            assertFalse(Files.exists(temporaryDirectory.resolve(".worldmanagement-quarantine")));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void reloadsWorldWhenDeleteUnloadMetadataUpdateFails() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final FailingReplaceMetadataRepository repository = new FailingReplaceMetadataRepository();
            final WorldManagementService metadata = new WorldManagementService(executor, repository, new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            repository.failReplace = true;
            final GlobalContextDispatcher dispatcher = new GlobalContextDispatcher();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.runtimeAccessAllowed = dispatcher::inGlobalContext;
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final java.util.concurrent.CompletionException failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.delete("creative").join()
            );

            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertEquals("simulated metadata replace failure", cause.getMessage());
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(WorldLoadState.LOADED, metadata.metadataWorld("creative").orElseThrow().desiredState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsReplacementReloadAfterDeleteMetadataFailure() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final FailingReplaceMetadataRepository repository = new FailingReplaceMetadataRepository();
            final UUID acceptedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID replacementUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final var acceptedIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", acceptedUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            repository.create(WorldMetadata.createDefault(
                "creative", acceptedIdentity,
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), true
            ));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            repository.failReplace = true;
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.nextClaimLoadIdentity = new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:creative", replacementUuid,
                io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 42L, true
            );
            createPaperStorage(temporaryDirectory, "creative");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final java.util.concurrent.CompletionException failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.delete("creative").join()
            );

            assertTrue(hasCauseMessage(failure, "simulated metadata replace failure"));
            assertFalse(gateway.loaded.contains("creative"));
            assertEquals(replacementUuid, gateway.lastIdentityUnload.worldUuid());
            final WorldMetadata conflicted = metadata.metadataWorld("creative").orElseThrow();
            assertEquals(
                io.github.bearl.worldmanagement.world.IdentityVerificationState.CONFLICT,
                conflicted.identityState()
            );
            assertEquals(replacementUuid, conflicted.pendingIdentity().orElseThrow().worldUuid());
            assertEquals(acceptedUuid, conflicted.identity().worldUuid());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static boolean hasCauseMessage(final Throwable failure, final String message) {
        Throwable current = failure;
        while (current != null) {
            if (message.equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Test
    void requiresSecondConfirmedDeleteAfterUnloadingLoadedWorld() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final Path levelData = Files.createDirectories(temporaryDirectory.resolve("creative")).resolve("level.dat");
            Files.writeString(levelData, "world");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult first = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION, first.status());
            assertFalse(gateway.loaded.contains("creative"));
            assertTrue(Files.isRegularFile(levelData));
            assertEquals(WorldLoadState.UNLOADED, metadata.metadataWorld("creative").orElseThrow().desiredState());
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.ACTIVE,
                metadata.metadataWorld("creative").orElseThrow().managementState()
            );

            final WorldLifecycleCoordinator.DeleteResult second = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.DELETED, second.status());
            assertFalse(Files.exists(levelData));
            assertTrue(metadata.metadataWorld("creative").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void teleportsPlayersToLoadedFallbackBeforeDeletingWorld() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.players = 2;
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor, Optional.of("lobby"), temporaryDirectory);

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION, result.status());
            assertEquals("creative->lobby", gateway.lastTeleport);
            assertEquals(0, gateway.players);
            assertFalse(gateway.loaded.contains("creative"));
            assertTrue(Files.exists(worldDirectory));
            assertTrue(metadata.metadataWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void usesCommandFallbackForAsyncUnloadWhenProvided() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            metadata.adopt("archive", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.loaded.add("archive");
            gateway.players = 1;
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor, Optional.of("lobby"));

            final WorldLifecycleCoordinator.LifecycleResult result = service.unloadAsync("creative", Optional.of("archive"), null).join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED, result.status());
            assertEquals("creative->archive", gateway.lastTeleport);
            assertFalse(gateway.loaded.contains("creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void usesUnknownLoadedRuntimeWorldAsFallback() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.players = 1;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty()
            );

            final WorldLifecycleCoordinator.LifecycleResult result = service.unloadAsync(
                "creative", Optional.of("lobby"), null
            ).join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.UNLOADED, result.status());
            assertEquals("creative->lobby", gateway.lastTeleport);
            assertFalse(gateway.loaded.contains("creative"));
            assertTrue(gateway.loaded.contains("lobby"));
            assertTrue(metadata.metadataWorld("lobby").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsLoadedFallbackWithReplacementIdentity() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final UUID sourceUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID fallbackUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final UUID replacementUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
            final var sourceIdentity = identity("creative", sourceUuid);
            final var fallbackIdentity = identity("lobby", fallbackUuid);
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            repository.create(WorldMetadata.createDefault(
                "creative", sourceIdentity,
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), true
            ));
            repository.create(WorldMetadata.createDefault(
                "lobby", fallbackIdentity,
                io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
                Optional.empty(), true
            ));
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.runtimeIdentities.put("lobby", fallbackIdentity);
            gateway.replacementAfterLoadedLookupIdentity = identity("lobby", replacementUuid);
            gateway.players = 1;
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.of("lobby")
            );

            final WorldLifecycleCoordinator.LifecycleResult result = service.unloadAsync("creative").join();

            assertEquals(WorldLifecycleCoordinator.LifecycleStatus.FALLBACK_UNAVAILABLE, result.status());
            assertEquals("creative->lobby", gateway.lastTeleport);
            assertTrue(gateway.loaded.contains("creative"));
            assertEquals(WorldLoadState.LOADED, metadata.managedWorld("creative").orElseThrow().desiredState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static io.github.bearl.worldmanagement.world.WorldIdentitySnapshot identity(
        final String worldId,
        final UUID uuid
    ) {
        return new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
            "minecraft:" + worldId, uuid,
            io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 0L, true
        );
    }

    private static WorldMetadata externalOnlyMetadata(final String worldId) {
        return WorldMetadata.createDefault(
            worldId,
            new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                "minecraft:" + worldId,
                UUID.nameUUIDFromBytes(("external:" + worldId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                io.github.bearl.worldmanagement.world.WorldEnvironment.CUSTOM,
                0L,
                true
            ),
            io.github.bearl.worldmanagement.world.LifecycleCapability.EXTERNAL_ONLY,
            Optional.empty(),
            true
        );
    }

    @Test
    void usesCommandFallbackForDeleteWhenProvided() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            metadata.adopt("archive", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.loaded.add("archive");
            gateway.players = 1;
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor, Optional.of("lobby"), temporaryDirectory);

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative", Optional.of("archive"), null).join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION, result.status());
            assertEquals("creative->archive", gateway.lastTeleport);
            assertTrue(Files.exists(worldDirectory));
            assertTrue(metadata.metadataWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void keepsWorldLoadedWhenFallbackTeleportFails() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.players = 1;
            gateway.teleportSucceeds = false;
            final Path dimension = Files.createDirectories(
                temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
            );
            Files.createDirectories(dimension.resolve("data"));
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.of("lobby"), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.PLAYERS_PRESENT, result.status());
            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(metadata.managedWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void retainsLoadedWorldWhenDeleteStorageIsMissing() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor);

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.STORAGE_NOT_FOUND, service.delete("creative").join().status());
            assertTrue(gateway.loaded.contains("creative"));
            assertTrue(metadata.managedWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void deletesAlreadyUnloadedWorldFromPaperDimensionStorage() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            final Path dimension = Files.createDirectories(
                temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
            );
            Files.createDirectories(dimension.resolve("data"));
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor, Optional.empty(), temporaryDirectory);

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.DELETED, result.status());
            assertTrue(metadata.metadataWorld("creative").isEmpty());
            assertTrue(Files.notExists(dimension));
            assertTrue(Files.notExists(
                dimension.getParent().resolve(".worldmanagement-quarantine")
            ));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsConcurrentLifecycleOperationUntilPendingDeleteCompletes() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.players = 1;
            gateway.pendingTeleport = new CompletableFuture<>();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(gateway, metadata, executor, Optional.of("lobby"), temporaryDirectory);

            final CompletableFuture<WorldLifecycleCoordinator.DeleteResult> first = service.delete("creative");
            assertEquals(WorldLifecycleCoordinator.DeleteStatus.OPERATION_IN_PROGRESS, service.delete("creative").join().status());

            gateway.players = 0;
            gateway.pendingTeleport.complete(true);
            assertEquals(
                WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION,
                first.join().status()
            );
            assertTrue(Files.exists(worldDirectory));
            assertTrue(metadata.metadataWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void abortsDelayedDeleteWhenWorldIsExternallyReloaded() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final DelayedDispatcher dispatcher = new DelayedDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ofSeconds(1),
                dispatcher, Optional.empty()
            );

            assertEquals(
                WorldLifecycleCoordinator.DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION,
                service.delete("creative").join().status()
            );
            final CompletableFuture<WorldLifecycleCoordinator.DeleteResult> pending = service.delete("creative");
            final Runnable delayedDelete = dispatcher.awaitDelayed();
            gateway.loaded.add("creative");
            delayedDelete.run();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.RELOADED, pending.join().status());
            assertTrue(Files.exists(worldDirectory));
            assertTrue(metadata.managedWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void restoresQuarantinedStorageWhenWorldReloadsBeforeDeletingTombstone() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final QuarantineBarrierDispatcher dispatcher = new QuarantineBarrierDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ofSeconds(1),
                dispatcher, Optional.empty()
            );

            final CompletableFuture<WorldLifecycleCoordinator.DeleteResult> pending = service.delete("creative");
            dispatcher.awaitDelayed().run();
            final Runnable afterQuarantine = dispatcher.awaitAfterQuarantine();
            gateway.loaded.add("creative");
            afterQuarantine.run();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.RELOADED, pending.join().status());
            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));
            assertTrue(metadata.managedWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void isolatesReloadAndReportsConflictWhenWorldReloadsDuringDeletingTombstoneWrite() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final BlockingDeletingRepository repository = new BlockingDeletingRepository();
            final WorldManagementService metadata = new WorldManagementService(executor, repository, new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FakeGateway gateway = new FakeGateway();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.empty(), temporaryDirectory
            );

            final CompletableFuture<WorldLifecycleCoordinator.DeleteResult> deletion = service.delete("creative");
            assertTrue(repository.deletingWriteStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            gateway.loaded.add("creative");
            service.worldLoaded(FakeGateway.lifecycleWorld("creative"));
            repository.releaseDeletingWrite.countDown();

            assertEquals(
                WorldLifecycleCoordinator.DeleteStatus.RELOADED_AFTER_TOMBSTONE,
                deletion.join().status()
            );
            assertEquals(0, service.retainedDeleteLoadGenerationCount());
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.DELETING,
                metadata.metadataWorld("creative").orElseThrow().managementState()
            );
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldRuntimeResolution.Status.ISOLATED,
                metadata.resolveRuntimeWorld(FakeGateway.lifecycleWorld("creative").identity()).status()
            );
            assertFalse(Files.exists(worldDirectory));
            assertTrue(Files.isDirectory(temporaryDirectory.resolve(".worldmanagement-quarantine")));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void permanentlyDeletesAfterDeletingTombstoneIsDurable() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final GlobalContextDispatcher dispatcher = new GlobalContextDispatcher();
            final FakeGateway gateway = new FakeGateway();
            gateway.runtimeAccessAllowed = dispatcher::inGlobalContext;
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.empty()
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.DELETED, result.status());
            assertTrue(metadata.metadataWorld("creative").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void completesDeleteWhenDurableTombstoneAcknowledgementIsRecovered() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final AmbiguousDeletingCommitRepository repository = new AmbiguousDeletingCommitRepository();
            final WorldManagementService metadata = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                new FakeGateway(), metadata, executor, Optional.empty(), temporaryDirectory
            );

            final WorldLifecycleCoordinator.DeleteResult result = service.delete("creative").join();

            assertEquals(WorldLifecycleCoordinator.DeleteStatus.DELETED, result.status());
            assertFalse(Files.exists(worldDirectory));
            assertTrue(Files.notExists(temporaryDirectory.resolve(".worldmanagement-quarantine")));
            assertTrue(metadata.metadataWorld("creative").isEmpty());
            assertTrue(repository.find("creative").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void restoresQuarantinedStorageWhenMetadataPurgeFails() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldManagementService metadata = new WorldManagementService(executor, repository, new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final var current = metadata.managedWorld("creative").orElseThrow();
            repository.replace(current.withDesiredState(WorldLoadState.UNLOADED), current.version());
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                new FakeGateway(), metadata, executor, Optional.empty(), temporaryDirectory
            );

            assertThrows(java.util.concurrent.CompletionException.class, () -> service.delete("creative").join());

            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));
            assertTrue(metadata.managedWorld("creative").isPresent());
            assertTrue(repository.find("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void keepsDeletingTombstoneWhenSameRuntimePermanentDeleteFails() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final FailingDeleteStorageGateway storage = new FailingDeleteStorageGateway();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                new FakeGateway(), metadata, true, executor, storage, Duration.ZERO,
                new ImmediateDispatcher(), Optional.empty()
            );

            assertThrows(java.util.concurrent.CompletionException.class, () -> service.delete("creative").join());
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.DELETING,
                metadata.metadataWorld("creative").orElseThrow().managementState()
            );
            assertFalse(storage.restored);
            assertTrue(storage.deleteCalled);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void keepsDeletingTombstoneWhenSameRuntimeMetadataPurgeFails() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final FailingDeleteMetadataRepository repository = new FailingDeleteMetadataRepository();
            final WorldManagementService metadata = new WorldManagementService(executor, repository, new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final WorldLifecycleCoordinator service = service(
                new FakeGateway(), metadata, executor, Optional.empty(), temporaryDirectory
            );

            assertThrows(java.util.concurrent.CompletionException.class, () -> service.delete("creative").join());
            assertTrue(Files.notExists(worldDirectory));
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.DELETING,
                metadata.metadataWorld("creative").orElseThrow().managementState()
            );
            assertEquals(
                io.github.bearl.worldmanagement.world.WorldManagementState.DELETING,
                repository.find("creative").orElseThrow().managementState()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void restoresQuarantinedStorageWhenShutdownCancelsGlobalContinuation() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(executor, new InMemoryWorldMetadataRepository(), new WorldRegistry());
            metadata.load().join();
            metadata.adopt("creative", true).join();
            final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative"));
            Files.writeString(worldDirectory.resolve("level.dat"), "world");
            final CancellableQuarantineDispatcher dispatcher = new CancellableQuarantineDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                new FakeGateway(), metadata, true, executor, storage(temporaryDirectory), Duration.ofSeconds(1),
                dispatcher, Optional.empty()
            );

            service.delete("creative");
            dispatcher.awaitDelayed().run();
            dispatcher.awaitCancellation();
            final CompletableFuture<Void> shutdown = service.beginShutdown();
            dispatcher.cancelOwnedTasks();
            assertThrows(java.util.concurrent.CompletionException.class, shutdown::join);

            assertTrue(Files.isRegularFile(worldDirectory.resolve("level.dat")));
            assertTrue(metadata.managedWorld("creative").isPresent());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void shutdownCancelsPendingPlayerTeleport() {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.players = 1;
            gateway.pendingTeleport = new CompletableFuture<>();
            final WorldLifecycleCoordinator service = service(
                gateway, metadata, executor, Optional.of("lobby")
            );

            final CompletableFuture<WorldLifecycleCoordinator.LifecycleResult> unload = service.unloadAsync("creative");
            final CompletableFuture<Void> shutdown = service.beginShutdown();

            assertTrue(gateway.pendingOperationsCancelled);
            assertDoesNotThrow(() -> shutdown.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(unload.isDone());
            assertTrue(shutdown.isDone());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void shutdownCompletesWhenTeleportContinuationIsCancelled() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("LifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("lobby", true).join();
            final FakeGateway gateway = new FakeGateway();
            gateway.loaded.add("creative");
            gateway.loaded.add("lobby");
            gateway.players = 1;
            gateway.pendingTeleport = new CompletableFuture<>();
            final CancellableContinuationDispatcher dispatcher = new CancellableContinuationDispatcher();
            final WorldLifecycleCoordinator service = new WorldLifecycleCoordinator(
                gateway, metadata, true, executor, storage(temporaryDirectory), Duration.ZERO,
                dispatcher, Optional.of("lobby")
            );

            final CompletableFuture<WorldLifecycleCoordinator.LifecycleResult> unload = service.unloadAsync("creative");
            assertTrue(gateway.teleportStarted.get(1, java.util.concurrent.TimeUnit.SECONDS) == null);
            final CompletableFuture<Void> shutdown = service.beginShutdown();
            dispatcher.cancelOwnedTasks();

            assertDoesNotThrow(() -> shutdown.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(unload.isDone());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static final class FakeGateway implements WorldRuntimeGateway {
        private final Set<String> loaded = new HashSet<>();
        private int players;
        private boolean teleportSucceeds = true;
        private boolean loadSucceeds = true;
        private boolean createReturnsNull;
        private boolean loadReturnsNullAfterLoading;
        private RuntimeException createFailure;
        private RuntimeException loadFailure;
        private RuntimeException saveFailure;
        private boolean identityUnloadSucceeds = true;
        private boolean worldMutationsAllowed = true;
        private int mutationReadinessChecks;
        private final java.util.ArrayDeque<Boolean> mutationReadinessResults = new java.util.ArrayDeque<>();
        private String lastTeleport;
        private CompletableFuture<Boolean> pendingTeleport;
        private final CompletableFuture<Void> teleportStarted = new CompletableFuture<>();
        private boolean pendingOperationsCancelled;
        private Path createdWorldDirectory;
        private int runtimeAccesses;
        private int unmanagedLoadCalls;
        private int saveCalls;
        private Boolean lastUnloadSave;
        private io.github.bearl.worldmanagement.world.WorldIdentitySnapshot nextClaimLoadIdentity;
        private io.github.bearl.worldmanagement.world.WorldIdentitySnapshot nextCreateIdentity;
        private io.github.bearl.worldmanagement.world.WorldIdentitySnapshot nextNameLoadIdentity;
        private io.github.bearl.worldmanagement.world.WorldIdentitySnapshot nextLookupIdentity;
        private io.github.bearl.worldmanagement.world.WorldIdentitySnapshot replacementAfterLoadedLookupIdentity;
        private io.github.bearl.worldmanagement.world.WorldIdentitySnapshot replacementAfterNameLoadIdentity;
        private Runnable unmanagedLoadSideEffect = () -> { };
        private WorldCreationRequest lastCreateRequest;
        private WorldEnvironment lastUnmanagedLoadEnvironment;
        private WorldEnvironment lastManagedLoadEnvironment;
        private Optional<WorldGeneratorReference> lastManagedLoadGenerator;
        private Optional<WorldGeneratorReference> lastManagedLoadBiomeProvider;
        private final java.util.Map<String, io.github.bearl.worldmanagement.world.WorldIdentitySnapshot>
            lookupIdentities = new java.util.HashMap<>();
        private final java.util.Map<String, io.github.bearl.worldmanagement.world.WorldIdentitySnapshot>
            runtimeIdentities = new java.util.HashMap<>();
        private final java.util.Map<String, io.github.bearl.worldmanagement.world.LifecycleCapability>
            runtimeCapabilities = new java.util.HashMap<>();
        private final java.util.Map<UUID, LifecycleWorld> uuidOwners = new java.util.HashMap<>();
        private io.github.bearl.worldmanagement.world.VerifiedWorldRef lastIdentityUnload;
        private java.util.function.BooleanSupplier runtimeAccessAllowed = () -> true;

        @Override
        public boolean canMutateWorldsNow() {
            mutationReadinessChecks++;
            return mutationReadinessResults.isEmpty()
                ? worldMutationsAllowed
                : mutationReadinessResults.removeFirst();
        }

        @Override
        public LifecycleWorld create(final String worldName, final WorldEnvironment environment, final WorldType type, final Long seed) {
            recordRuntimeAccess();
            loaded.add(worldName);
            if (createdWorldDirectory != null) {
                try {
                    Files.createDirectories(createdWorldDirectory);
                    Files.writeString(createdWorldDirectory.resolve("level.dat"), "new-world");
                } catch (final java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            if (createFailure != null) {
                throw createFailure;
            }
            if (createReturnsNull) {
                return null;
            }
            final LifecycleWorld created = lifecycleWorld(worldName, nextCreateIdentity);
            runtimeIdentities.put(worldName, created.identity());
            return created;
        }

        @Override
        public LifecycleWorld create(final WorldCreationRequest request) {
            lastCreateRequest = request;
            return create(
                request.worldName(), request.environment(), request.type(),
                request.seed().isPresent() ? request.seed().getAsLong() : null
            );
        }

        @Override
        public LoadResult loadUnmanaged(final String worldName, final WorldEnvironment environment) {
            recordRuntimeAccess();
            unmanagedLoadCalls++;
            lastUnmanagedLoadEnvironment = environment;
            if (!loadSucceeds) {
                return LoadResult.failed();
            }
            final boolean newlyLoaded = loaded.add(worldName);
            if (loadFailure != null) {
                throw loadFailure;
            }
            unmanagedLoadSideEffect.run();
            final LifecycleWorld loadedWorld = loadReturnsNullAfterLoading
                ? null
                : lifecycleWorld(worldName, nextNameLoadIdentity);
            if (loadedWorld != null) {
                runtimeIdentities.put(worldName, loadedWorld.identity());
            }
            if (replacementAfterNameLoadIdentity != null) {
                nextLookupIdentity = replacementAfterNameLoadIdentity;
            }
            return loadedWorld == null ? LoadResult.failed() : LoadResult.loaded(loadedWorld, newlyLoaded);
        }

        @Override
        public LoadResult load(final WorldStorageGateway.LoadClaim claim) {
            recordRuntimeAccess();
            if (!loadSucceeds) {
                return LoadResult.failed();
            }
            final boolean newlyLoaded = loaded.add(claim.world().worldId());
            final var identity = nextClaimLoadIdentity == null
                ? new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                    claim.world().paperKey(), claim.world().worldUuid(),
                    io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 0L, true
                )
                : nextClaimLoadIdentity;
            runtimeIdentities.put(claim.world().worldId(), identity);
            return LoadResult.loaded(new LifecycleWorld(
                identity, io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED
            ), newlyLoaded);
        }

        @Override
        public LoadResult load(
            final WorldStorageGateway.LoadClaim claim,
            final WorldEnvironment environment,
            final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> generator,
            final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> biomeProvider
        ) {
            lastManagedLoadEnvironment = environment;
            lastManagedLoadGenerator = generator;
            lastManagedLoadBiomeProvider = biomeProvider;
            return load(claim);
        }

        @Override
        public boolean unload(final LifecycleWorld world, final boolean save) {
            recordRuntimeAccess();
            lastIdentityUnload = world.reference();
            lastUnloadSave = save;
            if (nextLookupIdentity != null
                && !nextLookupIdentity.hasSameDurableIdentity(world.identity())) {
                return false;
            }
            if (!identityUnloadSucceeds || !loaded.remove(world.name())) {
                return false;
            }
            runtimeIdentities.remove(world.name());
            return true;
        }

        @Override
        public boolean save(final LifecycleWorld world) {
            recordRuntimeAccess();
            saveCalls++;
            return loaded.contains(world.name());
        }

        @Override
        public Optional<LifecycleWorld> findWorld(
            final io.github.bearl.worldmanagement.world.VerifiedWorldRef expected
        ) {
            recordRuntimeAccess();
            if (!loaded.contains(expected.worldId())) {
                return Optional.empty();
            }
            final var identity = lookupIdentities.getOrDefault(
                expected.worldId(),
                nextLookupIdentity == null
                    ? runtimeIdentities.getOrDefault(
                        expected.worldId(),
                        new io.github.bearl.worldmanagement.world.WorldIdentitySnapshot(
                            expected.paperKey(), expected.worldUuid(),
                            io.github.bearl.worldmanagement.world.WorldEnvironment.NORMAL, 0L, true
                        )
                    )
                    : nextLookupIdentity
            );
            return Optional.of(new LifecycleWorld(
                identity,
                runtimeCapabilities.getOrDefault(
                    expected.worldId(), io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED
                )
            ));
        }

        @Override
        public Optional<LifecycleWorld> findLoadedWorldById(final String worldId) {
            recordRuntimeAccess();
            if (!loaded.contains(worldId)) {
                return Optional.empty();
            }
            final WorldMetadata metadata = WorldMetadata.createDefault(worldId, true);
            final LifecycleWorld result = new LifecycleWorld(
                runtimeIdentities.getOrDefault(worldId, metadata.identity()),
                runtimeCapabilities.getOrDefault(
                    worldId, io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED
                )
            );
            if (replacementAfterLoadedLookupIdentity != null) {
                runtimeIdentities.put(worldId, replacementAfterLoadedLookupIdentity);
                replacementAfterLoadedLookupIdentity = null;
            }
            return Optional.of(result);
        }

        @Override
        public Optional<LifecycleWorld> findLoadedWorldByUuid(final UUID worldUuid) {
            recordRuntimeAccess();
            return Optional.ofNullable(uuidOwners.get(worldUuid));
        }

        @Override
        public Optional<LifecycleWorld> findWorldByPaperKey(final String paperKey) {
            recordRuntimeAccess();
            final String worldId = paperKey.substring(paperKey.indexOf(':') + 1);
            if (!loaded.contains(worldId)) {
                return Optional.empty();
            }
            final WorldMetadata metadata = WorldMetadata.createDefault(worldId, true);
            final var observed = lookupIdentities.getOrDefault(
                worldId, runtimeIdentities.getOrDefault(worldId, metadata.identity())
            );
            return Optional.of(new LifecycleWorld(observed, metadata.lifecycleCapability()));
        }

        private void recordRuntimeAccess() {
            assertTrue(runtimeAccessAllowed.getAsBoolean(), "Runtime gateway accessed outside global scheduler");
            runtimeAccesses++;
        }

        private static LifecycleWorld lifecycleWorld(final String worldName) {
            return lifecycleWorld(worldName, null);
        }

        private static LifecycleWorld lifecycleWorld(
            final String worldName,
            final io.github.bearl.worldmanagement.world.WorldIdentitySnapshot identity
        ) {
            final WorldMetadata metadata = WorldMetadata.createDefault(worldName, true);
            return new LifecycleWorld(
                identity == null ? metadata.identity() : identity,
                metadata.lifecycleCapability()
            );
        }

        @Override
        public int playerCount(final LifecycleWorld world) { return players; }

        @Override
        public CompletableFuture<Boolean> teleportPlayersToWorld(
            final LifecycleWorld source,
            final LifecycleWorld target
        ) {
            lastTeleport = source.name() + "->" + target.name();
            teleportStarted.complete(null);
            if (pendingTeleport != null) {
                return pendingTeleport;
            }
            if (teleportSucceeds) {
                players = 0;
            }
            return CompletableFuture.completedFuture(teleportSucceeds);
        }

        @Override
        public Optional<LifecycleWorld> primaryWorld() {
            return loaded.stream().findFirst().map(worldId -> {
                final WorldMetadata metadata = WorldMetadata.createDefault(worldId, true);
                final var observed = lookupIdentities.getOrDefault(
                    worldId, runtimeIdentities.getOrDefault(worldId, metadata.identity())
                );
                return new LifecycleWorld(observed, metadata.lifecycleCapability());
            });
        }

        @Override
        public CompletableFuture<Void> beginShutdown() {
            pendingOperationsCancelled = true;
            if (pendingTeleport != null) {
                pendingTeleport.complete(false);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class BlockingDeletingRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();
        private final java.util.concurrent.CountDownLatch deletingWriteStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseDeletingWrite = new java.util.concurrent.CountDownLatch(1);

        @Override
        public Collection<WorldMetadata> loadAll() {
            return delegate.loadAll();
        }

        @Override
        public Optional<WorldMetadata> find(final String worldName) {
            return delegate.find(worldName);
        }

        @Override
        public void create(final WorldMetadata metadata) {
            delegate.create(metadata);
        }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            if (metadata.managementState() == io.github.bearl.worldmanagement.world.WorldManagementState.DELETING) {
                deletingWriteStarted.countDown();
                try {
                    releaseDeletingWrite.await();
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new StorageException("Interrupted while writing deleting tombstone.", exception);
                }
            }
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }

    private static final class BlockingCreateRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();
        private final java.util.concurrent.CountDownLatch createStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseCreate = new java.util.concurrent.CountDownLatch(1);

        @Override
        public Collection<WorldMetadata> loadAll() {
            return delegate.loadAll();
        }

        @Override
        public Optional<WorldMetadata> find(final String worldName) {
            return delegate.find(worldName);
        }

        @Override
        public void create(final WorldMetadata metadata) {
            createStarted.countDown();
            try {
                releaseCreate.await();
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new StorageException("Interrupted while writing created metadata.", exception);
            }
            delegate.create(metadata);
        }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }

    private static final class FailingDeleteStorageGateway implements WorldStorageGateway {
        private boolean restored;
        private boolean deleteCalled;

        @Override
        public boolean exists(final String worldId) { return true; }

        @Override
        public boolean isImportable(final String worldId) { return true; }

        @Override
        public Optional<LoadClaim> prepareLoad(final WorldMetadata metadata) {
            return Optional.empty();
        }

        @Override
        public void validateLoadClaim(final LoadClaim loadClaim) {
            throw new UnsupportedOperationException("Load claims are not used by this delete failure fixture.");
        }

        @Override
        public Optional<CreationClaim> prepareCreation(final String worldId) {
            return Optional.of(new CreationClaim() { });
        }

        @Override
        public OwnedCreationClaim bindCreated(
            final CreationClaim creationClaim,
            final io.github.bearl.worldmanagement.world.VerifiedWorldRef world
        ) {
            return () -> world;
        }

        @Override
        public void deleteCreated(final OwnedCreationClaim creationClaim) {
        }

        @Override
        public QuarantinedWorld quarantine(final WorldMetadata metadata) {
            final var world = io.github.bearl.worldmanagement.world.VerifiedWorldRef.from(metadata).orElseThrow();
            final long metadataVersion = metadata.version();
            final UUID transactionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
            return new QuarantinedWorld() {
                @Override
                public io.github.bearl.worldmanagement.world.VerifiedWorldRef world() { return world; }

                @Override
                public long metadataVersion() { return metadataVersion; }

                @Override
                public UUID transactionId() { return transactionId; }
            };
        }

        @Override
        public void restore(final QuarantinedWorld quarantinedWorld) { restored = true; }

        @Override
        public void delete(final QuarantinedWorld quarantinedWorld) {
            deleteCalled = true;
            throw new io.github.bearl.worldmanagement.storage.StorageException("simulated permanent delete failure");
        }

        @Override
        public Set<String> recoverQuarantined(final Set<WorldMetadata> metadata) { return Set.of(); }
    }

    private static final class MissingLoadStorageGateway implements WorldStorageGateway {

        @Override
        public boolean exists(final String worldId) { return false; }

        @Override
        public boolean isImportable(final String worldId) { return false; }

        @Override
        public Optional<LoadClaim> prepareLoad(final WorldMetadata metadata) { return Optional.empty(); }

        @Override
        public void validateLoadClaim(final LoadClaim loadClaim) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<CreationClaim> prepareCreation(final String worldId) { return Optional.empty(); }

        @Override
        public OwnedCreationClaim bindCreated(
            final CreationClaim creationClaim,
            final io.github.bearl.worldmanagement.world.VerifiedWorldRef world
        ) { throw new UnsupportedOperationException(); }

        @Override
        public void deleteCreated(final OwnedCreationClaim creationClaim) { throw new UnsupportedOperationException(); }

        @Override
        public QuarantinedWorld quarantine(final WorldMetadata metadata) { throw new UnsupportedOperationException(); }

        @Override
        public void restore(final QuarantinedWorld quarantinedWorld) { throw new UnsupportedOperationException(); }

        @Override
        public void delete(final QuarantinedWorld quarantinedWorld) { throw new UnsupportedOperationException(); }

        @Override
        public Set<String> recoverQuarantined(final Set<WorldMetadata> metadata) { return Set.of(); }
    }

    private static final class FailingCreateMetadataRepository implements WorldMetadataRepository {

        @Override
        public Collection<WorldMetadata> loadAll() { return Set.of(); }

        @Override
        public Optional<WorldMetadata> find(final String worldName) { return Optional.empty(); }

        @Override
        public void create(final WorldMetadata metadata) {
            throw new StorageException("simulated metadata create failure");
        }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FailingDeleteMetadataRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();

        @Override
        public Collection<WorldMetadata> loadAll() { return delegate.loadAll(); }

        @Override
        public Optional<WorldMetadata> find(final String worldName) { return delegate.find(worldName); }

        @Override
        public void create(final WorldMetadata metadata) { delegate.create(metadata); }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            throw new StorageException("simulated metadata purge failure");
        }
    }

    private static final class AmbiguousDeletingCommitRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();

        @Override
        public Collection<WorldMetadata> loadAll() { return delegate.loadAll(); }

        @Override
        public Optional<WorldMetadata> find(final String worldName) { return delegate.find(worldName); }

        @Override
        public void create(final WorldMetadata metadata) { delegate.create(metadata); }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            delegate.replace(metadata, expectedVersion);
            if (metadata.managementState()
                == io.github.bearl.worldmanagement.world.WorldManagementState.DELETING) {
                throw new StorageException("simulated lost commit acknowledgement");
            }
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }

    private static final class FailingReplaceMetadataRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();
        private boolean failReplace;

        @Override
        public Collection<WorldMetadata> loadAll() { return delegate.loadAll(); }

        @Override
        public Optional<WorldMetadata> find(final String worldName) { return delegate.find(worldName); }

        @Override
        public void create(final WorldMetadata metadata) { delegate.create(metadata); }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            if (failReplace) {
                failReplace = false;
                throw new StorageException("simulated metadata replace failure");
            }
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }

    private WorldLifecycleCoordinator service(
        final WorldRuntimeGateway gateway,
        final WorldManagementService metadata,
        final PluginIoExecutor executor
    ) {
        return service(gateway, metadata, executor, Optional.empty());
    }

    private WorldLifecycleCoordinator service(
        final WorldRuntimeGateway gateway,
        final WorldManagementService metadata,
        final PluginIoExecutor executor,
        final Optional<String> fallbackWorld
    ) {
        return service(gateway, metadata, executor, fallbackWorld, temporaryDirectory);
    }

    private static WorldLifecycleCoordinator service(
        final WorldRuntimeGateway gateway,
        final WorldManagementService metadata,
        final PluginIoExecutor executor,
        final Optional<String> fallbackWorld,
        final Path worldContainer
    ) {
        if (gateway instanceof FakeGateway fakeGateway && fakeGateway.createdWorldDirectory == null) {
            fakeGateway.createdWorldDirectory = worldContainer.resolve("dimensions")
                .resolve("minecraft").resolve("creative");
        }
        return new WorldLifecycleCoordinator(
            gateway,
            metadata,
            true,
            executor,
            storage(worldContainer),
            Duration.ZERO,
            new ImmediateDispatcher(),
            fallbackWorld
        );
    }

    private static WorldStorageGateway storage(final Path levelDirectory) {
        return new PaperWorldStorageGateway(
            levelDirectory,
            levelDirectory,
            new io.github.bearl.worldmanagement.core.WorldNameValidator(),
            new WorldDirectoryRemover(new io.github.bearl.worldmanagement.core.WorldNameValidator())
        );
    }

    private static void createPaperStorage(final Path levelDirectory, final String worldId) throws Exception {
        createPaperStorage(
            levelDirectory,
            worldId,
            UUID.nameUUIDFromBytes(
                ("paper-fixture:" + worldId).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            )
        );
    }

    private static void createPaperStorage(
        final Path levelDirectory,
        final String worldId,
        final UUID worldUuid
    ) throws java.io.IOException {
        final Path data = Files.createDirectories(
            levelDirectory.resolve("dimensions").resolve("minecraft").resolve(worldId).resolve("data")
        );
        Files.createDirectories(data.resolve("minecraft"));
        Files.createDirectories(data.resolve("paper"));
        Files.writeString(data.resolve("minecraft").resolve("world_gen_settings.dat"), "worldgen");
        final int[] encodedUuid = {
            (int) (worldUuid.getMostSignificantBits() >> 32),
            (int) worldUuid.getMostSignificantBits(),
            (int) (worldUuid.getLeastSignificantBits() >> 32),
            (int) worldUuid.getLeastSignificantBits()
        };
        final net.kyori.adventure.nbt.CompoundBinaryTag root =
            net.kyori.adventure.nbt.CompoundBinaryTag.builder()
                .put("data", net.kyori.adventure.nbt.CompoundBinaryTag.builder()
                    .putIntArray("uuid", encodedUuid)
                    .build())
                .build();
        net.kyori.adventure.nbt.BinaryTagIO.writer().write(
            root,
            data.resolve("paper").resolve("metadata.dat"),
            net.kyori.adventure.nbt.BinaryTagIO.Compression.GZIP
        );
        Files.writeString(data.resolve("paper").resolve("level_overrides.dat"), "overrides");
    }

    private static void writeLegacyUuid(final Path path, final UUID worldUuid) throws Exception {
        try (final java.io.DataOutputStream output = new java.io.DataOutputStream(Files.newOutputStream(path))) {
            output.writeLong(worldUuid.getMostSignificantBits());
            output.writeLong(worldUuid.getLeastSignificantBits());
        }
    }

    private static UUID readLegacyUuid(final Path path) throws Exception {
        try (final java.io.DataInputStream input = new java.io.DataInputStream(Files.newInputStream(path))) {
            return new UUID(input.readLong(), input.readLong());
        }
    }

    private static final class ImmediateDispatcher implements WorldThreadDispatcher {
        @Override
        public void executeGlobal(final Runnable task) { task.run(); }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
        }
    }

    private static final class RecordingWorldTrackingHook implements WorldTrackingHook {
        private final java.util.List<String> untrackedWorlds = new java.util.ArrayList<>();
        private UntrackStatus status = UntrackStatus.UNTRACKED;

        @Override
        public UntrackStatus untrack(final String worldName) {
            untrackedWorlds.add(worldName);
            return status;
        }
    }

    private static final class GlobalContextDispatcher implements WorldThreadDispatcher {
        private final ThreadLocal<Boolean> globalContext = ThreadLocal.withInitial(() -> false);

        @Override
        public void executeGlobal(final Runnable task) {
            final boolean previous = globalContext.get();
            globalContext.set(true);
            try {
                task.run();
            } finally {
                globalContext.set(previous);
            }
        }

        @Override
        public void executeGlobalLater(final Duration delay, final Runnable task) { executeGlobal(task); }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() { }

        private boolean inGlobalContext() { return globalContext.get(); }
    }

    private static final class DelayedDispatcher implements WorldThreadDispatcher {
        private final CompletableFuture<Runnable> delayedTask = new CompletableFuture<>();

        @Override
        public void executeGlobal(final Runnable task) { task.run(); }

        @Override
        public void executeGlobalLater(final Duration delay, final Runnable task) { delayedTask.complete(task); }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
        }

        private Runnable awaitDelayed() {
            return delayedTask.join();
        }
    }

    private static final class ManualGlobalDispatcher implements WorldThreadDispatcher {
        private final java.util.concurrent.BlockingQueue<Runnable> globalTasks =
            new java.util.concurrent.LinkedBlockingQueue<>();

        @Override
        public void executeGlobal(final Runnable task) { globalTasks.add(task); }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() { }

        private void runNextGlobal() throws InterruptedException {
            final Runnable task = globalTasks.poll(1, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(task != null, "Expected a global scheduler task");
            task.run();
        }
    }

    private static final class QuarantineBarrierDispatcher implements WorldThreadDispatcher {
        private final CompletableFuture<Runnable> delayedTask = new CompletableFuture<>();
        private final CompletableFuture<Runnable> afterQuarantine = new CompletableFuture<>();
        private int globalExecutions;

        @Override
        public void executeGlobal(final Runnable task) {
            if (++globalExecutions <= 2) {
                task.run();
            } else {
                afterQuarantine.complete(task);
            }
        }

        @Override
        public void executeGlobalLater(final Duration delay, final Runnable task) {
            delayedTask.complete(task);
        }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
        }

        private Runnable awaitDelayed() {
            return delayedTask.join();
        }

        private Runnable awaitAfterQuarantine() {
            return afterQuarantine.join();
        }
    }

    private static final class CancellableQuarantineDispatcher implements WorldThreadDispatcher {
        private final CompletableFuture<Runnable> delayedTask = new CompletableFuture<>();
        private final CompletableFuture<Runnable> cancellation = new CompletableFuture<>();
        private int globalExecutions;

        @Override
        public void executeGlobal(final Runnable task) {
            executeGlobal(task, () -> { });
        }

        @Override
        public void executeGlobal(final Runnable task, final Runnable cancelledTask) {
            if (++globalExecutions <= 2) {
                task.run();
            } else {
                cancellation.complete(cancelledTask);
            }
        }

        @Override
        public void executeGlobalLater(final Duration delay, final Runnable task, final Runnable cancelledTask) {
            delayedTask.complete(task);
        }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
            cancellation.join().run();
        }

        private Runnable awaitDelayed() {
            return delayedTask.join();
        }

        private void awaitCancellation() {
            cancellation.join();
        }
    }

    private static final class CancellableContinuationDispatcher implements WorldThreadDispatcher {
        private final CompletableFuture<Runnable> cancellation = new CompletableFuture<>();
        private int globalExecutions;

        @Override
        public void executeGlobal(final Runnable task) {
            executeGlobal(task, () -> { });
        }

        @Override
        public void executeGlobal(final Runnable task, final Runnable cancelledTask) {
            if (++globalExecutions == 1) {
                task.run();
            } else {
                cancellation.complete(cancelledTask);
            }
        }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
            cancellation.join().run();
        }
    }
}