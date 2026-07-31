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
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class SuggestionCatalogTest {

    @Test
    void manageableWorldsStayEmptyWithoutAnAuthorizationSnapshot() {
        assertEquals(List.of(), new SuggestionCatalog(new OnlinePlayerSnapshot()).manageableWorlds());
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
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> name;
                default -> defaultValue(method.getReturnType());
            }
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