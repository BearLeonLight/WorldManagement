package io.github.bearl.worldmanagement.module;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModuleManagerTest {

    @Test
    void exposesOnlyRegisteredAndEnabledModules() {
        final ModuleManager manager = new ModuleManager(
            List.of(() -> ModuleId.WARP, () -> ModuleId.OWNERSHIP),
            new ModuleConfiguration(Map.of(ModuleId.WARP, false, ModuleId.OWNERSHIP, true))
        );

        assertFalse(manager.enabled(ModuleId.WARP));
        assertTrue(manager.enabled(ModuleId.OWNERSHIP));
        assertFalse(manager.enabled(ModuleId.LIFECYCLE));
        assertEquals(List.of(ModuleId.OWNERSHIP), manager.enabledModules());
    }
}