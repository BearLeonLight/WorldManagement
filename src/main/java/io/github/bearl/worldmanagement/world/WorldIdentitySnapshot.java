package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable Paper-owned identity and generation facts captured from a world. */
public record WorldIdentitySnapshot(
    String paperKey,
    UUID worldUuid,
    WorldEnvironment environment,
    long seed,
    boolean generateStructures
) {

    private static final Pattern PAPER_KEY_PATTERN = Pattern.compile("^[a-z0-9._-]+:[a-z0-9._/-]+$");
    private static final Pattern WORLD_ID_PATTERN = Pattern.compile("^[a-z0-9_-]+$");

    public WorldIdentitySnapshot {
        Objects.requireNonNull(paperKey, "paperKey");
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(environment, "environment");
        if (!PAPER_KEY_PATTERN.matcher(paperKey).matches()) {
            throw new IllegalArgumentException("paperKey must be a complete lowercase namespaced key.");
        }
    }

    public String keyValue() {
        return paperKey.substring(paperKey.indexOf(':') + 1);
    }

    public WorldIdentitySnapshot requireWorldId(final String worldId) {
        Objects.requireNonNull(worldId, "worldId");
        if (!WORLD_ID_PATTERN.matcher(worldId).matches()) {
            throw new IllegalArgumentException(
                "World IDs may only contain lowercase letters, numbers, underscores, and hyphens."
            );
        }
        if (!worldId.equals(keyValue())) {
            throw new IllegalArgumentException("Paper key value must match the world ID.");
        }
        return this;
    }

    public boolean hasSameDurableIdentity(final WorldIdentitySnapshot observed) {
        final WorldIdentitySnapshot requiredObserved = Objects.requireNonNull(observed, "observed");
        return paperKey.equals(requiredObserved.paperKey) && worldUuid.equals(requiredObserved.worldUuid);
    }
}