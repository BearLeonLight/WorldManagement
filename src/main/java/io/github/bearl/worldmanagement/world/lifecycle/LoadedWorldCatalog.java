package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically published loaded-world identities captured from Paper events. */
public final class LoadedWorldCatalog {

    private final AtomicReference<State> state = new AtomicReference<>(new State(Map.of(), Map.of()));

    public void replaceAll(final Collection<WorldRuntimeGateway.LifecycleWorld> worlds) {
        final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> replacement = new LinkedHashMap<>();
        for (final WorldRuntimeGateway.LifecycleWorld world : Objects.requireNonNull(worlds, "worlds")) {
            final WorldRuntimeGateway.LifecycleWorld requiredWorld = Objects.requireNonNull(world, "world");
            if (replacement.put(requiredWorld.reference(), requiredWorld) != null) {
                throw new IllegalArgumentException("Duplicate loaded world identity: " + requiredWorld.reference());
            }
        }
        state.updateAndGet(current -> new State(Map.copyOf(replacement), nextGenerations(current, replacement.values())));
    }

    public Observation loaded(final WorldRuntimeGateway.LifecycleWorld world) {
        final WorldRuntimeGateway.LifecycleWorld requiredWorld = Objects.requireNonNull(world, "world");
        return update(requiredWorld.name(), current -> {
            final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> replacement =
                new LinkedHashMap<>(current.worlds());
            replacement.put(requiredWorld.reference(), requiredWorld);
            return Map.copyOf(replacement);
        });
    }

    public Observation unloaded(final VerifiedWorldRef world) {
        final VerifiedWorldRef requiredWorld = Objects.requireNonNull(world, "world");
        return update(requiredWorld.worldId(), current -> {
            final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> replacement =
                new LinkedHashMap<>(current.worlds());
            replacement.remove(requiredWorld);
            return Map.copyOf(replacement);
        });
    }

    public Optional<WorldRuntimeGateway.LifecycleWorld> findUniqueByWorldId(final String worldId) {
        final String requiredWorldId = Objects.requireNonNull(worldId, "worldId");
        final var matches = state.get().worlds().values().stream()
            .filter(world -> world.name().equals(requiredWorldId))
            .limit(2)
            .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public boolean isCurrent(final Observation observation) {
        final Observation requiredObservation = Objects.requireNonNull(observation, "observation");
        return state.get().generations().getOrDefault(requiredObservation.worldId(), 0L)
            == requiredObservation.generation();
    }

    public Optional<Observation> currentObservation(final String worldId) {
        final String requiredWorldId = Objects.requireNonNull(worldId, "worldId");
        final State current = state.get();
        final long matches = current.worlds().values().stream()
            .filter(world -> world.name().equals(requiredWorldId))
            .limit(2)
            .count();
        if (matches != 1L) {
            return Optional.empty();
        }
        final Long generation = current.generations().get(requiredWorldId);
        return generation == null ? Optional.empty() : Optional.of(new Observation(requiredWorldId, generation));
    }

    private Observation update(
        final String worldId,
        final java.util.function.Function<State, Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld>> mutation
    ) {
        final AtomicReference<Observation> observation = new AtomicReference<>();
        state.updateAndGet(current -> {
            final long generation = current.generations().getOrDefault(worldId, 0L) + 1L;
            final Map<String, Long> generations = new LinkedHashMap<>(current.generations());
            generations.put(worldId, generation);
            observation.set(new Observation(worldId, generation));
            return new State(mutation.apply(current), Map.copyOf(generations));
        });
        return observation.get();
    }

    private static Map<String, Long> nextGenerations(
        final State current,
        final Collection<WorldRuntimeGateway.LifecycleWorld> worlds
    ) {
        final Map<String, Long> generations = new LinkedHashMap<>(current.generations());
        current.worlds().values().stream().map(WorldRuntimeGateway.LifecycleWorld::name)
            .forEach(worldId -> generations.merge(worldId, 1L, Long::sum));
        worlds.stream().map(WorldRuntimeGateway.LifecycleWorld::name)
            .forEach(worldId -> generations.merge(worldId, 1L, Long::sum));
        return Map.copyOf(generations);
    }

    public record Observation(String worldId, long generation) {
        public Observation {
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    private record State(
        Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> worlds,
        Map<String, Long> generations
    ) {
    }
}