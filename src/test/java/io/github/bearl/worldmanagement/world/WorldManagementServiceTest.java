package io.github.bearl.worldmanagement.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.WorldMetadataRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class WorldManagementServiceTest {

    @Test
    void resolvesRuntimeIdentityBeforeApplyingManagedMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldServiceTest");
        try {
            final WorldManagementService service = new WorldManagementService(
                executor,
                new InMemoryWorldMetadataRepository(),
                new WorldRegistry()
            );
            service.load().join();
            final WorldIdentitySnapshot accepted = new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                WorldEnvironment.NORMAL,
                42L,
                true
            );
            service.adopt(accepted, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();

            assertEquals(WorldRuntimeResolution.Status.VERIFIED, service.resolveRuntimeWorld(
                accepted, LifecycleCapability.MANAGED
            ).status());
            assertEquals(WorldRuntimeResolution.Status.UNMANAGED, service.resolveRuntimeWorld(
                new WorldIdentitySnapshot(
                    "minecraft:survival",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    WorldEnvironment.NORMAL,
                    42L,
                    true
                ), LifecycleCapability.MANAGED
            ).status());
            assertEquals(WorldRuntimeResolution.Status.ISOLATED, service.resolveRuntimeWorld(
                new WorldIdentitySnapshot(
                    "minecraft:creative",
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    WorldEnvironment.NORMAL,
                    42L,
                    true
                ), LifecycleCapability.MANAGED
            ).status());
            assertEquals(WorldRuntimeResolution.Status.ISOLATED, service.resolveRuntimeWorld(
                new WorldIdentitySnapshot(
                    "minecraft:creative",
                    accepted.worldUuid(),
                    WorldEnvironment.NORMAL,
                    99L,
                    true
                ), LifecycleCapability.MANAGED
            ).status());
            assertEquals(WorldRuntimeResolution.Status.ISOLATED, service.resolveRuntimeWorld(
                accepted, LifecycleCapability.EXTERNAL_ONLY
            ).status());
            service.remove("creative").join();
            assertEquals(WorldRuntimeResolution.Status.UNMANAGED, service.resolveRuntimeWorld(
                accepted, LifecycleCapability.MANAGED
            ).status());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void adoptsOnceAndPublishesThePersistedMetadataToTheRegistry() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final WorldManagementService service = new WorldManagementService(
                executor,
                new InMemoryWorldMetadataRepository(),
                new WorldRegistry()
            );
            service.load().join();

            final WorldManagementService.AdoptionResult first = service.adopt("creative", true).join();
            final WorldManagementService.AdoptionResult second = service.adopt("creative", true).join();

            assertEquals(WorldManagementService.AdoptionStatus.ADOPTED, first.status());
            assertEquals(WorldManagementService.AdoptionStatus.ALREADY_MANAGED, second.status());
            assertTrue(service.managedWorlds().stream().anyMatch(world -> world.worldName().equals("creative")));
            assertEquals(first.metadata(), service.snapshot().byId().get("creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsIdentityCollisionBeforePersistingAdoptedMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final UUID sharedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            repository.create(WorldMetadata.createDefault(
                "existing",
                new WorldIdentitySnapshot(
                    "minecraft:existing", sharedUuid, WorldEnvironment.NORMAL, 42L, true
                ),
                LifecycleCapability.MANAGED,
                Optional.empty(),
                true
            ));
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();

            final WorldManagementService.AdoptionResult result = service.adopt(
                new WorldIdentitySnapshot(
                    "minecraft:unknown", sharedUuid, WorldEnvironment.NORMAL, 42L, true
                ),
                LifecycleCapability.MANAGED,
                Optional.empty(),
                true,
                null
            ).join();

            assertEquals(WorldManagementService.AdoptionStatus.IDENTITY_CONFLICT, result.status());
            assertTrue(repository.find("unknown").isEmpty());
            assertTrue(service.metadataWorld("unknown").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void persistsMutationsBeforePublishingUpdatedMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            service.adopt("creative", true).join();

            final WorldManagementService.UpdateResult result = service.update(
                "creative",
                metadata -> metadata.withOwner("Alex")
            ).join();

            assertEquals(WorldManagementService.UpdateStatus.UPDATED, result.status());
            assertEquals("Alex", repository.find("creative").orElseThrow().owner());
            assertEquals(1, service.managedWorlds().getFirst().version());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void classifiesAndPersistsLoadedIdentityDriftBeforePublishingIt() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final UUID worldUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final WorldIdentitySnapshot accepted = new WorldIdentitySnapshot(
                "minecraft:creative", worldUuid, WorldEnvironment.NORMAL, 42L, true
            );
            repository.create(WorldMetadata.createDefault(
                "creative", accepted, LifecycleCapability.MANAGED, Optional.empty(), true
            ));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            final WorldIdentitySnapshot observed = new WorldIdentitySnapshot(
                "minecraft:creative", worldUuid, WorldEnvironment.NORMAL, 99L, true
            );

            final WorldManagementService.UpdateResult result = service.classifyLoadedIdentity(observed).join();

            assertEquals(WorldManagementService.UpdateStatus.UPDATED, result.status());
            assertEquals(IdentityVerificationState.SYNC_PENDING, result.metadata().identityState());
            assertEquals(accepted, result.metadata().identity());
            assertEquals(Optional.of(observed), result.metadata().pendingIdentity());
            assertEquals(result.metadata(), repository.find("creative").orElseThrow());
            assertEquals(result.metadata(), service.metadataWorld("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void detachedLifecycleIdentityCheckNeverMutatesMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot accepted = identity("11111111-1111-1111-1111-111111111111", 42L);
            repository.create(WorldMetadata.createDefault(
                "creative", accepted, LifecycleCapability.MANAGED, Optional.empty(), true
            ).withManagementState(WorldManagementState.DETACHED));
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            final WorldMetadata before = service.metadataWorld("creative").orElseThrow();

            final WorldManagementService.UpdateResult matching = service.classifyLifecycleIdentity(
                accepted, LifecycleCapability.MANAGED
            ).join();
            final WorldManagementService.UpdateResult drifted = service.classifyLifecycleIdentity(
                identity("11111111-1111-1111-1111-111111111111", 99L), LifecycleCapability.MANAGED
            ).join();

            assertEquals(WorldManagementService.UpdateStatus.UPDATED, matching.status());
            assertEquals(WorldManagementService.UpdateStatus.NOT_MANAGED, drifted.status());
            assertEquals(before, service.metadataWorld("creative").orElseThrow());
            assertEquals(before, repository.find("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void synchronizesOnlyTheCurrentPendingSnapshotAndPersistsIt() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot accepted = identity("11111111-1111-1111-1111-111111111111", 42L);
            repository.create(WorldMetadata.createDefault(
                "creative", accepted, LifecycleCapability.MANAGED, Optional.empty(), true
            ));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            final WorldIdentitySnapshot observed = identity("11111111-1111-1111-1111-111111111111", 99L);
            service.classifyLoadedIdentity(observed).join();

            final WorldManagementService.IdentitySyncResult result = service.synchronizeIdentity(
                "creative", observed, null
            ).join();

            assertEquals(WorldManagementService.IdentitySyncStatus.SYNCHRONIZED, result.status());
            assertEquals(observed, result.metadata().identity());
            assertEquals(IdentityVerificationState.VERIFIED, result.metadata().identityState());
            assertEquals(result.metadata(), repository.find("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void staleIdentitySyncNeverAcceptsANewerPendingSnapshot() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final WorldManagementService service = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            service.load().join();
            final WorldIdentitySnapshot accepted = identity("11111111-1111-1111-1111-111111111111", 42L);
            service.adopt(accepted, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            final WorldIdentitySnapshot older = identity("11111111-1111-1111-1111-111111111111", 88L);
            final WorldIdentitySnapshot newer = identity("11111111-1111-1111-1111-111111111111", 99L);
            service.classifyLoadedIdentity(newer).join();

            final WorldManagementService.IdentitySyncResult result = service.synchronizeIdentity(
                "creative", older, null
            ).join();

            assertEquals(WorldManagementService.IdentitySyncStatus.STALE, result.status());
            assertEquals(Optional.of(newer), service.metadataWorld("creative").orElseThrow().pendingIdentity());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void classifiesLoadedUuidReplacementAsConflict() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot accepted = identity("11111111-1111-1111-1111-111111111111", 42L);
            repository.create(WorldMetadata.createDefault(
                "creative", accepted, LifecycleCapability.MANAGED, Optional.empty(), true
            ));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            final WorldIdentitySnapshot replacement = identity("22222222-2222-2222-2222-222222222222", 42L);

            final WorldMetadata classified = service.classifyLoadedIdentity(replacement).join().metadata();

            assertEquals(IdentityVerificationState.CONFLICT, classified.identityState());
            assertEquals(accepted, classified.identity());
            assertEquals(Optional.of(replacement), classified.pendingIdentity());
            assertEquals(classified, repository.find("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void acceptsTheCurrentReplacementAndPersistsTheSelectedWarpPolicy() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot accepted = identity("11111111-1111-1111-1111-111111111111", 42L);
            repository.create(WorldMetadata.createDefault(
                "creative", accepted, LifecycleCapability.MANAGED, Optional.empty(), true
            ).withWarp(new WorldWarp(
                "spawn", 1, 64, 2, 0, 0, WarpVisibility.PUBLIC, java.util.Set.of(), java.util.Set.of(), ""
            )));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            final WorldIdentitySnapshot replacement = identity("22222222-2222-2222-2222-222222222222", 42L);
            service.classifyLoadedIdentity(replacement).join();

            final WorldManagementService.IdentityMutationResult result = service.acceptIdentityReplacement(
                "creative", replacement, false, null
            ).join();

            assertEquals(WorldManagementService.IdentityMutationStatus.REPLACEMENT_ACCEPTED, result.status());
            assertEquals(replacement, result.metadata().identity());
            assertTrue(result.metadata().warps().isEmpty());
            assertEquals(result.metadata(), repository.find("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsAReplacementWhoseUuidBelongsToAnotherWorldBeforePersistence() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot creative = identity("11111111-1111-1111-1111-111111111111", 42L);
            final WorldIdentitySnapshot survival = new WorldIdentitySnapshot(
                "minecraft:survival",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                WorldEnvironment.NORMAL,
                7L,
                true
            );
            repository.create(WorldMetadata.createDefault(
                "creative", creative, LifecycleCapability.MANAGED, Optional.empty(), true
            ));
            repository.create(WorldMetadata.createDefault(
                "survival", survival, LifecycleCapability.MANAGED, Optional.empty(), true
            ));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            final WorldIdentitySnapshot collidingReplacement = new WorldIdentitySnapshot(
                "minecraft:creative", survival.worldUuid(), WorldEnvironment.NORMAL, 42L, true
            );
            final WorldMetadata conflict = service.classifyLoadedIdentity(collidingReplacement).join().metadata();

            final WorldManagementService.IdentityMutationResult result = service.acceptIdentityReplacement(
                "creative", collidingReplacement, true, null
            ).join();

            assertEquals(WorldManagementService.IdentityMutationStatus.INDEX_CONFLICT, result.status());
            assertEquals(conflict, repository.find("creative").orElseThrow());
            assertEquals(conflict, service.metadataWorld("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void abandonsOnlyTheExpectedNonVerifiedVersion() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            final WorldIdentitySnapshot accepted = identity("11111111-1111-1111-1111-111111111111", 42L);
            service.adopt(accepted, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            final WorldMetadata conflict = service.classifyLoadedIdentity(
                identity("22222222-2222-2222-2222-222222222222", 42L)
            ).join().metadata();

            final WorldManagementService.IdentityMutationResult result = service.abandonIdentity(
                "creative", conflict.version(), null
            ).join();

            assertEquals(WorldManagementService.IdentityMutationStatus.ABANDONED, result.status());
            assertEquals(WorldManagementState.DETACHED, result.metadata().managementState());
            assertEquals(result.metadata(), repository.find("creative").orElseThrow());
            assertEquals(
                WorldManagementService.IdentityMutationStatus.STALE,
                service.abandonIdentity("creative", conflict.version(), null).join().status()
            );
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void persistsExternalOnlyCapabilityObservedAtRuntime() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot identity = identity("11111111-1111-1111-1111-111111111111", 42L);
            repository.create(WorldMetadata.createDefault(
                "creative", identity, LifecycleCapability.MANAGED, Optional.empty(), true
            ));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();

            final WorldMetadata classified = service.classifyLoadedIdentity(
                identity, LifecycleCapability.EXTERNAL_ONLY
            ).join().metadata();

            assertEquals(LifecycleCapability.EXTERNAL_ONLY, classified.lifecycleCapability());
            assertEquals(classified, repository.find("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void keepsManagedCapabilityForPersistedGeneratorWorld() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot identity = identity("11111111-1111-1111-1111-111111111111", 42L);
            repository.create(WorldMetadata.createDefault(
                "creative", identity, LifecycleCapability.MANAGED, Optional.of(RequestedWorldType.NORMAL),
                Optional.of(WorldGeneratorReference.parse("Terra:normal")), true
            ));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();

            final WorldMetadata classified = service.classifyLoadedIdentity(
                identity, LifecycleCapability.EXTERNAL_ONLY
            ).join().metadata();

            assertEquals(LifecycleCapability.MANAGED, classified.lifecycleCapability());
            assertEquals(classified, repository.find("creative").orElseThrow());
            assertEquals(WorldRuntimeResolution.Status.VERIFIED, service.resolveRuntimeWorld(
                identity, LifecycleCapability.EXTERNAL_ONLY
            ).status());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void keepsManagedCapabilityForPersistedBiomeProviderWorld() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldIdentitySnapshot identity = identity("11111111-1111-1111-1111-111111111111", 42L);
            repository.create(WorldMetadata.createDefault(
                "creative", identity, LifecycleCapability.MANAGED, Optional.of(RequestedWorldType.NORMAL),
                Optional.empty(), Optional.of(WorldGeneratorReference.parse("Terra:climate")), true
            ));
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();

            final WorldMetadata classified = service.classifyLoadedIdentity(
                identity, LifecycleCapability.EXTERNAL_ONLY
            ).join().metadata();

            assertEquals(LifecycleCapability.MANAGED, classified.lifecycleCapability());
            assertEquals(classified, repository.find("creative").orElseThrow());
            assertEquals(WorldRuntimeResolution.Status.VERIFIED, service.resolveRuntimeWorld(
                identity, LifecycleCapability.EXTERNAL_ONLY
            ).status());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void keepsVerifiedRegistrySnapshotWhenIdentityPersistenceFails() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final WorldMetadata accepted = WorldMetadata.createDefault(
                "creative",
                identity("11111111-1111-1111-1111-111111111111", 42L),
                LifecycleCapability.MANAGED,
                Optional.empty(),
                true
            );
            final FailingReplaceRepository repository = new FailingReplaceRepository(accepted);
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();

            assertThrows(CompletionException.class, () -> service.classifyLoadedIdentity(
                identity("11111111-1111-1111-1111-111111111111", 99L)
            ).join());

            assertEquals(accepted, service.metadataWorld("creative").orElseThrow());
            assertEquals(accepted, repository.find("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static WorldIdentitySnapshot identity(final String uuid, final long seed) {
        return new WorldIdentitySnapshot(
            "minecraft:creative", UUID.fromString(uuid), WorldEnvironment.NORMAL, seed, true
        );
    }

    @Test
    void removesByDetachingAndPurgesOnlyAfterExplicitConfirmation() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldManagementService service = new WorldManagementService(executor, repository, new WorldRegistry());
            service.load().join();
            service.adopt("creative", true).join();

            final WorldManagementService.RemoveResult detached = service.remove("creative").join();

            assertEquals(WorldManagementService.RemoveStatus.DETACHED, detached.status());
            assertEquals(WorldManagementState.DETACHED, repository.find("creative").orElseThrow().managementState());
            assertTrue(service.detachedWorlds().stream().anyMatch(world -> world.worldName().equals("creative")));
            assertTrue(service.manage("creative", null).join().metadata().managementState() == WorldManagementState.ACTIVE);
            assertEquals(WorldManagementState.ACTIVE, repository.find("creative").orElseThrow().managementState());

            service.remove("creative").join();
            final WorldManagementService.RemoveResult purged = service.purgeDetached("creative", null).join();

            assertEquals(WorldManagementService.RemoveStatus.PURGED, purged.status());
            assertFalse(repository.find("creative").isPresent());
            assertTrue(service.managedWorld("creative").isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void managedMutationRechecksActiveStateAfterQueuedDetach() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final BlockingDetachRepository repository = new BlockingDetachRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();

            final CompletableFuture<WorldManagementService.RemoveResult> detach = service.remove("creative");
            assertTrue(repository.detachStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            final CompletableFuture<WorldManagementService.UpdateResult> mutation = service.updateManaged(
                "creative", metadata -> metadata.withOwner("replacement-owner")
            );
            repository.releaseDetach.countDown();

            assertEquals(WorldManagementService.RemoveStatus.DETACHED, detach.join().status());
            assertEquals(WorldManagementService.UpdateStatus.NOT_MANAGED, mutation.join().status());
            final WorldMetadata detached = repository.find("creative").orElseThrow();
            assertEquals(WorldManagementState.DETACHED, detached.managementState());
            assertEquals(WorldMetadata.SERVER_OWNER, detached.owner());
            assertEquals(1, repository.replaceCalls);
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void marksDeletingOnlyForTheExactQuarantineClaim() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();
            final WorldMetadata current = service.managedWorld("creative").orElseThrow();
            final UUID transactionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final WorldDeletionClaim claim = new WorldDeletionClaim(
                VerifiedWorldRef.from(current).orElseThrow(), current.version(), transactionId,
                WorldManagementState.ACTIVE
            );

            final WorldManagementService.DeletionTransitionResult result =
                service.markDeleting(claim, null).join();

            assertEquals(WorldManagementService.DeletionTransitionStatus.UPDATED, result.status());
            final WorldMetadata deleting = repository.find("creative").orElseThrow();
            assertEquals(WorldManagementState.DELETING, deleting.managementState());
            assertEquals(Optional.of(transactionId), deleting.deletionTransactionId());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void queuedIdentityClassificationCannotMutateADeletingTombstone() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final BlockingDeletingRepository repository = new BlockingDeletingRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();
            final WorldMetadata current = service.managedWorld("creative").orElseThrow();
            final WorldDeletionClaim claim = new WorldDeletionClaim(
                VerifiedWorldRef.from(current).orElseThrow(), current.version(),
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                WorldManagementState.ACTIVE
            );

            final CompletableFuture<WorldManagementService.DeletionTransitionResult> deleting =
                service.markDeleting(claim, null);
            assertTrue(repository.deletingWriteStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            final CompletableFuture<WorldManagementService.UpdateResult> classification =
                service.classifyLoadedIdentity(identity(
                    current.identity().worldUuid().toString(), current.identity().seed() + 1L
                ));
            repository.releaseDeletingWrite.countDown();

            assertEquals(WorldManagementService.DeletionTransitionStatus.UPDATED, deleting.join().status());
            assertEquals(WorldManagementService.UpdateStatus.NOT_MANAGED, classification.join().status());
            final WorldMetadata tombstone = service.metadataWorld("creative").orElseThrow();
            assertEquals(WorldManagementState.DELETING, tombstone.managementState());
            assertEquals(current.version() + 1L, tombstone.version());
            assertEquals(Optional.of(claim.transactionId()), tombstone.deletionTransactionId());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unchangedIdentityClassificationWaitsBehindADeletingTombstone() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final BlockingDeletingRepository repository = new BlockingDeletingRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();
            final WorldMetadata current = service.managedWorld("creative").orElseThrow();
            final WorldDeletionClaim claim = new WorldDeletionClaim(
                VerifiedWorldRef.from(current).orElseThrow(), current.version(),
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                WorldManagementState.ACTIVE
            );

            final CompletableFuture<WorldManagementService.DeletionTransitionResult> deleting =
                service.markDeleting(claim, null);
            assertTrue(repository.deletingWriteStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            final CompletableFuture<WorldManagementService.UpdateResult> classification =
                service.classifyLoadedIdentity(current.identity(), current.lifecycleCapability());

            assertFalse(classification.isDone());
            repository.releaseDeletingWrite.countDown();

            assertEquals(WorldManagementService.DeletionTransitionStatus.UPDATED, deleting.join().status());
            assertEquals(WorldManagementService.UpdateStatus.NOT_MANAGED, classification.join().status());
            assertEquals(WorldManagementState.DELETING, service.metadataWorld("creative").orElseThrow().managementState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void unchangedIdentityClassificationDoesNotRewriteMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final CountingReplaceRepository repository = new CountingReplaceRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();
            final WorldMetadata before = service.managedWorld("creative").orElseThrow();

            final WorldManagementService.UpdateResult result = service.classifyLoadedIdentity(
                before.identity(), before.lifecycleCapability()
            ).join();

            assertEquals(WorldManagementService.UpdateStatus.UPDATED, result.status());
            assertEquals(0, repository.replaceCalls);
            assertEquals(before, service.metadataWorld("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void publishesDeletingTombstoneAfterAmbiguousRepositoryCommit() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final AmbiguousDeletingCommitRepository repository = new AmbiguousDeletingCommitRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();
            final WorldMetadata current = service.managedWorld("creative").orElseThrow();
            final WorldDeletionClaim claim = new WorldDeletionClaim(
                VerifiedWorldRef.from(current).orElseThrow(), current.version(),
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                WorldManagementState.ACTIVE
            );

            final WorldManagementService.DeletionTransitionResult result =
                service.markDeleting(claim, null).join();

            assertEquals(WorldManagementService.DeletionTransitionStatus.UPDATED, result.status());
            final WorldMetadata tombstone = service.metadataWorld("creative").orElseThrow();
            assertEquals(WorldManagementState.DELETING, tombstone.managementState());
            assertEquals(current.version() + 1L, tombstone.version());
            assertEquals(Optional.of(claim.transactionId()), tombstone.deletionTransactionId());
            assertEquals(tombstone, repository.find("creative").orElseThrow());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsStaleOrMismatchedDeletionClaimsWithoutWritingATombstone() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();
            final WorldMetadata current = service.managedWorld("creative").orElseThrow();
            final UUID transactionId = UUID.fromString("22222222-2222-2222-2222-222222222222");

            final WorldDeletionClaim stale = new WorldDeletionClaim(
                VerifiedWorldRef.from(current).orElseThrow(), current.version() + 1, transactionId,
                WorldManagementState.ACTIVE
            );
            assertEquals(
                WorldManagementService.DeletionTransitionStatus.STALE,
                service.markDeleting(stale, null).join().status()
            );

            final WorldDeletionClaim wrongIdentity = new WorldDeletionClaim(
                new VerifiedWorldRef(
                    "creative", "minecraft:creative",
                    UUID.fromString("33333333-3333-3333-3333-333333333333")
                ),
                current.version(), transactionId, WorldManagementState.ACTIVE
            );
            assertEquals(
                WorldManagementService.DeletionTransitionStatus.STALE,
                service.markDeleting(wrongIdentity, null).join().status()
            );

            service.remove("creative").join();
            final WorldMetadata detached = service.detachedWorld("creative").orElseThrow();
            final WorldDeletionClaim wrongState = new WorldDeletionClaim(
                VerifiedWorldRef.from(detached).orElseThrow(), detached.version(), transactionId,
                WorldManagementState.ACTIVE
            );
            assertEquals(
                WorldManagementService.DeletionTransitionStatus.STALE,
                service.markDeleting(wrongState, null).join().status()
            );
            assertEquals(WorldManagementState.DETACHED, repository.find("creative").orElseThrow().managementState());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void restoresDeletingStateOnlyForTheMatchingTransaction() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
            final WorldManagementService service = new WorldManagementService(
                executor, repository, new WorldRegistry()
            );
            service.load().join();
            service.adopt("creative", true).join();
            final WorldMetadata current = service.managedWorld("creative").orElseThrow();
            final WorldDeletionClaim claim = new WorldDeletionClaim(
                VerifiedWorldRef.from(current).orElseThrow(), current.version(),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                WorldManagementState.ACTIVE
            );
            service.markDeleting(claim, null).join();
            final WorldDeletionClaim wrongTransaction = new WorldDeletionClaim(
                claim.world(), claim.metadataVersion(),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                claim.originalManagementState()
            );

            assertEquals(
                WorldManagementService.DeletionTransitionStatus.STALE,
                service.cancelDeleting(wrongTransaction, null).join().status()
            );
            assertEquals(WorldManagementState.DELETING, repository.find("creative").orElseThrow().managementState());

            assertEquals(
                WorldManagementService.DeletionTransitionStatus.UPDATED,
                service.cancelDeleting(claim, null).join().status()
            );
            final WorldMetadata restored = repository.find("creative").orElseThrow();
            assertEquals(WorldManagementState.ACTIVE, restored.managementState());
            assertTrue(restored.deletionTransactionId().isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsMetadataMutationsAfterSuccessfulMigrationFreeze() {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");
        try {
            final MetadataMutationGate mutationGate = new MetadataMutationGate(executor);
            final WorldManagementService service = new WorldManagementService(
                executor,
                new InMemoryWorldMetadataRepository(),
                new WorldRegistry(),
                mutationGate
            );
            service.load().join();
            mutationGate.submitMigration(() -> null).join();

            org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> service.adopt("creative", true).join()
            );
            assertTrue(service.managedWorlds().isEmpty());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static final class FailingReplaceRepository implements WorldMetadataRepository {

        private final WorldMetadata metadata;

        private FailingReplaceRepository(final WorldMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public Collection<WorldMetadata> loadAll() {
            return java.util.List.of(metadata);
        }

        @Override
        public Optional<WorldMetadata> find(final String worldName) {
            return metadata.worldName().equals(worldName) ? Optional.of(metadata) : Optional.empty();
        }

        @Override
        public void create(final WorldMetadata created) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replace(final WorldMetadata updated, final long expectedVersion) {
            throw new StorageException("simulated identity persistence failure");
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class BlockingDetachRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();
        private final java.util.concurrent.CountDownLatch detachStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseDetach = new java.util.concurrent.CountDownLatch(1);
        private int replaceCalls;

        @Override
        public Collection<WorldMetadata> loadAll() { return delegate.loadAll(); }

        @Override
        public Optional<WorldMetadata> find(final String worldName) { return delegate.find(worldName); }

        @Override
        public void create(final WorldMetadata metadata) { delegate.create(metadata); }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            if (metadata.managementState() == WorldManagementState.DETACHED) {
                detachStarted.countDown();
                try {
                    releaseDetach.await();
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new StorageException("Interrupted while detaching metadata.", exception);
                }
            }
            replaceCalls++;
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }

    private static final class BlockingDeletingRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();
        private final java.util.concurrent.CountDownLatch deletingWriteStarted =
            new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseDeletingWrite =
            new java.util.concurrent.CountDownLatch(1);

        @Override
        public Collection<WorldMetadata> loadAll() { return delegate.loadAll(); }

        @Override
        public Optional<WorldMetadata> find(final String worldName) { return delegate.find(worldName); }

        @Override
        public void create(final WorldMetadata metadata) { delegate.create(metadata); }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            if (metadata.managementState() == WorldManagementState.DELETING) {
                deletingWriteStarted.countDown();
                try {
                    releaseDeletingWrite.await();
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new StorageException("Interrupted while writing deleting metadata.", exception);
                }
            }
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
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
            if (metadata.managementState() == WorldManagementState.DELETING) {
                throw new StorageException("simulated lost commit acknowledgement");
            }
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }

    private static final class CountingReplaceRepository implements WorldMetadataRepository {
        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();
        private int replaceCalls;

        @Override
        public Collection<WorldMetadata> loadAll() { return delegate.loadAll(); }

        @Override
        public Optional<WorldMetadata> find(final String worldName) { return delegate.find(worldName); }

        @Override
        public void create(final WorldMetadata metadata) { delegate.create(metadata); }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            replaceCalls++;
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }
}