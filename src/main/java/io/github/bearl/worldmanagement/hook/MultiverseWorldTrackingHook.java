package io.github.bearl.worldmanagement.hook;

import java.util.Objects;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;

/** Multiverse-Core 5 adapter that removes its tracking while preserving the Bukkit runtime. */
public final class MultiverseWorldTrackingHook implements WorldTrackingHook {

    private final Gateway gateway;
    private final java.util.Set<String> pendingPersistence = java.util.concurrent.ConcurrentHashMap.newKeySet();

    MultiverseWorldTrackingHook(final Gateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public static WorldTrackingHook connect() {
        return new MultiverseWorldTrackingHook(new ApiGateway(MultiverseCoreApi.get()));
    }

    @Override
    public UntrackStatus untrack(final String worldName) {
        Objects.requireNonNull(worldName, "worldName");
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