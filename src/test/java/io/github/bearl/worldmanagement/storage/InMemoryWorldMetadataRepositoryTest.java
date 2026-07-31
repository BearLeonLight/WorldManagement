package io.github.bearl.worldmanagement.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class InMemoryWorldMetadataRepositoryTest {

    @Test
    void replacesOnlyWhenTheExpectedVersionMatches() {
        final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
        final WorldMetadata created = WorldMetadata.createDefault("creative", true);
        repository.create(created);

        final WorldMetadata updated = created.withAccessControl(new AccessControl(AccessMode.WHITELIST, Set.of()));
        repository.replace(updated, created.version());

        assertEquals(updated, repository.find("creative").orElseThrow());
        assertThrows(ConcurrentWorldUpdateException.class, () -> repository.replace(updated, created.version()));
    }

    @Test
    void deletesOnlyWhenTheExpectedVersionMatches() {
        final InMemoryWorldMetadataRepository repository = new InMemoryWorldMetadataRepository();
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);
        repository.create(metadata);

        assertThrows(ConcurrentWorldUpdateException.class, () -> repository.delete("creative", 1));
        repository.delete("creative", 0);
        assertThrows(ConcurrentWorldUpdateException.class, () -> repository.delete("creative", 0));
    }
}