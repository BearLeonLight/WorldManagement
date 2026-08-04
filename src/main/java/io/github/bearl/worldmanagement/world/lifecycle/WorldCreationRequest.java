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
    }

    public WorldCreationRequest(
        final String worldName,
        final WorldRuntimeGateway.WorldEnvironment environment,
        final WorldRuntimeGateway.WorldType type,
        final OptionalLong seed,
        final Optional<WorldGeneratorReference> generator
    ) {
        this(worldName, environment, type, seed, generator, false);
    }
}