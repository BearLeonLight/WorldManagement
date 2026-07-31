package io.github.bearl.worldmanagement.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Player;

/** Sends command components on the scheduler that owns the final audience. */
public final class CommandMessageSender {

    private final WorldThreadDispatcher dispatcher;
    private final ConsoleOutput consoleOutput;

    public CommandMessageSender(final WorldThreadDispatcher dispatcher) {
        this(dispatcher, null);
    }

    public CommandMessageSender(final WorldThreadDispatcher dispatcher, final ConsoleOutput consoleOutput) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.consoleOutput = consoleOutput;
    }

    public Target capture(final CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        final CommandSender schedulerOwner = schedulerOwner(sender);
        if (schedulerOwner == null) {
            return new Target(Audience.empty(), null, false, false);
        }
        final Audience audience = sender instanceof ProxiedCommandSender proxy ? proxy.audience() : sender;
        return new Target(
            audience,
            schedulerOwner instanceof Player player ? player : null,
            consoleOutput != null && sender instanceof ConsoleCommandSender,
            true
        );
    }

    public void send(final Target target, final Component message) {
        Objects.requireNonNull(target, "target");
        if (!target.deliverable()) {
            return;
        }
        final Runnable delivery = target.console()
            ? () -> consoleOutput.info(Objects.requireNonNull(message, "message"))
            : () -> target.audience().sendMessage(Objects.requireNonNull(message, "message"));
        if (target.player() != null) {
            final Player player = target.player();
            dispatcher.executeFor(player, delivery, () -> { });
        } else {
            dispatcher.executeGlobal(delivery);
        }
    }

    public void send(final CommandSender sender, final Component message) {
        send(capture(sender), message);
    }

    private static CommandSender schedulerOwner(final CommandSender sender) {
        final Set<CommandSender> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        CommandSender current = sender;
        while (current instanceof ProxiedCommandSender proxy) {
            if (!visited.add(current)) {
                return null;
            }
            current = proxy.getCaller();
        }
        return current;
    }

    public record Target(Audience audience, Player player, boolean console, boolean deliverable) {
        public Target {
            Objects.requireNonNull(audience, "audience");
        }
    }
}