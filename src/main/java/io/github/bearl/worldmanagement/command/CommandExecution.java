package io.github.bearl.worldmanagement.command;

import java.util.List;
import java.util.Objects;

record CommandExecution(CommandRoute route, List<CommandArgumentBinding> arguments) {

    CommandExecution {
        Objects.requireNonNull(route, "route");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    List<String> argumentNames() {
        return arguments.stream().map(CommandArgumentBinding::name).toList();
    }
}