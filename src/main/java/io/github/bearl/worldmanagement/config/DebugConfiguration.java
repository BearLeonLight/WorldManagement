package io.github.bearl.worldmanagement.config;

import java.util.Objects;
import java.util.Set;

public record DebugConfiguration(
    DebugLevel level,
    boolean consoleEnabled,
    boolean fileEnabled,
    Set<DebugArea> areas,
    DebugFileConfiguration file,
    DebugPrivacyConfiguration privacy
) {

    public DebugConfiguration {
        Objects.requireNonNull(level, "level");
        areas = Set.copyOf(Objects.requireNonNull(areas, "areas"));
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(privacy, "privacy");
    }

    public boolean enabled(final DebugLevel required, final DebugArea area) {
        return level.includes(Objects.requireNonNull(required, "required"))
            && areas.contains(Objects.requireNonNull(area, "area"));
    }
}