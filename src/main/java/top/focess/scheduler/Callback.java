package top.focess.scheduler;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.util.concurrent.*;
import java.util.function.Function;

/**
 * A handle to a scheduled callable task that produces a value.
 * <p>
 * Extends {@link Task} with the ability to retrieve the computed result and implements
 * {@link Future} for compatibility with standard Java concurrency APIs.
 *
 * @param <V> the result type
 */
public interface Callback<V> extends Task, Future<V> {

    /**
     * Returns the computed result, blocking until the task completes if necessary.
     *
     * @return the computed value
     * @throws CancellationException    if the task was cancelled
     * @throws TaskNotFinishedException if the task has not yet finished
     * @throws ExecutionException       if the task threw an exception
     */
    V call() throws ExecutionException, CancellationException, TaskNotFinishedException;

    /**
     * Waits for the task to finish, then returns the computed result.
     *
     * @return the computed value
     * @throws InterruptedException if the current thread was interrupted while waiting
     * @throws ExecutionException   if the task threw an exception
     * @see #join()
     */
    default V waitCall() throws InterruptedException, ExecutionException {
        this.join();
        return this.call();
    }

    /**
     * Returns {@code true} if the task has finished.
     *
     * @return {@code true} if the task is finished, {@code false} otherwise
     * @see #isFinished()
     */
    @Override
    default boolean isDone() {
        return this.isFinished();
    }

    /**
     * Waits for the task to finish if necessary, then returns the computed result.
     *
     * @return the computed value
     * @throws InterruptedException if the current thread was interrupted while waiting
     * @throws ExecutionException   if the task threw an exception
     * @see #waitCall()
     * @see #join()
     */
    @Override
    default V get() throws InterruptedException, ExecutionException {
        return this.waitCall();
    }

    /**
     * Waits for at most the given time for the task to finish, then returns the computed result.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the computed value
     * @throws InterruptedException  if the current thread was interrupted while waiting
     * @throws ExecutionException    if the task threw an exception
     * @throws TimeoutException      if the wait timed out
     * @throws CancellationException if the task was cancelled
     * @see #join(long, TimeUnit)
     */
    @Override
    default V get(long timeout, @NotNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException, CancellationException {
        this.join(timeout, unit);
        return this.call();
    }

    /**
     * Sets the exception handler for this callback.
     * <p>
     * When the task throws an exception, the handler is invoked and its return value
     * is used as the result of {@link #call()}, effectively suppressing the exception.
     *
     * @param handler the exception handler
     */
    void setExceptionHandler(Function<ExecutionException,V> handler);
}
