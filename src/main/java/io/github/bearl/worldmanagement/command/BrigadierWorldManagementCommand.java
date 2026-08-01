package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.module.ModuleManager;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/** Compiles and dispatches the single WorldManagement command specification. */
public final class BrigadierWorldManagementCommand {

    private final AtomicReference<WorldManagementCommand> delegate = new AtomicReference<>();
    private final AtomicReference<Consumer<CommandSender>> loadingResponder = new AtomicReference<>(
        sender -> sender.sendMessage(Component.text("WorldManagement 正在載入資料。"))
    );
    private final AtomicReference<BiConsumer<CommandSender, CommandHelpService.Result>> helpResponder = new AtomicReference<>(
        (sender, result) -> loadingResponder.get().accept(sender)
    );
    private final AtomicReference<BiConsumer<CommandSender, CommandSyntaxFeedback>> syntaxFeedbackResponder = new AtomicReference<>(
        (sender, feedback) -> loadingResponder.get().accept(sender)
    );
    private final CommandAccessPolicy accessPolicy = new CommandAccessPolicy();
    private final SuggestionCatalog suggestions;
    private final WorldManagementCommandSpec specification;
    private final BrigadierCommandSpecCompiler compiler;
    private final CommandHelpService helpService;

    public BrigadierWorldManagementCommand() {
        this(new SuggestionCatalog(new OnlinePlayerSnapshot()));
    }

    public BrigadierWorldManagementCommand(final SuggestionCatalog suggestions) {
        this.suggestions = java.util.Objects.requireNonNull(suggestions, "suggestions");
        this.specification = new WorldManagementCommandSpec(suggestions, accessPolicy);
        this.helpService = new CommandHelpService(specification.root(), accessPolicy, 6);
        this.compiler = new BrigadierCommandSpecCompiler(
            accessPolicy,
            (context, execution) -> execute(context, execution.route(), execution.arguments()),
            this::executeSyntaxFeedback
        );
    }

    public void initialize(final WorldManagementCommand commandHandler, final ModuleManager manager) {
        delegate.set(java.util.Objects.requireNonNull(commandHandler, "commandHandler"));
        helpResponder.set(commandHandler::showHelp);
        syntaxFeedbackResponder.set(commandHandler::showSyntaxFeedback);
        accessPolicy.initialize(manager);
    }

    public void initializeLoadingResponder(final Consumer<CommandSender> responder) {
        loadingResponder.set(java.util.Objects.requireNonNull(responder, "responder"));
    }

    void initializeHelpResponder(final BiConsumer<CommandSender, CommandHelpService.Result> responder) {
        helpResponder.set(java.util.Objects.requireNonNull(responder, "responder"));
    }

    void initializeSyntaxFeedbackResponder(final BiConsumer<CommandSender, CommandSyntaxFeedback> responder) {
        syntaxFeedbackResponder.set(java.util.Objects.requireNonNull(responder, "responder"));
    }

    public void initializeHelpAccess(final boolean playersEnabled) {
        accessPolicy.initializeHelpAccess(playersEnabled);
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
        return compiler.compileRoot(specification.root(), label);
    }

    public com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build() {
        return buildRoot("wm");
    }

    public com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> buildModuleRoot(
        final ModuleId module,
        final String label
    ) {
        return compiler.compileModuleRoot(specification.module(module), label);
    }

    private int execute(
        final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        final CommandRoute route,
        final List<CommandArgumentBinding> arguments
    ) {
        final CommandSender sender = context.getSource().getSender();
        final List<Object> parsedArguments = new java.util.ArrayList<>(arguments.size());
        arguments.forEach(binding -> parsedArguments.add(context.getArgument(binding.name(), binding.type())));
        if (route.prefix().equals(List.of("help")) && route.suffix().isEmpty()) {
            final String query = parsedArguments.isEmpty() ? "" : (String) parsedArguments.getFirst();
            helpResponder.get().accept(sender, helpService.resolve(sender, query));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }
        final WorldManagementCommand commandHandler = delegate.get();
        if (commandHandler == null) {
            loadingResponder.get().accept(sender);
            return 0;
        }
        if (route.prefix().equals(List.of("create")) && route.suffix().isEmpty()) {
            commandHandler.executeCreate(
                sender,
                (String) parsedArguments.get(0),
                (String) parsedArguments.get(1),
                (String) parsedArguments.get(2),
                parsedArguments.size() == 4
                    ? (CreateCommandOptions) parsedArguments.get(3)
                    : CreateCommandOptions.defaults()
            );
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }
        commandHandler.execute(sender, route.arguments(parsedArguments.stream().map(String.class::cast).toList()));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeSyntaxFeedback(
        final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
        final CommandSyntaxFeedbackRequest request
    ) {
        final CommandSender sender = context.getSource().getSender();
        final String topicPath = String.join(" ", request.topicPath());
        final List<String> usageLines = helpService.resolve(sender, topicPath) instanceof CommandHelpService.TopicResult topic
            ? topic.topic().usageLines()
            : List.of();
        syntaxFeedbackResponder.get().accept(sender, new CommandSyntaxFeedback(request.kind(), topicPath, usageLines));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }
}