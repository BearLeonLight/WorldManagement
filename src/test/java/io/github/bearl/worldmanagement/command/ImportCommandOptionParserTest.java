package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ImportCommandOptionParserTest {

    private final ImportCommandOptionParser parser = new ImportCommandOptionParser();

    @Test
    void acceptsFlagsInEitherOrder() {
        assertEquals(
            new ImportCommandOptions(true, true),
            parser.parse(new StringReader("--detached --regenerate-identity"))
        );
        assertEquals(
            new ImportCommandOptions(true, true),
            parser.parse(new StringReader("--regenerate-identity --detached"))
        );
        assertEquals(ImportCommandOptions.defaults(), parser.parse(new StringReader("")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "--detached --detached",
        "--regenerate-identity --regenerate-identity",
        "--unknown",
        "loose"
    })
    void rejectsDuplicateAndUnknownFlags(final String input) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new StringReader(input)));
    }
}