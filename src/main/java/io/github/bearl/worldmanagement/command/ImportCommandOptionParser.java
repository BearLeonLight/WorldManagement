package io.github.bearl.worldmanagement.command;

import com.mojang.brigadier.StringReader;

final class ImportCommandOptionParser {

    ImportCommandOptions parse(final StringReader reader) {
        boolean detached = false;
        boolean regenerateIdentity = false;
        while (hasNext(reader)) {
            final String option = readToken(reader);
            switch (option) {
                case "--detached" -> {
                    if (detached) {
                        throw new IllegalArgumentException("Duplicate option: --detached");
                    }
                    detached = true;
                }
                case "--regenerate-identity" -> {
                    if (regenerateIdentity) {
                        throw new IllegalArgumentException("Duplicate option: --regenerate-identity");
                    }
                    regenerateIdentity = true;
                }
                default -> throw new IllegalArgumentException("Unknown import option: " + option);
            }
        }
        return new ImportCommandOptions(detached, regenerateIdentity);
    }

    private static boolean hasNext(final StringReader reader) {
        skipWhitespace(reader);
        return reader.canRead();
    }

    private static String readToken(final StringReader reader) {
        skipWhitespace(reader);
        final int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    private static void skipWhitespace(final StringReader reader) {
        while (reader.canRead() && Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
    }
}
