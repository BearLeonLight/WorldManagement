package io.github.bearl.worldmanagement.hook;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** PlaceholderAPI adapter for the shared immutable WorldManagement placeholder model. */
public final class PlaceholderApiExpansion extends PlaceholderExpansion {

    private final WorldPlaceholderResolver resolver;
    private final String author;
    private final String version;

    public PlaceholderApiExpansion(
        final WorldPlaceholderResolver resolver,
        final String author,
        final String version
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.author = Objects.requireNonNull(author, "author");
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public @NotNull String getIdentifier() {
        return WorldPlaceholderResolver.NAMESPACE;
    }

    @Override
    public @NotNull String getAuthor() {
        return author;
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return Stream.concat(
            Stream.concat(
                WorldPlaceholderResolver.GLOBAL_KEYS.stream(),
                WorldPlaceholderResolver.AUDIENCE_KEYS.stream()
            ).map(key -> "%wm_" + key + "%"),
            WorldPlaceholderResolver.WORLD_KEYS.stream().map(key -> "%wm_" + key + ":<world>%")
        ).sorted().toList();
    }

    @Override
    public @Nullable String onRequest(final OfflinePlayer player, final @NotNull String params) {
        final ParsedPlaceholder parsed = ParsedPlaceholder.parse(params);
        if (parsed == null) {
            return null;
        }
        final UUID playerId = player == null ? null : player.getUniqueId();
        return resolver.resolve(parsed.key(), parsed.argument(), playerId)
            .map(WorldPlaceholderResolver.Value::plainText)
            .orElse(null);
    }

    private record ParsedPlaceholder(String key, Optional<String> argument) {

        private static ParsedPlaceholder parse(final String params) {
            final int separator = params.indexOf(':');
            final String key = (separator < 0 ? params : params.substring(0, separator))
                .toLowerCase(Locale.ROOT);
            if (WorldPlaceholderResolver.GLOBAL_KEYS.contains(key)
                || WorldPlaceholderResolver.AUDIENCE_KEYS.contains(key)) {
                return separator < 0 ? new ParsedPlaceholder(key, Optional.empty()) : null;
            }
            if (!WorldPlaceholderResolver.WORLD_KEYS.contains(key) || separator < 0) {
                return null;
            }
            final String worldId = params.substring(separator + 1);
            return worldId.isBlank() ? null : new ParsedPlaceholder(key, Optional.of(worldId));
        }
    }
}