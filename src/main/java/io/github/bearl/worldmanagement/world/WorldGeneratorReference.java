package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.regex.Pattern;

public record WorldGeneratorReference(String pluginName, String id) {

    private static final Pattern PLUGIN_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    public WorldGeneratorReference {
        if (!PLUGIN_NAME_PATTERN.matcher(Objects.requireNonNull(pluginName, "pluginName")).matches()) {
            throw new IllegalArgumentException("Generator plugin name is invalid.");
        }
        Objects.requireNonNull(id, "id");
        if (id.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Generator id is invalid.");
        }
    }

    public static WorldGeneratorReference parse(final String value) {
        final String required = Objects.requireNonNull(value, "value");
        final int separator = required.indexOf(':');
        if (separator == required.length() - 1) {
            throw new IllegalArgumentException("Generator id must not be empty.");
        }
        return separator < 0
            ? new WorldGeneratorReference(required, "")
            : new WorldGeneratorReference(required.substring(0, separator), required.substring(separator + 1));
    }

    public String serialized() {
        return id.isEmpty() ? pluginName : pluginName + ':' + id;
    }
}