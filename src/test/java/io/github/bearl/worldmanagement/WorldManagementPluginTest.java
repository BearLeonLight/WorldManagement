package io.github.bearl.worldmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class WorldManagementPluginTest {

    @Test
    void preservesShutdownOperationFuture() {
        final CompletableFuture<Void> operation = new CompletableFuture<>();

        assertSame(operation, WorldManagementPlugin.beginShutdown(() -> operation));
    }

    @Test
    void capturesSynchronousShutdownFailure() {
        final IllegalStateException failure = new IllegalStateException("shutdown failed");

        final CompletionException captured = assertThrows(
            CompletionException.class,
            () -> WorldManagementPlugin.beginShutdown(() -> { throw failure; }).join()
        );

        assertSame(failure, captured.getCause());
    }

    @Test
    void rejectsNullShutdownFuture() {
        final CompletionException captured = assertThrows(
            CompletionException.class,
            () -> WorldManagementPlugin.beginShutdown(() -> null).join()
        );

        assertEquals("Shutdown operation future", captured.getCause().getMessage());
    }
}