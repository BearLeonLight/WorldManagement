package io.github.bearl.worldmanagement.world;

/** Current relationship between persisted identity and an observed Paper world. */
public enum IdentityVerificationState {
    VERIFIED,
    SYNC_PENDING,
    CONFLICT
}