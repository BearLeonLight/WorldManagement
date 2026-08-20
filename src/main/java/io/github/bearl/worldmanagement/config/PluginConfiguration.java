package io.github.bearl.worldmanagement.config;

import io.github.bearl.worldmanagement.audit.AuditPolicy;
import io.github.bearl.worldmanagement.module.ModuleConfiguration;
import io.github.bearl.worldmanagement.storage.StorageConfiguration;
import io.github.bearl.worldmanagement.storage.StorageProvider;
import java.util.Map;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable, validated settings consumed by application and lifecycle services. */
public record PluginConfiguration(
    StorageConfiguration storage,
    Optional<String> fallbackWorld,
    Duration deletionDelay,
    boolean defaultRankSystemEnabled,
    int maximumCustomRanks,
    boolean warpEnabled,
    AuditPolicy auditPolicy,
    AuditFileConfiguration auditFile,
    String locale,
    DebugConfiguration debug,
    HookConfiguration hooks,
    Map<StorageProvider, StorageConfiguration> migrationTargets,
    ModuleConfiguration modules
) {

    public PluginConfiguration {
        Objects.requireNonNull(storage, "storage");
        fallbackWorld = Objects.requireNonNull(fallbackWorld, "fallbackWorld");
        Objects.requireNonNull(deletionDelay, "deletionDelay");
        if (deletionDelay.isNegative()) {
            throw new IllegalArgumentException("deletionDelay must not be negative.");
        }
        if (maximumCustomRanks < 0) {
            throw new IllegalArgumentException("maximumCustomRanks must not be negative.");
        }
        Objects.requireNonNull(auditPolicy, "auditPolicy");
        Objects.requireNonNull(auditFile, "auditFile");
        if (locale == null || locale.isBlank()) {
            throw new IllegalArgumentException("locale must not be blank.");
        }
        Objects.requireNonNull(debug, "debug");
        Objects.requireNonNull(hooks, "hooks");
        migrationTargets = Map.copyOf(Objects.requireNonNull(migrationTargets, "migrationTargets"));
        Objects.requireNonNull(modules, "modules");
    }
}
