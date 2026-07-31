package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Synchronizes non-durable runtime snapshot drift with bounded, generation-aware retries. */
public final class WorldIdentityAutoSynchronizer {

    private static final Duration[] RETRY_DELAYS = {
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30)
    };

    private final WorldManagementService metadataService;
    private final Function<WorldRuntimeGateway.LifecycleWorld, CompletableFuture<WorldManagementService.IdentitySyncResult>> syncOperation;
    private final LoadedWorldCatalog loadedWorldCatalog;
    private final WorldThreadDispatcher dispatcher;
    private final Map<String, Synchronization> synchronizations = new ConcurrentHashMap<>();
    private final AtomicBoolean acceptingOperations = new AtomicBoolean(true);

    public WorldIdentityAutoSynchronizer(
        final WorldManagementService metadataService,
        final LoadedWorldCatalog loadedWorldCatalog,
        final WorldThreadDispatcher dispatcher
    ) {
        this(
            metadataService,
            observed -> metadataService.synchronizeIdentity(
                observed.name(),
                observed.identity(),
                new AuditEvent(
                    Instant.now(), Optional.of("system"), "world.identity.auto-sync", observed.name(), ""
                )
            ),
            loadedWorldCatalog,
            dispatcher
        );
    }

    WorldIdentityAutoSynchronizer(
        final WorldManagementService metadataService,
        final Function<WorldRuntimeGateway.LifecycleWorld, CompletableFuture<WorldManagementService.IdentitySyncResult>> syncOperation,
        final LoadedWorldCatalog loadedWorldCatalog,
        final WorldThreadDispatcher dispatcher
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.syncOperation = Objects.requireNonNull(syncOperation, "syncOperation");
        this.loadedWorldCatalog = Objects.requireNonNull(loadedWorldCatalog, "loadedWorldCatalog");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public CompletableFuture<Void> synchronize(
        final LoadedWorldCatalog.Observation observation,
        final WorldRuntimeGateway.LifecycleWorld observedWorld
    ) {
        final LoadedWorldCatalog.Observation requiredObservation = Objects.requireNonNull(
            observation, "observation"
        );
        final WorldRuntimeGateway.LifecycleWorld requiredWorld = Objects.requireNonNull(
            observedWorld, "observedWorld"
        );
        if (!requiredObservation.worldId().equals(requiredWorld.name())) {
            throw new IllegalArgumentException("Catalog observation must match the observed world ID.");
        }
        if (!acceptingOperations.get()) {
            return CompletableFuture.completedFuture(null);
        }
        final Synchronization synchronization = new Synchronization(
            requiredObservation, requiredWorld, new CompletableFuture<>()
        );
        synchronizations.compute(requiredWorld.name(), (ignored, previous) -> {
            if (previous != null) {
                previous.completion().complete(null);
            }
            return synchronization;
        });
        if (!acceptingOperations.get()) {
            complete(synchronization);
            return synchronization.completion();
        }
        attempt(synchronization, 0);
        return synchronization.completion();
    }

    public CompletableFuture<Void> synchronizePendingLoadedWorlds() {
        if (!acceptingOperations.get()) {
            return CompletableFuture.completedFuture(null);
        }
        final CompletableFuture<?>[] pending = metadataService.managedWorlds().stream()
            .filter(metadata -> metadata.identityState() == IdentityVerificationState.SYNC_PENDING)
            .map(metadata -> loadedWorldCatalog.findUniqueByWorldId(metadata.worldName())
                .flatMap(world -> loadedWorldCatalog.currentObservation(metadata.worldName())
                    .map(observation -> new PendingSynchronization(observation, world))))
            .flatMap(Optional::stream)
            .map(pendingSync -> synchronize(pendingSync.observation(), pendingSync.world()))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending);
    }

    public CompletableFuture<Void> beginShutdown() {
        acceptingOperations.set(false);
        final CompletableFuture<?>[] pending = synchronizations.values().stream()
            .map(Synchronization::completion)
            .toArray(CompletableFuture[]::new);
        synchronizations.forEach((worldId, synchronization) -> complete(synchronization));
        return CompletableFuture.allOf(pending);
    }

    private void attempt(final Synchronization synchronization, final int attempt) {
        if (!isCurrent(synchronization)) {
            complete(synchronization);
            return;
        }
        final CompletableFuture<WorldManagementService.IdentitySyncResult> result;
        try {
            result = Objects.requireNonNull(
                syncOperation.apply(synchronization.world()), "identity sync result"
            );
        } catch (final RuntimeException failure) {
            retryOrFail(synchronization, attempt, failure);
            return;
        }
        result.whenComplete((outcome, failure) -> {
            if (!isCurrent(synchronization)) {
                complete(synchronization);
                return;
            }
            if (failure == null) {
                complete(synchronization);
                return;
            }
            retryOrFail(synchronization, attempt, failure);
        });
    }

    private void retryOrFail(
        final Synchronization synchronization,
        final int attempt,
        final Throwable failure
    ) {
        if (!isCurrent(synchronization)) {
            complete(synchronization);
            return;
        }
        if (attempt >= RETRY_DELAYS.length) {
            fail(synchronization, failure);
            return;
        }
        dispatcher.executeGlobalLater(
            RETRY_DELAYS[attempt],
            () -> attempt(synchronization, attempt + 1),
            () -> complete(synchronization)
        );
    }

    private boolean isCurrent(final Synchronization synchronization) {
        return acceptingOperations.get()
            && synchronizations.get(synchronization.world().name()) == synchronization
            && loadedWorldCatalog.isCurrent(synchronization.observation())
            && loadedWorldCatalog.findUniqueByWorldId(synchronization.world().name())
                .filter(synchronization.world()::equals)
                .isPresent()
            && metadataService.managedWorld(synchronization.world().name())
                .filter(metadata -> metadata.identityState() == IdentityVerificationState.SYNC_PENDING)
                .filter(metadata -> metadata.pendingIdentity().equals(Optional.of(synchronization.world().identity())))
                .filter(metadata -> metadata.lifecycleCapability() == synchronization.world().lifecycleCapability())
                .isPresent();
    }

    private void complete(final Synchronization synchronization) {
        synchronizations.remove(synchronization.world().name(), synchronization);
        synchronization.completion().complete(null);
    }

    private void fail(final Synchronization synchronization, final Throwable failure) {
        synchronizations.remove(synchronization.world().name(), synchronization);
        synchronization.completion().completeExceptionally(failure);
    }

    private record Synchronization(
        LoadedWorldCatalog.Observation observation,
        WorldRuntimeGateway.LifecycleWorld world,
        CompletableFuture<Void> completion
    ) {
    }

    private record PendingSynchronization(
        LoadedWorldCatalog.Observation observation,
        WorldRuntimeGateway.LifecycleWorld world
    ) {
    }
}