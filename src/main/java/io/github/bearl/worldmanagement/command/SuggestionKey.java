package io.github.bearl.worldmanagement.command;

import java.util.Objects;

/** Typed identifier for a reusable, snapshot-backed command completion source. */
public record SuggestionKey<T>(String id, Class<T> type) {

    public SuggestionKey {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Suggestion key id must not be blank.");
        }
        Objects.requireNonNull(type, "type");
    }
}