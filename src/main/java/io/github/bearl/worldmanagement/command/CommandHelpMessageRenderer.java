package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.core.MessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;

final class CommandHelpMessageRenderer {

    private final MessageService messages;

    CommandHelpMessageRenderer(final MessageService messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    List<Component> render(final CommandHelpService.Result result) {
        return switch (Objects.requireNonNull(result, "result")) {
            case CommandHelpService.PageResult page -> renderPage(page.page());
            case CommandHelpService.TopicResult topic -> renderTopic(topic.topic());
            case CommandHelpService.InvalidPageResult invalid -> List.of(messages.component(
                "command.help.invalid-page", "page", invalid.value()
            ));
            case CommandHelpService.NotFoundResult ignored -> List.of(messages.component("command.help.not-found"));
        };
    }

    private List<Component> renderPage(final CommandHelpRenderer.Page page) {
        final List<Component> result = new ArrayList<>();
        result.add(messages.component(
            "command.help.page-header",
            "page", Integer.toString(page.page()),
            "pages", Integer.toString(page.pageCount())
        ));
        for (final CommandHelpRenderer.Topic topic : page.topics()) {
            result.add(messages.component("command.help.page-entry", Map.of(
                "topic", Component.text(topic.path()),
                "description", messages.component(topic.descriptionKey())
            )));
        }
        return List.copyOf(result);
    }

    private List<Component> renderTopic(final CommandHelpRenderer.Topic topic) {
        final List<Component> result = new ArrayList<>();
        result.add(messages.component("command.help.topic-header", Map.of(
            "topic", Component.text(topic.path()),
            "description", messages.component(topic.descriptionKey())
        )));
        topic.usageLines().forEach(usage -> result.add(messages.component("command.help.usage", "usage", usage)));
        if (!topic.children().isEmpty()) {
            result.add(messages.component("command.help.children", "children", String.join(", ", topic.children())));
        }
        if (!topic.permissions().isEmpty()) {
            result.add(messages.component("command.help.permissions", "permissions", String.join(", ", topic.permissions())));
        }
        return List.copyOf(result);
    }
}