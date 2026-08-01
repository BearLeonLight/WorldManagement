package io.github.bearl.worldmanagement.command;

import io.github.bearl.worldmanagement.core.MessageService;
import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Renders immutable world metadata as an interactive Adventure list. */
final class WorldListMessageRenderer {

    private final MessageService messages;
    private final DisplayNameValidator displayNames;

    WorldListMessageRenderer(final MessageService messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.displayNames = new DisplayNameValidator();
    }

    List<Component> render(final List<WorldMetadata> worlds, final boolean detached) {
        final List<Component> result = new ArrayList<>(worlds.size() + 1);
        result.add(messages.component(detached ? "command.list.detached-result" : "command.list.result"));
        worlds.stream()
            .sorted(Comparator.comparing(WorldMetadata::worldName))
            .map(this::entry)
            .forEach(result::add);
        return List.copyOf(result);
    }

    private Component entry(final WorldMetadata metadata) {
        Component displayName = displayNames.validate(metadata.displayName()).component();
        if (!metadata.displayName().equals(metadata.worldName())) {
            displayName = displayName.hoverEvent(messages.component(
                "command.list.world-id-hover", "world", metadata.worldName()
            ));
        }
        return messages.component("command.list.entry", Map.of(
            "name", displayName,
            "type", Component.text(metadata.identity().environment().name(), environmentColor(metadata.identity().environment()))
        ));
    }

    private static NamedTextColor environmentColor(final WorldEnvironment environment) {
        return switch (environment) {
            case NORMAL -> NamedTextColor.GREEN;
            case NETHER -> NamedTextColor.RED;
            case THE_END -> NamedTextColor.AQUA;
            case CUSTOM -> NamedTextColor.GOLD;
        };
    }
}