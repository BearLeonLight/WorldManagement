package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

final class ConsoleOutputTest {

    @Test
    void serializesComponentsWithIndexedAnsiColor() {
        final String output = ConsoleOutput.serialize(Component.text("WorldManagement", NamedTextColor.AQUA));

        assertTrue(output.contains("\u001B["));
        assertTrue(output.contains("WorldManagement"));
    }
}