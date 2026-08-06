package io.github.bearl.worldmanagement.hook;

import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.RegistrySnapshot;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

/** Resolves public WorldManagement placeholders from immutable in-memory snapshots only. */
public final class WorldPlaceholderResolver {

    public static final String NAMESPACE = "wm";

    public static final Set<String> GLOBAL_KEYS = Set.of(
        "managed_world_count",
        "detached_world_count"
    );
    public static final Set<String> WORLD_KEYS = Set.of(
        "world_exists",
        "world_display_name",
        "world_environment",
        "world_management_state",
        "world_desired_state",
        "world_identity_state",
        "world_lifecycle_capability",
        "world_runtime_loaded",
        "world_access_mode",
        "world_rank_system_enabled",
        "world_custom_rank_count",
        "world_warp_count"
    );
    public static final Set<String> AUDIENCE_KEYS = Set.of(
        "current_world_managed",
        "current_world_id",
        "current_world_display_name",
        "current_world_environment",
        "current_world_management_state",
        "current_world_desired_state",
        "current_world_identity_state",
        "current_world_access_mode",
        "current_world_is_owner",
        "current_world_rank_id",
        "current_world_rank_display_name"
    );

    private static final Value EMPTY = Value.literal("");
    private static final Value TRUE = Value.literal("true");
    private static final Value FALSE = Value.literal("false");

    private final Supplier<RegistrySnapshot> metadataSupplier;
    private final Predicate<VerifiedWorldRef> loadedWorldLookup;
    private final Function<UUID, Optional<VerifiedWorldRef>> playerWorldLookup;
    private final AtomicReference<CachedMetadata> cache = new AtomicReference<>();

    public WorldPlaceholderResolver(
        final Supplier<RegistrySnapshot> metadataSupplier,
        final Predicate<VerifiedWorldRef> loadedWorldLookup,
        final Function<UUID, Optional<VerifiedWorldRef>> playerWorldLookup
    ) {
        this.metadataSupplier = Objects.requireNonNull(metadataSupplier, "metadataSupplier");
        this.loadedWorldLookup = Objects.requireNonNull(loadedWorldLookup, "loadedWorldLookup");
        this.playerWorldLookup = Objects.requireNonNull(playerWorldLookup, "playerWorldLookup");
    }

    public Optional<Value> resolve(final String key, final Optional<String> argument, final UUID playerId) {
        final String requiredKey = Objects.requireNonNull(key, "key").toLowerCase(Locale.ROOT);
        final Optional<String> requiredArgument = Objects.requireNonNull(argument, "argument");
        final CachedMetadata metadata = cachedMetadata();
        return switch (requiredKey) {
            case "managed_world_count" -> Optional.of(Value.literal(metadata.managedWorldCount()));
            case "detached_world_count" -> Optional.of(Value.literal(metadata.detachedWorldCount()));
            case "world_exists" -> worldArgument(requiredArgument, worldId ->
                metadata.worlds().containsKey(worldId) ? TRUE : FALSE);
            case "world_display_name" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::displayName));
            case "world_environment" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::environment));
            case "world_management_state" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::managementState));
            case "world_desired_state" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::desiredState));
            case "world_identity_state" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::identityState));
            case "world_lifecycle_capability" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::lifecycleCapability));
            case "world_runtime_loaded" -> worldArgument(requiredArgument, worldId ->
                runtimeLoaded(metadata.worlds().get(worldId)) ? TRUE : FALSE);
            case "world_access_mode" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::accessMode));
            case "world_rank_system_enabled" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::rankSystemEnabled));
            case "world_custom_rank_count" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::customRankCount));
            case "world_warp_count" -> worldArgument(requiredArgument, worldId ->
                worldValue(metadata, worldId, WorldValue::warpCount));
            case "current_world_managed" -> Optional.of(currentWorld(metadata, playerId).isPresent() ? TRUE : FALSE);
            case "current_world_id" -> Optional.of(currentWorld(metadata, playerId)
                .map(world -> Value.literal(world.metadata().worldName())).orElse(EMPTY));
            case "current_world_display_name" -> Optional.of(currentValue(metadata, playerId, WorldValue::displayName));
            case "current_world_environment" -> Optional.of(currentValue(metadata, playerId, WorldValue::environment));
            case "current_world_management_state" -> Optional.of(currentValue(metadata, playerId, WorldValue::managementState));
            case "current_world_desired_state" -> Optional.of(currentValue(metadata, playerId, WorldValue::desiredState));
            case "current_world_identity_state" -> Optional.of(currentValue(metadata, playerId, WorldValue::identityState));
            case "current_world_access_mode" -> Optional.of(currentValue(metadata, playerId, WorldValue::accessMode));
            case "current_world_is_owner" -> Optional.of(currentWorld(metadata, playerId)
                .map(world -> world.metadata().owner().equals(playerId.toString()) ? TRUE : FALSE)
                .orElse(FALSE));
            case "current_world_rank_id" -> Optional.of(currentWorld(metadata, playerId)
                .map(world -> Value.literal(rankId(world.metadata(), playerId))).orElse(EMPTY));
            case "current_world_rank_display_name" -> Optional.of(currentWorld(metadata, playerId)
                .map(world -> Value.literal(world.metadata().ranks().get(rankId(world.metadata(), playerId)).displayName()))
                .orElse(EMPTY));
            default -> Optional.empty();
        };
    }

    private CachedMetadata cachedMetadata() {
        final RegistrySnapshot snapshot = Objects.requireNonNull(metadataSupplier.get(), "metadata snapshot");
        final CachedMetadata current = cache.get();
        if (current != null && current.source() == snapshot) {
            return current;
        }
        final CachedMetadata replacement = buildCache(snapshot);
        cache.set(replacement);
        return replacement;
    }

    private static CachedMetadata buildCache(final RegistrySnapshot snapshot) {
        final DisplayNameValidator displayNames = new DisplayNameValidator();
        final Map<String, WorldValue> worlds = new LinkedHashMap<>();
        int managed = 0;
        int detached = 0;
        for (final WorldMetadata world : snapshot.worlds()) {
            if (world.managementState() == WorldManagementState.ACTIVE) {
                managed++;
            } else if (world.managementState() == WorldManagementState.DETACHED) {
                detached++;
            }
            final var displayName = displayNames.validate(world.displayName());
            worlds.put(world.worldName(), new WorldValue(
                world,
                new Value(displayName.plainText(), displayName.component()),
                Value.literal(enumValue(world.identity().environment())),
                Value.literal(enumValue(world.managementState())),
                Value.literal(enumValue(world.desiredState())),
                Value.literal(enumValue(world.identityState())),
                Value.literal(enumValue(world.lifecycleCapability())),
                Value.literal(enumValue(world.accessControl().mode())),
                world.rankSystemEnabled() ? TRUE : FALSE,
                Value.literal(Math.max(0, world.ranks().size() - 2)),
                Value.literal(world.warps().size())
            ));
        }
        return new CachedMetadata(snapshot, Map.copyOf(worlds), managed, detached);
    }

    private Optional<CurrentWorld> currentWorld(final CachedMetadata metadata, final UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        final VerifiedWorldRef observed = playerWorldLookup.apply(playerId).orElse(null);
        if (observed == null) {
            return Optional.empty();
        }
        final WorldValue world = metadata.worlds().get(observed.worldId());
        if (world == null
            || world.metadata().managementState() != WorldManagementState.ACTIVE
            || world.metadata().identityState() != IdentityVerificationState.VERIFIED
            || !VerifiedWorldRef.from(world.metadata()).equals(Optional.of(observed))
            || !runtimeLoaded(world)) {
            return Optional.empty();
        }
        return Optional.of(new CurrentWorld(world.metadata(), world));
    }

    private boolean runtimeLoaded(final WorldValue world) {
        return world != null && VerifiedWorldRef.from(world.metadata()).filter(loadedWorldLookup).isPresent();
    }

    private Value currentValue(
        final CachedMetadata metadata,
        final UUID playerId,
        final Function<WorldValue, Value> value
    ) {
        return currentWorld(metadata, playerId).map(CurrentWorld::value).map(value).orElse(EMPTY);
    }

    private static String rankId(final WorldMetadata metadata, final UUID playerId) {
        if (metadata.owner().equals(playerId.toString())) {
            return WorldMetadata.OWNER_RANK;
        }
        return metadata.playerRanks().getOrDefault(playerId, WorldMetadata.GUEST_RANK);
    }

    private static Optional<Value> worldArgument(
        final Optional<String> argument,
        final Function<String, Value> value
    ) {
        return argument.filter(worldId -> !worldId.isBlank()).map(value);
    }

    private static Value worldValue(
        final CachedMetadata metadata,
        final String worldId,
        final Function<WorldValue, Value> value
    ) {
        final WorldValue world = metadata.worlds().get(worldId);
        return world == null ? EMPTY : value.apply(world);
    }

    private static String enumValue(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    public record Value(String plainText, Component component) {
        public Value {
            Objects.requireNonNull(plainText, "plainText");
            Objects.requireNonNull(component, "component");
        }

        public static Value literal(final Object value) {
            final String text = Objects.toString(value);
            return new Value(text, Component.text(text));
        }
    }

    private record CachedMetadata(
        RegistrySnapshot source,
        Map<String, WorldValue> worlds,
        int managedWorldCount,
        int detachedWorldCount
    ) {
    }

    private record WorldValue(
        WorldMetadata metadata,
        Value displayName,
        Value environment,
        Value managementState,
        Value desiredState,
        Value identityState,
        Value lifecycleCapability,
        Value accessMode,
        Value rankSystemEnabled,
        Value customRankCount,
        Value warpCount
    ) {
    }

    private record CurrentWorld(WorldMetadata metadata, WorldValue value) {
    }
}