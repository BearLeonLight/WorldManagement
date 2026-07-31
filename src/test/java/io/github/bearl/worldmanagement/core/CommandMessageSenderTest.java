package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class CommandMessageSenderTest {

    @Test
    void routesPlayerMessagesThroughEntityDispatcher() {
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicReference<Component> received = new AtomicReference<>();
        final Player player = sender(Player.class, received);

        final CommandMessageSender messages = new CommandMessageSender(dispatcher);
        messages.send(messages.capture(player), Component.text("player"));

        assertEquals(player, dispatcher.entity.get());
        assertEquals(Component.text("player"), received.get());
        assertEquals(0, dispatcher.globalExecutions.get());
    }

    @Test
    void routesConsoleMessagesThroughGlobalDispatcher() {
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicReference<Component> received = new AtomicReference<>();
        final CommandSender console = sender(CommandSender.class, received);

        final CommandMessageSender messages = new CommandMessageSender(dispatcher);
        messages.send(messages.capture(console), Component.text("console"));

        assertEquals(1, dispatcher.globalExecutions.get());
        assertEquals(Component.text("console"), received.get());
        assertNull(dispatcher.entity.get());
    }

    @Test
    void routesProxyMessagesToTheFinalCaller() {
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicReference<Component> received = new AtomicReference<>();
        final Player caller = sender(Player.class, received);
        final CommandSender callee = sender(CommandSender.class, new AtomicReference<>());
        final AtomicReference<Component> proxyAudienceMessage = new AtomicReference<>();
        final Audience proxyAudience = new Audience() {
            @Override
            public void sendMessage(final Component message) {
                proxyAudienceMessage.set(message);
            }
        };
        final ProxiedCommandSender proxy = proxy(caller, callee, proxyAudience);

        final CommandMessageSender messages = new CommandMessageSender(dispatcher);
        messages.send(messages.capture(proxy), Component.text("proxy"));

        assertEquals(caller, dispatcher.entity.get());
        assertNull(received.get());
        assertEquals(Component.text("proxy"), proxyAudienceMessage.get());
    }

    @Test
    void dropsCyclicProxyMessages() {
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final AtomicReference<CommandSender> caller = new AtomicReference<>();
        final ProxiedCommandSender proxy = (ProxiedCommandSender) Proxy.newProxyInstance(
            ProxiedCommandSender.class.getClassLoader(),
            new Class<?>[] { ProxiedCommandSender.class },
            (instance, method, arguments) -> switch (method.getName()) {
                case "getCaller" -> caller.get();
                case "getCallee" -> instance;
                default -> defaultValue(method.getReturnType());
            }
        );
        caller.set(proxy);

        final CommandMessageSender messages = new CommandMessageSender(dispatcher);
        messages.send(messages.capture(proxy), Component.text("cycle"));

        assertEquals(0, dispatcher.globalExecutions.get());
        assertNull(dispatcher.entity.get());
    }

    @SuppressWarnings("unchecked")
    private static <T extends CommandSender> T sender(final Class<T> type, final AtomicReference<Component> received) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (instance, method, arguments) -> {
            if (method.getName().equals("sendMessage") && arguments != null && arguments.length == 1 && arguments[0] instanceof Component component) {
                received.set(component);
                return null;
            }
            if (method.getName().equals("equals")) return instance == arguments[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
            return defaultValue(method.getReturnType());
        });
    }

    private static ProxiedCommandSender proxy(final CommandSender caller, final CommandSender callee, final Audience audience) {
        return (ProxiedCommandSender) Proxy.newProxyInstance(
            ProxiedCommandSender.class.getClassLoader(),
            new Class<?>[] { ProxiedCommandSender.class },
            (instance, method, arguments) -> switch (method.getName()) {
                case "getCaller" -> caller;
                case "getCallee" -> callee;
                case "audience" -> audience;
                case "equals" -> instance == arguments[0];
                case "hashCode" -> System.identityHashCode(instance);
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

    private static final class RecordingDispatcher implements WorldThreadDispatcher {
        private final AtomicInteger globalExecutions = new AtomicInteger();
        private final AtomicReference<Entity> entity = new AtomicReference<>();

        @Override
        public void executeGlobal(final Runnable task) {
            globalExecutions.incrementAndGet();
            task.run();
        }

        @Override
        public void executeAt(final Location location, final Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            this.entity.set(entity);
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
        }
    }
}