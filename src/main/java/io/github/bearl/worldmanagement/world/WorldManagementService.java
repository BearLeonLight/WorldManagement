package io.github.bearl.worldmanagement.world;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.storage.AuditedWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.WorldMetadataRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** Coordinates metadata mutations without exposing storage details to command handlers. */
public final class WorldManagementService {

    private final PluginIoExecutor ioExecutor;
    private final WorldMetadataRepository repository;
    private final WorldRegistry registry;
    private final AuditService auditService;
    private final DiagnosticLogger diagnostics;
    private final MetadataMutationGate mutationGate;
    private final AtomicBoolean ready = new AtomicBoolean();

    public WorldManagementService(
        final PluginIoExecutor ioExecutor,
        final WorldMetadataRepository repository,
        final WorldRegistry registry
    ) {
        this(ioExecutor, repository, registry, null, null, new MetadataMutationGate(ioExecutor));
    }

    public WorldManagementService(
        final PluginIoExecutor ioExecutor,
        final WorldMetadataRepository repository,
        final WorldRegistry registry,
        final MetadataMutationGate mutationGate
    ) {
        this(ioExecutor, repository, registry, null, null, mutationGate);
    }

    public WorldManagementService(
        final PluginIoExecutor ioExecutor,
        final WorldMetadataRepository repository,
        final WorldRegistry registry,
        final AuditService auditService
    ) {
        this(ioExecutor, repository, registry, auditService, null, new MetadataMutationGate(ioExecutor));
    }

    public WorldManagementService(
        final PluginIoExecutor ioExecutor,
        final WorldMetadataRepository repository,
        final WorldRegistry registry,
        final AuditService auditService,
        final DiagnosticLogger diagnostics
    ) {
        this(ioExecutor, repository, registry, auditService, diagnostics, new MetadataMutationGate(ioExecutor));
    }

    public WorldManagementService(
        final PluginIoExecutor ioExecutor,
        final WorldMetadataRepository repository,
        final WorldRegistry registry,
        final AuditService auditService,
        final DiagnosticLogger diagnostics,
        final MetadataMutationGate mutationGate
    ) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.auditService = auditService;
        this.diagnostics = diagnostics;
        this.mutationGate = Objects.requireNonNull(mutationGate, "mutationGate");
    }

    public CompletableFuture<Void> load() {
        final long startedAt = System.nanoTime();
        ready.set(false);
        return ioExecutor.execute(() -> {
            registry.replaceAll(repository.loadAll());
            ready.set(true);
        }).whenComplete((unused, failure) -> logOutcome("metadata_loaded", null, startedAt, failure));
    }

    public boolean isReady() {
        return ready.get();
    }

    public List<WorldMetadata> managedWorlds() {
        return registry.snapshots().stream()
            .filter(world -> world.managementState() == WorldManagementState.ACTIVE)
            .sorted(java.util.Comparator.comparing(WorldMetadata::worldName))
            .toList();
    }

    public List<WorldMetadata> detachedWorlds() {
        return registry.snapshots().stream()
            .filter(world -> world.managementState() == WorldManagementState.DETACHED)
            .sorted(java.util.Comparator.comparing(WorldMetadata::worldName))
            .toList();
    }

    public List<WorldMetadata> deletingWorlds() {
        return registry.snapshots().stream()
            .filter(world -> world.managementState() == WorldManagementState.DELETING)
            .sorted(java.util.Comparator.comparing(WorldMetadata::worldName))
            .toList();
    }

    public Optional<WorldMetadata> managedWorld(final String worldName) {
        return registry.find(worldName).filter(metadata -> metadata.managementState() == WorldManagementState.ACTIVE);
    }

    public Optional<WorldMetadata> metadataWorld(final String worldName) {
        return registry.find(worldName);
    }

    public WorldRuntimeResolution resolveRuntimeWorld(final WorldIdentitySnapshot observedIdentity) {
        return resolveRuntimeWorld(observedIdentity, LifecycleCapability.MANAGED);
    }

    public WorldRuntimeResolution resolveRuntimeWorld(
        final WorldIdentitySnapshot observedIdentity,
        final LifecycleCapability observedCapability
    ) {
        final WorldIdentitySnapshot observed = Objects.requireNonNull(observedIdentity, "observedIdentity");
        final LifecycleCapability capability = Objects.requireNonNull(observedCapability, "observedCapability");
        final RegistrySnapshot snapshot = registry.snapshot();
        final WorldMetadata byId = snapshot.byId().get(observed.keyValue());
        final WorldMetadata byKey = snapshot.byPaperKey().get(observed.paperKey());
        final WorldMetadata byUuid = snapshot.byUuid().get(observed.worldUuid());
        if (byId == null && byKey == null && byUuid == null) {
            return WorldRuntimeResolution.unmanaged();
        }
        final WorldMetadata candidate = byId != null ? byId : byKey != null ? byKey : byUuid;
        return candidate.managementState() == WorldManagementState.ACTIVE
            && candidate == byId
            && candidate == byKey
            && candidate == byUuid
            && candidate.identityState() == IdentityVerificationState.VERIFIED
            && candidate.lifecycleCapability() == capability
            && candidate.identity().equals(observed)
                ? WorldRuntimeResolution.verified(candidate)
                : WorldRuntimeResolution.isolated(candidate);
    }

    public Optional<WorldMetadata> detachedWorld(final String worldName) {
        return registry.find(worldName).filter(metadata -> metadata.managementState() == WorldManagementState.DETACHED);
    }

    public CompletableFuture<UpdateResult> classifyLoadedIdentity(final WorldIdentitySnapshot observedIdentity) {
        return classifyLoadedIdentity(observedIdentity, LifecycleCapability.MANAGED);
    }

    public CompletableFuture<UpdateResult> classifyLoadedIdentity(
        final WorldIdentitySnapshot observedIdentity,
        final LifecycleCapability observedCapability
    ) {
        final WorldIdentitySnapshot observed = Objects.requireNonNull(observedIdentity, "observedIdentity");
        final LifecycleCapability capability = Objects.requireNonNull(observedCapability, "observedCapability");
        final String worldName = observed.keyValue();
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null) {
            return CompletableFuture.completedFuture(UpdateResult.notManaged());
        }
        final WorldMetadata classified = current.withObservedIdentity(observed, capability);
        if (classified == current) {
            return CompletableFuture.completedFuture(UpdateResult.updated(current));
        }
        return update(worldName, metadata -> metadata.withObservedIdentity(observed, capability));
    }

    public CompletableFuture<IdentitySyncResult> synchronizeIdentity(
        final String worldName,
        final WorldIdentitySnapshot observedIdentity,
        final AuditEvent auditEvent
    ) {
        final String requiredWorldName = Objects.requireNonNull(worldName, "worldName");
        final WorldIdentitySnapshot observed = Objects.requireNonNull(observedIdentity, "observedIdentity")
            .requireWorldId(requiredWorldName);
        if (!isReady()) {
            return CompletableFuture.completedFuture(IdentitySyncResult.notReady());
        }
        if (registry.find(requiredWorldName).isEmpty()) {
            return CompletableFuture.completedFuture(IdentitySyncResult.notManaged());
        }
        final long startedAt = System.nanoTime();
        return mutationGate.submitMutation(() -> {
            final WorldMetadata latest = registry.find(requiredWorldName).orElse(null);
            if (latest == null) {
                return IdentitySyncResult.notManaged();
            }
            if (latest.identityState() != IdentityVerificationState.SYNC_PENDING
                || !latest.pendingIdentity().equals(Optional.of(observed))) {
                return IdentitySyncResult.stale(latest);
            }
            final WorldMetadata updated = latest.synchronizePendingIdentity();
            replace(updated, latest.version(), auditEvent);
            registry.replace(updated);
            return IdentitySyncResult.synchronizedIdentity(updated);
        }).whenComplete((result, failure) ->
            logOutcome("metadata_identity_synchronized", requiredWorldName, startedAt, failure));
    }

    public CompletableFuture<IdentityMutationResult> acceptIdentityReplacement(
        final String worldName,
        final WorldIdentitySnapshot expectedReplacement,
        final boolean keepWarps,
        final AuditEvent auditEvent
    ) {
        final String requiredWorldName = Objects.requireNonNull(worldName, "worldName");
        final WorldIdentitySnapshot replacement = Objects.requireNonNull(expectedReplacement, "expectedReplacement")
            .requireWorldId(requiredWorldName);
        if (!isReady()) {
            return CompletableFuture.completedFuture(IdentityMutationResult.notReady());
        }
        if (registry.find(requiredWorldName).isEmpty()) {
            return CompletableFuture.completedFuture(IdentityMutationResult.notManaged());
        }
        final long startedAt = System.nanoTime();
        return mutationGate.submitMutation(() -> {
            final WorldMetadata latest = registry.find(requiredWorldName).orElse(null);
            if (latest == null) {
                return IdentityMutationResult.notManaged();
            }
            if (latest.identityState() != IdentityVerificationState.CONFLICT
                || !latest.pendingIdentity().equals(Optional.of(replacement))) {
                return IdentityMutationResult.stale(latest);
            }
            final WorldMetadata updated = latest.acceptPendingReplacement(keepWarps);
            try {
                final java.util.LinkedHashMap<String, WorldMetadata> candidate =
                    new java.util.LinkedHashMap<>(registry.snapshot().byId());
                candidate.put(requiredWorldName, updated);
                RegistrySnapshot.from(candidate.values());
            } catch (final IllegalArgumentException exception) {
                return IdentityMutationResult.indexConflict(latest);
            }
            replace(updated, latest.version(), auditEvent);
            registry.replace(updated);
            return IdentityMutationResult.replacementAccepted(updated);
        }).whenComplete((result, failure) ->
            logOutcome("metadata_identity_replacement_accepted", requiredWorldName, startedAt, failure));
    }

    public CompletableFuture<IdentityMutationResult> abandonIdentity(
        final String worldName,
        final long expectedVersion,
        final AuditEvent auditEvent
    ) {
        final String requiredWorldName = Objects.requireNonNull(worldName, "worldName");
        if (!isReady()) {
            return CompletableFuture.completedFuture(IdentityMutationResult.notReady());
        }
        if (registry.find(requiredWorldName).isEmpty()) {
            return CompletableFuture.completedFuture(IdentityMutationResult.notManaged());
        }
        final long startedAt = System.nanoTime();
        return mutationGate.submitMutation(() -> {
            final WorldMetadata latest = registry.find(requiredWorldName).orElse(null);
            if (latest == null) {
                return IdentityMutationResult.notManaged();
            }
            if (latest.version() != expectedVersion
                || latest.managementState() != WorldManagementState.ACTIVE
                || latest.identityState() == IdentityVerificationState.VERIFIED) {
                return IdentityMutationResult.stale(latest);
            }
            final WorldMetadata updated = latest.abandonNonVerifiedIdentity();
            replace(updated, latest.version(), auditEvent);
            registry.replace(updated);
            return IdentityMutationResult.abandoned(updated);
        }).whenComplete((result, failure) ->
            logOutcome("metadata_identity_abandoned", requiredWorldName, startedAt, failure));
    }

    public CompletableFuture<AdoptionResult> adopt(final String worldName, final boolean rankSystemEnabled) {
        return adopt(worldName, rankSystemEnabled, null);
    }

    public CompletableFuture<AdoptionResult> adopt(final String worldName, final boolean rankSystemEnabled, final AuditEvent auditEvent) {
        return adopt(WorldMetadata.createDefault(
            Objects.requireNonNull(worldName, "worldName"), rankSystemEnabled
        ), auditEvent);
    }

    public CompletableFuture<AdoptionResult> adopt(
        final WorldIdentitySnapshot identity,
        final LifecycleCapability lifecycleCapability,
        final Optional<RequestedWorldType> requestedWorldType,
        final boolean rankSystemEnabled,
        final AuditEvent auditEvent
    ) {
        final WorldIdentitySnapshot requiredIdentity = Objects.requireNonNull(identity, "identity");
        return adopt(WorldMetadata.createDefault(
            requiredIdentity.keyValue(),
            requiredIdentity,
            Objects.requireNonNull(lifecycleCapability, "lifecycleCapability"),
            Objects.requireNonNull(requestedWorldType, "requestedWorldType"),
            rankSystemEnabled
        ), auditEvent);
    }

    private CompletableFuture<AdoptionResult> adopt(
        final WorldMetadata metadata,
        final AuditEvent auditEvent
    ) {
        final String worldName = metadata.worldName();
        if (!isReady()) {
            return CompletableFuture.completedFuture(AdoptionResult.notReady());
        }
        if (registry.find(worldName).isPresent()) {
            return CompletableFuture.completedFuture(AdoptionResult.alreadyManaged());
        }

        final long startedAt = System.nanoTime();
        return mutationGate.submitMutation(() -> {
            try {
                create(metadata, auditEvent);
            } catch (final IllegalStateException exception) {
                return AdoptionResult.alreadyManaged();
            }
            registry.replace(metadata);
            return AdoptionResult.adopted(metadata);
        }).whenComplete((result, failure) -> logOutcome("metadata_adopted", worldName, startedAt, failure));
    }

    public CompletableFuture<UpdateResult> update(
        final String worldName,
        final UnaryOperator<WorldMetadata> mutation
    ) {
        return update(worldName, mutation, null);
    }

    public CompletableFuture<UpdateResult> update(
        final String worldName,
        final UnaryOperator<WorldMetadata> mutation,
        final AuditEvent auditEvent
    ) {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(mutation, "mutation");
        if (!isReady()) {
            return CompletableFuture.completedFuture(UpdateResult.notReady());
        }
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null) {
            return CompletableFuture.completedFuture(UpdateResult.notManaged());
        }

        final long startedAt = System.nanoTime();
        return mutationGate.submitMutation(() -> {
            final WorldMetadata latest = registry.find(worldName).orElse(null);
            if (latest == null) {
                return UpdateResult.notManaged();
            }
            final WorldMetadata updated = Objects.requireNonNull(mutation.apply(latest), "mutation result");
            replace(updated, latest.version(), auditEvent);
            registry.replace(updated);
            return UpdateResult.updated(updated);
        }).whenComplete((result, failure) -> logOutcome("metadata_updated", worldName, startedAt, failure));
    }

    public CompletableFuture<UpdateResult> ensureDesiredState(
        final String worldName,
        final WorldLoadState desiredState,
        final AuditEvent auditEvent
    ) {
        Objects.requireNonNull(desiredState, "desiredState");
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null) {
            return CompletableFuture.completedFuture(UpdateResult.notManaged());
        }
        if (current.desiredState() == desiredState) {
            return CompletableFuture.completedFuture(UpdateResult.updated(current));
        }
        return update(worldName, metadata -> metadata.withDesiredState(desiredState), auditEvent);
    }

    public CompletableFuture<RemoveResult> remove(final String worldName) {
        return remove(worldName, null);
    }

    public CompletableFuture<RemoveResult> remove(final String worldName, final AuditEvent auditEvent) {
        Objects.requireNonNull(worldName, "worldName");
        if (!isReady()) {
            return CompletableFuture.completedFuture(RemoveResult.notReady());
        }
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null) {
            return CompletableFuture.completedFuture(RemoveResult.notManaged());
        }
        final long startedAt = System.nanoTime();
        if (current.managementState() != WorldManagementState.ACTIVE) {
            return CompletableFuture.completedFuture(RemoveResult.alreadyDetached());
        }
        return update(worldName, metadata -> metadata
                .withDesiredState(WorldLoadState.UNLOADED)
                .withManagementState(WorldManagementState.DETACHED), auditEvent)
            .thenApply(result -> result.status() == UpdateStatus.UPDATED
                ? RemoveResult.detached(result.metadata())
                : result.status() == UpdateStatus.NOT_READY ? RemoveResult.notReady() : RemoveResult.notManaged())
            .whenComplete((result, failure) -> logOutcome("metadata_detached", worldName, startedAt, failure));
    }

    public CompletableFuture<UpdateResult> manage(final String worldName, final AuditEvent auditEvent) {
        Objects.requireNonNull(worldName, "worldName");
        if (!isReady()) {
            return CompletableFuture.completedFuture(UpdateResult.notReady());
        }
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null || current.managementState() != WorldManagementState.DETACHED) {
            return CompletableFuture.completedFuture(UpdateResult.notManaged());
        }
        return update(worldName, metadata -> metadata.withManagementState(WorldManagementState.ACTIVE), auditEvent);
    }

    public CompletableFuture<UpdateResult> markDeleting(final String worldName, final AuditEvent auditEvent) {
        Objects.requireNonNull(worldName, "worldName");
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null || current.managementState() != WorldManagementState.ACTIVE) {
            return CompletableFuture.completedFuture(UpdateResult.notManaged());
        }
        return update(worldName, metadata -> metadata.withManagementState(WorldManagementState.DELETING), auditEvent);
    }

    public CompletableFuture<UpdateResult> cancelDeleting(final String worldName, final AuditEvent auditEvent) {
        Objects.requireNonNull(worldName, "worldName");
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null || current.managementState() != WorldManagementState.DELETING) {
            return CompletableFuture.completedFuture(UpdateResult.notManaged());
        }
        return update(worldName, metadata -> metadata.withManagementState(WorldManagementState.ACTIVE), auditEvent);
    }

    public CompletableFuture<RemoveResult> purgeDetached(final String worldName, final AuditEvent auditEvent) {
        Objects.requireNonNull(worldName, "worldName");
        if (!isReady()) {
            return CompletableFuture.completedFuture(RemoveResult.notReady());
        }
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null || current.managementState() != WorldManagementState.DETACHED) {
            return CompletableFuture.completedFuture(RemoveResult.notManaged());
        }
        final long startedAt = System.nanoTime();
        return mutationGate.submitMutation(() -> {
            delete(worldName, current.version(), auditEvent);
            registry.remove(worldName);
            return RemoveResult.purged();
        }).whenComplete((result, failure) -> logOutcome("metadata_purged", worldName, startedAt, failure));
    }

    public CompletableFuture<RemoveResult> purge(final String worldName, final AuditEvent auditEvent) {
        Objects.requireNonNull(worldName, "worldName");
        if (!isReady()) {
            return CompletableFuture.completedFuture(RemoveResult.notReady());
        }
        final WorldMetadata current = registry.find(worldName).orElse(null);
        if (current == null) {
            return CompletableFuture.completedFuture(RemoveResult.notManaged());
        }
        final long startedAt = System.nanoTime();
        return mutationGate.submitMutation(() -> {
            delete(worldName, current.version(), auditEvent);
            registry.remove(worldName);
            return RemoveResult.purged();
        }).whenComplete((result, failure) -> logOutcome("metadata_purged", worldName, startedAt, failure));
    }

    public void close() {
        repository.close();
    }

    private void create(final WorldMetadata metadata, final AuditEvent auditEvent) {
        if (auditEvent != null && repository instanceof AuditedWorldMetadataRepository auditedRepository) {
            auditedRepository.createWithAudit(metadata, auditEvent);
            return;
        }
        repository.create(metadata);
        recordAudit(auditEvent);
    }

    private void replace(final WorldMetadata metadata, final long expectedVersion, final AuditEvent auditEvent) {
        if (auditEvent != null && repository instanceof AuditedWorldMetadataRepository auditedRepository) {
            auditedRepository.replaceWithAudit(metadata, expectedVersion, auditEvent);
            return;
        }
        repository.replace(metadata, expectedVersion);
        recordAudit(auditEvent);
    }

    private void delete(final String worldName, final long expectedVersion, final AuditEvent auditEvent) {
        if (auditEvent != null && repository instanceof AuditedWorldMetadataRepository auditedRepository) {
            auditedRepository.deleteWithAudit(worldName, expectedVersion, auditEvent);
            return;
        }
        repository.delete(worldName, expectedVersion);
        recordAudit(auditEvent);
    }

    private void recordAudit(final AuditEvent auditEvent) {
        if (auditEvent != null && auditService != null) {
            auditService.record(auditEvent.actor().orElse(null), auditEvent.action(), auditEvent.worldName(), auditEvent.detail());
        }
    }

    private void logOutcome(final String event, final String worldName, final long startedAt, final Throwable failure) {
        if (diagnostics == null) {
            return;
        }
        final java.util.function.Supplier<java.util.Map<String, String>> fields = () -> {
            final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            if (worldName != null) {
                values.put("world", worldName);
            }
            values.put("outcome", failure == null ? "success" : "failure");
            values.put("durationMs", Long.toString(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
            values.put("managedWorlds", Integer.toString(registry.snapshots().size()));
            return values;
        };
        if (failure == null) {
            diagnostics.basic(DebugArea.METADATA, event, fields);
        } else {
            diagnostics.failure(DebugArea.METADATA, event, fields, failure);
        }
    }

    public enum AdoptionStatus {
        ADOPTED,
        ALREADY_MANAGED,
        NOT_READY
    }

    public record AdoptionResult(AdoptionStatus status, WorldMetadata metadata) {
        private static AdoptionResult adopted(final WorldMetadata metadata) {
            return new AdoptionResult(AdoptionStatus.ADOPTED, metadata);
        }

        private static AdoptionResult alreadyManaged() {
            return new AdoptionResult(AdoptionStatus.ALREADY_MANAGED, null);
        }

        private static AdoptionResult notReady() {
            return new AdoptionResult(AdoptionStatus.NOT_READY, null);
        }
    }

    public enum UpdateStatus {
        UPDATED,
        NOT_MANAGED,
        NOT_READY
    }

    public enum IdentitySyncStatus {
        SYNCHRONIZED,
        STALE,
        NOT_MANAGED,
        NOT_READY
    }

    public enum IdentityMutationStatus {
        REPLACEMENT_ACCEPTED,
        ABANDONED,
        STALE,
        INDEX_CONFLICT,
        NOT_MANAGED,
        NOT_READY
    }

    public record IdentityMutationResult(IdentityMutationStatus status, WorldMetadata metadata) {
        private static IdentityMutationResult replacementAccepted(final WorldMetadata metadata) {
            return new IdentityMutationResult(IdentityMutationStatus.REPLACEMENT_ACCEPTED, metadata);
        }

        private static IdentityMutationResult abandoned(final WorldMetadata metadata) {
            return new IdentityMutationResult(IdentityMutationStatus.ABANDONED, metadata);
        }

        private static IdentityMutationResult stale(final WorldMetadata metadata) {
            return new IdentityMutationResult(IdentityMutationStatus.STALE, metadata);
        }

        private static IdentityMutationResult indexConflict(final WorldMetadata metadata) {
            return new IdentityMutationResult(IdentityMutationStatus.INDEX_CONFLICT, metadata);
        }

        private static IdentityMutationResult notManaged() {
            return new IdentityMutationResult(IdentityMutationStatus.NOT_MANAGED, null);
        }

        private static IdentityMutationResult notReady() {
            return new IdentityMutationResult(IdentityMutationStatus.NOT_READY, null);
        }
    }

    public record IdentitySyncResult(IdentitySyncStatus status, WorldMetadata metadata) {
        private static IdentitySyncResult synchronizedIdentity(final WorldMetadata metadata) {
            return new IdentitySyncResult(IdentitySyncStatus.SYNCHRONIZED, metadata);
        }

        private static IdentitySyncResult stale(final WorldMetadata metadata) {
            return new IdentitySyncResult(IdentitySyncStatus.STALE, metadata);
        }

        private static IdentitySyncResult notManaged() {
            return new IdentitySyncResult(IdentitySyncStatus.NOT_MANAGED, null);
        }

        private static IdentitySyncResult notReady() {
            return new IdentitySyncResult(IdentitySyncStatus.NOT_READY, null);
        }
    }

    public record UpdateResult(UpdateStatus status, WorldMetadata metadata) {
        private static UpdateResult updated(final WorldMetadata metadata) {
            return new UpdateResult(UpdateStatus.UPDATED, metadata);
        }

        private static UpdateResult notManaged() {
            return new UpdateResult(UpdateStatus.NOT_MANAGED, null);
        }

        private static UpdateResult notReady() {
            return new UpdateResult(UpdateStatus.NOT_READY, null);
        }
    }

    public enum RemoveStatus {
        DETACHED,
        ALREADY_DETACHED,
        PURGED,
        NOT_MANAGED,
        NOT_READY
    }

    public record RemoveResult(RemoveStatus status, WorldMetadata metadata) {
        private static RemoveResult detached(final WorldMetadata metadata) { return new RemoveResult(RemoveStatus.DETACHED, metadata); }
        private static RemoveResult alreadyDetached() { return new RemoveResult(RemoveStatus.ALREADY_DETACHED, null); }
        private static RemoveResult purged() { return new RemoveResult(RemoveStatus.PURGED, null); }
        private static RemoveResult notManaged() { return new RemoveResult(RemoveStatus.NOT_MANAGED, null); }
        private static RemoveResult notReady() { return new RemoveResult(RemoveStatus.NOT_READY, null); }
    }
}
