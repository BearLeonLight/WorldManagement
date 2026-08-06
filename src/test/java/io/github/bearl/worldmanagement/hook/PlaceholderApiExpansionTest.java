package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.RegistrySnapshot;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

final class PlaceholderApiExpansionTest {

    private static final UUID WORLD_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void exposesWmNamespaceAndPlainTextValues() {
        final PlaceholderExpansion expansion = expansion();

        assertEquals("wm", expansion.getIdentifier());
        assertTrue(expansion.persist());
        assertEquals("1", expansion.onRequest(null, "managed_world_count"));
        assertEquals("創意世界", expansion.onRequest(null, "world_display_name:creative_world"));
        assertEquals("創意世界", expansion.onRequest(null, "WORLD_DISPLAY_NAME:creative_world"));
        assertNull(expansion.onRequest(null, "world_display_name"));
        assertNull(expansion.onRequest(null, "unknown"));
    }

    @Test
    void resolvesCurrentWorldUsingOnlyOfflinePlayerUuid() {
        final OfflinePlayer player = offlinePlayer();

        assertEquals("creative_world", expansion().onRequest(player, "current_world_id"));
    }

    @Test
    void advertisesTheCompletePlaceholderCatalog() {
        final List<String> placeholders = expansion().getPlaceholders();

        assertEquals(
            WorldPlaceholderResolver.GLOBAL_KEYS.size()
                + WorldPlaceholderResolver.WORLD_KEYS.size()
                + WorldPlaceholderResolver.AUDIENCE_KEYS.size(),
            placeholders.size()
        );
        assertTrue(placeholders.contains("%wm_managed_world_count%"));
        assertTrue(placeholders.contains("%wm_world_display_name:<world>%"));
        assertTrue(placeholders.contains("%wm_current_world_id%"));
        assertEquals(placeholders.size(), new HashSet<>(placeholders).size());
    }

    private static OfflinePlayer offlinePlayer() {
        return (OfflinePlayer) Proxy.newProxyInstance(
            OfflinePlayer.class.getClassLoader(),
            new Class<?>[] { OfflinePlayer.class },
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER_UUID;
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
                default -> throw new AssertionError("Unexpected OfflinePlayer method: " + method.getName());
            }
        );
    }

    private static PlaceholderExpansion expansion() {
        final WorldMetadata metadata = WorldMetadata.createDefault(
            "creative_world",
            new WorldIdentitySnapshot(
                "minecraft:creative_world", WORLD_UUID, WorldEnvironment.NORMAL, 42L, true
            ),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true
        ).withDisplayName(new DisplayNameValidator().validate("<aqua>創意世界</aqua>"));
        final VerifiedWorldRef world = VerifiedWorldRef.from(metadata).orElseThrow();
        final WorldPlaceholderResolver resolver = new WorldPlaceholderResolver(
            () -> RegistrySnapshot.from(Set.of(metadata)),
            world::equals,
            playerId -> PLAYER_UUID.equals(playerId) ? Optional.of(world) : Optional.empty()
        );
        return new PlaceholderApiExpansion(resolver, "WorldManagement", "test");
    }
}