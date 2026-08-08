package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class StartupDiagnosticsTest {

    @Test
    void formatsStageAndTotalDurations() {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final StartupDiagnostics diagnostics = new StartupDiagnostics(clock::get);

        clock.set(3_000_000L);
        final long storageStartedAt = diagnostics.beginStage();
        clock.set(21_000_000L);

        assertEquals("    Storage", diagnostics.section("Storage"));
        assertEquals("        Provider: YAML", diagnostics.detail("Provider: YAML"));
        assertEquals("    Loading configuration and storage...", diagnostics.stageStarted("Loading configuration and storage"));
        assertEquals(
            "    Configuration and storage loaded: YAML metadata storage, locale zh_TW. Took 18ms",
            diagnostics.stageCompleted("Configuration and storage loaded", storageStartedAt, "YAML metadata storage, locale zh_TW")
        );

        clock.set(57_000_000L);
        assertEquals(
            "WorldManagement 1.0.0 enabled. Took 56ms",
            diagnostics.startupCompleted("WorldManagement 1.0.0 enabled")
        );
    }

    @Test
    void formatsFailuresAndIgnoresNegativeDurations() {
        final AtomicLong clock = new AtomicLong(20_000_000L);
        final StartupDiagnostics diagnostics = new StartupDiagnostics(clock::get);

        clock.set(10_000_000L);

        assertEquals("    Failed during metadata loading after 0ms.", diagnostics.stageFailed("metadata loading", 20_000_000L));
    }
}