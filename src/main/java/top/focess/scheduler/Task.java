package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * A handle to a scheduled task, providing methods to query execution state,
 * wait for completion, and request cancellation.
 * <p>
 * Note: Task does not extend {@link java.util.concurrent.Future} directly to avoid
 * generic type conflicts with {@link Callback}. Instead, {@link Callback} extends
 * both Task and {@code Future<V>}. Task provides cancel/isDone/isCancelled methods
 * that are semantically identical to Future's.
 */
public interface Task {

    /**
     * Cancels this task.
     * <p>
     * When {@code mayInterruptIfRunning} is {@code true} and the task is currently running,
     * the executing thread is interrupted. Cancellation is <em>cooperative</em>: a blocking
     * call such as {@link Thread#sleep} throws {@link InterruptedException}, but a task that
     * swallows the interrupt without reacting to it will continue to completion.
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
     * Returns whether this task has finished execution.
     *
     * @return {@code true} if the task is done, {@code false} otherwise
     */
    boolean isDone();

    /**
     * Returns whether this task has been cancelled.
     *
     * @return {@code true} if the task is cancelled, {@code false} otherwise
     */
    boolean isCancelled();

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
    @NonNull
    Scheduler getScheduler();

    /**
     * Returns the name of this task.
     *
     * @return the task name
     */
    @NonNull
    String getName();

    /**
     * Returns whether this task is a periodic task.
     *
     * @return {@code true} if the task is periodic, {@code false} otherwise
     */
    boolean isPeriod();

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
    void join(long timeout, @NonNull TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException;

    /**
     * Sets the exception handler for this task.
     * <p>
     * When set, the handler is invoked if the task throws an exception, consuming it
     * so that {@link #join()} does not throw.
     *
     * @param handler the exception handler, or {@code null} to clear
     */
    void setExceptionHandler(@Nullable Consumer<ExecutionException> handler);

    /**
     * Register a callback to be invoked when this task completes
     * (finishes, is cancelled, or throws an exception).
     * If the task is already done, the listener is called immediately.
     * <p>
     * Callers should wrap the listener in a fire-once guard to avoid
     * double-invocation in a race between {@code onComplete} and task completion.
     * <p>
     * Note: For periodic tasks, listeners are one-shot — they fire on the
     * first completion only and are cleared on re-dispatch via {@code clear()}.
     *
     * @param listener the callback to invoke on completion
     */
    void onComplete(@NonNull Runnable listener);
}
