package io.github.bearl.worldmanagement.warp;

import io.github.bearl.worldmanagement.audit.AuditEvent;
import io.github.bearl.worldmanagement.core.DiagnosticLogger;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.hook.CachedPermissionLookup;
import io.github.bearl.worldmanagement.world.WorldAccessPolicy;
import io.github.bearl.worldmanagement.world.WorldManagementService;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.lifecycle.LoadedWorldCatalog;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Coordinates Warp metadata writes and cache-only authorization. */
public final class WarpService {

    private final WorldManagementService metadataService;
    private final WorldAccessPolicy accessPolicy;
    private final DestinationWorldPermissionResolver destinationPermissions;
    private final DiagnosticLogger diagnostics;

    public WarpService(final WorldManagementService metadataService, final WorldAccessPolicy accessPolicy) {
        this(metadataService, accessPolicy, unavailableDestinationPermissions(), null);
    }

    public WarpService(
        final WorldManagementService metadataService,
        final WorldAccessPolicy accessPolicy,
        final DiagnosticLogger diagnostics
    ) {
        this(metadataService, accessPolicy, unavailableDestinationPermissions(), diagnostics);
    }

    public WarpService(
        final WorldManagementService metadataService,
        final WorldAccessPolicy accessPolicy,
        final DestinationWorldPermissionResolver destinationPermissions
    ) {
        this(metadataService, accessPolicy, destinationPermissions, null);
    }

    public WarpService(
        final WorldManagementService metadataService,
        final WorldAccessPolicy accessPolicy,
        final DestinationWorldPermissionResolver destinationPermissions,
        final DiagnosticLogger diagnostics
    ) {
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.destinationPermissions = Objects.requireNonNull(destinationPermissions, "destinationPermissions");
        this.diagnostics = diagnostics;
    }

    public CompletableFuture<WorldManagementService.UpdateResult> set(final String worldName, final WorldWarp warp) {
        return metadataService.updateManaged(worldName, metadata -> metadata.withWarp(warp));
    }

    public CompletableFuture<WorldManagementService.UpdateResult> set(final String worldName, final WorldWarp warp, final AuditEvent event) {
        return metadataService.updateManaged(worldName, metadata -> metadata.withWarp(warp), event);
    }

    public CompletableFuture<WorldManagementService.UpdateResult> delete(final String worldName, final String warpName) {
        return metadataService.updateManaged(worldName, metadata -> metadata.withoutWarp(warpName));
    }

    public CompletableFuture<WorldManagementService.UpdateResult> delete(final String worldName, final String warpName, final AuditEvent event) {
        return metadataService.updateManaged(worldName, metadata -> metadata.withoutWarp(warpName), event);
    }

    public CompletableFuture<WorldManagementService.UpdateResult> trust(
        final String worldName,
        final String warpName,
        final UUID playerId,
        final boolean trusted
    ) {
        return metadataService.updateManaged(
            worldName, metadata -> metadata.withTrustedWarpPlayer(warpName, playerId, trusted)
        );
    }

    public CompletableFuture<WorldManagementService.UpdateResult> trust(
        final String worldName,
        final String warpName,
        final UUID playerId,
        final boolean trusted,
        final AuditEvent event
    ) {
        return metadataService.updateManaged(
            worldName, metadata -> metadata.withTrustedWarpPlayer(warpName, playerId, trusted), event
        );
    }

    public CompletableFuture<TeleportResult> teleport(
        final UUID playerId,
        final String worldName,
        final String warpName,
        final boolean bypass,
        final WarpTeleportGateway gateway
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(gateway, "gateway");
        final WorldMetadata metadata = metadataService.managedWorld(worldName).orElse(null);
        final WorldWarp warp = metadata == null ? null : metadata.warps().get(warpName);
        final VerifiedWorldRef target = metadata == null ? null : VerifiedWorldRef.from(metadata).orElse(null);
        final CompletableFuture<TeleportResult> result;
        if (metadata == null) {
            result = CompletableFuture.completedFuture(TeleportResult.notManaged());
        } else if (warp == null) {
            result = CompletableFuture.completedFuture(TeleportResult.notFound());
        } else if (target == null || !accessPolicy.allowsWarp(
            metadata,
            warp,
            playerId,
            bypass,
            destinationPermissions.hasPermission(playerId, target, warp.requiredPermission())
        )) {
            result = CompletableFuture.completedFuture(TeleportResult.denied());
        } else {
            result = gateway.teleport(playerId, target, warp)
                .handle((teleported, failure) -> failure == null && teleported
                    ? TeleportResult.teleported()
                    : TeleportResult.failed());
        }
        if (diagnostics != null) {
            result.thenAccept(outcome -> diagnostics.basic(DebugArea.WARP, "warp_teleport_completed", () -> java.util.Map.of(
                    "actorUuid", playerId.toString(),
                    "world", worldName,
                    "warp", warpName,
                    "outcome", outcome.status().name().toLowerCase(java.util.Locale.ROOT)
                )));
            if (warp != null) {
                diagnostics.verbose(DebugArea.WARP, "warp_location_resolved", () -> java.util.Map.of(
                    "world", worldName,
                    "warp", warpName,
                    "block", "%d,%d,%d".formatted((int) Math.floor(warp.x()), (int) Math.floor(warp.y()), (int) Math.floor(warp.z()))
                ));
            }
        }
        return result;
    }

    private static DestinationWorldPermissionResolver unavailableDestinationPermissions() {
        return new DestinationWorldPermissionResolver(new LoadedWorldCatalog(), CachedPermissionLookup.denyAll());
    }

    public enum TeleportStatus {
        TELEPORTED,
        NOT_MANAGED,
        NOT_FOUND,
        DENIED,
        FAILED
    }

    public record TeleportResult(TeleportStatus status) {
        private static TeleportResult teleported() { return new TeleportResult(TeleportStatus.TELEPORTED); }
        private static TeleportResult notManaged() { return new TeleportResult(TeleportStatus.NOT_MANAGED); }
        private static TeleportResult notFound() { return new TeleportResult(TeleportStatus.NOT_FOUND); }
        private static TeleportResult denied() { return new TeleportResult(TeleportStatus.DENIED); }
        private static TeleportResult failed() { return new TeleportResult(TeleportStatus.FAILED); }
    }
}