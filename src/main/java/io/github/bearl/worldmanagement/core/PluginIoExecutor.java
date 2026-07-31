package io.github.bearl.worldmanagement.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes blocking plugin-owned I/O away from Paper game threads. */
public final class PluginIoExecutor {

    public static final int SHUTDOWN_DRAINED = 0;
    public static final int SHUTDOWN_TIMED_OUT = 1;
    public static final int SHUTDOWN_INTERRUPTED = 2;
    static final int DEFAULT_QUEUE_CAPACITY = 1_024;
    private static final int MAX_TERMINAL_TASKS = 16;

    private final TerminalThreadPoolExecutor executor;
    private final Semaphore taskSlots;
    private final Object admissionLock = new Object();
    private final AtomicBoolean acceptingTasks = new AtomicBoolean(true);
    private final String pluginName;
    private final Set<Thread> terminalWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PluginIoExecutor(final String pluginName) {
        this(pluginName, DEFAULT_QUEUE_CAPACITY);
    }

    PluginIoExecutor(final String pluginName, final int queueCapacity) {
        Objects.requireNonNull(pluginName, "pluginName");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive.");
        }
        this.pluginName = pluginName;
        this.taskSlots = new Semaphore(queueCapacity + 1);
        this.executor = new TerminalThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity + 2),
            new PluginThreadFactory(pluginName),
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.prestartCoreThread();
    }

    public <T> CompletableFuture<T> submit(final Callable<T> task) {
        Objects.requireNonNull(task, "task");
        synchronized (admissionLock) {
            if (!acceptingTasks.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("I/O executor is shutting down."));
            }
            if (!taskSlots.tryAcquire()) {
                return CompletableFuture.failedFuture(new IllegalStateException("I/O executor queue is full."));
            }
            final IoFutureTask<T> submittedTask = new IoFutureTask<>(task, taskSlots::release, true);
            try {
                executor.execute(submittedTask);
            } catch (final RejectedExecutionException exception) {
                submittedTask.reject(new IllegalStateException("I/O executor is not accepting tasks.", exception));
            }
            return submittedTask.completion();
        }
    }

    public CompletableFuture<Void> execute(final IoTask task) {
        Objects.requireNonNull(task, "task");
        return submit(() -> {
            task.run();
            return null;
        });
    }

    public int shutdown(final Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        beginShutdown();
        try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return SHUTDOWN_DRAINED;
            }
            cancelQueuedTasks(executor.shutdownNow());
            return SHUTDOWN_TIMED_OUT;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelQueuedTasks(executor.shutdownNow());
            return SHUTDOWN_INTERRUPTED;
        }
    }

    public void beginShutdown() {
        synchronized (admissionLock) {
            acceptingTasks.set(false);
            executor.shutdown();
        }
    }

    public CompletableFuture<Void> shutdownAfterPending(
        final Duration timeout,
        final IoTask... terminalTasks
    ) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(terminalTasks, "terminalTasks");
        if (terminalTasks.length == 0 || terminalTasks.length > MAX_TERMINAL_TASKS) {
            throw new IllegalArgumentException("terminalTasks must contain between 1 and " + MAX_TERMINAL_TASKS + " tasks.");
        }
        final IoTask[] tasks = terminalTasks.clone();
        for (final IoTask task : tasks) {
            Objects.requireNonNull(task, "terminalTask");
        }
        synchronized (admissionLock) {
            acceptingTasks.set(false);
            final IoFutureTask<Void> submittedTask = new IoFutureTask<>(() -> {
                runTerminalTasks(timeout, tasks);
                return null;
            }, () -> { }, false);
            final CompletableFuture<Void> completion = executor.setTerminalTask(submittedTask);
            if (completion != submittedTask.completion()) {
                return completion;
            }
            try {
                executor.execute(submittedTask);
            } catch (final RejectedExecutionException exception) {
                submittedTask.reject(new IllegalStateException("I/O executor could not schedule terminal shutdown.", exception));
            }
            executor.shutdown();
            return completion;
        }
    }

    public void forceShutdown() {
        synchronized (admissionLock) {
            acceptingTasks.set(false);
            if (executor.hasTerminalTask()) {
                executor.forceTerminalShutdown();
            } else {
                cancelQueuedTasks(executor.shutdownNow());
            }
            terminalWorkers.forEach(Thread::interrupt);
        }
    }

    private void runTerminalTasks(final Duration timeout, final IoTask[] tasks) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        Throwable failure = null;
        for (int index = 0; index < tasks.length; index++) {
            final int taskIndex = index;
            final CompletableFuture<Throwable> result = new CompletableFuture<>();
            final Thread worker = new Thread(
                () -> runTerminalTask(tasks[taskIndex], result),
                pluginName + " I/O terminal-" + (taskIndex + 1)
            );
            worker.setDaemon(true);
            terminalWorkers.add(worker);
            worker.start();
            try {
                final long remaining = remainingNanos(deadline);
                final int remainingTasks = tasks.length - index;
                final Throwable closeFailure = result.get(
                    Math.max(1L, remaining / remainingTasks), TimeUnit.NANOSECONDS
                );
                if (closeFailure != null) {
                    failure = appendFailure(failure, closeFailure);
                }
            } catch (final TimeoutException timeoutFailure) {
                worker.interrupt();
                failure = appendFailure(failure, timeoutFailure);
            } catch (final InterruptedException interrupted) {
                worker.interrupt();
                throw interrupted;
            } catch (final java.util.concurrent.ExecutionException impossible) {
                throw new IllegalStateException("Terminal I/O result completed exceptionally.", impossible);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private void runTerminalTask(
        final IoTask task,
        final CompletableFuture<Throwable> result
    ) {
        try {
            try {
                task.run();
                result.complete(null);
            } catch (final Throwable failure) {
                result.complete(failure);
            }
        } finally {
            terminalWorkers.remove(Thread.currentThread());
        }
    }

    private static long remainingNanos(final long deadline) throws TimeoutException {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("Terminal I/O tasks exceeded the shutdown deadline.");
        }
        return remaining;
    }

    private static Throwable appendFailure(final Throwable primary, final Throwable additional) {
        if (primary == null) {
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    private static void rethrow(final Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Could not close plugin resources.", failure);
    }

    private static void cancelQueuedTasks(final java.util.List<Runnable> queuedTasks) {
        queuedTasks.forEach(task -> {
            if (task instanceof IoFutureTask<?> ioTask) {
                ioTask.reject(new IllegalStateException("I/O task was cancelled during shutdown."));
            }
        });
    }

    @FunctionalInterface
    public interface IoTask {
        void run() throws Exception;
    }

    private static final class IoFutureTask<T> implements Runnable {
        private final Callable<T> task;
        private final Runnable released;
        private final boolean wrapFailure;
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean releasePending = new AtomicBoolean(true);
        private final AtomicBoolean interruptRequested = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicReference<Thread> runner =
            new java.util.concurrent.atomic.AtomicReference<>();

        private IoFutureTask(final Callable<T> task, final Runnable released, final boolean wrapFailure) {
            this.task = task;
            this.released = released;
            this.wrapFailure = wrapFailure;
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            final Thread current = Thread.currentThread();
            runner.set(current);
            if (interruptRequested.get()) {
                current.interrupt();
            }
            try {
                completion.complete(task.call());
            } catch (final Throwable failure) {
                completion.completeExceptionally(wrapFailure && failure instanceof Exception exception
                    ? new PluginIoException(exception)
                    : failure);
            } finally {
                runner.compareAndSet(current, null);
                releaseSlot();
            }
        }

        private CompletableFuture<T> completion() {
            return completion;
        }

        private void reject(final RuntimeException failure) {
            completion.completeExceptionally(failure);
            releaseSlot();
        }

        private void interrupt() {
            interruptRequested.set(true);
            final Thread runningThread = runner.get();
            if (runningThread != null) {
                runningThread.interrupt();
            }
        }

        private void releaseSlot() {
            if (releasePending.compareAndSet(true, false)) {
                released.run();
            }
        }
    }

    private static final class PluginThreadFactory implements ThreadFactory {
        private final String threadName;

        private PluginThreadFactory(final String pluginName) {
            this.threadName = pluginName + " I/O";
        }

        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class TerminalThreadPoolExecutor extends ThreadPoolExecutor {
        private final java.util.concurrent.atomic.AtomicReference<IoFutureTask<Void>> terminalTask =
            new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicReference<IoFutureTask<?>> runningTask =
            new java.util.concurrent.atomic.AtomicReference<>();

        private TerminalThreadPoolExecutor(
            final int corePoolSize,
            final int maximumPoolSize,
            final long keepAliveTime,
            final TimeUnit unit,
            final java.util.concurrent.BlockingQueue<Runnable> workQueue,
            final ThreadFactory threadFactory,
            final RejectedExecutionHandler handler
        ) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
        }

        private CompletableFuture<Void> setTerminalTask(final IoFutureTask<Void> task) {
            if (!terminalTask.compareAndSet(null, task)) {
                return CompletableFuture.failedFuture(new IllegalStateException("I/O shutdown is already configured."));
            }
            return task.completion();
        }

        @Override
        protected void beforeExecute(final Thread thread, final Runnable task) {
            super.beforeExecute(thread, task);
            if (task instanceof IoFutureTask<?> ioTask) {
                runningTask.set(ioTask);
            }
        }

        @Override
        protected void afterExecute(final Runnable task, final Throwable failure) {
            if (task instanceof IoFutureTask<?> ioTask) {
                runningTask.compareAndSet(ioTask, null);
            }
            super.afterExecute(task, failure);
        }

        private boolean hasTerminalTask() {
            return terminalTask.get() != null;
        }

        private void forceTerminalShutdown() {
            final IoFutureTask<Void> terminal = terminalTask.get();
            for (final Runnable queuedTask : getQueue().toArray(Runnable[]::new)) {
                if (queuedTask != terminal && remove(queuedTask) && queuedTask instanceof IoFutureTask<?> ioTask) {
                    ioTask.reject(new IllegalStateException("I/O task was cancelled during shutdown."));
                }
            }
            final IoFutureTask<?> running = runningTask.get();
            if (running != null && running != terminal) {
                running.interrupt();
            }
            if (remove(terminal)) {
                final Thread terminalCoordinator = new Thread(terminal, "PluginIoExecutor terminal coordinator");
                terminalCoordinator.setDaemon(true);
                terminalCoordinator.start();
            }
        }
    }

    private static final class PluginIoException extends RuntimeException {
        private PluginIoException(final Exception cause) {
            super(cause);
        }
    }
}
