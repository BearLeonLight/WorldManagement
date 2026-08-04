package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Optional;
import java.util.Set;

/** Blocking access to world storage. Invoke only from PluginIoExecutor. */
public interface WorldStorageGateway {

    boolean exists(String worldId);

    boolean isImportable(String worldId);

    default Optional<ImportClaim> prepareImport(final String worldId) {
        return isImportable(worldId) ? Optional.of(new ImportClaim() { }) : Optional.empty();
    }

    default void validateImportClaim(final ImportClaim importClaim) {
        java.util.Objects.requireNonNull(importClaim, "importClaim");
    }

    Optional<LoadClaim> prepareLoad(WorldMetadata metadata);

    void validateLoadClaim(LoadClaim loadClaim);

    Optional<CreationClaim> prepareCreation(String worldId);

    OwnedCreationClaim bindCreated(CreationClaim creationClaim, VerifiedWorldRef world);

    void deleteCreated(OwnedCreationClaim creationClaim);

    QuarantinedWorld quarantine(WorldMetadata metadata);

    void restore(QuarantinedWorld quarantinedWorld);

    void delete(QuarantinedWorld quarantinedWorld);

    Set<String> recoverQuarantined(Set<WorldMetadata> metadata);

    /** Opaque claim for storage moved out of its live world path. */
    interface QuarantinedWorld {
        VerifiedWorldRef world();

        long metadataVersion();

        java.util.UUID transactionId();
    }

    /** Opaque claim proving both supported storage paths were absent before creation. */
    interface CreationClaim {
    }

    /** Opaque claim pinning a created runtime identity to one unchanged storage entry. */
    interface OwnedCreationClaim {
        VerifiedWorldRef world();
    }

    /** Opaque claim pinning one unchanged importable storage entry. */
    interface ImportClaim {
    }

    /** Identity and metadata version pinned to one unchanged persisted storage entry. */
    interface LoadClaim {
        VerifiedWorldRef world();

        long metadataVersion();
    }
}