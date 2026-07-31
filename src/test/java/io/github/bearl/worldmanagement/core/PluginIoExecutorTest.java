package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class PluginIoExecutorTest {

    @Test
    void executesTasksAndStopsAcceptingNewTasksAfterShutdown() throws ExecutionException, InterruptedException {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest");

        assertEquals("stored", executor.submit(() -> "stored").get());

        final int result = executor.shutdown(Duration.ofSeconds(1));

        assertEquals(PluginIoExecutor.SHUTDOWN_DRAINED, result);
        assertTrue(executor.submit(() -> "rejected").isCompletedExceptionally());
    }

    @Test
    void returnsFailedFutureWhenBoundedQueueIsFull() throws InterruptedException {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest", 1);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                started.countDown();
                release.await();
                return "running";
            });
            started.await();
            executor.submit(() -> "queued");
            final AtomicReference<java.util.concurrent.CompletableFuture<String>> rejected = new AtomicReference<>();

            assertDoesNotThrow(() -> rejected.set(executor.submit(() -> "rejected")));
            assertTrue(rejected.get().isCompletedExceptionally());
        } finally {
            release.countDown();
            executor.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void forceShutdownCompletesQueuedTaskFuturesExceptionally() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest", 2);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                started.countDown();
                release.await();
                return "running";
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            final var firstQueued = executor.submit(() -> "first");
            final var secondQueued = executor.submit(() -> "second");

            executor.forceShutdown();

            assertTrue(firstQueued.isCompletedExceptionally());
            assertTrue(secondQueued.isCompletedExceptionally());
        } finally {
            release.countDown();
            executor.forceShutdown();
        }
    }

    @Test
    void reservesTerminalTaskAfterAllAcceptedWorkWhenQueueIsFull() throws Exception {
        final PluginIoExecutor executor = new PluginIoExecutor("WorldManagementTest", 1);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        try {
            executor.execute(() -> {
                started.countDown();
                release.await();
                order.add("running");
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            executor.execute(() -> order.add("queued"));
            assertTrue(executor.execute(() -> order.add("rejected")).isCompletedExceptionally());

            final var shutdown = executor.shutdownAfterPending(
                Duration.ofSeconds(1), () -> order.add("terminal")
            );
            release.countDown();
            shutdown.get(1, TimeUnit.SECONDS);

            assertEquals(java.util.List.of("running", "queued", "terminal"), order);
        } finally {
            release.countDown();
            executor.forceShutdown();
        }
    }
}