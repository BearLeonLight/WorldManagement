package io.github.bearl.worldmanagement.hook;

import java.util.Objects;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;

/** LuckPerms cached lookup scoped to an already loaded destination Bukkit world. */
final class LuckPermsCachedPermissionLookup implements CachedPermissionLookup {

    private final LuckPerms luckPerms;

    LuckPermsCachedPermissionLookup(final LuckPerms luckPerms) {
        this.luckPerms = Objects.requireNonNull(luckPerms, "luckPerms");
    }

    @Override
    public boolean hasPermission(final UUID playerId, final String bukkitWorldName, final String permission) {
        Objects.requireNonNull(playerId, "playerId");
        if (Objects.requireNonNull(bukkitWorldName, "bukkitWorldName").isBlank()
            || Objects.requireNonNull(permission, "permission").isBlank()) {
            return false;
        }
        try {
            final User user = luckPerms.getUserManager().getUser(playerId);
            if (user == null) {
                return false;
            }
            final QueryOptions sourceOptions = luckPerms.getContextManager().getQueryOptions(user).orElse(null);
            if (sourceOptions == null || sourceOptions.mode() != QueryMode.CONTEXTUAL) {
                return false;
            }
            final MutableContextSet destinationContext = sourceOptions.context().mutableCopy();
            destinationContext.removeAll(DefaultContextKeys.WORLD_KEY);
            destinationContext.add(DefaultContextKeys.WORLD_KEY, bukkitWorldName);
            final QueryOptions destinationOptions = sourceOptions.toBuilder().context(destinationContext).build();
            return user.getCachedData().getPermissionData(destinationOptions).checkPermission(permission).asBoolean();
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}