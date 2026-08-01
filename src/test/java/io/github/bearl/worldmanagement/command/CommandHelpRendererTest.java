package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.module.ModuleConfiguration;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.ModuleManager;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

final class CommandHelpRendererTest {

    @Test
    void rendersPagesAndTopicsFromTheSamePermissionFilteredSpecification() {
        final CommandAccessPolicy accessPolicy = new CommandAccessPolicy();
        accessPolicy.initialize(moduleManager(Map.of(ModuleId.OWNERSHIP, false)));
        final CommandHelpRenderer renderer = new CommandHelpRenderer(
            new WorldManagementCommandSpec(new SuggestionCatalog(new OnlinePlayerSnapshot())).root(),
            accessPolicy,
            100
        );
        final CommandSender warpOnly = sender(Set.of("worldmanagement.command.warp"));

        final CommandHelpRenderer.Page page = renderer.page(warpOnly, 1, false).orElseThrow();
        assertEquals(List.of("help", "warp"), page.topics().stream().map(CommandHelpRenderer.Topic::path).toList());
        assertEquals(1, page.page());
        assertEquals(1, page.pageCount());
        assertTrue(renderer.topic(warpOnly, List.of("warp", "set"), false).isPresent());
        assertTrue(renderer.topic(warpOnly, List.of("create"), false).isEmpty());

        final Set<String> allVisible = renderer.page(warpOnly, 1, true).orElseThrow().topics().stream()
            .map(CommandHelpRenderer.Topic::path)
            .collect(Collectors.toSet());
        assertTrue(allVisible.contains("create"));
        assertTrue(allVisible.contains("storage"));
        assertFalse(allVisible.contains("ownership"));
        assertTrue(renderer.topic(warpOnly, List.of("ownership", "rank", "set"), true).isEmpty());
    }

    @Test
    void fullLiteralPathResolvesGroupAndLeafUsageFromTheSpecification() {
        final CommandAccessPolicy accessPolicy = new CommandAccessPolicy();
        accessPolicy.initialize(moduleManager(Map.of()));
        final CommandHelpRenderer renderer = new CommandHelpRenderer(
            new WorldManagementCommandSpec(new SuggestionCatalog(new OnlinePlayerSnapshot())).root(),
            accessPolicy,
            6
        );

        final CommandHelpRenderer.Topic rank = renderer.topic(sender(Set.of()), List.of("OwNeRsHiP", "RaNk"), true).orElseThrow();
        final CommandHelpRenderer.Topic set = renderer.topic(sender(Set.of()), List.of("ownership", "rank", "set"), true).orElseThrow();

        assertEquals("ownership rank", rank.path());
        assertTrue(rank.children().contains("ownership rank set"));
        assertEquals(List.of("/wm ownership rank set <world> <player> <rank>"), set.usageLines());
        assertEquals("command.help.topic.ownership.rank.set.description", set.descriptionKey());
        assertTrue(renderer.page(sender(Set.of()), 999, true).isEmpty());
    }

    private static ModuleManager moduleManager(final Map<ModuleId, Boolean> overrides) {
        return new ModuleManager(
            Arrays.stream(ModuleId.values()).map(module -> (io.github.bearl.worldmanagement.module.WorldManagementModule) () -> module).toList(),
            new ModuleConfiguration(overrides)
        );
    }

    private static CommandSender sender(final Set<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> method.getName().equals("hasPermission")
                ? permissions.contains((String) arguments[0])
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