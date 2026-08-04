package io.github.bearl.worldmanagement.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.MetadataMutationGate;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StorageMigrationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsMigrationPreconditionRejections() {
        final PluginIoExecutor executor = new PluginIoExecutor("MigrationTest");
        try {
            final StorageMigrationService service = new StorageMigrationService(
                executor,
                StorageConfiguration.defaults(),
                Map.of(StorageProvider.YAML, StorageConfiguration.defaults()),
                temporaryDirectory
            );

            assertEquals(StorageMigrationService.MigrationStatus.SOURCE_NOT_ACTIVE,
                service.migrate(StorageProvider.SQLITE, StorageProvider.YAML).join().status());
            assertEquals(StorageMigrationService.MigrationStatus.SAME_PROVIDER,
                service.migrate(StorageProvider.YAML, StorageProvider.YAML).join().status());
            assertEquals(StorageMigrationService.MigrationStatus.TARGET_NOT_CONFIGURED,
                service.migrate(StorageProvider.YAML, StorageProvider.SQLITE).join().status());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void rejectsNonEmptyTargetWithoutFreezingMetadataMutations() {
        final PluginIoExecutor executor = new PluginIoExecutor("MigrationTest");
        try {
            final MetadataMutationGate mutationGate = new MetadataMutationGate(executor);
            final StorageConfiguration sqlite = sqliteConfiguration("non-empty-target.db");
            try (WorldMetadataRepository target = StorageRepositoryFactory.create(sqlite, temporaryDirectory)) {
                target.create(completeMetadata());
            }
            final StorageMigrationService service = new StorageMigrationService(
                executor,
                StorageConfiguration.defaults(),
                Map.of(StorageProvider.SQLITE, sqlite),
                temporaryDirectory,
                mutationGate
            );

            assertEquals(StorageMigrationService.MigrationStatus.TARGET_NOT_EMPTY,
                service.migrate(StorageProvider.YAML, StorageProvider.SQLITE).join().status());
            assertEquals("still-open", mutationGate.submitMutation(() -> "still-open").join());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void freezesSharedMetadataMutationsAfterSuccessfulMigration() {
        final PluginIoExecutor executor = new PluginIoExecutor("MigrationTest");
        try {
            final MetadataMutationGate mutationGate = new MetadataMutationGate(executor);
            final StorageConfiguration sqlite = new StorageConfiguration(
                StorageProvider.SQLITE,
                "jdbc:sqlite:" + temporaryDirectory.resolve("migration.db").toAbsolutePath(),
                "",
                ""
            );
            final StorageMigrationService service = new StorageMigrationService(
                executor,
                StorageConfiguration.defaults(),
                Map.of(StorageProvider.SQLITE, sqlite),
                temporaryDirectory,
                mutationGate
            );

            assertEquals(StorageMigrationService.MigrationStatus.MIGRATED,
                service.migrate(StorageProvider.YAML, StorageProvider.SQLITE).join().status());

            assertTrue(mutationGate.submitMutation(() -> null).isCompletedExceptionally());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void migratesCompleteSchemaOneAggregateFromYamlToSqlite() {
        final StorageConfiguration yaml = StorageConfiguration.defaults();
        final StorageConfiguration sqlite = sqliteConfiguration("yaml-to-sqlite.db");
        final WorldMetadata metadata = completeMetadata();
        try (WorldMetadataRepository source = StorageRepositoryFactory.create(yaml, temporaryDirectory)) {
            source.create(metadata);
        }

        runMigration(yaml, sqlite, StorageProvider.YAML, StorageProvider.SQLITE);

        try (WorldMetadataRepository target = StorageRepositoryFactory.create(sqlite, temporaryDirectory)) {
            assertEquals(metadata, target.find("creative").orElseThrow());
        }
    }

    @Test
    void migratesCompleteSchemaOneAggregateFromSqliteToYaml() {
        final StorageConfiguration sqlite = sqliteConfiguration("sqlite-to-yaml.db");
        final StorageConfiguration yaml = StorageConfiguration.defaults();
        final WorldMetadata metadata = completeMetadata();
        try (WorldMetadataRepository source = StorageRepositoryFactory.create(sqlite, temporaryDirectory)) {
            source.create(metadata);
        }

        runMigration(sqlite, yaml, StorageProvider.SQLITE, StorageProvider.YAML);

        try (WorldMetadataRepository target = StorageRepositoryFactory.create(yaml, temporaryDirectory)) {
            assertEquals(metadata, target.find("creative").orElseThrow());
        }
    }

    private void runMigration(
        final StorageConfiguration active,
        final StorageConfiguration target,
        final StorageProvider sourceProvider,
        final StorageProvider targetProvider
    ) {
        final PluginIoExecutor executor = new PluginIoExecutor("MigrationTest");
        try {
            final StorageMigrationService service = new StorageMigrationService(
                executor,
                active,
                Map.of(targetProvider, target),
                temporaryDirectory
            );
            final StorageMigrationService.MigrationOutcome outcome =
                service.migrate(sourceProvider, targetProvider).join();
            assertEquals(StorageMigrationService.MigrationStatus.MIGRATED, outcome.status());
            assertEquals(1, outcome.migratedWorlds());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private StorageConfiguration sqliteConfiguration(final String fileName) {
        return new StorageConfiguration(
            StorageProvider.SQLITE,
            "jdbc:sqlite:" + temporaryDirectory.resolve(fileName).toAbsolutePath(),
            "",
            ""
        );
    }

    private static WorldMetadata completeMetadata() {
        final UUID worldUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final UUID playerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        return WorldMetadata.createDefault(
            "creative",
            new WorldIdentitySnapshot("minecraft:creative", worldUuid, WorldEnvironment.NORMAL, 42L, true),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true
        ).withAccessControl(new AccessControl(AccessMode.WHITELIST, Set.of(playerUuid)))
            .withWarp(new WorldWarp(
                "spawn", 1, 64, 2, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
            ));
    }
}