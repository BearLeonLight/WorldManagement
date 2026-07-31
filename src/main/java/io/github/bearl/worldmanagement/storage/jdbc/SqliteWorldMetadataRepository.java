package io.github.bearl.worldmanagement.storage.jdbc;

import io.github.bearl.worldmanagement.storage.WorldMetadataCodec;

/** SQLite-specific repository entry point backed by the shared JDBC contract implementation. */
public final class SqliteWorldMetadataRepository extends JdbcWorldMetadataRepository {

    public SqliteWorldMetadataRepository(final String jdbcUrl, final WorldMetadataCodec codec) {
        super(jdbcUrl, null, null, codec);
    }
}