package top.focess.scheduler;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * A handle to a scheduled task, providing methods to query execution state,
 * wait for completion, and request cancellation.
 */
public interface Task {

    /**
     * Cancels this task.
     * <p>
     * When {@code mayInterruptIfRunning} is {@code true} and the task is currently running,
     * the executing thread is interrupted. Cancellation is <em>cooperative</em>: a blocking
     * call such as {@link Thread#sleep} throws {@link InterruptedException}, but a task that
     * swallows the interrupt without reacting to it will continue to completion. The executing
     * thread is never forcibly stopped and is reused for later tasks.
     *
     * @param mayInterruptIfRunning {@code true} to interrupt the running task's thread
     * @return {@code true} if the task was cancelled, {@code false} if it was already
     *         cancelled or had already finished
     */
    boolean cancel(boolean mayInterruptIfRunning);

    /**
     * Cancels this task without interrupting the executing thread.
     *
     * @return {@code true} if the task was cancelled, {@code false} otherwise
     * @see #cancel(boolean)
     */
    default boolean cancel() {
        return this.cancel(false);
    }

    /**
     * Returns whether this task is currently executing.
     *
     * @return {@code true} if the task is running, {@code false} otherwise
     */
    boolean isRunning();

    /**
     * Returns the scheduler this task belongs to.
     *
     * @return the owning scheduler
     */
    Scheduler getScheduler();

    /**
     * Returns the name of this task.
     *
     * @return the task name
     */
    String getName();

    /**
     * Returns whether this task is a periodic task.
     *
     * @return {@code true} if the task is periodic, {@code false} otherwise
     */
    boolean isPeriod();

    /**
     * Returns whether this task has finished execution.
     *
     * @return {@code true} if the task is finished, {@code false} otherwise
     */
    boolean isFinished();

    /**
     * Returns whether this task has been cancelled.
     *
     * @return {@code true} if the task is cancelled, {@code false} otherwise
     */
    boolean isCancelled();

    /**
     * Waits until this task finishes, is cancelled, or throws an exception.
     *
     * @throws ExecutionException    if the task threw an exception
     * @throws InterruptedException  if the current thread was interrupted while waiting
     * @throws CancellationException if the task was cancelled
     */
    void join() throws ExecutionException, InterruptedException, CancellationException;

    /**
     * Waits at most the given time for this task to finish.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @throws InterruptedException  if the current thread was interrupted while waiting
     * @throws ExecutionException    if the task threw an exception
     * @throws TimeoutException      if the wait timed out
     * @throws CancellationException if the task was cancelled
     */
    void join(final long timeout, final TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException;

    /**
     * Sets the exception handler for this task.
     * <p>
     * When set, the handler is invoked if the task throws an exception, consuming it
     * so that {@link #join()} does not throw.
     *
     * @param handler the exception handler, or {@code null} to clear
     */
    void setExceptionHandler(Consumer<ExecutionException> handler);

}
