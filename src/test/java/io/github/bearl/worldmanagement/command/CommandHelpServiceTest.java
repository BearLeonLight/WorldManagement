package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.bearl.worldmanagement.module.ModuleConfiguration;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.ModuleManager;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

final class CommandHelpServiceTest {

    @Test
    void resolvesPagesAndTopicsFromTheProductionSpecification() {
        final CommandHelpService service = service();
        final CommandSender sender = sender(Set.of(
            "worldmanagement.command.warp",
            "worldmanagement.command.trust"
        ));

        final CommandHelpService.PageResult page = assertInstanceOf(
            CommandHelpService.PageResult.class,
            service.resolve(sender, "")
        );
        assertEquals(List.of("help", "warp"), page.page().topics().stream().map(CommandHelpRenderer.Topic::path).toList());

        final CommandHelpService.TopicResult topic = assertInstanceOf(
            CommandHelpService.TopicResult.class,
            service.resolve(sender, "WaRp TrUsT")
        );
        assertEquals("warp trust", topic.topic().path());
    }

    @Test
    void helpAllIgnoresCommandPermissionsButNotDisabledModules() {
        final CommandHelpService service = service();
        final CommandSender sender = sender(Set.of("worldmanagement.command.help.all"));

        final CommandHelpService.PageResult page = assertInstanceOf(
            CommandHelpService.PageResult.class,
            service.resolve(sender, "1")
        );

        assertEquals(List.of("help", "create", "adopt", "load", "unload", "remove"),
            page.page().topics().stream().map(CommandHelpRenderer.Topic::path).toList());
        assertInstanceOf(CommandHelpService.NotFoundResult.class, service.resolve(sender, "ownership"));
    }

    @Test
    void invalidAndOutOfRangePagesHaveOneNonLeakingResult() {
        final CommandHelpService service = service();
        final CommandSender sender = sender(Set.of("worldmanagement.command.help.all"));

        assertInstanceOf(CommandHelpService.InvalidPageResult.class, service.resolve(sender, "0"));
        assertInstanceOf(CommandHelpService.InvalidPageResult.class, service.resolve(sender, "999999999999999999999"));
        assertInstanceOf(CommandHelpService.InvalidPageResult.class, service.resolve(sender, "99"));
        assertInstanceOf(CommandHelpService.NotFoundResult.class, service.resolve(sender, "ownership rank"));
        assertInstanceOf(CommandHelpService.NotFoundResult.class, service.resolve(sender, "does-not-exist"));
    }

    private static CommandHelpService service() {
        final CommandAccessPolicy accessPolicy = new CommandAccessPolicy();
        accessPolicy.initialize(new ModuleManager(
            Arrays.stream(ModuleId.values())
                .map(module -> (io.github.bearl.worldmanagement.module.WorldManagementModule) () -> module)
                .toList(),
            new ModuleConfiguration(Map.of(ModuleId.OWNERSHIP, false))
        ));
        final WorldManagementCommandSpec specification = new WorldManagementCommandSpec(
            new SuggestionCatalog(new OnlinePlayerSnapshot())
        );
        return new CommandHelpService(specification.root(), accessPolicy, 6);
    }

    private static CommandSender sender(final Set<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "hasPermission" -> permissions.contains(arguments[0]);
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }
}