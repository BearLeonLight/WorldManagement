package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only repository implementing the same optimistic-version contract as persistent providers. */
public final class InMemoryWorldMetadataRepository implements WorldMetadataRepository {

    private final Map<String, WorldMetadata> metadataByWorld = new ConcurrentHashMap<>();

    @Override
    public Collection<WorldMetadata> loadAll() {
        return List.copyOf(metadataByWorld.values());
    }

    @Override
    public Optional<WorldMetadata> find(final String worldName) {
        return Optional.ofNullable(metadataByWorld.get(worldName));
    }

    @Override
    public void create(final WorldMetadata metadata) {
        if (metadataByWorld.putIfAbsent(metadata.worldName(), metadata) != null) {
            throw new IllegalStateException("World metadata already exists: " + metadata.worldName());
        }
    }

    @Override
    public void replace(final WorldMetadata metadata, final long expectedVersion) {
        final AtomicBoolean updated = new AtomicBoolean();
        metadataByWorld.computeIfPresent(metadata.worldName(), (worldName, current) -> {
            if (current.version() != expectedVersion) {
                return current;
            }
            updated.set(true);
            return metadata;
        });
        if (!updated.get()) {
            throw new ConcurrentWorldUpdateException(metadata.worldName());
        }
    }

    @Override
    public void delete(final String worldName, final long expectedVersion) {
        final WorldMetadata current = metadataByWorld.get(worldName);
        if (current == null || current.version() != expectedVersion || !metadataByWorld.remove(worldName, current)) {
            throw new ConcurrentWorldUpdateException(worldName);
        }
    }
}
