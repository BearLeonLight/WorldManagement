package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.Rank;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class SuggestionCatalogTest {

    @Test
    void lifecycleWorldsIncludeActiveAndDetachedMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("SuggestionCatalogLifecycleTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("archive", true).join();
            metadata.remove("archive").join();
            final SuggestionCatalog catalog = new SuggestionCatalog(new OnlinePlayerSnapshot());
            catalog.initialize(metadata);

            assertEquals(List.of("archive", "creative"), catalog.lifecycleWorlds());
            assertEquals(List.of("creative"), catalog.managedWorlds());
            assertEquals(List.of("archive"), catalog.detachedWorlds());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void runtimeBackedTargetsIncludeOnlyUniqueLoadedWorldIds() {
        final PluginIoExecutor executor = new PluginIoExecutor("SuggestionCatalogRuntimeTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("archive", true).join();
            metadata.remove("archive").join();
            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            loadedWorlds.replaceAll(List.of(
                runtimeWorld("creative", "11111111-1111-1111-1111-111111111111"),
                runtimeWorld("lobby", "22222222-2222-2222-2222-222222222222"),
                runtimeWorld("ambiguous", "33333333-3333-3333-3333-333333333333"),
                runtimeWorld("ambiguous", "44444444-4444-4444-4444-444444444444")
            ));
            final SuggestionCatalog catalog = new SuggestionCatalog(
                new OnlinePlayerSnapshot(), new CommandAuthorizationSnapshot(), loadedWorlds
            );
            catalog.initialize(metadata);

            assertEquals(List.of("archive", "creative", "lobby"), catalog.lifecycleTargets());
            assertEquals(List.of("creative", "lobby"), catalog.fallbackTargets());
            assertEquals(List.of("archive", "creative"), catalog.displayNameWorlds());
            assertEquals(List.of("archive", "creative"), catalog.lifecycleWorlds());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void manageableWorldsFollowImmutableOwnershipAuthorizationScope() {
        final PluginIoExecutor executor = new PluginIoExecutor("SuggestionCatalogAuthorizationTest");
        try {
            final UUID ownerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final UUID otherOwnerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final Player owner = player(ownerId, "Owner", Set.of());
            final Player administrator = player(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Administrator",
                Set.of(CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION)
            );
            final Player unknown = player(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "Unknown",
                Set.of(CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION)
            );
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("survival", true).join();
            metadata.update("creative", world -> world.withOwner(ownerId.toString())).join();
            metadata.update("survival", world -> world.withOwner(otherOwnerId.toString())).join();
            final CommandAuthorizationSnapshot authorizations = new CommandAuthorizationSnapshot();
            authorizations.replace(List.of(owner, administrator));
            final SuggestionCatalog catalog = new SuggestionCatalog(new OnlinePlayerSnapshot(), authorizations);
            catalog.initialize(metadata);

            assertEquals(List.of("creative"), catalog.manageableWorlds(
                source(owner), CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP
            ));
            assertEquals(List.of("creative", "survival"), catalog.manageableWorlds(
                source(administrator), CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP
            ));
            assertEquals(List.of(), catalog.manageableWorlds(
                source(unknown), CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP
            ));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void visibleWarpsDoNotEvaluateExternalPermissionsDuringCompletion() {
        final PluginIoExecutor executor = new PluginIoExecutor("SuggestionCatalogTest");
        try {
            final UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final WorldIdentitySnapshot identity = new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                WorldEnvironment.NORMAL,
                42L,
                true
            );
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt(identity, LifecycleCapability.MANAGED, java.util.Optional.empty(), true, null).join();
            metadata.update("creative", world -> world
                .withOwner("owner")
                .withRankSystemEnabled(false)
                .withWarp(new WorldWarp(
                    "vip", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), "worldmanagement.warp.vip"
                ))
                .withWarp(new WorldWarp(
                    "spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
                ))
                .withWarp(new WorldWarp(
                    "staff", 0, 64, 0, 0, 0, WarpVisibility.PRIVATE, Set.of(), Set.of(), ""
                ))
            ).join();
            final OnlinePlayerSnapshot players = new OnlinePlayerSnapshot();
            players.replace(List.of(player(playerId, "Builder")));
            final SuggestionCatalog catalog = new SuggestionCatalog(players);
            catalog.initialize(metadata);

            assertEquals(List.of("spawn"), catalog.visibleWarpNames("creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void hidesExternalPermissionWarpsFromSnapshotOnlyCompletion() {
        final PluginIoExecutor executor = new PluginIoExecutor("SuggestionCatalogTest");
        try {
            final UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.update("creative", world -> world
                .withOwner("owner")
                .withRank("MEMBER", new Rank("Member", Set.of(RankPermission.USE_PUBLIC_WARP)))
                .withPlayerRank(playerId, "MEMBER")
                .withWarp(new WorldWarp(
                    "vip", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), "worldmanagement.warp.vip"
                ))
            ).join();
            final OnlinePlayerSnapshot players = new OnlinePlayerSnapshot();
            players.replace(List.of(player(playerId, "Builder")));
            final SuggestionCatalog catalog = new SuggestionCatalog(players);
            catalog.initialize(metadata);

            assertEquals(List.of(), catalog.visibleWarpNames("creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void hidesPublicWarpWhenAnyAssignableRankCannotUseIt() {
        final PluginIoExecutor executor = new PluginIoExecutor("SuggestionCatalogRankTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.update("creative", world -> world
                .withOwner("owner")
                .withRankPermission(WorldMetadata.GUEST_RANK, RankPermission.USE_PUBLIC_WARP, true)
                .withRank("MEMBER", new Rank("Member", Set.of()))
                .withWarp(new WorldWarp(
                    "spawn", 0, 64, 0, 0, 0, WarpVisibility.PUBLIC, Set.of(), Set.of(), ""
                ))
            ).join();
            final SuggestionCatalog catalog = new SuggestionCatalog(new OnlinePlayerSnapshot());
            catalog.initialize(metadata);

            assertEquals(List.of(), catalog.visibleWarpNames("creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static Player player(final UUID playerId, final String name) {
        return player(playerId, name, Set.of());
    }

    private static WorldRuntimeGateway.LifecycleWorld runtimeWorld(
        final String worldId,
        final String uuid
    ) {
        return new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot(
                "minecraft:" + worldId, UUID.fromString(uuid), WorldEnvironment.NORMAL, 0L, true
            ),
            LifecycleCapability.MANAGED
        );
    }

    private static Player player(final UUID playerId, final String name, final Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> name;
                case "hasPermission" -> permissions.contains(arguments[0]);
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static CommandSourceStack source(final Player player) {
        return (CommandSourceStack) Proxy.newProxyInstance(
            CommandSourceStack.class.getClassLoader(),
            new Class<?>[] {CommandSourceStack.class},
            (proxy, method, arguments) -> method.getName().equals("getSender")
                ? player
                : defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}