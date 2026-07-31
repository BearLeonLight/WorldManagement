package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldNameValidatorTest {

    private final WorldNameValidator validator = new WorldNameValidator();

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsSafeWorldNames() {
        assertEquals("creative_1-test", validator.requireValidName("creative_1-test"));
    }

    @Test
    void rejectsPathTraversalAndSpecialCharacters() {
        assertThrows(IllegalArgumentException.class, () -> validator.requireValidName("../world"));
        assertThrows(IllegalArgumentException.class, () -> validator.requireValidName("world/name"));
        assertThrows(IllegalArgumentException.class, () -> validator.requireValidName("world name"));
        assertThrows(IllegalArgumentException.class, () -> validator.requireValidName("world.name"));
        assertThrows(IllegalArgumentException.class, () -> validator.requireValidName("World"));
    }

    @Test
    void resolvesOnlyDirectChildrenOfTheConfiguredContainer() {
        final Path expected = temporaryDirectory.toAbsolutePath().normalize().resolve("survival");

        assertEquals(expected, validator.resolveDirectChild(temporaryDirectory, "survival"));
    }

    @Test
    void requiresLevelDatForImports() throws IOException {
        final Path worldDirectory = Files.createDirectory(temporaryDirectory.resolve("importable"));
        Files.createFile(worldDirectory.resolve("level.dat"));

        assertEquals(worldDirectory.toAbsolutePath().normalize(), validator.requireImportableWorld(temporaryDirectory, "importable"));
        assertThrows(IllegalArgumentException.class, () -> validator.requireImportableWorld(temporaryDirectory, "missing"));
    }

    @Test
    void rejectsDirectoryJunctionEscapingConfiguredContainer() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        final Path container = Files.createDirectory(temporaryDirectory.resolve("container"));
        final Path external = Files.createDirectory(temporaryDirectory.resolve("external"));
        final Path junction = container.resolve("linked_world");
        final Process process = new ProcessBuilder(
            "cmd.exe", "/c", "mklink", "/J", junction.toString(), external.toString()
        ).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assumptions.assumeTrue(process.waitFor() == 0, "Could not create junction: " + output);

        assertThrows(SecurityException.class, () -> validator.requireExistingDirectChild(container, "linked_world"));
    }
}