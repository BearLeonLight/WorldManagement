package io.github.bearl.worldmanagement.storage;

import java.util.Objects;

/** Immutable configuration for the one active metadata provider. */
public record StorageConfiguration(StorageProvider provider, String jdbcUrl, String username, String password) {

    public StorageConfiguration {
        Objects.requireNonNull(provider, "provider");
        jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        username = Objects.requireNonNull(username, "username");
        password = Objects.requireNonNull(password, "password");
        if (provider != StorageProvider.YAML && jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("A JDBC URL is required for " + provider + ".");
        }
    }

    public static StorageConfiguration defaults() {
        return new StorageConfiguration(StorageProvider.YAML, "", "", "");
    }
}