package io.github.bearl.worldmanagement.storage.jdbc;

import io.github.bearl.worldmanagement.storage.WorldMetadataCodec;

/** MariaDB repository entry point backed by the shared JDBC contract implementation. */
public final class MariaDbWorldMetadataRepository extends JdbcWorldMetadataRepository {

    public MariaDbWorldMetadataRepository(
        final String jdbcUrl,
        final String username,
        final String password,
        final WorldMetadataCodec codec
    ) {
        super(jdbcUrl, username, password, codec);
    }
}
