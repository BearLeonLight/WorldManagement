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

        catalog.loaded(external);
        assertTrue(catalog.findUniqueByWorldId("creative").isEmpty());
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
