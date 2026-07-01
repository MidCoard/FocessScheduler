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
     * @throws java.util.concurrent.RejectedExecutionException if the dispatcher has been shut down
     */
    void dispatch(@NonNull FocessTask task);

    /**
     * Graceful shutdown: the dispatcher continues to dispatch pending tasks
     * until the queue is empty, then the thread exits naturally.
     */
    void shutdown();

    /**
     * Immediate shutdown: halts the dispatcher thread, drains and cancels
     * all pending tasks, and returns them.
     *
     * @return the tasks that were awaiting execution
     */
    @NonNull
    List<FocessTask> shutdownNow();

    /**
     * Whether the dispatcher has been shut down.
     *
     * @return {@code true} if shut down, {@code false} otherwise
     */
    boolean isShutdown();

    /**
     * Whether the dispatcher thread has fully exited and is no longer
     * dispatching tasks.
     *
     * @return {@code true} if terminated, {@code false} otherwise
     */
    boolean isTerminated();
}
