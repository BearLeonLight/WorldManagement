package io.github.bearl.worldmanagement.command;

import java.util.List;
import java.util.Objects;

record CommandSyntaxFeedback(Kind kind, String topicPath, List<String> usageLines) {

    CommandSyntaxFeedback {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(topicPath, "topicPath");
        usageLines = List.copyOf(Objects.requireNonNull(usageLines, "usageLines"));
    }

    enum Kind {
        MISSING,
        INVALID,
        EXTRA
    }
}