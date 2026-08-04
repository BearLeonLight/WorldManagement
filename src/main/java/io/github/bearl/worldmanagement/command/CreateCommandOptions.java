package io.github.bearl.worldmanagement.command;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

record CreateCommandOptions(
    OptionalLong seed,
    Optional<String> generator,
    Optional<String> generatorSettings,
    boolean generateStructures,
    boolean bonusChest,
    Optional<String> biomeProvider,
    Optional<io.github.bearl.worldmanagement.world.lifecycle.WorldSpawnPosition> forcedSpawnPosition,
    boolean detached
) {

    CreateCommandOptions {
        seed = Objects.requireNonNull(seed, "seed");
        generator = Objects.requireNonNull(generator, "generator");
        generatorSettings = Objects.requireNonNull(generatorSettings, "generatorSettings");
        biomeProvider = Objects.requireNonNull(biomeProvider, "biomeProvider");
        forcedSpawnPosition = Objects.requireNonNull(forcedSpawnPosition, "forcedSpawnPosition");
        java.util.stream.Stream.concat(generator.stream(), biomeProvider.stream()).forEach(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Provider reference must not be blank.");
            }
        });
        generatorSettings.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Generator settings must not be blank.");
            }
        });
        if (bonusChest && forcedSpawnPosition.isPresent()) {
            throw new IllegalArgumentException("Bonus chest and forced spawn position cannot be combined.");
        }
    }

    static CreateCommandOptions defaults() {
        return new CreateCommandOptions(
            OptionalLong.empty(), Optional.empty(), Optional.empty(), true, false,
            Optional.empty(), Optional.empty(), false
        );
    }
}