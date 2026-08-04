package io.github.bearl.worldmanagement.world.lifecycle;

/** Current in-flight lifecycle state for one managed world. */
public enum WorldOperationState {
    ADOPTING,
    CREATING,
    LOADING,
    UNLOADING,
    REMOVING,
    MANAGING,
    PURGING,
    IMPORTING,
    DELETING
}