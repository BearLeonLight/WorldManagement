package io.github.bearl.worldmanagement.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.storage.yaml.YamlWorldMetadataRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class StorageConfigurationTest {

    @Test
    void defaultsToTheSingleYamlProvider() {
        assertEquals(StorageProvider.YAML, StorageConfiguration.defaults().provider());
    }

    @Test
    void recognizesSupportedProviderNames() {
        assertEquals(StorageProvider.SQLITE, StorageProvider.parse("sqlite"));
    }

    @Test
    void createsYamlRepositoryForYamlProvider() {
        final WorldMetadataRepository repository = StorageRepositoryFactory.create(
            StorageConfiguration.defaults(),
            Path.of("build", "test-storage")
        );

        assertTrue(repository instanceof YamlWorldMetadataRepository);
    }

    @Test
    void requiresJdbcUrlForSqlProviders() {
        assertThrows(IllegalArgumentException.class, () -> new StorageConfiguration(StorageProvider.SQLITE, "", "", ""));
    }
}