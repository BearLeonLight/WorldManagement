package io.github.bearl.worldmanagement.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Paper scheduler adapter for global, location, and entity-affine work. */
public final class PaperWorldThreadDispatcher implements WorldThreadDispatcher {

    private final Plugin plugin;
    private final Set<PendingGlobalTask> pendingGlobalTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean acceptingGlobalTasks = new AtomicBoolean(true);

    public PaperWorldThreadDispatcher(final Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void executeGlobal(final Runnable task) {
        executeGlobal(task, () -> { });
    }

    @Override
    public void executeGlobal(final Runnable task, final Runnable cancelledTask) {
        final PendingGlobalTask pending = new PendingGlobalTask(task, cancelledTask);
        if (!acceptingGlobalTasks.get()) {
            pending.cancel();
            return;
        }
        pendingGlobalTasks.add(pending);
        if (!acceptingGlobalTasks.get()) {
            pending.cancel();
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, pending::run);
        } catch (final RuntimeException exception) {
            pending.cancel();
        }
    }

    @Override
    public void executeGlobalLater(final Duration delay, final Runnable task) {
        executeGlobalLater(delay, task, () -> { });
    }

    @Override
    public void executeGlobalLater(final Duration delay, final Runnable task, final Runnable cancelledTask) {
        final long ticks = Math.max(1L, (Objects.requireNonNull(delay, "delay").toMillis() + 49L) / 50L);
        final PendingGlobalTask pending = new PendingGlobalTask(task, cancelledTask);
        if (!acceptingGlobalTasks.get()) {
            pending.cancel();
            return;
        }
        pendingGlobalTasks.add(pending);
        if (!acceptingGlobalTasks.get()) {
            pending.cancel();
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().runDelayed(
                plugin,
                scheduledTask -> pending.run(),
                ticks
            );
        } catch (final RuntimeException exception) {
            pending.cancel();
        }
    }

    @Override
    public void executeAt(final Location location, final Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, Objects.requireNonNull(location, "location"), Objects.requireNonNull(task, "task"));
    }

    @Override
    public boolean executeFor(final Entity entity, final Runnable task, final Runnable retiredTask) {
        return Objects.requireNonNull(entity, "entity")
            .getScheduler()
            .execute(plugin, Objects.requireNonNull(task, "task"), Objects.requireNonNull(retiredTask, "retiredTask"), 1L);
    }

    @Override
    public void cancelOwnedTasks() {
        acceptingGlobalTasks.set(false);
        pendingGlobalTasks.forEach(PendingGlobalTask::cancel);
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    private final class PendingGlobalTask {
        private final Runnable task;
        private final Runnable cancelledTask;
        private final AtomicBoolean completed = new AtomicBoolean();

        private PendingGlobalTask(final Runnable task, final Runnable cancelledTask) {
            this.task = Objects.requireNonNull(task, "task");
            this.cancelledTask = Objects.requireNonNull(cancelledTask, "cancelledTask");
        }

        private void run() {
            complete(task);
        }

        private void cancel() {
            complete(cancelledTask);
        }

        private void complete(final Runnable completion) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            pendingGlobalTasks.remove(this);
            completion.run();
        }
    }
}
