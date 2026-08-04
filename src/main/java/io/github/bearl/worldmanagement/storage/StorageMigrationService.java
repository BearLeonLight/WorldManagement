package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.world.MetadataMutationGate;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Performs an explicit one-way metadata copy while the plugin keeps a single active provider. */
public final class StorageMigrationService {

    private final PluginIoExecutor ioExecutor;
    private final StorageConfiguration activeConfiguration;
    private final Map<StorageProvider, StorageConfiguration> targets;
    private final Path dataDirectory;
    private final StorageMigrator migrator;
    private final DiagnosticLogger diagnostics;
    private final MetadataMutationGate mutationGate;

    public StorageMigrationService(
        final PluginIoExecutor ioExecutor,
        final StorageConfiguration activeConfiguration,
        final Map<StorageProvider, StorageConfiguration> targets,
        final Path dataDirectory
    ) {
        this(ioExecutor, activeConfiguration, targets, dataDirectory, null, new MetadataMutationGate(ioExecutor));
    }

    public StorageMigrationService(
        final PluginIoExecutor ioExecutor,
        final StorageConfiguration activeConfiguration,
        final Map<StorageProvider, StorageConfiguration> targets,
        final Path dataDirectory,
        final MetadataMutationGate mutationGate
    ) {
        this(ioExecutor, activeConfiguration, targets, dataDirectory, null, mutationGate);
    }

    public StorageMigrationService(
        final PluginIoExecutor ioExecutor,
        final StorageConfiguration activeConfiguration,
        final Map<StorageProvider, StorageConfiguration> targets,
        final Path dataDirectory,
        final DiagnosticLogger diagnostics
    ) {
        this(ioExecutor, activeConfiguration, targets, dataDirectory, diagnostics, new MetadataMutationGate(ioExecutor));
    }

    public StorageMigrationService(
        final PluginIoExecutor ioExecutor,
        final StorageConfiguration activeConfiguration,
        final Map<StorageProvider, StorageConfiguration> targets,
        final Path dataDirectory,
        final DiagnosticLogger diagnostics,
        final MetadataMutationGate mutationGate
    ) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.activeConfiguration = Objects.requireNonNull(activeConfiguration, "activeConfiguration");
        this.targets = Map.copyOf(Objects.requireNonNull(targets, "targets"));
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.migrator = new StorageMigrator();
        this.diagnostics = diagnostics;
        this.mutationGate = Objects.requireNonNull(mutationGate, "mutationGate");
    }

    public CompletableFuture<MigrationOutcome> migrate(final StorageProvider source, final StorageProvider target) {
        if (source != activeConfiguration.provider()) {
            return CompletableFuture.completedFuture(MigrationOutcome.rejected(MigrationStatus.SOURCE_NOT_ACTIVE));
        }
        if (source == target) {
            return CompletableFuture.completedFuture(MigrationOutcome.rejected(MigrationStatus.SAME_PROVIDER));
        }
        final StorageConfiguration targetConfiguration = targets.get(target);
        if (targetConfiguration == null) {
            return CompletableFuture.completedFuture(MigrationOutcome.rejected(MigrationStatus.TARGET_NOT_CONFIGURED));
        }
        final long startedAt = System.nanoTime();
        final CompletableFuture<MigrationOutcome> outcome = mutationGate.submitMigration(() -> {
            try (WorldMetadataRepository sourceRepository = StorageRepositoryFactory.create(activeConfiguration, dataDirectory);
                  WorldMetadataRepository targetRepository = StorageRepositoryFactory.create(targetConfiguration, dataDirectory)) {
                return migrator.migrate(sourceRepository, targetRepository);
            }
        }).handle((result, failure) -> {
            if (failure == null) {
                return MigrationOutcome.migrated(result.migratedWorlds());
            }
            if (hasCause(failure, StorageMigrator.TargetNotEmptyException.class)) {
                return MigrationOutcome.rejected(MigrationStatus.TARGET_NOT_EMPTY);
            }
            throw new java.util.concurrent.CompletionException(failure);
        });
        return outcome.whenComplete((result, failure) -> {
            if (diagnostics == null) {
                return;
            }
            final var fields = (java.util.function.Supplier<Map<String, String>>) () -> Map.of(
                "source", source.name(),
                "target", target.name(),
                "outcome", failure == null ? result.status().name().toLowerCase(java.util.Locale.ROOT) : "failure",
                "durationMs", Long.toString(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            );
            if (failure == null && result.status() == MigrationStatus.MIGRATED) {
                diagnostics.basic(DebugArea.STORAGE, "storage_migration_completed", fields);
            } else if (failure == null) {
                diagnostics.basic(DebugArea.STORAGE, "storage_migration_rejected", fields);
            } else {
                diagnostics.failure(DebugArea.STORAGE, "storage_migration_failed", fields, failure);
            }
        });
    }

    private static boolean hasCause(final Throwable failure, final Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public enum MigrationStatus {
        MIGRATED,
        SOURCE_NOT_ACTIVE,
        SAME_PROVIDER,
        TARGET_NOT_CONFIGURED,
        TARGET_NOT_EMPTY
    }

    public record MigrationOutcome(MigrationStatus status, int migratedWorlds) {
        public MigrationOutcome {
            Objects.requireNonNull(status, "status");
            if (migratedWorlds < 0 || status != MigrationStatus.MIGRATED && migratedWorlds != 0) {
                throw new IllegalArgumentException("Only a successful migration may report a positive count.");
            }
        }

        private static MigrationOutcome migrated(final int count) {
            return new MigrationOutcome(MigrationStatus.MIGRATED, count);
        }

        private static MigrationOutcome rejected(final MigrationStatus status) {
            return new MigrationOutcome(status, 0);
        }
    }
}