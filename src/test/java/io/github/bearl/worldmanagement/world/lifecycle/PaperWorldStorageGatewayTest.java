package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

final class PaperWorldStorageGatewayTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsIdentityBoundLoadClaimForTheOnlyExistingStoragePath() throws Exception {
        createPaperStorage(temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative"));
        final WorldMetadata metadata = metadata();
        final PaperWorldStorageGateway gateway = gateway();

        final WorldStorageGateway.LoadClaim claim = gateway.prepareLoad(metadata).orElseThrow();

        assertEquals("creative", claim.world().worldId());
        assertEquals(metadata.identity().paperKey(), claim.world().paperKey());
        assertEquals(metadata.identity().worldUuid(), claim.world().worldUuid());
        assertEquals(metadata.version(), claim.metadataVersion());
        gateway.validateLoadClaim(claim);
    }

    @Test
    void rejectsLoadClaimWhenItsStorageEntryWasReplaced() throws Exception {
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
        );
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.LoadClaim claim = gateway.prepareLoad(metadata()).orElseThrow();
        final Path moved = dimension.resolveSibling("creative_old");
        Files.move(dimension, moved);
        createPaperStorage(dimension);

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.validateLoadClaim(claim)
        );
        assertTrue(Files.isDirectory(dimension));
        assertTrue(Files.isDirectory(moved));
    }

    @Test
    void rejectsImportClaimWhenItsStorageEntryWasReplaced() throws Exception {
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "original");
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.ImportClaim claim = gateway.prepareImport("archive").orElseThrow();
        final Path moved = legacy.resolveSibling("archive_old");
        Files.move(legacy, moved);
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("level.dat"), "replacement");

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.validateImportClaim(claim)
        );
        assertEquals("replacement", Files.readString(legacy.resolve("level.dat")));
        assertEquals("original", Files.readString(moved.resolve("level.dat")));
    }

    @Test
    void capturesLegacyWorldUuidAndRejectsItsReplacement() throws Exception {
        final UUID originalUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        writeLegacyUuid(legacy.resolve("uid.dat"), originalUuid);
        final PaperWorldStorageGateway gateway = gateway();

        final WorldStorageGateway.ImportClaim claim = gateway.prepareImportPreparation("archive")
            .claim().orElseThrow();

        assertEquals(Optional.of(originalUuid), claim.persistedWorldUuid());
        writeLegacyUuid(
            legacy.resolve("uid.dat"),
            UUID.fromString("22222222-2222-2222-2222-222222222222")
        );
        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.validateImportClaim(claim)
        );
    }

    @Test
    void capturesPaperWorldUuidFromMetadataSavedData() throws Exception {
        final UUID worldUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive")
        );
        writePaperMetadata(dimension.resolve("data").resolve("paper").resolve("metadata.dat"), worldUuid);

        final WorldStorageGateway.ImportClaim claim = gateway().prepareImportPreparation("archive")
            .claim().orElseThrow();

        assertEquals(Optional.of(worldUuid), claim.persistedWorldUuid());
    }

    @Test
    void restoresLegacyIdentityRegenerationBeforeRuntimeSideEffects() throws Exception {
        final UUID worldUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        writeLegacyUuid(legacy.resolve("uid.dat"), worldUuid);
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.ImportClaim importClaim = gateway.prepareImportPreparation("archive")
            .claim().orElseThrow();

        final WorldStorageGateway.IdentityRegenerationClaim regeneration =
            gateway.beginIdentityRegeneration(importClaim);
        assertFalse(Files.exists(legacy.resolve("uid.dat")));

        gateway.restoreIdentity(regeneration);

        assertTrue(Files.isRegularFile(legacy.resolve("uid.dat")));
        assertEquals(worldUuid, gateway.prepareImportPreparation("archive")
            .claim().orElseThrow().persistedWorldUuid().orElseThrow());
    }

    @Test
    void finalizesIdentityRegenerationWithoutRestoringOldUuid() throws Exception {
        final UUID regeneratedUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        writeLegacyUuid(
            legacy.resolve("uid.dat"),
            UUID.fromString("11111111-1111-1111-1111-111111111111")
        );
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.IdentityRegenerationClaim regeneration = gateway.beginIdentityRegeneration(
            gateway.prepareImportPreparation("archive").claim().orElseThrow()
        );
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive")
        );
        writePaperMetadata(
            dimension.resolve("data").resolve("paper").resolve("metadata.dat"), regeneratedUuid
        );

        gateway.finalizeIdentityRegeneration(regeneration, regeneratedUuid);

        assertFalse(Files.exists(legacy.resolve("uid.dat")));
        assertFalse(Files.exists(regeneration.recoveryPath()));
    }

    @Test
    void finalizesLegacyIdentityAfterPaperMigratesAndDeletesSourceDirectory() throws Exception {
        final UUID regeneratedUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        writeLegacyUuid(
            legacy.resolve("uid.dat"),
            UUID.fromString("11111111-1111-1111-1111-111111111111")
        );
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.IdentityRegenerationClaim regeneration = gateway.beginIdentityRegeneration(
            gateway.prepareImportPreparation("archive").claim().orElseThrow()
        );
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive")
        );
        writePaperMetadata(
            dimension.resolve("data").resolve("paper").resolve("metadata.dat"),
            regeneratedUuid
        );
        try (final var paths = Files.walk(legacy)) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }

        gateway.finalizeIdentityRegeneration(regeneration, regeneratedUuid);

        assertFalse(Files.exists(regeneration.recoveryPath()));
        assertEquals(
            Optional.of(regeneratedUuid),
            gateway.prepareImportPreparation("archive").claim().orElseThrow().persistedWorldUuid()
        );
    }

    @Test
    void rejectsFinalizationWhenPaperMetadataDoesNotMatchRuntimeUuid() throws Exception {
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        writeLegacyUuid(
            legacy.resolve("uid.dat"),
            UUID.fromString("11111111-1111-1111-1111-111111111111")
        );
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.IdentityRegenerationClaim regeneration = gateway.beginIdentityRegeneration(
            gateway.prepareImportPreparation("archive").claim().orElseThrow()
        );
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive")
        );
        writePaperMetadata(
            dimension.resolve("data").resolve("paper").resolve("metadata.dat"),
            UUID.fromString("22222222-2222-2222-2222-222222222222")
        );

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.finalizeIdentityRegeneration(
                regeneration, UUID.fromString("33333333-3333-3333-3333-333333333333")
            )
        );

        assertTrue(Files.isRegularFile(regeneration.recoveryPath()));
    }

    @Test
    void rejectsMissingRecoveryMarkerWhileLegacySourceStillExists() throws Exception {
        final UUID regeneratedUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        writeLegacyUuid(
            legacy.resolve("uid.dat"),
            UUID.fromString("11111111-1111-1111-1111-111111111111")
        );
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.IdentityRegenerationClaim regeneration = gateway.beginIdentityRegeneration(
            gateway.prepareImportPreparation("archive").claim().orElseThrow()
        );
        Files.delete(regeneration.recoveryPath());
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive")
        );
        writePaperMetadata(
            dimension.resolve("data").resolve("paper").resolve("metadata.dat"), regeneratedUuid
        );

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.finalizeIdentityRegeneration(regeneration, regeneratedUuid)
        );
    }

    @Test
    void reportsImportStorageConflictWithoutChangingEitherPath() throws Exception {
        final Path current = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("archive")
        );
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "legacy");

        final WorldStorageGateway.ImportPreparation preparation = gateway().prepareImportPreparation("archive");

        assertEquals(WorldStorageGateway.ImportPreparationStatus.STORAGE_CONFLICT, preparation.status());
        assertTrue(preparation.claim().isEmpty());
        assertTrue(Files.isDirectory(current));
        assertEquals("legacy", Files.readString(legacy.resolve("level.dat")));
    }

    @Test
    void deletesOnlyStorageStillOwnedByTheCreatedRuntimeIdentity() throws Exception {
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.CreationClaim creation = gateway.prepareCreation("creative").orElseThrow();
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
        );
        final var world = io.github.bearl.worldmanagement.world.VerifiedWorldRef.from(metadata()).orElseThrow();
        final WorldStorageGateway.OwnedCreationClaim owned = gateway.bindCreated(creation, world);

        gateway.deleteCreated(owned);

        assertFalse(Files.exists(dimension));
    }

    @Test
    void refusesToDeleteCreatedStorageAfterItWasReplaced() throws Exception {
        final PaperWorldStorageGateway gateway = gateway();
        final WorldStorageGateway.CreationClaim creation = gateway.prepareCreation("creative").orElseThrow();
        final Path dimension = createPaperStorage(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
        );
        final var world = io.github.bearl.worldmanagement.world.VerifiedWorldRef.from(metadata()).orElseThrow();
        final WorldStorageGateway.OwnedCreationClaim owned = gateway.bindCreated(creation, world);
        final Path original = dimension.resolveSibling("creative_original");
        Files.move(dimension, original);
        createPaperStorage(dimension);

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.deleteCreated(owned)
        );
        assertTrue(Files.isDirectory(dimension));
        assertTrue(Files.isDirectory(original));
    }

    @Test
    void refusesMissingOrAmbiguousStorageWithoutCreatingDirectories() throws Exception {
        final PaperWorldStorageGateway gateway = gateway();
        assertTrue(gateway.prepareLoad(metadata()).isEmpty());
        assertFalse(Files.exists(temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")));

        Files.createDirectories(temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative"));
        Files.createDirectories(temporaryDirectory.resolve("creative"));

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.prepareLoad(metadata())
        );
    }

    @Test
    void restoresQuarantinedPaperDimensionToItsLivePath() throws Exception {
        final Path dimension = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
        );
        Files.writeString(dimension.resolve("level.dat"), "world");
        final PaperWorldStorageGateway gateway = gateway();

        final WorldStorageGateway.QuarantinedWorld quarantined = gateway.quarantine(metadata());
        assertFalse(Files.exists(dimension));
        assertEquals("creative", quarantined.world().worldId());
        assertEquals(metadata().identity().paperKey(), quarantined.world().paperKey());
        assertEquals(metadata().identity().worldUuid(), quarantined.world().worldUuid());
        assertEquals(metadata().version(), quarantined.metadataVersion());
        assertNotNull(quarantined.transactionId());

        gateway.restore(quarantined);

        assertTrue(Files.isRegularFile(dimension.resolve("level.dat")));
        assertFalse(Files.exists(dimension.getParent().resolve(".worldmanagement-quarantine")));
    }

    @Test
    void deletesQuarantinedLegacyWorldWithoutRestoringLivePath() throws Exception {
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        final PaperWorldStorageGateway gateway = gateway();

        final WorldStorageGateway.QuarantinedWorld quarantined = gateway.quarantine(metadata("archive"));
        gateway.delete(quarantined);

        assertFalse(Files.exists(legacy));
        assertFalse(Files.exists(temporaryDirectory.resolve(".worldmanagement-quarantine")));
    }

    @Test
    void restoresQuarantineOnlyWhenIdentityAndSourceVersionMatchActiveMetadata() throws Exception {
        final Path managed = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("managed_world")
        );
        Files.writeString(managed.resolve("level.dat"), "managed");
        final WorldMetadata managedMetadata = metadata("managed_world");
        gateway().quarantine(managedMetadata);

        gateway().recoverQuarantined(Set.of(managedMetadata));

        assertTrue(Files.isRegularFile(managed.resolve("level.dat")));
    }

    @Test
    void permanentlyDeletesQuarantineForDeletingMetadata() throws Exception {
        final Path deleting = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("deleting_world")
        );
        Files.writeString(deleting.resolve("level.dat"), "deleting");
        final WorldMetadata active = metadata("deleting_world");
        final WorldStorageGateway.QuarantinedWorld claim = gateway().quarantine(active);
        final WorldMetadata deletingMetadata = active.withDeleting(claim.transactionId());

        final Set<String> completed = gateway().recoverQuarantined(Set.of(deletingMetadata));

        assertTrue(completed.contains("deleting_world"));
        assertFalse(Files.exists(deleting));
        assertFalse(Files.exists(deleting.getParent().resolve(".worldmanagement-quarantine")));
    }

    @Test
    void failsClosedWhenDeletingTransactionDoesNotMatchQuarantine() throws Exception {
        final Path deleting = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("deleting_world")
        );
        Files.writeString(deleting.resolve("level.dat"), "deleting");
        final PaperWorldStorageGateway gateway = gateway();
        final WorldMetadata active = metadata("deleting_world");
        gateway.quarantine(active);
        final WorldMetadata wrongTransaction = active.withDeleting(
            UUID.fromString("88888888-8888-8888-8888-888888888888")
        );

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.recoverQuarantined(Set.of(wrongTransaction))
        );
        assertTrue(Files.isDirectory(deleting.getParent().resolve(".worldmanagement-quarantine")));
        assertFalse(Files.exists(deleting));
    }

    @Test
    void failsClosedWhenDeletingClaimCoexistsWithLiveStorage() throws Exception {
        final Path deleting = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("deleting_world")
        );
        Files.writeString(deleting.resolve("level.dat"), "old");
        final PaperWorldStorageGateway gateway = gateway();
        final WorldMetadata active = metadata("deleting_world");
        gateway.quarantine(active);

        Files.createDirectories(deleting);
        Files.writeString(deleting.resolve("level.dat"), "reloaded");

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway.recoverQuarantined(Set.of(active.withDeleting(
                UUID.fromString("99999999-9999-9999-9999-999999999999")
            )))
        );
        assertTrue(Files.isRegularFile(deleting.resolve("level.dat")));
        assertTrue(Files.isDirectory(deleting.getParent().resolve(".worldmanagement-quarantine")));
    }

    @Test
    void rejectsAmbiguousCurrentAndLegacyStorageWithoutMovingEitherPath() throws Exception {
        final Path current = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
        );
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("creative"));
        Files.writeString(current.resolve("level.dat"), "current");
        Files.writeString(legacy.resolve("level.dat"), "legacy");

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway().quarantine(metadata())
        );
        assertTrue(Files.isRegularFile(current.resolve("level.dat")));
        assertTrue(Files.isRegularFile(legacy.resolve("level.dat")));
        assertFalse(Files.exists(current.getParent().resolve(".worldmanagement-quarantine")));
        assertFalse(Files.exists(temporaryDirectory.resolve(".worldmanagement-quarantine")));
    }

    @Test
    void rejectsLinkedQuarantineRootWithoutTouchingExternalContent() throws Exception {
        final Path legacy = Files.createDirectories(temporaryDirectory.resolve("archive"));
        Files.writeString(legacy.resolve("level.dat"), "world");
        final Path external = Files.createDirectories(temporaryDirectory.resolve("external"));
        final Path externalFile = external.resolve("keep.txt");
        Files.writeString(externalFile, "keep");
        try {
            Files.createSymbolicLink(temporaryDirectory.resolve(".worldmanagement-quarantine"), external);
        } catch (final java.io.IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic links are unavailable in this test environment: " + exception.getMessage());
        }

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway().quarantine(metadata("archive"))
        );
        assertTrue(Files.isRegularFile(legacy.resolve("level.dat")));
        assertTrue(Files.isRegularFile(externalFile));
    }

    @Test
    void rejectsJunctionEntryDuringQuarantineRecovery() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        final Path quarantine = Files.createDirectory(temporaryDirectory.resolve(".worldmanagement-quarantine"));
        final Path external = Files.createDirectory(temporaryDirectory.resolve("external"));
        final Path externalFile = external.resolve("keep.txt");
        Files.writeString(externalFile, "keep");
        final Path junction = quarantine.resolve("archive_0123456789abcdef0123456789abcdef");
        final Process process = new ProcessBuilder(
            "cmd.exe", "/c", "mklink", "/J", junction.toString(), external.toString()
        ).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assumptions.assumeTrue(process.waitFor() == 0, "Could not create junction: " + output);

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway().recoverQuarantined(Set.of(metadata("archive")))
        );
        assertFalse(Files.exists(temporaryDirectory.resolve("archive")));
        assertTrue(Files.isRegularFile(externalFile));
    }

    @Test
    void failsClosedWhenQuarantineClaimIdentityDoesNotMatchMetadata() throws Exception {
        final Path dimension = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
        );
        Files.writeString(dimension.resolve("level.dat"), "world");
        final WorldMetadata accepted = metadata();
        gateway().quarantine(accepted);
        final WorldMetadata replacement = WorldMetadata.createDefault(
            "creative",
            new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                WorldEnvironment.NORMAL,
                42L,
                true
            ),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true
        );

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway().recoverQuarantined(Set.of(replacement))
        );
        assertFalse(Files.exists(dimension));
        assertTrue(Files.isDirectory(dimension.getParent().resolve(".worldmanagement-quarantine")));
    }

    @Test
    void failsClosedInsteadOfDeletingQuarantineWithoutMetadata() throws Exception {
        final Path dimension = Files.createDirectories(
            temporaryDirectory.resolve("dimensions").resolve("minecraft").resolve("creative")
        );
        Files.writeString(dimension.resolve("level.dat"), "world");
        gateway().quarantine(metadata());

        assertThrows(
            io.github.bearl.worldmanagement.storage.StorageException.class,
            () -> gateway().recoverQuarantined(Set.of())
        );
        assertTrue(Files.isDirectory(dimension.getParent().resolve(".worldmanagement-quarantine")));
    }

    private PaperWorldStorageGateway gateway() {
        final WorldNameValidator validator = new WorldNameValidator();
        return new PaperWorldStorageGateway(
            temporaryDirectory,
            temporaryDirectory,
            validator,
            new WorldDirectoryRemover(validator)
        );
    }

    private static void writeLegacyUuid(final Path path, final UUID worldUuid) throws Exception {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeLong(worldUuid.getMostSignificantBits());
            output.writeLong(worldUuid.getLeastSignificantBits());
        }
    }

    private static void writePaperMetadata(final Path path, final UUID worldUuid) throws Exception {
        final int[] encodedUuid = {
            (int) (worldUuid.getMostSignificantBits() >> 32),
            (int) worldUuid.getMostSignificantBits(),
            (int) (worldUuid.getLeastSignificantBits() >> 32),
            (int) worldUuid.getLeastSignificantBits()
        };
        final CompoundBinaryTag root = CompoundBinaryTag.builder()
            .put("data", CompoundBinaryTag.builder().putIntArray("uuid", encodedUuid).build())
            .build();
        BinaryTagIO.writer().write(root, path, BinaryTagIO.Compression.GZIP);
    }

    private static WorldMetadata metadata() {
        return metadata("creative");
    }

    private static WorldMetadata metadata(final String worldId) {
        return WorldMetadata.createDefault(
            worldId,
            new WorldIdentitySnapshot(
                "minecraft:" + worldId,
                UUID.nameUUIDFromBytes(("test:" + worldId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                WorldEnvironment.NORMAL,
                42L,
                true
            ),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true
        );
    }

    private static Path createPaperStorage(final Path dimension) throws Exception {
        final Path data = Files.createDirectories(dimension.resolve("data"));
        Files.createDirectories(data.resolve("minecraft"));
        Files.createDirectories(data.resolve("paper"));
        Files.writeString(data.resolve("minecraft").resolve("world_gen_settings.dat"), "worldgen");
        Files.writeString(data.resolve("paper").resolve("metadata.dat"), "metadata");
        Files.writeString(data.resolve("paper").resolve("level_overrides.dat"), "overrides");
        return dimension;
    }
}