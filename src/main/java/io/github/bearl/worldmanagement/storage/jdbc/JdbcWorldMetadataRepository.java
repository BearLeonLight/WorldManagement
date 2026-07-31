package io.github.bearl.worldmanagement.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.storage.AuditedWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.ConcurrentWorldUpdateException;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.WorldMetadataCodec;
import io.github.bearl.worldmanagement.storage.WorldMetadataRepository;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** JDBC adapter storing each complete metadata aggregate as one typed codec payload. */
public class JdbcWorldMetadataRepository implements AuditedWorldMetadataRepository {

    private static final String TABLE = "worldmanagement_metadata";
    private static final String SCHEMA_VERSION_TABLE = "worldmanagement_schema_version";
    private static final String AUDIT_TABLE = "worldmanagement_audit";
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Set<String> OWNED_TABLES = Set.of(TABLE, SCHEMA_VERSION_TABLE, AUDIT_TABLE);
    private static final Set<Integer> TEXT_TYPES = Set.of(
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB
    );
    private static final Set<Integer> INTEGER_TYPES = Set.of(
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL
    );

    private final HikariDataSource dataSource;
    private final WorldMetadataCodec codec;

    public JdbcWorldMetadataRepository(
        final String jdbcUrl,
        final String username,
        final String password,
        final WorldMetadataCodec codec
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        HikariDataSource initializedDataSource = null;
        try {
            final HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            if (username != null) {
                config.setUsername(username);
                config.setPassword(password);
            }
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            config.setPoolName("WorldManagement metadata");
            initializedDataSource = new HikariDataSource(config);
            this.dataSource = initializedDataSource;
            initializeSchema();
        } catch (final RuntimeException exception) {
            if (initializedDataSource != null) {
                initializedDataSource.close();
            }
            throw exception;
        } catch (final SQLException exception) {
            if (initializedDataSource != null) {
                initializedDataSource.close();
            }
            throw new StorageException("Could not connect to metadata database.", exception);
        }
    }

    @Override
    public Collection<WorldMetadata> loadAll() {
        try (Connection connection = dataSource.getConnection()) {
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                final List<PayloadRow> rows = readRows(connection);
                final Collection<WorldMetadata> metadata = new ArrayList<>();
                for (final PayloadRow row : rows) {
                    metadata.add(decodeAndValidate(row));
                }
                connection.commit();
                return metadata;
            } catch (final RuntimeException | SQLException exception) {
                rollback(connection, exception);
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new StorageException("Could not load world metadata from database.", exception);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (final SQLException exception) {
            throw new StorageException("Could not load world metadata from database.", exception);
        }
    }

    @Override
    public Optional<WorldMetadata> find(final String worldName) {
        try (Connection connection = dataSource.getConnection()) {
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT world_name, version, payload FROM " + TABLE + " WHERE world_name = ?")) {
            statement.setString(1, worldName);
            try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    final Optional<WorldMetadata> metadata = Optional.of(decodeAndValidate(
                        new PayloadRow(results.getString(1), results.getLong(2), results.getString(3))));
                    connection.commit();
                    return metadata;
                }
            } catch (final RuntimeException | SQLException exception) {
                rollback(connection, exception);
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new StorageException("Could not find world metadata in database.", exception);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (final SQLException exception) {
            throw new StorageException("Could not find world metadata in database.", exception);
        }
    }

    @Override
    public void create(final WorldMetadata metadata) {
           try (Connection connection = dataSource.getConnection();
               PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + TABLE + " (world_name, version, payload) VALUES (?, ?, ?)"
        )) {
            statement.setString(1, metadata.worldName());
            statement.setLong(2, metadata.version());
            statement.setString(3, codec.encode(metadata));
            statement.executeUpdate();
        } catch (final SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new IllegalStateException("World metadata already exists: " + metadata.worldName(), exception);
            }
            throw new StorageException("Could not create world metadata in database.", exception);
        }
    }

    @Override
    public void createWithAudit(final WorldMetadata metadata, final AuditEvent event) {
        inTransaction(connection -> {
            create(connection, metadata);
            appendAudit(connection, event);
            return null;
        });
    }

    @Override
    public void replace(final WorldMetadata metadata, final long expectedVersion) {
           try (Connection connection = dataSource.getConnection();
               PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + TABLE + " SET version = ?, payload = ? WHERE world_name = ? AND version = ?"
        )) {
            statement.setLong(1, metadata.version());
            statement.setString(2, codec.encode(metadata));
            statement.setString(3, metadata.worldName());
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentWorldUpdateException(metadata.worldName());
            }
        } catch (final SQLException exception) {
            throw new StorageException("Could not replace world metadata in database.", exception);
        }
    }

    @Override
    public void replaceWithAudit(final WorldMetadata metadata, final long expectedVersion, final AuditEvent event) {
        inTransaction(connection -> {
            replace(connection, metadata, expectedVersion);
            appendAudit(connection, event);
            return null;
        });
    }

    @Override
    public void delete(final String worldName, final long expectedVersion) {
           try (Connection connection = dataSource.getConnection();
               PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + TABLE + " WHERE world_name = ? AND version = ?"
        )) {
            statement.setString(1, worldName);
            statement.setLong(2, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentWorldUpdateException(worldName);
            }
        } catch (final SQLException exception) {
            throw new StorageException("Could not delete world metadata in database.", exception);
        }
    }

    @Override
    public void deleteWithAudit(final String worldName, final long expectedVersion, final AuditEvent event) {
        inTransaction(connection -> {
            delete(connection, worldName, expectedVersion);
            appendAudit(connection, event);
            return null;
        });
    }

    @Override
    public void close() {
        dataSource.close();
    }

    public <T> T inTransaction(final JdbcTransaction<T> transaction) {
        Objects.requireNonNull(transaction, "transaction");
        try (Connection connection = dataSource.getConnection()) {
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                final T result = transaction.execute(connection);
                connection.commit();
                return result;
            } catch (final Exception exception) {
                connection.rollback();
                throw new StorageException("Could not commit metadata transaction.", exception);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (final SQLException exception) {
            throw new StorageException("Could not open metadata transaction.", exception);
        }
    }

    private void create(final Connection connection, final WorldMetadata metadata) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + TABLE + " (world_name, version, payload) VALUES (?, ?, ?)"
        )) {
            statement.setString(1, metadata.worldName());
            statement.setLong(2, metadata.version());
            statement.setString(3, codec.encode(metadata));
            statement.executeUpdate();
        }
    }

    private void replace(final Connection connection, final WorldMetadata metadata, final long expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + TABLE + " SET version = ?, payload = ? WHERE world_name = ? AND version = ?"
        )) {
            statement.setLong(1, metadata.version());
            statement.setString(2, codec.encode(metadata));
            statement.setString(3, metadata.worldName());
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentWorldUpdateException(metadata.worldName());
            }
        }
    }

    private void delete(final Connection connection, final String worldName, final long expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + TABLE + " WHERE world_name = ? AND version = ?"
        )) {
            statement.setString(1, worldName);
            statement.setLong(2, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentWorldUpdateException(worldName);
            }
        }
    }

    private void appendAudit(final Connection connection, final AuditEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + AUDIT_TABLE + " (event_id, occurred_at, actor, action, world_name, detail) VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            statement.setString(1, java.util.UUID.randomUUID().toString());
            statement.setString(2, event.occurredAt().toString());
            statement.setString(3, event.actor().orElse(null));
            statement.setString(4, event.action());
            statement.setString(5, event.worldName());
            statement.setString(6, event.detail());
            statement.executeUpdate();
        }
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            final Map<String, String> ownedTables = ownedTables(connection);
            if (ownedTables.isEmpty()) {
                createFreshSchema(connection);
                return;
            }
            if (!ownedTables.keySet().equals(OWNED_TABLES)) {
                throw new StorageException("JDBC metadata schema is partial or contains unexpected owned tables.");
            }
            validateTableShapes(connection, ownedTables);
            validateSchemaMarker(connection);
        }
    }

    private static void createFreshSchema(final Connection connection) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + SCHEMA_VERSION_TABLE + " (version INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE " + TABLE + " ("
                + "world_name VARCHAR(64) NOT NULL PRIMARY KEY, "
                + "version BIGINT NOT NULL, "
                + "payload TEXT NOT NULL"
                + ")");
            statement.executeUpdate("CREATE TABLE " + AUDIT_TABLE + " ("
                + "event_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                + "occurred_at VARCHAR(64) NOT NULL, "
                + "actor VARCHAR(128), "
                + "action VARCHAR(128) NOT NULL, "
                + "world_name VARCHAR(64) NOT NULL, "
                + "detail TEXT NOT NULL"
                + ")");
            statement.executeUpdate(
                "INSERT INTO " + SCHEMA_VERSION_TABLE + " (version) VALUES (" + CURRENT_SCHEMA_VERSION + ")"
            );
            connection.commit();
        } catch (final RuntimeException | SQLException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static Map<String, String> ownedTables(final Connection connection) throws SQLException {
        final Map<String, String> tables = new LinkedHashMap<>();
        try (ResultSet result = connection.getMetaData().getTables(
            connection.getCatalog(), null, "%", new String[] {"TABLE"}
        )) {
            while (result.next()) {
                final String actualName = result.getString("TABLE_NAME");
                final String normalizedName = actualName.toLowerCase(Locale.ROOT);
                if (normalizedName.startsWith("worldmanagement_")) {
                    tables.put(normalizedName, actualName);
                }
            }
        }
        return Map.copyOf(tables);
    }

    private static void validateTableShapes(
        final Connection connection,
        final Map<String, String> ownedTables
    ) throws SQLException {
        validateTableShape(connection, ownedTables.get(SCHEMA_VERSION_TABLE), Map.of(
            "version", new ColumnRequirement(INTEGER_TYPES, false)
        ), Set.of());
        validateTableShape(connection, ownedTables.get(TABLE), Map.of(
            "world_name", new ColumnRequirement(TEXT_TYPES, false),
            "version", new ColumnRequirement(INTEGER_TYPES, false),
            "payload", new ColumnRequirement(TEXT_TYPES, false)
        ), Set.of("world_name"));
        validateTableShape(connection, ownedTables.get(AUDIT_TABLE), Map.of(
            "event_id", new ColumnRequirement(TEXT_TYPES, false),
            "occurred_at", new ColumnRequirement(TEXT_TYPES, false),
            "actor", new ColumnRequirement(TEXT_TYPES, true),
            "action", new ColumnRequirement(TEXT_TYPES, false),
            "world_name", new ColumnRequirement(TEXT_TYPES, false),
            "detail", new ColumnRequirement(TEXT_TYPES, false)
        ), Set.of("event_id"));
    }

    private static void validateTableShape(
        final Connection connection,
        final String actualTableName,
        final Map<String, ColumnRequirement> requirements,
        final Set<String> expectedPrimaryKeys
    ) throws SQLException {
        final DatabaseMetaData metadata = connection.getMetaData();
        final Map<String, ColumnShape> columns = new LinkedHashMap<>();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, actualTableName, "%")) {
            while (result.next()) {
                columns.put(
                    result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                    new ColumnShape(result.getInt("DATA_TYPE"), result.getInt("NULLABLE"))
                );
            }
        }
        if (!columns.keySet().equals(requirements.keySet())) {
            throw new StorageException("Unexpected JDBC table columns for " + actualTableName + ".");
        }
        for (final Map.Entry<String, ColumnRequirement> requirement : requirements.entrySet()) {
            final ColumnShape column = columns.get(requirement.getKey());
            if (!requirement.getValue().acceptedTypes().contains(column.jdbcType())) {
                throw new StorageException("Unexpected JDBC column type for " + actualTableName + "." + requirement.getKey());
            }
            final boolean nullable = column.nullability() != DatabaseMetaData.columnNoNulls;
            if (nullable != requirement.getValue().nullable()) {
                throw new StorageException("Unexpected JDBC nullability for " + actualTableName + "." + requirement.getKey());
            }
        }
        final Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet result = metadata.getPrimaryKeys(connection.getCatalog(), null, actualTableName)) {
            while (result.next()) {
                primaryKeys.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        if (!primaryKeys.equals(expectedPrimaryKeys)) {
            throw new StorageException("Unexpected JDBC primary key for " + actualTableName + ".");
        }
    }

    private static void validateSchemaMarker(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM " + SCHEMA_VERSION_TABLE)) {
            if (!result.next()) {
                throw new StorageException("JDBC metadata schema marker is empty.");
            }
            final int version = result.getInt(1);
            if (result.wasNull() || result.next()) {
                throw new StorageException("JDBC metadata schema marker must contain exactly one version.");
            }
            if (version > CURRENT_SCHEMA_VERSION) {
                throw new io.github.bearl.worldmanagement.storage.UnsupportedStorageSchemaException(
                    "Unsupported JDBC metadata schema version: " + version
                );
            }
            if (version != CURRENT_SCHEMA_VERSION) {
                throw new StorageException("Invalid JDBC metadata schema version: " + version);
            }
        }
    }

    private List<PayloadRow> readRows(final Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT world_name, version, payload FROM " + TABLE + " ORDER BY world_name")) {
            final List<PayloadRow> rows = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new PayloadRow(results.getString(1), results.getLong(2), results.getString(3)));
                }
            }
            return rows;
        }
    }

    private WorldMetadata decodeAndValidate(final PayloadRow row) {
        final WorldMetadata metadata = codec.decode(row.payload());
        if (!row.worldName().equals(metadata.worldName()) || row.version() != metadata.version()) {
            throw new StorageException("JDBC row identity does not match its metadata payload.");
        }
        return metadata;
    }

    private static void rollback(final Connection connection, final Exception failure) {
        try {
            connection.rollback();
        } catch (final SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private record PayloadRow(String worldName, long version, String payload) {
    }

    private record ColumnRequirement(Set<Integer> acceptedTypes, boolean nullable) {
    }

    private record ColumnShape(int jdbcType, int nullability) {
    }

    private static boolean isConstraintViolation(final SQLException exception) {
        return exception.getSQLState() != null && exception.getSQLState().startsWith("23");
    }
}