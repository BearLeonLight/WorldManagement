package io.github.bearl.worldmanagement.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

final class WorldProtectionListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void tokenAllowsOneExactVerifiedDestinationButNeverAnIsolatedReplacement() {
        final PluginIoExecutor executor = new PluginIoExecutor("ProtectionListenerTest");
        try {
            final WorldManagementService service = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            service.load().join();
            final WorldIdentitySnapshot accepted = new WorldIdentitySnapshot(
                "minecraft:creative", WORLD_UUID, WorldEnvironment.NORMAL, 42L, true
            );
            service.adopt(accepted, LifecycleCapability.MANAGED, Optional.empty(), true, null).join();
            service.update("creative", metadata -> metadata.withAccessControl(
                new AccessControl(AccessMode.WHITELIST, Set.of())
            )).join();
            final TeleportBypassTokens tokens = new TeleportBypassTokens();
            final WorldProtectionListener listener = new WorldProtectionListener(
                service, new WorldAccessPolicy(), null, tokens
            );
            final Player player = player();
            final World source = world("minecraft:source", UUID.randomUUID(), 1L);
            final World exactTarget = world("minecraft:creative", WORLD_UUID, 42L);
            final World replacement = world("minecraft:creative", UUID.randomUUID(), 42L);
            tokens.issue(PLAYER_ID, new VerifiedWorldRef("creative", "minecraft:creative", WORLD_UUID));

            final PlayerPortalEvent isolated = portalEvent(player, source, replacement);
            listener.onPortal(isolated);
            final PlayerTeleportEvent firstExact = event(player, source, exactTarget);
            listener.onTeleport(firstExact);
            final PlayerTeleportEvent secondExact = event(player, source, exactTarget);
            listener.onTeleport(secondExact);

            assertTrue(isolated.isCancelled());
            assertFalse(firstExact.isCancelled());
            assertTrue(secondExact.isCancelled());
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static PlayerTeleportEvent event(final Player player, final World source, final World target) {
        return new PlayerTeleportEvent(
            player,
            new Location(source, 0.0, 64.0, 0.0),
            new Location(target, 0.0, 64.0, 0.0),
            PlayerTeleportEvent.TeleportCause.PLUGIN
        );
    }

    private static PlayerPortalEvent portalEvent(final Player player, final World source, final World target) {
        return new PlayerPortalEvent(
            player,
            new Location(source, 0.0, 64.0, 0.0),
            new Location(target, 0.0, 64.0, 0.0),
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        );
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER_ID;
                case "hasPermission" -> false;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static World world(final String paperKey, final UUID uuid, final long seed) {
        final NamespacedKey key = NamespacedKey.fromString(paperKey);
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> key.getKey();
                case "getKey" -> key;
                case "getUID" -> uuid;
                case "getEnvironment" -> World.Environment.NORMAL;
                case "getSeed" -> seed;
                case "canGenerateStructures" -> true;
                case "getGenerator", "getBiomeProvider" -> null;
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