package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.StringReader;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

final class CreateCommandOptionParser {

    private static final Pattern SEED_PATTERN = Pattern.compile("[A-Za-z0-9]+");

    CreateCommandOptions parse(final StringReader reader) {
        OptionalLong seed = OptionalLong.empty();
        Optional<String> generator = Optional.empty();
        boolean detached = false;
        while (hasNext(reader)) {
            final String option = readToken(reader);
            switch (option) {
                case "--seed" -> {
                    if (seed.isPresent()) {
                        throw new IllegalArgumentException("Duplicate option: --seed");
                    }
                    final String value = requireValue(reader, option);
                    if (!SEED_PATTERN.matcher(value).matches()) {
                        throw new IllegalArgumentException("Seed must contain only ASCII letters and digits.");
                    }
                    seed = OptionalLong.of(parseSeed(value));
                }
                case "--generator" -> {
                    if (generator.isPresent()) {
                        throw new IllegalArgumentException("Duplicate option: --generator");
                    }
                    generator = Optional.of(requireValue(reader, option));
                }
                case "--detached" -> {
                    if (detached) {
                        throw new IllegalArgumentException("Duplicate option: --detached");
                    }
                    detached = true;
                }
                default -> throw new IllegalArgumentException("Unknown create option: " + option);
            }
        }
        return new CreateCommandOptions(seed, generator, detached);
    }

    private static long parseSeed(final String value) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException ignored) {
            return value.hashCode();
        }
    }

    private static String requireValue(final StringReader reader, final String option) {
        if (!hasNext(reader)) {
            throw new IllegalArgumentException(option + " requires a value.");
        }
        final String value = readToken(reader);
        if (value.startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value.");
        }
        return value;
    }

    private static boolean hasNext(final StringReader reader) {
        skipWhitespace(reader);
        return reader.canRead();
    }

    private static String readToken(final StringReader reader) {
        skipWhitespace(reader);
        final int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        if (reader.getCursor() == start) {
            throw new IllegalArgumentException("Expected command option token.");
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    private static void skipWhitespace(final StringReader reader) {
        while (reader.canRead() && reader.peek() == ' ') {
            reader.skip();
        }
    }
}