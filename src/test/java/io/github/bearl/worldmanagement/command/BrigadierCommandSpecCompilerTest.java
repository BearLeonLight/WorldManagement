package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.bearl.worldmanagement.module.ModuleId;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

final class BrigadierCommandSpecCompilerTest {

    @Test
    void compilesCanonicalExecutionAndParsedArgumentsFromOneSpecification() throws Exception {
        final CommandRoute route = new CommandRoute(List.of("create"));
        final CommandNodeSpec root = CommandNodeSpec.root(List.of(CommandNodeSpec.literal(
            "create",
            "create",
            new CommandAccess(ModuleId.LIFECYCLE, List.of("worldmanagement.command.create")),
            List.of(CommandNodeSpec.argument("create.world", "world", CommandArgumentKind.WORD, List.of())
                .executes(route, List.of("world")))
        )));
        final AtomicReference<CommandExecution> receivedExecution = new AtomicReference<>();
        final AtomicReference<List<String>> receivedArguments = new AtomicReference<>();
        final BrigadierCommandSpecCompiler compiler = new BrigadierCommandSpecCompiler(
            new CommandAccessPolicy(),
            (context, execution) -> {
                receivedExecution.set(execution);
                receivedArguments.set(compilerArguments(context, execution));
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }
        );
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(compiler.compileRoot(root, "wm"));

        final String input = "wm create creative";
        final var parsed = dispatcher.parse(input, source(true));
        assertTrue(parsed.getExceptions().isEmpty());
        assertTrue(!parsed.getReader().canRead());
        dispatcher.execute(parsed);

        assertEquals(route, receivedExecution.get().route());
        assertEquals(List.of("creative"), receivedArguments.get());
        assertTrue(dispatcher.parse(input, source(false)).getReader().canRead());
    }

    @Test
    void productionSpecificationCompilesHelpAndGreedyTopicQuery() throws Exception {
        final AtomicReference<CommandExecution> receivedExecution = new AtomicReference<>();
        final AtomicReference<List<String>> receivedArguments = new AtomicReference<>();
        final BrigadierCommandSpecCompiler compiler = new BrigadierCommandSpecCompiler(
            new CommandAccessPolicy(),
            (context, execution) -> {
                receivedExecution.set(execution);
                receivedArguments.set(compilerArguments(context, execution));
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }
        );
        final WorldManagementCommandSpec specification = new WorldManagementCommandSpec(
            new SuggestionCatalog(new OnlinePlayerSnapshot())
        );
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(compiler.compileRoot(specification.root(), "wm"));

        dispatcher.execute("wm help", source(true));
        assertEquals(new CommandRoute(List.of("help")), receivedExecution.get().route());
        assertEquals(List.of(), receivedArguments.get());

        dispatcher.execute("wm help ownership rank", source(true));
        assertEquals(new CommandRoute(List.of("help")), receivedExecution.get().route());
        assertEquals(List.of("ownership rank"), receivedArguments.get());
    }

    @Test
    void extractsTypedArgumentsUsingTheExecutionBinding() throws Exception {
        final CommandRoute route = new CommandRoute(List.of("typed"));
        final CommandNodeSpec root = CommandNodeSpec.root(List.of(CommandNodeSpec.literal(
            "typed",
            "typed",
            new CommandAccess(ModuleId.LIFECYCLE, List.of("worldmanagement.command.create")),
            List.of(CommandNodeSpec.typedArgument(
                "typed.value", "value", IntegerArgumentType.integer(), "<value>", List.of()
            ).executesTyped(route, List.of(new CommandArgumentBinding("value", Integer.class))))
        )));
        final AtomicReference<Object> received = new AtomicReference<>();
        final BrigadierCommandSpecCompiler compiler = new BrigadierCommandSpecCompiler(
            new CommandAccessPolicy(),
            (context, execution) -> {
                received.set(context.getArgument(
                    execution.arguments().getFirst().name(),
                    execution.arguments().getFirst().type()
                ));
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }
        );
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(compiler.compileRoot(root, "wm"));

        dispatcher.execute("wm typed 42", source(true));

        assertEquals(42, received.get());
    }

    private static List<String> compilerArguments(
        final CommandContext<CommandSourceStack> context,
        final CommandExecution execution
    ) {
        return execution.argumentNames().stream()
            .map(name -> context.getArgument(name, String.class))
            .toList();
    }

    private static CommandSourceStack source(final boolean permitted) {
        final CommandSender sender = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, arguments) -> method.getName().equals("hasPermission") ? permitted : defaultValue(method.getReturnType())
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