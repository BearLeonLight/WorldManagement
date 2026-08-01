package io.github.bearl.worldmanagement.command;

import java.util.List;
import java.util.Objects;

record CommandSyntaxFeedbackRequest(CommandSyntaxFeedback.Kind kind, List<String> topicPath) {

    CommandSyntaxFeedbackRequest {
        Objects.requireNonNull(kind, "kind");
        topicPath = List.copyOf(Objects.requireNonNull(topicPath, "topicPath"));
    }
}