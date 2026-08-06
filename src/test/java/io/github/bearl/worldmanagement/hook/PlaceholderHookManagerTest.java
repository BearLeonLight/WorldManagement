package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bearl.worldmanagement.config.HookConfiguration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PlaceholderHookManagerTest {

    @Test
    void connectsOnlyEnabledInstalledProvidersAndReportsStatusesIndependently() {
        final Set<String> installed = Set.of("PlaceholderAPI");
        final List<String> connected = new ArrayList<>();
        final PlaceholderHookManager manager = new PlaceholderHookManager(
            installed::contains,
            provider(installed, connected, "PlaceholderAPI"),
            provider(installed, connected, "MiniPlaceholders")
        );

        final PlaceholderHookManager.Status status = manager.connect(
            new HookConfiguration(true, true, true, true)
        );

        assertEquals(List.of("PlaceholderAPI"), connected);
        assertEquals("available", status.placeholderApi());
        assertEquals("not installed", status.miniPlaceholders());
    }

    @Test
    void skipsFactoriesForHooksDisabledByConfiguration() {
        final List<String> connected = new ArrayList<>();
        final PlaceholderHookManager manager = new PlaceholderHookManager(
            plugin -> true,
            provider(Set.of("PlaceholderAPI"), connected, "PlaceholderAPI"),
            provider(Set.of("MiniPlaceholders"), connected, "MiniPlaceholders")
        );

        final PlaceholderHookManager.Status status = manager.connect(
            new HookConfiguration(true, true, false, false)
        );

        assertEquals(List.of(), connected);
        assertEquals("disabled by hooks.yml", status.placeholderApi());
        assertEquals("disabled by hooks.yml", status.miniPlaceholders());
    }

    @Test
    void disconnectsOwnedProvidersOnDependencyDisableAndShutdown() {
        final Set<String> disconnected = new HashSet<>();
        final PlaceholderHookManager manager = new PlaceholderHookManager(
            plugin -> true,
            provider(disconnected, "PlaceholderAPI"),
            provider(disconnected, "MiniPlaceholders")
        );
        manager.connect(new HookConfiguration(true, true, true, true));

        manager.disconnect("PlaceholderAPI");
        manager.close();

        assertEquals(Set.of("PlaceholderAPI", "MiniPlaceholders"), disconnected);
    }

    @Test
    void reconnectsProvidersAfterDependencyReenableWithoutDuplicatingConnections() {
        final List<String> lifecycle = new ArrayList<>();
        final PlaceholderHookManager manager = new PlaceholderHookManager(
            plugin -> true,
            () -> PlaceholderHookManager.Connection.available(() -> lifecycle.add("disconnect-papi")),
            () -> {
                lifecycle.add("connect-mini");
                return PlaceholderHookManager.Connection.available(() -> lifecycle.add("disconnect-mini"));
            }
        );
        final HookConfiguration configuration = new HookConfiguration(true, true, false, true);
        manager.connect(configuration);

        manager.disconnect("MiniPlaceholders");
        assertEquals("available", manager.connect("MiniPlaceholders", configuration));
        assertEquals("available", manager.connect("MiniPlaceholders", configuration));
        manager.close();

        assertEquals(
            List.of("connect-mini", "disconnect-mini", "connect-mini", "disconnect-mini"),
            lifecycle
        );
    }

    @Test
    void continuesDisconnectingProvidersWhenOneCleanupFails() {
        final List<String> lifecycle = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final PlaceholderHookManager manager = new PlaceholderHookManager(
            plugin -> true,
            () -> PlaceholderHookManager.Connection.available(() -> {
                lifecycle.add("disconnect-papi");
                throw new IllegalStateException("PAPI unavailable");
            }),
            () -> PlaceholderHookManager.Connection.available(() -> lifecycle.add("disconnect-mini")),
            failures::add
        );
        manager.connect(new HookConfiguration(true, true, true, true));

        manager.close();

        assertEquals(List.of("disconnect-papi", "disconnect-mini"), lifecycle);
        assertEquals(1, failures.size());
    }

    private static PlaceholderHookManager.ConnectionFactory provider(
        final Set<String> installed,
        final List<String> connected,
        final String pluginName
    ) {
        return () -> {
            if (!installed.contains(pluginName)) {
                throw new AssertionError("Factory called for missing plugin: " + pluginName);
            }
            connected.add(pluginName);
            return PlaceholderHookManager.Connection.available(() -> { });
        };
    }

    private static PlaceholderHookManager.ConnectionFactory provider(
        final Set<String> disconnected,
        final String pluginName
    ) {
        return () -> PlaceholderHookManager.Connection.available(() -> disconnected.add(pluginName));
    }
}