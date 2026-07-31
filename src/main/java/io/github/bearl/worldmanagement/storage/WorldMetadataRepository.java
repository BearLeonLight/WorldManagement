package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Collection;
import java.util.Optional;

/** Provider-neutral persistence boundary for complete world metadata aggregates. */
public interface WorldMetadataRepository extends AutoCloseable {

    Collection<WorldMetadata> loadAll();

    Optional<WorldMetadata> find(String worldName);

    void create(WorldMetadata metadata);

    void replace(WorldMetadata metadata, long expectedVersion) throws ConcurrentWorldUpdateException;

    void delete(String worldName, long expectedVersion) throws ConcurrentWorldUpdateException;

    @Override
    default void close() {
    }
}
