package io.github.bearl.worldmanagement.audit;

import java.util.Locale;

/** Controls whether an operation may proceed when its audit event cannot be queued. */
public enum AuditPolicy {
    OFF,
    BEST_EFFORT,
    STRICT;

    public static AuditPolicy parse(final String value) {
        return AuditPolicy.valueOf(value.toUpperCase(Locale.ROOT));
    }
}