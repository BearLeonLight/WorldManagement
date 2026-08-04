package io.github.bearl.worldmanagement.storage.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.storage.ConcurrentWorldUpdateException;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.UnsupportedStorageSchemaException;
import io.github.bearl.worldmanagement.storage.yaml.YamlWorldMetadataCodec;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JdbcWorldMetadataRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsPhysicalSchemaOneOnlyForAFreshDatabase() throws Exception {
        final String url = sqliteUrl("fresh.db");

        try (JdbcWorldMetadataRepository ignored = repository(url)) {
            // Constructor performs the fresh schema creation.
        }

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             var marker = statement.executeQuery("SELECT version FROM worldmanagement_schema_version")) {
            assertEquals(Set.of(
                "worldmanagement_schema_version", "worldmanagement_metadata", "worldmanagement_audit"
            ), ownedTables(connection));
            assertTrue(marker.next());
            assertEquals(1, marker.getInt(1));
            assertFalse(marker.next());
        }
    }

    @Test
    void persistsCompleteMetadataAndEnforcesVersion() {
        final String url = "jdbc:sqlite:" + temporaryDirectory.resolve("metadata.db").toAbsolutePath();
        try (JdbcWorldMetadataRepository repository = new JdbcWorldMetadataRepository(url, null, null, new YamlWorldMetadataCodec())) {
            final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);
            repository.create(metadata);
            final WorldMetadata updated = metadata.withAccessControl(new AccessControl(AccessMode.BLACKLIST, Set.of()));

            repository.replace(updated, metadata.version());

            assertEquals(updated, repository.find("creative").orElseThrow());
            assertThrows(ConcurrentWorldUpdateException.class, () -> repository.delete("creative", 0));
        }
    }

    @Test
    void persistsDeletingTransactionInSharedPayload() {
        final String url = sqliteUrl("deleting-transaction.db");
        final java.util.UUID transactionId = java.util.UUID.fromString(
            "77777777-7777-7777-7777-777777777777"
        );
        try (JdbcWorldMetadataRepository repository = repository(url)) {
            final WorldMetadata deleting = WorldMetadata.createDefault("creative", true)
                .withDeleting(transactionId);

            repository.create(deleting);

            assertEquals(deleting, repository.find("creative").orElseThrow());
        }
    }

    @Test
    void rollsBackAuditAndMetadataWorkInOneTransaction() throws Exception {
        final String url = "jdbc:sqlite:" + temporaryDirectory.resolve("transaction.db").toAbsolutePath();
        try (JdbcWorldMetadataRepository repository = new JdbcWorldMetadataRepository(url, null, null, new YamlWorldMetadataCodec())) {
            assertThrows(io.github.bearl.worldmanagement.storage.StorageException.class, () -> repository.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO worldmanagement_metadata (world_name, version, payload) VALUES (?, ?, ?)")) {
                    statement.setString(1, "creative");
                    statement.setLong(2, 0);
                    statement.setString(3, "invalid");
                    statement.executeUpdate();
                }
                throw new IllegalStateException("abort");
            }));

            assertEquals(0, repository.loadAll().size());
        }
    }

    @Test
    void refusesFuturePayloadWithoutRewritingIt() {
        final String url = sqliteUrl("future-payload.db");
        final YamlWorldMetadataCodec codec = new YamlWorldMetadataCodec();
        try (JdbcWorldMetadataRepository repository = new JdbcWorldMetadataRepository(url, null, null, codec)) {
            final String futurePayload = "schema-version: 6\nworld-id: creative\n";
            repository.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO worldmanagement_metadata (world_name, version, payload) VALUES (?, ?, ?)")) {
                    statement.setString(1, "creative");
                    statement.setLong(2, 7);
                    statement.setString(3, futurePayload);
                    statement.executeUpdate();
                }
                return null;
            });

            assertThrows(UnsupportedStorageSchemaException.class, repository::loadAll);
            repository.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT payload FROM worldmanagement_metadata WHERE world_name = ?")) {
                    statement.setString(1, "creative");
                    try (var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(futurePayload, result.getString(1));
                    }
                }
                return null;
            });
        }
    }

    @Test
    void rejectsRowsWhoseNameOrVersionDisagreesWithThePayload() {
        final YamlWorldMetadataCodec codec = new YamlWorldMetadataCodec();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);
        final String nameMismatchUrl = sqliteUrl("name-mismatch.db");
        try (JdbcWorldMetadataRepository repository = repository(nameMismatchUrl)) {
            insertPayload(repository, "survival", metadata.version(), codec.encode(metadata));
            assertThrows(StorageException.class, repository::loadAll);
        }
        final String versionMismatchUrl = sqliteUrl("version-mismatch.db");
        try (JdbcWorldMetadataRepository repository = repository(versionMismatchUrl)) {
            insertPayload(repository, metadata.worldName(), metadata.version() + 1, codec.encode(metadata));
            assertThrows(StorageException.class, repository::loadAll);
        }
    }

    @Test
    void refusesPartialOwnedTablesWithoutCreatingMissingTables() throws Exception {
        final String url = sqliteUrl("partial.db");
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE worldmanagement_metadata (world_name VARCHAR(64) PRIMARY KEY, version BIGINT NOT NULL, payload TEXT NOT NULL)"
            );
        }

        assertThrows(StorageException.class, () -> repository(url));

        try (Connection connection = DriverManager.getConnection(url)) {
            assertEquals(Set.of("worldmanagement_metadata"), ownedTables(connection));
        }
    }

    @Test
    void refusesEmptyMultipleAndNonOneMarkersWithoutChangingThem() throws Exception {
        final String emptyUrl = sqliteUrl("empty-marker.db");
        createSchema(emptyUrl);
        assertThrows(StorageException.class, () -> repository(emptyUrl));
        assertEquals(0, markerCount(emptyUrl));

        final String multipleUrl = sqliteUrl("multiple-marker.db");
        createSchema(multipleUrl, 1, 1);
        assertThrows(StorageException.class, () -> repository(multipleUrl));
        assertEquals(2, markerCount(multipleUrl));

        final String futureUrl = sqliteUrl("future-marker.db");
        createSchema(futureUrl, 2);
        assertThrows(UnsupportedStorageSchemaException.class, () -> repository(futureUrl));
        assertEquals(2, markerVersion(futureUrl));

        final String invalidUrl = sqliteUrl("invalid-marker.db");
        createSchema(invalidUrl, 0);
        assertThrows(StorageException.class, () -> repository(invalidUrl));
        assertEquals(0, markerVersion(invalidUrl));
    }

    @Test
    void refusesOwnedTableShapeMismatchWithoutAlteringTheTable() throws Exception {
        final String url = sqliteUrl("shape-mismatch.db");
        createSchema(url, 1);
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE worldmanagement_metadata");
            statement.executeUpdate(
                "CREATE TABLE worldmanagement_metadata (world_name VARCHAR(64) PRIMARY KEY, version BIGINT NOT NULL)"
            );
        }

        assertThrows(StorageException.class, () -> repository(url));

        try (Connection connection = DriverManager.getConnection(url);
             var columns = connection.getMetaData().getColumns(null, null, "worldmanagement_metadata", null)) {
            final Set<String> names = new LinkedHashSet<>();
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
            }
            assertEquals(Set.of("world_name", "version"), names);
        }
    }

    @Test
    void commitsMetadataAndSuccessAuditTogether() {
        final String url = "jdbc:sqlite:" + temporaryDirectory.resolve("audited.db").toAbsolutePath();
        try (JdbcWorldMetadataRepository repository = new JdbcWorldMetadataRepository(url, null, null, new YamlWorldMetadataCodec())) {
            final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);
            repository.createWithAudit(metadata, event("world.adopt"));

            assertEquals(metadata, repository.find("creative").orElseThrow());
            assertEquals(1, auditCount(repository));
        }
    }

    @Test
    void rollsBackMetadataWhenSuccessAuditCannotBeWritten() {
        final String url = "jdbc:sqlite:" + temporaryDirectory.resolve("audit-rollback.db").toAbsolutePath();
        try (JdbcWorldMetadataRepository repository = new JdbcWorldMetadataRepository(url, null, null, new YamlWorldMetadataCodec())) {
            repository.inTransaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DROP TABLE worldmanagement_audit");
                }
                return null;
            });

            assertThrows(io.github.bearl.worldmanagement.storage.StorageException.class,
                () -> repository.createWithAudit(WorldMetadata.createDefault("creative", true), event("world.adopt")));
            assertEquals(0, repository.loadAll().size());
        }
    }

    private static AuditEvent event(final String action) {
        return new AuditEvent(Instant.parse("2026-01-01T00:00:00Z"), Optional.of("console"), action, "creative", "");
    }

    private static int auditCount(final JdbcWorldMetadataRepository repository) {
        return repository.inTransaction(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM worldmanagement_audit")) {
                return result.next() ? result.getInt(1) : 0;
            }
        });
    }

    private String sqliteUrl(final String fileName) {
        return "jdbc:sqlite:" + temporaryDirectory.resolve(fileName).toAbsolutePath();
    }

    private static JdbcWorldMetadataRepository repository(final String url) {
        return new JdbcWorldMetadataRepository(url, null, null, new YamlWorldMetadataCodec());
    }

    private static void insertPayload(
        final JdbcWorldMetadataRepository repository,
        final String worldName,
        final long version,
        final String payload
    ) {
        repository.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO worldmanagement_metadata (world_name, version, payload) VALUES (?, ?, ?)")) {
                statement.setString(1, worldName);
                statement.setLong(2, version);
                statement.setString(3, payload);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void createSchema(final String url, final int... markerVersions) throws Exception {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE worldmanagement_schema_version (version INTEGER NOT NULL)");
            statement.executeUpdate(
                "CREATE TABLE worldmanagement_metadata (world_name VARCHAR(64) NOT NULL PRIMARY KEY, version BIGINT NOT NULL, payload TEXT NOT NULL)"
            );
            statement.executeUpdate("CREATE TABLE worldmanagement_audit ("
                + "event_id VARCHAR(64) NOT NULL PRIMARY KEY, occurred_at VARCHAR(64) NOT NULL, actor VARCHAR(128), "
                + "action VARCHAR(128) NOT NULL, world_name VARCHAR(64) NOT NULL, detail TEXT NOT NULL)"
            );
            for (final int markerVersion : markerVersions) {
                statement.executeUpdate(
                    "INSERT INTO worldmanagement_schema_version (version) VALUES (" + markerVersion + ")"
                );
            }
        }
    }

    private static Set<String> ownedTables(final Connection connection) throws Exception {
        final Set<String> tables = new LinkedHashSet<>();
        try (var result = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (result.next()) {
                final String table = result.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT);
                if (table.startsWith("worldmanagement_")) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private static int markerCount(final String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM worldmanagement_schema_version")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static int markerVersion(final String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT version FROM worldmanagement_schema_version")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }
}