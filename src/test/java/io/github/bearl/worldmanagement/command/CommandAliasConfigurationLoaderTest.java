package io.github.bearl.worldmanagement.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.module.ModuleId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CommandAliasConfigurationLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsDefaultConfigurationWithOnlyRootAliases() {
        final CommandAliasConfiguration configuration = new CommandAliasConfigurationLoader().load(temporaryDirectory);

        assertEquals(List.of("worldmanager", "worldmanagement"), configuration.rootAliases());
        assertEquals(List.of(), configuration.aliases(ModuleId.WARP));
        assertTrue(configuration.helpPlayersEnabled());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("commands.yml")));
    }

    @Test
    void loadsMultipleUnicodeModuleAliases() throws Exception {
        Files.writeString(temporaryDirectory.resolve("commands.yml"), """
            commands:
              root-aliases: [wmadmin]
              modules:
                warp:
                  aliases: [warp, 地標, warps]
                ownership:
                  aliases: []
                storage:
                  aliases: [wmstorage]
            """);

        final CommandAliasConfiguration configuration = new CommandAliasConfigurationLoader().load(temporaryDirectory);

        assertEquals(List.of("warp", "地標", "warps"), configuration.aliases(ModuleId.WARP));
        assertEquals(List.of("wmstorage"), configuration.aliases(ModuleId.STORAGE));
    }

    @Test
    void rejectsAliasesSharedByTwoCommandRoots() throws Exception {
        Files.writeString(temporaryDirectory.resolve("commands.yml"), """
            commands:
              root-aliases: [warp]
              modules:
                warp:
                  aliases: [warp]
            """);

        assertThrows(IllegalArgumentException.class, () -> new CommandAliasConfigurationLoader().load(temporaryDirectory));
    }

    @Test
    void loadsPlayerHelpAccessSetting() throws Exception {
        Files.writeString(temporaryDirectory.resolve("commands.yml"), """
            commands:
              root-aliases: []
              help:
                players-enabled: false
            """);

        assertTrue(!new CommandAliasConfigurationLoader().load(temporaryDirectory).helpPlayersEnabled());
    }
}