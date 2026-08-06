package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.Rank;
import io.github.bearl.worldmanagement.world.RegistrySnapshot;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class WorldPlaceholderResolverTest {

    private static final UUID WORLD_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMBER_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final WorldIdentitySnapshot IDENTITY = new WorldIdentitySnapshot(
        "minecraft:creative_world", WORLD_UUID, WorldEnvironment.NORMAL, 42L, true
    );

    @Test
    void resolvesGlobalAndWorldArgumentPlaceholdersFromOneMetadataSnapshot() {
        final WorldMetadata active = metadata();
        final WorldMetadata detached = WorldMetadata.createDefault("archive", true)
            .withManagementState(WorldManagementState.DETACHED);
        final AtomicReference<RegistrySnapshot> worlds = new AtomicReference<>(
            RegistrySnapshot.from(Set.of(active, detached))
        );
        final WorldPlaceholderResolver resolver = resolver(worlds, Map.of(), Set.of(activeReference(active)));

        assertEquals("wm", WorldPlaceholderResolver.NAMESPACE);
        assertEquals("1", text(resolver, "managed_world_count", Optional.empty(), null));
        assertEquals("1", text(resolver, "detached_world_count", Optional.empty(), null));
        assertEquals("true", text(resolver, "world_exists", Optional.of("creative_world"), null));
        assertEquals("false", text(resolver, "world_exists", Optional.of("missing"), null));
        assertEquals("創意世界", text(resolver, "world_display_name", Optional.of("creative_world"), null));
        assertEquals("active", text(resolver, "world_management_state", Optional.of("creative_world"), null));
        assertEquals("loaded", text(resolver, "world_desired_state", Optional.of("creative_world"), null));
        assertEquals("verified", text(resolver, "world_identity_state", Optional.of("creative_world"), null));
        assertEquals("managed", text(resolver, "world_lifecycle_capability", Optional.of("creative_world"), null));
        assertEquals("normal", text(resolver, "world_environment", Optional.of("creative_world"), null));
        assertEquals("none", text(resolver, "world_access_mode", Optional.of("creative_world"), null));
        assertEquals("true", text(resolver, "world_rank_system_enabled", Optional.of("creative_world"), null));
        assertEquals("1", text(resolver, "world_custom_rank_count", Optional.of("creative_world"), null));
        assertEquals("0", text(resolver, "world_warp_count", Optional.of("creative_world"), null));
        assertEquals("true", text(resolver, "world_runtime_loaded", Optional.of("creative_world"), null));
        assertEquals("", text(resolver, "world_display_name", Optional.of("missing"), null));
        assertTrue(resolver.resolve("unknown", Optional.empty(), null).isEmpty());
    }

    @Test
    void resolvesPlayerPlaceholdersWithoutReadingBukkitObjects() {
        final WorldMetadata metadata = metadata();
        final AtomicReference<RegistrySnapshot> worlds = new AtomicReference<>(RegistrySnapshot.from(Set.of(metadata)));
        final WorldPlaceholderResolver resolver = resolver(
            worlds,
            Map.of(OWNER_UUID, activeReference(metadata), MEMBER_UUID, activeReference(metadata)),
            Set.of(activeReference(metadata))
        );

        assertEquals("true", text(resolver, "current_world_managed", Optional.empty(), OWNER_UUID));
        assertEquals("creative_world", text(resolver, "current_world_id", Optional.empty(), OWNER_UUID));
        assertEquals("創意世界", text(resolver, "current_world_display_name", Optional.empty(), OWNER_UUID));
        assertEquals("true", text(resolver, "current_world_is_owner", Optional.empty(), OWNER_UUID));
        assertEquals("OWNER", text(resolver, "current_world_rank_id", Optional.empty(), OWNER_UUID));
        assertEquals("World Owner", text(resolver, "current_world_rank_display_name", Optional.empty(), OWNER_UUID));
        assertEquals("false", text(resolver, "current_world_is_owner", Optional.empty(), MEMBER_UUID));
        assertEquals("BUILDER", text(resolver, "current_world_rank_id", Optional.empty(), MEMBER_UUID));
        assertEquals("Builder", text(resolver, "current_world_rank_display_name", Optional.empty(), MEMBER_UUID));
        assertEquals("false", text(resolver, "current_world_managed", Optional.empty(), UUID.randomUUID()));
        assertEquals("", text(resolver, "current_world_id", Optional.empty(), UUID.randomUUID()));
    }

    @Test
    void preservesOnlyValidatedDisplayNameFormattingInComponentValues() {
        final WorldMetadata metadata = metadata();
        final WorldPlaceholderResolver resolver = resolver(
            new AtomicReference<>(RegistrySnapshot.from(Set.of(metadata))),
            Map.of(),
            Set.of(activeReference(metadata))
        );

        final WorldPlaceholderResolver.Value value = resolver.resolve(
            "world_display_name", Optional.of("creative_world"), null
        ).orElseThrow();

        assertEquals("創意世界", value.plainText());
        assertEquals(Component.text("創意世界", net.kyori.adventure.text.format.NamedTextColor.AQUA), value.component());
    }

    @Test
    void rejectsStalePlayerWorldIdentity() {
        final WorldMetadata metadata = metadata();
        final VerifiedWorldRef replacement = new VerifiedWorldRef(
            "creative_world", "minecraft:creative_world", UUID.randomUUID()
        );
        final WorldPlaceholderResolver resolver = resolver(
            new AtomicReference<>(RegistrySnapshot.from(Set.of(metadata))),
            Map.of(OWNER_UUID, replacement),
            Set.of(activeReference(metadata))
        );

        assertEquals("false", text(resolver, "current_world_managed", Optional.empty(), OWNER_UUID));
        assertEquals("", text(resolver, "current_world_id", Optional.empty(), OWNER_UUID));
    }

    private static WorldMetadata metadata() {
        return WorldMetadata.createDefault(
            "creative_world", IDENTITY, LifecycleCapability.MANAGED, Optional.empty(), true
        )
            .withDisplayName(new DisplayNameValidator().validate("<aqua>創意世界</aqua>"))
            .withOwner(OWNER_UUID.toString())
            .withRank("BUILDER", new Rank("Builder", Set.of()), 5)
            .withPlayerRank(MEMBER_UUID, "BUILDER");
    }

    private static VerifiedWorldRef activeReference(final WorldMetadata metadata) {
        return VerifiedWorldRef.from(metadata).orElseThrow();
    }

    private static WorldPlaceholderResolver resolver(
        final AtomicReference<RegistrySnapshot> worlds,
        final Map<UUID, VerifiedWorldRef> players,
        final Set<VerifiedWorldRef> loaded
    ) {
        return new WorldPlaceholderResolver(
            worlds::get,
            reference -> loaded.contains(reference),
            playerId -> Optional.ofNullable(players.get(playerId))
        );
    }

    private static String text(
        final WorldPlaceholderResolver resolver,
        final String key,
        final Optional<String> argument,
        final UUID playerId
    ) {
        return resolver.resolve(key, argument, playerId).orElseThrow().plainText();
    }
}