package io.github.bearl.worldmanagement.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Structured record for an observable administrative action. */
public record AuditEvent(Instant occurredAt, Optional<String> actor, String action, String worldName, String detail) {

    public AuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        actor = Objects.requireNonNull(actor, "actor");
        requireText(action, "action");
        requireText(worldName, "worldName");
        detail = Objects.requireNonNull(detail, "detail");
    }

    private static void requireText(final String value, final String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
    }
}