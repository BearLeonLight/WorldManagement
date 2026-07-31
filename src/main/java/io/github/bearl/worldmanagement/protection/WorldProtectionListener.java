package io.github.bearl.worldmanagement.protection;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.bearl.worldmanagement.ownership.WorldAuthorizationService;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldRuntimeResolution;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.lifecycle.PaperWorldIdentity;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class WorldProtectionListener implements Listener {

    private static final String BYPASS_PERMISSION = "worldmanagement.bypass.protection";

    private final WorldManagementService service;
    private final WorldAuthorizationService authorization;
    private final DiagnosticLogger diagnostics;
    private final TeleportBypassTokens teleportBypassTokens;
    private final PlayerIsolationService playerIsolationService;
    private final ConcurrentHashMap<String, LongAdder> denied = new ConcurrentHashMap<>();
    private final AtomicLong nextSummaryAt = new AtomicLong(System.nanoTime() + TimeUnit.SECONDS.toNanos(30));

    public WorldProtectionListener(final WorldManagementService service, final WorldAccessPolicy policy) {
        this(service, policy, null, new TeleportBypassTokens(), null);
    }

    public WorldProtectionListener(
        final WorldManagementService service,
        final WorldAccessPolicy policy,
        final DiagnosticLogger diagnostics
    ) {
        this(service, policy, diagnostics, new TeleportBypassTokens(), null);
    }

    public WorldProtectionListener(
        final WorldManagementService service,
        final WorldAccessPolicy policy,
        final DiagnosticLogger diagnostics,
        final TeleportBypassTokens teleportBypassTokens
    ) {
        this(service, policy, diagnostics, teleportBypassTokens, null);
    }

    public WorldProtectionListener(
        final WorldManagementService service,
        final WorldAccessPolicy policy,
        final DiagnosticLogger diagnostics,
        final TeleportBypassTokens teleportBypassTokens,
        final PlayerIsolationService playerIsolationService
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.authorization = new WorldAuthorizationService(Objects.requireNonNull(policy, "policy"));
        this.diagnostics = diagnostics;
        this.teleportBypassTokens = Objects.requireNonNull(teleportBypassTokens, "teleportBypassTokens");
        this.playerIsolationService = playerIsolationService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent) {
            return;
        }
        enforceEntry(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        enforceEntry(event);
    }

    private void enforceEntry(final PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }
        final Player player = event.getPlayer();
        final WorldRuntimeResolution resolution = runtimeWorld(event.getTo().getWorld());
        final boolean tokenAccepted = resolution.status() == WorldRuntimeResolution.Status.VERIFIED
            && resolution.metadata().flatMap(VerifiedWorldRef::from)
                .filter(target -> teleportBypassTokens.consume(player.getUniqueId(), target))
                .isPresent();
        if (tokenAccepted || authorization.allowsEntry(
            resolution, player.getUniqueId(), bypasses(player)
        )) {
            return;
        }
        event.setCancelled(true);
        recordDenied(event.getTo().getWorld(), "entry");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        relocateIfIsolated(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(final PlayerChangedWorldEvent event) {
        relocateIfIsolated(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(final PlayerPostRespawnEvent event) {
        relocateIfIsolated(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (!allows(event.getPlayer(), event.getBlock().getWorld(), RankPermission.BUILD)) {
            event.setCancelled(true);
            recordDenied(event.getBlock().getWorld(), "build");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (!allows(event.getPlayer(), event.getBlock().getWorld(), RankPermission.BUILD)) {
            event.setCancelled(true);
            recordDenied(event.getBlock().getWorld(), "build");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && !allows(event.getPlayer(), event.getClickedBlock().getWorld(), RankPermission.INTERACT)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            recordDenied(event.getClickedBlock().getWorld(), "interact");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        if (event.getInventory().getLocation() == null || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!allows(player, event.getInventory().getLocation().getWorld(), RankPermission.CONTAINER)) {
            event.setCancelled(true);
            recordDenied(event.getInventory().getLocation().getWorld(), "container");
        }
    }

    private boolean allowsEntry(final Player player, final World world) {
        return authorization.allowsEntry(runtimeWorld(world), player.getUniqueId(), bypasses(player));
    }

    private boolean allows(final Player player, final World world, final RankPermission permission) {
        return authorization.allows(runtimeWorld(world), player.getUniqueId(), permission, bypasses(player));
    }

    private WorldRuntimeResolution runtimeWorld(final World world) {
        if (world == null) {
            return WorldRuntimeResolution.unmanaged();
        }
        final PaperWorldIdentity observed = PaperWorldIdentity.capture(world);
        return service.resolveRuntimeWorld(observed.snapshot(), observed.lifecycleCapability());
    }

    private void relocateIfIsolated(final Player player) {
        if (playerIsolationService != null) {
            playerIsolationService.relocateIfNeeded(
                player.getUniqueId(), runtimeWorld(player.getWorld()), bypasses(player)
            );
        }
    }

    private static boolean bypasses(final Player player) {
        return player.hasPermission(BYPASS_PERMISSION);
    }

    private void recordDenied(final World world, final String reason) {
        if (diagnostics == null || world == null) {
            return;
        }
        denied.computeIfAbsent(world.getName() + ':' + reason, ignored -> new LongAdder()).increment();
        final long now = System.nanoTime();
        final long expected = nextSummaryAt.get();
        if (now < expected || !nextSummaryAt.compareAndSet(expected, now + TimeUnit.SECONDS.toNanos(30))) {
            return;
        }
        denied.forEach((key, count) -> {
            final long total = count.sumThenReset();
            if (total > 0) {
                final int separator = key.lastIndexOf(':');
                diagnostics.basic(DebugArea.PROTECTION, "protection_denied_summary", () -> java.util.Map.of(
                    "world", key.substring(0, separator),
                    "reason", key.substring(separator + 1),
                    "count", Long.toString(total),
                    "windowSeconds", "30"
                ));
            }
        });
    }
}