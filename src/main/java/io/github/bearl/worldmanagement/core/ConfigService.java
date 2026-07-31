package io.github.bearl.worldmanagement.core;

import io.github.bearl.worldmanagement.config.PluginConfiguration;
import java.util.Objects;

/** Exposes the immutable validated plugin configuration to application services. */
public final class ConfigService {

    private final PluginConfiguration configuration;

    public ConfigService(final PluginConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public PluginConfiguration configuration() {
        return configuration;
    }
}