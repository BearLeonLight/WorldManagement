package io.github.bearl.worldmanagement.storage.jdbc;

import java.sql.Connection;

/** Callback executed inside one JDBC metadata transaction. */
@FunctionalInterface
public interface JdbcTransaction<T> {

    T execute(Connection connection) throws Exception;
}