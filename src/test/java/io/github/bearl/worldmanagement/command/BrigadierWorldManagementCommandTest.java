package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import io.github.bearl.worldmanagement.storage.InMemoryWorldMetadataRepository;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRegistry;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class BrigadierWorldManagementCommandTest {

    private static final CommandSourceStack SOURCE = commandSource();

    @Test
    void createEnvironmentSuggestionsReplaceOnlyEnvironmentArgument() {
        final String input = "wm create creative ";
        final Suggestions suggestions = suggestions(input);

        assertEquals(input.length(), suggestions.getRange().getStart());
        assertEquals(input.length(), suggestions.getRange().getEnd());
        assertEquals(List.of("NETHER", "NORMAL", "THE_END"), suggestions.getList().stream().map(suggestion -> suggestion.getText()).sorted().toList());
    }

    @Test
    void createTypeSuggestionsReplaceOnlyTypeArgument() {
        final String input = "wm create creative NORMAL ";
        final Suggestions suggestions = suggestions(input);

        assertEquals(input.length(), suggestions.getRange().getStart());
        assertEquals(input.length(), suggestions.getRange().getEnd());
        assertTrue(suggestions.getList().stream().anyMatch(suggestion -> suggestion.getText().equals("FLAT")));
    }

    @Test
    void createOptionsAreUnorderedAndUsedOptionsAreNotSuggestedAgain() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm create creative NORMAL FLAT --seed Alpha123 --generator Terra:normal");
        assertFullyParsed(dispatcher, "wm create creative NORMAL FLAT --generator Terra:normal --seed Alpha123");
        assertFullyParsed(
            dispatcher,
            "wm create creative NORMAL FLAT --generator-settings '{}' --no-structures "
                + "--biome Terra:climate --force-spawn-position 0,80,0,90,0"
        );
        assertFullyParsed(dispatcher, "wm create creative NORMAL FLAT --generate-bonus-chest");

        assertEquals(
            List.of(
                "--biome", "--detached", "--force-spawn-position", "--generate-bonus-chest",
                "--generator", "--generator-settings", "--no-structures", "--seed"
            ),
            suggestions("wm create creative NORMAL FLAT ").getList().stream()
                .map(suggestion -> suggestion.getText()).sorted().toList()
        );
        assertEquals(
            List.of(
                "--biome", "--detached", "--force-spawn-position", "--generate-bonus-chest",
                "--generator", "--generator-settings", "--no-structures"
            ),
            suggestions("wm create creative NORMAL FLAT --seed Alpha123 ").getList().stream()
                .map(suggestion -> suggestion.getText()).sorted().toList()
        );
        assertEquals(
            List.of(
                "--biome", "--force-spawn-position", "--generate-bonus-chest", "--generator",
                "--generator-settings", "--no-structures", "--seed"
            ),
            suggestions("wm create creative NORMAL FLAT --detached ").getList().stream()
                .map(suggestion -> suggestion.getText()).sorted().toList()
        );
        assertFullyParsed(dispatcher, "wm create creative NORMAL FLAT --detached --seed Alpha123");
    }

    @Test
    void createGeneratorValueUsesTheImmutableGeneratorSnapshot() {
        final SuggestionCatalog catalog = new SuggestionCatalog(new OnlinePlayerSnapshot());
        catalog.replaceGeneratorPlugins(List.of("Iris", "Terra"));
        final BrigadierWorldManagementCommand command = new BrigadierWorldManagementCommand(catalog);
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());
        final String input = "wm create creative NORMAL FLAT --generator T";

        final Suggestions suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, SOURCE)).join();

        assertEquals(input.length() - 1, suggestions.getRange().getStart());
        assertEquals(input.length(), suggestions.getRange().getEnd());
        assertEquals(List.of("Terra"), suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList());
    }

    @Test
    void createProviderCompletionKeepsGeneratorAndBiomeSnapshotsSeparate() {
        final SuggestionCatalog catalog = new SuggestionCatalog(new OnlinePlayerSnapshot());
        catalog.replaceGeneratorPlugins(List.of("Terra"));
        catalog.replaceBiomeProviderPlugins(List.of("BiomeOnly"));
        final BrigadierWorldManagementCommand command = new BrigadierWorldManagementCommand(catalog);
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());

        final Suggestions generators = dispatcher.getCompletionSuggestions(dispatcher.parse(
            "wm create creative NORMAL FLAT --generator ", SOURCE
        )).join();
        final Suggestions biomes = dispatcher.getCompletionSuggestions(dispatcher.parse(
            "wm create creative NORMAL FLAT --biome ", SOURCE
        )).join();

        assertEquals(List.of("Terra"), generators.getList().stream().map(suggestion -> suggestion.getText()).toList());
        assertEquals(List.of("BiomeOnly"), biomes.getList().stream().map(suggestion -> suggestion.getText()).toList());
    }

    @Test
    void helpSuggestionsTraverseTheSameLiteralSpecification() {
        final Suggestions topLevel = suggestions("wm help own");
        assertEquals(List.of("ownership"), topLevel.getList().stream().map(suggestion -> suggestion.getText()).toList());

        final Suggestions nested = suggestions("wm help ownership r");
        assertEquals(List.of("ownership rank"), nested.getList().stream().map(suggestion -> suggestion.getText()).toList());

        assertEquals(List.of(), suggestions("wm help 2").getList());
    }

    @Test
    void importRequiresEnvironmentAndSuggestsSupportedValues() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm import archive NETHER");
        assertExecutableParsed(dispatcher, "wm import archive NETHER --detached");
        assertExecutableParsed(dispatcher, "wm adopt archive --detached");
        assertFullyParsed(dispatcher, "wm import archive");

        final String input = "wm import archive ";
        final Suggestions suggestions = suggestions(input);
        assertEquals(input.length(), suggestions.getRange().getStart());
        assertEquals(
            List.of("NETHER", "NORMAL", "THE_END"),
            suggestions.getList().stream().map(suggestion -> suggestion.getText()).sorted().toList()
        );
    }

    @Test
    void moduleAliasUsesTheCanonicalModuleRoute() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().buildModuleRoot(ModuleId.WARP, "w"));

        assertFullyParsed(dispatcher, "w trust world spawn add player");
    }

    @Test
    void legacyParallelTrustSyntaxIsNotPresentInRootTree() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm trust world spawn add player");
        assertFullyParsed(dispatcher, "wm warp trust world spawn add player");
    }

    @Test
    void ownershipBranchesAreSiblingsUnderTheOwnershipModule() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm ownership rank delete world member");
        assertFullyParsed(dispatcher, "wm ownership access world mode WHITELIST");
    }

    @Test
    void ownershipWorldCompletionUsesOwnerAndAdministratorSnapshots() {
        final PluginIoExecutor executor = new PluginIoExecutor("BrigadierOwnershipCompletionTest");
        try {
            final UUID ownerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            final Player owner = player(ownerId, Set.of(
                "worldmanagement.command.rank", "worldmanagement.command.owner"
            ));
            final Player administrator = player(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                Set.of(
                    "worldmanagement.command.rank",
                    "worldmanagement.command.owner",
                    CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION
                )
            );
            final Player unknown = player(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                Set.of(
                    "worldmanagement.command.rank",
                    "worldmanagement.command.owner",
                    CommandAuthorizationSnapshot.OWNERSHIP_ADMIN_PERMISSION
                )
            );
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("survival", true).join();
            metadata.update("creative", world -> world.withOwner(ownerId.toString())).join();
            final CommandAuthorizationSnapshot authorizations = new CommandAuthorizationSnapshot();
            authorizations.replace(List.of(owner, administrator));
            final SuggestionCatalog catalog = new SuggestionCatalog(new OnlinePlayerSnapshot(), authorizations);
            catalog.initialize(metadata);
            final BrigadierWorldManagementCommand command = new BrigadierWorldManagementCommand(catalog);

            assertSuggestions(command, owner, "wm ownership rank toggle ", List.of("creative"));
            assertSuggestions(command, administrator, "wm ownership rank toggle ", List.of("creative", "survival"));
            assertSuggestions(command, unknown, "wm ownership rank toggle ", List.of());
            assertSuggestions(command, owner, "wm ownership owner remove ", List.of());
            assertSuggestions(command, administrator, "wm ownership owner remove ", List.of("creative", "survival"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void lifecycleRoutesExposeDetachedAndFallbackBranches() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm manage creative");
        assertFullyParsed(dispatcher, "wm list detached");
        assertFullyParsed(dispatcher, "wm remove creative purge confirm");
        assertFullyParsed(dispatcher, "wm unload creative lobby");
        assertFullyParsed(dispatcher, "wm delete creative lobby confirm");
        assertExecutableParsed(dispatcher, "wm load archive NETHER --detached");
        assertFullyParsed(dispatcher, "wm load archive --detached");
    }

    @Test
    void lifecycleWorldCompletionIncludesActiveAndDetachedMetadata() {
        final PluginIoExecutor executor = new PluginIoExecutor("BrigadierLifecycleCompletionTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("archive", true).join();
            metadata.remove("archive").join();
            final SuggestionCatalog catalog = new SuggestionCatalog(new OnlinePlayerSnapshot());
            catalog.initialize(metadata);
            final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand(catalog).build());

            for (final String operation : List.of("load", "unload", "delete", "remove")) {
                final String input = "wm " + operation + " ";
                final Suggestions suggestions = dispatcher.getCompletionSuggestions(
                    dispatcher.parse(input, SOURCE)
                ).join();
                assertEquals(
                    List.of("archive", "creative"),
                    suggestions.getList().stream().map(suggestion -> suggestion.getText()).sorted().toList(),
                    operation
                );
            }
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void lifecycleCompletionScopesUseRuntimeAndMetadataSnapshots() {
        final PluginIoExecutor executor = new PluginIoExecutor("BrigadierRuntimeCompletionTest");
        try {
            final WorldManagementService metadata = new WorldManagementService(
                executor, new InMemoryWorldMetadataRepository(), new WorldRegistry()
            );
            metadata.load().join();
            metadata.adopt("creative", true).join();
            metadata.adopt("archive", true).join();
            metadata.remove("archive").join();
            final LoadedWorldCatalog loadedWorlds = new LoadedWorldCatalog();
            loadedWorlds.replaceAll(List.of(
                new WorldRuntimeGateway.LifecycleWorld(
                    new WorldIdentitySnapshot(
                        "minecraft:creative",
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        WorldEnvironment.NORMAL, 0L, true
                    ), LifecycleCapability.MANAGED
                ),
                new WorldRuntimeGateway.LifecycleWorld(
                    new WorldIdentitySnapshot(
                        "minecraft:lobby",
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        WorldEnvironment.NORMAL, 0L, true
                    ), LifecycleCapability.MANAGED
                )
            ));
            final SuggestionCatalog catalog = new SuggestionCatalog(
                new OnlinePlayerSnapshot(), new CommandAuthorizationSnapshot(), loadedWorlds
            );
            catalog.initialize(metadata);
            final BrigadierWorldManagementCommand command = new BrigadierWorldManagementCommand(catalog);

            assertSuggestions(command, SOURCE, "wm load ", List.of("archive", "creative"));
            assertSuggestions(command, SOURCE, "wm remove ", List.of("archive", "creative"));
            assertSuggestions(command, SOURCE, "wm unload ", List.of("archive", "creative", "lobby"));
            assertSuggestions(command, SOURCE, "wm delete ", List.of("archive", "creative", "lobby"));
            assertSuggestions(command, SOURCE, "wm unload creative ", List.of("lobby"));
            assertSuggestions(command, SOURCE, "wm display-name set ", List.of("archive", "creative"));
            assertSuggestions(command, SOURCE, "wm tp self ", List.of("archive", "creative"));
        } finally {
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void identityShowIsExecutableWithoutLegacySetSyntax() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm identity show creative");
        assertFullyParsed(dispatcher, "wm identity set creative confirm");
    }

    @Test
    void identityMutationRoutesUseExplicitConfirmationAndWarpPolicyLiterals() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm identity sync creative");
        assertFullyParsed(dispatcher, "wm identity accept-replacement creative confirm clear-warps");
        assertFullyParsed(dispatcher, "wm identity accept-replacement creative confirm keep-warps");
        assertFullyParsed(dispatcher, "wm identity abandon creative confirm");
        assertFullyParsed(dispatcher, "wm identity accept-replacement creative confirm");
        assertFullyParsed(dispatcher, "wm identity accept-replacement creative keep-warps confirm");
    }

    @Test
    void displayNameSetUsesOneTerminalGreedyArgumentAndResetIsExact() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm display-name set creative <gradient:red:gold>創意 世界</gradient>");
        assertFullyParsed(dispatcher, "wm display-name reset creative");
        assertFullyParsed(dispatcher, "wm display-name reset creative extra");
    }

    @Test
    void teleportRoutesRequireEitherNoCoordinatesOrAllThreeCoordinates() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm tp self creative");
        assertFullyParsed(dispatcher, "wm tp self creative 1.5 64 -2");
        assertFullyParsed(dispatcher, "wm tp player Alex creative 1.5 64 -2");
        assertFullyParsed(dispatcher, "wm tp --any creative");
        assertFullyParsed(dispatcher, "wm tp self creative 1.5 64");
    }

    @Test
    void usesInjectedResponderWhileCommandServicesAreLoading() throws Exception {
        final BrigadierWorldManagementCommand command = new BrigadierWorldManagementCommand();
        final AtomicReference<CommandSender> received = new AtomicReference<>();
        command.initializeLoadingResponder(received::set);
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());
        final ParseResults<CommandSourceStack> parsed = dispatcher.parse("wm list", SOURCE);

        dispatcher.findNode(List.of("wm", "list")).getCommand().run(parsed.getContext().build("wm list"));

        assertSame(SOURCE.getSender(), received.get());
    }

    @Test
    void dispatchesHelpQueryToTheStructuredHelpResponder() throws Exception {
        final BrigadierWorldManagementCommand command = new BrigadierWorldManagementCommand();
        final AtomicReference<CommandHelpService.Result> received = new AtomicReference<>();
        command.initializeHelpResponder((sender, result) -> received.set(result));
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());

        dispatcher.execute("wm help 2", SOURCE);

        final CommandHelpService.PageResult page = org.junit.jupiter.api.Assertions.assertInstanceOf(
            CommandHelpService.PageResult.class,
            received.get()
        );
        assertEquals(2, page.page().page());
    }

    @Test
    void dispatchesNestedSyntaxFeedbackWithCanonicalTopicUsage() throws Exception {
        final BrigadierWorldManagementCommand command = new BrigadierWorldManagementCommand();
        final java.util.ArrayList<CommandSyntaxFeedback> received = new java.util.ArrayList<>();
        command.initializeSyntaxFeedbackResponder((sender, feedback) -> received.add(feedback));
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());

        dispatcher.execute("wm", SOURCE);
        dispatcher.execute("wm ownership rank", SOURCE);
        dispatcher.execute("wm storage migrate yaml sqlite nope", SOURCE);
        dispatcher.execute("wm list detached extra", SOURCE);

        assertEquals(CommandSyntaxFeedback.Kind.MISSING, received.get(0).kind());
        assertEquals("", received.get(0).topicPath());
        assertEquals(CommandSyntaxFeedback.Kind.MISSING, received.get(1).kind());
        assertEquals("ownership rank", received.get(1).topicPath());
        assertTrue(received.get(1).usageLines().contains("/wm ownership rank set <world> <player> <rank>"));
        assertEquals(CommandSyntaxFeedback.Kind.INVALID, received.get(2).kind());
        assertEquals("storage migrate", received.get(2).topicPath());
        assertEquals(List.of("/wm storage migrate <source> <target> confirm"), received.get(2).usageLines());
        assertEquals(CommandSyntaxFeedback.Kind.EXTRA, received.get(3).kind());
        assertEquals("list detached", received.get(3).topicPath());
        assertEquals(List.of("/wm list detached"), received.get(3).usageLines());
    }

    @Test
    void everyExecutableLeafDeclaresItsMinimumRuntimeLevel() {
        final CommandNode<CommandSourceStack> root = new BrigadierWorldManagementCommand().build();
        final Set<String> executablePaths = executablePaths(root, "wm");
        final Set<String> declaredPaths = runtimeCoveragePaths();

        assertEquals(executablePaths, declaredPaths);
    }

    private static Set<String> executablePaths(final CommandNode<CommandSourceStack> node, final String path) {
        final java.util.HashSet<String> paths = new java.util.HashSet<>();
        if (node.getCommand() != null && !(node.getCommand() instanceof GeneratedSyntaxFeedbackCommand)) {
            paths.add(path);
        }
        for (final CommandNode<CommandSourceStack> child : node.getChildren()) {
            final String segment;
            if (child instanceof ArgumentCommandNode<?, ?> argument) {
                final boolean greedy = argument.getType() instanceof StringArgumentType stringType
                    && stringType.getType() == StringArgumentType.StringType.GREEDY_PHRASE;
                segment = '<' + child.getName() + (greedy ? "...>" : ">");
            } else {
                segment = child.getName();
            }
            paths.addAll(executablePaths(child, path + ' ' + segment));
        }
        return Set.copyOf(paths);
    }

    private static Set<String> runtimeCoveragePaths() {
        final Path manifest = Path.of(System.getProperty("user.dir"), "e2e", "command-runtime-coverage.txt");
        try {
            final List<String> declarations = Files.readString(manifest, StandardCharsets.UTF_8).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
            assertTrue(declarations.stream().allMatch(line ->
                line.startsWith("CONSOLE_RUNTIME wm ") || line.startsWith("PLAYER_RUNTIME wm ")
            ), "Runtime coverage entries must declare CONSOLE_RUNTIME or PLAYER_RUNTIME followed by a canonical wm path.");
            final List<String> paths = declarations.stream()
                .map(line -> line.substring(line.indexOf(' ') + 1))
                .toList();
            final Set<String> uniquePaths = paths.stream().collect(Collectors.toUnmodifiableSet());
            assertEquals(paths.size(), uniquePaths.size(), "Runtime coverage paths must be unique.");
            return uniquePaths;
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Suggestions suggestions(final String input) {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, SOURCE)).join();
    }

    private static void assertSuggestions(
        final BrigadierWorldManagementCommand command,
        final Player sender,
        final String input,
        final List<String> expected
    ) {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());
        final Suggestions suggestions = dispatcher.getCompletionSuggestions(
            dispatcher.parse(input, commandSource(sender))
        ).join();
        if (!expected.isEmpty()) {
            assertEquals(input.length(), suggestions.getRange().getStart());
            assertEquals(input.length(), suggestions.getRange().getEnd());
        }
        assertEquals(expected, suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList());
    }

    private static void assertSuggestions(
        final BrigadierWorldManagementCommand command,
        final CommandSourceStack source,
        final String input,
        final List<String> expected
    ) {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());
        final Suggestions suggestions = dispatcher.getCompletionSuggestions(
            dispatcher.parse(input, source)
        ).join();
        assertEquals(expected, suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList());
    }

    private static void assertFullyParsed(final CommandDispatcher<CommandSourceStack> dispatcher, final String input) {
        final ParseResults<CommandSourceStack> result = dispatcher.parse(input, SOURCE);
        assertTrue(result.getExceptions().isEmpty(), input);
        assertTrue(!result.getReader().canRead(), input);
    }

    private static void assertExecutableParsed(
        final CommandDispatcher<CommandSourceStack> dispatcher,
        final String input
    ) {
        final ParseResults<CommandSourceStack> result = dispatcher.parse(input, SOURCE);
        assertTrue(result.getExceptions().isEmpty(), input);
        assertTrue(!result.getReader().canRead(), input);
        assertTrue(!(result.getContext().getCommand() instanceof GeneratedSyntaxFeedbackCommand), input);
    }

    private static CommandSourceStack commandSource() {
        final CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> method.getName().equals("hasPermission") ? true : defaultValue(method.getReturnType())
        );
        return (CommandSourceStack) Proxy.newProxyInstance(
            CommandSourceStack.class.getClassLoader(),
            new Class<?>[] {CommandSourceStack.class},
            (proxy, method, arguments) -> method.getName().equals("getSender") ? sender : defaultValue(method.getReturnType())
        );
    }

    private static CommandSourceStack commandSource(final CommandSender sender) {
        return (CommandSourceStack) Proxy.newProxyInstance(
            CommandSourceStack.class.getClassLoader(),
            new Class<?>[] {CommandSourceStack.class},
            (proxy, method, arguments) -> method.getName().equals("getSender")
                ? sender
                : defaultValue(method.getReturnType())
        );
    }

    private static Player player(final UUID playerId, final Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "hasPermission" -> permissions.contains(arguments[0]);
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}