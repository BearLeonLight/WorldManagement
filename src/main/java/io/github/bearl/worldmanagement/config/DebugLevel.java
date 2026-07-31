package io.github.bearl.worldmanagement.config;

import java.util.Locale;

public enum DebugLevel {
    OFF,
    BASIC,
    VERBOSE;

    public static DebugLevel parse(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("debug.level must not be blank.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported debug.level: " + value, exception);
        }
    }

    public boolean includes(final DebugLevel required) {
        return this.ordinal() >= required.ordinal();
    }
}