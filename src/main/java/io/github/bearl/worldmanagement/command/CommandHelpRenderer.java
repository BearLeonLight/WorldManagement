package io.github.bearl.worldmanagement.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.bukkit.command.CommandSender;

final class CommandHelpRenderer {

    private final CommandNodeSpec root;
    private final CommandAccessPolicy accessPolicy;
    private final int pageSize;

    CommandHelpRenderer(
        final CommandNodeSpec root,
        final CommandAccessPolicy accessPolicy,
        final int pageSize
    ) {
        this.root = Objects.requireNonNull(root, "root");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        if (pageSize < 1) {
            throw new IllegalArgumentException("Help page size must be positive.");
        }
        this.pageSize = pageSize;
    }

    Optional<Page> page(
        final CommandSender sender,
        final int requestedPage,
        final boolean ignoreCommandPermissions
    ) {
        final List<Topic> topics = visibleLiteralChildren(
            root,
            null,
            List.of(),
            sender,
            ignoreCommandPermissions
        );
        final int pageCount = Math.max(1, (topics.size() + pageSize - 1) / pageSize);
        if (requestedPage < 1 || requestedPage > pageCount) {
            return Optional.empty();
        }
        final int fromIndex = (requestedPage - 1) * pageSize;
        final int toIndex = Math.min(fromIndex + pageSize, topics.size());
        return Optional.of(new Page(requestedPage, pageCount, topics.subList(fromIndex, toIndex)));
    }

    Optional<Topic> topic(
        final CommandSender sender,
        final List<String> query,
        final boolean ignoreCommandPermissions
    ) {
        Objects.requireNonNull(query, "query");
        if (query.isEmpty()) {
            return Optional.empty();
        }
        CommandNodeSpec current = root;
        CommandAccess inheritedAccess = null;
        final List<String> path = new ArrayList<>();
        for (final String token : query) {
            final String literal = Objects.requireNonNull(token, "query token").toLowerCase(Locale.ROOT);
            final Optional<CommandNodeSpec> child = current.children().stream()
                .filter(node -> node.segment() instanceof CommandNodeSpec.LiteralSegment segment
                    && segment.value().equalsIgnoreCase(literal))
                .findFirst();
            if (child.isEmpty()) {
                return Optional.empty();
            }
            current = child.orElseThrow();
            inheritedAccess = inheritedAccess == null
                ? requireConcrete(current.access())
                : current.effectiveAccess(inheritedAccess);
            if (!accessPolicy.visible(inheritedAccess, sender, ignoreCommandPermissions)) {
                return Optional.empty();
            }
            path.add(((CommandNodeSpec.LiteralSegment) current.segment()).value());
        }
        return Optional.of(toTopic(current, inheritedAccess, path, sender, ignoreCommandPermissions));
    }

    CompletableFuture<Suggestions> suggest(
        final CommandSender sender,
        final String input,
        final SuggestionsBuilder builder,
        final boolean ignoreCommandPermissions
    ) {
        final PartialQuery query = partialQuery(input);
        if (query == null || !query.completed().isEmpty() && query.completed().getFirst().chars().allMatch(Character::isDigit)) {
            return builder.buildFuture();
        }
        CommandNodeSpec current = root;
        CommandAccess inheritedAccess = null;
        for (final String token : query.completed()) {
            final Optional<CommandNodeSpec> child = current.children().stream()
                .filter(node -> node.segment() instanceof CommandNodeSpec.LiteralSegment literal
                    && literal.value().equalsIgnoreCase(token))
                .findFirst();
            if (child.isEmpty()) {
                return builder.buildFuture();
            }
            current = child.orElseThrow();
            inheritedAccess = inheritedAccess == null
                ? requireConcrete(current.access())
                : current.effectiveAccess(inheritedAccess);
            if (!accessPolicy.visible(inheritedAccess, sender, ignoreCommandPermissions)) {
                return builder.buildFuture();
            }
        }
        final String prefix = query.prefix().toLowerCase(Locale.ROOT);
        final String parentPath = query.completed().isEmpty() ? "" : String.join(" ", query.completed()) + ' ';
        for (final CommandNodeSpec child : current.children()) {
            if (!(child.segment() instanceof CommandNodeSpec.LiteralSegment literal)
                || !literal.value().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            final CommandAccess access = inheritedAccess == null
                ? requireConcrete(child.access())
                : child.effectiveAccess(inheritedAccess);
            if (accessPolicy.visible(access, sender, ignoreCommandPermissions)) {
                builder.suggest(parentPath + literal.value());
            }
        }
        return builder.buildFuture();
    }

    private static PartialQuery partialQuery(final String input) {
        final StringReader reader = new StringReader(Objects.requireNonNull(input, "input"));
        final List<String> tokens = new ArrayList<>();
        try {
            while (true) {
                reader.skipWhitespace();
                if (!reader.canRead()) {
                    break;
                }
                tokens.add(reader.readString());
            }
        } catch (final CommandSyntaxException exception) {
            return null;
        }
        final boolean trailingWhitespace = !input.isEmpty() && Character.isWhitespace(input.charAt(input.length() - 1));
        if (tokens.isEmpty() || trailingWhitespace) {
            return new PartialQuery(tokens, "");
        }
        return new PartialQuery(tokens.subList(0, tokens.size() - 1), tokens.getLast());
    }

    private List<Topic> visibleLiteralChildren(
        final CommandNodeSpec parent,
        final CommandAccess inheritedAccess,
        final List<String> parentPath,
        final CommandSender sender,
        final boolean ignoreCommandPermissions
    ) {
        final List<Topic> result = new ArrayList<>();
        for (final CommandNodeSpec child : parent.children()) {
            if (!(child.segment() instanceof CommandNodeSpec.LiteralSegment literal)) {
                continue;
            }
            final CommandAccess access = inheritedAccess == null
                ? requireConcrete(child.access())
                : child.effectiveAccess(inheritedAccess);
            if (!accessPolicy.visible(access, sender, ignoreCommandPermissions)) {
                continue;
            }
            final List<String> path = new ArrayList<>(parentPath);
            path.add(literal.value());
            result.add(toTopic(child, access, path, sender, ignoreCommandPermissions));
        }
        return List.copyOf(result);
    }

    private Topic toTopic(
        final CommandNodeSpec node,
        final CommandAccess access,
        final List<String> path,
        final CommandSender sender,
        final boolean ignoreCommandPermissions
    ) {
        final List<String> children = visibleLiteralChildren(
            node,
            access,
            path,
            sender,
            ignoreCommandPermissions
        ).stream().map(Topic::path).toList();
        return new Topic(
            String.join(" ", path),
            node.descriptionKey(),
            visibleUsageLines(node, access, path.subList(0, path.size() - 1), sender, ignoreCommandPermissions),
            children,
            access.permissions()
        );
    }

    private List<String> visibleUsageLines(
        final CommandNodeSpec node,
        final CommandAccess access,
        final List<String> parentPath,
        final CommandSender sender,
        final boolean ignoreCommandPermissions
    ) {
        final List<String> result = new ArrayList<>();
        final String prefix = parentPath.isEmpty() ? "/wm" : "/wm " + String.join(" ", parentPath);
        collectVisibleUsage(node, access, prefix, sender, ignoreCommandPermissions, result);
        return List.copyOf(result);
    }

    private void collectVisibleUsage(
        final CommandNodeSpec node,
        final CommandAccess access,
        final String prefix,
        final CommandSender sender,
        final boolean ignoreCommandPermissions,
        final List<String> result
    ) {
        final String path = prefix + ' ' + node.displaySegment();
        if (node.execution().isPresent()) {
            result.add(path);
        }
        for (final CommandNodeSpec child : node.children()) {
            final CommandAccess childAccess = child.effectiveAccess(access);
            if (accessPolicy.visible(childAccess, sender, ignoreCommandPermissions)) {
                collectVisibleUsage(child, childAccess, path, sender, ignoreCommandPermissions, result);
            }
        }
    }

    private static CommandAccess requireConcrete(final CommandAccess access) {
        if (access.permissionMode() == CommandPermissionMode.INHERIT) {
            throw new IllegalArgumentException("Top-level help topic must declare concrete access.");
        }
        return access;
    }

    record Page(int page, int pageCount, List<Topic> topics) {

        Page {
            topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
        }
    }

    record Topic(
        String path,
        String descriptionKey,
        List<String> usageLines,
        List<String> children,
        List<String> permissions
    ) {

        Topic {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(descriptionKey, "descriptionKey");
            usageLines = List.copyOf(Objects.requireNonNull(usageLines, "usageLines"));
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        }
    }

    private record PartialQuery(List<String> completed, String prefix) {
        private PartialQuery {
            completed = List.copyOf(completed);
        }
    }
}