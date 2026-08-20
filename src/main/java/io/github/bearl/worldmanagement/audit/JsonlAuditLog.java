package io.github.bearl.worldmanagement.audit;

import io.github.bearl.worldmanagement.storage.StorageException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Append-only audit log. Call append only from PluginIoExecutor. */
public final class JsonlAuditLog implements AuditStore {

    private static final Pattern ARCHIVE_FILE_NAME = Pattern.compile(
        "audit-\\d{4}-\\d{2}-\\d{2}--?\\d+\\.jsonl"
    );

    private final Path auditFile;
    private final long maximumFileSizeBytes;
    private final int retainedFiles;
    private final Consumer<String> warningSink;

    public JsonlAuditLog(final Path dataDirectory) {
        this(dataDirectory, 10L * 1024L * 1024L, 10, ignored -> { });
    }

    public JsonlAuditLog(final Path dataDirectory, final long maximumFileSizeBytes) {
        this(dataDirectory, maximumFileSizeBytes, 10, ignored -> { });
    }

    public JsonlAuditLog(
        final Path dataDirectory,
        final long maximumFileSizeBytes,
        final int retainedFiles
    ) {
        this(dataDirectory, maximumFileSizeBytes, retainedFiles, ignored -> { });
    }

    public JsonlAuditLog(
        final Path dataDirectory,
        final long maximumFileSizeBytes,
        final int retainedFiles,
        final Consumer<String> warningSink
    ) {
        this.auditFile = Objects.requireNonNull(dataDirectory, "dataDirectory")
            .toAbsolutePath().normalize().resolve("audit.jsonl");
        if (maximumFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maximumFileSizeBytes must be positive.");
        }
        if (retainedFiles < 1 || retainedFiles > 100) {
            throw new IllegalArgumentException("retainedFiles must be between 1 and 100.");
        }
        this.maximumFileSizeBytes = maximumFileSizeBytes;
        this.retainedFiles = retainedFiles;
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
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
        pruneArchives();
    }

    private void pruneArchives() {
        try (Stream<Path> files = Files.list(auditFile.getParent())) {
            final List<RetainedFile> archives = new ArrayList<>();
            for (final Path file : files.toList()) {
                final String fileName = file.getFileName().toString();
                if (ARCHIVE_FILE_NAME.matcher(fileName).matches()
                    && Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    archives.add(new RetainedFile(file, Files.getLastModifiedTime(file)));
                }
            }
            archives.sort(Comparator.comparing(RetainedFile::modified)
                .thenComparing(retained -> retained.path().getFileName().toString())
                .reversed());
            for (int index = retainedFiles; index < archives.size(); index++) {
                Files.deleteIfExists(archives.get(index).path());
            }
        } catch (final IOException | RuntimeException exception) {
            warnRetentionFailure("Could not enforce JSONL audit archive retention: " + exception.getMessage());
        }
    }

    private void warnRetentionFailure(final String message) {
        try {
            warningSink.accept(message);
        } catch (final RuntimeException ignored) {
            // Retention cleanup is best-effort and must not reject the current audit append.
        }
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

    private record RetainedFile(Path path, FileTime modified) {
    }
}
