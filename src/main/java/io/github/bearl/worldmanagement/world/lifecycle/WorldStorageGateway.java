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

    default ImportPreparation prepareImportPreparation(final String worldId) {
        return prepareImport(worldId)
            .map(ImportPreparation::importable)
            .orElseGet(ImportPreparation::missing);
    }

    default void validateImportClaim(final ImportClaim importClaim) {
        java.util.Objects.requireNonNull(importClaim, "importClaim");
    }

    default IdentityRegenerationClaim beginIdentityRegeneration(final ImportClaim importClaim) {
        throw new UnsupportedOperationException("Identity regeneration is unavailable.");
    }

    default void restoreIdentity(final IdentityRegenerationClaim regenerationClaim) {
        throw new UnsupportedOperationException("Identity regeneration is unavailable.");
    }

    default void finalizeIdentityRegeneration(final IdentityRegenerationClaim regenerationClaim) {
        throw new UnsupportedOperationException("Identity regeneration is unavailable.");
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
        default Optional<java.util.UUID> persistedWorldUuid() {
            return Optional.empty();
        }
    }

    interface IdentityRegenerationClaim {
        java.util.UUID previousWorldUuid();

        java.nio.file.Path recoveryPath();
    }

    enum ImportPreparationStatus {
        IMPORTABLE,
        MISSING,
        STORAGE_CONFLICT
    }

    record ImportPreparation(ImportPreparationStatus status, Optional<ImportClaim> claim) {
        public ImportPreparation {
            java.util.Objects.requireNonNull(status, "status");
            claim = java.util.Objects.requireNonNull(claim, "claim");
            if ((status == ImportPreparationStatus.IMPORTABLE) != claim.isPresent()) {
                throw new IllegalArgumentException("Only importable preparation may contain a claim.");
            }
        }

        public static ImportPreparation importable(final ImportClaim claim) {
            return new ImportPreparation(
                ImportPreparationStatus.IMPORTABLE,
                Optional.of(java.util.Objects.requireNonNull(claim, "claim"))
            );
        }

        public static ImportPreparation missing() {
            return new ImportPreparation(ImportPreparationStatus.MISSING, Optional.empty());
        }

        public static ImportPreparation storageConflict() {
            return new ImportPreparation(ImportPreparationStatus.STORAGE_CONFLICT, Optional.empty());
        }
    }

    /** Identity and metadata version pinned to one unchanged persisted storage entry. */
    interface LoadClaim {
        VerifiedWorldRef world();

        long metadataVersion();
    }
}