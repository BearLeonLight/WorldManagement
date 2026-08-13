package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class ImportCommandOptionsArgument implements CustomArgumentType<ImportCommandOptions, String> {

    private static final SimpleCommandExceptionType INVALID_OPTIONS =
        new SimpleCommandExceptionType(new LiteralMessage("Invalid import options."));
    private static final List<String> OPTION_KEYS = List.of("--detached", "--regenerate-identity");
    private final ImportCommandOptionParser parser = new ImportCommandOptionParser();

    @Override
    public ImportCommandOptions parse(final StringReader reader) throws CommandSyntaxException {
        try {
            return parser.parse(reader);
        } catch (final IllegalArgumentException exception) {
            throw INVALID_OPTIONS.create();
        }
    }

    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(
        final CommandContext<S> context,
        final SuggestionsBuilder builder
    ) {
        final String remaining = builder.getRemaining();
        final boolean trailingSpace = !remaining.isEmpty() && remaining.endsWith(" ");
        final List<String> tokens = java.util.Arrays.stream(remaining.trim().split(" +"))
            .filter(token -> !token.isEmpty())
            .toList();
        final Set<String> used = Set.copyOf(trailingSpace ? tokens : tokens.stream()
            .limit(Math.max(0, tokens.size() - 1L)).toList());
        if (used.stream().anyMatch(option -> !OPTION_KEYS.contains(option)) || used.size() < (trailingSpace ? tokens.size() : Math.max(0, tokens.size() - 1))) {
            return builder.buildFuture();
        }
        final String current = trailingSpace || tokens.isEmpty() ? "" : tokens.getLast();
        final int currentStart = trailingSpace || tokens.isEmpty()
            ? remaining.length()
            : remaining.lastIndexOf(current);
        final SuggestionsBuilder currentBuilder = builder.createOffset(builder.getStart() + currentStart);
        final String prefix = current.toLowerCase(Locale.ROOT);
        OPTION_KEYS.stream()
            .filter(option -> !used.contains(option))
            .filter(option -> option.startsWith(prefix))
            .forEach(currentBuilder::suggest);
        return currentBuilder.buildFuture();
    }
}
