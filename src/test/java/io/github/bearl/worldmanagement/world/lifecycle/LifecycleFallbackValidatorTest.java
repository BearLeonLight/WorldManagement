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
    void rejectsConfiguredFallbackWithoutManagedMetadata() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();

        assertThrows(
            IllegalArgumentException.class,
            () -> validator.requireUsable(
                Optional.of("lobby"), worldName -> Optional.empty(), expected -> Optional.empty()
            )
        );
    }

    @Test
    void rejectsNonVerifiedOrExternalOnlyFallbackMetadata() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();
        final WorldMetadata managed = WorldMetadata.createDefault("lobby", true);
        final WorldIdentitySnapshot drifted = new WorldIdentitySnapshot(
            managed.identity().paperKey(), managed.identity().worldUuid(),
            WorldEnvironment.NORMAL, managed.identity().seed() + 1, managed.identity().generateStructures()
        );
        final WorldMetadata syncPending = managed.withObservedIdentity(drifted);
        final WorldMetadata externalOnly = WorldMetadata.createDefault(
            "lobby", managed.identity(), LifecycleCapability.EXTERNAL_ONLY, Optional.empty(), true
        );

        assertThrows(IllegalArgumentException.class, () -> validator.requireUsable(
            Optional.of("lobby"), worldName -> Optional.of(syncPending), expected -> Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> validator.requireUsable(
            Optional.of("lobby"), worldName -> Optional.of(externalOnly), expected -> Optional.empty()
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
            worldName -> Optional.of(managed),
            expected -> Optional.of(new WorldRuntimeGateway.LifecycleWorld(replacement, LifecycleCapability.MANAGED))
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
            Optional.empty(), worldName -> Optional.empty(), expected -> Optional.empty()
        ));
        assertDoesNotThrow(() -> validator.requireUsable(
            Optional.of("lobby"), worldName -> Optional.of(managed), expected -> Optional.of(runtime)
        ));
        assertEquals(managed.identity().worldUuid(), validator.resolveUsable(
            Optional.of("lobby"), worldName -> Optional.of(managed), expected -> Optional.of(runtime)
        ).orElseThrow().worldUuid());
    }

    @Test
    void rejectsFallbackWithSnapshotDriftDespiteMatchingKeyAndUuid() {
        final LifecycleFallbackValidator validator = new LifecycleFallbackValidator();
        final WorldMetadata managed = WorldMetadata.createDefault("lobby", true);
        final WorldIdentitySnapshot drifted = new WorldIdentitySnapshot(
            managed.identity().paperKey(), managed.identity().worldUuid(), managed.identity().environment(),
            managed.identity().seed() + 1L, managed.identity().generateStructures()
        );

        assertThrows(IllegalArgumentException.class, () -> validator.resolveUsable(
            Optional.of("lobby"),
            worldName -> Optional.of(managed),
            expected -> Optional.of(new WorldRuntimeGateway.LifecycleWorld(drifted, LifecycleCapability.MANAGED))
        ));
    }
}