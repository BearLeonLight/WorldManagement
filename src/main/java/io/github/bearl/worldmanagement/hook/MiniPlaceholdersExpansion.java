package io.github.bearl.worldmanagement.hook;

import io.github.miniplaceholders.api.Expansion;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;

/** Builds the MiniPlaceholders expansion backed by immutable WorldManagement snapshots. */
public final class MiniPlaceholdersExpansion {

    private MiniPlaceholdersExpansion() {
    }

    public static Expansion create(
        final WorldPlaceholderResolver resolver,
        final String author,
        final String version
    ) {
        Objects.requireNonNull(resolver, "resolver");
        final Expansion.Builder builder = Expansion.builder(WorldPlaceholderResolver.NAMESPACE)
            .author(Objects.requireNonNull(author, "author"))
            .version(Objects.requireNonNull(version, "version"));

        for (final String key : WorldPlaceholderResolver.GLOBAL_KEYS) {
            builder.globalPlaceholder(key, (arguments, context) -> resolve(
                resolver, key, Optional.empty(), null, arguments.hasNext()
            ));
        }
        for (final String key : WorldPlaceholderResolver.WORLD_KEYS) {
            builder.globalPlaceholder(key, (arguments, context) -> resolve(
                resolver, key, worldArgument(arguments), null, arguments.hasNext()
            ));
        }
        for (final String key : WorldPlaceholderResolver.AUDIENCE_KEYS) {
            builder.audiencePlaceholder(key, (audience, arguments, context) -> resolve(
                resolver,
                key,
                Optional.empty(),
                audience.get(Identity.UUID).orElse(null),
                arguments.hasNext()
            ));
        }
        return builder.build();
    }

    private static Optional<String> worldArgument(final ArgumentQueue arguments) {
        if (!arguments.hasNext()) {
            return Optional.empty();
        }
        final String worldId = arguments.pop().value();
        return worldId.isBlank() ? Optional.empty() : Optional.of(worldId);
    }

    private static Tag resolve(
        final WorldPlaceholderResolver resolver,
        final String key,
        final Optional<String> argument,
        final UUID playerId,
        final boolean unexpectedArguments
    ) {
        if (unexpectedArguments) {
            return Tag.selfClosingInserting(Component.empty());
        }
        return Tag.selfClosingInserting(resolver.resolve(key, argument, playerId)
            .map(WorldPlaceholderResolver.Value::component)
            .orElse(Component.empty()));
    }
}