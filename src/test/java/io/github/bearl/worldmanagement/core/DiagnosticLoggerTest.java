package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugConfiguration;
import io.github.bearl.worldmanagement.config.DebugFileConfiguration;
import io.github.bearl.worldmanagement.config.DebugLevel;
import io.github.bearl.worldmanagement.config.DebugPrivacyConfiguration;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DiagnosticLoggerTest {

    @Test
    void doesNotEvaluateDisabledMessages() {
        final AtomicInteger evaluations = new AtomicInteger();
        final DiagnosticLogger logger = new DiagnosticLogger(configuration(DebugLevel.OFF), message -> { }, null);

        logger.basic(DebugArea.STARTUP, "enabled", () -> {
            evaluations.incrementAndGet();
            return Map.of();
        });

        assertEquals(0, evaluations.get());
    }

    @Test
    void emitsStructuredConsoleMessage() {
        final StringBuilder output = new StringBuilder();
        final DiagnosticLogger logger = new DiagnosticLogger(configuration(DebugLevel.BASIC), output::append, null);

        logger.basic(DebugArea.COMMAND, "command_completed", () -> Map.of("route", "warp teleport", "outcome", "success"));

        assertTrue(output.toString().contains("[Debug/command] command_completed"));
        assertTrue(output.toString().contains("route=warp teleport"));
    }

    private static DebugConfiguration configuration(final DebugLevel level) {
        return new DebugConfiguration(level, true, false, EnumSet.allOf(DebugArea.class),
            new DebugFileConfiguration(10, 5), new DebugPrivacyConfiguration(false, false, false));
    }
}