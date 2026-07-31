package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AccessControl(AccessMode mode, Set<UUID> entries) {

    public AccessControl {
        Objects.requireNonNull(mode, "mode");
        entries = Set.copyOf(entries);
    }

    public static AccessControl unrestricted() {
        return new AccessControl(AccessMode.NONE, Set.of());
    }
}
