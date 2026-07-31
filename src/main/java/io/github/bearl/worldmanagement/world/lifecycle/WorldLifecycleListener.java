package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.world.WorldLoadState;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.BiConsumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Reapplies persisted UNLOADED intent after another plugin loads a managed world. */
public final class WorldLifecycleListener implements Listener {

    private static final Duration NEXT_TICK = Duration.ofMillis(50);

    private final WorldManagementService metadataService;
    private final Consumer<WorldRuntimeGateway.LifecycleWorld> loadedWorldObserver;
    private final Function<String, CompletableFuture<Void>> reconcileWorld;
    private final BiFunction<LoadedWorldCatalog.Observation, WorldRuntimeGateway.LifecycleWorld, CompletableFuture<Void>> synchronizeIdentity;
    private final BiConsumer<org.bukkit.World, WorldRuntimeGateway.LifecycleWorld> isolateExistingPlayers;
    private final WorldThreadDispatcher dispatcher;
    private final LoadedWorldCatalog loadedWorldCatalog;

    public WorldLifecycleListener(
        final WorldManagementService metadataService,
        final WorldLifecycleCoordinator lifecycleCoordinator,
        final WorldLifecycleReconciler reconciler,
        final WorldIdentityAutoSynchronizer identityAutoSynchronizer,
        final BiConsumer<org.bukkit.World, WorldRuntimeGateway.LifecycleWorld> isolateExistingPlayers,
        final WorldThreadDispatcher dispatcher,
        final LoadedWorldCatalog loadedWorldCatalog
    ) {
        this(
            metadataService,
            Objects.requireNonNull(lifecycleCoordinator, "lifecycleCoordinator")::worldLoaded,
            Objects.requireNonNull(reconciler, "reconciler")::reconcileWorld,
            Objects.requireNonNull(identityAutoSynchronizer, "identityAutoSynchronizer")::synchronize,
            isolateExistingPlayers,
            dispatcher,
            loadedWorldCatalog
        );
    }

    WorldLifecycleListener(
        final WorldManagementService metadataService,
        final Consumer<WorldRuntimeGateway.LifecycleWorld> loadedWorldObserver,
        final Function<String, CompletableFuture<Void>> reconcileWorld,
        final WorldThreadDispatcher dispatcher,
        final LoadedWorldCatalog loadedWorldCatalog
    ) {
        this(
            metadataService, loadedWorldObserver, reconcileWorld,
            (observation, observed) -> CompletableFuture.completedFuture(null),
            (world, observed) -> { },
            dispatcher, loadedWorldCatalog
        );
    }

    WorldLifecycleListener(
        final WorldManagementService metadataService,
        final Consumer<WorldRuntimeGateway.LifecycleWorld> loadedWorldObserver,
        final Function<String, CompletableFuture<Void>> reconcileWorld,
        final BiFunction<LoadedWorldCatalog.Observation, WorldRuntimeGateway.LifecycleWorld, CompletableFuture<Void>> synchronizeIdentity,
        final WorldThreadDispatcher dispatcher,
        final LoadedWorldCatalog loadedWorldCatalog
    ) {
        this(
            metadataService, loadedWorldObserver, reconcileWorld, synchronizeIdentity,
            (world, observed) -> { }, dispatcher, loadedWorldCatalog
        );
    }

    WorldLifecycleListener(
        final WorldManagementService metadataService,
        final Consumer<WorldRuntimeGateway.LifecycleWorld> loadedWorldObserver,
        final Function<String, CompletableFuture<Void>> reconcileWorld,
        final BiFunction<LoadedWorldCatalog.Observation, WorldRuntimeGateway.LifecycleWorld, CompletableFuture<Void>> synchronizeIdentity,
        final BiConsumer<org.bukkit.World, WorldRuntimeGateway.LifecycleWorld> isolateExistingPlayers,
        final WorldThreadDispatcher dispatcher,
        final LoadedWorldCatalog loadedWorldCatalog
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.loadedWorldObserver = Objects.requireNonNull(loadedWorldObserver, "loadedWorldObserver");
        this.reconcileWorld = Objects.requireNonNull(reconcileWorld, "reconcileWorld");
        this.synchronizeIdentity = Objects.requireNonNull(synchronizeIdentity, "synchronizeIdentity");
        this.isolateExistingPlayers = Objects.requireNonNull(isolateExistingPlayers, "isolateExistingPlayers");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.loadedWorldCatalog = Objects.requireNonNull(loadedWorldCatalog, "loadedWorldCatalog");
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        final PaperWorldIdentity identity = PaperWorldIdentity.capture(event.getWorld());
        final String worldName = identity.snapshot().keyValue();
        final WorldRuntimeGateway.LifecycleWorld observed = new WorldRuntimeGateway.LifecycleWorld(
            identity.snapshot(), identity.lifecycleCapability(), identity.bukkitWorldName()
        );
        final LoadedWorldCatalog.Observation observation = loadedWorldCatalog.loaded(observed);
        loadedWorldObserver.accept(observed);
        metadataService.classifyLoadedIdentity(identity.snapshot(), identity.lifecycleCapability()).thenRun(() -> {
            if (!isCurrent(observation, observed)) {
                return;
            }
            final boolean shouldSynchronize = metadataService.managedWorld(worldName)
                .map(metadata -> metadata.identityState()
                    == io.github.bearl.worldmanagement.world.IdentityVerificationState.SYNC_PENDING)
                .orElse(false);
            if (shouldSynchronize) {
                synchronizeIdentity.apply(observation, observed);
            }
            final boolean shouldIsolate = metadataService.managedWorld(worldName)
                .map(metadata -> metadata.identityState()
                    != io.github.bearl.worldmanagement.world.IdentityVerificationState.VERIFIED)
                .orElse(false);
            if (shouldIsolate) {
                dispatcher.executeGlobal(() -> {
                    if (isCurrent(observation, observed)) {
                        isolateExistingPlayers.accept(event.getWorld(), observed);
                    }
                });
            }
            final boolean shouldUnload = metadataService.managedWorld(worldName)
                .map(metadata -> metadata.desiredState() == WorldLoadState.UNLOADED)
                .orElse(false);
            if (shouldUnload) {
                dispatcher.executeGlobalLater(NEXT_TICK, () -> {
                    if (isCurrent(observation, observed)) {
                        reconcileWorld.apply(worldName);
                    }
                });
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(final WorldUnloadEvent event) {
        final PaperWorldIdentity identity = PaperWorldIdentity.capture(event.getWorld());
        final WorldRuntimeGateway.LifecycleWorld observed = new WorldRuntimeGateway.LifecycleWorld(
            identity.snapshot(), identity.lifecycleCapability(), identity.bukkitWorldName()
        );
        final LoadedWorldCatalog.Observation observation = loadedWorldCatalog.unloaded(observed.reference());
        final boolean shouldLoad = metadataService.managedWorld(observed.name())
            .map(metadata -> metadata.desiredState() == WorldLoadState.LOADED)
            .orElse(false);
        if (shouldLoad) {
            dispatcher.executeGlobalLater(NEXT_TICK, () -> {
                if (loadedWorldCatalog.isCurrent(observation)
                    && loadedWorldCatalog.findUniqueByWorldId(observed.name()).isEmpty()) {
                    reconcileWorld.apply(observed.name());
                }
            });
        }
    }

    private boolean isCurrent(
        final LoadedWorldCatalog.Observation observation,
        final WorldRuntimeGateway.LifecycleWorld observed
    ) {
        return loadedWorldCatalog.isCurrent(observation)
            && loadedWorldCatalog.findUniqueByWorldId(observed.name())
                .map(current -> current.reference().equals(observed.reference()))
                .orElse(false);
    }
}