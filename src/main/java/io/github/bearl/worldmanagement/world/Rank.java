package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.Set;

public record Rank(String displayName, Set<RankPermission> permissions) {

    public Rank {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank.");
        }
        permissions = Set.copyOf(permissions);
    }
}
