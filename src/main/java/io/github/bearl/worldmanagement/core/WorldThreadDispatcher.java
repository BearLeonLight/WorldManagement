package io.github.bearl.worldmanagement.core;

import java.time.Duration;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Routes Paper work to the scheduler context that owns the affected game state. */
public interface WorldThreadDispatcher {

    void executeGlobal(Runnable task);

    default void executeGlobal(final Runnable task, final Runnable cancelledTask) {
        executeGlobal(task);
    }

    default void executeGlobalLater(final Duration delay, final Runnable task) {
        executeGlobal(task);
    }

    default void executeGlobalLater(final Duration delay, final Runnable task, final Runnable cancelledTask) {
        executeGlobalLater(delay, task);
    }

    void executeAt(Location location, Runnable task);

    boolean executeFor(Entity entity, Runnable task, Runnable retiredTask);

    void cancelOwnedTasks();
}
