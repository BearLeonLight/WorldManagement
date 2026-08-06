package io.github.bearl.worldmanagement.hook;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/** Reconnects optional placeholder providers across dependency lifecycle changes. */
public final class PlaceholderDependencyListener implements Listener {

    private static final Set<String> SUPPORTED = Set.of(
        PlaceholderHookManager.PLACEHOLDER_API,
        PlaceholderHookManager.MINI_PLACEHOLDERS
    );

    private final Consumer<String> onEnabled;
    private final Consumer<String> onDisabled;

    public PlaceholderDependencyListener(
        final Consumer<String> onEnabled,
        final Consumer<String> onDisabled
    ) {
        this.onEnabled = Objects.requireNonNull(onEnabled, "onEnabled");
        this.onDisabled = Objects.requireNonNull(onDisabled, "onDisabled");
    }

    @EventHandler
    public void onEnabled(final PluginEnableEvent event) {
        enabled(event.getPlugin().getName());
    }

    void enabled(final String pluginName) {
        if (SUPPORTED.contains(pluginName)) {
            onEnabled.accept(pluginName);
        }
    }

    @EventHandler
    public void onDisabled(final PluginDisableEvent event) {
        disabled(event.getPlugin().getName());
    }

    void disabled(final String pluginName) {
        if (SUPPORTED.contains(pluginName)) {
            onDisabled.accept(pluginName);
        }
    }
}