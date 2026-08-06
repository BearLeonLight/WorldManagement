package io.github.bearl.worldmanagement.hook;

import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Event-maintained player world identities safe for arbitrary-thread placeholder reads. */
public final class PlayerWorldContextSnapshot implements Listener {

    private final ConcurrentMap<UUID, VerifiedWorldRef> worlds = new ConcurrentHashMap<>();

    public Optional<VerifiedWorldRef> currentWorld(final UUID playerId) {
        return Optional.ofNullable(worlds.get(Objects.requireNonNull(playerId, "playerId")));
    }

    public void capture(final Player player) {
        final Player requiredPlayer = Objects.requireNonNull(player, "player");
        final World world = requiredPlayer.getWorld();
        worlds.put(requiredPlayer.getUniqueId(), new VerifiedWorldRef(
            world.getKey().getKey(),
            world.getKey().toString(),
            world.getUID()
        ));
    }

    public void clear() {
        worlds.clear();
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        capture(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(final PlayerChangedWorldEvent event) {
        capture(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        worlds.remove(event.getPlayer().getUniqueId());
    }
}