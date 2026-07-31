package io.github.bearl.worldmanagement.hook;

import org.bukkit.Bukkit;

/** Optional LuckPerms capability that keeps the plugin loadable when the API is absent. */
public final class LuckPermsHook {

    private final Object luckPerms;
    private final CachedPermissionLookup cachedPermissions;

    private LuckPermsHook(final Object luckPerms, final CachedPermissionLookup cachedPermissions) {
        this.luckPerms = luckPerms;
        this.cachedPermissions = cachedPermissions;
    }

    public static LuckPermsHook detect(final boolean enabled) {
        if (!enabled) {
            return unavailable();
        }
        try {
            final Class<?> apiType = Class.forName("net.luckperms.api.LuckPerms", false, LuckPermsHook.class.getClassLoader());
            final Object service = Bukkit.getServicesManager().load(apiType);
            if (service == null) {
                return unavailable();
            }
            final Class<?> adapterType = Class.forName(
                "io.github.bearl.worldmanagement.hook.LuckPermsCachedPermissionLookup",
                true,
                LuckPermsHook.class.getClassLoader()
            );
            final java.lang.reflect.Constructor<?> constructor = adapterType.getDeclaredConstructor(apiType);
            constructor.setAccessible(true);
            final CachedPermissionLookup lookup = (CachedPermissionLookup) constructor.newInstance(service);
            return new LuckPermsHook(service, lookup);
        } catch (final ReflectiveOperationException | LinkageError exception) {
            return unavailable();
        }
    }

    public boolean available() {
        return luckPerms != null;
    }

    public CachedPermissionLookup cachedPermissions() {
        return cachedPermissions;
    }

    private static LuckPermsHook unavailable() {
        return new LuckPermsHook(null, CachedPermissionLookup.denyAll());
    }

}