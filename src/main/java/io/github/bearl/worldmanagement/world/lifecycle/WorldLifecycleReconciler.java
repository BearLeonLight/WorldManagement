package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.WorldLoadState;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Replays persisted desired world state without blocking Paper threads. */
public final class WorldLifecycleReconciler {

    private static final Duration[] RETRY_DELAYS = {
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30)
    };

    private final WorldManagementService metadataService;
    private final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> loadOperation;
    private final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> unloadOperation;
    private final WorldThreadDispatcher dispatcher;
    private final Map<String, Reconciliation> reconciliations = new ConcurrentHashMap<>();
    private final AtomicBoolean acceptingOperations = new AtomicBoolean(true);

    public WorldLifecycleReconciler(
        final WorldManagementService metadataService,
        final WorldLifecycleCoordinator lifecycleCoordinator,
        final WorldThreadDispatcher dispatcher
    ) {
        this(
            metadataService,
            Objects.requireNonNull(lifecycleCoordinator, "lifecycleCoordinator")::loadAsync,
            lifecycleCoordinator::unloadAsync,
            dispatcher
        );
    }

    WorldLifecycleReconciler(
        final WorldManagementService metadataService,
        final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> loadOperation,
        final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> unloadOperation,
        final WorldThreadDispatcher dispatcher
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.loadOperation = Objects.requireNonNull(loadOperation, "loadOperation");
        this.unloadOperation = Objects.requireNonNull(unloadOperation, "unloadOperation");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public CompletableFuture<Void> reconcileStartup() {
        return CompletableFuture.allOf(metadataService.managedWorlds().stream()
            .map(WorldMetadata::worldName)
            .map(this::reconcileWorld)
            .toArray(CompletableFuture[]::new));
    }

    public CompletableFuture<Void> reconcileWorld(final String worldName) {
        final String requiredWorldName = Objects.requireNonNull(worldName, "worldName");
        if (!acceptingOperations.get()) {
            return CompletableFuture.completedFuture(null);
        }
        final Reconciliation reconciliation = new Reconciliation(requiredWorldName, new CompletableFuture<>());
        reconciliations.compute(requiredWorldName, (ignored, previous) -> {
            if (previous != null) {
                previous.completion().complete(null);
            }
            return reconciliation;
        });
        if (!acceptingOperations.get()) {
            complete(reconciliation);
            return reconciliation.completion();
        }
        attempt(reconciliation, 0);
        return reconciliation.completion();
    }

    public CompletableFuture<Void> beginShutdown() {
        acceptingOperations.set(false);
        final CompletableFuture<?>[] pending = reconciliations.values().stream()
            .map(Reconciliation::completion)
            .toArray(CompletableFuture[]::new);
        reconciliations.forEach((worldName, reconciliation) -> complete(reconciliation));
        return CompletableFuture.allOf(pending);
    }

    private void attempt(final Reconciliation reconciliation, final int attempt) {
        if (!isCurrent(reconciliation)) {
            return;
        }
        final WorldMetadata metadata = metadataService.managedWorld(reconciliation.worldName()).orElse(null);
        if (!canReconcile(metadata)) {
            complete(reconciliation);
            return;
        }
        final Function<String, CompletableFuture<WorldLifecycleCoordinator.LifecycleResult>> operation =
            metadata.desiredState() == WorldLoadState.LOADED ? loadOperation : unloadOperation;
        final CompletableFuture<WorldLifecycleCoordinator.LifecycleResult> result;
        try {
            result = Objects.requireNonNull(operation.apply(metadata.worldName()), "reconciliation result");
        } catch (final RuntimeException failure) {
            continueOrFail(reconciliation, attempt, null, failure);
            return;
        }
        result.whenComplete((outcome, failure) -> continueOrFail(
            reconciliation, attempt, metadata.desiredState(), failure == null ? outcome : failure
        ));
    }

    private void continueOrFail(
        final Reconciliation reconciliation,
        final int attempt,
        final WorldLoadState attemptedState,
        final Object outcome
    ) {
        if (!isCurrent(reconciliation)) {
            return;
        }
        if (outcome instanceof WorldLifecycleCoordinator.LifecycleResult result
            && isTerminal(attemptedState, result.status())) {
            complete(reconciliation);
            return;
        }
        if (attempt >= RETRY_DELAYS.length) {
            fail(reconciliation, outcome);
            return;
        }
        dispatcher.executeGlobalLater(
            RETRY_DELAYS[attempt],
            () -> attempt(reconciliation, attempt + 1),
            () -> complete(reconciliation)
        );
    }

    private boolean isCurrent(final Reconciliation reconciliation) {
        return acceptingOperations.get()
            && reconciliations.get(reconciliation.worldName()) == reconciliation;
    }

    private void complete(final Reconciliation reconciliation) {
        reconciliations.remove(reconciliation.worldName(), reconciliation);
        reconciliation.completion().complete(null);
    }

    private void fail(final Reconciliation reconciliation, final Object outcome) {
        reconciliations.remove(reconciliation.worldName(), reconciliation);
        final Throwable failure = outcome instanceof Throwable throwable
            ? throwable
            : new IllegalStateException(
                "World reconciliation exhausted retries for " + reconciliation.worldName() + ": " + outcome
            );
        reconciliation.completion().completeExceptionally(failure);
    }

    private static boolean canReconcile(final WorldMetadata metadata) {
        return metadata != null
            && metadata.identityState() == IdentityVerificationState.VERIFIED
            && metadata.lifecycleCapability().permitsManagedLifecycle();
    }

    private static boolean isTerminal(
        final WorldLoadState desiredState,
        final WorldLifecycleCoordinator.LifecycleStatus status
    ) {
        return desiredState == WorldLoadState.LOADED
            ? status == WorldLifecycleCoordinator.LifecycleStatus.LOADED
                || status == WorldLifecycleCoordinator.LifecycleStatus.ALREADY_LOADED
            : status == WorldLifecycleCoordinator.LifecycleStatus.UNLOADED
                || status == WorldLifecycleCoordinator.LifecycleStatus.ALREADY_UNLOADED;
    }

    private record Reconciliation(String worldName, CompletableFuture<Void> completion) {
    }
}