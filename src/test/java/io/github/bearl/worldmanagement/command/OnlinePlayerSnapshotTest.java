package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class OnlinePlayerSnapshotTest {

    @Test
    void resolvesCurrentPlayerNamesCaseInsensitivelyAndPassesThroughUuids() {
        final UUID playerId = UUID.fromString("e5b649b6-81ac-4af1-a78f-e0f674d5db37");
        final OnlinePlayerSnapshot snapshot = new OnlinePlayerSnapshot();
        snapshot.replace(List.of(player(playerId, "Builder")));

        assertEquals(List.of("Builder"), snapshot.names());
        assertEquals(playerId, snapshot.resolve("builder").orElseThrow());
        assertEquals(playerId, snapshot.resolve(playerId.toString()).orElseThrow());
        assertTrue(snapshot.resolve("offline-player").isEmpty());
    }

    private static Player player(final UUID uniqueId, final String name) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> uniqueId;
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