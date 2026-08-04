package io.github.bearl.worldmanagement.world;

import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Schedules world teleports through the target player's entity-affine Paper scheduler. */
public final class PaperWorldTeleportGateway implements WorldTeleportGateway {

    private final WorldThreadDispatcher dispatcher;
    private final RuntimeResolver runtimeResolver;
    private final Object admissionLock = new Object();
    private final java.util.Set<PendingTeleport> pendingTeleports = ConcurrentHashMap.newKeySet();
    private boolean acceptingOperations = true;

    public PaperWorldTeleportGateway(final WorldThreadDispatcher dispatcher) {
        this(dispatcher, new BukkitRuntimeResolver());
    }

    PaperWorldTeleportGateway(
        final WorldThreadDispatcher dispatcher,
        final RuntimeResolver runtimeResolver
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
    }

    @Override
    public CompletableFuture<Boolean> teleport(
        final UUID playerId,
        final VerifiedWorldRef target,
        final Optional<WorldCoordinates> coordinates
    ) {
        final UUID requiredPlayerId = Objects.requireNonNull(playerId, "playerId");
        final VerifiedWorldRef requiredTarget = Objects.requireNonNull(target, "target");
        final Optional<WorldCoordinates> requiredCoordinates = Objects.requireNonNull(coordinates, "coordinates");
        final PendingTeleport operation = new PendingTeleport();
        synchronized (admissionLock) {
            if (!acceptingOperations) {
                operation.rejectBeforeSubmission();
                return operation.result();
            }
            pendingTeleports.add(operation);
            operation.drain().whenComplete((unused, failure) -> pendingTeleports.remove(operation));
        }
        try {
            dispatcher.executeGlobal(() -> {
                try {
                    final NamespacedKey key = NamespacedKey.fromString(requiredTarget.paperKey());
                    final World world = key == null ? null : runtimeResolver.world(key);
                    final Player player = runtimeResolver.player(requiredPlayerId);
                    if (world == null || player == null || !world.getUID().equals(requiredTarget.worldUuid())) {
                        completeBeforeSubmission(operation, false);
                        return;
                    }
                    final Location destination = requiredCoordinates
                        .map(value -> new Location(world, value.x(), value.y(), value.z()))
                        .orElseGet(world::getSpawnLocation);
                    if (!accepting()) {
                        completeBeforeSubmission(operation, false);
                        return;
                    }
                    final boolean scheduled = dispatcher.executeFor(
                        player,
                        () -> submitPaperTeleport(operation, player, destination),
                        () -> completeBeforeSubmission(operation, false)
                    );
                    if (!scheduled) {
                        completeBeforeSubmission(operation, false);
                    }
                } catch (final RuntimeException failure) {
                    completeBeforeSubmission(operation, false);
                }
            }, () -> completeBeforeSubmission(operation, false));
        } catch (final RuntimeException failure) {
            completeBeforeSubmission(operation, false);
        }
        return operation.result();
    }

    @Override
    public CompletableFuture<Void> beginShutdown() {
        final PendingTeleport[] pending;
        synchronized (admissionLock) {
            acceptingOperations = false;
            pending = pendingTeleports.toArray(PendingTeleport[]::new);
            for (final PendingTeleport operation : pending) {
                operation.rejectForShutdown();
            }
        }
        return CompletableFuture.allOf(java.util.Arrays.stream(pending)
            .map(PendingTeleport::drain)
            .toArray(CompletableFuture[]::new));
    }

    private boolean accepting() {
        synchronized (admissionLock) {
            return acceptingOperations;
        }
    }

    private void submitPaperTeleport(
        final PendingTeleport operation,
        final Player player,
        final Location destination
    ) {
        synchronized (admissionLock) {
            if (!acceptingOperations) {
                operation.rejectBeforeSubmission();
                return;
            }
            try {
                final CompletableFuture<Boolean> paperTeleport = Objects.requireNonNull(
                    player.teleportAsync(destination), "Paper teleport future"
                );
                operation.markSubmitted();
                paperTeleport.whenComplete((teleported, failure) ->
                    completeSubmitted(operation, failure == null && Boolean.TRUE.equals(teleported))
                );
            } catch (final RuntimeException failure) {
                operation.rejectBeforeSubmission();
            }
        }
    }

    private void completeBeforeSubmission(final PendingTeleport operation, final boolean result) {
        synchronized (admissionLock) {
            operation.completeBeforeSubmission(result);
        }
    }

    private void completeSubmitted(final PendingTeleport operation, final boolean result) {
        synchronized (admissionLock) {
            operation.completeSubmitted(result);
        }
    }

    interface RuntimeResolver {
        World world(NamespacedKey key);
        Player player(UUID playerId);
    }

    private static final class BukkitRuntimeResolver implements RuntimeResolver {
        @Override
        public World world(final NamespacedKey key) {
            return Bukkit.getWorld(key);
        }

        @Override
        public Player player(final UUID playerId) {
            return Bukkit.getPlayer(playerId);
        }
    }

    private static final class PendingTeleport {
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final CompletableFuture<Void> drain = new CompletableFuture<>();
        private boolean submitted;

        private CompletableFuture<Boolean> result() {
            return result;
        }

        private CompletableFuture<Void> drain() {
            return drain;
        }

        private void markSubmitted() {
            submitted = true;
        }

        private void rejectForShutdown() {
            result.complete(false);
            if (!submitted) {
                drain.complete(null);
            }
        }

        private void rejectBeforeSubmission() {
            completeBeforeSubmission(false);
        }

        private void completeBeforeSubmission(final boolean value) {
            if (submitted) {
                return;
            }
            result.complete(value);
            drain.complete(null);
        }

        private void completeSubmitted(final boolean value) {
            if (!submitted) {
                return;
            }
            result.complete(value);
            drain.complete(null);
        }
    }
}