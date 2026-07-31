package io.github.bearl.worldmanagement.storage;

import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Copies validated aggregates between explicitly selected providers without dual writes. */
public final class StorageMigrator {

    public MigrationResult migrate(final WorldMetadataRepository source, final WorldMetadataRepository target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        final Collection<WorldMetadata> sourceMetadata = source.loadAll();
        validate(sourceMetadata);
        if (!target.loadAll().isEmpty()) {
            throw new StorageException("Migration target must be empty.");
        }
        try {
            for (final WorldMetadata metadata : sourceMetadata) {
                target.create(metadata);
            }
            final Collection<WorldMetadata> targetMetadata = target.loadAll();
            verify(sourceMetadata, targetMetadata);
            return new MigrationResult(sourceMetadata.size());
        } catch (final RuntimeException failure) {
            clearPartialTarget(target, failure);
            throw failure;
        }
    }

    private static void clearPartialTarget(final WorldMetadataRepository target, final RuntimeException failure) {
        final Collection<WorldMetadata> partialTarget;
        try {
            partialTarget = target.loadAll();
        } catch (final RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            return;
        }
        for (final WorldMetadata metadata : partialTarget) {
            try {
                target.delete(metadata.worldName(), metadata.version());
            } catch (final RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void validate(final Collection<WorldMetadata> metadata) {
        final Set<String> worldNames = new HashSet<>();
        for (final WorldMetadata world : metadata) {
            if (!worldNames.add(world.worldName())) {
                throw new StorageException("Migration source contains duplicate world metadata: " + world.worldName());
            }
        }
    }

    private static void verify(final Collection<WorldMetadata> source, final Collection<WorldMetadata> target) {
        if (!Set.copyOf(source).equals(Set.copyOf(target))) {
            throw new StorageException("Migration verification failed.");
        }
    }

    public record MigrationResult(int migratedWorlds) {
    }
}