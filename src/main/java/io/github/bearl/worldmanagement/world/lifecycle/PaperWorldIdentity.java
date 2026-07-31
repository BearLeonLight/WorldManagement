package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import java.util.Objects;
import org.bukkit.World;

/** Immutable identity facts captured from a Paper world on its owning thread. */
public record PaperWorldIdentity(
    WorldIdentitySnapshot snapshot,
    LifecycleCapability lifecycleCapability,
    String bukkitWorldName
) {

    public PaperWorldIdentity {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(lifecycleCapability, "lifecycleCapability");
        if (Objects.requireNonNull(bukkitWorldName, "bukkitWorldName").isBlank()) {
            throw new IllegalArgumentException("bukkitWorldName must not be blank.");
        }
    }

    public static PaperWorldIdentity capture(final World world) {
        final World requiredWorld = Objects.requireNonNull(world, "world");
        final WorldIdentitySnapshot snapshot = new WorldIdentitySnapshot(
            requiredWorld.getKey().toString(),
            requiredWorld.getUID(),
            WorldEnvironment.valueOf(requiredWorld.getEnvironment().name()),
            requiredWorld.getSeed(),
            requiredWorld.canGenerateStructures()
        );
        final LifecycleCapability lifecycleCapability = requiredWorld.getEnvironment() == World.Environment.CUSTOM
            || !"minecraft".equals(requiredWorld.getKey().getNamespace())
            || requiredWorld.getGenerator() != null
            || requiredWorld.getBiomeProvider() != null
            ? LifecycleCapability.EXTERNAL_ONLY
            : LifecycleCapability.MANAGED;
        return new PaperWorldIdentity(snapshot, lifecycleCapability, requiredWorld.getName());
    }
}