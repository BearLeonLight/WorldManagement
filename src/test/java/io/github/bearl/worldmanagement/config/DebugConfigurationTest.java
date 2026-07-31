package io.github.bearl.worldmanagement.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

final class DebugConfigurationTest {

    @Test
    void gatesByLevelAndArea() {
        final DebugConfiguration configuration = new DebugConfiguration(
            DebugLevel.BASIC,
            true,
            true,
            EnumSet.of(DebugArea.STARTUP),
            new DebugFileConfiguration(10, 5),
            new DebugPrivacyConfiguration(false, false, false)
        );

        assertTrue(configuration.enabled(DebugLevel.BASIC, DebugArea.STARTUP));
        assertFalse(configuration.enabled(DebugLevel.VERBOSE, DebugArea.STARTUP));
        assertFalse(configuration.enabled(DebugLevel.BASIC, DebugArea.STORAGE));
    }

    @Test
    void validatesFileLimits() {
        assertThrows(IllegalArgumentException.class, () -> new DebugFileConfiguration(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new DebugFileConfiguration(10, 21));
    }
}