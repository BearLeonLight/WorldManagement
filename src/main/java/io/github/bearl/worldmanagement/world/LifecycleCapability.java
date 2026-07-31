package io.github.bearl.worldmanagement.world;

/** Whether WorldManagement may actively manage the world's Paper lifecycle. */
public enum LifecycleCapability {
    MANAGED,
    EXTERNAL_ONLY;

    public boolean permitsManagedLifecycle() {
        return this == MANAGED;
    }
}