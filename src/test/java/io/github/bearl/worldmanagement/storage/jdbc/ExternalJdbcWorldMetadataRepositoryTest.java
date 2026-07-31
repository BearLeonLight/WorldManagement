package io.github.bearl.worldmanagement.storage.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.storage.yaml.YamlWorldMetadataCodec;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs against a supplied MySQL or MariaDB instance only when the JDBC URL property is configured.
 * Use a dedicated test database because the repository creates its metadata table.
 */
@EnabledIfSystemProperty(named = "worldmanagement.jdbc.url", matches = ".+")
final class ExternalJdbcWorldMetadataRepositoryTest {

    @Test
    void persistsAndRemovesMetadataAgainstConfiguredDatabase() {
        final String worldName = "integration_" + UUID.randomUUID().toString().replace("-", "");
        final String url = System.getProperty("worldmanagement.jdbc.url");
        final String username = System.getProperty("worldmanagement.jdbc.username");
        final String password = System.getProperty("worldmanagement.jdbc.password");
        try (JdbcWorldMetadataRepository repository = new JdbcWorldMetadataRepository(url, username, password, new YamlWorldMetadataCodec())) {
            final WorldMetadata metadata = WorldMetadata.createDefault(worldName, true);
            repository.create(metadata);

            assertEquals(metadata, repository.find(worldName).orElseThrow());

            repository.delete(worldName, metadata.version());
            assertTrue(repository.find(worldName).isEmpty());
        }
    }
}