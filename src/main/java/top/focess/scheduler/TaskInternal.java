package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

/**
 * Internal task interface used by Dispatcher and Executor.
 * Not part of the public API.
 */
interface TaskInternal extends Task {

    /**
     * Execute the task's payload.
     */
    void run() throws ExecutionException;

    /**
     * Returns the period for periodic tasks, or null for one-off tasks.
     */
    Duration getPeriod();

    /**
     * Reset a periodic task's state for re-scheduling.
     */
    void clear();

    /**
     * Attempt to transition to RUNNING state (CAS: PENDING → RUNNING).
     *
     * @return {@code true} if the transition succeeded, {@code false} if the
     *         task was already in a different state (e.g. CANCELLED)
     */
    boolean startRun();

    /**
     * Transition from RUNNING to FINISHED and fire completion listeners.
     */
    void endRun();

    /**
     * Set the exception produced by this task.
     */
    void setException(@NonNull ExecutionException e);

    /**
     * Get the exception produced by this task, or null.
     */
    ExecutionException getException();

    /**
     * Returns the {@link Runnable} representation of this task's payload.
     * Used by {@link AbstractScheduler#shutdownNow()} to satisfy the
     * {@code ExecutorService} contract.
     */
    Runnable asRunnable();
}
