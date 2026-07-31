package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bearl.worldmanagement.config.DebugArea;
import io.github.bearl.worldmanagement.config.DebugFileConfiguration;
import io.github.bearl.worldmanagement.config.DebugLevel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PluginShutdownCoordinatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void closesResourcesOnIoWorkerAfterPendingOperationsFinish() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownTest");
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            dispatcher, ioExecutor, Logger.getLogger("ShutdownTest")
        );
        final CompletableFuture<Void> pendingOperations = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<String> closeThread = new AtomicReference<>();

        final CompletableFuture<Void> shutdown = coordinator.shutdown(
            Duration.ofSeconds(1),
            pendingOperations,
            () -> {
                closeThread.set(Thread.currentThread().getName());
                closed.set(true);
            }
        );

        assertTrue(dispatcher.cancelled);
        assertFalse(closed.get());
        assertFalse(shutdown.isDone());

        pendingOperations.complete(null);
        shutdown.get(1, TimeUnit.SECONDS);

        assertTrue(closed.get());
        assertTrue(closeThread.get().contains("ShutdownTest I/O"));
        assertTrue(ioExecutor.submit(() -> "rejected").isCompletedExceptionally());
    }

    @Test
    void closesResourcesAfterLifecycleDrainTimeout() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownTimeoutTest");
        final RecordingDispatcher dispatcher = new RecordingDispatcher();
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            dispatcher, ioExecutor, Logger.getLogger("ShutdownTimeoutTest")
        );
        final AtomicBoolean closed = new AtomicBoolean();

        final CompletableFuture<Void> shutdown = coordinator.shutdown(
            Duration.ofMillis(50),
            new CompletableFuture<>(),
            () -> closed.set(true)
        );

        shutdown.get(1, TimeUnit.SECONDS);

        assertTrue(dispatcher.cancelled);
        assertTrue(closed.get());
        assertTrue(ioExecutor.submit(() -> "rejected").isCompletedExceptionally());
    }

    @Test
    void closesDiagnosticWriterWhenResourceClosureFails() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownFailureTest");
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            new RecordingDispatcher(), ioExecutor, Logger.getLogger("ShutdownFailureTest")
        );
        final DiagnosticFileWriter writer = new DiagnosticFileWriter(
            temporaryDirectory, new DebugFileConfiguration(1, 1), failure -> { }, 8
        );
        coordinator.attachDiagnosticWriter(writer);
        final IllegalStateException closeFailure = new IllegalStateException("repository close failure");

        try {
            final CompletableFuture<Void> shutdown = coordinator.shutdown(
                Duration.ofSeconds(1),
                CompletableFuture.completedFuture(null),
                () -> { throw closeFailure; }
            );

            final ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> shutdown.get(1, TimeUnit.SECONDS)
            );
            assertEquals(closeFailure, failure.getCause());
            assertFalse(writer.offer(new DiagnosticEvent(
                Instant.now(), DebugLevel.BASIC, DebugArea.IO, "after_close", Map.of(), Optional.empty()
            )));
        } finally {
            writer.close(Duration.ofSeconds(1));
        }
    }

    @Test
    void attemptsEveryCloseTaskAndAggregatesFailures() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownAggregateTest");
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            new RecordingDispatcher(), ioExecutor, Logger.getLogger("ShutdownAggregateTest")
        );
        final AtomicBoolean auditClosed = new AtomicBoolean();
        final IllegalStateException repositoryFailure = new IllegalStateException("repository close failure");
        final IllegalStateException pendingStorageFailure = new IllegalStateException("pending storage close failure");

        final CompletableFuture<Void> shutdown = coordinator.shutdown(
            Duration.ofSeconds(1),
            CompletableFuture.completedFuture(null),
            () -> { throw repositoryFailure; },
            () -> auditClosed.set(true),
            () -> { throw pendingStorageFailure; }
        );

        final ExecutionException failure = assertThrows(
            ExecutionException.class,
            () -> shutdown.get(1, TimeUnit.SECONDS)
        );
        assertEquals(repositoryFailure, failure.getCause());
        assertTrue(auditClosed.get());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals(pendingStorageFailure, failure.getCause().getSuppressed()[0]);
    }

    @Test
    void reportsIoDrainTimeoutAndStillRunsTerminalClose() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownIoTimeoutTest");
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            new RecordingDispatcher(), ioExecutor, Logger.getLogger("ShutdownIoTimeoutTest")
        );
        final CountDownLatch ioStarted = new CountDownLatch(1);
        final CountDownLatch ioInterrupted = new CountDownLatch(1);
        final CountDownLatch terminalClosed = new CountDownLatch(1);
        final CountDownLatch secondTerminalClosed = new CountDownLatch(1);
        final CountDownLatch thirdTerminalClosed = new CountDownLatch(1);
        final CountDownLatch releaseIo = new CountDownLatch(1);

        try {
            ioExecutor.execute(() -> {
                ioStarted.countDown();
                try {
                    releaseIo.await();
                } catch (final InterruptedException exception) {
                    ioInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(ioStarted.await(1, TimeUnit.SECONDS));

            final CompletableFuture<Void> shutdown = coordinator.shutdown(
                Duration.ofMillis(50),
                CompletableFuture.completedFuture(null),
                terminalClosed::countDown,
                secondTerminalClosed::countDown,
                thirdTerminalClosed::countDown
            );

            final ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> shutdown.get(1, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
            assertTrue(ioInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(terminalClosed.await(1, TimeUnit.SECONDS));
            assertTrue(secondTerminalClosed.await(1, TimeUnit.SECONDS));
            assertTrue(thirdTerminalClosed.await(1, TimeUnit.SECONDS));
        } finally {
            releaseIo.countDown();
            ioExecutor.forceShutdown();
        }
    }

    @Test
    void runsTerminalCloseWhenAcceptedIoWorkIgnoresInterrupt() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownUninterruptibleIoTest");
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            new RecordingDispatcher(), ioExecutor, Logger.getLogger("ShutdownUninterruptibleIoTest")
        );
        final CountDownLatch ioStarted = new CountDownLatch(1);
        final CountDownLatch ioInterrupted = new CountDownLatch(1);
        final CountDownLatch releaseIo = new CountDownLatch(1);
        final CountDownLatch terminalClosed = new CountDownLatch(1);

        try {
            ioExecutor.execute(() -> {
                ioStarted.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        releaseIo.await();
                        released = true;
                    } catch (final InterruptedException exception) {
                        ioInterrupted.countDown();
                    }
                }
            });
            assertTrue(ioStarted.await(1, TimeUnit.SECONDS));

            final CompletableFuture<Void> shutdown = coordinator.shutdown(
                Duration.ofMillis(50), CompletableFuture.completedFuture(null), terminalClosed::countDown
            );

            final ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> shutdown.get(1, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
            assertTrue(ioInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(terminalClosed.await(1, TimeUnit.SECONDS));
        } finally {
            releaseIo.countDown();
            ioExecutor.forceShutdown();
        }
    }

    @Test
    void interruptsBlockedTerminalCloseAndAttemptsRemainingResources() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownBlockedCloseTest");
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            new RecordingDispatcher(), ioExecutor, Logger.getLogger("ShutdownBlockedCloseTest")
        );
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeInterrupted = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final CountDownLatch remainingResourceClosed = new CountDownLatch(1);

        try {
            final CompletableFuture<Void> shutdown = coordinator.shutdown(
                Duration.ofMillis(50),
                CompletableFuture.completedFuture(null),
                () -> {
                    closeStarted.countDown();
                    try {
                        releaseClose.await();
                    } catch (final InterruptedException exception) {
                        closeInterrupted.countDown();
                        throw exception;
                    }
                },
                remainingResourceClosed::countDown
            );
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));

            final ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> shutdown.get(1, TimeUnit.SECONDS)
            );

            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
            assertTrue(closeInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(remainingResourceClosed.await(1, TimeUnit.SECONDS));
        } finally {
            releaseClose.countDown();
            ioExecutor.forceShutdown();
        }
    }

    @Test
    void attemptsRemainingResourcesWhenTerminalCloseIgnoresInterrupt() throws Exception {
        final PluginIoExecutor ioExecutor = new PluginIoExecutor("ShutdownUninterruptibleCloseTest");
        final PluginShutdownCoordinator coordinator = new PluginShutdownCoordinator(
            new RecordingDispatcher(), ioExecutor, Logger.getLogger("ShutdownUninterruptibleCloseTest")
        );
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeInterrupted = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final CountDownLatch remainingResourceClosed = new CountDownLatch(1);

        try {
            final CompletableFuture<Void> shutdown = coordinator.shutdown(
                Duration.ofMillis(50),
                CompletableFuture.completedFuture(null),
                () -> {
                    closeStarted.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            releaseClose.await();
                            released = true;
                        } catch (final InterruptedException exception) {
                            closeInterrupted.countDown();
                        }
                    }
                },
                remainingResourceClosed::countDown
            );
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS));

            final ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> shutdown.get(1, TimeUnit.SECONDS)
            );

            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
            assertTrue(closeInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(remainingResourceClosed.await(1, TimeUnit.SECONDS));
        } finally {
            releaseClose.countDown();
            ioExecutor.forceShutdown();
        }
    }

    private static final class RecordingDispatcher implements WorldThreadDispatcher {
        private boolean cancelled;

        @Override
        public void executeGlobal(final Runnable task) {
            task.run();
        }

        @Override
        public void executeAt(final Location location, final Runnable task) {
            task.run();
        }

        @Override
        public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
            task.run();
            return true;
        }

        @Override
        public void cancelOwnedTasks() {
            cancelled = true;
        }
    }
}