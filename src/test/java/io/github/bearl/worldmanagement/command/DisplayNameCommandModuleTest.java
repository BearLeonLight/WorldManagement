package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.audit.AuditPolicy;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DisplayNameCommandModuleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void setsAValidatedGreedyDisplayNameAndRendersItsComponent() throws Exception {
        try (Fixture fixture = fixture()) {
            final CapturingSender sender = new CapturingSender();
            final String displayName = "<red>創意  世界</red>";

            fixture.module.execute(sender.sender(), new String[] {
                "display-name", "set", "creative", displayName
            });
            final Component response = sender.message();

            assertEquals(displayName, fixture.service.managedWorld("creative").orElseThrow().displayName());
            assertEquals(
                Component.text("創意  世界", NamedTextColor.RED),
                findColoredText(response, NamedTextColor.RED)
            );
            assertEquals(List.of("world.display-name.set"),
                fixture.auditEvents.stream().map(AuditEvent::action).toList());
            assertFalse(fixture.auditEvents.getFirst().detail().contains("創意"));
        }
    }

    @Test
    void rejectsInteractiveMiniMessageWithoutMutatingMetadata() throws Exception {
        try (Fixture fixture = fixture()) {
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {
                "display-name", "set", "creative", "<click:run_command:/op>unsafe</click>"
            });

            assertEquals("creative", fixture.service.managedWorld("creative").orElseThrow().displayName());
            assertEquals("顯示名稱無效。", plain(sender.message()));
            assertEquals(List.of(), fixture.auditEvents);
        }
    }

    @Test
    void resetsDisplayNameToTheWorldId() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.service.update("creative", metadata -> metadata.withDisplayName(
                new DisplayNameValidator().validate("<gold>Creative</gold>")
            )).join();
            final CapturingSender sender = new CapturingSender();

            fixture.module.execute(sender.sender(), new String[] {"display-name", "reset", "creative"});
            sender.message();

            assertEquals("creative", fixture.service.managedWorld("creative").orElseThrow().displayName());
            assertEquals(List.of("world.display-name.reset"),
                fixture.auditEvents.stream().map(AuditEvent::action).toList());
        }
    }

    private Fixture fixture() {
        final PluginIoExecutor executor = new PluginIoExecutor("DisplayCommandTest");
        final List<AuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        final AuditService audit = new AuditService(
            executor,
            auditEvents::add,
            Logger.getLogger("DisplayCommandTest"),
            AuditPolicy.BEST_EFFORT
        );
        final WorldManagementService service = new WorldManagementService(
            executor,
            new InMemoryWorldMetadataRepository(),
            new WorldRegistry(),
            audit
        );
        service.load().join();
        service.adopt("creative", true).join();
        final InputStream bundled = DisplayNameCommandModuleTest.class.getResourceAsStream("/messages_zh_TW.yml");
        final MessageService messages = MessageService.load(
            temporaryDirectory,
            "zh_TW",
            java.util.Objects.requireNonNull(bundled),
            ignored -> { }
        );
        return new Fixture(
            executor,
            service,
            auditEvents,
            new DisplayNameCommandModule(
                service,
                new WorldNameValidator(),
                new DisplayNameValidator(),
                messages,
                new CommandMessageSender(new ImmediateDispatcher())
            )
        );
    }

    private static Component findColoredText(final Component component, final NamedTextColor color) {
        if (component.color() == color && !plain(component).isEmpty()) {
            return component;
        }
        return component.children().stream()
            .map(child -> findColoredText(child, color))
            .filter(child -> !plain(child).isEmpty())
            .findFirst()
            .orElse(Component.empty());
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private record Fixture(
        PluginIoExecutor executor,
        WorldManagementService service,
        List<AuditEvent> auditEvents,
        DisplayNameCommandModule module
    ) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    private static final class CapturingSender {
        private final CompletableFuture<Component> message = new CompletableFuture<>();
        private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("hasPermission")) return true;
                if (method.getName().equals("getName")) return "console";
                if (method.getName().equals("sendMessage") && arguments != null) {
                    for (final Object argument : arguments) {
                        if (argument instanceof Component component) message.complete(component);
                    }
                }
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return defaultValue(method.getReturnType());
            }
        );

        CommandSender sender() {
            return sender;
        }

        Component message() throws Exception {
            return message.get(2, TimeUnit.SECONDS);
        }
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }

    private static final class ImmediateDispatcher implements WorldThreadDispatcher {
        @Override
        public void executeGlobal(final Runnable task) { task.run(); }

        @Override
        public void executeAt(final Location location, final Runnable task) { task.run(); }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() { }
    }
}