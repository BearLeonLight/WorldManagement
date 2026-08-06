package io.github.bearl.worldmanagement.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.bearl.worldmanagement.world.RegistrySnapshot;
import io.github.miniplaceholders.api.Expansion;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class MiniPlaceholdersProviderTest {

    @Test
    void refusesToReplaceAnExistingWmExpansion() {
        final Registry registry = new Registry();
        final Expansion existing = MiniPlaceholdersExpansion.create(resolver(), "other", "1");
        registry.current = existing;

        final PlaceholderHookManager.Connection connection = MiniPlaceholdersProvider.connect(
            resolver(), "BearL", "test", registry
        );

        assertEquals("identifier collision", connection.status());
        assertSame(existing, registry.current);
        assertEquals(0, registry.registerCalls);
    }

    @Test
    void unregistersOnlyWhileTheRegisteredInstanceIsStillOwned() {
        final Registry registry = new Registry();
        final PlaceholderHookManager.Connection connection = MiniPlaceholdersProvider.connect(
            resolver(), "BearL", "test", registry
        );
        assertEquals("available", connection.status());

        connection.disconnect().run();
        assertEquals(1, registry.unregisterCalls);

        final PlaceholderHookManager.Connection replacementConnection = MiniPlaceholdersProvider.connect(
            resolver(), "BearL", "test", registry
        );
        registry.current = MiniPlaceholdersExpansion.create(resolver(), "replacement", "2");
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

    private static final class Registry implements MiniPlaceholdersProvider.Registry {
        private Expansion current;
        private int registerCalls;
        private int unregisterCalls;

        @Override
        public Expansion current(final String name) {
            return current;
        }

        @Override
        public void register(final Expansion expansion) {
            registerCalls++;
            current = expansion;
        }

        @Override
        public void unregister(final Expansion expansion) {
            unregisterCalls++;
            current = null;
        }
    }
}