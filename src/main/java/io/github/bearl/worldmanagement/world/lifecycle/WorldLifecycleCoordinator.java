package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldLoadState;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.RequestedWorldType;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

/** Coordinates global-scheduler world operations with asynchronous metadata updates. */
public final class WorldLifecycleCoordinator {

    private static final int MAX_NON_TICKING_ATTEMPTS = 20;
    private static final Duration NON_TICKING_RETRY_DELAY = Duration.ofMillis(50L);

    private final WorldRuntimeGateway gateway;
    private final WorldManagementService metadataService;
    private final boolean defaultRankSystemEnabled;
    private final PluginIoExecutor ioExecutor;
    private final WorldStorageGateway storageGateway;
    private final Duration deletionDelay;
    private final WorldThreadDispatcher threadDispatcher;
    private final Optional<String> fallbackWorld;
    private final Map<String, WorldOperationState> activeOperations = new ConcurrentHashMap<>();
    private final Object operationLock = new Object();
    private final Set<CompletableFuture<?>> pendingOperations = new HashSet<>();
    private final Set<VerifiedWorldRef> worldsLoadedDuringDelete = ConcurrentHashMap.newKeySet();
    private boolean acceptingOperations = true;
    private final WorldNameValidator worldNameValidator = new WorldNameValidator();
    private final DiagnosticLogger diagnostics;

    public WorldLifecycleCoordinator(
        final WorldRuntimeGateway gateway,
        final WorldManagementService metadataService,
        final boolean defaultRankSystemEnabled,
        final PluginIoExecutor ioExecutor,
        final WorldStorageGateway storageGateway,
        final Duration deletionDelay,
        final WorldThreadDispatcher threadDispatcher,
        final Optional<String> fallbackWorld
    ) {
        this(gateway, metadataService, defaultRankSystemEnabled, ioExecutor, storageGateway,
            deletionDelay, threadDispatcher, fallbackWorld, null);
    }

    public WorldLifecycleCoordinator(
        final WorldRuntimeGateway gateway,
        final WorldManagementService metadataService,
        final boolean defaultRankSystemEnabled,
        final PluginIoExecutor ioExecutor,
        final WorldStorageGateway storageGateway,
        final Duration deletionDelay,
        final WorldThreadDispatcher threadDispatcher,
        final Optional<String> fallbackWorld,
        final DiagnosticLogger diagnostics
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.defaultRankSystemEnabled = defaultRankSystemEnabled;
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.storageGateway = Objects.requireNonNull(storageGateway, "storageGateway");
        this.deletionDelay = Objects.requireNonNull(deletionDelay, "deletionDelay");
        this.threadDispatcher = Objects.requireNonNull(threadDispatcher, "threadDispatcher");
        this.fallbackWorld = Objects.requireNonNull(fallbackWorld, "fallbackWorld");
        this.diagnostics = diagnostics;
    }

    public CompletableFuture<CreateResult> create(
        final String worldName,
        final WorldRuntimeGateway.WorldEnvironment environment,
        final WorldRuntimeGateway.WorldType type,
        final Long seed
    ) {
        return create(new WorldCreationRequest(
            worldName,
            environment,
            type,
            seed == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(seed),
            Optional.empty()
        ), null);
    }

    public CompletableFuture<CreateResult> create(
        final String worldName,
        final WorldRuntimeGateway.WorldEnvironment environment,
        final WorldRuntimeGateway.WorldType type,
        final Long seed,
        final AuditEvent event
    ) {
        return create(new WorldCreationRequest(
            worldName,
            environment,
            type,
            seed == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(seed),
            Optional.empty()
        ), event);
    }

    public CompletableFuture<CreateResult> create(final WorldCreationRequest request, final AuditEvent event) {
        final WorldCreationRequest requiredRequest = Objects.requireNonNull(request, "request");
        final String worldName = requiredRequest.worldName();
        return withOperation(worldName, WorldOperationState.CREATING, CreateResult.operationInProgress(), () -> continueOnGlobal(() -> {
            if (gateway.findWorldByPaperKey("minecraft:" + worldName).isPresent()
                || metadataService.managedWorld(worldName).isPresent()) {
                return CompletableFuture.completedFuture(CreateResult.alreadyExists());
            }
            return ioExecutor.submit(() -> storageGateway.prepareCreation(worldName))
                .thenCompose(creationClaim -> continueOnNonTickingGlobal(() -> {
                    if (creationClaim.isEmpty()
                        || gateway.findWorldByPaperKey("minecraft:" + worldName).isPresent()
                        || metadataService.managedWorld(worldName).isPresent()) {
                        return CompletableFuture.completedFuture(CreateResult.alreadyExists());
                    }
                    final WorldRuntimeGateway.LifecycleWorld world;
                    try {
                        world = gateway.create(requiredRequest);
                    } catch (final RuntimeException failure) {
                        return compensateFailedCreate(null, creationClaim.orElseThrow(), failure);
                    }
                    if (world == null) {
                        return compensateUnpersistedCreate(
                            null, creationClaim.orElseThrow(), CreateResult.failed()
                        );
                    }
                    return metadataService.adopt(
                        world.identity(), world.lifecycleCapability(),
                        Optional.of(RequestedWorldType.valueOf(requiredRequest.type().name())),
                        requiredRequest.generator(),
                        defaultRankSystemEnabled, event
                    )
                        .thenCompose(adoption -> switch (adoption.status()) {
                            case ADOPTED -> revalidatePersistedRuntimeIdentity(world)
                                .thenApply(verified -> verified ? CreateResult.created() : CreateResult.failed());
                            case ALREADY_MANAGED -> CompletableFuture.completedFuture(CreateResult.alreadyExists());
                            case NOT_READY -> compensateUnpersistedCreate(
                                world, creationClaim.orElseThrow(), CreateResult.notReady()
                            );
                        })
                        .exceptionallyCompose(failure -> compensateFailedCreate(
                            world, creationClaim.orElseThrow(), failure
                        ));
                }));
                }));
    }

    public CompletableFuture<AdoptResult> adoptLoadedWorld(
        final String worldName,
        final AuditEvent event
    ) {
        return withOperation(
            worldName,
            WorldOperationState.ADOPTING,
            AdoptResult.operationInProgress(),
            () -> continueOnGlobal(() -> {
                if (metadataService.metadataWorld(worldName).isPresent()) {
                    return CompletableFuture.completedFuture(AdoptResult.alreadyManaged());
                }
                final WorldRuntimeGateway.LifecycleWorld observed = gateway.findLoadedWorldById(worldName)
                    .orElse(null);
                if (observed == null) {
                    return CompletableFuture.completedFuture(AdoptResult.notLoaded());
                }
                return metadataService.adopt(
                    observed.identity(), observed.lifecycleCapability(), Optional.empty(),
                    defaultRankSystemEnabled, event
                ).thenCompose(adoption -> switch (adoption.status()) {
                    case ADOPTED -> revalidatePersistedRuntimeIdentity(observed)
                        .thenApply(verified -> verified ? AdoptResult.adopted() : AdoptResult.failed());
                    case ALREADY_MANAGED -> CompletableFuture.completedFuture(AdoptResult.alreadyManaged());
                    case NOT_READY -> CompletableFuture.completedFuture(AdoptResult.notReady());
                });
            })
        );
    }

    private CompletableFuture<CreateResult> compensateFailedCreate(
        final WorldRuntimeGateway.LifecycleWorld world,
        final WorldStorageGateway.CreationClaim creationClaim,
        final Throwable failure
    ) {
        return continueOnNonTickingGlobal(() -> {
            if (world == null) {
                return CompletableFuture.failedFuture(failure);
            }
            if (world != null && !gateway.unload(world, false)) {
                failure.addSuppressed(new IllegalStateException(
                    "Could not unload created world after metadata persistence failed: " + world.name()
                ));
                return CompletableFuture.failedFuture(failure);
            }
            return ioExecutor.execute(() -> storageGateway.deleteCreated(creationClaim))
                .handle((unused, cleanupFailure) -> {
                    if (cleanupFailure != null) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw new java.util.concurrent.CompletionException(failure);
                });
        });
    }

    private CompletableFuture<CreateResult> compensateUnpersistedCreate(
        final WorldRuntimeGateway.LifecycleWorld world,
        final WorldStorageGateway.CreationClaim creationClaim,
        final CreateResult result
    ) {
        return continueOnNonTickingGlobal(() -> {
            if (world == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (world != null && !gateway.unload(world, false)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Could not unload created world without persisted metadata: " + world.name()
                ));
            }
            return ioExecutor.execute(() -> storageGateway.deleteCreated(creationClaim)).thenApply(unused -> result);
        });
    }

    public CompletableFuture<RemoveResult> remove(final String worldName) {
        return remove(worldName, null);
    }

    public CompletableFuture<RemoveResult> remove(final String worldName, final AuditEvent event) {
        return withOperation(worldName, WorldOperationState.REMOVING, RemoveResult.operationInProgress(), () -> {
            final var metadata = metadataService.metadataWorld(worldName);
            if (metadata.isEmpty() || metadata.get().managementState() != WorldManagementState.ACTIVE) {
                return CompletableFuture.completedFuture(RemoveResult.notManaged());
            }
            final VerifiedWorldRef expected = VerifiedWorldRef.from(metadata.orElseThrow()).orElse(null);
            if (metadata.get().desiredState() != WorldLoadState.UNLOADED
                || expected == null
                || gateway.findWorld(expected).isPresent()) {
                return CompletableFuture.completedFuture(RemoveResult.notUnloaded());
            }
            return metadataService.remove(worldName, event).thenApply(removal -> switch (removal.status()) {
                case DETACHED, ALREADY_DETACHED -> RemoveResult.detached();
                case NOT_MANAGED -> RemoveResult.notManaged();
                case NOT_READY -> RemoveResult.notReady();
                case PURGED -> RemoveResult.detached();
            });
        });
    }

    public CompletableFuture<LifecycleResult> loadAsync(final String worldName) {
        return loadAsync(worldName, null);
    }

    public CompletableFuture<LifecycleResult> loadAsync(final String worldName, final AuditEvent event) {
        return withOperation(worldName, WorldOperationState.LOADING, LifecycleResult.operationInProgress(), () -> {
            final var metadata = metadataService.managedWorld(worldName);
            if (metadata.isEmpty()) {
                return CompletableFuture.completedFuture(LifecycleResult.notManaged());
            }
            if (!metadata.orElseThrow().lifecycleCapability().permitsManagedLifecycle()) {
                return CompletableFuture.completedFuture(LifecycleResult.externalOnly());
            }
            return ioExecutor.submit(() -> storageGateway.prepareLoad(metadata.orElseThrow())).thenCompose(loadClaim -> {
                if (loadClaim.isEmpty()) {
                    return CompletableFuture.completedFuture(LifecycleResult.storageNotFound());
                }
                final WorldStorageGateway.LoadClaim claim = loadClaim.orElseThrow();
                final var current = metadataService.managedWorld(worldName);
                if (current.isEmpty()
                    || current.get().version() != claim.metadataVersion()
                    || !current.get().identity().paperKey().equals(claim.world().paperKey())
                    || !current.get().identity().worldUuid().equals(claim.world().worldUuid())) {
                    return CompletableFuture.completedFuture(LifecycleResult.failed());
                }
                return ioExecutor.execute(() -> storageGateway.validateLoadClaim(claim))
                    .thenCompose(unused -> continueOnNonTickingGlobal(() -> {
                            final WorldRuntimeGateway.LoadResult loadResult = gateway.load(
                                claim,
                                WorldRuntimeGateway.WorldEnvironment.valueOf(current.get().identity().environment().name()),
                                current.get().generator()
                            );
                            if (loadResult.world().isEmpty()) {
                                return CompletableFuture.completedFuture(LifecycleResult.failed());
                            }
                            final WorldRuntimeGateway.LifecycleWorld observed = loadResult.world().orElseThrow();
                            return metadataService.classifyLoadedIdentity(
                                observed.identity(), observed.lifecycleCapability()
                            ).thenCompose(classification -> {
                                final boolean verified = classification.status()
                                    == WorldManagementService.UpdateStatus.UPDATED
                                    && classification.metadata() != null
                                    && classification.metadata().identityState()
                                    == io.github.bearl.worldmanagement.world.IdentityVerificationState.VERIFIED;
                                if (verified && claim.world().equals(observed.reference())) {
                                    return metadataService.ensureDesiredState(
                                        worldName, WorldLoadState.LOADED, event
                                    ).thenApply(update -> {
                                        if (update.status() == WorldManagementService.UpdateStatus.UPDATED) {
                                            return loadResult.newlyLoaded()
                                                ? LifecycleResult.loaded() : LifecycleResult.alreadyLoaded();
                                        }
                                        return update.status() == WorldManagementService.UpdateStatus.NOT_READY
                                            ? LifecycleResult.notReady() : LifecycleResult.notManaged();
                                    });
                                }
                                return compensateRejectedLoad(observed, loadResult.newlyLoaded());
                            });
                    }));
            });
        });
    }

    private CompletableFuture<LifecycleResult> compensateRejectedLoad(
        final WorldRuntimeGateway.LifecycleWorld observed,
        final boolean newlyLoaded
    ) {
        if (!newlyLoaded) {
            return CompletableFuture.completedFuture(LifecycleResult.failed());
        }
        return continueOnNonTickingGlobal(() -> CompletableFuture.completedFuture(
            gateway.unload(observed, false)
                ? LifecycleResult.failed()
                : LifecycleResult.unloadFailed()
        ));
    }

    public CompletableFuture<LifecycleResult> unloadAsync(final String worldName) {
        return unloadAsync(worldName, Optional.empty(), null);
    }

    public CompletableFuture<LifecycleResult> unloadAsync(final String worldName, final AuditEvent event) {
        return unloadAsync(worldName, Optional.empty(), event);
    }

    public CompletableFuture<LifecycleResult> unloadAsync(
        final String worldName,
        final Optional<String> requestedFallback,
        final AuditEvent event
    ) {
        Objects.requireNonNull(requestedFallback, "requestedFallback");
        return withOperation(worldName, WorldOperationState.UNLOADING, LifecycleResult.operationInProgress(), () -> {
            final var metadata = metadataService.managedWorld(worldName);
            if (metadata.isEmpty()) {
                return CompletableFuture.completedFuture(LifecycleResult.notManaged());
            }
            if (!metadata.orElseThrow().lifecycleCapability().permitsManagedLifecycle()) {
                return CompletableFuture.completedFuture(LifecycleResult.externalOnly());
            }
            final VerifiedWorldRef expected = VerifiedWorldRef.from(metadata.orElseThrow()).orElse(null);
            if (expected == null) {
                return CompletableFuture.completedFuture(LifecycleResult.failed());
            }
            return continueOnNonTickingGlobal(() -> {
                final WorldRuntimeGateway.LifecycleWorld observed = gateway.findWorld(expected).orElse(null);
                if (observed == null) {
                    return persistUnloadIntent(worldName, event, LifecycleResult.alreadyUnloaded());
                }
                if (!expected.equals(observed.reference())) {
                    return classifyRejectedRuntimeIdentity(observed);
                }
                if (gateway.playerCount(observed) > 0) {
                    final WorldRuntimeGateway.LifecycleWorld target = resolveFallback(
                        observed, requestedFallback
                    ).orElse(null);
                    if (target == null) {
                        return CompletableFuture.completedFuture(LifecycleResult.fallbackUnavailable());
                    }
                    return gateway.teleportPlayersToWorld(observed, target)
                        .thenCompose(teleported -> continueUnloadAfterTeleport(observed, teleported, event));
                }
                if (!saveAndUnload(observed)) {
                    return CompletableFuture.completedFuture(LifecycleResult.unloadFailed());
                }
                return persistUnloadIntent(worldName, event, LifecycleResult.unloaded());
            });
        });
    }

    private CompletableFuture<LifecycleResult> persistUnloadIntent(
        final String worldName,
        final AuditEvent event,
        final LifecycleResult success
    ) {
        return metadataService.ensureDesiredState(worldName, WorldLoadState.UNLOADED, event)
            .thenApply(update -> {
                if (update.status() == WorldManagementService.UpdateStatus.UPDATED) {
                    return success;
                }
                return update.status() == WorldManagementService.UpdateStatus.NOT_READY
                    ? LifecycleResult.notReady() : LifecycleResult.notManaged();
            });
    }

    private CompletableFuture<LifecycleResult> classifyRejectedRuntimeIdentity(
        final WorldRuntimeGateway.LifecycleWorld observed
    ) {
        return metadataService.classifyLoadedIdentity(observed.identity(), observed.lifecycleCapability())
            .thenApply(unused -> LifecycleResult.unloadFailed());
    }

    private CompletableFuture<LifecycleResult> continueUnloadAfterTeleport(
        final WorldRuntimeGateway.LifecycleWorld source,
        final boolean teleported,
        final AuditEvent event
    ) {
        return continueOnNonTickingGlobal(() -> {
            final WorldRuntimeGateway.LifecycleWorld observed = gateway.findWorld(source.reference()).orElse(null);
            if (observed == null || !source.reference().equals(observed.reference())) {
                return CompletableFuture.completedFuture(LifecycleResult.unloadFailed());
            }
            if (!teleported || gateway.playerCount(source) > 0) {
                return CompletableFuture.completedFuture(LifecycleResult.playersPresent());
            }
            if (!saveAndUnload(source)) {
                return CompletableFuture.completedFuture(LifecycleResult.unloadFailed());
            }
            return persistUnloadIntent(source.name(), event, LifecycleResult.unloaded());
        }, () -> CompletableFuture.completedFuture(LifecycleResult.playersPresent()));
    }

    public CompletableFuture<CreateResult> importWorld(
        final String worldName,
        final WorldRuntimeGateway.WorldEnvironment environment
    ) {
        return importWorld(worldName, environment, null);
    }

    public CompletableFuture<CreateResult> importWorld(
        final String worldName,
        final WorldRuntimeGateway.WorldEnvironment environment,
        final AuditEvent event
    ) {
        Objects.requireNonNull(environment, "environment");
        return withOperation(worldName, WorldOperationState.IMPORTING, CreateResult.operationInProgress(), () -> continueOnNonTickingGlobal(() -> {
            if (gateway.findWorldByPaperKey("minecraft:" + worldName).isPresent()
                || metadataService.managedWorld(worldName).isPresent()) {
                return CompletableFuture.completedFuture(CreateResult.alreadyExists());
            }
            final WorldRuntimeGateway.LoadResult loadResult;
            try {
                loadResult = gateway.loadUnmanaged(worldName, environment);
            } catch (final RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (loadResult.world().isEmpty()) {
                return CompletableFuture.completedFuture(CreateResult.failed());
            }
            final WorldRuntimeGateway.LifecycleWorld world = loadResult.world().orElseThrow();
            return metadataService.adopt(
                world.identity(), world.lifecycleCapability(), Optional.empty(),
                defaultRankSystemEnabled, event
            )
                .thenCompose(adoption -> switch (adoption.status()) {
                    case ADOPTED -> revalidatePersistedRuntimeIdentity(world)
                        .thenApply(verified -> verified ? CreateResult.created() : CreateResult.failed());
                    case ALREADY_MANAGED -> CompletableFuture.completedFuture(CreateResult.alreadyExists());
                    case NOT_READY -> unloadUnpersistedImport(world, CreateResult.notReady());
                })
                .exceptionallyCompose(failure -> unloadAfterMetadataFailure(world, failure));
            }));
    }

    private CompletableFuture<Boolean> revalidatePersistedRuntimeIdentity(
        final WorldRuntimeGateway.LifecycleWorld expected
    ) {
        return continueOnGlobal(() -> {
            final WorldRuntimeGateway.LifecycleWorld observed = gateway.findWorld(expected.reference()).orElse(null);
            if (observed == null) {
                return CompletableFuture.completedFuture(false);
            }
            return metadataService.classifyLoadedIdentity(
                observed.identity(), observed.lifecycleCapability()
            ).thenApply(classification -> classification.status() == WorldManagementService.UpdateStatus.UPDATED
                && classification.metadata() != null
                && classification.metadata().identityState()
                    == io.github.bearl.worldmanagement.world.IdentityVerificationState.VERIFIED
                && expected.reference().equals(observed.reference())
            );
        });
    }

    private CompletableFuture<CreateResult> unloadUnpersistedImport(
        final WorldRuntimeGateway.LifecycleWorld world,
        final CreateResult result
    ) {
        return continueOnNonTickingGlobal(() -> {
            if (!gateway.unload(world, false)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Could not unload imported world without persisted metadata: " + world.name()
                ));
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private <T> CompletableFuture<T> unloadAfterMetadataFailure(
        final WorldRuntimeGateway.LifecycleWorld world,
        final Throwable failure
    ) {
        return continueOnNonTickingGlobal(() -> {
            if (!gateway.unload(world, false)) {
                failure.addSuppressed(new IllegalStateException(
                    "Could not unload world after metadata persistence failed: " + world.name()
                ));
            }
            return CompletableFuture.failedFuture(failure);
        });
    }

    public CompletableFuture<Boolean> validateImportable(final String worldName) {
        return ioExecutor.submit(() -> storageGateway.isImportable(worldName));
    }

    public CompletableFuture<DeleteResult> delete(final String worldName) {
        return delete(worldName, Optional.empty(), null);
    }

    public CompletableFuture<DeleteResult> delete(final String worldName, final AuditEvent event) {
        return delete(worldName, Optional.empty(), event);
    }

    public CompletableFuture<DeleteResult> delete(
        final String worldName,
        final Optional<String> requestedFallback,
        final AuditEvent event
    ) {
        Objects.requireNonNull(requestedFallback, "requestedFallback");
        return withOperation(worldName, WorldOperationState.DELETING, DeleteResult.operationInProgress(), () -> {
            final WorldMetadata metadata = metadataService.managedWorld(worldName).orElse(null);
            if (metadata == null) {
                return CompletableFuture.completedFuture(DeleteResult.notManaged());
            }
            if (!metadata.lifecycleCapability().permitsManagedLifecycle()) {
                return CompletableFuture.completedFuture(DeleteResult.externalOnly());
            }
            final VerifiedWorldRef expected = VerifiedWorldRef.from(metadata).orElse(null);
            if (expected == null) {
                return CompletableFuture.completedFuture(DeleteResult.unloadFailed());
            }
            return ioExecutor.submit(() -> storageGateway.exists(worldName)).thenCompose(exists -> continueOnGlobal(() -> {
                if (!exists) {
                    return CompletableFuture.completedFuture(DeleteResult.storageNotFound());
                }
                final WorldRuntimeGateway.LifecycleWorld source = gateway.findWorld(expected).orElse(null);
                if (source == null) {
                    return deleteUnloadedWorld(worldName, event);
                }
                if (!expected.equals(source.reference())) {
                    return metadataService.classifyLoadedIdentity(source.identity(), source.lifecycleCapability())
                        .thenApply(unused -> DeleteResult.unloadFailed());
                }
                final WorldRuntimeGateway.LifecycleWorld target = resolveFallback(
                    source, requestedFallback
                ).orElse(null);
                if (target == null && gateway.playerCount(source) > 0) {
                    return CompletableFuture.completedFuture(DeleteResult.fallbackUnavailable());
                }
                if (gateway.playerCount(source) > 0) {
                    return gateway.teleportPlayersToWorld(source, target)
                    .thenCompose(teleported -> continueDeleteAfterTeleport(source, teleported, event));
                }
                return continueOnNonTickingGlobal(() -> deleteLoadedWorld(source, event));
            }));
        });
    }

    /** Records a runtime load event so an in-flight delete can compensate before permanent removal. */
    public void worldLoaded(final WorldRuntimeGateway.LifecycleWorld world) {
        final WorldRuntimeGateway.LifecycleWorld observed = Objects.requireNonNull(world, "world");
        if (activeOperations.get(observed.name()) == WorldOperationState.DELETING) {
            worldsLoadedDuringDelete.add(observed.reference());
        }
    }

    private CompletableFuture<DeleteResult> continueDeleteAfterTeleport(
        final WorldRuntimeGateway.LifecycleWorld source,
        final boolean teleported,
        final AuditEvent event
    ) {
        return continueOnNonTickingGlobal(() -> {
            final WorldRuntimeGateway.LifecycleWorld observed = gateway.findWorld(source.reference()).orElse(null);
            if (observed == null || !source.reference().equals(observed.reference())) {
                return CompletableFuture.completedFuture(DeleteResult.unloadFailed());
            }
            if (!teleported || gateway.playerCount(source) > 0) {
                return CompletableFuture.completedFuture(DeleteResult.playersPresent());
            }
            return deleteLoadedWorld(source, event);
        }, () -> CompletableFuture.completedFuture(DeleteResult.playersPresent()));
    }

    private CompletableFuture<DeleteResult> deleteLoadedWorld(
        final WorldRuntimeGateway.LifecycleWorld source,
        final AuditEvent event
    ) {
        if (!saveAndUnload(source)) {
            return CompletableFuture.completedFuture(DeleteResult.unloadFailed());
        }
        return metadataService.ensureDesiredState(source.name(), WorldLoadState.UNLOADED, event)
            .thenCompose(update -> switch (update.status()) {
                case UPDATED -> CompletableFuture.completedFuture(DeleteResult.unloadedRequiresConfirmation());
                case NOT_MANAGED -> reloadAfterMetadataRejection(source, DeleteResult.notManaged());
                case NOT_READY -> reloadAfterMetadataRejection(source, DeleteResult.metadataRemovalFailed());
            })
            .exceptionallyCompose(failure -> reloadAfterMetadataFailure(source, failure));
    }

    private CompletableFuture<DeleteResult> reloadAfterMetadataRejection(
        final WorldRuntimeGateway.LifecycleWorld source,
        final DeleteResult result
    ) {
        return reloadAcceptedWorld(source).thenApply(unused -> result);
    }

    private <T> CompletableFuture<T> reloadAfterMetadataFailure(
        final WorldRuntimeGateway.LifecycleWorld source,
        final Throwable failure
    ) {
        return reloadAcceptedWorld(source).handle((unused, reloadFailure) -> {
            if (reloadFailure != null) {
                failure.addSuppressed(reloadFailure);
            }
            throw new java.util.concurrent.CompletionException(failure);
        });
    }

    private CompletableFuture<Void> reloadAcceptedWorld(final WorldRuntimeGateway.LifecycleWorld source) {
        final WorldMetadata metadata = metadataService.metadataWorld(source.name()).orElse(null);
        if (metadata == null || !VerifiedWorldRef.from(metadata).equals(Optional.of(source.reference()))) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Could not establish accepted metadata for compensating world reload: " + source.name()
            ));
        }
        return ioExecutor.submit(() -> storageGateway.prepareLoad(metadata)).thenCompose(loadClaim -> {
            if (loadClaim.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Could not establish storage claim for compensating world reload: " + source.name()
                ));
            }
            final WorldStorageGateway.LoadClaim claim = loadClaim.orElseThrow();
            return ioExecutor.execute(() -> storageGateway.validateLoadClaim(claim))
                .thenCompose(unused -> continueOnNonTickingGlobal(() -> {
                    final WorldRuntimeGateway.LoadResult loadResult = gateway.load(
                        claim,
                        WorldRuntimeGateway.WorldEnvironment.valueOf(metadata.identity().environment().name()),
                        metadata.generator()
                    );
                    if (loadResult.world().isEmpty()) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                            "Could not reload world after metadata update failed: " + source.name()
                        ));
                    }
                    final WorldRuntimeGateway.LifecycleWorld observed = loadResult.world().orElseThrow();
                    return metadataService.classifyLoadedIdentity(
                        observed.identity(), observed.lifecycleCapability()
                    ).thenCompose(classification -> {
                        final boolean verified = classification.status()
                            == WorldManagementService.UpdateStatus.UPDATED
                            && classification.metadata() != null
                            && classification.metadata().identityState()
                            == io.github.bearl.worldmanagement.world.IdentityVerificationState.VERIFIED;
                        if (verified && source.reference().equals(observed.reference())) {
                            return CompletableFuture.completedFuture(null);
                        }
                        final CompletableFuture<Void> rejected = CompletableFuture.failedFuture(
                            new IllegalStateException(
                                "Compensating world reload returned an unexpected identity: " + source.name()
                            )
                        );
                        if (!loadResult.newlyLoaded()) {
                            return rejected;
                        }
                        return continueOnNonTickingGlobal(() -> {
                            if (!gateway.unload(observed, false)) {
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Could not unload unexpected compensating world reload: " + source.name()
                                ));
                            }
                            return rejected;
                        });
                    });
                }));
        });
    }

    private boolean saveAndUnload(final WorldRuntimeGateway.LifecycleWorld world) {
        try {
            return gateway.unload(world, true) && gateway.findWorld(world.reference()).isEmpty();
        } catch (final RuntimeException exception) {
            return false;
        }
    }

    private CompletableFuture<DeleteResult> deleteUnloadedWorld(final String worldName, final AuditEvent event) {
        return deleteStorageAndMetadata(worldName, event);
    }

    private CompletableFuture<DeleteResult> deleteStorageAndMetadata(final String worldName, final AuditEvent event) {
        final CompletableFuture<DeleteResult> result = new CompletableFuture<>();
        threadDispatcher.executeGlobalLater(deletionDelay, () -> {
            final WorldMetadata metadata = metadataService.managedWorld(worldName).orElse(null);
            if (metadata == null) {
                result.complete(DeleteResult.notManaged());
                return;
            }
            final VerifiedWorldRef expected = VerifiedWorldRef.from(metadata).orElse(null);
            if (expected == null || gateway.findWorld(expected).isPresent()) {
                result.complete(DeleteResult.reloaded());
                return;
            }
            ioExecutor.submit(() -> storageGateway.quarantine(metadata))
                .thenCompose(quarantined -> continueOnGlobal(
                    () -> continueDeleteAfterQuarantine(worldName, event, quarantined),
                    () -> ioExecutor.execute(() -> storageGateway.restore(quarantined))
                        .thenCompose(unused -> CompletableFuture.failedFuture(
                            new IllegalStateException("World delete was cancelled during shutdown.")))
                ))
                .whenComplete((deleteResult, failure) -> {
                    if (failure == null) {
                        result.complete(deleteResult);
                    } else {
                        result.completeExceptionally(failure);
                    }
                });
                }, () -> result.completeExceptionally(new IllegalStateException("World delete was cancelled during shutdown.")));
        return result;
    }

    private CompletableFuture<DeleteResult> continueDeleteAfterQuarantine(
        final String worldName,
        final AuditEvent event,
        final WorldStorageGateway.QuarantinedWorld quarantined
    ) {
        if (gateway.findWorld(quarantined.world()).isPresent()) {
            return restoreQuarantined(quarantined, DeleteResult.reloaded());
        }
        final WorldMetadata current = metadataService.managedWorld(worldName).orElse(null);
        if (current == null
            || current.version() != quarantined.metadataVersion()
            || !VerifiedWorldRef.from(current).equals(Optional.of(quarantined.world()))) {
            return restoreQuarantined(quarantined, DeleteResult.metadataRemovalFailed());
        }
        return metadataService.markDeleting(worldName, event)
            .thenCompose(update -> continueOnGlobal(() -> {
                if (update.status() != WorldManagementService.UpdateStatus.UPDATED) {
                    return restoreQuarantined(quarantined, DeleteResult.metadataRemovalFailed());
                }
                final boolean observedLoad = worldsLoadedDuringDelete.removeIf(
                    world -> world.worldId().equals(worldName)
                );
                if (observedLoad || gateway.findWorld(quarantined.world()).isPresent()) {
                    return metadataService.cancelDeleting(worldName, null)
                        .thenCompose(cancelled -> restoreQuarantined(quarantined, DeleteResult.reloaded()));
                }
                return ioExecutor.execute(() -> storageGateway.delete(quarantined))
                    .thenCompose(unused -> metadataService.purge(worldName, null))
                    .thenApply(removal -> removal.status() == WorldManagementService.RemoveStatus.PURGED
                        ? DeleteResult.deleted()
                        : DeleteResult.pendingRestart())
                    .exceptionally(failure -> DeleteResult.pendingRestart());
            }))
            .exceptionallyCompose(failure -> {
            final boolean deletionStarted = metadataService.metadataWorld(worldName)
                .map(metadata -> metadata.managementState() == WorldManagementState.DELETING)
                .orElse(false);
            if (deletionStarted) {
                return CompletableFuture.failedFuture(failure);
            }
            return ioExecutor.execute(() -> storageGateway.restore(quarantined))
                .handle((unused, restoreFailure) -> {
                    if (restoreFailure != null) {
                        failure.addSuppressed(restoreFailure);
                    }
                    throw new java.util.concurrent.CompletionException(failure);
                });
        });
    }

    private CompletableFuture<DeleteResult> restoreQuarantined(
        final WorldStorageGateway.QuarantinedWorld quarantined,
        final DeleteResult result
    ) {
        return ioExecutor.execute(() -> storageGateway.restore(quarantined)).thenApply(unused -> result);
    }

    private Optional<WorldRuntimeGateway.LifecycleWorld> resolveFallback(
        final WorldRuntimeGateway.LifecycleWorld source,
        final Optional<String> requestedFallback
    ) {
        return requestedFallback
            .flatMap(worldId -> resolveManagedRuntimeWorld(worldId, source.reference()))
            .or(() -> fallbackWorld.flatMap(
                worldId -> resolveManagedRuntimeWorld(worldId, source.reference())
            ))
            .or(() -> gateway.primaryWorld().flatMap(primary ->
                resolveManagedRuntimeWorld(primary.name(), source.reference())
                    .filter(resolved -> resolved.reference().equals(primary.reference()))
            ));
    }

    private Optional<WorldRuntimeGateway.LifecycleWorld> resolveManagedRuntimeWorld(
        final String worldId,
        final VerifiedWorldRef source
    ) {
        if (source.worldId().equals(worldId)) {
            return Optional.empty();
        }
        final WorldMetadata metadata = metadataService.managedWorld(worldId).orElse(null);
        if (metadata == null
            || metadata.lifecycleCapability() != io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED) {
            return Optional.empty();
        }
        final VerifiedWorldRef expected = VerifiedWorldRef.from(metadata).orElse(null);
        if (expected == null || expected.equals(source)) {
            return Optional.empty();
        }
        return gateway.findWorld(expected).filter(observed -> expected.equals(observed.reference()));
    }

    private <T> CompletableFuture<T> continueOnGlobal(final java.util.function.Supplier<CompletableFuture<T>> operation) {
        return continueOnGlobal(operation, () -> CompletableFuture.failedFuture(
            new IllegalStateException("World operation was cancelled during shutdown.")));
    }

    private <T> CompletableFuture<T> continueOnGlobal(
        final java.util.function.Supplier<CompletableFuture<T>> operation,
        final java.util.function.Supplier<CompletableFuture<T>> cancelledOperation
    ) {
        final CompletableFuture<T> completion = new CompletableFuture<>();
        threadDispatcher.executeGlobal(() -> {
            try {
                operation.get().whenComplete((result, failure) -> {
                    if (failure == null) {
                        completion.complete(result);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
            } catch (final RuntimeException exception) {
                completion.completeExceptionally(exception);
            }
        }, () -> {
            try {
                cancelledOperation.get().whenComplete((result, failure) -> {
                    if (failure == null) {
                        completion.complete(result);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
            } catch (final RuntimeException exception) {
                completion.completeExceptionally(exception);
            }
        });
        return completion;
    }

    private <T> CompletableFuture<T> continueOnNonTickingGlobal(
        final java.util.function.Supplier<CompletableFuture<T>> operation
    ) {
        return continueOnNonTickingGlobal(operation, () -> CompletableFuture.failedFuture(
            new IllegalStateException("World operation was cancelled during shutdown.")));
    }

    private <T> CompletableFuture<T> continueOnNonTickingGlobal(
        final java.util.function.Supplier<CompletableFuture<T>> operation,
        final java.util.function.Supplier<CompletableFuture<T>> cancelledOperation
    ) {
        final CompletableFuture<T> completion = new CompletableFuture<>();
        scheduleNonTickingAttempt(operation, cancelledOperation, completion, 1);
        return completion;
    }

    private <T> void scheduleNonTickingAttempt(
        final java.util.function.Supplier<CompletableFuture<T>> operation,
        final java.util.function.Supplier<CompletableFuture<T>> cancelledOperation,
        final CompletableFuture<T> completion,
        final int attempt
    ) {
        final Runnable execute = () -> {
            if (completion.isDone()) {
                return;
            }
            try {
                if (!gateway.canMutateWorldsNow()) {
                    if (attempt >= MAX_NON_TICKING_ATTEMPTS) {
                        completion.completeExceptionally(new IllegalStateException(
                            "World operation could not run while worlds were ticking."
                        ));
                        return;
                    }
                    threadDispatcher.executeGlobalLater(
                        NON_TICKING_RETRY_DELAY,
                        () -> runNonTickingAttempt(operation, cancelledOperation, completion, attempt + 1),
                        () -> completeFrom(cancelledOperation, completion)
                    );
                    return;
                }
                operation.get().whenComplete((result, failure) -> {
                    if (failure == null) {
                        completion.complete(result);
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
            } catch (final RuntimeException exception) {
                completion.completeExceptionally(exception);
            }
        };
        if (attempt == 1) {
            threadDispatcher.executeGlobal(
                execute,
                () -> completeFrom(cancelledOperation, completion)
            );
        } else {
            execute.run();
        }
    }

    private <T> void runNonTickingAttempt(
        final java.util.function.Supplier<CompletableFuture<T>> operation,
        final java.util.function.Supplier<CompletableFuture<T>> cancelledOperation,
        final CompletableFuture<T> completion,
        final int attempt
    ) {
        scheduleNonTickingAttempt(operation, cancelledOperation, completion, attempt);
    }

    private static <T> void completeFrom(
        final java.util.function.Supplier<CompletableFuture<T>> source,
        final CompletableFuture<T> completion
    ) {
        try {
            source.get().whenComplete((result, failure) -> {
                if (failure == null) {
                    completion.complete(result);
                } else {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (final RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
    }

    private <T> CompletableFuture<T> withOperation(
        final String worldName,
        final WorldOperationState state,
        final T operationInProgress,
        final java.util.function.Supplier<CompletableFuture<T>> operation
    ) {
        final long startedAt = System.nanoTime();
        final CompletableFuture<T> trackedOperation = new CompletableFuture<>();
        synchronized (operationLock) {
            if (!acceptingOperations || activeOperations.putIfAbsent(worldName, state) != null) {
                logOperation(worldName, state, "operation_in_progress", startedAt, null);
                return CompletableFuture.completedFuture(operationInProgress);
            }
            pendingOperations.add(trackedOperation);
        }
        try {
            operation.get().whenComplete((result, failure) -> {
                activeOperations.remove(worldName, state);
                if (failure == null) {
                    trackedOperation.complete(result);
                } else {
                    trackedOperation.completeExceptionally(failure);
                }
                synchronized (operationLock) {
                    pendingOperations.remove(trackedOperation);
                }
                logOperation(worldName, state, result == null ? "unknown" : result.toString(), startedAt, failure);
            });
        } catch (final RuntimeException exception) {
            activeOperations.remove(worldName, state);
            trackedOperation.completeExceptionally(exception);
            synchronized (operationLock) {
                pendingOperations.remove(trackedOperation);
            }
            logOperation(worldName, state, "unknown", startedAt, exception);
        }
        return trackedOperation;
    }

    public CompletableFuture<Void> beginShutdown() {
        final CompletableFuture<Void> drain;
        synchronized (operationLock) {
            acceptingOperations = false;
            drain = CompletableFuture.allOf(pendingOperations.toArray(CompletableFuture[]::new));
        }
        gateway.cancelPendingOperations();
        return drain;
    }

    private void logOperation(
        final String worldName,
        final WorldOperationState state,
        final String outcome,
        final long startedAt,
        final Throwable failure
    ) {
        if (diagnostics == null) {
            return;
        }
        final var fields = (java.util.function.Supplier<Map<String, String>>) () -> Map.of(
            "world", worldName,
            "operation", state.name().toLowerCase(java.util.Locale.ROOT),
            "outcome", outcome,
            "durationMs", Long.toString(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
        );
        if (failure == null) {
            diagnostics.basic(DebugArea.LIFECYCLE, "world_operation_completed", fields);
        } else {
            diagnostics.failure(DebugArea.LIFECYCLE, "world_operation_failed", fields, failure);
        }
    }

    public enum CreateStatus {
        CREATED,
        ALREADY_EXISTS,
        FAILED,
        NOT_READY,
        OPERATION_IN_PROGRESS
    }

    public enum AdoptStatus {
        ADOPTED,
        ALREADY_MANAGED,
        NOT_LOADED,
        FAILED,
        NOT_READY,
        OPERATION_IN_PROGRESS
    }

    public record AdoptResult(AdoptStatus status) {
        private static AdoptResult adopted() { return new AdoptResult(AdoptStatus.ADOPTED); }
        private static AdoptResult alreadyManaged() { return new AdoptResult(AdoptStatus.ALREADY_MANAGED); }
        private static AdoptResult notLoaded() { return new AdoptResult(AdoptStatus.NOT_LOADED); }
        private static AdoptResult failed() { return new AdoptResult(AdoptStatus.FAILED); }
        private static AdoptResult notReady() { return new AdoptResult(AdoptStatus.NOT_READY); }
        private static AdoptResult operationInProgress() {
            return new AdoptResult(AdoptStatus.OPERATION_IN_PROGRESS);
        }
    }

    public record CreateResult(CreateStatus status) {
        private static CreateResult created() { return new CreateResult(CreateStatus.CREATED); }
        private static CreateResult alreadyExists() { return new CreateResult(CreateStatus.ALREADY_EXISTS); }
        private static CreateResult failed() { return new CreateResult(CreateStatus.FAILED); }
        private static CreateResult notReady() { return new CreateResult(CreateStatus.NOT_READY); }
        private static CreateResult operationInProgress() { return new CreateResult(CreateStatus.OPERATION_IN_PROGRESS); }
    }

    public enum RemoveStatus {
        DETACHED,
        NOT_MANAGED,
        NOT_UNLOADED,
        NOT_READY,
        OPERATION_IN_PROGRESS
    }

    public record RemoveResult(RemoveStatus status) {
        private static RemoveResult detached() { return new RemoveResult(RemoveStatus.DETACHED); }
        private static RemoveResult notManaged() { return new RemoveResult(RemoveStatus.NOT_MANAGED); }
        private static RemoveResult notUnloaded() { return new RemoveResult(RemoveStatus.NOT_UNLOADED); }
        private static RemoveResult notReady() { return new RemoveResult(RemoveStatus.NOT_READY); }
        private static RemoveResult operationInProgress() { return new RemoveResult(RemoveStatus.OPERATION_IN_PROGRESS); }
    }

    public enum LifecycleStatus {
        LOADED,
        UNLOADED,
        ALREADY_LOADED,
        ALREADY_UNLOADED,
        NOT_MANAGED,
        UNLOAD_FAILED,
        FAILED,
        FALLBACK_UNAVAILABLE,
        PLAYERS_PRESENT,
        STORAGE_NOT_FOUND,
        EXTERNAL_ONLY,
        NOT_READY,
        OPERATION_IN_PROGRESS
    }

    public record LifecycleResult(LifecycleStatus status) {
        private static LifecycleResult loaded() { return new LifecycleResult(LifecycleStatus.LOADED); }
        private static LifecycleResult unloaded() { return new LifecycleResult(LifecycleStatus.UNLOADED); }
        private static LifecycleResult alreadyLoaded() { return new LifecycleResult(LifecycleStatus.ALREADY_LOADED); }
        private static LifecycleResult alreadyUnloaded() { return new LifecycleResult(LifecycleStatus.ALREADY_UNLOADED); }
        private static LifecycleResult notManaged() { return new LifecycleResult(LifecycleStatus.NOT_MANAGED); }
        private static LifecycleResult unloadFailed() { return new LifecycleResult(LifecycleStatus.UNLOAD_FAILED); }
        private static LifecycleResult failed() { return new LifecycleResult(LifecycleStatus.FAILED); }
        private static LifecycleResult fallbackUnavailable() { return new LifecycleResult(LifecycleStatus.FALLBACK_UNAVAILABLE); }
        private static LifecycleResult playersPresent() { return new LifecycleResult(LifecycleStatus.PLAYERS_PRESENT); }
        private static LifecycleResult storageNotFound() { return new LifecycleResult(LifecycleStatus.STORAGE_NOT_FOUND); }
        private static LifecycleResult externalOnly() { return new LifecycleResult(LifecycleStatus.EXTERNAL_ONLY); }
        private static LifecycleResult notReady() { return new LifecycleResult(LifecycleStatus.NOT_READY); }
        private static LifecycleResult operationInProgress() { return new LifecycleResult(LifecycleStatus.OPERATION_IN_PROGRESS); }
    }

    public enum DeleteStatus {
        DELETED,
        PENDING_RESTART,
        UNLOADED_REQUIRES_CONFIRMATION,
        RELOADED,
        NOT_MANAGED,
        NOT_LOADED,
        PLAYERS_PRESENT,
        STORAGE_NOT_FOUND,
        UNLOAD_FAILED,
        METADATA_REMOVAL_FAILED,
        FALLBACK_UNAVAILABLE,
        EXTERNAL_ONLY,
        OPERATION_IN_PROGRESS
    }

    public record DeleteResult(DeleteStatus status) {
        private static DeleteResult deleted() { return new DeleteResult(DeleteStatus.DELETED); }
        private static DeleteResult pendingRestart() { return new DeleteResult(DeleteStatus.PENDING_RESTART); }
        private static DeleteResult unloadedRequiresConfirmation() {
            return new DeleteResult(DeleteStatus.UNLOADED_REQUIRES_CONFIRMATION);
        }
        private static DeleteResult reloaded() { return new DeleteResult(DeleteStatus.RELOADED); }
        private static DeleteResult notManaged() { return new DeleteResult(DeleteStatus.NOT_MANAGED); }
        private static DeleteResult notLoaded() { return new DeleteResult(DeleteStatus.NOT_LOADED); }
        private static DeleteResult playersPresent() { return new DeleteResult(DeleteStatus.PLAYERS_PRESENT); }
        private static DeleteResult storageNotFound() { return new DeleteResult(DeleteStatus.STORAGE_NOT_FOUND); }
        private static DeleteResult unloadFailed() { return new DeleteResult(DeleteStatus.UNLOAD_FAILED); }
        private static DeleteResult metadataRemovalFailed() { return new DeleteResult(DeleteStatus.METADATA_REMOVAL_FAILED); }
        private static DeleteResult fallbackUnavailable() { return new DeleteResult(DeleteStatus.FALLBACK_UNAVAILABLE); }
        private static DeleteResult externalOnly() { return new DeleteResult(DeleteStatus.EXTERNAL_ONLY); }
        private static DeleteResult operationInProgress() { return new DeleteResult(DeleteStatus.OPERATION_IN_PROGRESS); }
    }
}