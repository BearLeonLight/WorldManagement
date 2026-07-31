package io.github.bearl.worldmanagement.world;

import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Schedules world teleports through the target player's entity-affine Paper scheduler. */
public final class PaperWorldTeleportGateway implements WorldTeleportGateway {

    private final WorldThreadDispatcher dispatcher;
    private final java.util.Set<CompletableFuture<Boolean>> pendingTeleports = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean acceptingOperations = new AtomicBoolean(true);

    public PaperWorldTeleportGateway(final WorldThreadDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
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
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!acceptingOperations.get()) {
            result.complete(false);
            return result;
        }
        pendingTeleports.add(result);
        result.whenComplete((unused, failure) -> pendingTeleports.remove(result));
        if (!acceptingOperations.get()) {
            result.complete(false);
            return result;
        }
        dispatcher.executeGlobal(() -> {
            if (!acceptingOperations.get()) {
                result.complete(false);
                return;
            }
            final NamespacedKey key = NamespacedKey.fromString(requiredTarget.paperKey());
            final World world = key == null ? null : Bukkit.getWorld(key);
            final Player player = Bukkit.getPlayer(requiredPlayerId);
            if (world == null || player == null || !world.getUID().equals(requiredTarget.worldUuid())) {
                result.complete(false);
                return;
            }
            final Location destination = requiredCoordinates
                .map(value -> new Location(world, value.x(), value.y(), value.z()))
                .orElseGet(world::getSpawnLocation);
            final boolean scheduled = dispatcher.executeFor(
                player,
                () -> player.teleportAsync(destination).whenComplete((teleported, failure) ->
                    result.complete(failure == null && teleported)
                ),
                () -> result.complete(false)
            );
            if (!scheduled) {
                result.complete(false);
            }
        }, () -> result.complete(false));
        return result;
    }

    @Override
    public CompletableFuture<Void> beginShutdown() {
        acceptingOperations.set(false);
        final CompletableFuture<?>[] pending = pendingTeleports.toArray(CompletableFuture[]::new);
        pendingTeleports.forEach(teleport -> teleport.complete(false));
        return CompletableFuture.allOf(pending);
    }
}