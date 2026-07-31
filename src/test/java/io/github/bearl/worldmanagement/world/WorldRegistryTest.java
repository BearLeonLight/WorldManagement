package io.github.bearl.worldmanagement.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WorldRegistryTest {

    private static WorldMetadata metadata(final String worldId, final String namespace, final UUID worldUuid) {
        return WorldMetadata.createDefault(
            worldId,
            new WorldIdentitySnapshot(
                namespace + ":" + worldId,
                worldUuid,
                WorldEnvironment.NORMAL,
                1L,
                true
            ),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true
        );
    }

    @Test
    void storesAndRemovesImmutableMetadataSnapshots() {
        final WorldRegistry registry = new WorldRegistry();
        final WorldMetadata metadata = WorldMetadata.createDefault("survival", true);

        registry.replace(metadata);

        assertEquals(metadata, registry.find("survival").orElseThrow());
        assertEquals(1, registry.snapshots().size());
        assertTrue(registry.remove("survival").isPresent());
        assertFalse(registry.find("survival").isPresent());
    }

    @Test
    void replacesTheCompleteSnapshot() {
        final WorldRegistry registry = new WorldRegistry();
        registry.replace(WorldMetadata.createDefault("survival", true));
        final WorldMetadata creative = WorldMetadata.createDefault("creative", true);

        registry.replaceAll(java.util.List.of(creative));

        assertEquals(java.util.List.of(creative), registry.snapshots());
        assertTrue(registry.find("survival").isEmpty());
    }

    @Test
    void publishesIdKeyAndUuidIndexesAsOneSnapshot() {
        final WorldRegistry registry = new WorldRegistry();
        final UUID worldUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final WorldMetadata creative = metadata("creative", "minecraft", worldUuid);

        registry.replace(creative);

        assertEquals(creative, registry.find("creative").orElseThrow());
        assertEquals(creative, registry.findByPaperKey("minecraft:creative").orElseThrow());
        assertEquals(creative, registry.findByUuid(worldUuid).orElseThrow());
        assertEquals(registry.snapshot(), registry.snapshot());
    }

    @Test
    void rejectsDuplicatePaperKeysWithoutPublishingAPartialSnapshot() {
        final WorldRegistry registry = new WorldRegistry();
        final WorldMetadata creative = metadata(
            "creative", "minecraft", UUID.fromString("11111111-1111-1111-1111-111111111111")
        );
        final WorldMetadata duplicate = metadata(
            "creative", "minecraft", UUID.fromString("22222222-2222-2222-2222-222222222222")
        );
        registry.replace(creative);

        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(java.util.List.of(creative, duplicate)));
        assertEquals(java.util.List.of(creative), registry.snapshots());
    }

    @Test
    void rejectsDuplicateWorldUuidsAcrossDifferentIds() {
        final WorldRegistry registry = new WorldRegistry();
        final UUID duplicateUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(java.util.List.of(
            metadata("creative", "minecraft", duplicateUuid),
            metadata("survival", "minecraft", duplicateUuid)
        )));
        assertTrue(registry.snapshots().isEmpty());
    }
}