package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/** Reusable, immutable-snapshot-backed completion sources for command trees. */
public final class SuggestionCatalog {

    public static final SuggestionKey<String> MANAGED_WORLD = new SuggestionKey<>("managed-world", String.class);
    public static final SuggestionKey<String> LIFECYCLE_WORLD = new SuggestionKey<>("lifecycle-world", String.class);
    public static final SuggestionKey<String> DETACHED_WORLD = new SuggestionKey<>("detached-world", String.class);
    public static final SuggestionKey<String> MANAGEABLE_WORLD = new SuggestionKey<>("manageable-world", String.class);
    public static final SuggestionKey<String> VISIBLE_WARP = new SuggestionKey<>("visible-warp", String.class);
    public static final SuggestionKey<String> WORLD_WARP = new SuggestionKey<>("world-warp", String.class);
    public static final SuggestionKey<String> ONLINE_PLAYER = new SuggestionKey<>("online-player", String.class);
    public static final SuggestionKey<String> RANK_ID = new SuggestionKey<>("rank-id", String.class);
    public static final SuggestionKey<String> STORAGE_PROVIDER = new SuggestionKey<>("storage-provider", String.class);
    public static final SuggestionKey<String> IDENTITY_WORLD = new SuggestionKey<>("identity-world", String.class);
    public static final SuggestionKey<String> SYNC_PENDING_WORLD = new SuggestionKey<>("sync-pending-world", String.class);
    public static final SuggestionKey<String> CONFLICT_WORLD = new SuggestionKey<>("conflict-world", String.class);
    public static final SuggestionKey<String> NON_VERIFIED_WORLD = new SuggestionKey<>("non-verified-world", String.class);
    public static final SuggestionKey<String> GENERATOR_PLUGIN = new SuggestionKey<>("generator-plugin", String.class);
    public static final SuggestionKey<String> LIFECYCLE_TARGET = new SuggestionKey<>("lifecycle-target", String.class);
    public static final SuggestionKey<String> FALLBACK_TARGET = new SuggestionKey<>("fallback-target", String.class);
    public static final SuggestionKey<String> DISPLAY_NAME_WORLD = new SuggestionKey<>("display-name-world", String.class);

    private final AtomicReference<WorldManagementService> service = new AtomicReference<>();
    private final AtomicReference<List<String>> generatorPlugins = new AtomicReference<>(List.of());
    private final OnlinePlayerSnapshot players;
    private final CommandAuthorizationSnapshot authorizations;
    private final AtomicReference<LoadedWorldCatalog> loadedWorlds;

    public SuggestionCatalog(final OnlinePlayerSnapshot players) {
        this(players, new CommandAuthorizationSnapshot(), new LoadedWorldCatalog());
    }

    public SuggestionCatalog(
        final OnlinePlayerSnapshot players,
        final CommandAuthorizationSnapshot authorizations
    ) {
        this(players, authorizations, new LoadedWorldCatalog());
    }

    public SuggestionCatalog(
        final OnlinePlayerSnapshot players,
        final CommandAuthorizationSnapshot authorizations,
        final LoadedWorldCatalog loadedWorlds
    ) {
        this.players = Objects.requireNonNull(players, "players");
        this.authorizations = Objects.requireNonNull(authorizations, "authorizations");
        this.loadedWorlds = new AtomicReference<>(Objects.requireNonNull(loadedWorlds, "loadedWorlds"));
    }

    public void initialize(final WorldManagementService worldManagementService) {
        service.set(Objects.requireNonNull(worldManagementService, "worldManagementService"));
    }

    public void useLoadedWorldCatalog(final LoadedWorldCatalog catalog) {
        loadedWorlds.set(Objects.requireNonNull(catalog, "catalog"));
    }

    public SuggestionProvider<CommandSourceStack> provider(
        final SuggestionKey<String> key,
        final BiFunction<CommandContext<CommandSourceStack>, SuggestionCatalog, Collection<String>> selector
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(selector, "selector");
        return (context, builder) -> suggest(selector.apply(context, this), builder);
    }

    public Collection<String> managedWorlds() {
        final WorldManagementService current = service.get();
        return current == null ? List.of() : current.managedWorlds().stream().map(WorldMetadata::worldName).sorted().toList();
    }

    public Collection<String> lifecycleWorlds() {
        final WorldManagementService current = service.get();
        if (current == null) {
            return List.of();
        }
        return java.util.stream.Stream.concat(
            current.managedWorlds().stream(), current.detachedWorlds().stream()
        ).map(WorldMetadata::worldName).sorted().toList();
    }

    public Collection<String> lifecycleTargets() {
        return java.util.stream.Stream.concat(
            lifecycleWorlds().stream(), loadedWorlds.get().uniqueWorldIds().stream()
        ).distinct().sorted().toList();
    }

    public Collection<String> fallbackTargets() {
        return loadedWorlds.get().uniqueWorldIds();
    }

    public Collection<String> displayNameWorlds() {
        return lifecycleWorlds();
    }

    public Collection<String> detachedWorlds() {
        final WorldManagementService current = service.get();
        return current == null ? List.of() : current.detachedWorlds().stream().map(WorldMetadata::worldName).sorted().toList();
    }

    public Collection<String> identityWorlds() {
        final WorldManagementService current = service.get();
        return current == null ? List.of() : current.managedWorlds().stream()
            .map(WorldMetadata::worldName)
            .sorted()
            .toList();
    }

    public Collection<String> identityWorlds(final IdentityVerificationState state) {
        final WorldManagementService current = service.get();
        return current == null ? List.of() : current.managedWorlds().stream()
            .filter(metadata -> metadata.identityState() == state)
            .map(WorldMetadata::worldName)
            .sorted()
            .toList();
    }

    public Collection<String> nonVerifiedWorlds() {
        final WorldManagementService current = service.get();
        return current == null ? List.of() : current.managedWorlds().stream()
            .filter(metadata -> metadata.identityState() != IdentityVerificationState.VERIFIED)
            .map(WorldMetadata::worldName)
            .sorted()
            .toList();
    }

    public Collection<String> manageableWorlds(
        final CommandSourceStack source,
        final CommandAuthorizationSnapshot.ManagementArea area
    ) {
        final WorldManagementService current = service.get();
        if (current == null) {
            return List.of();
        }
        final CommandAuthorizationSnapshot.Scope scope = authorizations.scope(source.getSender(), area);
        if (!scope.known()) {
            return List.of();
        }
        return current.managedWorlds().stream()
            .filter(metadata -> scope.managesAllWorlds()
                || scope.playerId().map(playerId -> metadata.owner().equals(playerId.toString())).orElse(false))
            .map(WorldMetadata::worldName)
            .sorted()
            .toList();
    }

    public Collection<String> administrativeWorlds(
        final CommandSourceStack source,
        final CommandAuthorizationSnapshot.ManagementArea area
    ) {
        final WorldManagementService current = service.get();
        if (current == null) {
            return List.of();
        }
        final CommandAuthorizationSnapshot.Scope scope = authorizations.scope(source.getSender(), area);
        return scope.known() && scope.managesAllWorlds()
            ? current.managedWorlds().stream().map(WorldMetadata::worldName).sorted().toList()
            : List.of();
    }

    public Collection<String> warpNames(
        final CommandSourceStack source,
        final String worldName,
        final CommandAuthorizationSnapshot.ManagementArea area
    ) {
        final WorldManagementService current = service.get();
        if (current == null) {
            return List.of();
        }
        return current.managedWorld(worldName)
            .filter(metadata -> canManage(source, metadata, area))
            .map(metadata -> metadata.warps().keySet().stream().sorted().toList())
            .orElseGet(List::of);
    }

    public Collection<String> visibleWarpNames(final String worldName) {
        final WorldManagementService current = service.get();
        if (current == null) {
            return List.of();
        }
        return current.managedWorld(worldName)
            .map(metadata -> metadata.warps().values().stream()
                .filter(warp -> metadata.identityState() == IdentityVerificationState.VERIFIED)
                .filter(warp -> warp.requiredPermission().isEmpty())
                .filter(warp -> universallyVisible(metadata, warp))
                .map(WorldWarp::name)
                .sorted()
                .toList())
            .orElseGet(List::of);
    }

    public Collection<String> rankIds(final CommandSourceStack source, final String worldName) {
        final WorldManagementService current = service.get();
        if (current == null) {
            return List.of();
        }
        return current.managedWorld(worldName)
            .filter(metadata -> canManage(source, metadata, CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP))
            .map(metadata -> metadata.ranks().keySet().stream().sorted().toList())
            .orElseGet(List::of);
    }

    public Collection<String> onlinePlayers() {
        return players.names();
    }

    public void replaceGeneratorPlugins(final Collection<String> plugins) {
        generatorPlugins.set(Objects.requireNonNull(plugins, "plugins").stream()
            .map(plugin -> Objects.requireNonNull(plugin, "plugin"))
            .filter(plugin -> !plugin.isBlank())
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList());
    }

    public Collection<String> generatorPlugins() {
        return generatorPlugins.get();
    }

    private boolean canManage(
        final CommandSourceStack source,
        final WorldMetadata metadata,
        final CommandAuthorizationSnapshot.ManagementArea area
    ) {
        final CommandAuthorizationSnapshot.Scope scope = authorizations.scope(source.getSender(), area);
        return scope.known() && (scope.managesAllWorlds()
            || scope.playerId().map(playerId -> metadata.owner().equals(playerId.toString())).orElse(false));
    }

    private static boolean universallyVisible(final WorldMetadata metadata, final WorldWarp warp) {
        if (warp.visibility() != io.github.bearl.worldmanagement.world.WarpVisibility.PUBLIC) {
            return false;
        }
        if (WorldMetadata.SERVER_OWNER.equals(metadata.owner())) {
            return true;
        }
        return !metadata.rankSystemEnabled()
            || metadata.ranks().values().stream().allMatch(rank -> rank.permissions()
                .contains(io.github.bearl.worldmanagement.world.RankPermission.USE_PUBLIC_WARP));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
        final Collection<String> candidates,
        final SuggestionsBuilder builder
    ) {
        final String remaining = builder.getRemainingLowerCase();
        candidates.stream().filter(candidate -> candidate.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)).forEach(builder::suggest);
        return builder.buildFuture();
    }
}