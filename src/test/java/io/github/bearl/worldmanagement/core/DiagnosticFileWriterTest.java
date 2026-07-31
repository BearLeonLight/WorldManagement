package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugFileConfiguration;
import io.github.bearl.worldmanagement.config.DebugLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiagnosticFileWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesEscapedSingleLineAndClosesWithinDeadline() throws Exception {
        final DiagnosticFileWriter writer = new DiagnosticFileWriter(
            temporaryDirectory, new DebugFileConfiguration(1, 2), failure -> { }, 8
        );

        assertTrue(writer.offer(new DiagnosticEvent(Instant.now(), DebugLevel.BASIC, DebugArea.STORAGE,
            "write", Map.of("detail", "line1\nline2"), Optional.empty())));
        assertTrue(writer.close(Duration.ofSeconds(2)));

        final String output = Files.readString(temporaryDirectory.resolve("logs/debug.log"));
        assertTrue(output.contains("[Debug/storage] write"));
        assertTrue(output.contains("line1\\nline2"));
        assertFalse(output.contains("line1\nline2"));
    }
}