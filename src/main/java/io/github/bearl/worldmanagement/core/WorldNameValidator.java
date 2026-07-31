package io.github.bearl.worldmanagement.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validates managed world names and confines their directories to a world container. */
public final class WorldNameValidator {

    private static final Pattern WORLD_NAME_PATTERN = Pattern.compile("^[a-z0-9_-]+$");

    public String requireValidName(final String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        if (!WORLD_NAME_PATTERN.matcher(worldName).matches()) {
            throw new IllegalArgumentException("World IDs may only contain lowercase letters, numbers, underscores, and hyphens.");
        }
        return worldName;
    }

    public Path resolveDirectChild(final Path containerDirectory, final String worldName) {
        Objects.requireNonNull(containerDirectory, "containerDirectory");
        requireValidName(worldName);

        final Path normalizedContainer = containerDirectory.toAbsolutePath().normalize();
        final Path targetDirectory = normalizedContainer.resolve(worldName).toAbsolutePath().normalize();
        if (!normalizedContainer.equals(targetDirectory.getParent())) {
            throw new SecurityException("World directory must be a direct child of the configured container.");
        }
        if (Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeExistingDirectChild(normalizedContainer, targetDirectory, worldName);
        }
        return targetDirectory;
    }

    public Path requireExistingDirectChild(final Path containerDirectory, final String worldName) {
        final Path targetDirectory = resolveDirectChild(containerDirectory, worldName);
        if (!Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("World directory does not exist.");
        }
        final BasicFileAttributes attributes = requireSafeExistingDirectChild(
            containerDirectory.toAbsolutePath().normalize(), targetDirectory, worldName
        );
        if (!attributes.isDirectory()) {
            throw new IllegalArgumentException("World directory does not exist.");
        }
        return targetDirectory;
    }

    public Path requireImportableWorld(final Path containerDirectory, final String worldName) {
        final Path worldDirectory = requireExistingDirectChild(containerDirectory, worldName);
        if (!Files.isRegularFile(worldDirectory.resolve("level.dat"))) {
            throw new IllegalArgumentException("World directory does not contain level.dat.");
        }
        return worldDirectory;
    }

    private static BasicFileAttributes requireSafeExistingDirectChild(
        final Path containerDirectory,
        final Path targetDirectory,
        final String worldName
    ) {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(
                targetDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new SecurityException("World directory must not be a linked or special filesystem entry.");
            }
            final Path realContainer = containerDirectory.toRealPath();
            final Path realTarget = targetDirectory.toRealPath();
            if (!realTarget.equals(realContainer.resolve(worldName))) {
                throw new SecurityException("World directory escaped the configured container.");
            }
            return attributes;
        } catch (final IOException exception) {
            throw new SecurityException("Could not validate world directory confinement.", exception);
        }
    }
}
