package io.github.bearl.worldmanagement.core;

import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugLevel;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record DiagnosticEvent(
    Instant occurredAt,
    DebugLevel level,
    DebugArea area,
    String event,
    Map<String, String> fields,
    Optional<Throwable> failure
) {

    public DiagnosticEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(area, "area");
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("event must not be blank.");
        }
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
        failure = Objects.requireNonNull(failure, "failure");
    }
}