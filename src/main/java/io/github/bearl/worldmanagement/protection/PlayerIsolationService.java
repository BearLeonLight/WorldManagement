package io.github.bearl.worldmanagement.protection;

import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRuntimeResolution;
import io.github.bearl.worldmanagement.world.WorldTeleportGateway;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import io.github.bearl.worldmanagement.world.lifecycle.WorldRuntimeGateway;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldIdentity;
import io.github.bearl.worldmanagement.core.WorldThreadDispatcher;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Relocates players out of identity-isolated worlds without trusting stale metadata. */
public final class PlayerIsolationService {

    private static final Duration WARNING_INTERVAL = Duration.ofSeconds(30);
    private static final String BYPASS_PERMISSION = "worldmanagement.bypass.protection";

    private final WorldManagementService metadataService;
    private final LoadedWorldCatalog loadedWorlds;
    private final Optional<VerifiedWorldRef> fallback;
    private final WorldTeleportGateway teleportGateway;
    private final TeleportBypassTokens teleportBypassTokens;
    private final Consumer<String> warningSink;
    private final LongSupplier nanoTime;
    private final long warningIntervalNanos;
    private final Map<UUID, CompletableFuture<Boolean>> pendingRelocations = new ConcurrentHashMap<>();
    private final Map<String, Long> nextWarningAt = new ConcurrentHashMap<>();
    private final AtomicBoolean acceptingOperations = new AtomicBoolean(true);

    public PlayerIsolationService(
        final WorldManagementService metadataService,
        final LoadedWorldCatalog loadedWorlds,
        final Optional<VerifiedWorldRef> fallback,
        final WorldTeleportGateway teleportGateway,
        final TeleportBypassTokens teleportBypassTokens,
        final Consumer<String> warningSink
    ) {
        this(
            metadataService, loadedWorlds, fallback, teleportGateway, teleportBypassTokens,
            warningSink, System::nanoTime, WARNING_INTERVAL
        );
    }

    PlayerIsolationService(
        final WorldManagementService metadataService,
        final LoadedWorldCatalog loadedWorlds,
        final Optional<VerifiedWorldRef> fallback,
        final WorldTeleportGateway teleportGateway,
        final TeleportBypassTokens teleportBypassTokens,
        final Consumer<String> warningSink,
        final LongSupplier nanoTime,
        final Duration warningInterval
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.loadedWorlds = Objects.requireNonNull(loadedWorlds, "loadedWorlds");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.teleportGateway = Objects.requireNonNull(teleportGateway, "teleportGateway");
        this.teleportBypassTokens = Objects.requireNonNull(teleportBypassTokens, "teleportBypassTokens");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.warningIntervalNanos = Objects.requireNonNull(warningInterval, "warningInterval").toNanos();
        if (warningIntervalNanos <= 0L) {
            throw new IllegalArgumentException("Warning interval must be positive.");
        }
    }

    public RelocationStatus relocateIfNeeded(
        final UUID playerId,
        final WorldRuntimeResolution currentWorld,
        final boolean protectionBypass
    ) {
        final UUID requiredPlayerId = Objects.requireNonNull(playerId, "playerId");
        final WorldRuntimeResolution requiredWorld = Objects.requireNonNull(currentWorld, "currentWorld");
        if (requiredWorld.status() != WorldRuntimeResolution.Status.ISOLATED) {
            return RelocationStatus.NOT_REQUIRED;
        }
        if (protectionBypass) {
            return RelocationStatus.BYPASSED;
        }
        if (!acceptingOperations.get()) {
            return RelocationStatus.SHUTTING_DOWN;
        }
        final String isolatedWorldId = requiredWorld.metadata().orElseThrow().worldName();
        final VerifiedWorldRef target = resolveFallback(isolatedWorldId).orElse(null);
        if (target == null) {
            warnUnavailable(isolatedWorldId);
            return RelocationStatus.FALLBACK_UNAVAILABLE;
        }
        final CompletableFuture<Boolean> tracked = new CompletableFuture<>();
        if (pendingRelocations.putIfAbsent(requiredPlayerId, tracked) != null) {
            return RelocationStatus.ALREADY_PENDING;
        }
        if (!acceptingOperations.get()) {
            pendingRelocations.remove(requiredPlayerId, tracked);
            tracked.complete(false);
            return RelocationStatus.SHUTTING_DOWN;
        }
        final TeleportBypassTokens.Token token = teleportBypassTokens.issue(requiredPlayerId, target);
        try {
            teleportGateway.teleport(requiredPlayerId, target, Optional.empty())
                .whenComplete((teleported, failure) -> {
                    teleportBypassTokens.clear(token);
                    pendingRelocations.remove(requiredPlayerId, tracked);
                    if (failure == null) {
                        tracked.complete(Boolean.TRUE.equals(teleported));
                    } else {
                        tracked.completeExceptionally(failure);
                    }
                });
        } catch (final RuntimeException failure) {
            teleportBypassTokens.clear(token);
            pendingRelocations.remove(requiredPlayerId, tracked);
            tracked.completeExceptionally(failure);
            return RelocationStatus.FAILED;
        }
        return RelocationStatus.SCHEDULED;
    }

    public void relocatePlayersInWorld(
        final org.bukkit.World world,
        final WorldRuntimeGateway.LifecycleWorld expectedWorld,
        final WorldThreadDispatcher dispatcher
    ) {
        final org.bukkit.World requiredWorld = Objects.requireNonNull(world, "world");
        final WorldRuntimeGateway.LifecycleWorld expected = Objects.requireNonNull(expectedWorld, "expectedWorld");
        final WorldThreadDispatcher requiredDispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        if (!PaperWorldIdentity.capture(requiredWorld).snapshot().equals(expected.identity())) {
            return;
        }
        for (final org.bukkit.entity.Player player : java.util.List.copyOf(requiredWorld.getPlayers())) {
            requiredDispatcher.executeFor(player, () -> {
                final org.bukkit.World currentWorld = player.getWorld();
                final PaperWorldIdentity current = PaperWorldIdentity.capture(currentWorld);
                if (!current.snapshot().equals(expected.identity())
                    || current.lifecycleCapability() != expected.lifecycleCapability()) {
                    return;
                }
                relocateIfNeeded(
                    player.getUniqueId(),
                    metadataService.resolveRuntimeWorld(current.snapshot(), current.lifecycleCapability()),
                    player.hasPermission(BYPASS_PERMISSION)
                );
            }, () -> { });
        }
    }

    public CompletableFuture<Void> beginShutdown() {
        acceptingOperations.set(false);
        return CompletableFuture.allOf(pendingRelocations.values().toArray(CompletableFuture[]::new));
    }

    private Optional<VerifiedWorldRef> resolveFallback(final String isolatedWorldId) {
        return fallback
            .filter(target -> !target.worldId().equals(isolatedWorldId))
            .filter(target -> metadataService.managedWorld(target.worldId())
                .filter(metadata -> metadata.lifecycleCapability() == LifecycleCapability.MANAGED)
                .flatMap(VerifiedWorldRef::from)
                .filter(target::equals)
                .isPresent())
            .filter(target -> loadedWorlds.findUniqueByWorldId(target.worldId())
                .filter(runtime -> runtime.reference().equals(target))
                .filter(runtime -> runtime.lifecycleCapability() == LifecycleCapability.MANAGED)
                .filter(runtime -> metadataService.managedWorld(target.worldId())
                    .map(WorldMetadata::identity)
                    .filter(runtime.identity()::equals)
                    .isPresent())
                .isPresent());
    }

    private void warnUnavailable(final String isolatedWorldId) {
        final long now = nanoTime.getAsLong();
        final AtomicBoolean warn = new AtomicBoolean();
        nextWarningAt.compute(isolatedWorldId, (ignored, next) -> {
            if (next == null || now >= next) {
                warn.set(true);
                return saturatedAdd(now, warningIntervalNanos);
            }
            return next;
        });
        if (warn.get()) {
            warningSink.accept(
                "Player remains isolated in world " + isolatedWorldId
                    + " because no verified loaded fallback is available."
            );
        }
    }

    private static long saturatedAdd(final long value, final long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (final ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public enum RelocationStatus {
        NOT_REQUIRED,
        BYPASSED,
        SCHEDULED,
        ALREADY_PENDING,
        FALLBACK_UNAVAILABLE,
        SHUTTING_DOWN,
        FAILED
    }
}