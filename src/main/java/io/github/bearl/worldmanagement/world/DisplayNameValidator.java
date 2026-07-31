package io.github.bearl.worldmanagement.world;

import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Parses world display names with a formatting-only MiniMessage allowlist. */
public final class DisplayNameValidator {

    public static final int MAX_RAW_CODE_POINTS = 512;
    public static final int MAX_PLAIN_CODE_POINTS = 64;

    private static final TagResolver ALLOWED_TAGS = TagResolver.builder()
        .resolver(StandardTags.color())
        .resolver(StandardTags.decorations())
        .resolver(StandardTags.gradient())
        .resolver(StandardTags.rainbow())
        .resolver(StandardTags.reset())
        .build();
    private static final MiniMessage STRICT = MiniMessage.builder().tags(ALLOWED_TAGS).strict(true).build();
    private static final MiniMessage RESET_CAPABLE = MiniMessage.builder().tags(ALLOWED_TAGS).build();

    public ValidatedDisplayName validate(final String rawDisplayName) {
        final String raw = Objects.requireNonNull(rawDisplayName, "rawDisplayName");
        if (raw.codePointCount(0, raw.length()) > MAX_RAW_CODE_POINTS) {
            throw new IllegalArgumentException("Display name exceeds the raw code point limit.");
        }
        raw.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("Display name must not contain control characters.");
            }
        });

        final Set<String> rejectedTags = new LinkedHashSet<>();
        MiniMessage.builder()
            .tags(TagResolver.resolver(ALLOWED_TAGS, collectingRejectedTags(rejectedTags)))
            .build()
            .deserialize(raw);
        if (!rejectedTags.isEmpty()) {
            throw new IllegalArgumentException("Display names may only use formatting MiniMessage tags.");
        }

        final MiniMessage parser = raw.toLowerCase(java.util.Locale.ROOT).contains("<reset>")
            ? RESET_CAPABLE
            : STRICT;
        final Component component;
        try {
            component = parser.deserialize(raw);
        } catch (final RuntimeException exception) {
            throw new IllegalArgumentException("Invalid display name MiniMessage.", exception);
        }
        final String plainText = PlainTextComponentSerializer.plainText().serialize(component);
        if (plainText.isBlank()) {
            throw new IllegalArgumentException("Display name must contain visible text.");
        }
        if (plainText.codePointCount(0, plainText.length()) > MAX_PLAIN_CODE_POINTS) {
            throw new IllegalArgumentException("Display name exceeds the rendered plain-text limit.");
        }
        return new ValidatedDisplayName(raw, component, plainText);
    }

    private static TagResolver collectingRejectedTags(final Set<String> rejectedTags) {
        return new TagResolver() {
            @Override
            public Tag resolve(final String name, final ArgumentQueue arguments, final Context context) {
                rejectedTags.add(name);
                return Tag.inserting(Component.empty());
            }

            @Override
            public boolean has(final String name) {
                return !ALLOWED_TAGS.has(name);
            }
        };
    }
}