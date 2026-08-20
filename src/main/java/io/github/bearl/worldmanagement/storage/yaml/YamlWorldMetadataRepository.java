package io.github.bearl.worldmanagement.storage.yaml;

import io.github.bearl.worldmanagement.storage.ConcurrentWorldUpdateException;
import io.github.bearl.worldmanagement.storage.StorageException;
import io.github.bearl.worldmanagement.storage.UnsupportedStorageSchemaException;
import io.github.bearl.worldmanagement.storage.WorldMetadataRepository;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.world.WorldMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Stores each world aggregate in one independently recoverable YAML file. */
public final class YamlWorldMetadataRepository implements WorldMetadataRepository {

    private final Path worldsDirectory;
    private final YamlWorldMetadataCodec codec;
    private final WorldNameValidator worldNameValidator = new WorldNameValidator();
    private final int retainedBackupsPerWorld;
    private final Consumer<String> warningSink;

    public YamlWorldMetadataRepository(final Path worldsDirectory) {
        this(
            worldsDirectory,
            io.github.bearl.worldmanagement.storage.StorageConfiguration.DEFAULT_YAML_RETAINED_BACKUPS_PER_WORLD,
            ignored -> { }
        );
    }

    public YamlWorldMetadataRepository(
        final Path worldsDirectory,
        final int retainedBackupsPerWorld,
        final Consumer<String> warningSink
    ) {
        this.worldsDirectory = worldsDirectory.toAbsolutePath().normalize();
        this.codec = new YamlWorldMetadataCodec();
        if (retainedBackupsPerWorld < 1 || retainedBackupsPerWorld > 1_000) {
            throw new IllegalArgumentException("retainedBackupsPerWorld must be between 1 and 1000.");
        }
        this.retainedBackupsPerWorld = retainedBackupsPerWorld;
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    @Override
    public Collection<WorldMetadata> loadAll() {
        ensureDirectory();
        try (Stream<Path> files = Files.list(worldsDirectory)) {
            final List<Path> metadataFiles = files
                .filter(file -> file.getFileName().toString().endsWith(".yml"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
            final List<MetadataDocument> documents = new ArrayList<>();
            for (final Path file : metadataFiles) {
                try {
                    final String serialized = Files.readString(file, StandardCharsets.UTF_8);
                    codec.schemaVersion(serialized);
                    documents.add(new MetadataDocument(file, serialized));
                } catch (final UnsupportedStorageSchemaException exception) {
                    throw exception;
                } catch (final StorageException | IllegalArgumentException exception) {
                    documents.add(new MetadataDocument(file, null));
                }
            }
            final List<WorldMetadata> metadata = new ArrayList<>();
            for (final MetadataDocument document : documents) {
                if (document.serialized() == null) {
                    quarantine(document.file());
                    continue;
                }
                try {
                    metadata.add(codec.decode(document.serialized()));
                } catch (final UnsupportedStorageSchemaException exception) {
                    throw exception;
                } catch (final StorageException | IllegalArgumentException exception) {
                    quarantine(document.file());
                }
            }
            return List.copyOf(metadata);
        } catch (final IOException exception) {
            throw new StorageException("Could not list world metadata files.", exception);
        }
    }

    @Override
    public Optional<WorldMetadata> find(final String worldName) {
        final Path metadataFile = metadataFile(worldName);
        return Files.isRegularFile(metadataFile) ? Optional.of(read(metadataFile)) : Optional.empty();
    }

    @Override
    public void create(final WorldMetadata metadata) {
        final Path metadataFile = metadataFile(metadata.worldName());
        if (Files.exists(metadataFile)) {
            throw new IllegalStateException("World metadata already exists: " + metadata.worldName());
        }
        writeAtomically(metadataFile, metadata);
    }

    @Override
    public void replace(final WorldMetadata metadata, final long expectedVersion) {
        final WorldMetadata current = find(metadata.worldName())
            .orElseThrow(() -> new ConcurrentWorldUpdateException(metadata.worldName()));
        if (current.version() != expectedVersion) {
            throw new ConcurrentWorldUpdateException(metadata.worldName());
        }
        writeAtomically(metadataFile(metadata.worldName()), metadata);
    }

    @Override
    public void delete(final String worldName, final long expectedVersion) {
        final WorldMetadata current = find(worldName).orElseThrow(() -> new ConcurrentWorldUpdateException(worldName));
        if (current.version() != expectedVersion) {
            throw new ConcurrentWorldUpdateException(worldName);
        }
        try {
            backup(metadataFile(worldName));
            Files.delete(metadataFile(worldName));
        } catch (final IOException exception) {
            throw new StorageException("Could not delete world metadata.", exception);
        }
    }

    private WorldMetadata read(final Path metadataFile) {
        try {
            final String serialized = Files.readString(metadataFile, StandardCharsets.UTF_8);
            return codec.decode(serialized);
        } catch (final IOException exception) {
            throw new StorageException("Could not read world metadata.", exception);
        }
    }

    private void writeAtomically(final Path metadataFile, final WorldMetadata metadata) {
        ensureDirectory();
        final Path temporaryFile;
        try {
            if (Files.exists(metadataFile)) {
                backup(metadataFile);
            }
            temporaryFile = Files.createTempFile(worldsDirectory, metadata.worldName() + ".", ".tmp");
            Files.writeString(temporaryFile, codec.encode(metadata), StandardCharsets.UTF_8);
            moveReplacing(temporaryFile, metadataFile);
        } catch (final IOException exception) {
            throw new StorageException("Could not write world metadata.", exception);
        }
    }

    private static void moveReplacing(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path metadataFile(final String worldName) {
        worldNameValidator.requireValidName(worldName);
        final Path file = worldsDirectory.resolve(worldName + ".yml").toAbsolutePath().normalize();
        if (!worldsDirectory.equals(file.getParent())) {
            throw new SecurityException("World metadata must be a direct child of the worlds directory.");
        }
        return file;
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(worldsDirectory);
        } catch (final IOException exception) {
            throw new StorageException("Could not create world metadata directory.", exception);
        }
    }

    private void backup(final Path metadataFile) throws IOException {
        final Path backupDirectory = worldsDirectory.resolve("backup");
        Files.createDirectories(backupDirectory);
        Files.copy(metadataFile, backupDirectory.resolve(
            metadataFile.getFileName().toString() + "." + Instant.now().toEpochMilli()
                + "-" + System.nanoTime() + ".bak"
        ));
        pruneBackups(backupDirectory, metadataFile.getFileName().toString());
    }

    private void pruneBackups(final Path backupDirectory, final String metadataFileName) {
        try (Stream<Path> files = Files.list(backupDirectory)) {
            final String prefix = metadataFileName + ".";
            final List<RetainedFile> backups = new ArrayList<>();
            for (final Path file : files.toList()) {
                final String fileName = file.getFileName().toString();
                if (fileName.startsWith(prefix) && fileName.endsWith(".bak")
                    && Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    backups.add(new RetainedFile(file, Files.getLastModifiedTime(file)));
                }
            }
            backups.sort(Comparator.comparing(RetainedFile::modified)
                .thenComparing(retained -> retained.path().getFileName().toString())
                .reversed());
            for (int index = retainedBackupsPerWorld; index < backups.size(); index++) {
                Files.deleteIfExists(backups.get(index).path());
            }
        } catch (final IOException | RuntimeException exception) {
            warnRetentionFailure("Could not enforce YAML metadata backup retention: " + exception.getMessage());
        }
    }

    private void warnRetentionFailure(final String message) {
        try {
            warningSink.accept(message);
        } catch (final RuntimeException ignored) {
            // Retention cleanup is best-effort and must not invalidate a committed metadata mutation.
        }
    }

    private void quarantine(final Path metadataFile) {
        try {
            final Path quarantineDirectory = worldsDirectory.resolve("quarantine");
            Files.createDirectories(quarantineDirectory);
            Files.move(metadataFile, quarantineDirectory.resolve(metadataFile.getFileName().toString() + "." + Instant.now().toEpochMilli() + ".corrupt"));
        } catch (final IOException exception) {
            throw new StorageException("Could not quarantine corrupt world metadata.", exception);
        }
    }

    private record MetadataDocument(Path file, String serialized) {
    }

    private record RetainedFile(Path path, FileTime modified) {
    }
}
