package io.github.bearl.worldmanagement.core;

import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugConfiguration;
import io.github.bearl.worldmanagement.config.DebugLevel;
import io.github.bearl.worldmanagement.config.DebugPrivacyConfiguration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public final class DiagnosticLogger {

    private final DebugConfiguration configuration;
    private final Consumer<String> consoleSink;
    private final DiagnosticFileWriter fileWriter;

    public DiagnosticLogger(
        final DebugConfiguration configuration,
        final ComponentLogger componentLogger,
        final DiagnosticFileWriter fileWriter
    ) {
        this(configuration, message -> componentLogger.info(Component.text(message)), fileWriter);
    }

    DiagnosticLogger(
        final DebugConfiguration configuration,
        final Consumer<String> consoleSink,
        final DiagnosticFileWriter fileWriter
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.consoleSink = Objects.requireNonNull(consoleSink, "consoleSink");
        this.fileWriter = fileWriter;
    }

    public void basic(final DebugArea area, final String event, final Supplier<Map<String, String>> fields) {
        log(DebugLevel.BASIC, area, event, fields, null);
    }

    public void verbose(final DebugArea area, final String event, final Supplier<Map<String, String>> fields) {
        log(DebugLevel.VERBOSE, area, event, fields, null);
    }

    public void failure(
        final DebugArea area,
        final String event,
        final Supplier<Map<String, String>> fields,
        final Throwable failure
    ) {
        log(DebugLevel.BASIC, area, event, fields, Objects.requireNonNull(failure, "failure"));
    }

    public boolean enabled(final DebugLevel level, final DebugArea area) {
        return configuration.enabled(level, area)
            && (configuration.consoleEnabled() || configuration.fileEnabled());
    }

    public DebugPrivacyConfiguration privacy() {
        return configuration.privacy();
    }

    private void log(
        final DebugLevel level,
        final DebugArea area,
        final String event,
        final Supplier<Map<String, String>> fields,
        final Throwable failure
    ) {
        Objects.requireNonNull(fields, "fields");
        if (!enabled(level, area)) {
            return;
        }
        final DiagnosticEvent diagnosticEvent = new DiagnosticEvent(
            Instant.now(), level, area, event, fields.get(), Optional.ofNullable(failure)
        );
        if (configuration.consoleEnabled()) {
            consoleSink.accept(DiagnosticTextFormatter.format(diagnosticEvent, false));
        }
        if (configuration.fileEnabled() && fileWriter != null) {
            fileWriter.offer(diagnosticEvent);
        }
    }
}