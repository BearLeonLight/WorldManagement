package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

final class PlayerWorldContextSnapshotTest {

    private static final UUID PLAYER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CREATIVE_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SURVIVAL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void capturesJoinAndCompletedWorldChangesThenRemovesQuitPlayers() {
        final AtomicReference<World> currentWorld = new AtomicReference<>(world("creative_world", CREATIVE_UUID));
        final Player player = player(currentWorld);
        final PlayerWorldContextSnapshot snapshot = new PlayerWorldContextSnapshot();

        snapshot.onJoin(new PlayerJoinEvent(player, ComponentFixtures.empty()));
        assertEquals(
            new VerifiedWorldRef("creative_world", "minecraft:creative_world", CREATIVE_UUID),
            snapshot.currentWorld(PLAYER_UUID).orElseThrow()
        );

        final World previous = currentWorld.getAndSet(world("survival", SURVIVAL_UUID));
        snapshot.onChangedWorld(new PlayerChangedWorldEvent(player, previous));
        assertEquals(
            new VerifiedWorldRef("survival", "minecraft:survival", SURVIVAL_UUID),
            snapshot.currentWorld(PLAYER_UUID).orElseThrow()
        );

        snapshot.onQuit(new PlayerQuitEvent(player, ComponentFixtures.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertTrue(snapshot.currentWorld(PLAYER_UUID).isEmpty());
    }

    @Test
    void clearsCapturedContextsDuringShutdown() {
        final PlayerWorldContextSnapshot snapshot = new PlayerWorldContextSnapshot();
        snapshot.capture(player(new AtomicReference<>(world("creative_world", CREATIVE_UUID))));

        snapshot.clear();

        assertTrue(snapshot.currentWorld(PLAYER_UUID).isEmpty());
    }

    private static Player player(final AtomicReference<World> currentWorld) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] { Player.class },
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER_UUID;
                case "getWorld" -> currentWorld.get();
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
                default -> throw new AssertionError("Unexpected Player method: " + method.getName());
            }
        );
    }

    private static World world(final String id, final UUID uuid) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] { World.class },
            (instance, method, arguments) -> switch (method.getName()) {
                case "getKey" -> java.util.Objects.requireNonNull(NamespacedKey.fromString("minecraft:" + id));
                case "getUID" -> uuid;
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
                default -> throw new AssertionError("Unexpected World method: " + method.getName());
            }
        );
    }

    private static final class ComponentFixtures {
        private static net.kyori.adventure.text.Component empty() {
            return net.kyori.adventure.text.Component.empty();
        }
    }
}