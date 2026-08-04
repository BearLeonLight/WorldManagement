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
        Optional<String> generatorSettings = Optional.empty();
        boolean generateStructures = true;
        boolean structuresConfigured = false;
        boolean bonusChest = false;
        Optional<String> biomeProvider = Optional.empty();
        Optional<io.github.bearl.worldmanagement.world.lifecycle.WorldSpawnPosition> forcedSpawnPosition = Optional.empty();
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
                case "--generator-settings" -> {
                    if (generatorSettings.isPresent()) {
                        throw new IllegalArgumentException("Duplicate option: --generator-settings");
                    }
                    generatorSettings = Optional.of(requireValue(reader, option));
                }
                case "--no-structures" -> {
                    if (structuresConfigured) {
                        throw new IllegalArgumentException("Duplicate option: --no-structures");
                    }
                    generateStructures = false;
                    structuresConfigured = true;
                }
                case "--generate-bonus-chest" -> {
                    if (bonusChest) {
                        throw new IllegalArgumentException("Duplicate option: --generate-bonus-chest");
                    }
                    bonusChest = true;
                }
                case "--biome" -> {
                    if (biomeProvider.isPresent()) {
                        throw new IllegalArgumentException("Duplicate option: --biome");
                    }
                    biomeProvider = Optional.of(requireValue(reader, option));
                }
                case "--force-spawn-position" -> {
                    if (forcedSpawnPosition.isPresent()) {
                        throw new IllegalArgumentException("Duplicate option: --force-spawn-position");
                    }
                    forcedSpawnPosition = Optional.of(parseSpawnPosition(requireValue(reader, option)));
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
        return new CreateCommandOptions(
            seed, generator, generatorSettings, generateStructures, bonusChest,
            biomeProvider, forcedSpawnPosition, detached
        );
    }

    private static io.github.bearl.worldmanagement.world.lifecycle.WorldSpawnPosition parseSpawnPosition(
        final String value
    ) {
        final String[] parts = value.split(",", -1);
        if (parts.length != 3 && parts.length != 5) {
            throw new IllegalArgumentException("Spawn position must use x,y,z or x,y,z,yaw,pitch.");
        }
        try {
            return new io.github.bearl.worldmanagement.world.lifecycle.WorldSpawnPosition(
                Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                parts.length == 5 ? Float.parseFloat(parts[3]) : 0.0f,
                parts.length == 5 ? Float.parseFloat(parts[4]) : 0.0f
            );
        } catch (final NumberFormatException failure) {
            throw new IllegalArgumentException("Spawn position contains an invalid number.", failure);
        }
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
        if (!reader.canRead()) {
            throw new IllegalArgumentException("Expected command option token.");
        }
        if (reader.peek() != '"' && reader.peek() != '\'') {
            final int start = reader.getCursor();
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
                reader.skip();
            }
            return reader.getString().substring(start, reader.getCursor());
        }
        try {
            return reader.readString();
        } catch (final com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalArgumentException("Invalid quoted command option token.", failure);
        }
    }

    private static void skipWhitespace(final StringReader reader) {
        while (reader.canRead() && Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
    }
}