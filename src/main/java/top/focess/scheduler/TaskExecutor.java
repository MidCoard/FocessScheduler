package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

/**
 * Executes tasks that the dispatcher has handed off.
 * Reports completion via a callback.
 */
public interface TaskExecutor {

    /**
     * Set the scheduler that owns this executor.
     * Called by the scheduler after construction to avoid leaking
     * {@code this} in the constructor.
     *
     * @param scheduler the scheduler that owns this executor
     */
    void setScheduler(@NonNull AbstractScheduler scheduler);

    /**
     * Execute a task. When done (success, failure, or cancellation),
     * call the completionCallback.
     *
     * @param task              the task to execute
     * @param completionCallback called after the task finishes (including endRun)
     */
    void execute(@NonNull FocessTask task, @NonNull Runnable completionCallback);

    /**
     * Interrupt the thread currently running the given task, if any.
     *
     * @param task the task whose executing thread should be interrupted
     */
    void interruptTask(@NonNull FocessTask task);

    /**
     * Shutdown the executor.
     *
     * @param now if true, interrupt running tasks; if false, let them finish
     */
    void shutdown(boolean now);

    /**
     * Whether the executor has been shut down.
     *
     * @return {@code true} if shut down, {@code false} otherwise
     */
    boolean isShutdown();

    /**
     * Whether the executor has fully terminated — shut down and
     * no running tasks remain.
     *
     * @return {@code true} if terminated, {@code false} otherwise
     */
    boolean isTerminated();
}
