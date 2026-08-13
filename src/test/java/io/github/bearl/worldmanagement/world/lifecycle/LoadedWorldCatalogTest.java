package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LoadedWorldCatalogTest {

    @Test
    void resolvesOnlyAUniqueWorldIdMatch() {
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final WorldRuntimeGateway.LifecycleWorld minecraft = world(
            "minecraft:creative", "11111111-1111-1111-1111-111111111111"
        );
        final WorldRuntimeGateway.LifecycleWorld external = world(
            "external:creative", "22222222-2222-2222-2222-222222222222"
        );

        catalog.replaceAll(List.of(minecraft));
        assertEquals(minecraft, catalog.findUniqueByWorldId("creative").orElseThrow());
        assertEquals(minecraft, catalog.findExactUnique(minecraft.reference()).orElseThrow());

        catalog.loaded(external);
        assertTrue(catalog.findUniqueByWorldId("creative").isEmpty());
        assertTrue(catalog.findExactUnique(minecraft.reference()).isEmpty());
    }

    @Test
    void listsSortedUniqueWorldsAndExcludesAmbiguousWorldIds() {
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final WorldRuntimeGateway.LifecycleWorld alpha = world(
            "minecraft:alpha", "11111111-1111-1111-1111-111111111111"
        );
        final WorldRuntimeGateway.LifecycleWorld creative = world(
            "minecraft:creative", "22222222-2222-2222-2222-222222222222"
        );
        final WorldRuntimeGateway.LifecycleWorld externalCreative = world(
            "external:creative", "33333333-3333-3333-3333-333333333333"
        );
        final WorldRuntimeGateway.LifecycleWorld survival = world(
            "minecraft:survival", "44444444-4444-4444-4444-444444444444"
        );

        catalog.replaceAll(List.of(survival, creative, alpha, externalCreative));

        assertEquals(List.of(alpha, survival), catalog.uniqueWorlds());
    }

    @Test
    void resolvesOnlyAUniqueWorldUuidOwner() {
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final UUID sharedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final WorldRuntimeGateway.LifecycleWorld creative = world(
            "minecraft:creative", sharedUuid.toString()
        );
        final WorldRuntimeGateway.LifecycleWorld archive = world(
            "minecraft:archive", sharedUuid.toString()
        );

        catalog.replaceAll(List.of(creative));
        assertEquals(creative, catalog.findUniqueByWorldUuid(sharedUuid).orElseThrow());

        catalog.loaded(archive);
        assertTrue(catalog.findUniqueByWorldUuid(sharedUuid).isEmpty());

        catalog.unloaded(archive.reference());
        assertEquals(creative, catalog.findUniqueByWorldUuid(sharedUuid).orElseThrow());
    }

    @Test
    void exactUnloadInvalidatesThePriorGenerationWithoutRemovingAReplacement() {
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        final WorldRuntimeGateway.LifecycleWorld original = world(
            "minecraft:creative", "11111111-1111-1111-1111-111111111111"
        );
        final WorldRuntimeGateway.LifecycleWorld replacement = world(
            "minecraft:creative", "22222222-2222-2222-2222-222222222222"
        );

        final LoadedWorldCatalog.Observation originalLoad = catalog.loaded(original);
        catalog.loaded(replacement);
        final LoadedWorldCatalog.Observation staleUnload = catalog.unloaded(original.reference());

        assertFalse(catalog.isCurrent(originalLoad));
        assertTrue(catalog.isCurrent(staleUnload));
        assertEquals(replacement, catalog.findUniqueByWorldId("creative").orElseThrow());
    }

    @Test
    void retiredUnloadsDoNotRetainHistoricalWorldIdsOrAllowGenerationReuse() {
        final LoadedWorldCatalog catalog = new LoadedWorldCatalog();
        LoadedWorldCatalog.Observation firstUnload = null;

        for (int index = 0; index < 100; index++) {
            final WorldRuntimeGateway.LifecycleWorld loaded = world(
                "minecraft:temporary_" + index,
                new UUID(0L, index + 1L).toString()
            );
            catalog.loaded(loaded);
            final LoadedWorldCatalog.Observation unloaded = catalog.unloaded(loaded.reference());
            if (firstUnload == null) {
                firstUnload = unloaded;
            }
            assertTrue(catalog.isCurrent(unloaded));
            catalog.retire(unloaded);
            assertFalse(catalog.isCurrent(unloaded));
        }

        final WorldRuntimeGateway.LifecycleWorld reloaded = world(
            "minecraft:temporary_0", "11111111-1111-1111-1111-111111111111"
        );
        catalog.loaded(reloaded);

        assertFalse(catalog.isCurrent(firstUnload));
        assertEquals(1, catalog.retainedObservationCount());
    }

    private static WorldRuntimeGateway.LifecycleWorld world(final String paperKey, final String uuid) {
        return new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot(
                paperKey,
                UUID.fromString(uuid),
                WorldEnvironment.NORMAL,
                42L,
                true
            ),
            paperKey.startsWith("minecraft:")
                ? LifecycleCapability.MANAGED
                : LifecycleCapability.EXTERNAL_ONLY
        );
    }
}
