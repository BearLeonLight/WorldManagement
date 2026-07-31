package io.github.bearl.worldmanagement.audit;

/** Result of submitting an audit event before a protected operation proceeds. */
public enum AuditAdmission {
    ACCEPTED,
    DISABLED,
    REJECTED
}