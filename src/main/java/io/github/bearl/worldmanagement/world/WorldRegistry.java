package io.github.bearl.worldmanagement.world;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe in-memory read model for managed world metadata. */
public final class WorldRegistry {

    private final AtomicReference<RegistrySnapshot> worlds = new AtomicReference<>(RegistrySnapshot.empty());

    public Optional<WorldMetadata> find(final String worldName) {
        return Optional.ofNullable(worlds.get().byId().get(worldName));
    }

    public Optional<WorldMetadata> findByPaperKey(final String paperKey) {
        return Optional.ofNullable(worlds.get().byPaperKey().get(paperKey));
    }

    public Optional<WorldMetadata> findByUuid(final UUID worldUuid) {
        return Optional.ofNullable(worlds.get().byUuid().get(worldUuid));
    }

    public RegistrySnapshot snapshot() {
        return worlds.get();
    }

    public Collection<WorldMetadata> snapshots() {
        return worlds.get().worlds();
    }

    public void replace(final WorldMetadata metadata) {
        worlds.updateAndGet(current -> withEntry(current, metadata));
    }

    public Optional<WorldMetadata> remove(final String worldName) {
        final AtomicReference<WorldMetadata> removed = new AtomicReference<>();
        worlds.updateAndGet(current -> {
            removed.set(current.byId().get(worldName));
            return withoutEntry(current, worldName);
        });
        return Optional.ofNullable(removed.get());
    }

    public void replaceAll(final Collection<WorldMetadata> metadata) {
        worlds.set(RegistrySnapshot.from(metadata));
    }

    private static RegistrySnapshot withEntry(final RegistrySnapshot current, final WorldMetadata metadata) {
        final LinkedHashMap<String, WorldMetadata> byId = new LinkedHashMap<>(current.byId());
        byId.put(metadata.worldName(), metadata);
        return RegistrySnapshot.from(byId.values());
    }

    private static RegistrySnapshot withoutEntry(final RegistrySnapshot current, final String worldName) {
        if (!current.byId().containsKey(worldName)) {
            return current;
        }
        final LinkedHashMap<String, WorldMetadata> replacement = new LinkedHashMap<>(current.byId());
        replacement.remove(worldName);
        return RegistrySnapshot.from(replacement.values());
    }
}
