package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.bearl.worldmanagement.world.RegistrySnapshot;
import java.util.Optional;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.junit.jupiter.api.Test;

final class PlaceholderApiProviderTest {

    @Test
    void refusesToReplaceAnExistingWmExpansion() {
        final Registry registry = new Registry();
        final PlaceholderExpansion existing = new PlaceholderApiExpansion(resolver(), "other", "1");
        registry.current = existing;

        final PlaceholderHookManager.Connection connection = PlaceholderApiProvider.connect(
            resolver(), "BearL", "test", registry
        );

        assertEquals("identifier collision", connection.status());
        assertSame(existing, registry.current);
        assertEquals(0, registry.registerCalls);
    }

    @Test
    void unregistersOnlyWhileTheRegisteredInstanceIsStillOwned() {
        final Registry registry = new Registry();
        final PlaceholderHookManager.Connection connection = PlaceholderApiProvider.connect(
            resolver(), "BearL", "test", registry
        );
        assertEquals("available", connection.status());

        connection.disconnect().run();
        assertEquals(1, registry.unregisterCalls);

        final PlaceholderHookManager.Connection replacementConnection = PlaceholderApiProvider.connect(
            resolver(), "BearL", "test", registry
        );
        registry.current = new PlaceholderApiExpansion(resolver(), "replacement", "2");
        replacementConnection.disconnect().run();

        assertEquals(1, registry.unregisterCalls);
    }

    private static WorldPlaceholderResolver resolver() {
        return new WorldPlaceholderResolver(
            RegistrySnapshot::empty,
            world -> false,
            playerId -> Optional.empty()
        );
    }

    private static final class Registry implements PlaceholderApiProvider.Registry {
        private PlaceholderExpansion current;
        private int registerCalls;
        private int unregisterCalls;

        @Override
        public PlaceholderExpansion current(final String identifier) {
            return current;
        }

        @Override
        public boolean register(final PlaceholderExpansion expansion) {
            registerCalls++;
            current = expansion;
            return true;
        }

        @Override
        public void unregister(final PlaceholderExpansion expansion) {
            unregisterCalls++;
            current = null;
        }
    }
}