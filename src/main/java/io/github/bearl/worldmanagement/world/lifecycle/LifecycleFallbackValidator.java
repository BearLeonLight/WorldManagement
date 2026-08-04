package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class LifecycleFallbackValidator {

    public void requireUsable(
        final Optional<String> configuredFallback,
        final Function<String, Optional<WorldRuntimeGateway.LifecycleWorld>> runtimeLookup
    ) {
        resolveUsable(configuredFallback, runtimeLookup);
    }

    public Optional<VerifiedWorldRef> resolveUsable(
        final Optional<String> configuredFallback,
        final Function<String, Optional<WorldRuntimeGateway.LifecycleWorld>> runtimeLookup
    ) {
        Objects.requireNonNull(configuredFallback, "configuredFallback");
        Objects.requireNonNull(runtimeLookup, "runtimeLookup");
        return configuredFallback.map(worldName ->
            runtimeLookup.apply(worldName).orElseThrow(() ->
                new IllegalArgumentException(
                    "Configured lifecycle.fallback-world is not uniquely loaded: " + worldName
                )
            ).reference()
        );
    }
}