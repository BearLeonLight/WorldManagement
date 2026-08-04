package io.github.bearl.worldmanagement.warp;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.command.WorldManagementCommandModule;
import io.github.bearl.worldmanagement.command.OnlinePlayerSnapshot;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldIdentity;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Warp command module owned by the Warp feature package. */
public final class WarpCommandModule implements WorldManagementCommandModule {

    private static final String WARP_PERMISSION = "worldmanagement.command.warp";
    private static final String TRUST_PERMISSION = "worldmanagement.command.trust";
    private static final String BYPASS_PERMISSION = "worldmanagement.bypass.protection";
    private static final String ADMIN_PERMISSION = "worldmanagement.admin.warp.manage";

    private final WorldManagementService service;
    private final WorldNameValidator nameValidator;
    private final WorldThreadDispatcher dispatcher;
    private final WarpService warpService;
    private final WarpTeleportGateway teleportGateway;
    private final boolean enabled;
    private final OnlinePlayerSnapshot onlinePlayers;
    private final MessageService messages;
    private final CommandMessageSender messageSender;

    public WarpCommandModule(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final WorldThreadDispatcher dispatcher,
        final WarpService warpService,
        final WarpTeleportGateway teleportGateway,
        final boolean enabled,
        final OnlinePlayerSnapshot onlinePlayers,
        final MessageService messages,
        final CommandMessageSender messageSender
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.warpService = Objects.requireNonNull(warpService, "warpService");
        this.teleportGateway = Objects.requireNonNull(teleportGateway, "teleportGateway");
        this.enabled = enabled;
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.messageSender = Objects.requireNonNull(messageSender, "messageSender");
    }

    @Override
    public String command() {
        return "warp";
    }

    @Override
    public boolean execute(final CommandSender sender, final String[] arguments) {
        if (!enabled) {
            send(sender, "warp.disabled");
            return true;
        }
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "warp" -> warp(sender, arguments);
            default -> false;
        };
    }

    private boolean warp(final CommandSender sender, final String[] arguments) {
        if (arguments.length < 2) {
            send(sender, "warp.usage");
            return true;
        }
        if (arguments[1].equalsIgnoreCase("trust")) {
            return trust(sender, arguments);
        }
        if (!sender.hasPermission(WARP_PERMISSION)) {
            send(sender, "warp.permission");
            return true;
        }
        return switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, arguments);
            case "set" -> set(sender, arguments);
            case "delete" -> delete(sender, arguments);
            case "tp" -> teleport(sender, arguments);
            default -> {
                send(sender, "warp.usage");
                yield true;
            }
        };
    }

    private boolean list(final CommandSender sender, final String[] arguments) {
        if (arguments.length != 3) {
            send(sender, "warp.list.usage");
            return true;
        }
        final WorldMetadata metadata = service.managedWorld(arguments[2]).orElse(null);
        if (metadata == null) send(sender, "warp.world-not-managed");
        else if (metadata.warps().isEmpty()) send(sender, "warp.list.empty");
        else send(sender, "warp.list.result", "warps", String.join(", ", metadata.warps().keySet().stream().sorted().toList()));
        return true;
    }

    private boolean set(final CommandSender sender, final String[] arguments) {
        if (!(sender instanceof Player player)) {
            send(sender, "warp.set.player-only");
            return true;
        }
        if (arguments.length != 5) {
            send(sender, "warp.set.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[2]);
        if (worldName == null || !canManage(sender, worldName)) return true;
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        try {
            final WarpVisibility visibility = WarpVisibility.valueOf(arguments[4].toUpperCase(Locale.ROOT));
            final WorldWarp validated = new WorldWarp(
                arguments[3], 0, 0, 0, 0, 0, visibility, Set.of(), Set.of(), ""
            );
            dispatcher.executeFor(player, () -> {
                if (!canManage(sender, worldName)) {
                    return;
                }
                final WorldMetadata metadata = service.managedWorld(worldName).orElse(null);
                final var location = player.getLocation();
                final org.bukkit.World runtimeWorld = location.getWorld();
                if (metadata == null
                    || VerifiedWorldRef.from(metadata).isEmpty()
                    || runtimeWorld == null
                    || !PaperWorldIdentity.capture(runtimeWorld).snapshot().equals(metadata.identity())) {
                    send(sender, "warp.set.wrong-world");
                    return;
                }
                final WorldWarp warp = new WorldWarp(
                    validated.name(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(), validated.visibility(),
                    validated.trustedPlayers(), validated.trustedRanks(), validated.requiredPermission()
                );
                warpService.set(worldName, warp, event(sender, "warp.set", worldName, "name=" + warp.name())).whenComplete((result, failure) ->
                    respondUpdate(responseTarget, "warp.set", result, failure, "warp", warp.name()));
            }, () -> respond(responseTarget, "warp.set.failure"));
        } catch (final IllegalArgumentException exception) {
            send(sender, "warp.set.invalid");
        }
        return true;
    }

    private boolean delete(final CommandSender sender, final String[] arguments) {
        if (arguments.length != 4) {
            send(sender, "warp.delete.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[2]);
        if (worldName == null || !canManage(sender, worldName)) return true;
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        warpService.delete(worldName, arguments[3], event(sender, "warp.delete", worldName, "name=" + arguments[3])).whenComplete((result, failure) ->
            respondUpdate(responseTarget, "warp.delete", result, failure, "warp", arguments[3]));
        return true;
    }

    private boolean teleport(final CommandSender sender, final String[] arguments) {
        if (!(sender instanceof Player player) || arguments.length != 4) {
            send(sender, "warp.teleport.usage");
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        dispatcher.executeFor(player, () -> {
            warpService.teleport(
                player.getUniqueId(),
                arguments[2],
                arguments[3],
                player.hasPermission(BYPASS_PERMISSION),
                teleportGateway
            ).whenComplete((result, failure) -> {
                if (failure != null) {
                    respond(responseTarget, "warp.teleport.failure");
                    return;
                }
                final String key = switch (result.status()) {
                    case TELEPORTED -> null;
                    case NOT_MANAGED -> "warp.teleport.not-managed";
                    case NOT_FOUND -> "warp.teleport.not-found";
                    case DENIED -> "warp.teleport.denied";
                    case FAILED -> "warp.teleport.failure";
                };
                if (key != null) {
                    respond(responseTarget, key, "world", arguments[2], "warp", arguments[3]);
                }
            });
        }, () -> respond(responseTarget, "warp.teleport.failure"));
        return true;
    }

    private boolean trust(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(TRUST_PERMISSION) || arguments.length != 6) {
            send(sender, "warp.trust.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[2]);
        if (worldName == null || !canManage(sender, worldName)) return true;
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        try {
            final boolean trusted = switch (arguments[4].toLowerCase(Locale.ROOT)) {
                case "add" -> true;
                case "remove" -> false;
                default -> throw new IllegalArgumentException();
            };
            final UUID playerId = onlinePlayers.resolve(arguments[5]).orElseThrow(IllegalArgumentException::new);
            warpService.trust(worldName, arguments[3], playerId, trusted,
                event(sender, "warp.trust", worldName, "name=" + arguments[3] + ",player=" + playerId)).whenComplete((result, failure) ->
                respondUpdate(responseTarget, "warp.trust", result, failure));
        } catch (final IllegalArgumentException exception) {
            send(sender, "warp.trust.invalid");
        }
        return true;
    }

    private void respondUpdate(
        final CommandMessageSender.Target target,
        final String keyPrefix,
        final WorldManagementService.UpdateResult result,
        final Throwable failure,
        final String... replacements
    ) {
        final String key;
        if (failure != null) {
            key = hasCause(failure, IllegalArgumentException.class)
                ? keyPrefix + ".invalid"
                : keyPrefix + ".failure";
        } else {
            key = switch (result.status()) {
                case UPDATED -> keyPrefix + ".success";
                case NOT_MANAGED -> "warp.world-not-managed";
                case NOT_READY -> "command.loading";
            };
        }
        respond(target, key, replacements);
    }

    private static boolean hasCause(final Throwable failure, final Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String validWorldName(final CommandSender sender, final String value) {
        try { return nameValidator.requireValidName(value); }
        catch (final IllegalArgumentException exception) { send(sender, "command.world-name-invalid"); return null; }
    }

    private boolean canManage(final CommandSender sender, final String worldName) {
        final WorldMetadata metadata = service.managedWorld(worldName).orElse(null);
        if (metadata == null) { send(sender, "warp.world-not-managed"); return false; }
        if (sender.hasPermission(ADMIN_PERMISSION)) return true;
        if (sender instanceof Player player && metadata.owner().equals(player.getUniqueId().toString())) return true;
        send(sender, "warp.not-manager");
        return false;
    }

    private static AuditEvent event(final CommandSender sender, final String action, final String worldName, final String detail) {
        final String actor = sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
        return new AuditEvent(Instant.now(), Optional.ofNullable(actor), action, worldName, detail);
    }

    private void send(final CommandSender sender, final String key, final String... replacements) {
        messageSender.send(sender, messages.component(key, replacements));
    }

    private void respond(final CommandSender sender, final String key, final String... replacements) {
        send(sender, key, replacements);
    }

    private void respond(final CommandMessageSender.Target target, final String key, final String... replacements) {
        messageSender.send(target, messages.component(key, replacements));
    }
}
