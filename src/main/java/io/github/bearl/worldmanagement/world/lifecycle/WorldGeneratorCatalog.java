package io.github.bearl.worldmanagement.world.lifecycle;

import io.github.bearl.worldmanagement.world.WorldGeneratorReference;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Paper-affine generator discovery with an immutable completion snapshot. */
public final class WorldGeneratorCatalog implements Listener {

    private final PluginManager pluginManager;
    private final Consumer<String> warningSink;
    private final Consumer<List<String>> generatorSnapshotSink;
    private final Consumer<List<String>> biomeProviderSnapshotSink;
    private final AtomicReference<List<String>> generatorPluginNames = new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> biomeProviderPluginNames = new AtomicReference<>(List.of());

    public WorldGeneratorCatalog(final PluginManager pluginManager, final Consumer<String> warningSink) {
        this(pluginManager, warningSink, ignored -> { }, ignored -> { });
    }

    public WorldGeneratorCatalog(
        final PluginManager pluginManager,
        final Consumer<String> warningSink,
        final Consumer<List<String>> snapshotSink
    ) {
        this(pluginManager, warningSink, snapshotSink, ignored -> { });
    }

    public WorldGeneratorCatalog(
        final PluginManager pluginManager,
        final Consumer<String> warningSink,
        final Consumer<List<String>> generatorSnapshotSink,
        final Consumer<List<String>> biomeProviderSnapshotSink
    ) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.generatorSnapshotSink = Objects.requireNonNull(generatorSnapshotSink, "generatorSnapshotSink");
        this.biomeProviderSnapshotSink = Objects.requireNonNull(
            biomeProviderSnapshotSink, "biomeProviderSnapshotSink"
        );
    }

    public void refresh(final String probeWorldName) {
        final String requiredWorldName = Objects.requireNonNull(probeWorldName, "probeWorldName");
        final List<Plugin> enabledPlugins = java.util.Arrays.stream(pluginManager.getPlugins())
            .filter(Plugin::isEnabled)
            .toList();
        publishGenerators(enabledPlugins.stream()
            .filter(plugin -> probeGenerator(plugin, requiredWorldName))
            .map(Plugin::getName)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList());
        publishBiomeProviders(enabledPlugins.stream()
            .filter(plugin -> probeBiomeProvider(plugin, requiredWorldName))
            .map(Plugin::getName)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList());
    }

    public List<String> pluginNames() {
        return java.util.stream.Stream.concat(
            generatorPluginNames.get().stream(), biomeProviderPluginNames.get().stream()
        ).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> generatorPluginNames() {
        return generatorPluginNames.get();
    }

    public List<String> biomeProviderPluginNames() {
        return biomeProviderPluginNames.get();
    }

    public Optional<ChunkGenerator> resolve(
        final String worldName,
        final WorldGeneratorReference reference
    ) {
        final Plugin plugin = pluginManager.getPlugin(Objects.requireNonNull(reference, "reference").pluginName());
        if (plugin == null || !plugin.isEnabled()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(plugin.getDefaultWorldGenerator(
                Objects.requireNonNull(worldName, "worldName"),
                reference.id().isEmpty() ? null : reference.id()
            ));
        } catch (final RuntimeException failure) {
            warningSink.accept("Generator plugin " + plugin.getName() + " failed to resolve: " + failure.getMessage());
            return Optional.empty();
        }
    }

    public Optional<BiomeProvider> resolveBiomeProvider(
        final String worldName,
        final WorldGeneratorReference reference
    ) {
        final Plugin plugin = pluginManager.getPlugin(Objects.requireNonNull(reference, "reference").pluginName());
        if (plugin == null || !plugin.isEnabled()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(plugin.getDefaultBiomeProvider(
                Objects.requireNonNull(worldName, "worldName"),
                reference.id().isEmpty() ? null : reference.id()
            ));
        } catch (final RuntimeException failure) {
            warningSink.accept("Biome provider plugin " + plugin.getName()
                + " failed to resolve: " + failure.getMessage());
            return Optional.empty();
        }
    }

    private boolean probeGenerator(final Plugin plugin, final String worldName) {
        try {
            return plugin.getDefaultWorldGenerator(worldName, null) != null;
        } catch (final RuntimeException failure) {
            warningSink.accept("Generator plugin " + plugin.getName() + " failed discovery: " + failure.getMessage());
            return false;
        }
    }

    private boolean probeBiomeProvider(final Plugin plugin, final String worldName) {
        try {
            return plugin.getDefaultBiomeProvider(worldName, null) != null;
        } catch (final RuntimeException failure) {
            warningSink.accept("Biome provider plugin " + plugin.getName()
                + " failed discovery: " + failure.getMessage());
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPluginEnable(final PluginEnableEvent event) {
        final Plugin plugin = event.getPlugin();
        if (probeGenerator(plugin, "worldmanagement-generator-probe")) {
            publishGenerators(withPlugin(generatorPluginNames.get(), plugin));
        }
        if (probeBiomeProvider(plugin, "worldmanagement-generator-probe")) {
            publishBiomeProviders(withPlugin(biomeProviderPluginNames.get(), plugin));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPluginDisable(final PluginDisableEvent event) {
        pluginDisabled(event.getPlugin());
        }

    void pluginDisabled(final Plugin plugin) {
        final String pluginName = Objects.requireNonNull(plugin, "plugin").getName();
        publishGenerators(withoutPlugin(generatorPluginNames.get(), pluginName));
        publishBiomeProviders(withoutPlugin(biomeProviderPluginNames.get(), pluginName));
    }

    private static List<String> withPlugin(final List<String> current, final Plugin plugin) {
        return java.util.stream.Stream.concat(current.stream(), java.util.stream.Stream.of(plugin.getName()))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private static List<String> withoutPlugin(final List<String> current, final String pluginName) {
        return current.stream().filter(name -> !name.equalsIgnoreCase(pluginName)).toList();
    }

    private void publishGenerators(final List<String> updated) {
        generatorPluginNames.set(List.copyOf(updated));
        generatorSnapshotSink.accept(updated);
    }

    private void publishBiomeProviders(final List<String> updated) {
        biomeProviderPluginNames.set(List.copyOf(updated));
        biomeProviderSnapshotSink.accept(updated);
    }
}