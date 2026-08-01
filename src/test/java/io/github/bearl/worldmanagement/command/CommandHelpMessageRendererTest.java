package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.module.ModuleConfiguration;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.ModuleManager;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CommandHelpMessageRendererTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersLocalizedPageTopicAndErrors() {
        final MessageService messages = messages();
        final CommandHelpMessageRenderer messageRenderer = new CommandHelpMessageRenderer(messages);
        final CommandHelpService help = helpService();
        final CommandSender sender = sender(Set.of("worldmanagement.command.help.all"));

        final String page = plain(messageRenderer.render(help.resolve(sender, "1")));
        assertTrue(page.contains("WorldManagement 指令幫助"));
        assertTrue(page.contains("第 1/3 頁"));
        assertTrue(page.contains("/wm help create"));

        final String topic = plain(messageRenderer.render(help.resolve(sender, "ownership rank set")));
        assertTrue(topic.contains("/wm ownership rank set <world> <player> <rank>"));
        assertTrue(topic.contains("worldmanagement.command.rank"));

        assertTrue(plain(messageRenderer.render(help.resolve(sender, "99"))).contains("無效的幫助頁碼"));
        assertTrue(plain(messageRenderer.render(help.resolve(sender, "unknown"))).contains("找不到可查看的指令幫助"));
    }

    private MessageService messages() {
        final InputStream resource = getClass().getResourceAsStream("/messages_zh_TW.yml");
        return MessageService.load(
            temporaryDirectory,
            "zh_TW",
            java.util.Objects.requireNonNull(resource),
            ignored -> { }
        );
    }

    private static CommandHelpService helpService() {
        final CommandAccessPolicy accessPolicy = new CommandAccessPolicy();
        accessPolicy.initialize(new ModuleManager(
            Arrays.stream(ModuleId.values())
                .map(module -> (io.github.bearl.worldmanagement.module.WorldManagementModule) () -> module)
                .toList(),
            new ModuleConfiguration(Map.of())
        ));
        final WorldManagementCommandSpec specification = new WorldManagementCommandSpec(
            new SuggestionCatalog(new OnlinePlayerSnapshot())
        );
        return new CommandHelpService(specification.root(), accessPolicy, 6);
    }

    private static String plain(final java.util.List<net.kyori.adventure.text.Component> components) {
        return components.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static CommandSender sender(final Set<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> method.getName().equals("hasPermission")
                ? permissions.contains(arguments[0])
                : defaultValue(method.getReturnType())
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }
}