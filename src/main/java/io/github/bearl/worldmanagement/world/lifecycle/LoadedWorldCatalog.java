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

    private final AtomicReference<State> state = new AtomicReference<>(new State(Map.of(), Map.of(), Map.of(), 0L));

    public void replaceAll(final Collection<WorldRuntimeGateway.LifecycleWorld> worlds) {
        final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> replacement = new LinkedHashMap<>();
        for (final WorldRuntimeGateway.LifecycleWorld world : Objects.requireNonNull(worlds, "worlds")) {
            final WorldRuntimeGateway.LifecycleWorld requiredWorld = Objects.requireNonNull(world, "world");
            if (replacement.put(requiredWorld.reference(), requiredWorld) != null) {
                throw new IllegalArgumentException("Duplicate loaded world identity: " + requiredWorld.reference());
            }
        }
        state.updateAndGet(current -> replacementState(current, replacement));
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
        return Optional.ofNullable(state.get().uniqueWorldsById().get(requiredWorldId));
    }

    public Optional<WorldRuntimeGateway.LifecycleWorld> findExactUnique(final VerifiedWorldRef world) {
        final VerifiedWorldRef requiredWorld = Objects.requireNonNull(world, "world");
        return Optional.ofNullable(state.get().uniqueWorldsById().get(requiredWorld.worldId()))
            .filter(runtime -> runtime.reference().equals(requiredWorld));
    }

    public java.util.List<String> uniqueWorldIds() {
        return state.get().uniqueWorldsById().keySet().stream()
            .sorted()
            .toList();
    }

    public boolean isCurrent(final Observation observation) {
        final Observation requiredObservation = Objects.requireNonNull(observation, "observation");
        return state.get().generations().getOrDefault(requiredObservation.worldId(), 0L)
            == requiredObservation.generation();
    }

    public Optional<Observation> currentObservation(final String worldId) {
        final String requiredWorldId = Objects.requireNonNull(worldId, "worldId");
        final State current = state.get();
        if (!current.uniqueWorldsById().containsKey(requiredWorldId)) {
            return Optional.empty();
        }
        final Long generation = current.generations().get(requiredWorldId);
        return generation == null ? Optional.empty() : Optional.of(new Observation(requiredWorldId, generation));
    }

    public void retire(final Observation observation) {
        final Observation requiredObservation = Objects.requireNonNull(observation, "observation");
        state.updateAndGet(current -> {
            if (!Objects.equals(current.generations().get(requiredObservation.worldId()), requiredObservation.generation())
                || current.worlds().values().stream()
                    .anyMatch(world -> world.name().equals(requiredObservation.worldId()))) {
                return current;
            }
            final Map<String, Long> generations = new LinkedHashMap<>(current.generations());
            generations.remove(requiredObservation.worldId());
            return new State(
                current.worlds(), current.uniqueWorldsById(), Map.copyOf(generations), current.generationSequence()
            );
        });
    }

    int retainedObservationCount() {
        return state.get().generations().size();
    }

    private Observation update(
        final String worldId,
        final java.util.function.Function<State, Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld>> mutation
    ) {
        final AtomicReference<Observation> observation = new AtomicReference<>();
        state.updateAndGet(current -> {
            final long generation = current.generationSequence() + 1L;
            final Map<String, Long> generations = new LinkedHashMap<>(current.generations());
            generations.put(worldId, generation);
            observation.set(new Observation(worldId, generation));
            final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> worlds = mutation.apply(current);
            return new State(worlds, uniqueWorldsById(worlds), Map.copyOf(generations), generation);
        });
        return observation.get();
    }

    private static State replacementState(
        final State current,
        final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> worlds
    ) {
        long generation = current.generationSequence();
        final Map<String, Long> generations = new LinkedHashMap<>();
        for (final String worldId : worlds.values().stream()
            .map(WorldRuntimeGateway.LifecycleWorld::name)
            .distinct()
            .toList()) {
            generations.put(worldId, ++generation);
        }
        final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> immutableWorlds = Map.copyOf(worlds);
        return new State(
            immutableWorlds, uniqueWorldsById(immutableWorlds), Map.copyOf(generations), generation
        );
    }

    private static Map<String, WorldRuntimeGateway.LifecycleWorld> uniqueWorldsById(
        final Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> worlds
    ) {
        final Map<String, WorldRuntimeGateway.LifecycleWorld> unique = new LinkedHashMap<>();
        final java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (final WorldRuntimeGateway.LifecycleWorld world : worlds.values()) {
            if (ambiguous.contains(world.name())) {
                continue;
            }
            if (unique.putIfAbsent(world.name(), world) != null) {
                unique.remove(world.name());
                ambiguous.add(world.name());
            }
        }
        return Map.copyOf(unique);
    }

    public record Observation(String worldId, long generation) {
        public Observation {
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    private record State(
        Map<VerifiedWorldRef, WorldRuntimeGateway.LifecycleWorld> worlds,
        Map<String, WorldRuntimeGateway.LifecycleWorld> uniqueWorldsById,
        Map<String, Long> generations,
        long generationSequence
    ) {
    }
}