package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
     * Returns the computed result without blocking.
     *
     * @return the computed value
     * @throws CancellationException    if the task was cancelled
     * @throws TaskNotFinishedException if the task has not yet finished
     * @throws ExecutionException       if the task threw an exception
     */
    V getNow() throws ExecutionException, CancellationException, TaskNotFinishedException;

    /**
     * Waits for the task to finish, then returns the computed result.
     * <p>
     * This is the {@link Future#get()} implementation.
     *
     * @return the computed value
     * @throws InterruptedException if the current thread was interrupted while waiting
     * @throws ExecutionException   if the task threw an exception
     * @see #join()
     */
    @Override
    default V get() throws InterruptedException, ExecutionException {
        join();
        return getNow();
    }

    /**
     * Waits at most the given time for the task to finish, then returns the computed result.
     * <p>
     * This is the {@link Future#get(long, TimeUnit)} implementation.
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
    default V get(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException, CancellationException {
        join(timeout, unit);
        return getNow();
    }

    /**
     * Sets the exception handler for this callback.
     * <p>
     * When the task throws an exception, the handler is invoked and its return value
     * is used as the result of {@link #getNow()}, effectively suppressing the exception.
     *
     * @param handler the exception handler
     */
    void setExceptionHandler(@NonNull Function<ExecutionException, V> handler);
}
