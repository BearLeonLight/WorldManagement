package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.storage.StorageProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.ModuleManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/** Builds the typed Brigadier tree so nested completions replace their own argument node. */
public final class BrigadierWorldManagementCommand {

    private static final List<String> ENVIRONMENTS = List.of("NORMAL", "NETHER", "THE_END");
    private static final List<String> WORLD_TYPES = List.of("NORMAL", "FLAT", "AMPLIFIED", "LARGE_BIOMES");
    private static final String LIFECYCLE_PERMISSION = "worldmanagement.command.";
    private static final String WARP_PERMISSION = "worldmanagement.command.warp";
    private static final String TRUST_PERMISSION = "worldmanagement.command.trust";
    private static final String OWNER_PERMISSION = "worldmanagement.command.owner";
    private static final String RANK_PERMISSION = "worldmanagement.command.rank";
    private static final String ACCESS_PERMISSION = "worldmanagement.command.access";
    private static final String STORAGE_PERMISSION = "worldmanagement.command.storage";
    private static final String IDENTITY_SHOW_PERMISSION = "worldmanagement.command.identity.show";
    private static final String IDENTITY_SYNC_PERMISSION = "worldmanagement.command.identity.sync";
    private static final String IDENTITY_ACCEPT_PERMISSION = "worldmanagement.command.identity.accept-replacement";
    private static final String IDENTITY_ABANDON_PERMISSION = "worldmanagement.command.identity.abandon";
    private static final String DISPLAY_NAME_SET_PERMISSION = "worldmanagement.command.display-name.set";
    private static final String DISPLAY_NAME_RESET_PERMISSION = "worldmanagement.command.display-name.reset";

    private final AtomicReference<WorldManagementCommand> delegate = new AtomicReference<>();
    private final AtomicReference<Consumer<CommandSender>> loadingResponder = new AtomicReference<>(
        sender -> sender.sendMessage(Component.text("WorldManagement 正在載入資料。"))
    );
    private final CommandAccessPolicy accessPolicy = new CommandAccessPolicy();
    private final SuggestionCatalog suggestions;

    public BrigadierWorldManagementCommand() {
        this(new SuggestionCatalog(new OnlinePlayerSnapshot()));
    }

    public BrigadierWorldManagementCommand(final SuggestionCatalog suggestions) {
        this.suggestions = suggestions;
    }

    public void initialize(final WorldManagementCommand commandHandler, final ModuleManager manager) {
        delegate.set(commandHandler);
        accessPolicy.initialize(manager);
    }

    public void initializeLoadingResponder(final Consumer<CommandSender> responder) {
        loadingResponder.set(java.util.Objects.requireNonNull(responder, "responder"));
    }

    public void initialize(
        final WorldManagementCommand commandHandler,
        final ModuleManager manager,
        final WorldManagementService worldManagementService
    ) {
        initialize(commandHandler, manager);
        suggestions.initialize(worldManagementService);
    }

    public com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> buildRoot(final String label) {
        return Commands.literal(label)
            .then(lifecycleLiteral("create").then(createTree()))
            .then(lifecycleLiteral("adopt").then(words(new CommandRoute(List.of("adopt")), List.of("world"))))
            .then(lifecycleLiteral("load").then(words(new CommandRoute(List.of("load")), List.of("world"), managedWorlds())))
            .then(lifecycleLiteral("unload").then(optionalFallbackTree("unload")))
            .then(lifecycleLiteral("remove").then(removeTree()))
            .then(lifecycleLiteral("manage").then(words(new CommandRoute(List.of("manage")), List.of("world"), detachedWorlds())))
            .then(lifecycleLiteral("import").then(words(
                new CommandRoute(List.of("import")),
                List.of("world", "environment"),
                noSuggestions(), staticSuggestions(ENVIRONMENTS)
            )))
            .then(lifecycleLiteral("delete").then(deleteTree()))
            .then(identityTree())
            .then(displayNameTree())
            .then(tpLiteral())
            .then(warpTree("warp", new CommandRoute(List.of("warp"))))
            .then(ownershipTree("ownership", new CommandRoute(List.of("ownership"))))
            .then(storageLiteral("storage").then(storageTree(new CommandRoute(List.of("storage")))))
            .then(Commands.literal("list").requires(accessPolicy.command(ModuleId.LIFECYCLE, LIFECYCLE_PERMISSION + "list"))
                .executes(context -> execute(context, new CommandRoute(List.of("list")), List.of()))
                .then(Commands.literal("detached").executes(context -> execute(
                    context, new CommandRoute(List.of("list"), List.of("detached")), List.of()
                ))))
            .build();
    }

    public com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build() {
        return buildRoot("wm");
    }

    public com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> buildModuleRoot(final ModuleId module, final String label) {
        return switch (module) {
            case WARP -> warpTree(label, new CommandRoute(List.of("warp"))).build();
            case OWNERSHIP -> ownershipTree(label, new CommandRoute(List.of("ownership"))).build();
            case STORAGE -> storageLiteral(label).then(storageTree(new CommandRoute(List.of("storage")))).build();
            default -> throw new IllegalArgumentException("Module does not have a command tree: " + module);
        };
    }

    private LiteralArgumentBuilder<CommandSourceStack> lifecycleLiteral(final String literal) {
        return Commands.literal(literal).requires(accessPolicy.command(ModuleId.LIFECYCLE, LIFECYCLE_PERMISSION + literal));
    }

    private LiteralArgumentBuilder<CommandSourceStack> warpLiteral(final String literal) {
        return Commands.literal(literal).requires(accessPolicy.anyCommand(ModuleId.WARP, WARP_PERMISSION, TRUST_PERMISSION));
    }

    private LiteralArgumentBuilder<CommandSourceStack> ownershipLiteral(final String literal) {
        return Commands.literal(literal).requires(accessPolicy.anyCommand(ModuleId.OWNERSHIP, OWNER_PERMISSION, RANK_PERMISSION, ACCESS_PERMISSION));
    }

    private LiteralArgumentBuilder<CommandSourceStack> storageLiteral(final String literal) {
        return Commands.literal(literal).requires(accessPolicy.command(ModuleId.STORAGE, STORAGE_PERMISSION));
    }

    private LiteralArgumentBuilder<CommandSourceStack> identityTree() {
        return Commands.literal("identity")
            .requires(accessPolicy.anyCommand(
                ModuleId.LIFECYCLE,
                IDENTITY_SHOW_PERMISSION,
                IDENTITY_SYNC_PERMISSION,
                IDENTITY_ACCEPT_PERMISSION,
                IDENTITY_ABANDON_PERMISSION
            ))
            .then(Commands.literal("show")
                .requires(accessPolicy.command(ModuleId.LIFECYCLE, IDENTITY_SHOW_PERMISSION))
                .then(Commands.argument("world", StringArgumentType.word())
                    .suggests(suggestions.provider(
                        SuggestionCatalog.IDENTITY_WORLD,
                        (context, catalog) -> catalog.identityWorlds()
                    ))
                    .executes(context -> execute(
                        context,
                        new CommandRoute(List.of("identity", "show")),
                        List.of("world")
                    ))))
            .then(Commands.literal("sync")
                .requires(accessPolicy.command(ModuleId.LIFECYCLE, IDENTITY_SYNC_PERMISSION))
                .then(Commands.argument("world", StringArgumentType.word())
                    .suggests(suggestions.provider(
                        SuggestionCatalog.SYNC_PENDING_WORLD,
                        (context, catalog) -> catalog.identityWorlds(IdentityVerificationState.SYNC_PENDING)
                    ))
                    .executes(context -> execute(
                        context,
                        new CommandRoute(List.of("identity", "sync")),
                        List.of("world")
                    ))))
            .then(Commands.literal("accept-replacement")
                .requires(accessPolicy.command(ModuleId.LIFECYCLE, IDENTITY_ACCEPT_PERMISSION))
                .then(Commands.argument("world", StringArgumentType.word())
                    .suggests(suggestions.provider(
                        SuggestionCatalog.CONFLICT_WORLD,
                        (context, catalog) -> catalog.identityWorlds(IdentityVerificationState.CONFLICT)
                    ))
                    .then(identityAcceptConfirmation())))
            .then(Commands.literal("abandon")
                .requires(accessPolicy.command(ModuleId.LIFECYCLE, IDENTITY_ABANDON_PERMISSION))
                .then(Commands.argument("world", StringArgumentType.word())
                    .suggests(suggestions.provider(
                        SuggestionCatalog.NON_VERIFIED_WORLD,
                        (context, catalog) -> catalog.nonVerifiedWorlds()
                    ))
                    .then(Commands.literal("confirm").executes(context -> execute(
                        context,
                        new CommandRoute(List.of("identity", "abandon"), List.of("confirm")),
                        List.of("world")
                    )))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> identityAcceptConfirmation() {
        return Commands.literal("confirm")
            .then(Commands.literal("clear-warps").executes(context -> execute(
                context,
                new CommandRoute(
                    List.of("identity", "accept-replacement"), List.of("confirm", "clear-warps")
                ),
                List.of("world")
            )))
            .then(Commands.literal("keep-warps").executes(context -> execute(
                context,
                new CommandRoute(
                    List.of("identity", "accept-replacement"), List.of("confirm", "keep-warps")
                ),
                List.of("world")
            )));
    }

    private LiteralArgumentBuilder<CommandSourceStack> displayNameTree() {
        return Commands.literal("display-name")
            .requires(accessPolicy.anyCommand(
                ModuleId.LIFECYCLE,
                DISPLAY_NAME_SET_PERMISSION,
                DISPLAY_NAME_RESET_PERMISSION
            ))
            .then(Commands.literal("set")
                .requires(accessPolicy.command(ModuleId.LIFECYCLE, DISPLAY_NAME_SET_PERMISSION))
                .then(Commands.argument("world", StringArgumentType.word())
                    .suggests(suggestions.provider(
                        SuggestionCatalog.IDENTITY_WORLD,
                        (context, catalog) -> catalog.identityWorlds()
                    ))
                    .then(Commands.argument("display-name", StringArgumentType.greedyString())
                        .executes(context -> execute(
                            context,
                            new CommandRoute(List.of("display-name", "set")),
                            List.of("world", "display-name")
                        )))))
            .then(Commands.literal("reset")
                .requires(accessPolicy.command(ModuleId.LIFECYCLE, DISPLAY_NAME_RESET_PERMISSION))
                .then(Commands.argument("world", StringArgumentType.word())
                    .suggests(suggestions.provider(
                        SuggestionCatalog.IDENTITY_WORLD,
                        (context, catalog) -> catalog.identityWorlds()
                    ))
                    .executes(context -> execute(
                        context,
                        new CommandRoute(List.of("display-name", "reset")),
                        List.of("world")
                    ))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> words(final CommandRoute route, final List<String> names) {
        return words(route, names, List.of());
    }

    @SafeVarargs
    private final RequiredArgumentBuilder<CommandSourceStack, String> words(
        final CommandRoute route,
        final List<String> names,
        final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>... providers
    ) {
        return words(route, names, List.of(providers));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> words(
        final CommandRoute route,
        final List<String> names,
        final List<com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>> providers
    ) {
        if (names.isEmpty()) {
            throw new IllegalArgumentException("A word chain requires at least one argument.");
        }
        return word(route, names, providers, 0);
    }

    private ArgumentBuilder<CommandSourceStack, ?> deleteTree() {
        return Commands.argument("world", StringArgumentType.word())
            .suggests(suggestions.provider(SuggestionCatalog.MANAGED_WORLD, (context, catalog) -> catalog.managedWorlds()))
            .then(Commands.literal("confirm").executes(context -> execute(
                context, new CommandRoute(List.of("delete"), List.of("confirm")), List.of("world")
            )))
            .then(Commands.argument("fallback", StringArgumentType.word()).then(Commands.literal("confirm").executes(context -> execute(
                context, new CommandRoute(List.of("delete"), List.of("confirm")), List.of("world", "fallback")
            ))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> removeTree() {
        return Commands.argument("world", StringArgumentType.word())
            .suggests(suggestions.provider(SuggestionCatalog.MANAGED_WORLD, (context, catalog) -> catalog.managedWorlds()))
            .executes(context -> execute(context, new CommandRoute(List.of("remove")), List.of("world")))
            .then(Commands.literal("purge").then(Commands.literal("confirm").executes(context -> execute(
                context, new CommandRoute(List.of("remove"), List.of("purge", "confirm")), List.of("world")
            ))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> optionalFallbackTree(final String operation) {
        return Commands.argument("world", StringArgumentType.word())
            .suggests(suggestions.provider(SuggestionCatalog.MANAGED_WORLD, (context, catalog) -> catalog.managedWorlds()))
            .executes(context -> execute(context, new CommandRoute(List.of(operation)), List.of("world")))
            .then(Commands.argument("fallback", StringArgumentType.word()).executes(context -> execute(
                context, new CommandRoute(List.of(operation)), List.of("world", "fallback")
            )));
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> detachedWorlds() {
        return suggestions.provider(SuggestionCatalog.DETACHED_WORLD, (context, catalog) -> catalog.detachedWorlds());
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpLiteral() {
        final RequiredArgumentBuilder<CommandSourceStack, String> player = Commands.argument("player", StringArgumentType.word())
            .suggests(onlinePlayers())
            .then(worldTpTree(new CommandRoute(List.of("tp", "player")), List.of("player", "world")));
        return lifecycleLiteral("tp")
            .then(Commands.literal("self").then(worldTpTree(new CommandRoute(List.of("tp", "self")), List.of("world"))))
            .then(Commands.literal("player").requires(accessPolicy.command(ModuleId.LIFECYCLE, "worldmanagement.command.tp.others"))
                .then(player))
            .then(Commands.literal("--any").requires(accessPolicy.allCommands(
                ModuleId.LIFECYCLE,
                "worldmanagement.command.tp.any.explicit",
                "worldmanagement.bypass.protection"
            ))
                .then(worldTpTree(new CommandRoute(List.of("tp", "--any")), List.of("world"))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> worldTpTree(final CommandRoute route, final List<String> names) {
        final RequiredArgumentBuilder<CommandSourceStack, String> world = Commands.argument("world", StringArgumentType.word())
            .suggests(suggestions.provider(SuggestionCatalog.MANAGED_WORLD, (context, catalog) -> catalog.managedWorlds()))
            .executes(context -> execute(context, route, names));
        final RequiredArgumentBuilder<CommandSourceStack, String> x = Commands.argument("x", StringArgumentType.word());
        final RequiredArgumentBuilder<CommandSourceStack, String> y = Commands.argument("y", StringArgumentType.word());
        final RequiredArgumentBuilder<CommandSourceStack, String> z = Commands.argument("z", StringArgumentType.word())
            .executes(context -> execute(context, route, coordinateNames(names)));
        y.then(z);
        x.then(y);
        world.then(x);
        return world;
    }

    private List<String> coordinateNames(final List<String> names) {
        final List<String> coordinates = new ArrayList<>(names);
        coordinates.addAll(List.of("x", "y", "z"));
        return coordinates;
    }

    private LiteralArgumentBuilder<CommandSourceStack> warpTree(final String label, final CommandRoute route) {
        return warpLiteral(label)
            .then(Commands.literal("list").requires(accessPolicy.command(ModuleId.WARP, WARP_PERMISSION))
                .then(words(routeWith(route, "list"), List.of("world"), managedWorlds())))
            .then(Commands.literal("set").requires(accessPolicy.command(ModuleId.WARP, WARP_PERMISSION))
                .then(words(routeWith(route, "set"), List.of("world", "name", "visibility"), manageableWorlds(), noSuggestions(), staticSuggestions(List.of("PUBLIC", "PRIVATE")))))
            .then(Commands.literal("delete").requires(accessPolicy.command(ModuleId.WARP, WARP_PERMISSION))
                .then(words(routeWith(route, "delete"), List.of("world", "name"), manageableWorlds(), worldWarps())))
            .then(Commands.literal("tp").requires(accessPolicy.command(ModuleId.WARP, WARP_PERMISSION))
                .then(words(routeWith(route, "tp"), List.of("world", "name"), managedWorlds(), visibleWarps())))
            .then(Commands.literal("trust").requires(accessPolicy.command(ModuleId.WARP, TRUST_PERMISSION))
                .then(words(
                    routeWith(route, "trust"),
                    List.of("world", "warp", "operation", "player"),
                    manageableWorlds(), worldWarps(), staticSuggestions(List.of("add", "remove")), onlinePlayers()
                )));
    }

    private LiteralArgumentBuilder<CommandSourceStack> ownershipTree(final String label, final CommandRoute route) {
        return ownershipLiteral(label)
            .then(Commands.literal("owner").requires(accessPolicy.command(ModuleId.OWNERSHIP, OWNER_PERMISSION))
                .then(Commands.literal("set").then(words(routeWith(route, "owner", "set"), List.of("world", "player"), managedWorlds(), onlinePlayers())))
                .then(Commands.literal("remove").then(words(routeWith(route, "owner", "remove"), List.of("world"), managedWorlds()))))
            .then(rankTree(route))
            .then(accessTree(route));
    }

    private LiteralArgumentBuilder<CommandSourceStack> storageTree(final CommandRoute route) {
        return Commands.literal("migrate").requires(accessPolicy.command(ModuleId.STORAGE, STORAGE_PERMISSION))
            .then(Commands.argument("source", StringArgumentType.word())
                .suggests(storageProviders())
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests(storageProviders())
                    .then(Commands.literal("confirm").executes(context -> execute(
                        context,
                        new CommandRoute(List.of("storage", "migrate"), List.of("confirm")),
                        List.of("source", "target")
                    )))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> rankTree(final CommandRoute route) {
        return Commands.literal("rank").requires(accessPolicy.command(ModuleId.OWNERSHIP, RANK_PERMISSION))
            .then(Commands.literal("create").then(words(routeWith(route, "rank", "create"), List.of("world", "rank"), manageableWorlds(), noSuggestions())))
            .then(Commands.literal("delete").then(words(routeWith(route, "rank", "delete"), List.of("world", "rank"), manageableWorlds(), rankIds())))
            .then(Commands.literal("set").then(words(routeWith(route, "rank", "set"), List.of("world", "player", "rank"), manageableWorlds(), onlinePlayers(), rankIds())))
            .then(Commands.literal("remove").then(words(routeWith(route, "rank", "remove"), List.of("world", "player"), manageableWorlds(), onlinePlayers())))
            .then(Commands.literal("perm").then(words(routeWith(route, "rank", "perm"), List.of("world", "rank", "operation", "permission"), manageableWorlds(), rankIds(), staticSuggestions(List.of("add", "remove")), noSuggestions())))
            .then(Commands.literal("toggle").then(words(routeWith(route, "rank", "toggle"), List.of("world"), manageableWorlds())));
    }

    private LiteralArgumentBuilder<CommandSourceStack> accessTree(final CommandRoute route) {
        return Commands.literal("access").requires(accessPolicy.command(ModuleId.OWNERSHIP, ACCESS_PERMISSION))
            .then(words(
                routeWith(route, "access"),
                List.of("world", "operation", "value"),
                manageableWorlds(), staticSuggestions(List.of("mode", "add", "remove")), accessValue()
            ));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> word(
        final CommandRoute route,
        final List<String> names,
        final List<com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>> providers,
        final int index
    ) {
        final RequiredArgumentBuilder<CommandSourceStack, String> argument = Commands.argument(names.get(index), StringArgumentType.word());
        if (index < providers.size() && providers.get(index) != null) {
            argument.suggests(providers.get(index));
        }
        if (index + 1 == names.size()) {
            argument.executes(context -> execute(context, route, names));
        } else {
            argument.then(word(route, names, providers, index + 1));
        }
        return argument;
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> managedWorlds() {
        return suggestions.provider(SuggestionCatalog.MANAGED_WORLD, (context, catalog) -> catalog.managedWorlds());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> manageableWorlds() {
        return suggestions.provider(SuggestionCatalog.MANAGEABLE_WORLD, (context, catalog) -> catalog.manageableWorlds());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> worldWarps() {
        return suggestions.provider(SuggestionCatalog.WORLD_WARP, (context, catalog) -> catalog.warpNames(context.getArgument("world", String.class)));
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> visibleWarps() {
        return suggestions.provider(SuggestionCatalog.VISIBLE_WARP, (context, catalog) ->
            catalog.visibleWarpNames(context.getArgument("world", String.class))
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return suggestions.provider(SuggestionCatalog.ONLINE_PLAYER, (context, catalog) -> catalog.onlinePlayers());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> rankIds() {
        return suggestions.provider(SuggestionCatalog.RANK_ID, (context, catalog) -> catalog.rankIds(context.getArgument("world", String.class)));
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> storageProviders() {
        return suggestions.provider(SuggestionCatalog.STORAGE_PROVIDER, (context, catalog) ->
            java.util.Arrays.stream(StorageProvider.values()).map(Enum::name).toList()
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> accessValue() {
        return suggestions.provider(SuggestionCatalog.ONLINE_PLAYER, (context, catalog) -> switch (context.getArgument("operation", String.class).toLowerCase(java.util.Locale.ROOT)) {
            case "mode" -> List.of("NONE", "WHITELIST", "BLACKLIST");
            case "add", "remove" -> catalog.onlinePlayers();
            default -> List.of();
        });
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> noSuggestions() {
        return (context, builder) -> builder.buildFuture();
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> staticSuggestions(final Collection<String> candidates) {
        return (context, builder) -> suggest(candidates, builder);
    }

    private CommandRoute routeWith(final CommandRoute base, final String... suffix) {
        final List<String> prefix = new ArrayList<>(base.prefix());
        prefix.addAll(List.of(suffix));
        return new CommandRoute(prefix, base.suffix());
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> createTree() {
        final RequiredArgumentBuilder<CommandSourceStack, String> world = Commands.argument("world", StringArgumentType.word())
            .executes(context -> execute(context, new CommandRoute(List.of("create")), List.of("world")));
        final RequiredArgumentBuilder<CommandSourceStack, String> environment = Commands.argument("environment", StringArgumentType.word())
            .suggests((context, builder) -> suggest(ENVIRONMENTS, builder))
            .executes(context -> execute(context, new CommandRoute(List.of("create")), List.of("world", "environment")));
        final RequiredArgumentBuilder<CommandSourceStack, String> worldType = Commands.argument("world-type", StringArgumentType.word())
            .suggests((context, builder) -> suggest(WORLD_TYPES, builder))
            .executes(context -> execute(context, new CommandRoute(List.of("create")), List.of("world", "environment", "world-type")));
        worldType.then(Commands.argument("seed", StringArgumentType.word()).executes(context -> execute(
            context,
            new CommandRoute(List.of("create")),
            List.of("world", "environment", "world-type", "seed")
        )));
        environment.then(worldType);
        world.then(environment);
        return world;
    }

    private int execute(
        final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        final CommandRoute route,
        final List<String> argumentNames
    ) {
        final WorldManagementCommand commandHandler = delegate.get();
        if (commandHandler == null) {
            loadingResponder.get().accept(context.getSource().getSender());
            return 0;
        }
        final List<String> parsedArguments = argumentNames.stream().map(name -> context.getArgument(name, String.class)).toList();
        commandHandler.execute(context.getSource().getSender(), route.arguments(parsedArguments));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
        final Collection<String> candidates,
        final com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        final String remaining = builder.getRemainingLowerCase();
        candidates.stream().filter(candidate -> candidate.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)).forEach(builder::suggest);
        return builder.buildFuture();
    }
}