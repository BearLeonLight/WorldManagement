package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Objects;
import java.util.function.ToIntBiFunction;

final class GeneratedSyntaxFeedbackCommand implements Command<CommandSourceStack> {

    private final ToIntBiFunction<CommandContext<CommandSourceStack>, CommandSyntaxFeedbackRequest> executor;
    private final CommandSyntaxFeedbackRequest request;

    GeneratedSyntaxFeedbackCommand(
        final ToIntBiFunction<CommandContext<CommandSourceStack>, CommandSyntaxFeedbackRequest> executor,
        final CommandSyntaxFeedbackRequest request
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.request = Objects.requireNonNull(request, "request");
    }

    @Override
    public int run(final CommandContext<CommandSourceStack> context) {
        return executor.applyAsInt(context, request);
    }
}