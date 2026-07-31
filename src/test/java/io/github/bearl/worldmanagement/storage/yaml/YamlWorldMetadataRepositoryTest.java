package io.github.bearl.worldmanagement.storage.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.storage.ConcurrentWorldUpdateException;
import io.github.bearl.worldmanagement.storage.UnsupportedStorageSchemaException;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.Rank;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class YamlWorldMetadataRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndLoadsACompleteMetadataAggregate() {
        final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(temporaryDirectory.resolve("worlds"));
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata metadata = new WorldMetadata(
            "creative",
            playerId.toString(),
            true,
            new AccessControl(AccessMode.WHITELIST, Set.of(playerId)),
            Map.of(
                WorldMetadata.OWNER_RANK, new Rank("Owner", Set.of()),
                WorldMetadata.GUEST_RANK, new Rank("Guest", Set.of()),
                "BUILDER", new Rank("Builder", Set.of(RankPermission.BUILD, RankPermission.INTERACT))
            ),
            Map.of(playerId, "BUILDER"),
            Map.of(),
            0
        );

        repository.create(metadata);

        assertEquals(metadata, repository.find("creative").orElseThrow());
        assertEquals(1, repository.loadAll().size());
    }

    @Test
    void rejectsStaleReplacementsAndDeletes() {
        final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(temporaryDirectory.resolve("worlds"));
        final WorldMetadata metadata = WorldMetadata.createDefault("survival", true);
        repository.create(metadata);
        final WorldMetadata updated = metadata.withAccessControl(new AccessControl(AccessMode.BLACKLIST, Set.of()));

        repository.replace(updated, 0);

        assertThrows(ConcurrentWorldUpdateException.class, () -> repository.replace(updated, 0));
        assertThrows(ConcurrentWorldUpdateException.class, () -> repository.delete("survival", 0));
        repository.delete("survival", 1);
        assertFalse(repository.find("survival").isPresent());
    }

    @Test
    void roundTripsPrivateWarpAccess() {
        final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(temporaryDirectory.resolve("worlds"));
        final UUID playerId = UUID.randomUUID();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true).withWarp(
            new io.github.bearl.worldmanagement.world.WorldWarp("vault", 1.5, 64, -2, 90, 0,
                io.github.bearl.worldmanagement.world.WarpVisibility.PRIVATE, Set.of(playerId), Set.of("OWNER"), "worldmanagement.warp.vault")
        );

        repository.create(metadata);

        assertEquals(metadata, repository.find("creative").orElseThrow());
    }

    @Test
    void createsBackupBeforeReplacingMetadata() throws Exception {
        final Path worlds = temporaryDirectory.resolve("worlds");
        final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(worlds);
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);
        repository.create(metadata);

        repository.replace(metadata.withAccessControl(new AccessControl(AccessMode.BLACKLIST, Set.of())), metadata.version());

        try (var backups = Files.list(worlds.resolve("backup"))) {
            assertEquals(1, backups.count());
        }
    }

    @Test
    void quarantinesCorruptMetadataWithoutBlockingOtherWorlds() throws Exception {
        final Path worlds = temporaryDirectory.resolve("worlds");
        final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(worlds);
        repository.create(WorldMetadata.createDefault("creative", true));
        Files.writeString(worlds.resolve("broken.yml"), "schema-version: invalid\n");

        assertEquals(1, repository.loadAll().size());
        assertFalse(Files.exists(worlds.resolve("broken.yml")));
        try (var quarantined = Files.list(worlds.resolve("quarantine"))) {
            assertEquals(1, quarantined.count());
        }
    }

    @Test
    void refusesSchemaTwoWithoutModifyingOrQuarantiningIt() throws Exception {
        final Path worlds = temporaryDirectory.resolve("worlds");
        Files.createDirectories(worlds);
        final Path metadataFile = worlds.resolve("creative.yml");
        Files.writeString(metadataFile, "schema-version: 2\nworld-id: creative\n");

        final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(worlds);

        assertThrows(UnsupportedStorageSchemaException.class, repository::loadAll);
        assertEquals("schema-version: 2\nworld-id: creative\n", Files.readString(metadataFile));
        assertFalse(Files.exists(worlds.resolve("backup")));
        assertFalse(Files.exists(worlds.resolve("quarantine")));
    }

        @Test
        void refusesFutureSchemaWithoutQuarantiningIt() throws Exception {
                final Path worlds = temporaryDirectory.resolve("worlds");
                Files.createDirectories(worlds);
                final Path metadataFile = worlds.resolve("creative.yml");
                Files.writeString(metadataFile, "schema-version: 4\n");

                final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(worlds);

                assertThrows(UnsupportedStorageSchemaException.class, repository::loadAll);
                assertTrue(Files.exists(metadataFile));
                assertFalse(Files.exists(worlds.resolve("quarantine")));
        }

            @Test
    void refusesFutureSchemaBeforeQuarantiningAnyCorruptSchemaOneMetadata() throws Exception {
                final Path worlds = temporaryDirectory.resolve("worlds");
                Files.createDirectories(worlds);
                final Path corrupt = worlds.resolve("a-corrupt.yml");
                Files.writeString(corrupt, "schema-version: 1\nworld-id: creative\n");
                Files.writeString(worlds.resolve("z-future.yml"), "schema-version: 4\n");
                final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(worlds);

                assertThrows(UnsupportedStorageSchemaException.class, repository::loadAll);

                assertTrue(Files.exists(corrupt));
                assertFalse(Files.exists(worlds.resolve("quarantine")));
                assertFalse(Files.exists(worlds.resolve("backup")));
            }

    @Test
    void rejectsUppercaseMetadataFileIdentifiers() {
        final YamlWorldMetadataRepository repository = new YamlWorldMetadataRepository(temporaryDirectory.resolve("worlds"));

        assertThrows(IllegalArgumentException.class, () -> repository.find("Creative"));
    }
}