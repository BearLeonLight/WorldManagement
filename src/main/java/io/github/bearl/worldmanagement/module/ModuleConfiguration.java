package io.github.bearl.worldmanagement.module;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable module enablement snapshot loaded before services are exposed. */
public final class ModuleConfiguration {

    private final Map<ModuleId, Boolean> enabled;

    public ModuleConfiguration(final Map<ModuleId, Boolean> enabled) {
        Objects.requireNonNull(enabled, "enabled");
        final EnumMap<ModuleId, Boolean> values = new EnumMap<>(ModuleId.class);
        for (final ModuleId module : ModuleId.values()) {
            values.put(module, enabled.getOrDefault(module, true));
        }
        this.enabled = Map.copyOf(values);
    }

    public boolean enabled(final ModuleId module) {
        return enabled.get(Objects.requireNonNull(module, "module"));
    }

    public Map<ModuleId, Boolean> values() {
        return enabled;
    }
}
