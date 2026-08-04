package io.github.bearl.worldmanagement.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable metadata aggregate persisted by every supported storage provider. */
public record WorldMetadata(
    String worldName,
    String displayName,
    WorldIdentitySnapshot identity,
    IdentityVerificationState identityState,
    LifecycleCapability lifecycleCapability,
    Optional<WorldIdentitySnapshot> pendingIdentity,
    Optional<RequestedWorldType> requestedWorldType,
    Optional<WorldGeneratorReference> generator,
    WorldManagementState managementState,
    WorldLoadState desiredState,
    String owner,
    boolean rankSystemEnabled,
    AccessControl accessControl,
    Map<String, Rank> ranks,
    Map<UUID, String> playerRanks,
    Map<String, WorldWarp> warps,
    long version,
    Optional<UUID> deletionTransactionId,
    WorldRegistrationSource registrationSource
) {

    public static final String OWNER_RANK = "OWNER";
    public static final String GUEST_RANK = "GUEST";
    public static final String SERVER_OWNER = "server";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Z0-9_-]+$");

    public WorldMetadata {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(identity, "identity").requireWorldId(worldName);
        Objects.requireNonNull(identityState, "identityState");
        Objects.requireNonNull(lifecycleCapability, "lifecycleCapability");
        pendingIdentity = Objects.requireNonNull(pendingIdentity, "pendingIdentity");
        requestedWorldType = Objects.requireNonNull(requestedWorldType, "requestedWorldType");
        generator = Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(managementState, "managementState");
        Objects.requireNonNull(desiredState, "desiredState");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(accessControl, "accessControl");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank.");
        }
        pendingIdentity.ifPresent(snapshot -> snapshot.requireWorldId(worldName));
        if ((identityState == IdentityVerificationState.VERIFIED) != pendingIdentity.isEmpty()) {
            throw new IllegalArgumentException("Only non-verified metadata may contain a pending identity snapshot.");
        }
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank.");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative.");
        }
        deletionTransactionId = Objects.requireNonNull(deletionTransactionId, "deletionTransactionId");
        Objects.requireNonNull(registrationSource, "registrationSource");
        if (managementState != WorldManagementState.DELETING && deletionTransactionId.isPresent()) {
            throw new IllegalArgumentException("Only DELETING metadata may contain a deletion transaction.");
        }

        ranks = immutableRanks(ranks);
        playerRanks = Map.copyOf(playerRanks);
        warps = immutableWarps(warps);
        validateRanks(ranks, playerRanks);
    }

    public WorldMetadata(
        final String worldName,
        final String displayName,
        final WorldIdentitySnapshot identity,
        final IdentityVerificationState identityState,
        final LifecycleCapability lifecycleCapability,
        final Optional<WorldIdentitySnapshot> pendingIdentity,
        final Optional<RequestedWorldType> requestedWorldType,
        final Optional<WorldGeneratorReference> generator,
        final WorldManagementState managementState,
        final WorldLoadState desiredState,
        final String owner,
        final boolean rankSystemEnabled,
        final AccessControl accessControl,
        final Map<String, Rank> ranks,
        final Map<UUID, String> playerRanks,
        final Map<String, WorldWarp> warps,
        final long version
    ) {
        this(
            worldName, displayName, identity, identityState, lifecycleCapability, pendingIdentity,
            requestedWorldType, generator, managementState, desiredState, owner, rankSystemEnabled,
            accessControl, ranks, playerRanks, warps, version, Optional.empty(),
            WorldRegistrationSource.STANDARD
        );
    }

    public WorldMetadata(
        final String worldName,
        final String displayName,
        final WorldIdentitySnapshot identity,
        final IdentityVerificationState identityState,
        final LifecycleCapability lifecycleCapability,
        final Optional<WorldIdentitySnapshot> pendingIdentity,
        final Optional<RequestedWorldType> requestedWorldType,
        final WorldManagementState managementState,
        final WorldLoadState desiredState,
        final String owner,
        final boolean rankSystemEnabled,
        final AccessControl accessControl,
        final Map<String, Rank> ranks,
        final Map<UUID, String> playerRanks,
        final Map<String, WorldWarp> warps,
        final long version
    ) {
        this(
            worldName, displayName, identity, identityState, lifecycleCapability, pendingIdentity,
            requestedWorldType, Optional.empty(), managementState, desiredState, owner, rankSystemEnabled,
            accessControl, ranks, playerRanks, warps, version
        );
    }

    public WorldMetadata(
        final String worldName,
        final String worldKey,
        final WorldManagementState managementState,
        final WorldLoadState desiredState,
        final String owner,
        final boolean rankSystemEnabled,
        final AccessControl accessControl,
        final Map<String, Rank> ranks,
        final Map<UUID, String> playerRanks,
        final Map<String, WorldWarp> warps,
        final long version
    ) {
        this(
            worldName,
            worldName,
            legacyIdentity(worldName, worldKey),
            IdentityVerificationState.VERIFIED,
            LifecycleCapability.MANAGED,
            Optional.empty(),
            Optional.empty(),
            managementState,
            desiredState,
            owner,
            rankSystemEnabled,
            accessControl,
            ranks,
            playerRanks,
            warps,
            version
        );
    }

    public WorldMetadata(
        final String worldName,
        final String owner,
        final boolean rankSystemEnabled,
        final AccessControl accessControl,
        final Map<String, Rank> ranks,
        final Map<UUID, String> playerRanks,
        final Map<String, WorldWarp> warps,
        final long version
    ) {
        this(
            worldName,
            worldName,
            legacyIdentity(worldName, "minecraft:" + worldName),
            IdentityVerificationState.VERIFIED,
            LifecycleCapability.MANAGED,
            Optional.empty(),
            Optional.empty(),
            WorldManagementState.ACTIVE,
            WorldLoadState.LOADED,
            owner,
            rankSystemEnabled,
            accessControl,
            ranks,
            playerRanks,
            warps,
            version
        );
    }

    public static WorldMetadata createDefault(final String worldName, final boolean rankSystemEnabled) {
        return createDefault(
            worldName,
            legacyIdentity(worldName, "minecraft:" + worldName),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            rankSystemEnabled
        );
    }

    public static WorldMetadata createDefault(
        final String worldName,
        final WorldIdentitySnapshot identity,
        final LifecycleCapability lifecycleCapability,
        final Optional<RequestedWorldType> requestedWorldType,
        final boolean rankSystemEnabled
    ) {
        return createDefault(
            worldName, identity, lifecycleCapability, requestedWorldType, Optional.empty(), rankSystemEnabled
        );
    }

    public static WorldMetadata createDefault(
        final String worldName,
        final WorldIdentitySnapshot identity,
        final LifecycleCapability lifecycleCapability,
        final Optional<RequestedWorldType> requestedWorldType,
        final Optional<WorldGeneratorReference> generator,
        final boolean rankSystemEnabled
    ) {
        return createDefault(
            worldName, identity, lifecycleCapability, requestedWorldType, generator,
            WorldManagementState.ACTIVE, rankSystemEnabled
        );
    }

    public static WorldMetadata createDefault(
        final String worldName,
        final WorldIdentitySnapshot identity,
        final LifecycleCapability lifecycleCapability,
        final Optional<RequestedWorldType> requestedWorldType,
        final Optional<WorldGeneratorReference> generator,
        final WorldManagementState managementState,
        final boolean rankSystemEnabled
    ) {
        return createDefault(
            worldName, identity, lifecycleCapability, requestedWorldType, generator,
            managementState, WorldRegistrationSource.STANDARD, rankSystemEnabled
        );
    }

    public static WorldMetadata createDefault(
        final String worldName,
        final WorldIdentitySnapshot identity,
        final LifecycleCapability lifecycleCapability,
        final Optional<RequestedWorldType> requestedWorldType,
        final Optional<WorldGeneratorReference> generator,
        final WorldManagementState managementState,
        final WorldRegistrationSource registrationSource,
        final boolean rankSystemEnabled
    ) {
        final WorldManagementState initialState = Objects.requireNonNull(managementState, "managementState");
        if (initialState != WorldManagementState.ACTIVE && initialState != WorldManagementState.DETACHED) {
            throw new IllegalArgumentException("New metadata must be ACTIVE or DETACHED.");
        }
        return new WorldMetadata(
            worldName,
            worldName,
            identity,
            IdentityVerificationState.VERIFIED,
            lifecycleCapability,
            Optional.empty(),
            requestedWorldType,
            generator,
            initialState,
            WorldLoadState.LOADED,
            SERVER_OWNER,
            rankSystemEnabled,
            AccessControl.unrestricted(),
            Map.of(
                OWNER_RANK, new Rank("World Owner", Set.of()),
                GUEST_RANK, new Rank("Guest", Set.of())
            ),
            Map.of(),
            Map.of(),
            0,
            Optional.empty(),
            Objects.requireNonNull(registrationSource, "registrationSource")
        );
    }

    public WorldMetadata withAccessControl(final AccessControl updatedAccessControl) {
        return new WorldMetadata(
            worldName,
            displayName,
            identity,
            identityState,
            lifecycleCapability,
            pendingIdentity,
            requestedWorldType,
            generator,
            managementState,
            desiredState,
            owner,
            rankSystemEnabled,
            updatedAccessControl,
            ranks,
            playerRanks,
            warps,
            version + 1,
            deletionTransactionId,
            registrationSource
        );
    }

    public WorldMetadata withDisplayName(final ValidatedDisplayName updatedDisplayName) {
        return copyDisplayName(Objects.requireNonNull(updatedDisplayName, "updatedDisplayName").miniMessage());
    }

    public WorldMetadata resetDisplayName() {
        return copyDisplayName(worldName);
    }

    public WorldMetadata withOwner(final String updatedOwner) {
        return new WorldMetadata(
            worldName,
            displayName,
            identity,
            identityState,
            lifecycleCapability,
            pendingIdentity,
            requestedWorldType,
            generator,
            managementState,
            desiredState,
            updatedOwner,
            rankSystemEnabled,
            accessControl,
            ranks,
            playerRanks,
            warps,
            version + 1,
            deletionTransactionId,
            registrationSource
        );
    }

    public WorldMetadata withRankSystemEnabled(final boolean enabled) {
        return new WorldMetadata(
            worldName,
            displayName,
            identity,
            identityState,
            lifecycleCapability,
            pendingIdentity,
            requestedWorldType,
            generator,
            managementState,
            desiredState,
            owner,
            enabled,
            accessControl,
            ranks,
            playerRanks,
            warps,
            version + 1,
            deletionTransactionId,
            registrationSource
        );
    }

    public WorldMetadata withDesiredState(final WorldLoadState updatedDesiredState) {
        return new WorldMetadata(
            worldName, displayName, identity, identityState, lifecycleCapability, pendingIdentity, requestedWorldType, generator,
            managementState,
            Objects.requireNonNull(updatedDesiredState, "updatedDesiredState"),
            owner, rankSystemEnabled, accessControl, ranks, playerRanks, warps, version + 1,
            deletionTransactionId, registrationSource
        );
    }

    public WorldMetadata withManagementState(final WorldManagementState updatedManagementState) {
        return new WorldMetadata(
            worldName, displayName, identity, identityState, lifecycleCapability, pendingIdentity, requestedWorldType, generator,
            Objects.requireNonNull(updatedManagementState, "updatedManagementState"), desiredState,
            owner, rankSystemEnabled, accessControl, ranks, playerRanks, warps, version + 1,
            Optional.empty(), registrationSource
        );
    }

    public WorldMetadata withDeleting(final UUID transactionId) {
        return new WorldMetadata(
            worldName, displayName, identity, identityState, lifecycleCapability, pendingIdentity,
            requestedWorldType, generator, WorldManagementState.DELETING, desiredState, owner,
            rankSystemEnabled, accessControl, ranks, playerRanks, warps, version + 1,
            Optional.of(Objects.requireNonNull(transactionId, "transactionId")), registrationSource
        );
    }

    public WorldMetadata withManagementAndDesiredState(
        final WorldManagementState updatedManagementState,
        final WorldLoadState updatedDesiredState
    ) {
        return new WorldMetadata(
            worldName, displayName, identity, identityState, lifecycleCapability, pendingIdentity, requestedWorldType, generator,
            Objects.requireNonNull(updatedManagementState, "updatedManagementState"),
            Objects.requireNonNull(updatedDesiredState, "updatedDesiredState"),
            owner, rankSystemEnabled, accessControl, ranks, playerRanks, warps, version + 1,
            Optional.empty(), registrationSource
        );
    }

    public WorldMetadata withRank(final String rankId, final Rank rank) {
        return withRank(rankId, rank, Integer.MAX_VALUE);
    }

    public WorldMetadata withRank(final String rankId, final Rank rank, final int maximumCustomRanks) {
        if (maximumCustomRanks < 0) {
            throw new IllegalArgumentException("maximumCustomRanks must not be negative.");
        }
        final Map<String, Rank> updatedRanks = new LinkedHashMap<>(ranks);
        if (updatedRanks.size() - 2 >= maximumCustomRanks) {
            throw new IllegalArgumentException("Maximum custom rank count reached.");
        }
        if (updatedRanks.putIfAbsent(rankId, Objects.requireNonNull(rank, "rank")) != null) {
            throw new IllegalArgumentException("Rank already exists: " + rankId);
        }
        return copy(updatedRanks, playerRanks, accessControl);
    }

    public WorldMetadata withoutRank(final String rankId) {
        if (OWNER_RANK.equals(rankId) || GUEST_RANK.equals(rankId)) {
            throw new IllegalArgumentException("System ranks cannot be removed.");
        }
        if (!ranks.containsKey(rankId)) {
            throw new IllegalArgumentException("Unknown rank: " + rankId);
        }
        final Map<String, Rank> updatedRanks = new LinkedHashMap<>(ranks);
        updatedRanks.remove(rankId);
        final Map<UUID, String> updatedPlayerRanks = new LinkedHashMap<>(playerRanks);
        updatedPlayerRanks.replaceAll((playerId, assignedRank) -> rankId.equals(assignedRank) ? GUEST_RANK : assignedRank);
        return copy(updatedRanks, updatedPlayerRanks, accessControl);
    }

    public WorldMetadata withRankPermission(final String rankId, final RankPermission permission, final boolean granted) {
        if (OWNER_RANK.equals(rankId)) {
            throw new IllegalArgumentException("OWNER rank permissions cannot be modified.");
        }
        final Rank currentRank = requireRank(rankId);
        final Set<RankPermission> permissions = new java.util.LinkedHashSet<>(currentRank.permissions());
        if (granted) {
            permissions.add(Objects.requireNonNull(permission, "permission"));
        } else {
            permissions.remove(Objects.requireNonNull(permission, "permission"));
        }
        final Map<String, Rank> updatedRanks = new LinkedHashMap<>(ranks);
        updatedRanks.put(rankId, new Rank(currentRank.displayName(), permissions));
        return copy(updatedRanks, playerRanks, accessControl);
    }

    public WorldMetadata withPlayerRank(final UUID playerId, final String rankId) {
        requireRank(rankId);
        final Map<UUID, String> updatedPlayerRanks = new LinkedHashMap<>(playerRanks);
        updatedPlayerRanks.put(Objects.requireNonNull(playerId, "playerId"), rankId);
        return copy(ranks, updatedPlayerRanks, accessControl);
    }

    public WorldMetadata withoutPlayerRank(final UUID playerId) {
        final Map<UUID, String> updatedPlayerRanks = new LinkedHashMap<>(playerRanks);
        updatedPlayerRanks.remove(Objects.requireNonNull(playerId, "playerId"));
        return copy(ranks, updatedPlayerRanks, accessControl);
    }

    public WorldMetadata withWarp(final WorldWarp warp) {
        final WorldWarp requiredWarp = Objects.requireNonNull(warp, "warp");
        final Map<String, WorldWarp> updatedWarps = new LinkedHashMap<>(warps);
        updatedWarps.put(requiredWarp.name(), requiredWarp);
        return copy(ranks, playerRanks, accessControl, updatedWarps);
    }

    public WorldMetadata withoutWarp(final String warpName) {
        final Map<String, WorldWarp> updatedWarps = new LinkedHashMap<>(warps);
        if (updatedWarps.remove(Objects.requireNonNull(warpName, "warpName")) == null) {
            throw new IllegalArgumentException("Unknown warp: " + warpName);
        }
        return copy(ranks, playerRanks, accessControl, updatedWarps);
    }

    public WorldMetadata withTrustedWarpPlayer(final String warpName, final UUID playerId, final boolean trusted) {
        final WorldWarp warp = warps.get(Objects.requireNonNull(warpName, "warpName"));
        if (warp == null) {
            throw new IllegalArgumentException("Unknown warp: " + warpName);
        }
        return withWarp(warp.withTrustedPlayer(playerId, trusted));
    }

    public String worldKey() {
        return identity.paperKey();
    }

    public WorldMetadata withObservedIdentity(final WorldIdentitySnapshot observedIdentity) {
        return withObservedIdentity(observedIdentity, lifecycleCapability);
    }

    public WorldMetadata withObservedIdentity(
        final WorldIdentitySnapshot observedIdentity,
        final LifecycleCapability observedCapability
    ) {
        final WorldIdentitySnapshot observed = Objects.requireNonNull(observedIdentity, "observedIdentity")
            .requireWorldId(worldName);
        final LifecycleCapability classifiedCapability = lifecycleCapability == LifecycleCapability.EXTERNAL_ONLY
            || Objects.requireNonNull(observedCapability, "observedCapability") == LifecycleCapability.EXTERNAL_ONLY
            ? LifecycleCapability.EXTERNAL_ONLY
            : LifecycleCapability.MANAGED;
        if (identity.equals(observed)) {
            return identityState == IdentityVerificationState.VERIFIED
                && lifecycleCapability == classifiedCapability ? this : copyIdentity(
                identity, IdentityVerificationState.VERIFIED, Optional.empty(), classifiedCapability, warps
            );
        }
        final IdentityVerificationState observedState = identity.hasSameDurableIdentity(observed)
            ? IdentityVerificationState.SYNC_PENDING
            : IdentityVerificationState.CONFLICT;
        if (identityState == observedState
            && pendingIdentity.equals(Optional.of(observed))
            && lifecycleCapability == classifiedCapability) {
            return this;
        }
        return copyIdentity(identity, observedState, Optional.of(observed), classifiedCapability, warps);
    }

    public WorldMetadata synchronizePendingIdentity() {
        if (identityState != IdentityVerificationState.SYNC_PENDING) {
            throw new IllegalStateException("Only pending snapshot drift may be synchronized.");
        }
        final WorldIdentitySnapshot pending = pendingIdentity.orElseThrow(() ->
            new IllegalStateException("Pending snapshot drift has no observed identity."));
        if (!identity.hasSameDurableIdentity(pending)) {
            throw new IllegalStateException("Identity replacement cannot be synchronized.");
        }
        return copyIdentity(
            pending,
            IdentityVerificationState.VERIFIED,
            Optional.empty(),
            lifecycleCapability,
            warps
        );
    }

    public WorldMetadata acceptPendingReplacement(final boolean keepWarps) {
        if (identityState != IdentityVerificationState.CONFLICT) {
            throw new IllegalStateException("Only a conflicting observed world may be accepted as a replacement.");
        }
        final WorldIdentitySnapshot replacement = pendingIdentity.orElseThrow(() ->
            new IllegalStateException("Conflicting world has no observed identity."));
        return copyIdentity(
            replacement,
            IdentityVerificationState.VERIFIED,
            Optional.empty(),
            lifecycleCapability,
            keepWarps ? warps : Map.of()
        );
    }

    public WorldMetadata abandonNonVerifiedIdentity() {
        if (identityState == IdentityVerificationState.VERIFIED) {
            throw new IllegalStateException("Verified world metadata cannot be abandoned through identity recovery.");
        }
        return new WorldMetadata(
            worldName,
            displayName,
            identity,
            identityState,
            lifecycleCapability,
            pendingIdentity,
            requestedWorldType,
            generator,
            WorldManagementState.DETACHED,
            WorldLoadState.UNLOADED,
            owner,
            rankSystemEnabled,
            accessControl,
            ranks,
            playerRanks,
            warps,
            version + 1,
            Optional.empty(),
            registrationSource
        );
    }

    private WorldMetadata copy(
        final Map<String, Rank> updatedRanks,
        final Map<UUID, String> updatedPlayerRanks,
        final AccessControl updatedAccessControl
    ) {
        return copy(updatedRanks, updatedPlayerRanks, updatedAccessControl, warps);
    }

    private WorldMetadata copy(
        final Map<String, Rank> updatedRanks,
        final Map<UUID, String> updatedPlayerRanks,
        final AccessControl updatedAccessControl,
        final Map<String, WorldWarp> updatedWarps
    ) {
        return new WorldMetadata(
            worldName,
            displayName,
            identity,
            identityState,
            lifecycleCapability,
            pendingIdentity,
            requestedWorldType,
            generator,
            managementState,
            desiredState,
            owner,
            rankSystemEnabled,
            updatedAccessControl,
            updatedRanks,
            updatedPlayerRanks,
            updatedWarps,
            version + 1,
            deletionTransactionId,
            registrationSource
        );
    }

    private WorldMetadata copyDisplayName(final String updatedDisplayName) {
        return new WorldMetadata(
            worldName,
            updatedDisplayName,
            identity,
            identityState,
            lifecycleCapability,
            pendingIdentity,
            requestedWorldType,
            generator,
            managementState,
            desiredState,
            owner,
            rankSystemEnabled,
            accessControl,
            ranks,
            playerRanks,
            warps,
            version + 1,
            deletionTransactionId,
            registrationSource
        );
    }

    private WorldMetadata copyIdentity(
        final WorldIdentitySnapshot updatedIdentity,
        final IdentityVerificationState updatedIdentityState,
        final Optional<WorldIdentitySnapshot> updatedPendingIdentity,
        final LifecycleCapability updatedLifecycleCapability,
        final Map<String, WorldWarp> updatedWarps
    ) {
        return new WorldMetadata(
            worldName,
            displayName,
            updatedIdentity,
            updatedIdentityState,
            updatedLifecycleCapability,
            updatedPendingIdentity,
            requestedWorldType,
            generator,
            managementState,
            desiredState,
            owner,
            rankSystemEnabled,
            accessControl,
            ranks,
            playerRanks,
            updatedWarps,
            version + 1,
            deletionTransactionId,
            registrationSource
        );
    }

    private Rank requireRank(final String rankId) {
        final Rank rank = ranks.get(rankId);
        if (rank == null) {
            throw new IllegalArgumentException("Unknown rank: " + rankId);
        }
        return rank;
    }

    private static Map<String, WorldWarp> immutableWarps(final Map<String, WorldWarp> warps) {
        Objects.requireNonNull(warps, "warps");
        final Map<String, WorldWarp> copiedWarps = new LinkedHashMap<>();
        warps.forEach((name, warp) -> {
            final WorldWarp requiredWarp = Objects.requireNonNull(warp, "warp");
            if (!requiredWarp.name().equals(name)) {
                throw new IllegalArgumentException("Warp map key must match its name.");
            }
            copiedWarps.put(name, requiredWarp);
        });
        return Map.copyOf(copiedWarps);
    }

    private static Map<String, Rank> immutableRanks(final Map<String, Rank> ranks) {
        Objects.requireNonNull(ranks, "ranks");
        final Map<String, Rank> copiedRanks = new LinkedHashMap<>();
        ranks.forEach((rankId, rank) -> {
            if (rankId == null || !IDENTIFIER_PATTERN.matcher(rankId).matches()) {
                throw new IllegalArgumentException("Invalid rank identifier.");
            }
            copiedRanks.put(rankId, Objects.requireNonNull(rank, "rank"));
        });
        return Map.copyOf(copiedRanks);
    }

    private static void validateRanks(final Map<String, Rank> ranks, final Map<UUID, String> playerRanks) {
        if (!ranks.containsKey(OWNER_RANK) || !ranks.containsKey(GUEST_RANK)) {
            throw new IllegalArgumentException("World metadata must contain OWNER and GUEST ranks.");
        }
        playerRanks.forEach((playerId, rankId) -> {
            Objects.requireNonNull(playerId, "playerId");
            if (!ranks.containsKey(rankId)) {
                throw new IllegalArgumentException("Player rank must reference an existing rank.");
            }
        });
    }

    private static WorldIdentitySnapshot legacyIdentity(final String worldName, final String worldKey) {
        final UUID generatedUuid = UUID.nameUUIDFromBytes(
            ("worldmanagement:" + worldKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        return new WorldIdentitySnapshot(worldKey, generatedUuid, WorldEnvironment.NORMAL, 0L, true)
            .requireWorldId(worldName);
    }
}
