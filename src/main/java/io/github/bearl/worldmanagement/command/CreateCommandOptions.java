package io.github.bearl.worldmanagement.command;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

record CreateCommandOptions(OptionalLong seed, Optional<String> generator) {

    CreateCommandOptions {
        seed = Objects.requireNonNull(seed, "seed");
        generator = Objects.requireNonNull(generator, "generator");
        generator.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Generator reference must not be blank.");
            }
        });
    }

    static CreateCommandOptions defaults() {
        return new CreateCommandOptions(OptionalLong.empty(), Optional.empty());
    }
}