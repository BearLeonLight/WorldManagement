package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.storage.yaml.YamlWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.yaml.YamlWorldMetadataCodec;
import io.github.bearl.worldmanagement.storage.jdbc.JdbcWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.jdbc.MariaDbWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.jdbc.MySqlWorldMetadataRepository;
import io.github.bearl.worldmanagement.storage.jdbc.SqliteWorldMetadataRepository;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Creates the one repository selected by the immutable storage configuration. */
public final class StorageRepositoryFactory {

    private StorageRepositoryFactory() {
    }

    public static WorldMetadataRepository create(
        final StorageConfiguration configuration,
        final Path dataDirectory
    ) {
        return create(configuration, dataDirectory, ignored -> { });
    }

    public static WorldMetadataRepository create(
        final StorageConfiguration configuration,
        final Path dataDirectory,
        final Consumer<String> warningSink
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(warningSink, "warningSink");
        return switch (configuration.provider()) {
            case YAML -> new YamlWorldMetadataRepository(
                dataDirectory.resolve("worlds"), configuration.yamlRetainedBackupsPerWorld(), warningSink
            );
            case SQLITE -> new SqliteWorldMetadataRepository(
                configuration.jdbcUrl(), new YamlWorldMetadataCodec()
            );
            case MYSQL -> new MySqlWorldMetadataRepository(
                configuration.jdbcUrl(), configuration.username().isBlank() ? null : configuration.username(),
                configuration.password(), new YamlWorldMetadataCodec()
            );
            case MARIADB -> new MariaDbWorldMetadataRepository(
                configuration.jdbcUrl(), configuration.username().isBlank() ? null : configuration.username(),
                configuration.password(), new YamlWorldMetadataCodec()
            );
        };
    }
}
