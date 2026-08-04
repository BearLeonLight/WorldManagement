package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.world.LifecycleCapability;
import io.github.bearl.worldmanagement.world.VerifiedWorldRef;
import io.github.bearl.worldmanagement.world.WorldManagementState;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Resolves Paper 26.x dimension storage and legacy standalone world directories. */
public final class PaperWorldStorageGateway implements WorldStorageGateway {

    private final Path levelDirectory;
    private final Path worldContainer;
    private final WorldNameValidator nameValidator;
    private final WorldDirectoryRemover directoryRemover;
    private final UUID gatewayId = UUID.randomUUID();

    public PaperWorldStorageGateway(
        final Path levelDirectory,
        final Path worldContainer,
        final WorldNameValidator nameValidator,
        final WorldDirectoryRemover directoryRemover
    ) {
        this.levelDirectory = Objects.requireNonNull(levelDirectory, "levelDirectory").toAbsolutePath().normalize();
        this.worldContainer = Objects.requireNonNull(worldContainer, "worldContainer").toAbsolutePath().normalize();
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
        this.directoryRemover = Objects.requireNonNull(directoryRemover, "directoryRemover");
    }

    @Override
    public boolean exists(final String worldId) {
        return Files.isDirectory(dimensionDirectory(worldId)) || Files.isDirectory(legacyDirectory(worldId));
    }

    @Override
    public boolean isImportable(final String worldId) {
        final Path current = dimensionDirectory(worldId);
        if (Files.isDirectory(current.resolve("data"))) {
            return true;
        }
        final Path legacy = legacyDirectory(worldId);
        return Files.isDirectory(legacy) && Files.isRegularFile(legacy.resolve("level.dat"));
    }

    @Override
    public Optional<ImportClaim> prepareImport(final String worldId) {
        final Path current = dimensionDirectory(worldId);
        final Path legacy = legacyDirectory(worldId);
        final boolean currentExists = Files.exists(current, LinkOption.NOFOLLOW_LINKS);
        final boolean legacyExists = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        if (!currentExists && !legacyExists) {
            return Optional.empty();
        }
        if (currentExists && legacyExists) {
            throw new StorageException("World has ambiguous storage paths: " + worldId);
        }
        final Path locator = currentExists
            ? nameValidator.requireExistingDirectChild(current.getParent(), worldId)
            : nameValidator.requireImportableWorld(legacy.getParent(), worldId);
        final StorageFingerprint fingerprint = currentExists
            ? paperStorageFingerprint(locator)
            : legacyStorageFingerprint(locator);
        return Optional.of(new PaperImportClaim(gatewayId, worldId, locator, fingerprint));
    }

    @Override
    public void validateImportClaim(final ImportClaim importClaim) {
        final PaperImportClaim claim = requireImportClaim(importClaim);
        final Path current = claim.fingerprint().paperLayout()
            ? dimensionDirectory(claim.worldId())
            : legacyDirectory(claim.worldId());
        if (!claim.locator().equals(current)) {
            throw new StorageException("Import claim locator no longer matches the world ID.");
        }
        final StorageFingerprint observed = claim.fingerprint().paperLayout()
            ? paperStorageFingerprint(current)
            : legacyStorageFingerprint(current);
        if (!claim.fingerprint().equals(observed)) {
            throw new StorageException("World storage changed after the import claim was created.");
        }
    }

    @Override
    public Optional<LoadClaim> prepareLoad(final WorldMetadata metadata) {
        final WorldMetadata requiredMetadata = Objects.requireNonNull(metadata, "metadata");
        if (requiredMetadata.lifecycleCapability() != LifecycleCapability.MANAGED) {
            throw new StorageException("External-only worlds cannot be loaded by WorldManagement.");
        }
        final VerifiedWorldRef world = VerifiedWorldRef.from(requiredMetadata)
            .orElseThrow(() -> new StorageException("Only verified worlds may receive a load claim."));
        if (!world.paperKey().equals("minecraft:" + world.worldId())) {
            throw new StorageException("Managed lifecycle requires a minecraft world key matching the world ID.");
        }
        final Path dimension = dimensionDirectory(world.worldId());
        final Path legacy = legacyDirectory(world.worldId());
        final boolean dimensionExists = Files.exists(dimension, LinkOption.NOFOLLOW_LINKS);
        final boolean legacyExists = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        if (!dimensionExists && !legacyExists) {
            return Optional.empty();
        }
        if (dimensionExists && legacyExists) {
            throw new StorageException("World has ambiguous storage paths: " + world.worldId());
        }
        final Path locator = dimensionExists
            ? nameValidator.requireExistingDirectChild(dimension.getParent(), world.worldId())
            : nameValidator.requireImportableWorld(legacy.getParent(), world.worldId());
        final StorageFingerprint fingerprint = dimensionExists
            ? paperStorageFingerprint(locator)
            : legacyStorageFingerprint(locator);
        return Optional.of(new PaperLoadClaim(
            gatewayId, world, requiredMetadata.version(), locator, fingerprint
        ));
    }

    @Override
    public void validateLoadClaim(final LoadClaim loadClaim) {
        final PaperLoadClaim claim = requireLoadClaim(loadClaim);
        final Path expected = claim.locator();
        final Path current = claim.fingerprint().paperLayout()
            ? dimensionDirectory(claim.world().worldId())
            : legacyDirectory(claim.world().worldId());
        if (!expected.equals(current)) {
            throw new StorageException("Load claim locator no longer matches the world identity.");
        }
        final StorageFingerprint observed = claim.fingerprint().paperLayout()
            ? paperStorageFingerprint(current)
            : legacyStorageFingerprint(current);
        if (!claim.fingerprint().equals(observed)) {
            throw new StorageException("World storage changed after the load claim was created.");
        }
    }

    @Override
    public Optional<CreationClaim> prepareCreation(final String worldId) {
        final Path dimension = dimensionDirectory(worldId);
        final Path legacy = legacyDirectory(worldId);
        if (Files.exists(dimension, LinkOption.NOFOLLOW_LINKS)
            || Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(new PaperCreationClaim(gatewayId, worldId, dimension, legacy));
    }

    @Override
    public OwnedCreationClaim bindCreated(
        final CreationClaim creationClaim,
        final VerifiedWorldRef world
    ) {
        final PaperCreationClaim claim = requireCreationClaim(creationClaim);
        final VerifiedWorldRef requiredWorld = Objects.requireNonNull(world, "world");
        if (!claim.worldId().equals(requiredWorld.worldId())
            || !requiredWorld.paperKey().equals("minecraft:" + claim.worldId())) {
            throw new StorageException("Created runtime identity does not match its storage claim.");
        }
        final boolean dimensionExists = Files.exists(claim.dimension(), LinkOption.NOFOLLOW_LINKS);
        final boolean legacyExists = Files.exists(claim.legacy(), LinkOption.NOFOLLOW_LINKS);
        if (dimensionExists == legacyExists) {
            throw new StorageException("Created world has ambiguous or missing storage paths: " + claim.worldId());
        }
        final Path locator = dimensionExists
            ? nameValidator.requireExistingDirectChild(claim.dimension().getParent(), claim.worldId())
            : nameValidator.requireImportableWorld(claim.legacy().getParent(), claim.worldId());
        return new PaperOwnedCreationClaim(
            gatewayId, requiredWorld, locator, rootIdentity(locator), dimensionExists
        );
    }

    @Override
    public void deleteCreated(final OwnedCreationClaim creationClaim) {
        final PaperOwnedCreationClaim claim = requireOwnedCreationClaim(creationClaim);
        final Path alternate = claim.paperLayout()
            ? legacyDirectory(claim.world().worldId())
            : dimensionDirectory(claim.world().worldId());
        if (Files.exists(alternate, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Created world has ambiguous storage paths: " + claim.world().worldId());
        }
        if (!claim.rootIdentity().equals(rootIdentity(claim.locator()))) {
            throw new StorageException("Created world storage changed after ownership was bound.");
        }
        directoryRemover.deleteDirectChild(claim.locator().getParent(), claim.world().worldId());
    }

    @Override
    public QuarantinedWorld quarantine(final WorldMetadata metadata) {
        final WorldMetadata requiredMetadata = Objects.requireNonNull(metadata, "metadata");
        final VerifiedWorldRef world = VerifiedWorldRef.from(requiredMetadata)
            .orElseThrow(() -> new StorageException("Only verified worlds may receive a deletion claim."));
        final String worldId = world.worldId();
        final Path current = dimensionDirectory(worldId);
        final Path legacy = legacyDirectory(worldId);
        final boolean currentExists = Files.exists(current, LinkOption.NOFOLLOW_LINKS);
        final boolean legacyExists = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        if (currentExists == legacyExists) {
            throw new StorageException("World has ambiguous or missing storage paths: " + worldId);
        }
        final Path original = currentExists ? current : legacy;
        final LocatorKind locatorKind = currentExists ? LocatorKind.PAPER : LocatorKind.LEGACY;
        nameValidator.requireExistingDirectChild(original.getParent(), worldId);
        final Path quarantineRoot = original.getParent().resolve(".worldmanagement-quarantine");
        final UUID transactionId = UUID.randomUUID();
        final String token = encodeQuarantineToken(
            world, requiredMetadata.version(), locatorKind, transactionId
        );
        try {
            if (Files.notExists(quarantineRoot, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(quarantineRoot);
            }
            requireSafeQuarantineRoot(original.getParent(), quarantineRoot);
            final Path quarantined = nameValidator.resolveDirectChild(quarantineRoot, token);
            Files.move(original, quarantined, StandardCopyOption.ATOMIC_MOVE);
            return new PaperQuarantinedWorld(
                gatewayId, world, requiredMetadata.version(), transactionId,
                locatorKind, original, quarantineRoot, quarantined, token
            );
        } catch (final IOException exception) {
            throw new StorageException("Could not quarantine world directory.", exception);
        }
    }

    @Override
    public void restore(final QuarantinedWorld quarantinedWorld) {
        final PaperQuarantinedWorld claim = requireClaim(quarantinedWorld);
        try {
            requireSafeQuarantineRoot(claim.original().getParent(), claim.quarantineRoot());
            requireSafeQuarantineEntry(claim);
            Files.move(claim.quarantined(), claim.original(), StandardCopyOption.ATOMIC_MOVE);
            deleteEmptyQuarantineRoot(claim.quarantineRoot());
        } catch (final IOException exception) {
            throw new StorageException("Could not restore quarantined world directory.", exception);
        }
    }

    @Override
    public void delete(final QuarantinedWorld quarantinedWorld) {
        final PaperQuarantinedWorld claim = requireClaim(quarantinedWorld);
        requireSafeQuarantineRoot(claim.original().getParent(), claim.quarantineRoot());
        requireSafeQuarantineEntry(claim);
        directoryRemover.deleteDirectChild(claim.quarantineRoot(), claim.token());
        deleteEmptyQuarantineRoot(claim.quarantineRoot());
    }

    @Override
    public Set<String> recoverQuarantined(final Set<WorldMetadata> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        final Map<String, WorldMetadata> metadataById = new java.util.LinkedHashMap<>();
        for (final WorldMetadata world : metadata) {
            final WorldMetadata duplicate = metadataById.putIfAbsent(world.worldName(), world);
            if (duplicate != null) {
                throw new StorageException("Duplicate world metadata during quarantine recovery: " + world.worldName());
            }
        }
        final List<PaperQuarantinedWorld> claims = new java.util.ArrayList<>();
        claims.addAll(readQuarantineClaims(dimensionDirectory("recovery").getParent(), LocatorKind.PAPER));
        claims.addAll(readQuarantineClaims(worldContainer, LocatorKind.LEGACY));
        final Map<String, RecoveryAction> actions = new java.util.LinkedHashMap<>();
        for (final PaperQuarantinedWorld claim : claims) {
            if (actions.containsKey(claim.world().worldId())) {
                throw new StorageException("World has multiple quarantine claims: " + claim.world().worldId());
            }
            final WorldMetadata current = metadataById.get(claim.world().worldId());
            if (current == null) {
                throw new StorageException("Quarantine claim has no matching metadata: " + claim.world().worldId());
            }
            final VerifiedWorldRef currentWorld = VerifiedWorldRef.from(current)
                .orElseThrow(() -> new StorageException(
                    "Quarantine claim metadata is not verified: " + claim.world().worldId()
                ));
            if (!claim.world().equals(currentWorld)) {
                throw new StorageException("Quarantine claim identity does not match metadata: " + claim.world().worldId());
            }
            if (Files.exists(dimensionDirectory(claim.world().worldId()), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(legacyDirectory(claim.world().worldId()), LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException("Quarantine claim coexists with live storage: " + claim.world().worldId());
            }
            final RecoveryDisposition disposition;
            if (current.managementState() == WorldManagementState.DELETING) {
                final long deletingVersion;
                try {
                    deletingVersion = Math.addExact(claim.metadataVersion(), 1L);
                } catch (final ArithmeticException exception) {
                    throw new StorageException("Quarantine claim version overflow.", exception);
                }
                if (current.version() != deletingVersion) {
                    throw new StorageException("Deleting metadata version does not match its quarantine claim.");
                }
                if (!current.deletionTransactionId().equals(Optional.of(claim.transactionId()))) {
                    throw new StorageException("Deleting metadata transaction does not match its quarantine claim.");
                }
                disposition = RecoveryDisposition.DELETE;
            } else {
                if (current.version() != claim.metadataVersion()) {
                    throw new StorageException("Metadata version does not match its quarantine claim.");
                }
                disposition = RecoveryDisposition.RESTORE;
            }
            actions.put(claim.world().worldId(), new RecoveryAction(claim, disposition));
        }
        final Set<String> completedDeletes = new java.util.HashSet<>();
        for (final RecoveryAction action : actions.values()) {
            if (action.disposition() == RecoveryDisposition.DELETE) {
                delete(action.claim());
                completedDeletes.add(action.claim().world().worldId());
            } else {
                restore(action.claim());
            }
        }
        for (final WorldMetadata world : metadataById.values()) {
            if (world.managementState() != WorldManagementState.DELETING
                || actions.containsKey(world.worldName())) {
                continue;
            }
            if (Files.exists(dimensionDirectory(world.worldName()), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(legacyDirectory(world.worldName()), LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException("Deleting world has live storage but no quarantine claim: " + world.worldName());
            }
            completedDeletes.add(world.worldName());
        }
        return Set.copyOf(completedDeletes);
    }

    private Path dimensionDirectory(final String worldId) {
        final Path minecraftDimensions = levelDirectory.resolve("dimensions").resolve("minecraft");
        return nameValidator.resolveDirectChild(minecraftDimensions, worldId);
    }

    private Path legacyDirectory(final String worldId) {
        return nameValidator.resolveDirectChild(worldContainer, worldId);
    }

    private PaperQuarantinedWorld requireClaim(final QuarantinedWorld quarantinedWorld) {
        if (!(quarantinedWorld instanceof PaperQuarantinedWorld claim) || !gatewayId.equals(claim.gatewayId())) {
            throw new IllegalArgumentException("Quarantined world claim does not belong to this storage gateway.");
        }
        return claim;
    }

    private PaperCreationClaim requireCreationClaim(final CreationClaim creationClaim) {
        if (!(creationClaim instanceof PaperCreationClaim claim) || !gatewayId.equals(claim.gatewayId())) {
            throw new IllegalArgumentException("Created world claim does not belong to this storage gateway.");
        }
        return claim;
    }

    private PaperOwnedCreationClaim requireOwnedCreationClaim(final OwnedCreationClaim creationClaim) {
        if (!(creationClaim instanceof PaperOwnedCreationClaim claim) || !gatewayId.equals(claim.gatewayId())) {
            throw new IllegalArgumentException("Owned creation claim does not belong to this storage gateway.");
        }
        return claim;
    }

    private PaperImportClaim requireImportClaim(final ImportClaim importClaim) {
        if (!(importClaim instanceof PaperImportClaim claim) || !gatewayId.equals(claim.gatewayId())) {
            throw new IllegalArgumentException("Import claim does not belong to this storage gateway.");
        }
        return claim;
    }

    private PaperLoadClaim requireLoadClaim(final LoadClaim loadClaim) {
        if (!(loadClaim instanceof PaperLoadClaim claim) || !gatewayId.equals(claim.gatewayId())) {
            throw new IllegalArgumentException("Load claim does not belong to this storage gateway.");
        }
        return claim;
    }

    private StorageFingerprint paperStorageFingerprint(final Path locator) {
        return storageFingerprint(locator, true, List.of(
            locator.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat"),
            locator.resolve("data").resolve("paper").resolve("metadata.dat"),
            locator.resolve("data").resolve("paper").resolve("level_overrides.dat")
        ));
    }

    private StorageFingerprint legacyStorageFingerprint(final Path locator) {
        return storageFingerprint(locator, false, List.of(locator.resolve("level.dat")));
    }

    private static StorageRootIdentity rootIdentity(final Path locator) {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(
                locator, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new StorageException("Created world storage locator must be a regular directory.");
            }
            return new StorageRootIdentity(
                locator.toRealPath(),
                attributes.fileKey() == null ? Optional.empty() : Optional.of(attributes.fileKey().toString()),
                attributes.creationTime()
            );
        } catch (final IOException exception) {
            throw new StorageException("Could not validate created world storage ownership.", exception);
        }
    }

    private static StorageFingerprint storageFingerprint(
        final Path locator,
        final boolean paperLayout,
        final List<Path> markers
    ) {
        try {
            final BasicFileAttributes rootAttributes = Files.readAttributes(
                locator, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink() || rootAttributes.isOther()) {
                throw new StorageException("World storage locator must be a regular directory.");
            }
            final Path realLocator = locator.toRealPath();
            final EntryFingerprint rootFingerprint = entryFingerprint(locator, rootAttributes, false);
            final java.util.Map<Path, EntryFingerprint> markerFingerprints = new java.util.LinkedHashMap<>();
            for (final Path marker : markers) {
                final BasicFileAttributes markerAttributes = Files.readAttributes(
                    marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
                );
                if (!markerAttributes.isRegularFile() || markerAttributes.isSymbolicLink() || markerAttributes.isOther()) {
                    throw new StorageException("World storage marker must be a regular file.");
                }
                final Path realMarker = marker.toRealPath();
                if (!realMarker.startsWith(realLocator)) {
                    throw new StorageException("World storage marker escaped its claimed locator.");
                }
                markerFingerprints.put(
                    realLocator.relativize(realMarker),
                    entryFingerprint(marker, markerAttributes, true)
                );
            }
            return new StorageFingerprint(realLocator, rootFingerprint, Map.copyOf(markerFingerprints), paperLayout);
        } catch (final IOException exception) {
            throw new StorageException("Could not validate persisted world storage.", exception);
        }
    }

    private static EntryFingerprint entryFingerprint(
        final Path entry,
        final BasicFileAttributes attributes,
        final boolean hashContent
    ) throws IOException {
        return new EntryFingerprint(
            attributes.fileKey() == null ? Optional.empty() : Optional.of(attributes.fileKey().toString()),
            attributes.creationTime(),
            attributes.lastModifiedTime(),
            attributes.size(),
            hashContent ? Optional.of(sha256(entry)) : Optional.empty()
        );
    }

    private static String sha256(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
        try (var input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private void requireSafeQuarantineEntry(final PaperQuarantinedWorld claim) {
        try {
            final Path validated = nameValidator.requireExistingDirectChild(claim.quarantineRoot(), claim.token());
            if (!validated.equals(claim.quarantined())) {
                throw new SecurityException("Quarantined world claim no longer matches its storage entry.");
            }
        } catch (final IllegalArgumentException | SecurityException exception) {
            throw new StorageException("Unsafe quarantined world storage entry.", exception);
        }
    }

    private List<PaperQuarantinedWorld> readQuarantineClaims(
        final Path parent,
        final LocatorKind expectedLocatorKind
    ) {
        final Path quarantineRoot = parent.resolve(".worldmanagement-quarantine");
        if (Files.notExists(quarantineRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireSafeQuarantineRoot(parent, quarantineRoot);
        final List<PaperQuarantinedWorld> claims = new java.util.ArrayList<>();
        try (Stream<Path> entries = Files.list(quarantineRoot)) {
            for (final Path entry : entries.toList()) {
                final String token = entry.getFileName().toString();
                final ParsedQuarantineToken parsed = parseQuarantineToken(token);
                if (parsed.locatorKind() != expectedLocatorKind) {
                    throw new StorageException("Quarantine claim is stored under the wrong locator root.");
                }
                final Path original = nameValidator.resolveDirectChild(parent, parsed.world().worldId());
                final PaperQuarantinedWorld claim = new PaperQuarantinedWorld(
                    gatewayId, parsed.world(), parsed.metadataVersion(), parsed.transactionId(),
                    parsed.locatorKind(), original, quarantineRoot, entry, token
                );
                requireSafeQuarantineEntry(claim);
                claims.add(claim);
            }
        } catch (final IOException exception) {
            throw new StorageException("Could not recover quarantined world storage.", exception);
        }
        return List.copyOf(claims);
    }

    private String encodeQuarantineToken(
        final VerifiedWorldRef world,
        final long metadataVersion,
        final LocatorKind locatorKind,
        final UUID transactionId
    ) {
        return String.join(
            "_",
            "q1",
            hex(world.worldId()),
            locatorKind.token(),
            Long.toString(metadataVersion),
            hex(world.paperKey().substring(0, world.paperKey().indexOf(':'))),
            compactUuid(world.worldUuid()),
            compactUuid(transactionId)
        );
    }

    private ParsedQuarantineToken parseQuarantineToken(final String token) {
        try {
            final String[] parts = token.split("_", -1);
            if (parts.length != 7 || !"q1".equals(parts[0])) {
                throw new IllegalArgumentException("Unexpected quarantine token shape.");
            }
            final String worldId = unhex(parts[1]);
            nameValidator.requireValidName(worldId);
            final LocatorKind locatorKind = LocatorKind.fromToken(parts[2]);
            final long metadataVersion = Long.parseLong(parts[3]);
            if (metadataVersion < 0) {
                throw new IllegalArgumentException("Negative metadata version.");
            }
            final String namespace = unhex(parts[4]);
            final UUID worldUuid = expandUuid(parts[5]);
            final UUID transactionId = expandUuid(parts[6]);
            return new ParsedQuarantineToken(
                new VerifiedWorldRef(worldId, namespace + ":" + worldId, worldUuid),
                metadataVersion,
                transactionId,
                locatorKind
            );
        } catch (final IllegalArgumentException exception) {
            throw new StorageException("Invalid quarantined world token: " + token, exception);
        }
    }

    private static String hex(final String value) {
        return java.util.HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String unhex(final String value) {
        return new String(java.util.HexFormat.of().parseHex(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String compactUuid(final UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static UUID expandUuid(final String value) {
        if (value.length() != 32) {
            throw new IllegalArgumentException("UUID must contain 32 hexadecimal characters.");
        }
        return UUID.fromString(
            value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                + "-" + value.substring(16, 20) + "-" + value.substring(20)
        );
    }

    private static void requireSafeQuarantineRoot(final Path parent, final Path quarantineRoot) {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(
                quarantineRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new StorageException("World quarantine root must be a regular directory.");
            }
            final Path realParent = parent.toRealPath();
            final Path realRoot = quarantineRoot.toRealPath();
            if (!realParent.equals(realRoot.getParent())) {
                throw new StorageException("World quarantine root escaped its approved parent.");
            }
        } catch (final IOException exception) {
            throw new StorageException("Could not validate world quarantine directory.", exception);
        }
    }

    private static void deleteEmptyQuarantineRoot(final Path quarantineRoot) {
        if (!Files.isDirectory(quarantineRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> entries = Files.list(quarantineRoot)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(quarantineRoot);
            }
        } catch (final IOException exception) {
            throw new StorageException("Could not remove empty world quarantine directory.", exception);
        }
    }

    private record PaperQuarantinedWorld(
        UUID gatewayId,
        VerifiedWorldRef world,
        long metadataVersion,
        UUID transactionId,
        LocatorKind locatorKind,
        Path original,
        Path quarantineRoot,
        Path quarantined,
        String token
    ) implements QuarantinedWorld {
    }

    private record ParsedQuarantineToken(
        VerifiedWorldRef world,
        long metadataVersion,
        UUID transactionId,
        LocatorKind locatorKind
    ) {
    }

    private record RecoveryAction(PaperQuarantinedWorld claim, RecoveryDisposition disposition) {
    }

    private enum RecoveryDisposition {
        RESTORE,
        DELETE
    }

    private enum LocatorKind {
        PAPER("p"),
        LEGACY("l");

        private final String token;

        LocatorKind(final String token) {
            this.token = token;
        }

        private String token() {
            return token;
        }

        private static LocatorKind fromToken(final String token) {
            return switch (token) {
                case "p" -> PAPER;
                case "l" -> LEGACY;
                default -> throw new IllegalArgumentException("Unknown quarantine locator kind.");
            };
        }
    }

    private record PaperCreationClaim(
        UUID gatewayId,
        String worldId,
        Path dimension,
        Path legacy
    ) implements CreationClaim {
    }

    private record PaperOwnedCreationClaim(
        UUID gatewayId,
        VerifiedWorldRef world,
        Path locator,
        StorageRootIdentity rootIdentity,
        boolean paperLayout
    ) implements OwnedCreationClaim {
    }

    private record PaperImportClaim(
        UUID gatewayId,
        String worldId,
        Path locator,
        StorageFingerprint fingerprint
    ) implements ImportClaim {
    }

    private record PaperLoadClaim(
        UUID gatewayId,
        VerifiedWorldRef world,
        long metadataVersion,
        Path locator,
        StorageFingerprint fingerprint
    ) implements LoadClaim {
    }

    private record StorageFingerprint(
        Path realLocator,
        EntryFingerprint root,
        java.util.Map<Path, EntryFingerprint> markers,
        boolean paperLayout
    ) {
    }

    private record StorageRootIdentity(
        Path realLocator,
        Optional<String> fileKey,
        java.nio.file.attribute.FileTime creationTime
    ) {
    }

    private record EntryFingerprint(
        Optional<String> fileKey,
        java.nio.file.attribute.FileTime creationTime,
        java.nio.file.attribute.FileTime lastModifiedTime,
        long size,
        Optional<String> sha256
    ) {
    }
}