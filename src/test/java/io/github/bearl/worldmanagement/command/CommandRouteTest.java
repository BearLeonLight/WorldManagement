package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class CommandRouteTest {

    @Test
    void combinesCanonicalPrefixParsedArgumentsAndFixedSuffix() {
        final CommandRoute route = new CommandRoute(List.of("storage", "migrate"), List.of("confirm"));

        assertArrayEquals(new String[] {"storage", "migrate", "YAML", "SQLITE", "confirm"}, route.arguments(List.of("YAML", "SQLITE")));
    }

    @Test
    void placesRemovePurgeLiteralsAfterWorldArgument() {
        final CommandRoute route = new CommandRoute(List.of("remove"), List.of("purge", "confirm"));

        assertArrayEquals(new String[] {"remove", "archive", "purge", "confirm"}, route.arguments(List.of("archive")));
    }
}