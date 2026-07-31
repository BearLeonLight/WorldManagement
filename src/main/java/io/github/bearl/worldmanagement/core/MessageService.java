package io.github.bearl.worldmanagement.core;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Loads keyed locale messages on the I/O path and renders Adventure components. */
public final class MessageService {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final MiniMessage STRICT_MINI_MESSAGE = MiniMessage.builder().strict(true).build();

    private final Map<String, String> messages;

    private MessageService(final Map<String, String> messages) {
        this.messages = Map.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    public static MessageService load(
        final Path dataDirectory,
        final String locale,
        final InputStream bundledMessages,
        final Consumer<String> warningConsumer
    ) {
        Objects.requireNonNull(bundledMessages, "bundledMessages");
        Objects.requireNonNull(warningConsumer, "warningConsumer");
        final Path messageFile = dataDirectory.resolve("messages_" + locale + ".yml").toAbsolutePath().normalize();
        try (bundledMessages) {
            final Map<String, String> defaults = flatten(YamlDocument.create(bundledMessages));
            defaults.forEach(MessageService::validateBundledTemplate);
            final YamlDocument configuredDocument = YamlDocument.create(new ByteArrayInputStream(
                java.nio.file.Files.exists(messageFile) ? java.nio.file.Files.readAllBytes(messageFile) : new byte[0]
            ));
            final Map<String, String> configured = flatten(configuredDocument);
            final Map<String, String> selected = new LinkedHashMap<>();
            boolean localeUpdated = false;
            defaults.forEach((key, fallback) -> {
                final String candidate = configured.get(key);
                if (candidate == null) {
                    configuredDocument.set(key, fallback);
                    selected.put(key, fallback);
                    return;
                }
                try {
                    validateTemplate(candidate, placeholderNames(fallback));
                    selected.put(key, candidate);
                } catch (final RuntimeException exception) {
                    warningConsumer.accept("Invalid locale message " + key + " in " + messageFile.getFileName()
                        + " (template: " + candidate + "); using bundled default: " + exception.getMessage());
                    selected.put(key, fallback);
                }
            });
            if (!configured.keySet().containsAll(defaults.keySet())) {
                java.nio.file.Files.createDirectories(messageFile.getParent());
                configuredDocument.save(messageFile.toFile());
                localeUpdated = true;
            }
            if (localeUpdated) {
                warningConsumer.accept("Updated " + messageFile.getFileName() + " with missing bundled locale messages.");
            }
            return new MessageService(selected);
        } catch (final IOException exception) {
            throw new IllegalStateException("Could not load locale messages.", exception);
        }
    }

    public Component componentOrDefault(final String key, final String fallback, final String... replacements) {
        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("Message replacements must be name-value pairs.");
        }
        final List<TagResolver> placeholders = new ArrayList<>(replacements.length / 2 + 1);
        placeholders.add(Placeholder.component("prefix", MINI_MESSAGE.deserialize(messages.getOrDefault("format.prefix", ""))));
        for (int index = 0; index < replacements.length; index += 2) {
            placeholders.add(Placeholder.unparsed(replacements[index], replacements[index + 1]));
        }
        return MINI_MESSAGE.deserialize(messages.getOrDefault(key, fallback), TagResolver.resolver(placeholders));
    }

    public Component component(final String key, final String... replacements) {
        final String template = messages.get(key);
        if (template == null) {
            throw new IllegalArgumentException("Unknown locale message: " + key);
        }
        return componentOrDefault(key, template, replacements);
    }

    public Component component(final String key, final Map<String, Component> replacements) {
        final String template = messages.get(key);
        if (template == null) {
            throw new IllegalArgumentException("Unknown locale message: " + key);
        }
        final List<TagResolver> placeholders = new ArrayList<>(replacements.size() + 1);
        placeholders.add(Placeholder.component("prefix", MINI_MESSAGE.deserialize(messages.getOrDefault("format.prefix", ""))));
        replacements.forEach((name, component) -> placeholders.add(Placeholder.component(
            Objects.requireNonNull(name, "replacement name"),
            Objects.requireNonNull(component, "replacement component")
        )));
        return MINI_MESSAGE.deserialize(template, TagResolver.resolver(placeholders));
    }

    private static Map<String, String> flatten(final Section root) {
        final Map<String, String> result = new LinkedHashMap<>();
        flatten(root, "", result);
        return result;
    }

    private static void flatten(final Section section, final String prefix, final Map<String, String> result) {
        for (final Object keyValue : section.getKeys()) {
            final String key = keyValue.toString();
            final String route = prefix.isEmpty() ? key : prefix + "." + key;
            final Section child = section.getSection(key);
            if (child != null) {
                flatten(child, route, result);
                continue;
            }
            final String value = section.getString(key, null);
            if (value != null) {
                result.put(route, value);
            }
        }
    }

    private static void validateBundledTemplate(final String key, final String template) {
        try {
            validateTemplate(template, placeholderNames(template));
        } catch (final RuntimeException exception) {
            throw new IllegalStateException("Invalid bundled locale message: " + key, exception);
        }
    }

    private static Set<String> placeholderNames(final String template) {
        final Set<String> names = new LinkedHashSet<>();
        MINI_MESSAGE.deserialize(template, TagResolver.resolver(TagResolver.standard(), collectingResolver(names)));
        return Set.copyOf(names);
    }

    private static void validateTemplate(final String template, final Set<String> placeholders) {
        final Set<String> usedPlaceholders = placeholderNames(template);
        if (!placeholders.containsAll(usedPlaceholders)) {
            final Set<String> unknown = new LinkedHashSet<>(usedPlaceholders);
            unknown.removeAll(placeholders);
            throw new IllegalArgumentException("Unknown locale placeholder or MiniMessage tag: " + String.join(", ", unknown));
        }
        final List<TagResolver> resolvers = new ArrayList<>(placeholders.size() + 1);
        resolvers.add(TagResolver.standard());
        placeholders.forEach(name -> resolvers.add(Placeholder.unparsed(name, "")));
        final MiniMessage validator = template.toLowerCase(java.util.Locale.ROOT).contains("<reset>")
            ? MINI_MESSAGE
            : STRICT_MINI_MESSAGE;
        validator.deserialize(template, TagResolver.resolver(resolvers));
    }

    private static TagResolver collectingResolver(final Set<String> names) {
        return new TagResolver() {
            @Override
            public Tag resolve(final String name, final ArgumentQueue arguments, final Context context) {
                names.add(name);
                return Tag.inserting(Component.empty());
            }

            @Override
            public boolean has(final String name) {
                return !TagResolver.standard().has(name);
            }
        };
    }

}