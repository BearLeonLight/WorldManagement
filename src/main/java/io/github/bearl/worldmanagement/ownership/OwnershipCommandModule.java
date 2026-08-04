package io.github.bearl.worldmanagement.ownership;

import io.github.bearl.worldmanagement.audit.AuditAdmission;
import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.command.WorldManagementCommandModule;
import io.github.bearl.worldmanagement.command.OnlinePlayerSnapshot;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.Rank;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Owner, rank, and access command module owned by the ownership feature package. */
public final class OwnershipCommandModule implements WorldManagementCommandModule {

    private static final String OWNER_PERMISSION = "worldmanagement.command.owner";
    private static final String RANK_PERMISSION = "worldmanagement.command.rank";
    private static final String ACCESS_PERMISSION = "worldmanagement.command.access";
    private static final String ADMIN_PERMISSION = "worldmanagement.admin.ownership.manage";

    private final WorldManagementService service;
    private final WorldNameValidator nameValidator;
    private final WorldThreadDispatcher dispatcher;
    private final AuditService auditService;
    private final int maximumCustomRanks;
    private final OnlinePlayerSnapshot onlinePlayers;
    private final MessageService messages;
    private final CommandMessageSender messageSender;
    private final DiagnosticLogger diagnostics;

    public OwnershipCommandModule(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final WorldThreadDispatcher dispatcher,
        final AuditService auditService,
        final int maximumCustomRanks,
        final OnlinePlayerSnapshot onlinePlayers,
        final MessageService messages,
        final CommandMessageSender messageSender
    ) {
        this(service, nameValidator, dispatcher, auditService, maximumCustomRanks, onlinePlayers, messages, messageSender, null);
    }

    public OwnershipCommandModule(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final WorldThreadDispatcher dispatcher,
        final AuditService auditService,
        final int maximumCustomRanks,
        final OnlinePlayerSnapshot onlinePlayers,
        final MessageService messages,
        final CommandMessageSender messageSender,
        final DiagnosticLogger diagnostics
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.maximumCustomRanks = maximumCustomRanks;
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.messageSender = Objects.requireNonNull(messageSender, "messageSender");
        this.diagnostics = diagnostics;
    }

    @Override
    public String command() {
        return "ownership";
    }

    @Override
    public boolean execute(final CommandSender sender, final String[] arguments) {
        if (diagnostics != null && arguments.length > 0) {
            diagnostics.basic(DebugArea.OWNERSHIP, "ownership_command_received", () -> java.util.Map.of(
                "actorUuid", actor(sender),
                "action", arguments[0].toLowerCase(Locale.ROOT)
            ));
        }
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "owner" -> owner(sender, arguments);
            case "rank" -> rank(sender, arguments);
            case "access" -> access(sender, arguments);
            default -> false;
        };
    }

    private boolean owner(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(OWNER_PERMISSION) || !sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, "ownership.owner.admin-required");
            return true;
        }
        if (arguments.length != 3 && arguments.length != 4) {
            send(sender, "ownership.owner.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[2]);
        if (worldName == null) return true;
        try {
            final String owner = arguments[1].equalsIgnoreCase("set") && arguments.length == 4
                ? onlinePlayers.resolve(arguments[3]).orElseThrow(IllegalArgumentException::new).toString()
                : arguments[1].equalsIgnoreCase("remove") && arguments.length == 3 ? WorldMetadata.SERVER_OWNER : null;
            if (owner == null) throw new IllegalArgumentException();
            final String actor = actor(sender);
            final AuditEvent event = event(actor, "world.owner", worldName, "owner=" + owner);
            final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
            final Runnable action = () -> service.updateManaged(worldName, metadata -> metadata.withOwner(owner),
                event).whenComplete((result, failure) ->
                respondMutation(responseTarget, "ownership.owner", result, failure));
            if (auditService.requiresStrictAdmission("world.owner")) {
                auditService.admit(actor, "world.owner", worldName, "pending").thenAccept(admission -> {
                    if (admission == AuditAdmission.REJECTED) respond(responseTarget, "command.audit-rejected");
                    else action.run();
                });
            } else action.run();
        } catch (final IllegalArgumentException exception) {
            send(sender, "ownership.owner.invalid");
        }
        return true;
    }

    private boolean access(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(ACCESS_PERMISSION) || arguments.length != 4) {
            send(sender, "ownership.access.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[1]);
        if (worldName == null || !canManage(sender, worldName)) return true;
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        try {
            final String operation = arguments[2].toLowerCase(Locale.ROOT);
            service.updateManaged(worldName, metadata -> accessMutation(metadata, operation, arguments[3]),
                event(sender, "world.access", worldName, "operation=" + operation)).whenComplete((result, failure) ->
                respondMutation(responseTarget, "ownership.access", result, failure));
        } catch (final IllegalArgumentException exception) { send(sender, "ownership.access.invalid"); }
        return true;
    }

    private boolean rank(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(RANK_PERMISSION) || arguments.length < 3) {
            send(sender, "ownership.rank.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[2]);
        if (worldName == null || !canManage(sender, worldName)) return true;
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        try {
            final String operation = arguments[1].toLowerCase(Locale.ROOT);
            service.updateManaged(worldName, metadata -> rankMutation(metadata, operation, arguments),
                event(sender, "world.rank", worldName, "operation=" + operation)).whenComplete((result, failure) ->
                respondMutation(responseTarget, "ownership.rank", result, failure));
        } catch (final IllegalArgumentException exception) { send(sender, "ownership.rank.invalid"); }
        return true;
    }

    private void respondMutation(
        final CommandMessageSender.Target target,
        final String keyPrefix,
        final WorldManagementService.UpdateResult result,
        final Throwable failure
    ) {
        if (failure != null) {
            respond(target, isInvalidInput(failure) ? keyPrefix + ".invalid" : keyPrefix + ".failure");
            return;
        }
        final String key = switch (result.status()) {
            case UPDATED -> keyPrefix + ".success";
            case NOT_MANAGED -> "ownership.world-not-managed";
            case NOT_READY -> "command.loading";
        };
        respond(target, key);
    }

    private static boolean isInvalidInput(final Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IllegalArgumentException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private WorldMetadata accessMutation(final WorldMetadata metadata, final String operation, final String value) {
        final AccessControl current = metadata.accessControl();
        return switch (operation) {
            case "mode" -> metadata.withAccessControl(new AccessControl(AccessMode.valueOf(value.toUpperCase(Locale.ROOT)), current.entries()));
            case "add", "remove" -> {
                final Set<UUID> entries = new LinkedHashSet<>(current.entries());
                final UUID playerId = onlinePlayers.resolve(value).orElseThrow(IllegalArgumentException::new);
                if (operation.equals("add")) entries.add(playerId); else entries.remove(playerId);
                yield metadata.withAccessControl(new AccessControl(current.mode(), entries));
            }
            default -> throw new IllegalArgumentException();
        };
    }

    private WorldMetadata rankMutation(final WorldMetadata metadata, final String operation, final String[] arguments) {
        return switch (operation) {
            case "create" -> arguments.length == 4 ? metadata.withRank(arguments[3].toUpperCase(Locale.ROOT), new Rank(arguments[3], Set.of()), maximumCustomRanks) : invalid();
            case "delete" -> arguments.length == 4 ? metadata.withoutRank(arguments[3].toUpperCase(Locale.ROOT)) : invalid();
            case "set" -> arguments.length == 5 ? metadata.withPlayerRank(onlinePlayers.resolve(arguments[3]).orElseThrow(IllegalArgumentException::new), arguments[4].toUpperCase(Locale.ROOT)) : invalid();
            case "remove" -> arguments.length == 4 ? metadata.withoutPlayerRank(onlinePlayers.resolve(arguments[3]).orElseThrow(IllegalArgumentException::new)) : invalid();
            case "perm" -> arguments.length == 6 ? metadata.withRankPermission(arguments[3].toUpperCase(Locale.ROOT),
                RankPermission.valueOf(arguments[5].toUpperCase(Locale.ROOT)), granted(arguments[4])) : invalid();
            case "toggle" -> arguments.length == 3 ? metadata.withRankSystemEnabled(!metadata.rankSystemEnabled()) : invalid();
            default -> invalid();
        };
    }

    private WorldMetadata invalid() { throw new IllegalArgumentException(); }

    private boolean granted(final String operation) {
        return switch (operation.toLowerCase(Locale.ROOT)) { case "add" -> true; case "remove" -> false; default -> throw new IllegalArgumentException(); };
    }

    private String validWorldName(final CommandSender sender, final String value) {
        try { return nameValidator.requireValidName(value); }
        catch (final IllegalArgumentException exception) { send(sender, "command.world-name-invalid"); return null; }
    }

    private boolean canManage(final CommandSender sender, final String worldName) {
        final WorldMetadata metadata = service.managedWorld(worldName).orElse(null);
        if (metadata == null) { send(sender, "ownership.world-not-managed"); return false; }
        if (sender.hasPermission(ADMIN_PERMISSION)) return true;
        if (sender instanceof Player player && metadata.owner().equals(player.getUniqueId().toString())) return true;
        send(sender, "ownership.not-manager");
        return false;
    }

    private static String actor(final CommandSender sender) { return sender instanceof Player player ? player.getUniqueId().toString() : sender.getName(); }

    private static AuditEvent event(final CommandSender sender, final String action, final String worldName, final String detail) {
        return event(actor(sender), action, worldName, detail);
    }

    private static AuditEvent event(final String actor, final String action, final String worldName, final String detail) {
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
