package io.github.bearl.worldmanagement.core;

import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugFileConfiguration;
import io.github.bearl.worldmanagement.config.DebugLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public final class DiagnosticFileWriter {

    static final int DEFAULT_QUEUE_CAPACITY = 4096;

    private final Path logFile;
    private final long maximumFileSizeBytes;
    private final int retainedFiles;
    private final ArrayBlockingQueue<DiagnosticEvent> queue;
    private final Map<DebugArea, LongAdder> dropped = new java.util.EnumMap<>(DebugArea.class);
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Consumer<String> warningSink;
    private final Thread writerThread;
    private final java.util.concurrent.atomic.AtomicLong nextWarningAt = new java.util.concurrent.atomic.AtomicLong();

    public DiagnosticFileWriter(
        final Path dataDirectory,
        final DebugFileConfiguration configuration,
        final Consumer<String> warningSink
    ) {
        this(dataDirectory, configuration, warningSink, DEFAULT_QUEUE_CAPACITY);
    }

    DiagnosticFileWriter(
        final Path dataDirectory,
        final DebugFileConfiguration configuration,
        final Consumer<String> warningSink,
        final int queueCapacity
    ) {
        this.logFile = dataDirectory.toAbsolutePath().normalize().resolve("logs").resolve("debug.log");
        this.maximumFileSizeBytes = configuration.maximumFileSizeBytes();
        this.retainedFiles = configuration.retainedFiles();
        this.warningSink = java.util.Objects.requireNonNull(warningSink, "warningSink");
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        for (final DebugArea area : DebugArea.values()) {
            dropped.put(area, new LongAdder());
        }
        this.writerThread = new Thread(this::runWriter, "WorldManagement debug writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public boolean offer(final DiagnosticEvent event) {
        if (!accepting.get() || !queue.offer(java.util.Objects.requireNonNull(event, "event"))) {
            dropped.get(event.area()).increment();
            return false;
        }
        return true;
    }

    public boolean close(final Duration timeout) {
        accepting.set(false);
        writerThread.interrupt();
        try {
            writerThread.join(timeout.toMillis());
            return !writerThread.isAlive();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void runWriter() {
        while (accepting.get() || !queue.isEmpty()) {
            try {
                final DiagnosticEvent event = queue.poll(250, TimeUnit.MILLISECONDS);
                if (event != null) {
                    writeDroppedSummaries(event.occurredAt());
                    append(event);
                }
            } catch (final InterruptedException exception) {
                if (accepting.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (final IOException exception) {
                warn("Could not write WorldManagement debug log: " + exception.getMessage());
            }
        }
        try {
            writeDroppedSummaries(Instant.now());
        } catch (final IOException exception) {
            warn("Could not finish WorldManagement debug log: " + exception.getMessage());
        }
    }

    private void warn(final String message) {
        final long now = System.nanoTime();
        final long expected = nextWarningAt.get();
        if (now >= expected && nextWarningAt.compareAndSet(expected, now + TimeUnit.MINUTES.toNanos(1))) {
            warningSink.accept(message);
        }
    }

    private void writeDroppedSummaries(final Instant occurredAt) throws IOException {
        for (final Map.Entry<DebugArea, LongAdder> entry : dropped.entrySet()) {
            final long count = entry.getValue().sumThenReset();
            if (count > 0) {
                append(new DiagnosticEvent(
                    occurredAt,
                    DebugLevel.BASIC,
                    entry.getKey(),
                    "events_dropped",
                    Map.of("count", Long.toString(count)),
                    Optional.empty()
                ));
            }
        }
    }

    private void append(final DiagnosticEvent event) throws IOException {
        Files.createDirectories(logFile.getParent());
        final String line = DiagnosticTextFormatter.format(event, true) + System.lineSeparator();
        rotateIfNeeded(line.getBytes(StandardCharsets.UTF_8).length, event.occurredAt());
        Files.writeString(logFile, line, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private void rotateIfNeeded(final int nextLineBytes, final Instant occurredAt) throws IOException {
        if (Files.notExists(logFile) || Files.size(logFile) + nextLineBytes <= maximumFileSizeBytes) {
            return;
        }
        final String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(java.time.ZoneOffset.UTC)
            .format(occurredAt);
        Files.move(logFile, logFile.resolveSibling("debug-" + timestamp + "-" + System.nanoTime() + ".log"));
        retainNewestArchives();
    }

    private void retainNewestArchives() throws IOException {
        try (var archives = Files.list(logFile.getParent())) {
            final var ordered = archives
                .filter(path -> path.getFileName().toString().startsWith("debug-")
                    && path.getFileName().toString().endsWith(".log"))
                .sorted(Comparator.comparingLong(this::lastModified).reversed())
                .toList();
            for (int index = retainedFiles; index < ordered.size(); index++) {
                Files.deleteIfExists(ordered.get(index));
            }
        }
    }

    private long lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException exception) {
            return Long.MIN_VALUE;
        }
    }
}