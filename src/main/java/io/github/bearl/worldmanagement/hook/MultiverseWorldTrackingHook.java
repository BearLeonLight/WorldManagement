package io.github.bearl.worldmanagement.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;

/** Multiverse-Core 5 adapter that removes its tracking while preserving the Bukkit runtime. */
public final class MultiverseWorldTrackingHook implements WorldTrackingHook {

    private static final String REMOVE_WORLD_OPTIONS_CLASS =
        "org.mvplugins.multiverse.core.world.options.RemoveWorldOptions";

    private final Gateway gateway;
    private final java.util.Set<String> pendingPersistence = java.util.concurrent.ConcurrentHashMap.newKeySet();

    MultiverseWorldTrackingHook(final Gateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public static WorldTrackingHook connect() {
        requireCompatibleApi(MultiverseWorldTrackingHook.class.getClassLoader());
        return new MultiverseWorldTrackingHook(new ApiGateway(MultiverseCoreApi.get()));
    }

    static void requireCompatibleApi(final ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        try {
            final Class<?> options = Class.forName(REMOVE_WORLD_OPTIONS_CLASS, false, classLoader);
            final Class<?> multiverseWorld = Class.forName(
                "org.mvplugins.multiverse.core.world.MultiverseWorld", false, classLoader
            );
            final Class<?> worldManager = Class.forName(
                "org.mvplugins.multiverse.core.world.WorldManager", false, classLoader
            );
            final Class<?> attempt = Class.forName(
                "org.mvplugins.multiverse.core.utils.result.Attempt", false, classLoader
            );
            final Class<?> tryResult = Class.forName(
                "org.mvplugins.multiverse.external.vavr.control.Try", false, classLoader
            );
            verifyRequiredMethods(options, multiverseWorld, worldManager, attempt, tryResult);
        } catch (final ClassNotFoundException | NoSuchMethodException | LinkageError failure) {
            throw new IncompatibleApiException(
                "Multiverse-Core 5.2.0 or newer is required for safe world untracking.", failure
            );
        }
    }

    static void verifyRequiredMethods(
        final Class<?> options,
        final Class<?> multiverseWorld,
        final Class<?> worldManager,
        final Class<?> attempt,
        final Class<?> tryResult
    ) throws NoSuchMethodException {
        requireMethod(options.getMethod("world", multiverseWorld), options, true);
        requireMethod(options.getMethod("unloadBukkitWorld", boolean.class), options, false);
        requireMethod(worldManager.getMethod("removeWorld", options), attempt, false);
        requireMethod(worldManager.getMethod("saveWorldsConfig"), tryResult, false);
    }

    private static void requireMethod(
        final Method method,
        final Class<?> returnType,
        final boolean staticMethod
    ) throws NoSuchMethodException {
        if (method.getReturnType() != returnType || Modifier.isStatic(method.getModifiers()) != staticMethod) {
            throw new NoSuchMethodException(method.toGenericString());
        }
    }

    public static final class IncompatibleApiException extends IllegalStateException {

        private IncompatibleApiException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public UntrackStatus untrack(final String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        try {
            if (pendingPersistence.contains(worldName)) {
                return persistRemoval(worldName);
            }
            if (!gateway.isTracked(worldName)) {
                return UntrackStatus.NOT_TRACKED;
            }
            if (!gateway.remove(worldName, true)) {
                return UntrackStatus.FAILED;
            }
            pendingPersistence.add(worldName);
            return persistRemoval(worldName);
        } catch (final RuntimeException | LinkageError failure) {
            return UntrackStatus.FAILED;
        }
    }

    private UntrackStatus persistRemoval(final String worldName) {
        if (!gateway.save()) {
            return UntrackStatus.FAILED;
        }
        pendingPersistence.remove(worldName);
        return UntrackStatus.UNTRACKED;
    }

    interface Gateway {
        boolean isTracked(String worldName);

        boolean remove(String worldName, boolean keepBukkitRuntime);

        boolean save();
    }

    private static final class ApiGateway implements Gateway {
        private final org.mvplugins.multiverse.core.world.WorldManager worldManager;

        private ApiGateway(final MultiverseCoreApi api) {
            this.worldManager = Objects.requireNonNull(api, "api").getWorldManager();
        }

        @Override
        public boolean isTracked(final String worldName) {
            return worldManager.isWorld(worldName);
        }

        @Override
        public boolean remove(final String worldName, final boolean keepBukkitRuntime) {
            final org.mvplugins.multiverse.core.world.MultiverseWorld world =
                worldManager.getWorld(worldName).getOrNull();
            if (world == null) {
                return true;
            }
            return worldManager.removeWorld(
                RemoveWorldOptions.world(world).unloadBukkitWorld(!keepBukkitRuntime)
            ).isSuccess();
        }

        @Override
        public boolean save() {
            return worldManager.saveWorldsConfig().isSuccess();
        }
    }
}