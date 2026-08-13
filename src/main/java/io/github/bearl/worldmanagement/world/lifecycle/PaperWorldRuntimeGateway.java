package io.github.bearl.worldmanagement.world.lifecycle;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private final WorldGeneratorCatalog generators;
    private final RuntimeResolver runtimeResolver;
    private final Object admissionLock = new Object();
    private final Set<PendingLifecycleTeleport> pendingTeleports = ConcurrentHashMap.newKeySet();
    private boolean acceptingOperations = true;

    public PaperWorldRuntimeGateway(final Plugin plugin, final LoadedWorldCatalog loadedWorldCatalog) {
        this(plugin, loadedWorldCatalog, new WorldGeneratorCatalog(
            plugin.getServer().getPluginManager(), plugin.getLogger()::warning
        ));
    }

    public PaperWorldRuntimeGateway(
        final Plugin plugin,
        final LoadedWorldCatalog loadedWorldCatalog,
        final WorldGeneratorCatalog generators
    ) {
        this(plugin, loadedWorldCatalog, generators, new BukkitRuntimeResolver());
    }

    PaperWorldRuntimeGateway(
        final Plugin plugin,
        final LoadedWorldCatalog loadedWorldCatalog,
        final WorldGeneratorCatalog generators,
        final RuntimeResolver runtimeResolver
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.loadedWorldCatalog = Objects.requireNonNull(loadedWorldCatalog, "loadedWorldCatalog");
        this.generators = Objects.requireNonNull(generators, "generators");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
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
    public LifecycleWorld create(final WorldCreationRequest request) {
        final WorldCreationRequest required = Objects.requireNonNull(request, "request");
        final WorldCreator creator = worldCreator(required);
        if (required.generator().isPresent()) {
            final org.bukkit.generator.ChunkGenerator generator = generators.resolve(
                required.worldName(), required.generator().orElseThrow()
            ).orElse(null);
            if (generator == null) {
                return null;
            }
            creator.generator(generator);
        }
        if (required.biomeProvider().isPresent()) {
            final org.bukkit.generator.BiomeProvider biomeProvider = generators.resolveBiomeProvider(
                required.worldName(), required.biomeProvider().orElseThrow()
            ).orElse(null);
            if (biomeProvider == null) {
                return null;
            }
            creator.biomeProvider(biomeProvider);
        }
        final LifecycleWorld created = fromPaperWorld(Bukkit.createWorld(creator));
        return created != null && (required.generator().isPresent() || required.biomeProvider().isPresent())
            ? managed(created)
            : created;
    }

    WorldCreator worldCreator(final WorldCreationRequest request) {
        final WorldCreationRequest required = Objects.requireNonNull(request, "request");
        final WorldCreator creator = WorldCreator.ofKey(NamespacedKey.minecraft(required.worldName()))
            .environment(toPaperEnvironment(required.environment()))
            .type(toPaperType(required.type()))
            .generateStructures(required.generateStructures())
            .bonusChest(required.bonusChest());
        required.seed().ifPresent(creator::seed);
        required.generatorSettings().ifPresent(creator::generatorSettings);
        required.forcedSpawnPosition().ifPresent(spawn -> creator.forcedSpawnPosition(
            io.papermc.paper.math.Position.fine(spawn.x(), spawn.y(), spawn.z()),
            spawn.yaw(),
            spawn.pitch()
        ));
        return creator;
    }

    @Override
    public LoadResult loadUnmanaged(final String worldName, final WorldEnvironment environment) {
        final NamespacedKey key = NamespacedKey.minecraft(Objects.requireNonNull(worldName, "worldName"));
        final World existing = runtimeResolver.world(key);
        if (existing != null) {
            return LoadResult.loaded(fromPaperWorld(existing), false);
        }
        final World loaded = Bukkit.createWorld(WorldCreator.ofKey(key)
            .environment(toPaperEnvironment(Objects.requireNonNull(environment, "environment"))));
        return loaded == null ? LoadResult.failed() : LoadResult.loaded(fromPaperWorld(loaded), true);
    }

    @Override
    public LoadResult load(final WorldStorageGateway.LoadClaim claim) {
        return load(claim, Optional.empty(), Optional.empty());
    }

    @Override
    public LoadResult load(
        final WorldStorageGateway.LoadClaim claim,
        final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> generatorReference
    ) {
        return load(claim, WorldEnvironment.NORMAL, generatorReference, Optional.empty());
    }

    private LoadResult load(
        final WorldStorageGateway.LoadClaim claim,
        final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> generatorReference,
        final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> biomeProviderReference
    ) {
        return load(claim, WorldEnvironment.NORMAL, generatorReference, biomeProviderReference);
    }

    @Override
    public LoadResult load(
        final WorldStorageGateway.LoadClaim claim,
        final WorldEnvironment environment,
        final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> generatorReference
    ) {
        return load(claim, environment, generatorReference, Optional.empty());
    }

    @Override
    public LoadResult load(
        final WorldStorageGateway.LoadClaim claim,
        final WorldEnvironment environment,
        final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> generatorReference,
        final Optional<io.github.bearl.worldmanagement.world.WorldGeneratorReference> biomeProviderReference
    ) {
        final WorldStorageGateway.LoadClaim requiredClaim = Objects.requireNonNull(claim, "claim");
        final NamespacedKey key = NamespacedKey.fromString(requiredClaim.world().paperKey());
        if (key == null) {
            return LoadResult.failed();
        }
        final World existing = runtimeResolver.world(key);
        if (existing != null) {
            if (Objects.requireNonNull(generatorReference, "generatorReference").isPresent()
                || Objects.requireNonNull(biomeProviderReference, "biomeProviderReference").isPresent()) {
                return LoadResult.failed();
            }
            return LoadResult.loaded(fromPaperWorld(existing), false);
        }
        final WorldCreator creator = WorldCreator.ofKey(key)
            .environment(toPaperEnvironment(Objects.requireNonNull(environment, "environment")));
        if (generatorReference.isPresent()) {
            final org.bukkit.generator.ChunkGenerator generator = generators.resolve(
                requiredClaim.world().worldId(), generatorReference.orElseThrow()
            ).orElse(null);
            if (generator == null) {
                return LoadResult.failed();
            }
            creator.generator(generator);
        }
        if (biomeProviderReference.isPresent()) {
            final org.bukkit.generator.BiomeProvider biomeProvider = generators.resolveBiomeProvider(
                requiredClaim.world().worldId(), biomeProviderReference.orElseThrow()
            ).orElse(null);
            if (biomeProvider == null) {
                return LoadResult.failed();
            }
            creator.biomeProvider(biomeProvider);
        }
        final World loaded = Bukkit.createWorld(creator);
        if (loaded == null) {
            return LoadResult.failed();
        }
        final LifecycleWorld captured = fromPaperWorld(loaded);
        return LoadResult.loaded(
            generatorReference.isPresent() || biomeProviderReference.isPresent() ? managed(captured) : captured,
            true
        );
    }

    @Override
    public boolean unload(final LifecycleWorld lifecycleWorld, final boolean save) {
        final LifecycleWorld requiredWorld = Objects.requireNonNull(lifecycleWorld, "lifecycleWorld");
        final NamespacedKey key = NamespacedKey.fromString(requiredWorld.identity().paperKey());
        if (key == null) {
            return false;
        }
        final World world = runtimeResolver.world(key);
        return world != null
            && world.getUID().equals(requiredWorld.identity().worldUuid())
            && Bukkit.unloadWorld(world, save);
    }

    @Override
    public boolean save(final LifecycleWorld lifecycleWorld) {
        final LifecycleWorld requiredWorld = Objects.requireNonNull(lifecycleWorld, "lifecycleWorld");
        final NamespacedKey key = NamespacedKey.fromString(requiredWorld.identity().paperKey());
        if (key == null) {
            return false;
        }
        final World world = runtimeResolver.world(key);
        if (world == null || !world.getUID().equals(requiredWorld.identity().worldUuid())) {
            return false;
        }
        try {
            world.save();
            return true;
        } catch (final RuntimeException exception) {
            return false;
        }
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
        return Optional.ofNullable(runtimeResolver.world(key)).map(PaperWorldRuntimeGateway::fromPaperWorld);
    }

    @Override
    public Optional<LifecycleWorld> findLoadedWorldById(final String worldId) {
        return loadedWorldCatalog.findUniqueByWorldId(Objects.requireNonNull(worldId, "worldId"));
    }

    @Override
    public Optional<LifecycleWorld> findLoadedWorldByUuid(final java.util.UUID worldUuid) {
        return loadedWorldCatalog.findUniqueByWorldUuid(Objects.requireNonNull(worldUuid, "worldUuid"));
    }

    @Override
    public Optional<LifecycleWorld> findWorldByPaperKey(final String paperKey) {
        final NamespacedKey key = NamespacedKey.fromString(Objects.requireNonNull(paperKey, "paperKey"));
        return key == null
            ? Optional.empty()
            : Optional.ofNullable(runtimeResolver.world(key)).map(PaperWorldRuntimeGateway::fromPaperWorld);
    }

    @Override
    public Optional<LifecycleWorld> primaryWorld() {
        return runtimeResolver.primaryWorld().map(PaperWorldRuntimeGateway::fromPaperWorld);
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
        synchronized (admissionLock) {
            if (!acceptingOperations) {
                return CompletableFuture.completedFuture(false);
            }
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
        final PendingLifecycleTeleport operation = new PendingLifecycleTeleport();
        synchronized (admissionLock) {
            if (!acceptingOperations) {
                operation.completeBeforeSubmission(false);
                return operation.result();
            }
            pendingTeleports.add(operation);
            operation.drain().whenComplete((unused, failure) -> pendingTeleports.remove(operation));
        }
        final boolean scheduled;
        try {
            scheduled = player.getScheduler().execute(plugin, () -> {
                if (!player.getWorld().getUID().equals(expectedSource.worldUuid())
                    || destination.getWorld() == null
                    || !destination.getWorld().getUID().equals(expectedTarget.worldUuid())) {
                    completeBeforeSubmission(operation, false);
                    return;
                }
                submitPaperTeleport(operation, player, destination);
            },
                () -> completeBeforeSubmission(operation, false), 1L);
        } catch (final RuntimeException failure) {
            completeBeforeSubmission(operation, false);
            return operation.result();
        }
        if (!scheduled) {
            completeBeforeSubmission(operation, false);
        }
        return operation.result();
    }

    private void submitPaperTeleport(
        final PendingLifecycleTeleport operation,
        final Player player,
        final Location destination
    ) {
        synchronized (admissionLock) {
            if (!acceptingOperations) {
                operation.completeBeforeSubmission(false);
                return;
            }
            try {
                final CompletableFuture<Boolean> paperTeleport = Objects.requireNonNull(
                    player.teleportAsync(destination.clone()), "Paper teleport future"
                );
                operation.submitted(paperTeleport);
            } catch (final RuntimeException failure) {
                operation.completeBeforeSubmission(false);
            }
        }
    }

    private void completeBeforeSubmission(final PendingLifecycleTeleport operation, final boolean value) {
        synchronized (admissionLock) {
            operation.completeBeforeSubmission(false);
        }
    }

    private Optional<World> resolveExact(final LifecycleWorld lifecycleWorld) {
        final NamespacedKey key = NamespacedKey.fromString(lifecycleWorld.identity().paperKey());
        if (key == null) {
            return Optional.empty();
        }
        final World world = runtimeResolver.world(key);
        return world != null && world.getUID().equals(lifecycleWorld.identity().worldUuid())
            ? Optional.of(world) : Optional.empty();
    }

    @Override
    public CompletableFuture<Void> beginShutdown() {
        final PendingLifecycleTeleport[] pending;
        synchronized (admissionLock) {
            acceptingOperations = false;
            pending = pendingTeleports.toArray(PendingLifecycleTeleport[]::new);
            for (final PendingLifecycleTeleport operation : pending) {
                operation.rejectForShutdown();
            }
        }
        return CompletableFuture.allOf(java.util.Arrays.stream(pending)
            .map(PendingLifecycleTeleport::drain)
            .toArray(CompletableFuture[]::new));
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

    private static LifecycleWorld managed(final LifecycleWorld world) {
        return new LifecycleWorld(
            world.identity(), io.github.bearl.worldmanagement.world.LifecycleCapability.MANAGED,
            world.bukkitWorldName()
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

    interface RuntimeResolver {
        World world(NamespacedKey key);

        Optional<World> primaryWorld();
    }

    private static final class BukkitRuntimeResolver implements RuntimeResolver {
        @Override
        public World world(final NamespacedKey key) {
            return Bukkit.getWorld(key);
        }

        @Override
        public Optional<World> primaryWorld() {
            return Bukkit.getWorlds().stream().findFirst();
        }
    }

    /** Tracks a lifecycle teleport with separate command result and Paper drain futures. */
    private static final class PendingLifecycleTeleport {

        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final CompletableFuture<Void> drain = new CompletableFuture<>();
        private boolean submitted;

        CompletableFuture<Boolean> result() {
            return result;
        }

        CompletableFuture<Void> drain() {
            return drain;
        }

        void completeBeforeSubmission(final boolean value) {
            if (submitted) {
                return;
            }
            result.complete(value);
            drain.complete(null);
        }

        void submitted(final CompletableFuture<Boolean> paperTeleport) {
            submitted = true;
            paperTeleport.whenComplete((teleported, failure) -> {
                result.complete(failure == null && Boolean.TRUE.equals(teleported));
                drain.complete(null);
            });
        }

        void rejectForShutdown() {
            result.complete(false);
            if (!submitted) {
                drain.complete(null);
            }
        }
    }
}