package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.audit.AuditService;
import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.core.CommandMessageSender;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.protection.TeleportBypassTokens;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import io.github.bearl.worldmanagement.world.lifecycle.WorldLifecycleCoordinator;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldTeleportGateway;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.warp.WarpService;
import io.github.bearl.worldmanagement.warp.WarpTeleportGateway;
import io.github.bearl.worldmanagement.warp.WarpCommandModule;
import io.github.bearl.worldmanagement.ownership.OwnershipCommandModule;
import io.github.bearl.worldmanagement.storage.StorageMigrationService;
import io.github.bearl.worldmanagement.storage.StorageProvider;
import java.util.List;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Initial `/wm` command slice: metadata-only adopt and cache-backed list. */
public final class WorldManagementCommand {

    private static final String ADOPT_PERMISSION = "worldmanagement.command.adopt";
    private static final String LIST_PERMISSION = "worldmanagement.command.list";
    private static final String CREATE_PERMISSION = "worldmanagement.command.create";
    private static final String LOAD_PERMISSION = "worldmanagement.command.load";
    private static final String UNLOAD_PERMISSION = "worldmanagement.command.unload";
    private static final String REMOVE_PERMISSION = "worldmanagement.command.remove";
    private static final String IMPORT_PERMISSION = "worldmanagement.command.import";
    private static final String DELETE_PERMISSION = "worldmanagement.command.delete";
    private static final String MANAGE_PERMISSION = "worldmanagement.command.manage";
    private static final String TELEPORT_PERMISSION = "worldmanagement.command.tp";
    private static final String TELEPORT_OTHERS_PERMISSION = "worldmanagement.command.tp.others";
    private static final String TELEPORT_ANY_PERMISSION = "worldmanagement.command.tp.any.explicit";
    private static final String WARP_PERMISSION = "worldmanagement.command.warp";
    private static final String TRUST_PERMISSION = "worldmanagement.command.trust";
    private static final String OWNER_PERMISSION = "worldmanagement.command.owner";
    private static final String RANK_PERMISSION = "worldmanagement.command.rank";
    private static final String ACCESS_PERMISSION = "worldmanagement.command.access";
    private static final String BYPASS_PERMISSION = "worldmanagement.bypass.protection";

    private final WorldManagementService service;
    private final WorldNameValidator nameValidator;
    private final WorldThreadDispatcher threadDispatcher;
    private final WorldLifecycleCoordinator lifecycleService;
    private final WarpService warpService;
    private final WarpTeleportGateway teleportGateway;
    private final WorldTeleportGateway worldTeleportGateway;
    private final AuditService auditService;
    private final boolean warpEnabled;
    private final int maximumCustomRanks;
    private final StorageMigrationService migrationService;
    private final MessageService messages;
    private final CommandMessageSender messageSender;
    private final CommandHelpMessageRenderer helpMessages;
    private final WorldListMessageRenderer listMessages;
    private final java.util.Map<String, WorldManagementCommandModule> modules;
    private final OnlinePlayerSnapshot onlinePlayers;
    private final DiagnosticLogger diagnostics;
    private final TeleportBypassTokens teleportBypassTokens;
    private final LoadedWorldCatalog loadedWorlds;

    public WorldManagementCommand(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final WorldThreadDispatcher threadDispatcher,
        final WorldLifecycleCoordinator lifecycleService,
        final WarpService warpService,
        final WarpTeleportGateway teleportGateway,
        final WorldTeleportGateway worldTeleportGateway,
        final AuditService auditService,
        final boolean warpEnabled,
        final int maximumCustomRanks,
        final StorageMigrationService migrationService,
        final MessageService messages,
        final CommandMessageSender messageSender,
        final OnlinePlayerSnapshot onlinePlayers
    ) {
        this(service, nameValidator, threadDispatcher, lifecycleService, warpService, teleportGateway, worldTeleportGateway, auditService,
            warpEnabled, maximumCustomRanks, migrationService, messages, messageSender, onlinePlayers, null,
            new TeleportBypassTokens(), new LoadedWorldCatalog());
    }

    public WorldManagementCommand(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final WorldThreadDispatcher threadDispatcher,
        final WorldLifecycleCoordinator lifecycleService,
        final WarpService warpService,
        final WarpTeleportGateway teleportGateway,
        final WorldTeleportGateway worldTeleportGateway,
        final AuditService auditService,
        final boolean warpEnabled,
        final int maximumCustomRanks,
        final StorageMigrationService migrationService,
        final MessageService messages,
        final CommandMessageSender messageSender,
        final OnlinePlayerSnapshot onlinePlayers,
        final DiagnosticLogger diagnostics
    ) {
        this(service, nameValidator, threadDispatcher, lifecycleService, warpService, teleportGateway, worldTeleportGateway, auditService,
            warpEnabled, maximumCustomRanks, migrationService, messages, messageSender, onlinePlayers, diagnostics,
            new TeleportBypassTokens(), new LoadedWorldCatalog());
    }

    public WorldManagementCommand(
        final WorldManagementService service,
        final WorldNameValidator nameValidator,
        final WorldThreadDispatcher threadDispatcher,
        final WorldLifecycleCoordinator lifecycleService,
        final WarpService warpService,
        final WarpTeleportGateway teleportGateway,
        final WorldTeleportGateway worldTeleportGateway,
        final AuditService auditService,
        final boolean warpEnabled,
        final int maximumCustomRanks,
        final StorageMigrationService migrationService,
        final MessageService messages,
        final CommandMessageSender messageSender,
        final OnlinePlayerSnapshot onlinePlayers,
        final DiagnosticLogger diagnostics,
        final TeleportBypassTokens teleportBypassTokens,
        final LoadedWorldCatalog loadedWorlds
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
        this.threadDispatcher = Objects.requireNonNull(threadDispatcher, "threadDispatcher");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.warpService = Objects.requireNonNull(warpService, "warpService");
        this.teleportGateway = Objects.requireNonNull(teleportGateway, "teleportGateway");
        this.worldTeleportGateway = Objects.requireNonNull(worldTeleportGateway, "worldTeleportGateway");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.warpEnabled = warpEnabled;
        this.maximumCustomRanks = maximumCustomRanks;
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.messageSender = Objects.requireNonNull(messageSender, "messageSender");
        this.helpMessages = new CommandHelpMessageRenderer(messages);
        this.listMessages = new WorldListMessageRenderer(messages);
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.diagnostics = diagnostics;
        this.teleportBypassTokens = Objects.requireNonNull(teleportBypassTokens, "teleportBypassTokens");
        this.loadedWorlds = Objects.requireNonNull(loadedWorlds, "loadedWorlds");
        final WorldManagementCommandModule warpModule = new WarpCommandModule(
            service, nameValidator, threadDispatcher, warpService, teleportGateway, warpEnabled, onlinePlayers, messages, messageSender
        );
        final WorldManagementCommandModule ownershipModule = new OwnershipCommandModule(
            service, nameValidator, threadDispatcher, auditService, maximumCustomRanks, onlinePlayers, messages, messageSender, diagnostics
        );
        final WorldManagementCommandModule identityModule = new IdentityCommandModule(
            service, nameValidator, auditService, messages, messageSender
        );
        final WorldManagementCommandModule displayNameModule = new DisplayNameCommandModule(
            service, nameValidator, new DisplayNameValidator(), messages, messageSender
        );
        this.modules = java.util.Map.of(
            "warp", warpModule,
            "ownership", ownershipModule,
            "identity", identityModule,
            "display-name", displayNameModule
        );
    }

    void showHelp(final CommandSender sender, final CommandHelpService.Result result) {
        helpMessages.render(result).forEach(component -> messageSender.send(sender, component));
    }

    void showSyntaxFeedback(final CommandSender sender, final CommandSyntaxFeedback feedback) {
        send(sender, "command.syntax." + feedback.kind().name().toLowerCase(Locale.ROOT));
        feedback.usageLines().forEach(usage -> send(sender, "command.help.usage", "usage", usage));
        send(sender, "command.syntax.help-hint", "topic", feedback.topicPath().isBlank() ? "" : " " + feedback.topicPath());
    }

    public boolean execute(final CommandSender sender, final String[] arguments) {
        final String actor = actorOf(sender);
        if (arguments.length == 0) {
            send(sender, "command.root.usage");
            return true;
        }

        if (diagnostics != null) {
            diagnostics.basic(DebugArea.COMMAND, "command_received", () -> commandDiagnosticFields(sender, actor, arguments[0]));
            diagnostics.verbose(DebugArea.COMMAND, "command_arguments_resolved", () -> java.util.Map.of(
                "action", arguments[0].toLowerCase(Locale.ROOT),
                "argumentCount", Integer.toString(arguments.length)
            ));
        }

        final WorldManagementCommandModule module = modules.get(arguments[0].toLowerCase(Locale.ROOT));
        if (module != null) {
            return module.execute(sender, arguments[0].equalsIgnoreCase("ownership") ? Arrays.copyOfRange(arguments, 1, arguments.length) : arguments);
        }

        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "adopt" -> adopt(sender, arguments);
            case "create" -> create(sender, actor, arguments);
            case "load" -> load(sender, arguments);
            case "unload" -> unload(sender, arguments);
            case "remove" -> remove(sender, actor, arguments);
            case "manage" -> manage(sender, actor, arguments);
            case "tp" -> teleport(sender, actor, arguments);
            case "import" -> importWorld(sender, arguments);
            case "delete" -> delete(sender, actor, arguments);
            case "storage" -> storage(sender, actor, arguments);
            case "list" -> list(sender, arguments);
            default -> {
                send(sender, "command.root.unknown");
                yield true;
            }
        };
    }

    private java.util.Map<String, String> commandDiagnosticFields(
        final CommandSender sender,
        final String actor,
        final String action
    ) {
        final java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put(sender instanceof Player ? "actorUuid" : "actor", actor);
        fields.put("route", action.toLowerCase(Locale.ROOT));
        if (sender instanceof Player player) {
            if (diagnostics.privacy().includePlayerNames()) {
                fields.put("playerName", player.getName());
            }
            if (diagnostics.privacy().includeMaskedIpAddresses() && player.getAddress() != null
                && player.getAddress().getAddress() != null) {
                io.github.bearl.worldmanagement.core.DiagnosticPrivacy
                    .maskIpAddress(player.getAddress().getAddress().getHostAddress())
                    .ifPresent(masked -> fields.put("maskedIp", masked));
            }
        }
        return fields;
    }

    private boolean create(final CommandSender sender, final String actor, final String[] arguments) {
        if (arguments.length != 4) {
            send(sender, "command.create.usage");
            return true;
        }
        return executeCreate(
            sender,
            arguments[1],
            arguments[2],
            arguments[3],
            CreateCommandOptions.defaults()
        );
    }

    boolean executeCreate(
        final CommandSender sender,
        final String suppliedWorldName,
        final String suppliedEnvironment,
        final String suppliedType,
        final CreateCommandOptions options
    ) {
        if (!sender.hasPermission(CREATE_PERMISSION)) {
            send(sender, "command.permission.create");
            return true;
        }
        Objects.requireNonNull(options, "options");
        final String actor = actorOf(sender);
        final String worldName;
        try {
            worldName = nameValidator.requireValidName(suppliedWorldName);
            final WorldRuntimeGateway.WorldEnvironment environment = WorldRuntimeGateway.WorldEnvironment.valueOf(
                suppliedEnvironment.toUpperCase(Locale.ROOT)
            );
            final WorldRuntimeGateway.WorldType type = WorldRuntimeGateway.WorldType.valueOf(
                suppliedType.toUpperCase(Locale.ROOT)
            );
            send(sender, "command.create.started", "world", worldName);
            final AuditEvent event = auditEvent(actor, "world.create", worldName, "");
            final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
            threadDispatcher.executeGlobal(() -> lifecycleService.create(
                new io.github.bearl.worldmanagement.world.lifecycle.WorldCreationRequest(
                    worldName,
                    environment,
                    type,
                    options.seed(),
                    options.generator().map(io.github.bearl.worldmanagement.world.WorldGeneratorReference::parse),
                    options.generatorSettings(),
                    options.generateStructures(),
                    options.bonusChest(),
                    options.biomeProvider().map(io.github.bearl.worldmanagement.world.WorldGeneratorReference::parse),
                    options.forcedSpawnPosition(),
                    options.detached()
                ),
                event
            )
                .whenComplete((result, failure) -> respond(
                    responseTarget,
                    failure == null
                        ? createResultKey("command.create", options.detached(), result.status())
                        : "command.create.backend-failure",
                    "world", worldName
                )));
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.create.invalid");
        }
        return true;
    }

    private boolean load(final CommandSender sender, final String[] arguments) {
        if (arguments.length == 4 && arguments[3].equals("--detached")) {
            return loadDetached(sender, arguments);
        }
        return runLifecycle(sender, arguments, LOAD_PERMISSION, "load", WorldLifecycleCoordinator.LifecycleStatus.LOADED, "loaded");
    }

    private boolean loadDetached(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(LOAD_PERMISSION)) {
            send(sender, "command.permission.lifecycle", "operation", "load");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[1]);
        if (worldName == null) {
            return true;
        }
        final WorldRuntimeGateway.WorldEnvironment environment;
        try {
            environment = WorldRuntimeGateway.WorldEnvironment.valueOf(arguments[2].toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.load.usage");
            return true;
        }
        final String actor = actorOf(sender);
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        lifecycleService.validateImportable(worldName).whenComplete((valid, validationFailure) -> {
            if (validationFailure != null) {
                respond(responseTarget, "command.load.validation-failure", "world", worldName);
                return;
            }
            if (!valid) {
                respond(responseTarget, "command.load.not-importable", "world", worldName);
                return;
            }
            threadDispatcher.executeGlobal(() -> lifecycleService.importWorld(
                worldName, environment, true, auditEvent(actor, "world.load.detached", worldName, "")
            ).whenComplete((result, failure) -> respond(
                responseTarget,
                failure == null
                    ? createResultKey("command.load", true, result.status())
                    : "command.load.backend-failure",
                "world", worldName
            )));
        });
        return true;
    }

    private boolean unload(final CommandSender sender, final String[] arguments) {
        return runLifecycle(sender, arguments, UNLOAD_PERMISSION, "unload", WorldLifecycleCoordinator.LifecycleStatus.UNLOADED, "unloaded");
    }

    private boolean remove(final CommandSender sender, final String actor, final String[] arguments) {
        if (!sender.hasPermission(REMOVE_PERMISSION)) {
            send(sender, "command.permission.remove");
            return true;
        }
        if (arguments.length == 4 && arguments[2].equalsIgnoreCase("purge") && arguments[3].equalsIgnoreCase("confirm")) {
            return purgeDetached(sender, actor, arguments[1]);
        }
        if (arguments.length != 2) {
            send(sender, "command.remove.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[1]);
        if (worldName == null) {
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        if (auditService.requiresStrictAdmission("world.remove")) {
            auditService.admit(actor, "world.remove", worldName, "pending").thenAccept(admission -> {
                if (admission == io.github.bearl.worldmanagement.audit.AuditAdmission.REJECTED) {
                    respond(responseTarget, "command.audit-rejected");
                    return;
                }
                threadDispatcher.executeGlobal(() -> removeAfterAdmission(responseTarget, actor, worldName));
            });
            return true;
        }
        threadDispatcher.executeGlobal(() -> removeAfterAdmission(responseTarget, actor, worldName));
        return true;
    }

    private boolean purgeDetached(final CommandSender sender, final String actor, final String suppliedName) {
        final String worldName = validWorldName(sender, suppliedName);
        if (worldName == null) {
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        if (auditService.requiresStrictAdmission("world.remove.purge")) {
            auditService.admit(actor, "world.remove.purge", worldName, "pending").thenAccept(admission -> {
                if (admission == io.github.bearl.worldmanagement.audit.AuditAdmission.REJECTED) {
                    respond(responseTarget, "command.audit-rejected");
                    return;
                }
                purgeDetachedAfterAdmission(responseTarget, actor, worldName);
            });
            return true;
        }
        purgeDetachedAfterAdmission(responseTarget, actor, worldName);
        return true;
    }

    private void purgeDetachedAfterAdmission(
        final CommandMessageSender.Target responseTarget,
        final String actor,
        final String worldName
    ) {
        lifecycleService.purgeDetached(worldName, auditEvent(actor, "world.remove.purge", worldName, ""))
            .whenComplete((result, failure) -> {
                final String key = failure == null ? switch (result.status()) {
                    case PURGED -> "command.remove.purge-success";
                    case OPERATION_IN_PROGRESS -> "command.remove.operation-in-progress";
                    case NOT_READY -> "command.loading";
                    default -> "command.remove.purge-failure";
                } : "command.remove.purge-backend-failure";
                respond(responseTarget, key, "world", worldName);
            });
    }

    private boolean manage(final CommandSender sender, final String actor, final String[] arguments) {
        if (!sender.hasPermission(MANAGE_PERMISSION)) {
            send(sender, "command.permission.manage");
            return true;
        }
        if (arguments.length != 2) {
            send(sender, "command.manage.usage");
            return true;
        }
        final String worldName = validWorldName(sender, arguments[1]);
        if (worldName == null) {
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        lifecycleService.manage(worldName, auditEvent(actor, "world.manage", worldName, ""))
            .whenComplete((result, failure) -> {
                if (failure != null) {
                    respond(responseTarget, "command.manage.backend-failure", "world", worldName);
                    return;
                }
                final String key = switch (result.status()) {
                    case MANAGED -> "command.manage.success";
                    case IDENTITY_MISMATCH -> "command.manage.identity-mismatch";
                    case OPERATION_IN_PROGRESS -> "command.manage.operation-in-progress";
                    case NOT_READY -> "command.loading";
                    case NOT_DETACHED -> "command.manage.failure";
                };
                respond(responseTarget, key, "world", worldName);
            });
        return true;
    }

    private boolean teleport(final CommandSender sender, final String actor, final String[] arguments) {
        if (!sender.hasPermission(TELEPORT_PERMISSION)) {
            send(sender, "command.permission.tp");
            return true;
        }
        final TeleportRequest request = parseTeleportRequest(sender, arguments);
        if (request == null) {
            send(sender, "command.tp.usage");
            return true;
        }
        if (request.any() && (!sender.hasPermission(TELEPORT_ANY_PERMISSION)
            || !sender.hasPermission(BYPASS_PERMISSION))) {
            send(sender, "command.permission.tp-any");
            return true;
        }
        if (request.targetPlayer().isPresent() && !sender.hasPermission(TELEPORT_OTHERS_PERMISSION)) {
            send(sender, "command.permission.tp-others");
            return true;
        }
        final UUID targetPlayer = request.targetPlayer().orElseGet(() -> sender instanceof Player player ? player.getUniqueId() : null);
        if (targetPlayer == null) {
            send(sender, "command.tp.player-only");
            return true;
        }
        final WorldMetadata world = service.lifecycleWorld(request.worldName()).orElse(null);
        if (world == null) {
            send(sender, "command.tp.not-managed", "world", request.worldName());
            return true;
        }
        final VerifiedWorldRef target = VerifiedWorldRef.from(world).orElse(null);
        if (target == null) {
            send(sender, "command.tp.denied", "world", request.worldName());
            return true;
        }
        final boolean bypass = request.any() || sender.hasPermission(BYPASS_PERMISSION);
        if (world.managementState() == io.github.bearl.worldmanagement.world.WorldManagementState.ACTIVE
            && !new WorldAccessPolicy().allowsEntry(world, targetPlayer, bypass)) {
            send(sender, "command.tp.denied", "world", request.worldName());
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        final TeleportBypassTokens.Token bypassToken = request.any()
            ? teleportBypassTokens.issue(targetPlayer, target)
            : null;
        worldTeleportGateway.teleport(targetPlayer, target, request.coordinates())
            .whenComplete((teleported, failure) -> {
                if (bypassToken != null) {
                    teleportBypassTokens.clear(bypassToken);
                }
                respond(responseTarget,
                    failure == null && teleported
                        ? auditedKey(actor, "world.tp", request.worldName(), request.targetPlayer().map(UUID::toString).orElse("self"), "command.tp.success")
                        : "command.tp.failure", "world", request.worldName());
            });
        return true;
    }

    private void removeAfterAdmission(final CommandMessageSender.Target responseTarget, final String actor, final String worldName) {
        lifecycleService.remove(worldName, auditEvent(actor, "world.remove", worldName, ""))
            .whenComplete((result, failure) -> {
                if (failure != null) {
                    respond(responseTarget, "command.remove.backend-failure", "world", worldName);
                    return;
                }
                respond(responseTarget, removeResultKey(result.status()), "world", worldName);
            });
    }

    private boolean importWorld(final CommandSender sender, final String[] arguments) {
        final String actor = actorOf(sender);
        if (!sender.hasPermission(IMPORT_PERMISSION)) {
            send(sender, "command.permission.import");
            return true;
        }
        if (arguments.length != 3 && (arguments.length != 4 || !arguments[3].equals("--detached"))) {
            send(sender, "command.import.usage");
            return true;
        }
        final boolean detached = arguments.length == 4;
        final String worldName = validWorldName(sender, arguments[1]);
        if (worldName == null) {
            return true;
        }
        final WorldRuntimeGateway.WorldEnvironment environment;
        try {
            environment = WorldRuntimeGateway.WorldEnvironment.valueOf(arguments[2].toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.import.usage");
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        lifecycleService.validateImportable(worldName).whenComplete((valid, validationFailure) -> {
            if (validationFailure != null) {
                respond(responseTarget, "command.import.validation-failure", "world", worldName);
                return;
            }
            if (!valid) {
                respond(responseTarget, "command.import.not-importable");
                return;
            }
            final AuditEvent event = auditEvent(actor, "world.import", worldName, "");
            threadDispatcher.executeGlobal(() -> lifecycleService.importWorld(
                worldName, environment, detached, event
            ).whenComplete((result, failure) -> respond(
                responseTarget,
                failure == null
                    ? createResultKey("command.import", detached, result.status())
                    : "command.import.backend-failure",
                "world", worldName
            )));
        });
        return true;
    }

    private boolean delete(final CommandSender sender, final String actor, final String[] arguments) {
        if (!sender.hasPermission(DELETE_PERMISSION)) {
            send(sender, "command.permission.delete");
            return true;
        }
        final DeleteRequest request = parseDeleteRequest(arguments);
        if (request == null) {
            send(sender, "command.delete.confirm", "world", arguments.length > 1 ? arguments[1] : "<world>");
            return true;
        }
        final String worldName = validWorldName(sender, request.worldName());
        if (worldName == null) {
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        if (auditService.requiresStrictAdmission("world.delete")) {
            auditService.admit(actor, "world.delete", worldName, "pending").thenAccept(admission -> {
                if (admission == io.github.bearl.worldmanagement.audit.AuditAdmission.REJECTED) {
                    respond(responseTarget, "command.audit-rejected");
                    return;
                }
                threadDispatcher.executeGlobal(() -> deleteAfterAdmission(responseTarget, actor, worldName, request.fallbackWorld()));
            });
            return true;
        }
        threadDispatcher.executeGlobal(() -> deleteAfterAdmission(responseTarget, actor, worldName, request.fallbackWorld()));
        return true;
    }

    private void deleteAfterAdmission(
        final CommandMessageSender.Target responseTarget,
        final String actor,
        final String worldName,
        final Optional<String> fallbackWorld
    ) {
        lifecycleService.delete(worldName, fallbackWorld, auditEvent(actor, "world.delete", worldName, ""))
            .whenComplete((result, failure) -> {
                if (failure != null) {
                    respond(responseTarget, "command.delete.failure", "world", worldName);
                    return;
                }
                final String key = switch (result.status()) {
                    case DELETED -> "command.delete.success";
                    case PENDING_RESTART -> "command.delete.pending-restart";
                    case TRACKING_REMOVAL_FAILED -> "command.delete.tracking-removal-failed";
                    case UNLOADED_REQUIRES_CONFIRMATION -> "command.delete.unloaded-requires-confirmation";
                    case RELOADED -> "command.delete.reloaded";
                    case RELOADED_AFTER_TOMBSTONE -> "command.delete.reloaded-after-tombstone";
                    case PLAYERS_PRESENT -> "command.delete.players-present";
                    case NOT_MANAGED -> "command.delete.not-managed";
                    case NOT_LOADED -> "command.delete.not-loaded";
                    case STORAGE_NOT_FOUND -> "command.delete.storage-not-found";
                    case UNLOAD_FAILED -> "command.delete.unload-failed";
                    case METADATA_REMOVAL_FAILED -> "command.delete.metadata-failed";
                    case FALLBACK_UNAVAILABLE -> "command.delete.fallback-unavailable";
                    case EXTERNAL_ONLY -> "command.delete.external-only";
                    case OPERATION_IN_PROGRESS -> "command.delete.operation-in-progress";
                };
                respond(responseTarget, key, "world", worldName);
            });
    }

    private boolean storage(final CommandSender sender, final String actor, final String[] arguments) {
        if (!sender.hasPermission("worldmanagement.command.storage") || arguments.length != 5
            || !arguments[1].equalsIgnoreCase("migrate") || !arguments[4].equalsIgnoreCase("confirm")) {
            send(sender, "command.storage.usage");
            return true;
        }
        try {
            final StorageProvider source = StorageProvider.parse(arguments[2]);
            final StorageProvider target = StorageProvider.parse(arguments[3]);
            final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
            if (auditService.requiresStrictAdmission("storage.migrate")) {
                auditService.admit(actor, "storage.migrate", "storage", "pending").thenAccept(admission -> {
                    if (admission == io.github.bearl.worldmanagement.audit.AuditAdmission.REJECTED) {
                        respond(responseTarget, "command.audit-rejected");
                        return;
                    }
                    migrateAfterAdmission(responseTarget, actor, source, target);
                });
                return true;
            }
            migrateAfterAdmission(responseTarget, actor, source, target);
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.storage.unknown-provider");
        }
        return true;
    }

    private void migrateAfterAdmission(
        final CommandMessageSender.Target responseTarget,
        final String actor,
        final StorageProvider source,
        final StorageProvider target
    ) {
        migrationService.migrate(source, target).whenComplete((result, failure) -> {
            final String key = failure == null
                ? storageResultKey(result.status())
                : "command.storage.failure";
            if (failure == null && result.status() == StorageMigrationService.MigrationStatus.MIGRATED) {
                audit(actor, "storage.migrate", "storage", source + "->" + target);
            }
            respond(responseTarget, key, "count", failure == null ? Integer.toString(result.migratedWorlds()) : "0");
        });
    }

    private boolean runLifecycle(
        final CommandSender sender,
        final String[] arguments,
        final String permission,
        final String operation,
        final WorldLifecycleCoordinator.LifecycleStatus successStatus,
        final String successVerb
    ) {
        final String actor = actorOf(sender);
        if (!sender.hasPermission(permission)) {
            messageSender.send(sender, messages.component(
                "command.permission.lifecycle",
                Map.of("operation", messages.termComponent(operationTerm(operation)))
            ));
            return true;
        }
        if (arguments.length < 2 || arguments.length > 3) {
            send(sender, "command.lifecycle.usage", "operation", operation);
            return true;
        }
        final String worldName = validWorldName(sender, arguments[1]);
        if (worldName == null) {
            return true;
        }
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        final Optional<String> fallbackWorld = arguments.length == 3 ? Optional.of(arguments[2]) : Optional.empty();
        final CompletableFuture<WorldLifecycleCoordinator.LifecycleResult> action = operation.equals("load")
            ? lifecycleService.loadAsync(worldName, auditEvent(actor, "world.load", worldName, ""))
            : lifecycleService.unloadAsync(worldName, fallbackWorld, auditEvent(actor, "world.unload", worldName, fallbackWorld.orElse("")));
        action.whenComplete((result, failure) -> {
            if (failure != null) {
                respondLifecycle(responseTarget, "command.lifecycle.failure", operation, worldName, successVerb);
                return;
            }
            respondLifecycle(responseTarget, lifecycleResultKey(result.status()), operation, worldName, successVerb);
        });
        return true;
    }

    private void respondLifecycle(
        final CommandMessageSender.Target target,
        final String key,
        final String operation,
        final String worldName,
        final String result
    ) {
        messageSender.send(target, messages.component(key, Map.of(
            "operation", messages.termComponent(operationTerm(operation)),
            "world", Component.text(worldName),
            "result", messages.termComponent(resultTerm(result))
        )));
    }

    private static String operationTerm(final String operation) {
        return switch (operation) {
            case "load" -> "operation.load";
            case "unload" -> "operation.unload";
            default -> throw new IllegalArgumentException("Unknown lifecycle operation: " + operation);
        };
    }

    private static String resultTerm(final String result) {
        return switch (result) {
            case "loaded" -> "result.loaded";
            case "unloaded" -> "result.unloaded";
            default -> throw new IllegalArgumentException("Unknown lifecycle result: " + result);
        };
    }

    private String validWorldName(final CommandSender sender, final String suppliedName) {
        try {
            return nameValidator.requireValidName(suppliedName);
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.world-name-invalid");
            return null;
        }
    }

    private boolean adopt(final CommandSender sender, final String[] arguments) {
        final String actor = actorOf(sender);
        if (!sender.hasPermission(ADOPT_PERMISSION)) {
            send(sender, "command.permission.adopt");
            return true;
        }
        if (arguments.length != 2 && (arguments.length != 3 || !arguments[2].equals("--detached"))) {
            send(sender, "command.adopt.usage");
            return true;
        }
        final boolean detached = arguments.length == 3;
        if (!service.isReady()) {
            send(sender, "command.loading");
            return true;
        }

        final String worldName;
        try {
            worldName = nameValidator.requireValidName(arguments[1]);
        } catch (final IllegalArgumentException exception) {
            send(sender, "command.world-name-invalid");
            return true;
        }
        send(sender, "command.adopt.started", "world", worldName);
        final CommandMessageSender.Target responseTarget = messageSender.capture(sender);
        lifecycleService.adoptLoadedWorld(worldName, detached, auditEvent(actor, "world.adopt", worldName, ""))
            .whenComplete((result, throwable) -> sendAdoptionResult(
                responseTarget, worldName, detached, result, throwable
            ));
        return true;
    }

    private boolean list(final CommandSender sender, final String[] arguments) {
        if (!sender.hasPermission(LIST_PERMISSION)) {
            send(sender, "command.permission.list");
            return true;
        }
        if (!service.isReady()) {
            send(sender, "command.loading");
            return true;
        }

        if (arguments.length > 2 || arguments.length == 2
            && !arguments[1].equalsIgnoreCase("detached")
            && !arguments[1].equalsIgnoreCase("all")) {
            send(sender, "command.root.usage");
            return true;
        }
        if (arguments.length == 2 && arguments[1].equalsIgnoreCase("all")) {
            final List<WorldListMessageRenderer.LoadedWorldEntry> worlds = loadedWorldEntries();
            if (worlds.isEmpty()) {
                send(sender, "command.list.all-empty");
                return true;
            }
            messageSender.send(sender, listMessages.renderLoaded(worlds));
            return true;
        }
        final boolean detached = arguments.length == 2;
        final List<WorldMetadata> worlds = detached ? service.detachedWorlds() : service.managedWorlds();
        if (worlds.isEmpty()) {
            send(sender, detached ? "command.list.detached-empty" : "command.list.empty");
            return true;
        }
        messageSender.send(sender, listMessages.render(worlds, detached));
        return true;
    }

    List<WorldListMessageRenderer.LoadedWorldEntry> loadedWorldEntries() {
        return loadedWorlds.uniqueWorlds().stream()
            .map(world -> new WorldListMessageRenderer.LoadedWorldEntry(world, loadedStatus(world)))
            .toList();
    }

    private WorldListMessageRenderer.LoadedStatus loadedStatus(
        final WorldRuntimeGateway.LifecycleWorld world
    ) {
        return service.metadataWorld(world.name())
            .filter(metadata -> VerifiedWorldRef.from(metadata)
                .filter(world.reference()::equals)
                .isPresent())
            .map(metadata -> metadata.managementState() == WorldManagementState.ACTIVE
                ? WorldListMessageRenderer.LoadedStatus.ACTIVE
                : metadata.managementState() == WorldManagementState.DETACHED
                    ? WorldListMessageRenderer.LoadedStatus.DETACHED
                    : WorldListMessageRenderer.LoadedStatus.UNKNOWN)
            .orElse(WorldListMessageRenderer.LoadedStatus.UNKNOWN);
    }

    private DeleteRequest parseDeleteRequest(final String[] arguments) {
        if (arguments.length == 3 && arguments[2].equalsIgnoreCase("confirm")) {
            return new DeleteRequest(arguments[1], Optional.empty());
        }
        if (arguments.length == 4 && arguments[2].equalsIgnoreCase("confirm")) {
            return new DeleteRequest(arguments[1], Optional.of(arguments[3]));
        }
        if (arguments.length == 4 && arguments[3].equalsIgnoreCase("confirm")) {
            return new DeleteRequest(arguments[1], Optional.of(arguments[2]));
        }
        return null;
    }

    private TeleportRequest parseTeleportRequest(final CommandSender sender, final String[] arguments) {
        if (arguments.length < 3) {
            return null;
        }
        final boolean playerTarget = arguments[1].equalsIgnoreCase("player");
        final boolean selfTarget = arguments[1].equalsIgnoreCase("self");
        final boolean any = arguments[1].equalsIgnoreCase("--any");
        if (!playerTarget && !selfTarget && !any) {
            return null;
        }
        final int worldIndex = playerTarget ? 3 : 2;
        final int coordinateIndex = worldIndex + 1;
        if (arguments.length != coordinateIndex && arguments.length != coordinateIndex + 3) {
            return null;
        }
        final String worldName = validWorldName(sender, arguments[worldIndex]);
        if (worldName == null) {
            return null;
        }
        final Optional<UUID> targetPlayer = playerTarget ? onlinePlayers.resolve(arguments[2]) : Optional.empty();
        if (playerTarget && targetPlayer.isEmpty()) {
            send(sender, "command.tp.player-not-online");
            return null;
        }
        try {
            final Optional<WorldTeleportGateway.WorldCoordinates> coordinates = arguments.length == coordinateIndex
                ? Optional.empty()
                : Optional.of(new WorldTeleportGateway.WorldCoordinates(
                    Double.parseDouble(arguments[coordinateIndex]),
                    Double.parseDouble(arguments[coordinateIndex + 1]),
                    Double.parseDouble(arguments[coordinateIndex + 2])
                ));
            return new TeleportRequest(worldName, targetPlayer, any, coordinates);
        } catch (final NumberFormatException exception) {
            return null;
        }
    }

    private record DeleteRequest(String worldName, Optional<String> fallbackWorld) {
    }

    private record TeleportRequest(
        String worldName,
        Optional<UUID> targetPlayer,
        boolean any,
        Optional<WorldTeleportGateway.WorldCoordinates> coordinates
    ) {
    }

    private void sendAdoptionResult(
        final CommandMessageSender.Target responseTarget,
        final String worldName,
        final boolean detached,
        final WorldLifecycleCoordinator.AdoptResult result,
        final Throwable throwable
    ) {
        if (throwable != null) {
            respond(responseTarget, "command.adopt.backend-failure", "world", worldName);
            return;
        }
        respond(responseTarget, adoptResultKey(result.status(), detached), "world", worldName);
    }

    static String createResultKey(
        final String keyPrefix,
        final boolean detached,
        final WorldLifecycleCoordinator.CreateStatus status
    ) {
        return switch (status) {
            case CREATED -> keyPrefix + (detached ? ".success-detached" : ".success");
            case ALREADY_EXISTS -> keyPrefix + ".already-exists";
            case FAILED -> keyPrefix + ".failure";
            case NOT_READY -> "command.loading";
            case OPERATION_IN_PROGRESS -> keyPrefix + ".operation-in-progress";
        };
    }

    static String removeResultKey(final WorldLifecycleCoordinator.RemoveStatus status) {
        return switch (status) {
            case DETACHED -> "command.remove.success";
            case TRACKING_REMOVAL_FAILED -> "command.remove.tracking-removal-failed";
            case NOT_MANAGED -> "command.remove.not-managed";
            case NOT_READY -> "command.loading";
            case OPERATION_IN_PROGRESS -> "command.remove.operation-in-progress";
            case NOT_UNLOADED -> "command.remove.not-unloaded";
            case PURGED -> "command.remove.purge-success";
        };
    }

    static String lifecycleResultKey(final WorldLifecycleCoordinator.LifecycleStatus status) {
        return switch (status) {
            case LOADED, UNLOADED -> "command.lifecycle.success";
            case ALREADY_LOADED -> "command.lifecycle.already-loaded";
            case ALREADY_UNLOADED -> "command.lifecycle.already-unloaded";
            case NOT_MANAGED -> "command.lifecycle.not-managed";
            case EXTERNAL_ONLY -> "command.lifecycle.external-only";
            case PLAYERS_PRESENT -> "command.lifecycle.players-present";
            case FALLBACK_UNAVAILABLE -> "command.lifecycle.fallback-unavailable";
            case STORAGE_NOT_FOUND -> "command.lifecycle.storage-not-found";
            case UNLOAD_FAILED -> "command.lifecycle.save-failed";
            case OPERATION_IN_PROGRESS -> "command.lifecycle.operation-in-progress";
            case NOT_READY -> "command.loading";
            case FAILED -> "command.lifecycle.failure";
        };
    }

    static String adoptResultKey(
        final WorldLifecycleCoordinator.AdoptStatus status,
        final boolean detached
    ) {
        return switch (status) {
            case ADOPTED -> detached ? "command.adopt.success-detached" : "command.adopt.success";
            case ALREADY_MANAGED -> "command.adopt.already-managed";
            case NOT_LOADED -> "command.adopt.loaded-only";
            case FAILED -> "command.adopt.failure";
            case NOT_READY -> "command.loading";
            case OPERATION_IN_PROGRESS -> "command.adopt.operation-in-progress";
        };
    }

    static String storageResultKey(final StorageMigrationService.MigrationStatus status) {
        return switch (status) {
            case MIGRATED -> "command.storage.success";
            case SOURCE_NOT_ACTIVE -> "command.storage.source-not-active";
            case SAME_PROVIDER -> "command.storage.same-provider";
            case TARGET_NOT_CONFIGURED -> "command.storage.target-not-configured";
            case TARGET_NOT_EMPTY -> "command.storage.target-not-empty";
        };
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

    private String auditedKey(
        final String actor,
        final String action,
        final String worldName,
        final String detail,
        final String key
    ) {
        audit(actor, action, worldName, detail);
        return key;
    }

    private void audit(final String actor, final String action, final String worldName, final String detail) {
        auditService.record(actor, action, worldName, detail);
    }

    private void audit(final CommandSender sender, final String action, final String worldName, final String detail) {
        audit(actorOf(sender), action, worldName, detail);
    }

    private static AuditEvent auditEvent(final String actor, final String action, final String worldName, final String detail) {
        return new AuditEvent(Instant.now(), java.util.Optional.ofNullable(actor), action, worldName, detail);
    }

    private static String actorOf(final CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
    }

}
