package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Dispatches tasks based on timing.
 * When a task's scheduled time arrives, the dispatcher notifies the scheduler
 * via {@link AbstractScheduler#onTaskReady(FocessTask)}, which then delegates to the executor.
 */
public interface Dispatcher {

    /**
     * Set the scheduler that owns this dispatcher.
     * Called by the scheduler after construction to avoid leaking
     * {@code this} in the constructor.
     *
     * @param scheduler the scheduler that owns this dispatcher
     */
    void setScheduler(@NonNull AbstractScheduler scheduler);

    /**
     * Submit a task for dispatching.
     *
     * @param task the task to dispatch
     * @throws top.focess.scheduler.exceptions.SchedulerClosedException if the dispatcher has been shut down
     */
    void dispatch(@NonNull FocessTask task);

    /**
     * Cancel all pending (not-yet-dispatched) tasks.
     */
    void cancelPending();

    /**
     * Shutdown the dispatcher.
     *
     * @param now if true, stop immediately; if false, cancel pending tasks and stop gracefully
     */
    void shutdown(boolean now);

    /**
     * Whether the dispatcher has been shut down.
     *
     * @return {@code true} if shut down, {@code false} otherwise
     */
    boolean isShutdown();

    /**
     * Drain all pending tasks from the queue (including non-expired) and
     * cancel them. Returns the list of tasks that were drained.
     *
     * @return the tasks that were awaiting execution
     */
    @NonNull
    List<FocessTask> drainPending();

    /**
     * Whether the dispatcher thread has fully exited and is no longer
     * dispatching tasks.
     *
     * @return {@code true} if terminated, {@code false} otherwise
     */
    boolean isTerminated();
}
