package io.github.bearl.worldmanagement.audit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.bearl.worldmanagement.storage.StorageException;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** SQL audit store for JDBC metadata providers. */
public final class SqlAuditStore implements AuditStore, AutoCloseable {

    private final HikariDataSource dataSource;

    public SqlAuditStore(final String jdbcUrl, final String username, final String password) {
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null && !username.isBlank()) {
            config.setUsername(username);
            config.setPassword(password);
        }
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setPoolName("WorldManagement audit");
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public void append(final AuditEvent event) {
        try (var connection = dataSource.getConnection()) {
            append(connection, event);
        } catch (final SQLException exception) {
            throw new StorageException("Could not append SQL audit event.", exception);
        }
    }

    public void append(final Connection connection, final AuditEvent event) {
        final String sql = "INSERT INTO worldmanagement_audit (event_id, occurred_at, actor, action, world_name, detail) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, event.occurredAt().toString());
            statement.setString(3, event.actor().orElse(null));
            statement.setString(4, event.action());
            statement.setString(5, event.worldName());
            statement.setString(6, event.detail());
            statement.executeUpdate();
        } catch (final SQLException exception) {
            throw new StorageException("Could not append SQL audit event.", exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}