package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.RegistrySnapshot;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.miniplaceholders.api.Expansion;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

final class MiniPlaceholdersExpansionTest {

    private static final UUID WORLD_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void exposesGlobalAndWorldPlaceholdersWithWmPrefix() {
        final Expansion expansion = expansion();

        assertEquals("wm", expansion.name());
        assertEquals(
            Component.text("1"),
            MiniMessage.miniMessage().deserialize("<wm_managed_world_count>", expansion.globalPlaceholders())
        );
        assertEquals(
            Component.text("創意世界", NamedTextColor.AQUA),
            MiniMessage.miniMessage().deserialize(
                "<wm_world_display_name:creative_world>", expansion.globalPlaceholders()
            )
        );
    }

    @Test
    void resolvesAudiencePlaceholdersFromIdentityUuidPointer() {
        final Expansion expansion = expansion();
        final Pointers pointers = Pointers.builder().withStatic(Identity.UUID, PLAYER_UUID).build();
        final Audience audience = new Audience() {
            @Override
            public Pointers pointers() {
                return pointers;
            }
        };

        assertEquals(
            Component.text("creative_world"),
            MiniMessage.miniMessage().deserialize(
                "<wm_current_world_id>", audience, expansion.audiencePlaceholders()
            )
        );
    }

    @Test
    void registersEverySharedCatalogKey() {
        final Expansion expansion = expansion();

        WorldPlaceholderResolver.GLOBAL_KEYS.forEach(key ->
            assertEquals(true, expansion.hasGlobalPlaceholder(key))
        );
        WorldPlaceholderResolver.WORLD_KEYS.forEach(key ->
            assertEquals(true, expansion.hasGlobalPlaceholder(key))
        );
        WorldPlaceholderResolver.AUDIENCE_KEYS.forEach(key ->
            assertEquals(true, expansion.hasAudiencePlaceholder(key))
        );
    }

    private static Expansion expansion() {
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
        return MiniPlaceholdersExpansion.create(resolver, "WorldManagement", "test");
    }
}