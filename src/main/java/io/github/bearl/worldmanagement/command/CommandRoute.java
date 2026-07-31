package io.github.bearl.worldmanagement.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable canonical command template used by every equivalent Brigadier entry point. */
public record CommandRoute(List<String> prefix, List<String> suffix) {

    public CommandRoute {
        prefix = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
        suffix = List.copyOf(Objects.requireNonNull(suffix, "suffix"));
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("A command route needs a canonical prefix.");
        }
    }

    public CommandRoute(final List<String> prefix) {
        this(prefix, List.of());
    }

    public String[] arguments(final List<String> parsedArguments) {
        final List<String> combined = new ArrayList<>(prefix);
        combined.addAll(Objects.requireNonNull(parsedArguments, "parsedArguments"));
        combined.addAll(suffix);
        return combined.toArray(String[]::new);
    }
}