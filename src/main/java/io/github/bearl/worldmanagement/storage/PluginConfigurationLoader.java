package io.github.bearl.worldmanagement.storage;

import dev.dejvokep.boostedyaml.YamlDocument;
import io.github.bearl.worldmanagement.audit.AuditPolicy;
import io.github.bearl.worldmanagement.config.HookConfiguration;
import io.github.bearl.worldmanagement.config.PluginConfiguration;
import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugConfiguration;
import io.github.bearl.worldmanagement.config.DebugFileConfiguration;
import io.github.bearl.worldmanagement.config.DebugLevel;
import io.github.bearl.worldmanagement.config.DebugPrivacyConfiguration;
import io.github.bearl.worldmanagement.core.WorldNameValidator;
import io.github.bearl.worldmanagement.module.ModuleConfiguration;
import io.github.bearl.worldmanagement.module.ModuleId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Loads the one active storage provider configuration. Call only from the I/O executor. */
public final class PluginConfigurationLoader {

    private static final int CURRENT_SCHEMA_VERSION = 1;

        private static final String DEFAULT_CONFIGURATION = String.join("\n",
        "schema-version: 1",
                "storage:",
                "  provider: YAML",
                "  jdbc-url: \"\"",
                "  username: \"\"",
                "  password: \"\"",
                "lifecycle:",
                "  fallback-world: \"\"",
                "  deletion-delay-milliseconds: 1000",
                "ownership:",
                "  default-rank-system-enabled: true",
                "  maximum-custom-ranks: 5",
                "warp:",
                "  enabled: true",
                "audit:",
                "  policy: BEST_EFFORT",
                "debug:",
                "  level: OFF",
                "  sinks:",
                "    console: true",
                "    file: true",
                "  areas: [STARTUP, METADATA, AUDIT, IO, COMMAND, LIFECYCLE, WARP, OWNERSHIP, PROTECTION, STORAGE]",
                "  file:",
                "    max-file-size-mib: 10",
                "    retained-files: 5",
                "  privacy:",
                "    include-player-names: false",
                "    include-masked-ip-addresses: false",
                "    include-masked-jdbc-endpoints: false",
                "locale: zh_TW",
                "storage-migration:",
                "  targets: {}",
                ""
        );

    private static final String DEFAULT_MODULE_CONFIGURATION = String.join("\n",
        "lifecycle:",
        "  enabled: true",
        "warp:",
        "  enabled: true",
        "ownership:",
        "  enabled: true",
        "protection:",
        "  enabled: true",
        "storage:",
        "  enabled: true",
        ""
    );

        public PluginConfiguration load(final Path dataDirectory) {
        final Path configurationFile = dataDirectory.toAbsolutePath().normalize().resolve("config.yml");
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(configurationFile) || Files.readString(configurationFile, StandardCharsets.UTF_8).isBlank()) {
                Files.writeString(configurationFile, DEFAULT_CONFIGURATION, StandardCharsets.UTF_8);
            }
            final YamlDocument document = YamlDocument.create(
                new ByteArrayInputStream(Files.readAllBytes(configurationFile))
            );
            final int schemaVersion = document.getInt("schema-version", -1);
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new UnsupportedStorageSchemaException(
                    "Unsupported configuration schema version: " + schemaVersion
                );
            }
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Missing or invalid configuration schema version.");
            }
            final String provider = document.getString("storage.provider", null);
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("Missing storage.provider configuration.");
            }
            final String fallbackWorld = document.getString("lifecycle.fallback-world", "").trim();
            if (!fallbackWorld.isEmpty()) {
                new WorldNameValidator().requireValidName(fallbackWorld);
            }
            final long delayMilliseconds = document.getLong("lifecycle.deletion-delay-milliseconds", 1_000L);
            final int maximumCustomRanks = document.getInt("ownership.maximum-custom-ranks", 5);
            return new PluginConfiguration(
                new StorageConfiguration(
                    StorageProvider.parse(provider),
                    document.getString("storage.jdbc-url", ""),
                    document.getString("storage.username", ""),
                    document.getString("storage.password", "")
                ),
                fallbackWorld.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(fallbackWorld),
                java.time.Duration.ofMillis(delayMilliseconds),
                document.getBoolean("ownership.default-rank-system-enabled", true),
                maximumCustomRanks,
                document.getBoolean("warp.enabled", true),
                AuditPolicy.parse(document.getString("audit.policy", "BEST_EFFORT")),
                document.getString("locale", "zh_TW"),
                loadDebugConfiguration(document),
                new HookConfiguration(loadHooks(dataDirectory)),
                loadMigrationTargets(document),
                loadModules(dataDirectory)
            );
        } catch (final IOException exception) {
            throw new StorageException("Could not load plugin configuration.", exception);
        }
    }

    private DebugConfiguration loadDebugConfiguration(final YamlDocument document) {
        final EnumSet<DebugArea> areas = EnumSet.noneOf(DebugArea.class);
        for (final String value : document.getStringList("debug.areas")) {
            areas.add(DebugArea.parse(value));
        }
        if (!document.contains("debug.areas")) {
            areas.addAll(EnumSet.allOf(DebugArea.class));
        }
        return new DebugConfiguration(
            DebugLevel.parse(document.getString("debug.level", "OFF")),
            document.getBoolean("debug.sinks.console", true),
            document.getBoolean("debug.sinks.file", true),
            areas,
            new DebugFileConfiguration(
                document.getInt("debug.file.max-file-size-mib", DebugFileConfiguration.DEFAULT_MAXIMUM_FILE_SIZE_MIB),
                document.getInt("debug.file.retained-files", DebugFileConfiguration.DEFAULT_RETAINED_FILES)
            ),
            new DebugPrivacyConfiguration(
                document.getBoolean("debug.privacy.include-player-names", false),
                document.getBoolean("debug.privacy.include-masked-ip-addresses", false),
                document.getBoolean("debug.privacy.include-masked-jdbc-endpoints", false)
            )
        );
    }

    private boolean loadHooks(final Path dataDirectory) throws IOException {
        final Path hooksFile = dataDirectory.toAbsolutePath().normalize().resolve("hooks.yml");
        if (Files.notExists(hooksFile)) {
            Files.writeString(hooksFile, "luckperms:\n  enabled: true\n", StandardCharsets.UTF_8);
        }
        final YamlDocument hooks = YamlDocument.create(new ByteArrayInputStream(Files.readAllBytes(hooksFile)));
        return hooks.getBoolean("luckperms.enabled", true);
    }

    private Map<StorageProvider, StorageConfiguration> loadMigrationTargets(final YamlDocument document) {
        final Map<StorageProvider, StorageConfiguration> targets = new EnumMap<>(StorageProvider.class);
        for (final StorageProvider provider : StorageProvider.values()) {
            final String prefix = "storage-migration.targets." + provider.name().toLowerCase(java.util.Locale.ROOT);
            final String jdbcUrl = document.getString(prefix + ".jdbc-url", "");
            if (provider == StorageProvider.YAML || !jdbcUrl.isBlank()) {
                targets.put(provider, new StorageConfiguration(
                    provider,
                    jdbcUrl,
                    document.getString(prefix + ".username", ""),
                    document.getString(prefix + ".password", "")
                ));
            }
        }
        return targets;
    }

    private ModuleConfiguration loadModules(final Path dataDirectory) throws IOException {
        final Path modulesFile = dataDirectory.toAbsolutePath().normalize().resolve("modules.yml");
        if (Files.notExists(modulesFile)) {
            Files.writeString(modulesFile, DEFAULT_MODULE_CONFIGURATION, StandardCharsets.UTF_8);
        }
        final YamlDocument modules = YamlDocument.create(new ByteArrayInputStream(Files.readAllBytes(modulesFile)));
        final Map<ModuleId, Boolean> enabled = new EnumMap<>(ModuleId.class);
        for (final ModuleId module : ModuleId.values()) {
            enabled.put(module, modules.getBoolean(module.name().toLowerCase(java.util.Locale.ROOT) + ".enabled", true));
        }
        return new ModuleConfiguration(enabled);
    }
}