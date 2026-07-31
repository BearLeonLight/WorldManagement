package io.github.bearl.worldmanagement.world.lifecycle;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Paper world adapter. Callers must invoke it only from the global world scheduler. */
public final class PaperWorldRuntimeGateway implements WorldRuntimeGateway {

    private final Plugin plugin;
    private final LoadedWorldCatalog loadedWorldCatalog;
    private final Set<CompletableFuture<Boolean>> pendingTeleports = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean acceptingOperations = new AtomicBoolean(true);

    public PaperWorldRuntimeGateway(final Plugin plugin, final LoadedWorldCatalog loadedWorldCatalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.loadedWorldCatalog = Objects.requireNonNull(loadedWorldCatalog, "loadedWorldCatalog");
    }

    @Override
    public boolean canMutateWorldsNow() {
        return !Bukkit.isTickingWorlds();
    }

    @Override
    public LifecycleWorld create(
        final String worldName,
        final WorldEnvironment environment,
        final WorldType type,
        final Long seed
    ) {
        final WorldCreator creator = WorldCreator.ofKey(NamespacedKey.minecraft(worldName))
            .environment(toPaperEnvironment(Objects.requireNonNull(environment, "environment")))
            .type(toPaperType(Objects.requireNonNull(type, "type")));
        if (seed != null) {
            creator.seed(seed);
        }
        return fromPaperWorld(Bukkit.createWorld(creator));
    }

    @Override
    public LoadResult loadUnmanaged(final String worldName, final WorldEnvironment environment) {
        final NamespacedKey key = NamespacedKey.minecraft(Objects.requireNonNull(worldName, "worldName"));
        final World existing = Bukkit.getWorld(key);
        if (existing != null) {
            return LoadResult.loaded(fromPaperWorld(existing), false);
        }
        final World loaded = Bukkit.createWorld(WorldCreator.ofKey(key)
            .environment(toPaperEnvironment(Objects.requireNonNull(environment, "environment"))));
        return loaded == null ? LoadResult.failed() : LoadResult.loaded(fromPaperWorld(loaded), true);
    }

    @Override
    public LoadResult load(final WorldStorageGateway.LoadClaim claim) {
        final WorldStorageGateway.LoadClaim requiredClaim = Objects.requireNonNull(claim, "claim");
        final NamespacedKey key = NamespacedKey.fromString(requiredClaim.world().paperKey());
        if (key == null) {
            return LoadResult.failed();
        }
        final World existing = Bukkit.getWorld(key);
        if (existing != null) {
            return LoadResult.loaded(fromPaperWorld(existing), false);
        }
        final World loaded = Bukkit.createWorld(WorldCreator.ofKey(key));
        return loaded == null ? LoadResult.failed() : LoadResult.loaded(fromPaperWorld(loaded), true);
    }

    @Override
    public boolean unload(final LifecycleWorld lifecycleWorld, final boolean save) {
        final LifecycleWorld requiredWorld = Objects.requireNonNull(lifecycleWorld, "lifecycleWorld");
        final NamespacedKey key = NamespacedKey.fromString(requiredWorld.identity().paperKey());
        if (key == null) {
            return false;
        }
        final World world = Bukkit.getWorld(key);
        return world != null
            && world.getUID().equals(requiredWorld.identity().worldUuid())
            && Bukkit.unloadWorld(world, save);
    }

    @Override
    public Optional<LifecycleWorld> findWorld(final io.github.bearl.worldmanagement.world.VerifiedWorldRef expected) {
        final io.github.bearl.worldmanagement.world.VerifiedWorldRef requiredExpected = Objects.requireNonNull(
            expected, "expected"
        );
        final NamespacedKey key = NamespacedKey.fromString(requiredExpected.paperKey());
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Bukkit.getWorld(key)).map(PaperWorldRuntimeGateway::fromPaperWorld);
    }

    @Override
    public Optional<LifecycleWorld> findLoadedWorldById(final String worldId) {
        return loadedWorldCatalog.findUniqueByWorldId(Objects.requireNonNull(worldId, "worldId"));
    }

    @Override
    public Optional<LifecycleWorld> findWorldByPaperKey(final String paperKey) {
        final NamespacedKey key = NamespacedKey.fromString(Objects.requireNonNull(paperKey, "paperKey"));
        return key == null
            ? Optional.empty()
            : Optional.ofNullable(Bukkit.getWorld(key)).map(PaperWorldRuntimeGateway::fromPaperWorld);
    }

    @Override
    public Optional<LifecycleWorld> primaryWorld() {
        return Bukkit.getWorlds().stream().findFirst().map(PaperWorldRuntimeGateway::fromPaperWorld);
    }

    @Override
    public int playerCount(final LifecycleWorld lifecycleWorld) {
        return resolveExact(Objects.requireNonNull(lifecycleWorld, "lifecycleWorld"))
            .map(World::getPlayerCount)
            .orElse(0);
    }

    @Override
    public CompletableFuture<Boolean> teleportPlayersToWorld(
        final LifecycleWorld sourceWorld,
        final LifecycleWorld targetWorld
    ) {
        if (!acceptingOperations.get()) {
            return CompletableFuture.completedFuture(false);
        }
        final LifecycleWorld requiredSource = Objects.requireNonNull(sourceWorld, "sourceWorld");
        final LifecycleWorld requiredTarget = Objects.requireNonNull(targetWorld, "targetWorld");
        final World source = resolveExact(requiredSource).orElse(null);
        final World target = resolveExact(requiredTarget).orElse(null);
        if (source == null || target == null || source.getUID().equals(target.getUID())) {
            return CompletableFuture.completedFuture(false);
        }
        final Location destination = target.getSpawnLocation();
        final List<CompletableFuture<Boolean>> teleports = source.getPlayers().stream()
            .map(player -> teleport(player, destination, requiredSource.reference(), requiredTarget.reference()))
            .toList();
        return CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
            .thenApply(unused -> teleports.stream().allMatch(teleport -> teleport.getNow(false)));
    }

    private CompletableFuture<Boolean> teleport(
        final Player player,
        final Location destination,
        final io.github.bearl.worldmanagement.world.VerifiedWorldRef expectedSource,
        final io.github.bearl.worldmanagement.world.VerifiedWorldRef expectedTarget
    ) {
        final CompletableFuture<Boolean> completion = new CompletableFuture<>();
        pendingTeleports.add(completion);
        completion.whenComplete((result, failure) -> pendingTeleports.remove(completion));
        if (!acceptingOperations.get()) {
            completion.complete(false);
            return completion;
        }
        final boolean scheduled = player.getScheduler().execute(plugin, () -> {
            if (!player.getWorld().getUID().equals(expectedSource.worldUuid())
                || destination.getWorld() == null
                || !destination.getWorld().getUID().equals(expectedTarget.worldUuid())) {
                completion.complete(false);
                return;
            }
            player.teleportAsync(destination.clone())
                .whenComplete((teleported, failure) -> completion.complete(
                    failure == null && Boolean.TRUE.equals(teleported)
                ));
        },
            () -> completion.complete(false), 1L);
        if (!scheduled) {
            completion.complete(false);
        }
        return completion;
    }

    private Optional<World> resolveExact(final LifecycleWorld lifecycleWorld) {
        final NamespacedKey key = NamespacedKey.fromString(lifecycleWorld.identity().paperKey());
        if (key == null) {
            return Optional.empty();
        }
        final World world = Bukkit.getWorld(key);
        return world != null && world.getUID().equals(lifecycleWorld.identity().worldUuid())
            ? Optional.of(world) : Optional.empty();
    }

    @Override
    public void cancelPendingOperations() {
        acceptingOperations.set(false);
        pendingTeleports.forEach(completion -> completion.complete(false));
    }

    private static LifecycleWorld fromPaperWorld(final World world) {
        if (world == null) {
            return null;
        }
        final PaperWorldIdentity identity = PaperWorldIdentity.capture(world);
        return new LifecycleWorld(
            identity.snapshot(), identity.lifecycleCapability(), identity.bukkitWorldName()
        );
    }

    private static World.Environment toPaperEnvironment(final WorldEnvironment environment) {
        return switch (environment) {
            case NORMAL -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case THE_END -> World.Environment.THE_END;
        };
    }

    private static org.bukkit.WorldType toPaperType(final WorldType type) {
        return switch (type) {
            case NORMAL -> org.bukkit.WorldType.NORMAL;
            case FLAT -> org.bukkit.WorldType.FLAT;
            case AMPLIFIED -> org.bukkit.WorldType.AMPLIFIED;
            case LARGE_BIOMES -> org.bukkit.WorldType.LARGE_BIOMES;
        };
    }
}