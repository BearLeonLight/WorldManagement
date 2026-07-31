package io.github.bearl.worldmanagement.config;

import java.util.Locale;

public enum DebugArea {
    STARTUP,
    METADATA,
    AUDIT,
    IO,
    COMMAND,
    LIFECYCLE,
    WARP,
    OWNERSHIP,
    PROTECTION,
    STORAGE;

    public static DebugArea parse(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("debug.areas entries must not be blank.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported debug area: " + value, exception);
        }
    }
}