package io.github.bearl.worldmanagement.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class StorageMigratorTest {

    @Test
    void copiesValidatedAggregatesToAnEmptyTarget() {
        final InMemoryWorldMetadataRepository source = new InMemoryWorldMetadataRepository();
        source.create(WorldMetadata.createDefault("creative", true));
        final InMemoryWorldMetadataRepository target = new InMemoryWorldMetadataRepository();

        final StorageMigrator.MigrationResult result = new StorageMigrator().migrate(source, target);

        assertEquals(1, result.migratedWorlds());
        assertEquals(source.loadAll(), target.loadAll());
    }

    @Test
    void rejectsMigrationToNonEmptyTarget() {
        final InMemoryWorldMetadataRepository source = new InMemoryWorldMetadataRepository();
        final InMemoryWorldMetadataRepository target = new InMemoryWorldMetadataRepository();
        target.create(WorldMetadata.createDefault("creative", true));

        assertThrows(StorageException.class, () -> new StorageMigrator().migrate(source, target));
    }

    @Test
    void removesPartialTargetWhenCopyFails() {
        final InMemoryWorldMetadataRepository source = new InMemoryWorldMetadataRepository();
        source.create(WorldMetadata.createDefault("creative", true));
        source.create(WorldMetadata.createDefault("survival", true));
        final FailingCreateRepository target = new FailingCreateRepository(2);

        assertThrows(StorageException.class, () -> new StorageMigrator().migrate(source, target));

        assertEquals(0, target.loadAll().size());
    }

    private static final class FailingCreateRepository implements WorldMetadataRepository {

        private final InMemoryWorldMetadataRepository delegate = new InMemoryWorldMetadataRepository();
        private final int failingCreate;
        private int creates;

        private FailingCreateRepository(final int failingCreate) {
            this.failingCreate = failingCreate;
        }

        @Override
        public Collection<WorldMetadata> loadAll() {
            return delegate.loadAll();
        }

        @Override
        public Optional<WorldMetadata> find(final String worldName) {
            return delegate.find(worldName);
        }

        @Override
        public void create(final WorldMetadata metadata) {
            creates++;
            if (creates == failingCreate) {
                throw new StorageException("simulated copy failure");
            }
            delegate.create(metadata);
        }

        @Override
        public void replace(final WorldMetadata metadata, final long expectedVersion) {
            delegate.replace(metadata, expectedVersion);
        }

        @Override
        public void delete(final String worldName, final long expectedVersion) {
            delegate.delete(worldName, expectedVersion);
        }
    }
}