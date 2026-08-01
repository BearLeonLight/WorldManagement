package io.github.bearl.worldmanagement.command;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Immutable player authorization facts maintained outside command completion. */
public final class CommandAuthorizationSnapshot implements Listener {

    public static final String OWNERSHIP_ADMIN_PERMISSION = "worldmanagement.admin.ownership.manage";
    public static final String WARP_ADMIN_PERMISSION = "worldmanagement.admin.warp.manage";

    private final AtomicReference<State> state = new AtomicReference<>(State.empty());
    private final AtomicLong refreshGeneration = new AtomicLong();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public void replace(final Collection<? extends Player> onlinePlayers) {
        if (!accepting.get()) {
            return;
        }
        final Map<CommandSender, Authorization> replacement = new IdentityHashMap<>();
        onlinePlayers.forEach(player -> replacement.put(player, capture(player)));
        refreshGeneration.incrementAndGet();
        state.set(State.copyOf(replacement));
    }

    public void refresh(final Player player) {
        Objects.requireNonNull(player, "player");
        if (!accepting.get()) {
            return;
        }
        refreshGeneration.incrementAndGet();
        state.updateAndGet(current -> current.with(player, capture(player)));
    }

    public Refresh beginRefresh(final int expectedPlayers) {
        if (expectedPlayers < 0) {
            throw new IllegalArgumentException("expectedPlayers must not be negative");
        }
        final long generation = refreshGeneration.incrementAndGet();
        final Refresh refresh = new Refresh(generation, accepting.get() ? expectedPlayers : 0);
        refresh.publishIfComplete();
        return refresh;
    }

    public void beginShutdown() {
        accepting.set(false);
        refreshGeneration.incrementAndGet();
        state.set(State.empty());
    }

    Scope scope(final CommandSender sender, final ManagementArea area) {
        final CommandSender effectiveSender = effectiveSender(Objects.requireNonNull(sender, "sender"));
        Objects.requireNonNull(area, "area");
        if (effectiveSender == null) {
            return new Scope(Optional.empty(), false, false);
        }
        if (effectiveSender instanceof ConsoleCommandSender || effectiveSender instanceof RemoteConsoleCommandSender) {
            return new Scope(Optional.empty(), true, true);
        }
        if (!(effectiveSender instanceof Player)) {
            return new Scope(Optional.empty(), false, false);
        }
        final Authorization authorization = state.get().bySender().get(effectiveSender);
        return authorization == null
            ? new Scope(Optional.empty(), false, false)
            : new Scope(Optional.of(authorization.playerId()), authorization.manages(area), true);
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        refreshGeneration.incrementAndGet();
        state.updateAndGet(current -> current.without(player));
    }

    private static Authorization capture(final Player player) {
        return new Authorization(
            player.getUniqueId(),
            player.hasPermission(OWNERSHIP_ADMIN_PERMISSION),
            player.hasPermission(WARP_ADMIN_PERMISSION)
        );
    }

    private static CommandSender effectiveSender(final CommandSender sender) {
        final var visited = Collections.newSetFromMap(new IdentityHashMap<CommandSender, Boolean>());
        CommandSender current = sender;
        while (current instanceof ProxiedCommandSender proxy) {
            if (!visited.add(current)) {
                return null;
            }
            current = proxy.getCaller();
        }
        return current;
    }

    public final class Refresh {
        private final long generation;
        private final Map<CommandSender, Authorization> captured = new IdentityHashMap<>();
        private int remaining;
        private volatile boolean published;

        private Refresh(final long generation, final int expectedPlayers) {
            this.generation = generation;
            this.remaining = expectedPlayers;
        }

        public void capture(final Player player) {
            final Player requiredPlayer = Objects.requireNonNull(player, "player");
            if (published || !accepting.get() || refreshGeneration.get() != generation) {
                return;
            }
            final Authorization authorization = CommandAuthorizationSnapshot.capture(requiredPlayer);
            synchronized (this) {
                if (published || !accepting.get() || refreshGeneration.get() != generation) {
                    return;
                }
                captured.put(requiredPlayer, authorization);
                completeOne();
            }
        }

        public synchronized void skip() {
            if (!published) {
                completeOne();
            }
        }

        private void completeOne() {
            if (remaining == 0) {
                return;
            }
            remaining--;
            publishIfComplete();
        }

        private synchronized void publishIfComplete() {
            if (published || remaining != 0) {
                return;
            }
            published = true;
            if (accepting.get() && refreshGeneration.get() == generation) {
                state.set(State.copyOf(captured));
            }
        }
    }

    enum ManagementArea {
        OWNERSHIP,
        WARP
    }

    record Scope(Optional<UUID> playerId, boolean managesAllWorlds, boolean known) {
        Scope {
            playerId = Objects.requireNonNull(playerId, "playerId");
        }
    }

    private record Authorization(UUID playerId, boolean ownershipAdministrator, boolean warpAdministrator) {
        private Authorization {
            Objects.requireNonNull(playerId, "playerId");
        }

        private boolean manages(final ManagementArea area) {
            return switch (area) {
                case OWNERSHIP -> ownershipAdministrator;
                case WARP -> warpAdministrator;
            };
        }
    }

    private record State(Map<CommandSender, Authorization> bySender) {
        private State {
            Objects.requireNonNull(bySender, "bySender");
        }

        private static State empty() {
            return copyOf(Map.of());
        }

        private static State copyOf(final Map<CommandSender, Authorization> source) {
            final Map<CommandSender, Authorization> copy = new IdentityHashMap<>();
            copy.putAll(source);
            return new State(Collections.unmodifiableMap(copy));
        }

        private State with(final CommandSender sender, final Authorization authorization) {
            final Map<CommandSender, Authorization> replacement = new IdentityHashMap<>(bySender);
            replacement.put(sender, authorization);
            return copyOf(replacement);
        }

        private State without(final CommandSender sender) {
            final Map<CommandSender, Authorization> replacement = new IdentityHashMap<>(bySender);
            replacement.remove(sender);
            return copyOf(replacement);
        }
    }
}