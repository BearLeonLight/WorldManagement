package io.github.bearl.worldmanagement.world.lifecycle;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.bearl.worldmanagement.protection.PlayerIsolationService;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldRuntimeResolution;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Enforces lifecycle identity isolation independently of optional protection governance. */
public final class WorldIsolationListener implements Listener {

    private final WorldManagementService metadataService;
    private final PlayerIsolationService isolationService;

    public WorldIsolationListener(
        final WorldManagementService metadataService,
        final PlayerIsolationService isolationService
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.isolationService = Objects.requireNonNull(isolationService, "isolationService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent) {
            return;
        }
        rejectIsolatedEntry(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        rejectIsolatedEntry(event);
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

    private void rejectIsolatedEntry(final PlayerTeleportEvent event) {
        if (event.getTo() != null && runtimeWorld(event.getTo().getWorld()).status()
            == WorldRuntimeResolution.Status.ISOLATED) {
            event.setCancelled(true);
        }
    }

    private void relocateIfIsolated(final Player player) {
        isolationService.relocateIfNeeded(player.getUniqueId(), runtimeWorld(player.getWorld()));
    }

    private WorldRuntimeResolution runtimeWorld(final World world) {
        if (world == null) {
            return WorldRuntimeResolution.unmanaged();
        }
        final PaperWorldIdentity observed = PaperWorldIdentity.capture(world);
        return metadataService.resolveRuntimeWorld(observed.snapshot(), observed.lifecycleCapability());
    }
}