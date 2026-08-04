package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dejvokep.boostedyaml.YamlDocument;
import io.github.bearl.worldmanagement.module.ModuleId;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldManagementCommandSpecTest {

    @Test
    void oneSpecificationOwnsHelpUsageAndCommandAccessMetadata() {
        final CommandNodeSpec create = CommandNodeSpec.literal(
            "create",
            "create",
            new CommandAccess(ModuleId.LIFECYCLE, List.of("worldmanagement.command.create")),
            List.of(CommandNodeSpec.argument("create.world", "world", CommandArgumentKind.WORD, List.of())
                .executes(new CommandRoute(List.of("create")), List.of("world"))
                .withChildren(List.of(
                    CommandNodeSpec.argument("create.environment", "environment", CommandArgumentKind.WORD, List.of())
                        .executes(new CommandRoute(List.of("create")), List.of("world", "environment"))
                )))
        );

        assertEquals("create", create.id());
        assertEquals("command.help.topic.create.description", create.descriptionKey());
        assertEquals(ModuleId.LIFECYCLE, create.access().module());
        assertEquals(List.of("worldmanagement.command.create"), create.access().permissions());
        assertEquals(List.of(
            "/wm create <world>",
            "/wm create <world> <environment>"
        ), create.usageLines("wm"));
        assertEquals(create.access(), create.children().getFirst().effectiveAccess(create.access()));
        assertEquals(
            new CommandRoute(List.of("create")),
            create.children().getFirst().execution().orElseThrow().route()
        );
    }

    @Test
    void rejectsDuplicateStableIdsAndSiblingSegments() {
        final CommandAccess access = new CommandAccess(ModuleId.LIFECYCLE, List.of("permission"));

        assertThrows(IllegalArgumentException.class, () -> CommandNodeSpec.root(List.of(
            CommandNodeSpec.literal("same", "create", access, List.of()),
            CommandNodeSpec.literal("same", "load", access, List.of())
        )));
        assertThrows(IllegalArgumentException.class, () -> CommandNodeSpec.root(List.of(
            CommandNodeSpec.literal("create", "world", access, List.of()),
            CommandNodeSpec.literal("load", "world", access, List.of())
        )));
    }

    @Test
    void allPermissionModeRemainsMachineReadableForHelpAndCompiler() {
        final CommandAccess access = new CommandAccess(
            ModuleId.LIFECYCLE,
            CommandPermissionMode.ALL,
            List.of("worldmanagement.command.tp.any.explicit", "worldmanagement.bypass.protection")
        );

        assertEquals(CommandPermissionMode.ALL, access.permissionMode());
    }

    @Test
    void productionLifecycleUsageIncludesDetachedCreationModes() {
        final WorldManagementCommandSpec specification = new WorldManagementCommandSpec(
            new SuggestionCatalog(new OnlinePlayerSnapshot())
        );
        final CommandNodeSpec create = specification.root().children().stream()
            .filter(node -> node.id().equals("create"))
            .findFirst()
            .orElseThrow();
        final CommandNodeSpec adopt = specification.root().children().stream()
            .filter(node -> node.id().equals("adopt"))
            .findFirst()
            .orElseThrow();
        final CommandNodeSpec importWorld = specification.root().children().stream()
            .filter(node -> node.id().equals("import"))
            .findFirst()
            .orElseThrow();
        final CommandNodeSpec load = specification.root().children().stream()
            .filter(node -> node.id().equals("load"))
            .findFirst()
            .orElseThrow();

        assertEquals(List.of(
            "/wm create <world> <environment> <world-type>",
            "/wm create <world> <environment> <world-type> [--seed <seed>] [--generator <plugin[:id]>] "
                + "[--generator-settings <json>] [--no-structures] [--generate-bonus-chest] "
                + "[--biome <plugin[:id]>] [--force-spawn-position <x,y,z[,yaw,pitch]>] [--detached]"
        ), create.usageLines("wm"));
        assertEquals(List.of(
            "/wm adopt <world>",
            "/wm adopt <world> --detached"
        ), adopt.usageLines("wm"));
        assertEquals(List.of(
            "/wm import <world> <environment>",
            "/wm import <world> <environment> --detached"
        ), importWorld.usageLines("wm"));
        assertEquals(List.of(
            "/wm load <world>",
            "/wm load <world> <environment> --detached"
        ), load.usageLines("wm"));
    }

    @Test
    void everyReachableLiteralHelpTopicHasABundledDescription() throws Exception {
        final WorldManagementCommandSpec specification = new WorldManagementCommandSpec(
            new SuggestionCatalog(new OnlinePlayerSnapshot())
        );
        final List<CommandNodeSpec> topics = new ArrayList<>();
        collectLiteralTopics(specification.root(), topics);
        try (InputStream resource = getClass().getResourceAsStream("/messages_zh_TW.yml")) {
            final YamlDocument messages = YamlDocument.create(java.util.Objects.requireNonNull(resource));
            topics.forEach(topic -> assertNotNull(
                messages.getString(topic.descriptionKey(), null),
                topic.descriptionKey()
            ));
        }
    }

    private static void collectLiteralTopics(final CommandNodeSpec parent, final List<CommandNodeSpec> result) {
        parent.children().stream()
            .filter(child -> child.segment() instanceof CommandNodeSpec.LiteralSegment)
            .forEach(child -> {
                result.add(child);
                collectLiteralTopics(child, result);
            });
    }
}