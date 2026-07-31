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

    public CompletableFuture<StorageMigrator.MigrationResult> migrate(final StorageProvider source, final StorageProvider target) {
        if (source != activeConfiguration.provider()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Source must be the active storage provider."));
        }
        if (source == target) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Source and target providers must differ."));
        }
        final StorageConfiguration targetConfiguration = targets.get(target);
        if (targetConfiguration == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Target provider is not configured."));
        }
        final long startedAt = System.nanoTime();
        return mutationGate.submitMigration(() -> {
            try (WorldMetadataRepository sourceRepository = StorageRepositoryFactory.create(activeConfiguration, dataDirectory);
                  WorldMetadataRepository targetRepository = StorageRepositoryFactory.create(targetConfiguration, dataDirectory)) {
                return migrator.migrate(sourceRepository, targetRepository);
            }
        }).whenComplete((result, failure) -> {
            if (diagnostics == null) {
                return;
            }
            final var fields = (java.util.function.Supplier<Map<String, String>>) () -> Map.of(
                "source", source.name(),
                "target", target.name(),
                "outcome", failure == null ? "success" : "failure",
                "durationMs", Long.toString(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            );
            if (failure == null) {
                diagnostics.basic(DebugArea.STORAGE, "storage_migration_completed", fields);
            } else {
                diagnostics.failure(DebugArea.STORAGE, "storage_migration_failed", fields, failure);
            }
        });
    }
}