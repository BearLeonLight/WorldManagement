package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.WorldGeneratorReference;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record WorldCreationRequest(
    String worldName,
    WorldRuntimeGateway.WorldEnvironment environment,
    WorldRuntimeGateway.WorldType type,
    OptionalLong seed,
    Optional<WorldGeneratorReference> generator,
    Optional<String> generatorSettings,
    boolean generateStructures,
    boolean bonusChest,
    Optional<WorldGeneratorReference> biomeProvider,
    Optional<WorldSpawnPosition> forcedSpawnPosition,
    boolean detached
) {

    public WorldCreationRequest {
        if (Objects.requireNonNull(worldName, "worldName").isBlank()) {
            throw new IllegalArgumentException("World name must not be blank.");
        }
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(type, "type");
        seed = Objects.requireNonNull(seed, "seed");
        generator = Objects.requireNonNull(generator, "generator");
        generatorSettings = Objects.requireNonNull(generatorSettings, "generatorSettings");
        generatorSettings.ifPresent(settings -> {
            if (settings.isBlank()) {
                throw new IllegalArgumentException("Generator settings must not be blank.");
            }
        });
        biomeProvider = Objects.requireNonNull(biomeProvider, "biomeProvider");
        forcedSpawnPosition = Objects.requireNonNull(forcedSpawnPosition, "forcedSpawnPosition");
        if (bonusChest && forcedSpawnPosition.isPresent()) {
            throw new IllegalArgumentException("Paper cannot generate a bonus chest with a forced spawn position.");
        }
    }

    public WorldCreationRequest(
        final String worldName,
        final WorldRuntimeGateway.WorldEnvironment environment,
        final WorldRuntimeGateway.WorldType type,
        final OptionalLong seed,
        final Optional<WorldGeneratorReference> generator
    ) {
        this(
            worldName, environment, type, seed, generator, Optional.empty(), true, false,
            Optional.empty(), Optional.empty(), false
        );
    }

    public WorldCreationRequest(
        final String worldName,
        final WorldRuntimeGateway.WorldEnvironment environment,
        final WorldRuntimeGateway.WorldType type,
        final OptionalLong seed,
        final Optional<WorldGeneratorReference> generator,
        final boolean detached
    ) {
        this(
            worldName, environment, type, seed, generator, Optional.empty(), true, false,
            Optional.empty(), Optional.empty(), detached
        );
    }
}