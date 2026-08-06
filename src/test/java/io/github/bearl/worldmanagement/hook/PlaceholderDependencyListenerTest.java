package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PlaceholderDependencyListenerTest {

    @Test
    void forwardsOnlySupportedDependencyLifecycleEvents() {
        final List<String> enabled = new ArrayList<>();
        final List<String> disabled = new ArrayList<>();
        final PlaceholderDependencyListener listener = new PlaceholderDependencyListener(
            pluginName -> enabled.add(pluginName),
            disabled::add
        );

        listener.enabled("PlaceholderAPI");
        listener.enabled("MiniPlaceholders");
        listener.enabled("OtherPlugin");
        listener.disabled("PlaceholderAPI");
        listener.disabled("OtherPlugin");

        assertEquals(List.of("PlaceholderAPI", "MiniPlaceholders"), enabled);
        assertEquals(List.of("PlaceholderAPI"), disabled);
    }
}