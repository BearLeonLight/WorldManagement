package io.github.bearl.worldmanagement.hook;

/** Optional global-thread integration that removes a world from an external world manager. */
public interface WorldTrackingHook {

    UntrackStatus untrack(String worldName);

    static WorldTrackingHook disabled() {
        return worldName -> UntrackStatus.NOT_TRACKED;
    }

    enum UntrackStatus {
        UNTRACKED,
        NOT_TRACKED,
        FAILED
    }
}