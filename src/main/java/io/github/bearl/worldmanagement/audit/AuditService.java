package io.github.bearl.worldmanagement.audit;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Enqueues audit writes without making game operations wait for filesystem I/O. */
public final class AuditService {

    private final PluginIoExecutor ioExecutor;
    private final AuditStore auditStore;
    private final Logger logger;
    private final AuditPolicy policy;
    private final Map<String, AuditPolicy> overrides;
    private final DiagnosticLogger diagnostics;

    public AuditService(final PluginIoExecutor ioExecutor, final AuditStore auditStore, final Logger logger, final AuditPolicy policy) {
        this(ioExecutor, auditStore, logger, policy, Map.of(), null);
    }

    public AuditService(
        final PluginIoExecutor ioExecutor,
        final AuditStore auditStore,
        final Logger logger,
        final AuditPolicy policy,
        final Map<String, AuditPolicy> overrides
    ) {
        this(ioExecutor, auditStore, logger, policy, overrides, null);
    }

    public AuditService(
        final PluginIoExecutor ioExecutor,
        final AuditStore auditStore,
        final Logger logger,
        final AuditPolicy policy,
        final Map<String, AuditPolicy> overrides,
        final DiagnosticLogger diagnostics
    ) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.overrides = Map.copyOf(Objects.requireNonNull(overrides, "overrides"));
        this.diagnostics = diagnostics;
    }

    public AuditAdmission record(final String actor, final String action, final String worldName, final String detail) {
        final AuditPolicy effectivePolicy = effectivePolicy(action);
        if (effectivePolicy == AuditPolicy.OFF) {
            return AuditAdmission.DISABLED;
        }
        final var submission = ioExecutor.execute(() -> auditStore.append(new AuditEvent(
            Instant.now(), Optional.ofNullable(actor), action, worldName, detail
        )));
        if (submission.isCompletedExceptionally()) {
            logAdmission(action, effectivePolicy, "queue_rejected");
            return effectivePolicy == AuditPolicy.STRICT ? AuditAdmission.REJECTED : AuditAdmission.ACCEPTED;
        }
        submission.exceptionally(failure -> {
            logger.log(Level.WARNING, "Could not write audit event.", failure);
            if (diagnostics != null) {
                diagnostics.failure(DebugArea.AUDIT, "audit_write_failed", () -> Map.of("action", action), failure);
            }
            return null;
        });
        return AuditAdmission.ACCEPTED;
    }

    public boolean requiresStrictAdmission(final String action) {
        return effectivePolicy(action) == AuditPolicy.STRICT;
    }

    public CompletableFuture<AuditAdmission> admit(final String actor, final String action, final String worldName, final String detail) {
        final AuditPolicy effectivePolicy = effectivePolicy(action);
        if (effectivePolicy == AuditPolicy.OFF) {
            return CompletableFuture.completedFuture(AuditAdmission.DISABLED);
        }
        return ioExecutor.execute(() -> auditStore.append(new AuditEvent(
            Instant.now(), Optional.ofNullable(actor), action, worldName, detail
        ))).handle((unused, failure) -> {
            if (failure != null) {
                logger.log(Level.WARNING, "Could not write audit admission event.", failure);
                return effectivePolicy == AuditPolicy.STRICT ? AuditAdmission.REJECTED : AuditAdmission.ACCEPTED;
            }
            return AuditAdmission.ACCEPTED;
        });
    }

    public void close() {
        if (auditStore instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (final Exception exception) {
                logger.log(Level.WARNING, "Could not close audit store.", exception);
                throw exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Could not close audit store.", exception);
            }
        }
    }

    private AuditPolicy effectivePolicy(final String action) {
        String candidate = Objects.requireNonNull(action, "action");
        while (true) {
            final AuditPolicy override = overrides.get(candidate);
            if (override != null) {
                return override;
            }
            final int separator = candidate.lastIndexOf('.');
            if (separator < 0) {
                return policy;
            }
            candidate = candidate.substring(0, separator);
        }
    }

    private void logAdmission(final String action, final AuditPolicy effectivePolicy, final String outcome) {
        if (diagnostics != null) {
            diagnostics.basic(DebugArea.AUDIT, "audit_admission", () -> Map.of(
                "action", action,
                "policy", effectivePolicy.name(),
                "outcome", outcome
            ));
        }
    }
}