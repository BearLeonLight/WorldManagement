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

    private static final String TERM_PREFIX = "term.";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final MiniMessage STRICT_MINI_MESSAGE = MiniMessage.builder().strict(true).build();

    private final Map<String, String> messages;
    private final Map<String, String> terms;
    private final TagResolver termResolver;

    private MessageService(final Map<String, String> messages, final Map<String, String> terms) {
        this.messages = Map.copyOf(Objects.requireNonNull(messages, "messages"));
        this.terms = Map.copyOf(Objects.requireNonNull(terms, "terms"));
        this.termResolver = termResolver(this.terms);
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
            final Map<String, String> defaultTerms = terms(defaults);
            defaults.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith(TERM_PREFIX))
                .forEach(entry -> validateBundledTemplate(entry.getKey(), entry.getValue(), defaultTerms));
            final YamlDocument configuredDocument = YamlDocument.create(new ByteArrayInputStream(
                java.nio.file.Files.exists(messageFile) ? java.nio.file.Files.readAllBytes(messageFile) : new byte[0]
            ));
            final Map<String, String> configured = flatten(configuredDocument);
            final Map<String, String> selectedTerms = new LinkedHashMap<>();
            defaultTerms.forEach((key, fallback) -> selectedTerms.put(
                key,
                configured.getOrDefault(TERM_PREFIX + key, fallback)
            ));
            final Map<String, String> selected = new LinkedHashMap<>();
            boolean localeUpdated = false;
            defaults.forEach((key, fallback) -> {
                if (key.startsWith(TERM_PREFIX)) {
                    if (!configured.containsKey(key)) {
                        configuredDocument.set(key, fallback);
                    }
                    return;
                }
                final String candidate = configured.get(key);
                if (candidate == null) {
                    configuredDocument.set(key, fallback);
                    selected.put(key, fallback);
                    return;
                }
                try {
                    validateTemplate(candidate, placeholderNames(fallback, defaultTerms), selectedTerms);
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
            return new MessageService(selected, selectedTerms);
        } catch (final IOException exception) {
            throw new IllegalStateException("Could not load locale messages.", exception);
        }
    }

    public Component componentOrDefault(final String key, final String fallback, final String... replacements) {
        if (replacements.length % 2 != 0) {
            throw new IllegalArgumentException("Message replacements must be name-value pairs.");
        }
        final List<TagResolver> placeholders = new ArrayList<>(replacements.length / 2 + 1);
        placeholders.add(Placeholder.component(
            "prefix",
            MINI_MESSAGE.deserialize(messages.getOrDefault("format.prefix", ""), termResolver)
        ));
        for (int index = 0; index < replacements.length; index += 2) {
            placeholders.add(Placeholder.unparsed(replacements[index], replacements[index + 1]));
        }
        placeholders.add(termResolver);
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
        placeholders.add(Placeholder.component(
            "prefix",
            MINI_MESSAGE.deserialize(messages.getOrDefault("format.prefix", ""), termResolver)
        ));
        replacements.forEach((name, component) -> placeholders.add(Placeholder.component(
            Objects.requireNonNull(name, "replacement name"),
            Objects.requireNonNull(component, "replacement component")
        )));
        placeholders.add(termResolver);
        return MINI_MESSAGE.deserialize(template, TagResolver.resolver(placeholders));
    }

    public Component termComponent(final String key) {
        final String term = terms.get(Objects.requireNonNull(key, "key"));
        if (term == null) {
            throw new IllegalArgumentException("Unknown locale term: " + key);
        }
        return Component.text(term);
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

    private static Map<String, String> terms(final Map<String, String> values) {
        final Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(TERM_PREFIX)) {
                result.put(key.substring(TERM_PREFIX.length()), value);
            }
        });
        return result;
    }

    private static void validateBundledTemplate(
        final String key,
        final String template,
        final Map<String, String> terms
    ) {
        try {
            validateTemplate(template, placeholderNames(template, terms), terms);
        } catch (final RuntimeException exception) {
            throw new IllegalStateException("Invalid bundled locale message: " + key, exception);
        }
    }

    private static Set<String> placeholderNames(final String template, final Map<String, String> terms) {
        final Set<String> names = new LinkedHashSet<>();
        final Set<String> usedTerms = new LinkedHashSet<>();
        MINI_MESSAGE.deserialize(template, TagResolver.resolver(
            TagResolver.standard(),
            collectingResolver(names),
            collectingTermResolver(usedTerms)
        ));
        if (!terms.keySet().containsAll(usedTerms)) {
            final Set<String> unknown = new LinkedHashSet<>(usedTerms);
            unknown.removeAll(terms.keySet());
            throw new IllegalArgumentException("Unknown locale term: " + String.join(", ", unknown));
        }
        return Set.copyOf(names);
    }

    private static void validateTemplate(
        final String template,
        final Set<String> placeholders,
        final Map<String, String> terms
    ) {
        final Set<String> usedPlaceholders = placeholderNames(template, terms);
        if (!placeholders.containsAll(usedPlaceholders)) {
            final Set<String> unknown = new LinkedHashSet<>(usedPlaceholders);
            unknown.removeAll(placeholders);
            throw new IllegalArgumentException("Unknown locale placeholder or MiniMessage tag: " + String.join(", ", unknown));
        }
        final List<TagResolver> resolvers = new ArrayList<>(placeholders.size() + 1);
        resolvers.add(TagResolver.standard());
        resolvers.add(termResolver(terms));
        placeholders.forEach(name -> resolvers.add(Placeholder.unparsed(name, "")));
        final MiniMessage validator = template.toLowerCase(java.util.Locale.ROOT).contains("<reset>")
            ? MINI_MESSAGE
            : STRICT_MINI_MESSAGE;
        validator.deserialize(template, TagResolver.resolver(resolvers));
    }

    private static TagResolver termResolver(final Map<String, String> terms) {
        return TagResolver.resolver("term", (arguments, context) -> {
            final String key = termKey(arguments, context);
            final String value = terms.get(key);
            if (value == null) {
                throw context.newException("Unknown locale term: " + key, arguments);
            }
            return Tag.selfClosingInserting(Component.text(value));
        });
    }

    private static TagResolver collectingTermResolver(final Set<String> terms) {
        return TagResolver.resolver("term", (arguments, context) -> {
            terms.add(termKey(arguments, context));
            return Tag.selfClosingInserting(Component.empty());
        });
    }

    private static String termKey(final ArgumentQueue arguments, final Context context) {
        final String key = arguments.popOr("A locale term key is required.").value();
        if (arguments.hasNext()) {
            throw context.newException("Locale term tags accept exactly one key.", arguments);
        }
        return key;
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