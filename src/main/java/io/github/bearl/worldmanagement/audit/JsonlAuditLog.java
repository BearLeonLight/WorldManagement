package io.github.bearl.worldmanagement.audit;

import io.github.bearl.worldmanagement.storage.StorageException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/** Append-only audit log. Call append only from PluginIoExecutor. */
public final class JsonlAuditLog implements AuditStore {

    private final Path auditFile;
    private final long maximumFileSizeBytes;

    public JsonlAuditLog(final Path dataDirectory) {
        this(dataDirectory, 10L * 1024L * 1024L);
    }

    public JsonlAuditLog(final Path dataDirectory, final long maximumFileSizeBytes) {
        this.auditFile = Objects.requireNonNull(dataDirectory, "dataDirectory")
            .toAbsolutePath().normalize().resolve("audit.jsonl");
        if (maximumFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maximumFileSizeBytes must be positive.");
        }
        this.maximumFileSizeBytes = maximumFileSizeBytes;
    }

    @Override
    public void append(final AuditEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            Files.createDirectories(auditFile.getParent());
            rotateIfNeeded(event);
            Files.writeString(auditFile, encode(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (final IOException exception) {
            throw new StorageException("Could not append audit event.", exception);
        }
    }

    private void rotateIfNeeded(final AuditEvent event) throws IOException {
        if (Files.notExists(auditFile) || Files.size(auditFile) < maximumFileSizeBytes) {
            return;
        }
        final String date = LocalDate.ofInstant(event.occurredAt(), ZoneOffset.UTC).toString();
        final Path archive = auditFile.resolveSibling("audit-" + date + "-" + System.nanoTime() + ".jsonl");
        Files.move(auditFile, archive);
    }

    private static String encode(final AuditEvent event) {
        return "{\"occurredAt\":\"" + escape(event.occurredAt().toString())
            + "\",\"actor\":" + event.actor().map(actor -> "\"" + escape(actor) + "\"").orElse("null")
            + ",\"action\":\"" + escape(event.action())
            + "\",\"worldName\":\"" + escape(event.worldName())
            + "\",\"detail\":\"" + escape(event.detail()) + "\"}";
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}