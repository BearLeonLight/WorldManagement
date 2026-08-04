package io.github.bearl.worldmanagement.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.module.ModuleId;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PluginConfigurationLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAndLoadsYamlAsTheDefaultProvider() {
        final io.github.bearl.worldmanagement.config.PluginConfiguration configuration = new PluginConfigurationLoader().load(temporaryDirectory);

        assertEquals(StorageProvider.YAML, configuration.storage().provider());
        assertEquals(1_000, configuration.deletionDelay().toMillis());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config.yml")));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("modules.yml")));
        assertTrue(configuration.modules().enabled(ModuleId.WARP));
        assertEquals(DebugLevel.OFF, configuration.debug().level());
        assertTrue(configuration.debug().areas().containsAll(java.util.EnumSet.allOf(DebugArea.class)));
        assertTrue(configuration.debug().consoleEnabled());
        assertTrue(configuration.debug().fileEnabled());
                assertTrue(configuration.hooks().luckPermsEnabled());
                assertTrue(configuration.hooks().multiverseEnabled());
    }

        @Test
        void loadsOptionalHookEnablement() throws IOException {
                Files.writeString(temporaryDirectory.resolve("hooks.yml"), """
                        luckperms:
                            enabled: false
                        multiverse:
                            enabled: false
                        """);

                final var hooks = new PluginConfigurationLoader().load(temporaryDirectory).hooks();

                assertTrue(!hooks.luckPermsEnabled());
                assertTrue(!hooks.multiverseEnabled());
        }

    @Test
    void replacesAnExistingBlankConfigurationWithDefaults() throws IOException {
        final Path configurationFile = temporaryDirectory.resolve("config.yml");
        Files.writeString(configurationFile, "  \n\t");

        final io.github.bearl.worldmanagement.config.PluginConfiguration configuration = new PluginConfigurationLoader().load(temporaryDirectory);

        assertEquals(StorageProvider.YAML, configuration.storage().provider());
        assertTrue(Files.readString(configurationFile).contains("provider: YAML"));
    }

    @Test
    void rejectsAnUnsupportedConfiguredProvider() throws IOException {
        Files.writeString(temporaryDirectory.resolve("config.yml"), "schema-version: 1\nstorage:\n  provider: SQLITE\n");

        assertThrows(IllegalArgumentException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));
    }

    @Test
    void acceptsOnlyAnExplicitSchemaOneVersion() throws IOException {
        final Path configurationFile = temporaryDirectory.resolve("config.yml");

        Files.writeString(configurationFile, "storage:\n  provider: YAML\n");
        assertThrows(IllegalArgumentException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));

        Files.writeString(configurationFile, "schema-version: 0\nstorage:\n  provider: YAML\n");
        assertThrows(IllegalArgumentException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));

        Files.writeString(configurationFile, "schema-version: -1\nstorage:\n  provider: YAML\n");
        assertThrows(IllegalArgumentException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));
    }

    @Test
    void rejectsFutureConfigurationSchema() throws IOException {
        Files.writeString(temporaryDirectory.resolve("config.yml"), "schema-version: 2\nstorage:\n  provider: YAML\n");

        assertThrows(UnsupportedStorageSchemaException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));
    }

        @Test
        void rejectsInvalidFallbackWorldName() throws IOException {
                Files.writeString(temporaryDirectory.resolve("config.yml"), """
                        schema-version: 1
                        storage:
                            provider: YAML
                        lifecycle:
                            fallback-world: ../outside
                        """);

                assertThrows(IllegalArgumentException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));
        }

    @Test
    void loadsModuleEnablementFromDedicatedConfiguration() throws IOException {
        Files.writeString(temporaryDirectory.resolve("modules.yml"), "warp:\n  enabled: false\nownership:\n  enabled: true\n");

        final io.github.bearl.worldmanagement.config.PluginConfiguration configuration = new PluginConfigurationLoader().load(temporaryDirectory);

        assertTrue(!configuration.modules().enabled(ModuleId.WARP));
        assertTrue(configuration.modules().enabled(ModuleId.OWNERSHIP));
    }

        @Test
        void loadsDebugSettingsAndTreatsAnEmptyAreaListAsMatchNone() throws IOException {
                Files.writeString(temporaryDirectory.resolve("config.yml"), """
                        schema-version: 1
                        storage:
                            provider: YAML
                        debug:
                            level: verbose
                            sinks:
                                console: false
                                file: true
                            areas: []
                            file:
                                max-file-size-mib: 25
                                retained-files: 10
                            privacy:
                                include-player-names: true
                        """);

                final var configuration = new PluginConfigurationLoader().load(temporaryDirectory).debug();

                assertEquals(DebugLevel.VERBOSE, configuration.level());
                assertTrue(configuration.areas().isEmpty());
                assertTrue(!configuration.consoleEnabled());
                assertEquals(25, configuration.file().maximumFileSizeMib());
                assertTrue(configuration.privacy().includePlayerNames());
        }

        @Test
        void rejectsUnknownDebugLevelAndArea() throws IOException {
                Files.writeString(temporaryDirectory.resolve("config.yml"), "schema-version: 1\nstorage:\n  provider: YAML\ndebug:\n  level: noisy\n");
                assertThrows(IllegalArgumentException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));

                Files.writeString(temporaryDirectory.resolve("config.yml"), "schema-version: 1\nstorage:\n  provider: YAML\ndebug:\n  areas: [unknown]\n");
                assertThrows(IllegalArgumentException.class, () -> new PluginConfigurationLoader().load(temporaryDirectory));
        }
}