package io.github.bearl.worldmanagement.storage.yaml;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.UnsupportedStorageSchemaException;
import io.github.bearl.worldmanagement.storage.WorldMetadataCodec;
import io.github.bearl.worldmanagement.world.AccessControl;
import io.github.bearl.worldmanagement.world.AccessMode;
import io.github.bearl.worldmanagement.world.DisplayNameValidator;
import io.github.bearl.worldmanagement.world.IdentityVerificationState;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.Rank;
import io.github.bearl.worldmanagement.world.RankPermission;
import io.github.bearl.worldmanagement.world.RequestedWorldType;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import io.github.bearl.worldmanagement.world.WorldEnvironment;
import io.github.bearl.worldmanagement.world.WorldIdentitySnapshot;
import io.github.bearl.worldmanagement.world.WorldLoadState;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldWarp;
import io.github.bearl.worldmanagement.world.WarpVisibility;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Maps typed world metadata to and from a single BoostedYAML document. */
public final class YamlWorldMetadataCodec implements WorldMetadataCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String NONE = "NONE";
    private final DisplayNameValidator displayNameValidator = new DisplayNameValidator();

    @Override
    public String encode(final WorldMetadata metadata) {
        try {
            final YamlDocument document = YamlDocument.create(new ByteArrayInputStream(new byte[0]));
            document.set("schema-version", CURRENT_SCHEMA_VERSION);
            document.set("world-id", metadata.worldName());
            document.set("display-name", metadata.displayName());
            writeIdentity(document, "identity.accepted", metadata.identity());
            document.set("identity.verification-state", metadata.identityState().name());
            document.set("identity.lifecycle-capability", metadata.lifecycleCapability().name());
            document.set("identity.pending.present", metadata.pendingIdentity().isPresent());
            metadata.pendingIdentity().ifPresent(identity -> writeIdentity(document, "identity.pending.snapshot", identity));
            document.set("creation.requested-world-type", metadata.requestedWorldType().map(Enum::name).orElse(NONE));
            document.set("management-state", metadata.managementState().name());
            document.set("desired-state", metadata.desiredState().name());
            document.set("owner", metadata.owner());
            document.set("version", metadata.version());
            document.set("rank-system.enabled", metadata.rankSystemEnabled());
            document.set("access-control.mode", metadata.accessControl().mode().name());
            document.set("access-control.entries", metadata.accessControl().entries().stream().map(UUID::toString).sorted().toList());

            metadata.ranks().forEach((rankId, rank) -> {
                document.set("ranks." + rankId + ".display-name", rank.displayName());
                document.set("ranks." + rankId + ".permissions", rank.permissions().stream().map(Enum::name).sorted().toList());
            });
            document.set("players", Map.of());
            metadata.playerRanks().forEach((playerId, rankId) -> document.set("players." + playerId, rankId));
            document.set("warps", Map.of());
            metadata.warps().forEach((name, warp) -> {
                final String route = "warps." + name;
                document.set(route + ".x", warp.x());
                document.set(route + ".y", warp.y());
                document.set(route + ".z", warp.z());
                document.set(route + ".yaw", warp.yaw());
                document.set(route + ".pitch", warp.pitch());
                document.set(route + ".visibility", warp.visibility().name());
                document.set(route + ".trusted-players", warp.trustedPlayers().stream().map(UUID::toString).sorted().toList());
                document.set(route + ".trusted-ranks", warp.trustedRanks().stream().sorted().toList());
                document.set(route + ".required-permission", warp.requiredPermission());
            });
            return document.dump();
        } catch (final IOException exception) {
            throw new StorageException("Could not encode world metadata.", exception);
        }
    }

    @Override
    public WorldMetadata decode(final String yaml) {
        try {
            final YamlDocument document = YamlDocument.create(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            requireSchemaVersion(document);
            final String worldName = requireString(document, "world-id");
            final String displayName = requireString(document, "display-name");
            displayNameValidator.validate(displayName);
            final WorldIdentitySnapshot acceptedIdentity = parseIdentity(document, "identity.accepted");
            final IdentityVerificationState identityState = IdentityVerificationState.valueOf(
                requireString(document, "identity.verification-state")
            );
            final LifecycleCapability lifecycleCapability = LifecycleCapability.valueOf(
                requireString(document, "identity.lifecycle-capability")
            );
            final boolean pendingPresent = requireBoolean(document, "identity.pending.present");
            final Optional<WorldIdentitySnapshot> pendingIdentity = pendingPresent
                ? Optional.of(parseIdentity(document, "identity.pending.snapshot"))
                : Optional.empty();
            final String requestedWorldType = requireString(document, "creation.requested-world-type");
            return new WorldMetadata(
                worldName,
                displayName,
                acceptedIdentity,
                identityState,
                lifecycleCapability,
                pendingIdentity,
                NONE.equals(requestedWorldType)
                    ? Optional.empty()
                    : Optional.of(RequestedWorldType.valueOf(requestedWorldType)),
                WorldManagementState.valueOf(requireString(document, "management-state")),
                WorldLoadState.valueOf(requireString(document, "desired-state")),
                requireString(document, "owner"),
                requireBoolean(document, "rank-system.enabled"),
                new AccessControl(
                    parseAccessMode(requireString(document, "access-control.mode")),
                    parseUuidSet(document.getStringList("access-control.entries"))
                ),
                parseRanks(requireSection(document, "ranks")),
                parsePlayerRanks(document.getSection("players")),
                parseWarps(requireSection(document, "warps")),
                requireLong(document, "version")
            );
        } catch (final IOException | IllegalArgumentException exception) {
            throw new StorageException("Could not decode world metadata.", exception);
        }
    }

    public int schemaVersion(final String yaml) {
        try {
            final YamlDocument document = YamlDocument.create(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            return requireSchemaVersion(document);
        } catch (final IOException | IllegalArgumentException exception) {
            if (exception instanceof UnsupportedStorageSchemaException unsupported) {
                throw unsupported;
            }
            throw new StorageException("Could not read world metadata schema version.", exception);
        }
    }

    private static int requireSchemaVersion(final YamlDocument document) {
        final int schemaVersion = document.getInt("schema-version", -1);
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedStorageSchemaException("Unsupported world metadata schema version: " + schemaVersion);
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Missing or invalid world metadata schema version.");
        }
        return schemaVersion;
    }

    private static void writeIdentity(
        final YamlDocument document,
        final String route,
        final WorldIdentitySnapshot identity
    ) {
        document.set(route + ".paper-key", identity.paperKey());
        document.set(route + ".world-uuid", identity.worldUuid().toString());
        document.set(route + ".environment", identity.environment().name());
        document.set(route + ".seed", identity.seed());
        document.set(route + ".generate-structures", identity.generateStructures());
    }

    private static WorldIdentitySnapshot parseIdentity(final Section document, final String route) {
        return new WorldIdentitySnapshot(
            requireString(document, route + ".paper-key"),
            UUID.fromString(requireString(document, route + ".world-uuid")),
            WorldEnvironment.valueOf(requireString(document, route + ".environment")),
            requireLong(document, route + ".seed"),
            requireBoolean(document, route + ".generate-structures")
        );
    }

    private static Map<String, Rank> parseRanks(final Section ranksSection) {
        final Map<String, Rank> ranks = new LinkedHashMap<>();
        for (final Object key : ranksSection.getKeys()) {
            final String rankId = key.toString();
            final Section rankSection = requireSection(ranksSection, rankId);
            final Set<RankPermission> permissions = new LinkedHashSet<>();
            for (final String permission : rankSection.getStringList("permissions")) {
                permissions.add(RankPermission.valueOf(permission));
            }
            ranks.put(rankId, new Rank(requireString(rankSection, "display-name"), permissions));
        }
        return ranks;
    }

    private static Map<UUID, String> parsePlayerRanks(final Section playersSection) {
        final Map<UUID, String> playerRanks = new LinkedHashMap<>();
        if (playersSection == null) {
            return playerRanks;
        }
        for (final Object key : playersSection.getKeys()) {
            final UUID playerId = UUID.fromString(key.toString());
            playerRanks.put(playerId, requireString(playersSection, key.toString()));
        }
        return playerRanks;
    }

    private static Map<String, WorldWarp> parseWarps(final Section warpsSection) {
        if (warpsSection == null) {
            return Map.of();
        }
        final Map<String, WorldWarp> warps = new LinkedHashMap<>();
        for (final Object key : warpsSection.getKeys()) {
            final String name = key.toString();
            final Section warp = requireSection(warpsSection, name);
            warps.put(name, new WorldWarp(
                name,
                requireDouble(warp, "x"), requireDouble(warp, "y"), requireDouble(warp, "z"),
                (float) requireDouble(warp, "yaw"), (float) requireDouble(warp, "pitch"),
                WarpVisibility.valueOf(requireString(warp, "visibility")),
                parseUuidSet(warp.getStringList("trusted-players")),
                Set.copyOf(warp.getStringList("trusted-ranks")),
                warp.getString("required-permission", "")
            ));
        }
        return warps;
    }

    private static AccessMode parseAccessMode(final String value) {
        return AccessMode.valueOf(value);
    }

    private static Set<UUID> parseUuidSet(final Iterable<String> values) {
        final Set<UUID> result = new LinkedHashSet<>();
        for (final String value : values) {
            result.add(UUID.fromString(value));
        }
        return result;
    }

    private static Section requireSection(final Section section, final String route) {
        final Section result = section.getSection(route);
        if (result == null) {
            throw new IllegalArgumentException("Missing YAML section: " + route);
        }
        return result;
    }

    private static String requireString(final Section section, final String route) {
        final String result = section.getString(route, null);
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("Missing YAML string: " + route);
        }
        return result;
    }

    private static boolean requireBoolean(final Section section, final String route) {
        final Boolean result = section.getBoolean(route, null);
        if (result == null) {
            throw new IllegalArgumentException("Missing YAML boolean: " + route);
        }
        return result;
    }

    private static long requireLong(final Section section, final String route) {
        final Long result = section.getLong(route, null);
        if (result == null) {
            throw new IllegalArgumentException("Missing YAML long: " + route);
        }
        return result;
    }

    private static double requireDouble(final Section section, final String route) {
        final Double result = section.getDouble(route, null);
        if (result == null) {
            throw new IllegalArgumentException("Missing YAML decimal: " + route);
        }
        return result;
    }
}
