package io.github.bearl.worldmanagement.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Coordinates a bounded shutdown without waiting on a Paper game thread. */
public final class PluginShutdownCoordinator {

    private final WorldThreadDispatcher threadDispatcher;
    private final PluginIoExecutor ioExecutor;
    private final Logger logger;
    private volatile DiagnosticFileWriter diagnosticWriter;

    public PluginShutdownCoordinator(
        final WorldThreadDispatcher threadDispatcher,
        final PluginIoExecutor ioExecutor,
        final Logger logger
    ) {
        this.threadDispatcher = Objects.requireNonNull(threadDispatcher, "threadDispatcher");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<Void> shutdown(final Duration timeout) {
        return shutdown(timeout, CompletableFuture.completedFuture(null), () -> { });
    }

    public CompletableFuture<Void> shutdown(
        final Duration timeout,
        final CompletableFuture<Void> pendingOperations,
        final Runnable enqueueCloseTasks
    ) {
        Objects.requireNonNull(enqueueCloseTasks, "enqueueCloseTasks");
        return shutdown(timeout, pendingOperations, (PluginIoExecutor.IoTask) enqueueCloseTasks::run);
    }

    public CompletableFuture<Void> shutdown(
        final Duration timeout,
        final CompletableFuture<Void> pendingOperations,
        final PluginIoExecutor.IoTask... closeTasks
    ) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pendingOperations, "pendingOperations");
        Objects.requireNonNull(closeTasks, "closeTasks");
        final PluginIoExecutor.IoTask[] terminalCloseTasks = closeTasks.clone();
        for (final PluginIoExecutor.IoTask closeTask : terminalCloseTasks) {
            Objects.requireNonNull(closeTask, "closeTask");
        }
        threadDispatcher.cancelOwnedTasks();
        final CompletableFuture<Void> lifecycleDrain = pendingOperations
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .handle((unused, failure) -> {
                if (failure instanceof java.util.concurrent.TimeoutException) {
                    logger.warning("Lifecycle operations did not drain before shutdown deadline.");
                } else if (failure != null) {
                    logger.fine("A pending operation ended during shutdown: " + failure.getMessage());
                }
                return null;
            });
        return lifecycleDrain.thenCompose(unused -> {
                final CompletableFuture<Void> ioShutdown = ioExecutor.shutdownAfterPending(
                    timeout, appendDiagnosticClose(timeout, terminalCloseTasks)
                );
            ioShutdown.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((closed, failure) -> {
                if (failure != null) {
                    ioExecutor.forceShutdown();
                    logger.warning("I/O shutdown failed: " + failure.getMessage());
                }
            });
            return ioShutdown;
        });
    }

    private PluginIoExecutor.IoTask[] appendDiagnosticClose(
        final Duration timeout,
        final PluginIoExecutor.IoTask[] closeTasks
    ) {
        final DiagnosticFileWriter writer = diagnosticWriter;
        if (writer == null) {
            return closeTasks;
        }
        final PluginIoExecutor.IoTask[] terminalTasks = java.util.Arrays.copyOf(closeTasks, closeTasks.length + 1);
        terminalTasks[closeTasks.length] = () -> {
            if (!writer.close(timeout)) {
                logger.warning("Debug writer did not drain before shutdown deadline.");
            }
        };
        return terminalTasks;
    }

    public void attachDiagnosticWriter(final DiagnosticFileWriter writer) {
        this.diagnosticWriter = Objects.requireNonNull(writer, "writer");
    }
}
