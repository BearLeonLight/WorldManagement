package io.github.bearl.worldmanagement.storage;

import java.util.Locale;

/** Metadata provider choices recognized by the configuration contract. */
public enum StorageProvider {
    YAML,
    SQLITE,
    MYSQL,
    MARIADB;

    public static StorageProvider parse(final String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported storage provider: " + value, exception);
        }
    }
}