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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Paper-affine generator discovery with an immutable completion snapshot. */
public final class WorldGeneratorCatalog implements Listener {

    private final PluginManager pluginManager;
    private final Consumer<String> warningSink;
    private final Consumer<List<String>> snapshotSink;
    private final AtomicReference<List<String>> pluginNames = new AtomicReference<>(List.of());

    public WorldGeneratorCatalog(final PluginManager pluginManager, final Consumer<String> warningSink) {
        this(pluginManager, warningSink, ignored -> { });
    }

    public WorldGeneratorCatalog(
        final PluginManager pluginManager,
        final Consumer<String> warningSink,
        final Consumer<List<String>> snapshotSink
    ) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.snapshotSink = Objects.requireNonNull(snapshotSink, "snapshotSink");
    }

    public void refresh(final String probeWorldName) {
        final String requiredWorldName = Objects.requireNonNull(probeWorldName, "probeWorldName");
        final List<String> updated = java.util.Arrays.stream(pluginManager.getPlugins())
            .filter(Plugin::isEnabled)
            .filter(plugin -> probe(plugin, requiredWorldName))
            .map(Plugin::getName)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        pluginNames.set(updated);
        snapshotSink.accept(updated);
    }

    public List<String> pluginNames() {
        return pluginNames.get();
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

    private boolean probe(final Plugin plugin, final String worldName) {
        try {
            return plugin.getDefaultWorldGenerator(worldName, null) != null;
        } catch (final RuntimeException failure) {
            warningSink.accept("Generator plugin " + plugin.getName() + " failed discovery: " + failure.getMessage());
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPluginEnable(final PluginEnableEvent event) {
        final Plugin plugin = event.getPlugin();
        if (!probe(plugin, "worldmanagement-generator-probe")) {
            return;
        }
        publish(java.util.stream.Stream.concat(pluginNames.get().stream(), java.util.stream.Stream.of(plugin.getName()))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onPluginDisable(final PluginDisableEvent event) {
        pluginDisabled(event.getPlugin());
        }

        void pluginDisabled(final Plugin plugin) {
        publish(pluginNames.get().stream()
            .filter(name -> !name.equalsIgnoreCase(Objects.requireNonNull(plugin, "plugin").getName()))
            .toList());
    }

    private void publish(final List<String> updated) {
        pluginNames.set(List.copyOf(updated));
        snapshotSink.accept(updated);
    }
}