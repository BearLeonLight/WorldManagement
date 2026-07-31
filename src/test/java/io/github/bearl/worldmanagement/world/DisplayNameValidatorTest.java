package io.github.bearl.worldmanagement.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DisplayNameValidatorTest {

    private final DisplayNameValidator validator = new DisplayNameValidator();

    @Test
    void acceptsRestrictedFormattingUnicodeAndOrdinarySpaces() {
        final ValidatedDisplayName displayName = validator.validate(
            "<gradient:#12ab34:#abcdef><bold>創意 世界</bold></gradient>"
        );

        assertEquals("創意 世界", displayName.plainText());
    }

    @Test
    void acceptsRawAndPlainTextCodePointBoundaries() {
        validator.validate("界".repeat(64));
        validator.validate("<red></red>".repeat(45) + "<b></b><i></i>界世名");

        assertThrows(IllegalArgumentException.class, () -> validator.validate("界".repeat(65)));
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate("<red></red>".repeat(45) + "<b></b><i></i>界世名稱"));
    }

    @Test
    void rejectsBlankFormattingOnlyAndControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("   "));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("<red></red>"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("creative\nworld"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("creative\u0000world"));
    }

    @Test
    void rejectsInteractiveDataAndUnknownTags() {
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate("<click:run_command:'/op'>creative</click>"));
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate("<hover:show_text:'secret'>creative</hover>"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("<keybind:key.jump>creative</keybind>"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("<unknown>creative</unknown>"));
    }
}