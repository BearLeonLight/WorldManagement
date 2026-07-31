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
import java.lang.reflect.Proxy;
import org.bukkit.command.CommandSender;
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
    void importRequiresEnvironmentAndSuggestsSupportedValues() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm import archive NETHER");
        assertTrue(dispatcher.parse("wm import archive", SOURCE).getContext().getCommand() == null);

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

        assertTrue(dispatcher.parse("wm trust world spawn add player", SOURCE).getReader().canRead());
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
    void lifecycleRoutesExposeDetachedAndFallbackBranches() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm manage creative");
        assertFullyParsed(dispatcher, "wm list detached");
        assertFullyParsed(dispatcher, "wm remove creative purge confirm");
        assertFullyParsed(dispatcher, "wm unload creative lobby");
        assertFullyParsed(dispatcher, "wm delete creative lobby confirm");
    }

    @Test
    void identityShowIsExecutableWithoutLegacySetSyntax() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm identity show creative");
        assertTrue(dispatcher.parse("wm identity set creative confirm", SOURCE).getContext().getCommand() == null);
    }

    @Test
    void identityMutationRoutesUseExplicitConfirmationAndWarpPolicyLiterals() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm identity sync creative");
        assertFullyParsed(dispatcher, "wm identity accept-replacement creative confirm clear-warps");
        assertFullyParsed(dispatcher, "wm identity accept-replacement creative confirm keep-warps");
        assertFullyParsed(dispatcher, "wm identity abandon creative confirm");
        assertTrue(dispatcher.parse(
            "wm identity accept-replacement creative confirm", SOURCE
        ).getContext().getCommand() == null);
        assertTrue(dispatcher.parse(
            "wm identity accept-replacement creative keep-warps confirm", SOURCE
        ).getContext().getCommand() == null);
    }

    @Test
    void displayNameSetUsesOneTerminalGreedyArgumentAndResetIsExact() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm display-name set creative <gradient:red:gold>創意 世界</gradient>");
        assertFullyParsed(dispatcher, "wm display-name reset creative");
        assertTrue(dispatcher.parse("wm display-name reset creative extra", SOURCE).getReader().canRead());
    }

    @Test
    void teleportRoutesRequireEitherNoCoordinatesOrAllThreeCoordinates() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new BrigadierWorldManagementCommand().build());

        assertFullyParsed(dispatcher, "wm tp self creative");
        assertFullyParsed(dispatcher, "wm tp self creative 1.5 64 -2");
        assertFullyParsed(dispatcher, "wm tp player Alex creative 1.5 64 -2");
        assertFullyParsed(dispatcher, "wm tp --any creative");
        assertTrue(dispatcher.parse("wm tp self creative 1.5 64", SOURCE).getContext().getCommand() == null);
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
    void everyExecutableLeafDeclaresItsMinimumRuntimeLevel() {
        final CommandNode<CommandSourceStack> root = new BrigadierWorldManagementCommand().build();
        final Set<String> executablePaths = executablePaths(root, "wm");
        final Set<String> declaredPaths = runtimeCoveragePaths();

        assertEquals(executablePaths, declaredPaths);
    }

    private static Set<String> executablePaths(final CommandNode<CommandSourceStack> node, final String path) {
        final java.util.HashSet<String> paths = new java.util.HashSet<>();
        if (node.getCommand() != null) {
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

    private static void assertFullyParsed(final CommandDispatcher<CommandSourceStack> dispatcher, final String input) {
        final ParseResults<CommandSourceStack> result = dispatcher.parse(input, SOURCE);
        assertTrue(result.getExceptions().isEmpty(), input);
        assertTrue(!result.getReader().canRead(), input);
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