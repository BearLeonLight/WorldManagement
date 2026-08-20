package io.github.bearl.worldmanagement.storage;

import java.util.Objects;

/** Immutable configuration for the one active metadata provider. */
public record StorageConfiguration(
    StorageProvider provider,
    String jdbcUrl,
    String username,
    String password,
    int yamlRetainedBackupsPerWorld
) {

    public static final int DEFAULT_YAML_RETAINED_BACKUPS_PER_WORLD = 20;

    public StorageConfiguration(
        final StorageProvider provider,
        final String jdbcUrl,
        final String username,
        final String password
    ) {
        this(provider, jdbcUrl, username, password, DEFAULT_YAML_RETAINED_BACKUPS_PER_WORLD);
    }

    public StorageConfiguration {
        Objects.requireNonNull(provider, "provider");
        jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        if (provider != StorageProvider.YAML && jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("A JDBC URL is required for " + provider + ".");
        }
        if (yamlRetainedBackupsPerWorld < 1 || yamlRetainedBackupsPerWorld > 1_000) {
            throw new IllegalArgumentException("storage.yaml.retained-backups-per-world must be between 1 and 1000.");
        }
    }

    public static StorageConfiguration defaults() {
        return new StorageConfiguration(StorageProvider.YAML, "", "", "");
    }
}
