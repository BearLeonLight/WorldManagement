package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class HelpQueryParser {

    Query parse(final String input) {
        final StringReader reader = new StringReader(Objects.requireNonNull(input, "input"));
        final List<String> tokens = new ArrayList<>();
        try {
            while (true) {
                reader.skipWhitespace();
                if (!reader.canRead()) {
                    break;
                }
                tokens.add(reader.readString());
            }
        } catch (final CommandSyntaxException exception) {
            return new InvalidTopicQuery(input);
        }
        if (tokens.isEmpty()) {
            return new PageQuery(1);
        }
        if (tokens.size() == 1 && tokens.getFirst().chars().allMatch(Character::isDigit)) {
            try {
                final int page = Integer.parseInt(tokens.getFirst());
                return page < 1 ? new InvalidPageQuery(tokens.getFirst()) : new PageQuery(page);
            } catch (final NumberFormatException exception) {
                return new InvalidPageQuery(tokens.getFirst());
            }
        }
        return new TopicQuery(tokens);
    }

    sealed interface Query permits PageQuery, TopicQuery, InvalidPageQuery, InvalidTopicQuery { }

    record PageQuery(int page) implements Query { }

    record TopicQuery(List<String> tokens) implements Query {
        TopicQuery {
            tokens = List.copyOf(tokens);
        }
    }

    record InvalidPageQuery(String value) implements Query { }

    record InvalidTopicQuery(String value) implements Query { }
}