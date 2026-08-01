package io.github.bearl.worldmanagement.command;

import java.util.List;
import java.util.Objects;

record CommandExecution(CommandRoute route, List<String> argumentNames) {

    CommandExecution {
        Objects.requireNonNull(route, "route");
        argumentNames = List.copyOf(Objects.requireNonNull(argumentNames, "argumentNames"));
        if (argumentNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("Execution argument names must not be blank.");
        }
    }
}