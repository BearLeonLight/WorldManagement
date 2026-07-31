package io.github.bearl.worldmanagement.hook;

import java.util.Objects;
import java.util.UUID;

/** Resolves an online player's permission from an already-cached destination-world context. */
@FunctionalInterface
public interface CachedPermissionLookup {

    boolean hasPermission(UUID playerId, String bukkitWorldName, String permission);

    static CachedPermissionLookup denyAll() {
        return (playerId, bukkitWorldName, permission) -> {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
            Objects.requireNonNull(permission, "permission");
            return false;
        };
    }
}