package io.github.bearl.worldmanagement.audit;

/** Blocking audit persistence boundary invoked only by PluginIoExecutor. */
@FunctionalInterface
public interface AuditStore {

    void append(AuditEvent event);
}