package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.Optional;

/** Cache-only decision for applying persisted authority to an observed Paper world. */
public record WorldRuntimeResolution(Status status, Optional<WorldMetadata> metadata) {

    public WorldRuntimeResolution {
        Objects.requireNonNull(status, "status");
        metadata = Objects.requireNonNull(metadata, "metadata");
        if ((status == Status.UNMANAGED) != metadata.isEmpty()) {
            throw new IllegalArgumentException("Only unmanaged runtime worlds may omit metadata.");
        }
    }

    public static WorldRuntimeResolution unmanaged() {
        return new WorldRuntimeResolution(Status.UNMANAGED, Optional.empty());
    }

    public static WorldRuntimeResolution verified(final WorldMetadata metadata) {
        return new WorldRuntimeResolution(Status.VERIFIED, Optional.of(Objects.requireNonNull(metadata, "metadata")));
    }

    public static WorldRuntimeResolution isolated(final WorldMetadata metadata) {
        return new WorldRuntimeResolution(Status.ISOLATED, Optional.of(Objects.requireNonNull(metadata, "metadata")));
    }

    public enum Status {
        UNMANAGED,
        VERIFIED,
        ISOLATED
    }
}