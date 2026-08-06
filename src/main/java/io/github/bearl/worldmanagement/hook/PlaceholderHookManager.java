package io.github.bearl.worldmanagement.hook;

import io.github.bearl.worldmanagement.config.HookConfiguration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns optional placeholder provider registrations without exposing their API types. */
public final class PlaceholderHookManager implements AutoCloseable {

    public static final String PLACEHOLDER_API = "PlaceholderAPI";
    public static final String MINI_PLACEHOLDERS = "MiniPlaceholders";

    private final Predicate<String> pluginEnabled;
    private final ConnectionFactory placeholderApiFactory;
    private final ConnectionFactory miniPlaceholdersFactory;
    private final Consumer<Throwable> failureConsumer;
    private Connection placeholderApi;
    private Connection miniPlaceholders;

    public PlaceholderHookManager(
        final Predicate<String> pluginEnabled,
        final ConnectionFactory placeholderApiFactory,
        final ConnectionFactory miniPlaceholdersFactory
    ) {
        this(pluginEnabled, placeholderApiFactory, miniPlaceholdersFactory, failure -> { });
    }

    public PlaceholderHookManager(
        final Predicate<String> pluginEnabled,
        final ConnectionFactory placeholderApiFactory,
        final ConnectionFactory miniPlaceholdersFactory,
        final Consumer<Throwable> failureConsumer
    ) {
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
        this.placeholderApiFactory = Objects.requireNonNull(placeholderApiFactory, "placeholderApiFactory");
        this.miniPlaceholdersFactory = Objects.requireNonNull(miniPlaceholdersFactory, "miniPlaceholdersFactory");
        this.failureConsumer = Objects.requireNonNull(failureConsumer, "failureConsumer");
    }

    public Status connect(final HookConfiguration configuration) {
        final HookConfiguration requiredConfiguration = Objects.requireNonNull(configuration, "configuration");
        return new Status(
            connect(PLACEHOLDER_API, requiredConfiguration),
            connect(MINI_PLACEHOLDERS, requiredConfiguration)
        );
    }

    public String connect(final String pluginName, final HookConfiguration configuration) {
        final HookConfiguration requiredConfiguration = Objects.requireNonNull(configuration, "configuration");
        if (PLACEHOLDER_API.equals(pluginName)) {
            if (placeholderApi != null) {
                return placeholderApi.status();
            }
            final ConnectionResult result = connect(
                requiredConfiguration.placeholderApiEnabled(), pluginName, placeholderApiFactory
            );
            placeholderApi = result.connection();
            return result.status();
        }
        if (MINI_PLACEHOLDERS.equals(pluginName)) {
            if (miniPlaceholders != null) {
                return miniPlaceholders.status();
            }
            final ConnectionResult result = connect(
                requiredConfiguration.miniPlaceholdersEnabled(), pluginName, miniPlaceholdersFactory
            );
            miniPlaceholders = result.connection();
            return result.status();
        }
        throw new IllegalArgumentException("Unsupported placeholder plugin: " + pluginName);
    }

    public void disconnect(final String pluginName) {
        if (PLACEHOLDER_API.equals(pluginName)) {
            closeSafely(placeholderApi);
            placeholderApi = null;
        } else if (MINI_PLACEHOLDERS.equals(pluginName)) {
            closeSafely(miniPlaceholders);
            miniPlaceholders = null;
        }
    }

    @Override
    public void close() {
        disconnect(PLACEHOLDER_API);
        disconnect(MINI_PLACEHOLDERS);
    }

    private ConnectionResult connect(
        final boolean enabled,
        final String pluginName,
        final ConnectionFactory factory
    ) {
        if (!enabled) {
            return new ConnectionResult(null, "disabled by hooks.yml");
        }
        if (!pluginEnabled.test(pluginName)) {
            return new ConnectionResult(null, "not installed");
        }
        try {
            final Connection connection = Objects.requireNonNull(factory.connect(), "connection");
            return new ConnectionResult(connection, connection.status());
        } catch (final RuntimeException | LinkageError failure) {
            return new ConnectionResult(null, "API unavailable");
        }
    }

    private void closeSafely(final Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.disconnect().run();
        } catch (final RuntimeException | LinkageError failure) {
            failureConsumer.accept(failure);
        }
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection connect();
    }

    public record Connection(String status, Runnable disconnect) {
        public Connection {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(disconnect, "disconnect");
        }

        public static Connection available(final Runnable disconnect) {
            return new Connection("available", disconnect);
        }
    }

    public record Status(String placeholderApi, String miniPlaceholders) {
        public Status {
            Objects.requireNonNull(placeholderApi, "placeholderApi");
            Objects.requireNonNull(miniPlaceholders, "miniPlaceholders");
        }
    }

    private record ConnectionResult(Connection connection, String status) {
    }
}