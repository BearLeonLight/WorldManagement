package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.storage.StorageProvider;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class WorldManagementCommandSpec {

    private static final List<String> ENVIRONMENTS = List.of("NORMAL", "NETHER", "THE_END");
    private static final List<String> WORLD_TYPES = List.of("NORMAL", "FLAT", "AMPLIFIED", "LARGE_BIOMES");
    private static final String LIFECYCLE_PERMISSION = "worldmanagement.command.";
    private static final String WARP_PERMISSION = "worldmanagement.command.warp";
    private static final String TRUST_PERMISSION = "worldmanagement.command.trust";
    private static final String OWNER_PERMISSION = "worldmanagement.command.owner";
    private static final String RANK_PERMISSION = "worldmanagement.command.rank";
    private static final String ACCESS_PERMISSION = "worldmanagement.command.access";
    private static final String STORAGE_PERMISSION = "worldmanagement.command.storage";

    private final SuggestionCatalog suggestions;
    private final CommandAccessPolicy accessPolicy;
    private final CommandNodeSpec root;

    WorldManagementCommandSpec(final SuggestionCatalog suggestions) {
        this(suggestions, new CommandAccessPolicy());
    }

    WorldManagementCommandSpec(final SuggestionCatalog suggestions, final CommandAccessPolicy accessPolicy) {
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.root = CommandNodeSpec.root(List.of(
            helpTree(),
            lifecycle("create", createTree()),
            lifecycle("adopt", List.of(argument("adopt.world", "world", CommandArgumentKind.WORD, List.of(
                literal("adopt.detached", "--detached", inherit(), List.of())
                    .executes(new CommandRoute(List.of("adopt"), List.of("--detached")), List.of("world"))
            )).executes(route("adopt"), List.of("world")))),
            lifecycle("load", List.of(loadTree())),
            lifecycle("unload", List.of(optionalFallbackTree("unload"))),
            lifecycle("remove", List.of(removeTree())),
            lifecycle("manage", List.of(words("manage", route("manage"), List.of("world"), List.of(detachedWorlds())))),
            lifecycle("import", List.of(argument("import.world", "world", CommandArgumentKind.WORD, List.of(
                argument("import.environment", "environment", CommandArgumentKind.WORD, List.of(
                    literal("import.detached", "--detached", inherit(), List.of())
                        .executes(new CommandRoute(List.of("import"), List.of("--detached")), List.of("world", "environment"))
                )).suggests(staticSuggestions(ENVIRONMENTS))
                    .executes(route("import"), List.of("world", "environment"))
            )))),
            lifecycle("delete", List.of(deleteTree())),
            identityTree(),
            displayNameTree(),
            tpTree(),
            warpTree("warp", route("warp")),
            ownershipTree("ownership", route("ownership")),
            storageTree("storage", route("storage")),
            literal("list", "list", access(ModuleId.LIFECYCLE, LIFECYCLE_PERMISSION + "list"), List.of(
                literal("list.detached", "detached", inherit(), List.of())
                    .executes(new CommandRoute(List.of("list"), List.of("detached")), List.of()),
                literal("list.all", "all", inherit(), List.of())
                    .executes(new CommandRoute(List.of("list"), List.of("all")), List.of())
            )).executes(route("list"), List.of())
        ));
    }

    CommandNodeSpec root() {
        return root;
    }

    CommandNodeSpec module(final ModuleId module) {
        final String value = switch (module) {
            case WARP -> "warp";
            case OWNERSHIP -> "ownership";
            case STORAGE -> "storage";
            default -> throw new IllegalArgumentException("Module does not have a command tree: " + module);
        };
        return root.children().stream()
            .filter(node -> node.segment() instanceof CommandNodeSpec.LiteralSegment segment && segment.value().equals(value))
            .findFirst()
            .orElseThrow();
    }

    private CommandNodeSpec lifecycle(final String value, final List<CommandNodeSpec> children) {
        return literal(value, value, access(ModuleId.LIFECYCLE, LIFECYCLE_PERMISSION + value), children);
    }

    private CommandNodeSpec helpTree() {
        return literal("help", "help", CommandAccess.help(), List.of(
            argument("help.query", "query", CommandArgumentKind.GREEDY_STRING, List.of())
                .suggests((context, builder) -> new CommandHelpRenderer(root, accessPolicy, 6).suggest(
                    context.getSource().getSender(),
                    builder.getRemaining(),
                    builder,
                    context.getSource().getSender().hasPermission("worldmanagement.command.help.all")
                ))
                .executes(route("help"), List.of("query"))
        )).executes(route("help"), List.of());
    }

    private List<CommandNodeSpec> createTree() {
        final CommandNodeSpec options = CommandNodeSpec.typedArgument(
            "create.options",
            "options",
            new CreateCommandOptionsArgument(
                suggestions::generatorPlugins,
                suggestions::biomeProviderPlugins
            ),
            "[--seed <seed>] [--generator <plugin[:id]>] [--generator-settings <json>] "
                + "[--no-structures] [--generate-bonus-chest] [--biome <plugin[:id]>] "
                + "[--force-spawn-position <x,y,z[,yaw,pitch]>] [--detached]",
            List.of()
        ).executesTyped(route("create"), List.of(
            new CommandArgumentBinding("world", String.class),
            new CommandArgumentBinding("environment", String.class),
            new CommandArgumentBinding("world-type", String.class),
            new CommandArgumentBinding("options", CreateCommandOptions.class)
        ));
        final CommandNodeSpec worldType = argument("create.world-type", "world-type", CommandArgumentKind.WORD, List.of(options))
            .suggests(staticSuggestions(WORLD_TYPES))
            .executes(route("create"), List.of("world", "environment", "world-type"));
        final CommandNodeSpec environment = argument("create.environment", "environment", CommandArgumentKind.WORD, List.of(worldType))
            .suggests(staticSuggestions(ENVIRONMENTS));
        return List.of(argument("create.world", "world", CommandArgumentKind.WORD, List.of(environment)));
    }

    private CommandNodeSpec deleteTree() {
        return argument("delete.world", "world", CommandArgumentKind.WORD, List.of(
            literal("delete.confirm", "confirm", inherit(), List.of())
                .executes(new CommandRoute(List.of("delete"), List.of("confirm")), List.of("world")),
            argument("delete.fallback", "fallback", CommandArgumentKind.WORD, List.of(
                literal("delete.fallback.confirm", "confirm", inherit(), List.of())
                    .executes(new CommandRoute(List.of("delete"), List.of("confirm")), List.of("world", "fallback"))
            )).suggests(fallbackTargets())
        )).suggests(lifecycleTargets());
    }

    private CommandNodeSpec loadTree() {
        return argument("load.world", "world", CommandArgumentKind.WORD, List.of(
            argument("load.environment", "environment", CommandArgumentKind.WORD, List.of(
                literal("load.detached", "--detached", inherit(), List.of())
                    .executes(new CommandRoute(List.of("load"), List.of("--detached")),
                        List.of("world", "environment"))
            )).suggests(staticSuggestions(ENVIRONMENTS))
        )).suggests(lifecycleWorlds()).executes(route("load"), List.of("world"));
    }

    private CommandNodeSpec removeTree() {
        return argument("remove.world", "world", CommandArgumentKind.WORD, List.of(
            literal("remove.purge", "purge", inherit(), List.of(
                literal("remove.purge.confirm", "confirm", inherit(), List.of())
                    .executes(new CommandRoute(List.of("remove"), List.of("purge", "confirm")), List.of("world"))
            ))
        )).suggests(lifecycleWorlds()).executes(route("remove"), List.of("world"));
    }

    private CommandNodeSpec optionalFallbackTree(final String operation) {
        return argument(operation + ".world", "world", CommandArgumentKind.WORD, List.of(
            argument(operation + ".fallback", "fallback", CommandArgumentKind.WORD, List.of())
                .suggests(fallbackTargets())
                .executes(route(operation), List.of("world", "fallback"))
        )).suggests(lifecycleTargets()).executes(route(operation), List.of("world"));
    }

    private CommandNodeSpec identityTree() {
        return literal("identity", "identity", anyAccess(
            ModuleId.LIFECYCLE,
            "worldmanagement.command.identity.show",
            "worldmanagement.command.identity.sync",
            "worldmanagement.command.identity.accept-replacement",
            "worldmanagement.command.identity.abandon"
        ), List.of(
            literal("identity.show", "show", access(ModuleId.LIFECYCLE, "worldmanagement.command.identity.show"), List.of(
                argument("identity.show.world", "world", CommandArgumentKind.WORD, List.of())
                    .suggests(identityWorlds())
                    .executes(route("identity", "show"), List.of("world"))
            )),
            literal("identity.sync", "sync", access(ModuleId.LIFECYCLE, "worldmanagement.command.identity.sync"), List.of(
                argument("identity.sync.world", "world", CommandArgumentKind.WORD, List.of())
                    .suggests(identityWorlds(IdentityVerificationState.SYNC_PENDING))
                    .executes(route("identity", "sync"), List.of("world"))
            )),
            literal(
                "identity.accept-replacement",
                "accept-replacement",
                access(ModuleId.LIFECYCLE, "worldmanagement.command.identity.accept-replacement"),
                List.of(argument("identity.accept-replacement.world", "world", CommandArgumentKind.WORD, List.of(
                    literal("identity.accept-replacement.confirm", "confirm", inherit(), List.of(
                        literal("identity.accept-replacement.clear-warps", "clear-warps", inherit(), List.of())
                            .executes(new CommandRoute(
                                List.of("identity", "accept-replacement"), List.of("confirm", "clear-warps")
                            ), List.of("world")),
                        literal("identity.accept-replacement.keep-warps", "keep-warps", inherit(), List.of())
                            .executes(new CommandRoute(
                                List.of("identity", "accept-replacement"), List.of("confirm", "keep-warps")
                            ), List.of("world"))
                    ))
                )).suggests(identityWorlds(IdentityVerificationState.CONFLICT)))
            ),
            literal("identity.abandon", "abandon", access(ModuleId.LIFECYCLE, "worldmanagement.command.identity.abandon"), List.of(
                argument("identity.abandon.world", "world", CommandArgumentKind.WORD, List.of(
                    literal("identity.abandon.confirm", "confirm", inherit(), List.of())
                        .executes(new CommandRoute(List.of("identity", "abandon"), List.of("confirm")), List.of("world"))
                )).suggests(nonVerifiedWorlds())
            ))
        ));
    }

    private CommandNodeSpec displayNameTree() {
        return literal("display-name", "display-name", anyAccess(
            ModuleId.LIFECYCLE,
            "worldmanagement.command.display-name.set",
            "worldmanagement.command.display-name.reset"
        ), List.of(
            literal("display-name.set", "set", access(ModuleId.LIFECYCLE, "worldmanagement.command.display-name.set"), List.of(
                argument("display-name.set.world", "world", CommandArgumentKind.WORD, List.of(
                    argument("display-name.set.value", "display-name", CommandArgumentKind.GREEDY_STRING, List.of())
                        .executes(route("display-name", "set"), List.of("world", "display-name"))
                )).suggests(displayNameWorlds())
            )),
            literal("display-name.reset", "reset", access(ModuleId.LIFECYCLE, "worldmanagement.command.display-name.reset"), List.of(
                argument("display-name.reset.world", "world", CommandArgumentKind.WORD, List.of())
                    .suggests(displayNameWorlds())
                    .executes(route("display-name", "reset"), List.of("world"))
            ))
        ));
    }

    private CommandNodeSpec tpTree() {
        return literal("tp", "tp", access(ModuleId.LIFECYCLE, "worldmanagement.command.tp"), List.of(
            literal("tp.self", "self", inherit(), List.of(worldTpTree(
                "tp.self.world", route("tp", "self"), List.of("world")
            ))),
            literal("tp.player", "player", access(ModuleId.LIFECYCLE, "worldmanagement.command.tp.others"), List.of(
                argument("tp.player.player", "player", CommandArgumentKind.WORD, List.of(
                    worldTpTree("tp.player.world", route("tp", "player"), List.of("player", "world"))
                )).suggests(onlinePlayers())
            )),
            literal("tp.any", "--any", allAccess(
                ModuleId.LIFECYCLE,
                "worldmanagement.command.tp.any.explicit",
                "worldmanagement.bypass.protection"
            ), List.of(worldTpTree("tp.any.world", route("tp", "--any"), List.of("world"))))
        ));
    }

    private CommandNodeSpec worldTpTree(
        final String id,
        final CommandRoute route,
        final List<String> argumentNames
    ) {
        final List<String> coordinates = new ArrayList<>(argumentNames);
        coordinates.addAll(List.of("x", "y", "z"));
        final CommandNodeSpec z = argument(id + ".z", "z", CommandArgumentKind.WORD, List.of()).executes(route, coordinates);
        final CommandNodeSpec y = argument(id + ".y", "y", CommandArgumentKind.WORD, List.of(z));
        final CommandNodeSpec x = argument(id + ".x", "x", CommandArgumentKind.WORD, List.of(y));
        return argument(id, "world", CommandArgumentKind.WORD, List.of(x))
            .suggests(lifecycleWorlds())
            .executes(route, argumentNames);
    }

    private CommandNodeSpec warpTree(final String id, final CommandRoute route) {
        return literal(id, "warp", anyAccess(ModuleId.WARP, WARP_PERMISSION, TRUST_PERMISSION), List.of(
            literal(id + ".list", "list", access(ModuleId.WARP, WARP_PERMISSION), List.of(
                words(id + ".list", routeWith(route, "list"), List.of("world"), List.of(managedWorlds()))
            )),
            literal(id + ".set", "set", access(ModuleId.WARP, WARP_PERMISSION), List.of(
                words(id + ".set", routeWith(route, "set"), List.of("world", "name", "visibility"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.WARP), noSuggestions(),
                        staticSuggestions(List.of("PUBLIC", "PRIVATE"))))
            )),
            literal(id + ".delete", "delete", access(ModuleId.WARP, WARP_PERMISSION), List.of(
                words(id + ".delete", routeWith(route, "delete"), List.of("world", "name"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.WARP), worldWarps()))
            )),
            literal(id + ".tp", "tp", access(ModuleId.WARP, WARP_PERMISSION), List.of(
                words(id + ".tp", routeWith(route, "tp"), List.of("world", "name"), List.of(managedWorlds(), visibleWarps()))
            )),
            literal(id + ".trust", "trust", access(ModuleId.WARP, TRUST_PERMISSION), List.of(
                words(id + ".trust", routeWith(route, "trust"), List.of("world", "warp", "operation", "player"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.WARP), worldWarps(),
                        staticSuggestions(List.of("add", "remove")), onlinePlayers()))
            ))
        ));
    }

    private CommandNodeSpec ownershipTree(final String id, final CommandRoute route) {
        return literal(id, "ownership", anyAccess(ModuleId.OWNERSHIP, OWNER_PERMISSION, RANK_PERMISSION, ACCESS_PERMISSION), List.of(
            literal(id + ".owner", "owner", access(ModuleId.OWNERSHIP, OWNER_PERMISSION), List.of(
                literal(id + ".owner.set", "set", inherit(), List.of(
                    words(id + ".owner.set", routeWith(route, "owner", "set"), List.of("world", "player"),
                        List.of(administrativeWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP), onlinePlayers()))
                )),
                literal(id + ".owner.remove", "remove", inherit(), List.of(
                    words(id + ".owner.remove", routeWith(route, "owner", "remove"), List.of("world"),
                        List.of(administrativeWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP)))
                ))
            )),
            rankTree(id, route),
            accessTree(id, route)
        ));
    }

    private CommandNodeSpec rankTree(final String id, final CommandRoute route) {
        return literal(id + ".rank", "rank", access(ModuleId.OWNERSHIP, RANK_PERMISSION), List.of(
            literal(id + ".rank.create", "create", inherit(), List.of(
                words(id + ".rank.create", routeWith(route, "rank", "create"), List.of("world", "rank"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP), noSuggestions()))
            )),
            literal(id + ".rank.delete", "delete", inherit(), List.of(
                words(id + ".rank.delete", routeWith(route, "rank", "delete"), List.of("world", "rank"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP), rankIds()))
            )),
            literal(id + ".rank.set", "set", inherit(), List.of(
                words(id + ".rank.set", routeWith(route, "rank", "set"), List.of("world", "player", "rank"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP), onlinePlayers(), rankIds()))
            )),
            literal(id + ".rank.remove", "remove", inherit(), List.of(
                words(id + ".rank.remove", routeWith(route, "rank", "remove"), List.of("world", "player"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP), onlinePlayers()))
            )),
            literal(id + ".rank.perm", "perm", inherit(), List.of(
                words(id + ".rank.perm", routeWith(route, "rank", "perm"), List.of("world", "rank", "operation", "permission"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP), rankIds(),
                        staticSuggestions(List.of("add", "remove")), noSuggestions()))
            )),
            literal(id + ".rank.toggle", "toggle", inherit(), List.of(
                words(id + ".rank.toggle", routeWith(route, "rank", "toggle"), List.of("world"),
                    List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP)))
            ))
        ));
    }

    private CommandNodeSpec accessTree(final String id, final CommandRoute route) {
        return literal(id + ".access", "access", access(ModuleId.OWNERSHIP, ACCESS_PERMISSION), List.of(
            words(id + ".access", routeWith(route, "access"), List.of("world", "operation", "value"),
                List.of(manageableWorlds(CommandAuthorizationSnapshot.ManagementArea.OWNERSHIP),
                    staticSuggestions(List.of("mode", "add", "remove")), accessValues()))
        ));
    }

    private CommandNodeSpec storageTree(final String id, final CommandRoute route) {
        return literal(id, "storage", access(ModuleId.STORAGE, STORAGE_PERMISSION), List.of(
            literal(id + ".migrate", "migrate", inherit(), List.of(
                argument(id + ".migrate.source", "source", CommandArgumentKind.WORD, List.of(
                    argument(id + ".migrate.target", "target", CommandArgumentKind.WORD, List.of(
                        literal(id + ".migrate.confirm", "confirm", inherit(), List.of())
                            .executes(new CommandRoute(List.of("storage", "migrate"), List.of("confirm")), List.of("source", "target"))
                    )).suggests(storageProviders())
                )).suggests(storageProviders())
            ))
        ));
    }

    private CommandNodeSpec words(
        final String id,
        final CommandRoute route,
        final List<String> names,
        final List<com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>> providers
    ) {
        if (names.isEmpty()) {
            throw new IllegalArgumentException("A word chain requires at least one argument.");
        }
        return word(id, route, names, providers, 0);
    }

    private CommandNodeSpec word(
        final String id,
        final CommandRoute route,
        final List<String> names,
        final List<com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>> providers,
        final int index
    ) {
        final String name = names.get(index);
        CommandNodeSpec node = argument(id + '.' + name, name, CommandArgumentKind.WORD,
            index + 1 == names.size() ? List.of() : List.of(word(id, route, names, providers, index + 1)));
        if (index < providers.size() && providers.get(index) != null) {
            node = node.suggests(providers.get(index));
        }
        return index + 1 == names.size() ? node.executes(route, names) : node;
    }

    private CommandNodeSpec literal(
        final String id,
        final String value,
        final CommandAccess access,
        final List<CommandNodeSpec> children
    ) {
        return CommandNodeSpec.literal(id, value, access, children);
    }

    private CommandNodeSpec argument(
        final String id,
        final String name,
        final CommandArgumentKind kind,
        final List<CommandNodeSpec> children
    ) {
        return CommandNodeSpec.argument(id, name, kind, children);
    }

    private CommandAccess access(final ModuleId module, final String permission) {
        return new CommandAccess(module, List.of(permission));
    }

    private CommandAccess anyAccess(final ModuleId module, final String... permissions) {
        return new CommandAccess(module, CommandPermissionMode.ANY, List.of(permissions));
    }

    private CommandAccess allAccess(final ModuleId module, final String... permissions) {
        return new CommandAccess(module, CommandPermissionMode.ALL, List.of(permissions));
    }

    private CommandAccess inherit() {
        return CommandAccess.inherit();
    }

    private CommandRoute route(final String... prefix) {
        return new CommandRoute(List.of(prefix));
    }

    private CommandRoute routeWith(final CommandRoute base, final String... suffix) {
        final List<String> prefix = new ArrayList<>(base.prefix());
        prefix.addAll(List.of(suffix));
        return new CommandRoute(prefix, base.suffix());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> managedWorlds() {
        return suggestions.provider(SuggestionCatalog.MANAGED_WORLD, (context, catalog) -> catalog.managedWorlds());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> lifecycleWorlds() {
        return suggestions.provider(
            SuggestionCatalog.LIFECYCLE_WORLD, (context, catalog) -> catalog.lifecycleWorlds()
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> lifecycleTargets() {
        return suggestions.provider(
            SuggestionCatalog.LIFECYCLE_TARGET, (context, catalog) -> catalog.lifecycleTargets()
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> fallbackTargets() {
        return suggestions.provider(
            SuggestionCatalog.FALLBACK_TARGET, (context, catalog) -> {
                final String sourceWorld;
                try {
                    sourceWorld = context.getArgument("world", String.class);
                } catch (final IllegalArgumentException exception) {
                    return catalog.fallbackTargets();
                }
                return catalog.fallbackTargets().stream()
                    .filter(worldId -> !worldId.equals(sourceWorld))
                    .toList();
            }
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> displayNameWorlds() {
        return suggestions.provider(
            SuggestionCatalog.DISPLAY_NAME_WORLD, (context, catalog) -> catalog.displayNameWorlds()
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> detachedWorlds() {
        return suggestions.provider(SuggestionCatalog.DETACHED_WORLD, (context, catalog) -> catalog.detachedWorlds());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> manageableWorlds(
        final CommandAuthorizationSnapshot.ManagementArea area
    ) {
        return suggestions.provider(SuggestionCatalog.MANAGEABLE_WORLD, (context, catalog) ->
            catalog.manageableWorlds(context.getSource(), area)
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> administrativeWorlds(
        final CommandAuthorizationSnapshot.ManagementArea area
    ) {
        return suggestions.provider(SuggestionCatalog.MANAGEABLE_WORLD, (context, catalog) ->
            catalog.administrativeWorlds(context.getSource(), area)
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> identityWorlds() {
        return suggestions.provider(SuggestionCatalog.IDENTITY_WORLD, (context, catalog) -> catalog.identityWorlds());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> identityWorlds(
        final IdentityVerificationState state
    ) {
        final SuggestionKey<String> key = state == IdentityVerificationState.SYNC_PENDING
            ? SuggestionCatalog.SYNC_PENDING_WORLD
            : SuggestionCatalog.CONFLICT_WORLD;
        return suggestions.provider(key, (context, catalog) -> catalog.identityWorlds(state));
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> nonVerifiedWorlds() {
        return suggestions.provider(SuggestionCatalog.NON_VERIFIED_WORLD, (context, catalog) -> catalog.nonVerifiedWorlds());
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> worldWarps() {
        return suggestions.provider(SuggestionCatalog.WORLD_WARP, (context, catalog) ->
            catalog.warpNames(
                context.getSource(),
                context.getArgument("world", String.class),
                CommandAuthorizationSnapshot.ManagementArea.WARP
            )
        );
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
        return suggestions.provider(SuggestionCatalog.RANK_ID, (context, catalog) ->
            catalog.rankIds(context.getSource(), context.getArgument("world", String.class))
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> storageProviders() {
        return suggestions.provider(SuggestionCatalog.STORAGE_PROVIDER, (context, catalog) ->
            java.util.Arrays.stream(StorageProvider.values()).map(Enum::name).toList()
        );
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> accessValues() {
        return suggestions.provider(SuggestionCatalog.ONLINE_PLAYER, (context, catalog) -> switch (
            context.getArgument("operation", String.class).toLowerCase(java.util.Locale.ROOT)
        ) {
            case "mode" -> List.of("NONE", "WHITELIST", "BLACKLIST");
            case "add", "remove" -> catalog.onlinePlayers();
            default -> List.of();
        });
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> noSuggestions() {
        return (context, builder) -> builder.buildFuture();
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> staticSuggestions(
        final Collection<String> candidates
    ) {
        return (context, builder) -> {
            final String remaining = builder.getRemainingLowerCase();
            candidates.stream()
                .filter(candidate -> candidate.toLowerCase(java.util.Locale.ROOT).startsWith(remaining))
                .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }
}