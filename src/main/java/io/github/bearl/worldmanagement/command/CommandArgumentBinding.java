package io.github.bearl.worldmanagement.command;

import java.util.Objects;

record CommandArgumentBinding(String name, Class<?> type) {

    CommandArgumentBinding {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Command argument binding name must not be blank.");
        }
        Objects.requireNonNull(type, "type");
    }
}