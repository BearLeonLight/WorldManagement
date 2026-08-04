package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class CreateCommandOptionsArgument implements CustomArgumentType<CreateCommandOptions, String> {

    private static final SimpleCommandExceptionType INVALID_OPTIONS =
        new SimpleCommandExceptionType(new LiteralMessage("Invalid create options."));
    private static final List<String> OPTION_KEYS = List.of(
        "--biome", "--detached", "--force-spawn-position", "--generate-bonus-chest",
        "--generator", "--generator-settings", "--no-structures", "--seed"
    );
    private static final Set<String> VALUE_OPTIONS = Set.of(
        "--biome", "--force-spawn-position", "--generator", "--generator-settings", "--seed"
    );

    private final CreateCommandOptionParser parser = new CreateCommandOptionParser();
    private final Supplier<? extends Collection<String>> generatorPlugins;
    private final Supplier<? extends Collection<String>> biomeProviderPlugins;

    CreateCommandOptionsArgument(final Supplier<? extends Collection<String>> generatorPlugins) {
        this(generatorPlugins, generatorPlugins);
    }

    CreateCommandOptionsArgument(
        final Supplier<? extends Collection<String>> generatorPlugins,
        final Supplier<? extends Collection<String>> biomeProviderPlugins
    ) {
        this.generatorPlugins = java.util.Objects.requireNonNull(generatorPlugins, "generatorPlugins");
        this.biomeProviderPlugins = java.util.Objects.requireNonNull(
            biomeProviderPlugins, "biomeProviderPlugins"
        );
    }

    @Override
    public CreateCommandOptions parse(final StringReader reader) throws CommandSyntaxException {
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
        final PartialOptions partial = PartialOptions.parse(builder.getRemaining());
        if (!partial.valid()) {
            return builder.buildFuture();
        }
        final SuggestionsBuilder current = builder.createOffset(builder.getStart() + partial.currentStart());
        final Collection<String> candidates;
        if (partial.expectedValue() == null) {
            candidates = OPTION_KEYS.stream().filter(key -> !partial.used().contains(key)).toList();
        } else if (partial.expectedValue().equals("--generator")) {
            candidates = generatorPlugins.get();
        } else if (partial.expectedValue().equals("--biome")) {
            candidates = biomeProviderPlugins.get();
        } else {
            candidates = List.of();
        }
        final String prefix = partial.current().toLowerCase(Locale.ROOT);
        candidates.stream()
            .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
            .forEach(current::suggest);
        return current.buildFuture();
    }

    private record PartialOptions(Set<String> used, String expectedValue, String current, int currentStart, boolean valid) {

        private static PartialOptions parse(final String input) {
            final List<Token> tokens = tokens(input);
            final boolean trailingSpace = !input.isEmpty() && input.charAt(input.length() - 1) == ' ';
            final List<Token> completed = trailingSpace ? tokens : tokens.subList(0, Math.max(0, tokens.size() - 1));
            final String current = trailingSpace || tokens.isEmpty() ? "" : tokens.getLast().value();
            final int currentStart = trailingSpace || tokens.isEmpty() ? input.length() : tokens.getLast().start();
            final Set<String> used = new HashSet<>();
            String expectedValue = null;
            for (final Token token : completed) {
                if (expectedValue != null) {
                    if (token.value().startsWith("--")) {
                        return new PartialOptions(Set.of(), null, current, currentStart, false);
                    }
                    expectedValue = null;
                    continue;
                }
                if (!OPTION_KEYS.contains(token.value()) || !used.add(token.value())) {
                    return new PartialOptions(Set.of(), null, current, currentStart, false);
                }
                if (VALUE_OPTIONS.contains(token.value())) {
                    expectedValue = token.value();
                }
            }
            return new PartialOptions(Set.copyOf(used), expectedValue, current, currentStart, true);
        }

        private static List<Token> tokens(final String input) {
            final List<Token> result = new ArrayList<>();
            int cursor = 0;
            while (cursor < input.length()) {
                while (cursor < input.length() && input.charAt(cursor) == ' ') {
                    cursor++;
                }
                if (cursor >= input.length()) {
                    break;
                }
                final int start = cursor;
                while (cursor < input.length() && input.charAt(cursor) != ' ') {
                    cursor++;
                }
                result.add(new Token(input.substring(start, cursor), start));
            }
            return List.copyOf(result);
        }
    }

    private record Token(String value, int start) { }
}