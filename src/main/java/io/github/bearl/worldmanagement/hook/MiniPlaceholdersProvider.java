package io.github.bearl.worldmanagement.hook;

import io.github.miniplaceholders.api.Expansion;
import io.github.miniplaceholders.api.MiniPlaceholders;
import java.util.Objects;

/** Owns the MiniPlaceholders registration for the wm expansion. */
public final class MiniPlaceholdersProvider {

    private MiniPlaceholdersProvider() {
    }

    public static PlaceholderHookManager.Connection connect(
        final WorldPlaceholderResolver resolver,
        final String author,
        final String version
    ) {
        return connect(resolver, author, version, new ApiRegistry());
    }

    static PlaceholderHookManager.Connection connect(
        final WorldPlaceholderResolver resolver,
        final String author,
        final String version,
        final Registry registry
    ) {
        final Registry requiredRegistry = Objects.requireNonNull(registry, "registry");
        final String name = WorldPlaceholderResolver.NAMESPACE;
        if (requiredRegistry.current(name) != null) {
            return new PlaceholderHookManager.Connection("identifier collision", () -> { });
        }
        final Expansion expansion = MiniPlaceholdersExpansion.create(resolver, author, version);
        requiredRegistry.register(expansion);
        return PlaceholderHookManager.Connection.available(() -> {
            if (requiredRegistry.current(name) == expansion) {
                requiredRegistry.unregister(expansion);
            }
        });
    }

    interface Registry {
        Expansion current(String name);

        void register(Expansion expansion);

        void unregister(Expansion expansion);
    }

    private static final class ApiRegistry implements Registry {
        @Override
        public Expansion current(final String name) {
            return MiniPlaceholders.expansionByName(name);
        }

        @Override
        public void register(final Expansion expansion) {
            expansion.register();
        }

        @Override
        public void unregister(final Expansion expansion) {
            expansion.unregister();
        }
    }
}