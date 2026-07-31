package io.github.bearl.worldmanagement.core;

import java.util.Objects;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Formats concise startup progress messages without owning scheduling or I/O. */
public final class StartupDiagnostics {

    private final LongSupplier nanoTime;
    private final long startupStartedAt;

    public StartupDiagnostics() {
        this(System::nanoTime);
    }

    StartupDiagnostics(final LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startupStartedAt = nanoTime.getAsLong();
    }

    public long beginStage() {
        return nanoTime.getAsLong();
    }

    public String section(final String name) {
        return "    %s".formatted(Objects.requireNonNull(name, "name"));
    }

    public String detail(final String message) {
        return "        %s".formatted(Objects.requireNonNull(message, "message"));
    }

    public String stageStarted(final String stage) {
        return "    %s...".formatted(Objects.requireNonNull(stage, "stage"));
    }

    public String stageCompleted(final String stage, final long stageStartedAt, final String summary) {
        return "    %s%s. Took %dms".formatted(
            Objects.requireNonNull(stage, "stage"),
            formatSummary(summary),
            elapsedMillis(stageStartedAt, nanoTime.getAsLong())
        );
    }

    public String startupCompleted(final String summary) {
        return "%s. Took %dms".formatted(
            Objects.requireNonNull(summary, "summary"),
            elapsedMillis(startupStartedAt, nanoTime.getAsLong())
        );
    }

    public String stageFailed(final String stage, final long stageStartedAt) {
        return "    Failed during %s after %dms.".formatted(
            Objects.requireNonNull(stage, "stage"),
            elapsedMillis(stageStartedAt, nanoTime.getAsLong())
        );
    }

    public Component headingComponent(final String message) {
        return Component.text(Objects.requireNonNull(message, "message"), NamedTextColor.AQUA);
    }

    public Component sectionComponent(final String name) {
        return Component.text(section(name), NamedTextColor.GOLD);
    }

    public Component detailComponent(final String message) {
        return Component.text(detail(message), NamedTextColor.GRAY);
    }

    public Component successComponent(final String message) {
        return Component.text(Objects.requireNonNull(message, "message"), NamedTextColor.GREEN);
    }

    private static long elapsedMillis(final long startedAt, final long completedAt) {
        return Math.max(0L, (completedAt - startedAt) / 1_000_000L);
    }

    private static String formatSummary(final String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        return ": " + summary;
    }
}