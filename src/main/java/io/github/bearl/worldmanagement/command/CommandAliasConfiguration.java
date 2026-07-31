package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.module.ModuleId;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable command labels loaded before Paper registers the Brigadier command trees. */
public record CommandAliasConfiguration(
    List<String> rootAliases,
    Map<ModuleId, List<String>> moduleAliases
) {

    private static final Set<ModuleId> ALIASABLE_MODULES = Set.of(ModuleId.WARP, ModuleId.OWNERSHIP, ModuleId.STORAGE);

    public CommandAliasConfiguration {
        rootAliases = validateAliases(rootAliases, "root");
        final EnumMap<ModuleId, List<String>> normalized = new EnumMap<>(ModuleId.class);
        ALIASABLE_MODULES.forEach(module -> normalized.put(module, List.of()));
        Objects.requireNonNull(moduleAliases, "moduleAliases").forEach((module, aliases) -> {
            if (!ALIASABLE_MODULES.contains(module)) {
                throw new IllegalArgumentException("Module does not support command aliases: " + module);
            }
            normalized.put(module, validateAliases(aliases, module.name().toLowerCase(Locale.ROOT)));
        });
        final LinkedHashSet<String> labels = new LinkedHashSet<>(rootAliases);
        normalized.forEach((module, aliases) -> aliases.forEach(alias -> {
            if (!labels.add(alias)) {
                throw new IllegalArgumentException("Duplicate command alias: " + alias);
            }
        }));
        if (labels.contains("wm")) {
            throw new IllegalArgumentException("The canonical wm command cannot be configured as an alias.");
        }
        moduleAliases = Map.copyOf(normalized);
    }

    public static CommandAliasConfiguration defaults() {
        return new CommandAliasConfiguration(List.of("worldmanager", "worldmanagement"), Map.of());
    }

    public List<String> aliases(final ModuleId module) {
        return moduleAliases.getOrDefault(Objects.requireNonNull(module, "module"), List.of());
    }

    private static List<String> validateAliases(final List<String> aliases, final String scope) {
        final LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (final String alias : Objects.requireNonNull(aliases, scope + " aliases")) {
            final String value = Objects.requireNonNull(alias, scope + " alias").trim();
            if (value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf(':') >= 0 || value.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid command alias: " + alias);
            }
            if (!normalized.add(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate command alias: " + alias);
            }
        }
        return List.copyOf(normalized);
    }
}