package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LifecycleFallbackValidatorTest {

    @Test
    void acceptsConfiguredFallbackWithoutManagedMetadataWhenUniquelyLoaded() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();
        final WorldMetadata runtimeMetadata = WorldMetadata.createDefault("lobby", true);
        final WorldRuntimeGateway.LifecycleWorld runtime = new WorldRuntimeGateway.LifecycleWorld(
            runtimeMetadata.identity(), LifecycleCapability.MANAGED
        );

        assertEquals(runtime.reference(), validator.resolveUsable(
            Optional.of("lobby"), worldName -> Optional.of(runtime)
        ).orElseThrow());
    }

    @Test
    void acceptsDetachedOrExternalRuntimeFallback() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();
        final WorldMetadata managed = WorldMetadata.createDefault("lobby", true);
        final WorldRuntimeGateway.LifecycleWorld external = new WorldRuntimeGateway.LifecycleWorld(
            managed.identity(), LifecycleCapability.EXTERNAL_ONLY
        );

        assertDoesNotThrow(() -> validator.requireUsable(
            Optional.of("lobby"), worldName -> Optional.of(external)
        ));
    }

    @Test
    void rejectsLoadedFallbackWithReplacementIdentity() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();
        final WorldMetadata managed = WorldMetadata.createDefault("lobby", true);
        final WorldIdentitySnapshot replacement = new WorldIdentitySnapshot(
            managed.identity().paperKey(),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            managed.identity().environment(), managed.identity().seed(), managed.identity().generateStructures()
        );

        assertThrows(IllegalArgumentException.class, () -> validator.requireUsable(
            Optional.of("lobby"),
            worldName -> Optional.empty()
        ));
    }

    @Test
    void acceptsAbsentOrVerifiedManagedLoadedFallback() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();
        final WorldMetadata managed = WorldMetadata.createDefault("lobby", true);
        final WorldRuntimeGateway.LifecycleWorld runtime = new WorldRuntimeGateway.LifecycleWorld(
            managed.identity(), LifecycleCapability.MANAGED
        );

        assertDoesNotThrow(() -> validator.requireUsable(
            Optional.empty(), worldName -> Optional.empty()
        ));
        assertDoesNotThrow(() -> validator.requireUsable(
            Optional.of("lobby"), worldName -> Optional.of(runtime)
        ));
        assertEquals(managed.identity().worldUuid(), validator.resolveUsable(
            Optional.of("lobby"), worldName -> Optional.of(runtime)
        ).orElseThrow().worldUuid());
    }

    @Test
    void pinsObservedRuntimeSnapshot() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();
        final WorldMetadata managed = WorldMetadata.createDefault("lobby", true);
        final WorldIdentitySnapshot drifted = new WorldIdentitySnapshot(
            managed.identity().paperKey(), managed.identity().worldUuid(), managed.identity().environment(),
            managed.identity().seed() + 1L, managed.identity().generateStructures()
        );

        assertEquals(drifted.worldUuid(), validator.resolveUsable(
            Optional.of("lobby"),
            worldName -> Optional.of(new WorldRuntimeGateway.LifecycleWorld(
                drifted, LifecycleCapability.MANAGED
            ))
        ).orElseThrow().worldUuid());
    }
}