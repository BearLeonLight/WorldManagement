package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldGeneratorReference;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

/** Paper-affine world operations. Implementations must run on the global world scheduler. */
public interface WorldRuntimeGateway {

    boolean canMutateWorldsNow();

    LifecycleWorld create(String worldName, WorldEnvironment environment, WorldType type, Long seed);

    default LifecycleWorld create(final WorldCreationRequest request) {
        final WorldCreationRequest required = Objects.requireNonNull(request, "request");
        if (required.generator().isPresent()) {
            throw new UnsupportedOperationException("This runtime gateway does not support custom generators.");
        }
        return create(
            required.worldName(),
            required.environment(),
            required.type(),
            required.seed().isPresent() ? required.seed().getAsLong() : null
        );
    }

    LoadResult loadUnmanaged(String worldName, WorldEnvironment environment);

    LoadResult load(WorldStorageGateway.LoadClaim claim);

    default LoadResult load(
        final WorldStorageGateway.LoadClaim claim,
        final Optional<WorldGeneratorReference> generator
    ) {
        Objects.requireNonNull(generator, "generator");
        return load(claim);
    }

    default LoadResult load(
        final WorldStorageGateway.LoadClaim claim,
        final WorldEnvironment environment,
        final Optional<WorldGeneratorReference> generator
    ) {
        Objects.requireNonNull(environment, "environment");
        return load(claim, generator);
    }

    boolean unload(LifecycleWorld world, boolean save);

    Optional<LifecycleWorld> findWorld(VerifiedWorldRef expected);

    Optional<LifecycleWorld> findLoadedWorldById(String worldId);

    Optional<LifecycleWorld> findWorldByPaperKey(String paperKey);

    default Optional<LifecycleWorld> primaryWorld() {
        return Optional.empty();
    }

    int playerCount(LifecycleWorld world);

    CompletableFuture<Boolean> teleportPlayersToWorld(LifecycleWorld source, LifecycleWorld target);

    default void cancelPendingOperations() {
    }

    record LifecycleWorld(
        WorldIdentitySnapshot identity,
        LifecycleCapability lifecycleCapability,
        String bukkitWorldName
    ) {

        public LifecycleWorld {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(lifecycleCapability, "lifecycleCapability");
            if (Objects.requireNonNull(bukkitWorldName, "bukkitWorldName").isBlank()) {
                throw new IllegalArgumentException("bukkitWorldName must not be blank.");
            }
        }

        public LifecycleWorld(
            final WorldIdentitySnapshot identity,
            final LifecycleCapability lifecycleCapability
        ) {
            this(identity, lifecycleCapability, identity.keyValue());
        }

        public String name() {
            return identity.keyValue();
        }

        public VerifiedWorldRef reference() {
            return new VerifiedWorldRef(name(), identity.paperKey(), identity.worldUuid());
        }
    }

    record LoadResult(Optional<LifecycleWorld> world, boolean newlyLoaded) {

        public LoadResult {
            world = Objects.requireNonNull(world, "world");
            if (newlyLoaded && world.isEmpty()) {
                throw new IllegalArgumentException("A newly loaded result must contain a world.");
            }
        }

        public static LoadResult loaded(final LifecycleWorld world, final boolean newlyLoaded) {
            return new LoadResult(Optional.of(Objects.requireNonNull(world, "world")), newlyLoaded);
        }

        public static LoadResult failed() {
            return new LoadResult(Optional.empty(), false);
        }
    }

    enum WorldEnvironment {
        NORMAL,
        NETHER,
        THE_END
    }

    enum WorldType {
        NORMAL,
        FLAT,
        AMPLIFIED,
        LARGE_BIOMES
    }
}