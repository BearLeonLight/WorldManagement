package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CreateCommandOptionParserTest {

    private final CreateCommandOptionParser parser = new CreateCommandOptionParser();

    @Test
    void acceptsOptionsInEitherOrder() {
        final CreateCommandOptions seedFirst = parse("--seed MySeed123 --generator Terra:normal");
        final CreateCommandOptions generatorFirst = parse("--generator Terra:normal --seed MySeed123");

        assertEquals(seedFirst, generatorFirst);
        assertEquals(OptionalLong.of("MySeed123".hashCode()), seedFirst.seed());
        assertEquals(Optional.of("Terra:normal"), seedFirst.generator());
    }

    @Test
    void parsesNumericSeedAndAllowsNoOptions() {
        assertEquals(OptionalLong.of(12345L), parse("--seed 12345").seed());
        assertEquals(CreateCommandOptions.defaults(), parse(""));
    }

    @Test
    void hashesOverflowingNumericSeed() {
        final String seed = "999999999999999999999999999999";

        assertEquals(OptionalLong.of(seed.hashCode()), parse("--seed " + seed).seed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "--seed first --seed second",
        "--generator Terra --generator Iris",
        "--unknown value",
        "--seed",
        "--generator",
        "loose",
        "42",
        "--seed invalid_seed",
        "--seed -42",
        "--seed value!",
        "--seed=value",
        "-s value"
    })
    void rejectsInvalidOptionTails(final String input) {
        assertThrows(IllegalArgumentException.class, () -> parse(input));
    }

    @Test
    void brigadierErrorDoesNotExposeRejectedOptionText() {
        final CreateCommandOptionsArgument argument = new CreateCommandOptionsArgument(java.util.List::of);

        final CommandSyntaxException exception = assertThrows(
            CommandSyntaxException.class,
            () -> argument.parse(new StringReader("--unknown secret-value"))
        );

        assertEquals("Invalid create options.", exception.getRawMessage().getString());
        assertFalse(exception.getMessage().contains("--unknown"));
        assertFalse(exception.getMessage().contains("secret-value"));
    }

    private CreateCommandOptions parse(final String input) {
        return parser.parse(new StringReader(input));
    }
}