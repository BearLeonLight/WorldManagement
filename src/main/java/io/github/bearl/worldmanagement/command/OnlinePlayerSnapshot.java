package io.github.bearl.worldmanagement.command;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Main-thread maintained player identifiers that may be safely read by command completion. */
public final class OnlinePlayerSnapshot implements Listener {

    private final AtomicReference<Map<String, PlayerIdentity>> players = new AtomicReference<>(Map.of());

    public void replace(final Collection<? extends Player> onlinePlayers) {
        final Map<String, PlayerIdentity> replacement = new LinkedHashMap<>();
        onlinePlayers.stream()
            .map(player -> new PlayerIdentity(player.getUniqueId(), player.getName()))
            .sorted(Comparator.comparing(PlayerIdentity::name, String.CASE_INSENSITIVE_ORDER))
            .forEach(player -> replacement.put(normalize(player.name()), player));
        players.set(Map.copyOf(replacement));
    }

    public List<String> names() {
        return players.get().values().stream().map(PlayerIdentity::name).toList();
    }

    public Optional<UUID> resolve(final String nameOrUuid) {
        try {
            return Optional.of(UUID.fromString(nameOrUuid));
        } catch (final IllegalArgumentException ignored) {
            final PlayerIdentity identity = players.get().get(normalize(nameOrUuid));
            return identity == null ? Optional.empty() : Optional.of(identity.uniqueId());
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        update(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        players.updateAndGet(current -> {
            final Map<String, PlayerIdentity> replacement = new LinkedHashMap<>(current);
            replacement.remove(normalize(event.getPlayer().getName()));
            return Map.copyOf(replacement);
        });
    }

    private void update(final Player player) {
        final PlayerIdentity identity = new PlayerIdentity(player.getUniqueId(), player.getName());
        players.updateAndGet(current -> {
            final Map<String, PlayerIdentity> replacement = new LinkedHashMap<>(current);
            replacement.put(normalize(identity.name()), identity);
            return Map.copyOf(replacement);
        });
    }

    private static String normalize(final String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record PlayerIdentity(UUID uniqueId, String name) {
    }
}