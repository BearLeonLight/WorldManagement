package io.github.bearl.worldmanagement.command;

import java.util.Objects;
import org.bukkit.command.CommandSender;

final class CommandHelpService {

    private static final String HELP_ALL_PERMISSION = "worldmanagement.command.help.all";

    private final HelpQueryParser queryParser = new HelpQueryParser();
    private final CommandHelpRenderer renderer;

    CommandHelpService(
        final CommandNodeSpec root,
        final CommandAccessPolicy accessPolicy,
        final int pageSize
    ) {
        this.renderer = new CommandHelpRenderer(root, accessPolicy, pageSize);
    }

    Result resolve(final CommandSender sender, final String query) {
        Objects.requireNonNull(sender, "sender");
        final boolean ignoreCommandPermissions = sender.hasPermission(HELP_ALL_PERMISSION);
        return switch (queryParser.parse(Objects.requireNonNull(query, "query"))) {
            case HelpQueryParser.PageQuery page -> renderer.page(sender, page.page(), ignoreCommandPermissions)
                .<Result>map(PageResult::new)
                .orElseGet(() -> new InvalidPageResult(Integer.toString(page.page())));
            case HelpQueryParser.TopicQuery topic -> renderer.topic(sender, topic.tokens(), ignoreCommandPermissions)
                .<Result>map(TopicResult::new)
                .orElseGet(NotFoundResult::new);
            case HelpQueryParser.InvalidPageQuery invalid -> new InvalidPageResult(invalid.value());
            case HelpQueryParser.InvalidTopicQuery ignored -> new NotFoundResult();
        };
    }

    sealed interface Result permits PageResult, TopicResult, InvalidPageResult, NotFoundResult { }

    record PageResult(CommandHelpRenderer.Page page) implements Result {
        PageResult {
            Objects.requireNonNull(page, "page");
        }
    }

    record TopicResult(CommandHelpRenderer.Topic topic) implements Result {
        TopicResult {
            Objects.requireNonNull(topic, "topic");
        }
    }

    record InvalidPageResult(String value) implements Result {
        InvalidPageResult {
            Objects.requireNonNull(value, "value");
        }
    }

    record NotFoundResult() implements Result { }
}