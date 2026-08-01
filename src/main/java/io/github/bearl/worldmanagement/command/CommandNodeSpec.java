package io.github.bearl.worldmanagement.command;

import java.util.ArrayList;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.mojang.brigadier.arguments.ArgumentType;

record CommandNodeSpec(
    String id,
    Segment segment,
    CommandAccess access,
    Optional<SuggestionProvider<CommandSourceStack>> suggestions,
    Optional<CommandExecution> execution,
    List<CommandNodeSpec> children
) {

    CommandNodeSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Command node id must not be blank.");
        }
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(suggestions, "suggestions");
        Objects.requireNonNull(execution, "execution");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        validateSiblings(children);
    }

    static CommandNodeSpec root(final List<CommandNodeSpec> children) {
        final CommandNodeSpec root = new CommandNodeSpec(
            "root",
            new LiteralSegment("wm"),
            CommandAccess.inherit(),
            Optional.empty(),
            Optional.empty(),
            children
        );
        final Set<String> ids = new HashSet<>();
        collectIds(root, ids);
        return root;
    }

    static CommandNodeSpec literal(
        final String id,
        final String literal,
        final CommandAccess access,
        final List<CommandNodeSpec> children
    ) {
        return new CommandNodeSpec(id, new LiteralSegment(literal), access, Optional.empty(), Optional.empty(), children);
    }

    static CommandNodeSpec argument(
        final String id,
        final String name,
        final CommandArgumentKind kind,
        final List<CommandNodeSpec> children
    ) {
        return new CommandNodeSpec(
            id,
            new ArgumentSegment(name, kind),
            CommandAccess.inherit(),
            Optional.empty(),
            Optional.empty(),
            children
        );
    }

    static CommandNodeSpec typedArgument(
        final String id,
        final String name,
        final ArgumentType<?> type,
        final String display,
        final List<CommandNodeSpec> children
    ) {
        return new CommandNodeSpec(
            id,
            new TypedArgumentSegment(name, type, display),
            CommandAccess.inherit(),
            Optional.empty(),
            Optional.empty(),
            children
        );
    }

    CommandNodeSpec suggests(final SuggestionProvider<CommandSourceStack> provider) {
        if (!(segment instanceof ArgumentSegment)) {
            throw new IllegalStateException("Only argument nodes can declare suggestions.");
        }
        return new CommandNodeSpec(id, segment, access, Optional.of(Objects.requireNonNull(provider, "provider")), execution, children);
    }

    CommandNodeSpec executes(final CommandRoute route, final List<String> argumentNames) {
        return executesTyped(
            route,
            Objects.requireNonNull(argumentNames, "argumentNames").stream()
                .map(name -> new CommandArgumentBinding(name, String.class))
                .toList()
        );
    }

    CommandNodeSpec executesTyped(final CommandRoute route, final List<CommandArgumentBinding> arguments) {
        return new CommandNodeSpec(
            id,
            segment,
            access,
            suggestions,
            Optional.of(new CommandExecution(route, arguments)),
            children
        );
    }

    CommandNodeSpec withChildren(final List<CommandNodeSpec> replacementChildren) {
        return new CommandNodeSpec(id, segment, access, suggestions, execution, replacementChildren);
    }

    CommandAccess effectiveAccess(final CommandAccess parentAccess) {
        Objects.requireNonNull(parentAccess, "parentAccess");
        return access.permissionMode() == CommandPermissionMode.INHERIT ? parentAccess : access;
    }

    String descriptionKey() {
        return "command.help.topic." + id + ".description";
    }

    List<String> usageLines(final String rootLabel) {
        return usageLines(rootLabel, List.of());
    }

    List<String> usageLines(final String rootLabel, final List<String> parentPath) {
        if (rootLabel == null || rootLabel.isBlank()) {
            throw new IllegalArgumentException("Root label must not be blank.");
        }
        Objects.requireNonNull(parentPath, "parentPath");
        final List<String> usages = new ArrayList<>();
        final String prefix = parentPath.isEmpty()
            ? "/" + rootLabel
            : "/" + rootLabel + ' ' + String.join(" ", parentPath);
        collectUsage(this, prefix, usages);
        return List.copyOf(usages);
    }

    String displaySegment() {
        return segment.display();
    }

    private static void collectUsage(
        final CommandNodeSpec node,
        final String prefix,
        final List<String> usages
    ) {
        final String path = prefix + ' ' + node.displaySegment();
        if (node.execution.isPresent()) {
            usages.add(path);
        }
        node.children.forEach(child -> collectUsage(child, path, usages));
    }

    private static void validateSiblings(final List<CommandNodeSpec> children) {
        final Set<String> segments = new HashSet<>();
        for (final CommandNodeSpec child : children) {
            if (!segments.add(child.segment.identity())) {
                throw new IllegalArgumentException("Duplicate sibling command segment: " + child.segment.identity());
            }
        }
    }

    private static void collectIds(final CommandNodeSpec node, final Set<String> ids) {
        if (!ids.add(node.id)) {
            throw new IllegalArgumentException("Duplicate command node id: " + node.id);
        }
        node.children.forEach(child -> collectIds(child, ids));
    }

    sealed interface Segment permits LiteralSegment, ArgumentSegment, TypedArgumentSegment {

        String identity();

        String display();
    }

    record LiteralSegment(String value) implements Segment {

        LiteralSegment {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Literal value must not be blank.");
            }
        }

        @Override
        public String identity() {
            return "literal:" + value;
        }

        @Override
        public String display() {
            return value;
        }
    }

    record ArgumentSegment(String name, CommandArgumentKind kind) implements Segment {

        ArgumentSegment {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Argument name must not be blank.");
            }
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public String identity() {
            return "argument:" + name;
        }

        @Override
        public String display() {
            return '<' + name + (kind == CommandArgumentKind.GREEDY_STRING ? "...>" : ">");
        }
    }

    record TypedArgumentSegment(String name, ArgumentType<?> type, String display) implements Segment {

        TypedArgumentSegment {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Argument name must not be blank.");
            }
            Objects.requireNonNull(type, "type");
            if (display == null || display.isBlank()) {
                throw new IllegalArgumentException("Argument display must not be blank.");
            }
        }

        @Override
        public String identity() {
            return "argument:" + name;
        }
    }
}