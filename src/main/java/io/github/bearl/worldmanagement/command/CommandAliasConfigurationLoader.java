package io.github.bearl.worldmanagement.command;

import dev.dejvokep.boostedyaml.YamlDocument;
import io.github.bearl.worldmanagement.storage.StorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads command aliases before Paper's command lifecycle event is registered. */
public final class CommandAliasConfigurationLoader {

    private static final String DEFAULT_CONFIGURATION = String.join("\n",
        "commands:",
        "  root-aliases:",
        "    - worldmanager",
        "    - worldmanagement",
        "  modules:",
        "    warp:",
        "      aliases: []",
        "    ownership:",
        "      aliases: []",
        "    storage:",
        "      aliases: []",
        ""
    );

    public CommandAliasConfiguration load(final Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        final Path configurationFile = dataDirectory.toAbsolutePath().normalize().resolve("commands.yml");
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(configurationFile) || Files.readString(configurationFile, StandardCharsets.UTF_8).isBlank()) {
                Files.writeString(configurationFile, DEFAULT_CONFIGURATION, StandardCharsets.UTF_8);
            }
            final YamlDocument document = YamlDocument.create(new ByteArrayInputStream(Files.readAllBytes(configurationFile)));
            final Map<io.github.bearl.worldmanagement.module.ModuleId, List<String>> moduleAliases = new EnumMap<>(io.github.bearl.worldmanagement.module.ModuleId.class);
            moduleAliases.put(io.github.bearl.worldmanagement.module.ModuleId.WARP, document.getStringList("commands.modules.warp.aliases"));
            moduleAliases.put(io.github.bearl.worldmanagement.module.ModuleId.OWNERSHIP, document.getStringList("commands.modules.ownership.aliases"));
            moduleAliases.put(io.github.bearl.worldmanagement.module.ModuleId.STORAGE, document.getStringList("commands.modules.storage.aliases"));
            return new CommandAliasConfiguration(document.getStringList("commands.root-aliases"), moduleAliases);
        } catch (final IOException exception) {
            throw new StorageException("Could not load command alias configuration.", exception);
        }
    }
}