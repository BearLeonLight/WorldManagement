package io.github.bearl.worldmanagement.world;

import io.github.bearl.worldmanagement.core.PluginIoExecutor;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Serializes metadata mutations with one-way storage migration. */
public final class MetadataMutationGate {

    private final PluginIoExecutor ioExecutor;
    private State state = State.OPEN;

    public MetadataMutationGate(final PluginIoExecutor ioExecutor) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    public synchronized <T> CompletableFuture<T> submitMutation(final Callable<T> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (state != State.OPEN) {
            return CompletableFuture.failedFuture(new IllegalStateException("Metadata mutations are frozen for storage migration."));
        }
        return ioExecutor.submit(mutation);
    }

    public synchronized <T> CompletableFuture<T> submitMigration(final Callable<T> migration) {
        Objects.requireNonNull(migration, "migration");
        if (state != State.OPEN) {
            return CompletableFuture.failedFuture(new IllegalStateException("Metadata mutations are already frozen."));
        }
        state = State.MIGRATING;
        return ioExecutor.submit(migration).whenComplete((result, failure) -> completeMigration(failure));
    }

    private synchronized void completeMigration(final Throwable failure) {
        state = failure == null ? State.FROZEN : State.OPEN;
    }

    private enum State {
        OPEN,
        MIGRATING,
        FROZEN
    }
}