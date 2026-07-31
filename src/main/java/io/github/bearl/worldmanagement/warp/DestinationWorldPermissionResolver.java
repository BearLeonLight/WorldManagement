package io.github.bearl.worldmanagement.warp;

import io.github.bearl.worldmanagement.hook.CachedPermissionLookup;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import java.util.Objects;
import java.util.UUID;

/** Resolves external permissions only for the exact loaded destination world identity. */
public final class DestinationWorldPermissionResolver {

    private final LoadedWorldCatalog loadedWorlds;
    private final CachedPermissionLookup cachedPermissions;

    public DestinationWorldPermissionResolver(
        final LoadedWorldCatalog loadedWorlds,
        final CachedPermissionLookup cachedPermissions
    ) {
        this.loadedWorlds = Objects.requireNonNull(loadedWorlds, "loadedWorlds");
        this.cachedPermissions = Objects.requireNonNull(cachedPermissions, "cachedPermissions");
    }

    public boolean hasPermission(
        final UUID playerId,
        final VerifiedWorldRef target,
        final String permission
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(target, "target");
        if (Objects.requireNonNull(permission, "permission").isEmpty()) {
            return true;
        }
        return loadedWorlds.findUniqueByWorldId(target.worldId())
            .filter(world -> world.reference().equals(target))
            .map(world -> cachedPermissions.hasPermission(playerId, world.bukkitWorldName(), permission))
            .orElse(false);
    }
}