package io.github.bearl.worldmanagement.world;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, internally consistent indexes for all persisted world metadata. */
public record RegistrySnapshot(
    Map<String, WorldMetadata> byId,
    Map<String, WorldMetadata> byPaperKey,
    Map<UUID, WorldMetadata> byUuid
) {

    public RegistrySnapshot {
        byId = Map.copyOf(Objects.requireNonNull(byId, "byId"));
        byPaperKey = Map.copyOf(Objects.requireNonNull(byPaperKey, "byPaperKey"));
        byUuid = Map.copyOf(Objects.requireNonNull(byUuid, "byUuid"));
        if (byId.size() != byPaperKey.size() || byId.size() != byUuid.size()) {
            throw new IllegalArgumentException("Registry indexes must contain the same metadata aggregates.");
        }
        for (final Map.Entry<String, WorldMetadata> entry : byId.entrySet()) {
            final String worldId = entry.getKey();
            final WorldMetadata metadata = entry.getValue();
            if (!worldId.equals(metadata.worldName())
                || byPaperKey.get(metadata.identity().paperKey()) != metadata
                || byUuid.get(metadata.identity().worldUuid()) != metadata) {
                throw new IllegalArgumentException("Registry indexes are inconsistent.");
            }
        }
    }

    public static RegistrySnapshot empty() {
        return new RegistrySnapshot(Map.of(), Map.of(), Map.of());
    }

    public static RegistrySnapshot from(final Collection<WorldMetadata> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        final Map<String, WorldMetadata> byId = new LinkedHashMap<>();
        final Map<String, WorldMetadata> byPaperKey = new LinkedHashMap<>();
        final Map<UUID, WorldMetadata> byUuid = new LinkedHashMap<>();
        for (final WorldMetadata world : metadata) {
            final WorldMetadata requiredWorld = Objects.requireNonNull(world, "metadata entry");
            putUnique(byId, requiredWorld.worldName(), requiredWorld, "world ID");
            putUnique(byPaperKey, requiredWorld.identity().paperKey(), requiredWorld, "Paper key");
            putUnique(byUuid, requiredWorld.identity().worldUuid(), requiredWorld, "world UUID");
        }
        return new RegistrySnapshot(byId, byPaperKey, byUuid);
    }

    public List<WorldMetadata> worlds() {
        return List.copyOf(byId.values());
    }

    private static <K> void putUnique(
        final Map<K, WorldMetadata> index,
        final K key,
        final WorldMetadata metadata,
        final String indexName
    ) {
        if (index.putIfAbsent(key, metadata) != null) {
            throw new IllegalArgumentException("Duplicate " + indexName + " in registry snapshot: " + key);
        }
    }
}