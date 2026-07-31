package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class LifecycleFallbackValidator {

    public void requireUsable(
        final Optional<String> configuredFallback,
        final Function<String, Optional<WorldMetadata>> metadataLookup,
        final Function<VerifiedWorldRef, Optional<WorldRuntimeGateway.LifecycleWorld>> runtimeLookup
    ) {
        resolveUsable(configuredFallback, metadataLookup, runtimeLookup);
    }

    public Optional<VerifiedWorldRef> resolveUsable(
        final Optional<String> configuredFallback,
        final Function<String, Optional<WorldMetadata>> metadataLookup,
        final Function<VerifiedWorldRef, Optional<WorldRuntimeGateway.LifecycleWorld>> runtimeLookup
    ) {
        Objects.requireNonNull(configuredFallback, "configuredFallback");
        Objects.requireNonNull(metadataLookup, "metadataLookup");
        Objects.requireNonNull(runtimeLookup, "runtimeLookup");
        return configuredFallback.map(worldName -> {
            final WorldMetadata metadata = metadataLookup.apply(worldName).orElseThrow(() ->
                new IllegalArgumentException(
                    "Configured lifecycle.fallback-world is not actively managed: " + worldName
                )
            );
            final VerifiedWorldRef expected = VerifiedWorldRef.from(metadata).orElseThrow(() ->
                new IllegalArgumentException(
                    "Configured lifecycle.fallback-world identity is not verified: " + worldName
                )
            );
            if (!metadata.lifecycleCapability().permitsManagedLifecycle()) {
                throw new IllegalArgumentException(
                    "Configured lifecycle.fallback-world is externally managed: " + worldName
                );
            }
            final WorldRuntimeGateway.LifecycleWorld observed = runtimeLookup.apply(expected).orElse(null);
            if (observed == null
                || !metadata.identity().equals(observed.identity())
                || metadata.lifecycleCapability() != observed.lifecycleCapability()) {
                throw new IllegalArgumentException(
                    "Configured lifecycle.fallback-world is not loaded with its verified identity: " + worldName
                );
            }
            return expected;
        });
    }
}