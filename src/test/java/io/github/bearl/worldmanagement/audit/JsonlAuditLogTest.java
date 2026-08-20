package io.github.bearl.worldmanagement.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JsonlAuditLogTest {

    @Test
    void disablesAuditSubmissionWhenPolicyIsOff() {
        final io.github.bearl.worldmanagement.core.PluginIoExecutor executor = new io.github.bearl.worldmanagement.core.PluginIoExecutor("AuditTest");
        try {
            final AuditService service = new AuditService(executor, new JsonlAuditLog(temporaryDirectory),
                java.util.logging.Logger.getLogger("AuditTest"), AuditPolicy.OFF);

            assertEquals(AuditAdmission.DISABLED, service.record("server", "world.create", "creative", ""));
        } finally {
            executor.shutdown(java.time.Duration.ofSeconds(1));
        }
    }

    @Test
    void inheritsStrictPolicyFromParentAction() {
        final io.github.bearl.worldmanagement.core.PluginIoExecutor executor = new io.github.bearl.worldmanagement.core.PluginIoExecutor("AuditTest");
        try {
            final AuditService service = new AuditService(
                executor,
                event -> { },
                java.util.logging.Logger.getLogger("AuditTest"),
                AuditPolicy.BEST_EFFORT,
                java.util.Map.of("world.remove", AuditPolicy.STRICT)
            );

            assertTrue(service.requiresStrictAdmission("world.remove.purge"));
        } finally {
            executor.shutdown(java.time.Duration.ofSeconds(1));
        }
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void appendsEscapedJsonLines() throws Exception {
        final JsonlAuditLog auditLog = new JsonlAuditLog(temporaryDirectory);

        auditLog.append(new AuditEvent(Instant.EPOCH, Optional.of("Alex"), "warp.set", "creative", "name=\"spawn\""));

        final String content = Files.readString(temporaryDirectory.resolve("audit.jsonl"));
        assertTrue(content.contains("\"action\":\"warp.set\""));
        assertTrue(content.contains("name=\\\"spawn\\\""));
    }

    @Test
    void rotatesWhenAuditFileExceedsConfiguredLimit() throws Exception {
        final JsonlAuditLog auditLog = new JsonlAuditLog(temporaryDirectory, 1);
        auditLog.append(new AuditEvent(Instant.EPOCH, Optional.of("Alex"), "warp.set", "creative", "one"));
        auditLog.append(new AuditEvent(Instant.EPOCH, Optional.of("Alex"), "warp.set", "creative", "two"));

        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("audit-1970-01-01-")));
        }
    }

    @Test
    void retainsOnlyTheNewestConfiguredAuditArchives() throws Exception {
        final JsonlAuditLog auditLog = new JsonlAuditLog(temporaryDirectory, 1, 2);
        final Path unmanagedFile = temporaryDirectory.resolve("audit-manual.jsonl");
        Files.writeString(unmanagedFile, "preserve me");
        for (int index = 0; index < 5; index++) {
            auditLog.append(new AuditEvent(
                Instant.EPOCH.plusSeconds(index), Optional.of("Alex"), "warp.set", "creative", "event-" + index
            ));
        }

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("audit.jsonl")));
        assertEquals("preserve me", Files.readString(unmanagedFile));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(2, files.filter(path -> {
                final String name = path.getFileName().toString();
                return name.startsWith("audit-1970-01-01-") && name.endsWith(".jsonl");
            }).count());
        }
    }
}
