package io.github.bearl.worldmanagement.storage.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Driver;
import org.junit.jupiter.api.Test;

final class JdbcDriverCompatibilityTest {

    @Test
    void externalDriversAcceptTheirConfiguredProviderUrls() throws Exception {
        final Driver mySqlDriver = new com.mysql.cj.jdbc.Driver();
        final Driver mariaDbDriver = new org.mariadb.jdbc.Driver();

        assertTrue(mySqlDriver.acceptsURL("jdbc:mysql://localhost/worldmanagement"));
        assertTrue(mariaDbDriver.acceptsURL("jdbc:mariadb://localhost/worldmanagement"));
    }
}
