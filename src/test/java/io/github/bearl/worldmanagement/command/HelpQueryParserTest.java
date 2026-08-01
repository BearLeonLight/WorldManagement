package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class HelpQueryParserTest {

    private final HelpQueryParser parser = new HelpQueryParser();

    @Test
    void blankQuerySelectsTheFirstPage() {
        assertEquals(new HelpQueryParser.PageQuery(1), parser.parse("   "));
    }

    @Test
    void oneBasedPageAndFullCommandPathRemainDistinct() {
        assertEquals(new HelpQueryParser.PageQuery(2), parser.parse("  2  "));
        assertEquals(
            new HelpQueryParser.TopicQuery(List.of("ownership", "rank", "set")),
            parser.parse(" ownership   rank set ")
        );
    }

    @Test
    void overflowingNumericPageIsInvalidInsteadOfBecomingATopic() {
        assertEquals(
            new HelpQueryParser.InvalidPageQuery("999999999999999999999"),
            parser.parse("999999999999999999999")
        );
    }
}