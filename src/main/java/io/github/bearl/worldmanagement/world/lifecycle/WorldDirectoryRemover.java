package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.storage.StorageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Blocking directory deletion service. Invoke only from PluginIoExecutor. */
public final class WorldDirectoryRemover {

    private final WorldNameValidator nameValidator;

    public WorldDirectoryRemover(final WorldNameValidator nameValidator) {
        this.nameValidator = Objects.requireNonNull(nameValidator, "nameValidator");
    }

    public void deleteDirectChild(final Path worldContainer, final String worldName) {
        final Path worldDirectory = nameValidator.requireExistingDirectChild(worldContainer, worldName);
        try {
            Files.walkFileTree(worldDirectory, new SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(final Path directory, final IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException exception) {
            throw new StorageException("Could not delete world directory.", exception);
        }
    }
}