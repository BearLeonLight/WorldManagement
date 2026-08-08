package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldListMessageRendererTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersDisplayNameWithWorldIdHoverAndEnvironment() {
        final WorldMetadata metadata = WorldMetadata.createDefault(
            "creative",
            new WorldIdentitySnapshot(
                "minecraft:creative",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                WorldEnvironment.NETHER,
                42L,
                true
            ),
            LifecycleCapability.MANAGED,
            Optional.empty(),
            true
        ).withDisplayName(new DisplayNameValidator().validate("<aqua>創意世界</aqua>"));

        final List<Component> rendered = new WorldListMessageRenderer(messages()).render(List.of(metadata), false);

        assertEquals("受管世界：", plain(rendered.get(0)));
        assertEquals("創意世界 - 地獄", plain(rendered.get(1)));
        assertEquals("世界 ID：creative", plain(assertInstanceOf(
            Component.class, rendered.get(1).children().getFirst().hoverEvent().value()
        )));
    }

    @Test
    void rendersWorldIdWithoutRedundantHoverWhenDisplayNameIsReset() {
        final WorldMetadata metadata = WorldMetadata.createDefault("creative", true);

        final Component entry = new WorldListMessageRenderer(messages()).render(List.of(metadata), false).get(1);

        assertEquals("creative - 主世界", plain(entry));
        assertNull(entry.children().getFirst().hoverEvent());
    }

    @Test
    void rendersLoadedRuntimeWorldsWithLocalizedEnvironmentAndManagementStatus() {
        final List<Component> rendered = new WorldListMessageRenderer(messages()).renderLoaded(List.of(
            new WorldListMessageRenderer.LoadedWorldEntry(
                lifecycleWorld("alpha", WorldEnvironment.NORMAL, "11111111-1111-1111-1111-111111111111"),
                WorldListMessageRenderer.LoadedStatus.ACTIVE
            ),
            new WorldListMessageRenderer.LoadedWorldEntry(
                lifecycleWorld("beta", WorldEnvironment.NETHER, "22222222-2222-2222-2222-222222222222"),
                WorldListMessageRenderer.LoadedStatus.DETACHED
            ),
            new WorldListMessageRenderer.LoadedWorldEntry(
                lifecycleWorld("gamma", WorldEnvironment.THE_END, "33333333-3333-3333-3333-333333333333"),
                WorldListMessageRenderer.LoadedStatus.UNKNOWN
            ),
            new WorldListMessageRenderer.LoadedWorldEntry(
                lifecycleWorld("omega", WorldEnvironment.CUSTOM, "44444444-4444-4444-4444-444444444444"),
                WorldListMessageRenderer.LoadedStatus.ACTIVE
            )
        ));

        assertEquals("目前已載入世界：", plain(rendered.get(0)));
        assertEquals("alpha - 主世界 - 受管", plain(rendered.get(1)));
        assertEquals("beta - 地獄 - 已停止管理", plain(rendered.get(2)));
        assertEquals("gamma - 終界 - 未知", plain(rendered.get(3)));
        assertEquals("omega - 自訂 - 受管", plain(rendered.get(4)));
    }

    private static WorldRuntimeGateway.LifecycleWorld lifecycleWorld(
        final String name,
        final WorldEnvironment environment,
        final String uuid
    ) {
        return new WorldRuntimeGateway.LifecycleWorld(
            new WorldIdentitySnapshot("minecraft:" + name, UUID.fromString(uuid), environment, 42L, true),
            LifecycleCapability.MANAGED
        );
    }

    private MessageService messages() {
        final InputStream bundled = getClass().getResourceAsStream("/messages_zh_TW.yml");
        return MessageService.load(
            temporaryDirectory, "zh_TW", java.util.Objects.requireNonNull(bundled), ignored -> { }
        );
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}