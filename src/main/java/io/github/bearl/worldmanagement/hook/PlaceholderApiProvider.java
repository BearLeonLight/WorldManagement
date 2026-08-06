package io.github.bearl.worldmanagement.hook;

import java.util.Objects;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/** Owns the PlaceholderAPI registration for the wm expansion. */
public final class PlaceholderApiProvider {

    private PlaceholderApiProvider() {
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
        final String identifier = WorldPlaceholderResolver.NAMESPACE;
        if (requiredRegistry.current(identifier) != null) {
            return new PlaceholderHookManager.Connection("identifier collision", () -> { });
        }
        final PlaceholderApiExpansion expansion = new PlaceholderApiExpansion(resolver, author, version);
        if (!requiredRegistry.register(expansion)) {
            return new PlaceholderHookManager.Connection("registration failed", () -> { });
        }
        return PlaceholderHookManager.Connection.available(() -> {
            if (requiredRegistry.current(identifier) == expansion) {
                requiredRegistry.unregister(expansion);
            }
        });
    }

    interface Registry {
        PlaceholderExpansion current(String identifier);

        boolean register(PlaceholderExpansion expansion);

        void unregister(PlaceholderExpansion expansion);
    }

    private static final class ApiRegistry implements Registry {
        @Override
        public PlaceholderExpansion current(final String identifier) {
            return PlaceholderAPIPlugin.getInstance().getLocalExpansionManager()
                .findExpansionByIdentifier(identifier)
                .orElse(null);
        }

        @Override
        public boolean register(final PlaceholderExpansion expansion) {
            return expansion.register();
        }

        @Override
        public void unregister(final PlaceholderExpansion expansion) {
            expansion.unregister();
        }
    }
}