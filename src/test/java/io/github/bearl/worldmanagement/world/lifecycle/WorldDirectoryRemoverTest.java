package io.github.bearl.worldmanagement.world.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.bearl.worldmanagement.core.WorldNameValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldDirectoryRemoverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesOnlyTheValidatedWorldDirectory() throws Exception {
        final Path worldDirectory = Files.createDirectories(temporaryDirectory.resolve("creative").resolve("region"));
        Files.writeString(worldDirectory.resolve("r.0.0.mca"), "metadata");
        Files.writeString(temporaryDirectory.resolve("keep.txt"), "keep");

        new WorldDirectoryRemover(new WorldNameValidator()).deleteDirectChild(temporaryDirectory, "creative");

        assertFalse(Files.exists(temporaryDirectory.resolve("creative")));
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(temporaryDirectory.resolve("keep.txt")));
    }
}