package io.github.bearl.worldmanagement.module;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves enabled feature modules without exposing configuration details to consumers. */
public final class ModuleManager {

    private final Map<ModuleId, WorldManagementModule> modules;
    private final ModuleConfiguration configuration;

    public ModuleManager(final Collection<? extends WorldManagementModule> modules, final ModuleConfiguration configuration) {
        Objects.requireNonNull(modules, "modules");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        final EnumMap<ModuleId, WorldManagementModule> indexed = new EnumMap<>(ModuleId.class);
        for (final WorldManagementModule module : modules) {
            final WorldManagementModule previous = indexed.put(Objects.requireNonNull(module, "module").id(), module);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate module: " + module.id());
            }
        }
        this.modules = Map.copyOf(indexed);
    }

    public boolean enabled(final ModuleId module) {
        return modules.containsKey(module) && configuration.enabled(module);
    }

    public List<ModuleId> enabledModules() {
        return modules.keySet().stream()
            .filter(this::enabled)
            .sorted()
            .toList();
    }
}
