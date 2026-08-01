package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Objects;
import java.util.function.ToIntBiFunction;

final class BrigadierCommandSpecCompiler {

    private final CommandAccessPolicy accessPolicy;
    private final ToIntBiFunction<CommandContext<CommandSourceStack>, CommandExecution> executor;
    private final ToIntBiFunction<CommandContext<CommandSourceStack>, CommandSyntaxFeedbackRequest> feedbackExecutor;

    BrigadierCommandSpecCompiler(
        final CommandAccessPolicy accessPolicy,
        final ToIntBiFunction<CommandContext<CommandSourceStack>, CommandExecution> executor
    ) {
        this(accessPolicy, executor, (context, feedback) -> com.mojang.brigadier.Command.SINGLE_SUCCESS);
    }

    BrigadierCommandSpecCompiler(
        final CommandAccessPolicy accessPolicy,
        final ToIntBiFunction<CommandContext<CommandSourceStack>, CommandExecution> executor,
        final ToIntBiFunction<CommandContext<CommandSourceStack>, CommandSyntaxFeedbackRequest> feedbackExecutor
    ) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.feedbackExecutor = Objects.requireNonNull(feedbackExecutor, "feedbackExecutor");
    }

    com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> compileRoot(
        final CommandNodeSpec root,
        final String label
    ) {
        Objects.requireNonNull(root, "root");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Root label must not be blank.");
        }
        final LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(label);
        builder.executes(feedbackCommand(CommandSyntaxFeedback.Kind.MISSING, java.util.List.of()));
        for (final CommandNodeSpec child : root.children()) {
            builder.then(compile(child, null, java.util.List.of()));
        }
        addInvalidGuard(builder, java.util.List.of());
        return builder.build();
    }

    com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> compileModuleRoot(
        final CommandNodeSpec module,
        final String label
    ) {
        Objects.requireNonNull(module, "module");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Module root label must not be blank.");
        }
        final CommandAccess access = requireConcrete(module.access());
        final LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(label);
        builder.requires(accessPredicate(access));
        module.execution().ifPresent(execution -> builder.executes(context -> executor.applyAsInt(context, execution)));
        if (module.execution().isEmpty() && !module.children().isEmpty()) {
            builder.executes(feedbackCommand(
                CommandSyntaxFeedback.Kind.MISSING,
                java.util.List.of(((CommandNodeSpec.LiteralSegment) module.segment()).value())
            ));
        }
        final java.util.List<String> topicPath = java.util.List.of(((CommandNodeSpec.LiteralSegment) module.segment()).value());
        for (final CommandNodeSpec child : module.children()) {
            builder.then(compile(child, access, topicPath));
        }
        addGuard(builder, module, topicPath);
        return builder.build();
    }

    private ArgumentBuilder<CommandSourceStack, ?> compile(
        final CommandNodeSpec node,
        final CommandAccess inheritedAccess,
        final java.util.List<String> inheritedTopicPath
    ) {
        final CommandAccess access = inheritedAccess == null
            ? requireConcrete(node.access())
            : node.effectiveAccess(inheritedAccess);
        final ArgumentBuilder<CommandSourceStack, ?> builder = switch (node.segment()) {
            case CommandNodeSpec.LiteralSegment literal -> Commands.literal(literal.value());
            case CommandNodeSpec.ArgumentSegment argument -> {
                final com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> argumentBuilder = Commands.argument(
                    argument.name(),
                    argument.kind() == CommandArgumentKind.GREEDY_STRING
                    ? com.mojang.brigadier.arguments.StringArgumentType.greedyString()
                    : com.mojang.brigadier.arguments.StringArgumentType.word()
                );
                node.suggestions().ifPresent(argumentBuilder::suggests);
                yield argumentBuilder;
            }
        };
        final java.util.List<String> topicPath;
        if (node.segment() instanceof CommandNodeSpec.LiteralSegment literal) {
            final java.util.ArrayList<String> values = new java.util.ArrayList<>(inheritedTopicPath);
            values.add(literal.value());
            topicPath = java.util.List.copyOf(values);
        } else {
            topicPath = inheritedTopicPath;
        }
        builder.requires(accessPredicate(access));
        node.execution().ifPresent(execution -> builder.executes(context -> executor.applyAsInt(context, execution)));
        if (node.execution().isEmpty() && !node.children().isEmpty()) {
            builder.executes(feedbackCommand(CommandSyntaxFeedback.Kind.MISSING, topicPath));
        }
        for (final CommandNodeSpec child : node.children()) {
            builder.then(compile(child, access, topicPath));
        }
        addGuard(builder, node, topicPath);
        return builder;
    }

    private void addGuard(
        final ArgumentBuilder<CommandSourceStack, ?> builder,
        final CommandNodeSpec node,
        final java.util.List<String> topicPath
    ) {
        if (node.segment() instanceof CommandNodeSpec.ArgumentSegment argument
            && argument.kind() == CommandArgumentKind.GREEDY_STRING) {
            return;
        }
        if (node.children().isEmpty() && node.execution().isPresent()) {
            addExtraGuard(builder, topicPath);
            return;
        }
        if (!node.children().isEmpty() && node.children().stream().allMatch(child ->
            child.segment() instanceof CommandNodeSpec.LiteralSegment
        )) {
            addInvalidGuard(builder, topicPath);
        }
    }

    private void addInvalidGuard(
        final ArgumentBuilder<CommandSourceStack, ?> builder,
        final java.util.List<String> topicPath
    ) {
        builder.then(Commands.argument("__wm_invalid", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
            .executes(feedbackCommand(CommandSyntaxFeedback.Kind.INVALID, topicPath)));
    }

    private void addExtraGuard(
        final ArgumentBuilder<CommandSourceStack, ?> builder,
        final java.util.List<String> topicPath
    ) {
        builder.then(Commands.argument("__wm_extra", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
            .executes(feedbackCommand(CommandSyntaxFeedback.Kind.EXTRA, topicPath)));
    }

    private GeneratedSyntaxFeedbackCommand feedbackCommand(
        final CommandSyntaxFeedback.Kind kind,
        final java.util.List<String> topicPath
    ) {
        return new GeneratedSyntaxFeedbackCommand(
            feedbackExecutor,
            new CommandSyntaxFeedbackRequest(kind, topicPath)
        );
    }

    private java.util.function.Predicate<CommandSourceStack> accessPredicate(final CommandAccess access) {
        final String[] permissions = access.permissions().toArray(String[]::new);
        return switch (access.permissionMode()) {
            case HELP -> accessPolicy.helpCommand();
            case ANY -> accessPolicy.anyCommand(access.module(), permissions);
            case ALL -> accessPolicy.allCommands(access.module(), permissions);
            case INHERIT -> throw new IllegalArgumentException("Root command child must declare concrete access.");
        };
    }

    private static CommandAccess requireConcrete(final CommandAccess access) {
        if (access.permissionMode() == CommandPermissionMode.INHERIT) {
            throw new IllegalArgumentException("Root command child must declare concrete access.");
        }
        return access;
    }
}