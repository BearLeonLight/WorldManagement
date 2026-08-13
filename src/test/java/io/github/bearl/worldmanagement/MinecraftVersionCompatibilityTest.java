package io.github.bearl.worldmanagement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MinecraftVersionCompatibilityTest {

    @Test
    void acceptsEveryPatchVersionInTheSupportedMinorVersion() {
        assertTrue(MinecraftVersionCompatibility.isSupported("26.0"));
        assertTrue(MinecraftVersionCompatibility.isSupported("26.2"));
        assertTrue(MinecraftVersionCompatibility.isSupported("26.99"));
        assertTrue(MinecraftVersionCompatibility.isSupported("1.26.0"));
        assertTrue(MinecraftVersionCompatibility.isSupported("1.26.2"));
        assertTrue(MinecraftVersionCompatibility.isSupported("1.26.99"));
    }

    @Test
    void rejectsVersionsOutsideTheSupportedMinorVersion() {
        assertFalse(MinecraftVersionCompatibility.isSupported("1.25.4"));
        assertFalse(MinecraftVersionCompatibility.isSupported("1.27.0"));
        assertFalse(MinecraftVersionCompatibility.isSupported("25.4"));
        assertFalse(MinecraftVersionCompatibility.isSupported("27.0"));
        assertFalse(MinecraftVersionCompatibility.isSupported(""));
        assertFalse(MinecraftVersionCompatibility.isSupported(null));
    }
}
