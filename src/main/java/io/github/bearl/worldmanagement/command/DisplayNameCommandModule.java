package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.ValidatedDisplayName;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DisplayNameCommandModule implements WorldManagementCommandModule {

    private static final String SET_PERMISSION = "worldmanagement.command.display-name.set";
    private static final String RESET_PERMISSION = "worldmanagement.command.display-name.reset";

    private final WorldManagementService service;
    private final WorldNameValidator nameValidator;
    private final DisplayNameValidator displayNameValidator;
    private final MessageService messages;
    private final CommandMessageSender messageSender;

    public DisplayNameCommandModule(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final DisplayNameValidator displayNameValidator,
        final MessageService messages,
        final CommandMessageSender messageSender
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
        this.displayNameValidator = Objects.requireNonNull(displayNameValidator, "displayNameValidator");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.messageSender = Objects.requireNonNull(messageSender, "messageSender");
    }

    @Override
    public String command() {
        return "display-name";
    }

    @Override
    public boolean execute(final CommandSender sender, final String[] arguments) {
        if (arguments.length < 2 || !arguments[0].equalsIgnoreCase("display-name")) {
            return false;
        }
        return switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "set" -> set(sender, arguments);
            case "reset" -> reset(sender, arguments);
            default -> {
                send(sender, "command.display-name.usage");
                yield true;
            }
        };
    }

    private boolean set(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(SET_PERMISSION)) {
            send(sender, "command.permission.display-name-set");
            return true;
        }
        if (arguments.length != 4) {
            send(sender, "command.display-name.usage");
            return true;
        }
        final String worldName = validLifecycleWorld(sender, arguments[2]);
        if (worldName == null) {
            return true;
        }
        final ValidatedDisplayName displayName;
        try {
            displayName = displayNameValidator.validate(arguments[3]);
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.display-name.invalid");
            return true;
        }
        final String actor = actor(sender);
        final CommandMessageSender.Target target = messageSender.capture(sender);
        service.updateLifecycle(
            worldName,
            metadata -> metadata.withDisplayName(displayName),
            event(actor, "world.display-name.set", worldName)
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                respond(target, "command.display-name.failure", "world", worldName);
            } else if (result.status() == WorldManagementService.UpdateStatus.UPDATED) {
                messageSender.send(target, messages.component("command.display-name.set-success", Map.of(
                    "world", Component.text(worldName),
                    "display", displayName.component()
                )));
            } else {
                respond(target, updateFailureKey(result.status()), "world", worldName);
            }
        });
        return true;
    }

    private boolean reset(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(RESET_PERMISSION)) {
            send(sender, "command.permission.display-name-reset");
            return true;
        }
        if (arguments.length != 3) {
            send(sender, "command.display-name.usage");
            return true;
        }
        final String worldName = validLifecycleWorld(sender, arguments[2]);
        if (worldName == null) {
            return true;
        }
        final String actor = actor(sender);
        final CommandMessageSender.Target target = messageSender.capture(sender);
        service.updateLifecycle(
            worldName,
            metadata -> metadata.resetDisplayName(),
            event(actor, "world.display-name.reset", worldName)
        ).whenComplete((result, failure) -> respond(target,
            failure != null
                ? "command.display-name.failure"
                : result.status() == WorldManagementService.UpdateStatus.UPDATED
                    ? "command.display-name.reset-success"
                    : updateFailureKey(result.status()),
            "world", worldName));
        return true;
    }

    static String updateFailureKey(final WorldManagementService.UpdateStatus status) {
        return switch (status) {
            case NOT_MANAGED -> "command.display-name.not-managed";
            case NOT_READY -> "command.loading";
            case UPDATED -> throw new IllegalArgumentException("UPDATED is not a failure status.");
        };
    }

    private String validLifecycleWorld(final CommandSender sender, final String value) {
        final String worldName;
        try {
            worldName = nameValidator.requireValidName(value);
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.world-name-invalid");
            return null;
        }
        if (service.lifecycleWorld(worldName).isEmpty()) {
            send(sender, "command.display-name.not-managed", "world", worldName);
            return null;
        }
        return worldName;
    }

    private static AuditEvent event(final String actor, final String action, final String worldName) {
        return new AuditEvent(Instant.now(), Optional.ofNullable(actor), action, worldName, "updated");
    }

    private static String actor(final CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
    }

    private void send(final CommandSender sender, final String key, final String... replacements) {
        messageSender.send(sender, messages.component(key, replacements));
    }

    private void respond(final CommandMessageSender.Target target, final String key, final String... replacements) {
        messageSender.send(target, messages.component(key, replacements));
    }
}