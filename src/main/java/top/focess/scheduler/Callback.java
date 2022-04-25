package top.focess.scheduler;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.util.concurrent.*;
import java.util.function.Function;

/**
 * The wrapped callback. You can use this to handle callable processing
 *
 * @param <V> the target value type
 */
public interface Callback<V> extends Task, Future<V> {

    /**
     * Call the target value
     *
     * @return the target value
     * @throws CancellationException    if the task is cancelled
     * @throws TaskNotFinishedException if the task is not finished
     * @throws ExecutionException       if there is any exception in the execution processing
     */
    V call() throws ExecutionException, CancellationException, TaskNotFinishedException;

    /**
     * Wait for this task finished and call the target value
     *
     * @return the target value
     * @throws InterruptedException if the current thread was interrupted while waiting
     * @throws ExecutionException   if there is any exception in the execution processing
     */
    V waitCall() throws InterruptedException, ExecutionException;

    /**
     * Indicate whether this task is done or not
     *
     * @return true if this task is finished, false otherwise
     * @see #isFinished()
     */
    @Override
    default boolean isDone() {
        return this.isFinished();
    }

    /**
     * Wait for this task finished and call the target value
     *
     * @return the target value
     * @throws InterruptedException if the current thread was interrupted while waiting
     * @throws ExecutionException   if there is any exception in the execution processing
     * @see #waitCall()
     */
    @Override
    default V get() throws InterruptedException, ExecutionException {
        return this.waitCall();
    }

    /**
     * Wait for the time and call the target value
     *
     * @param timeout the timeout
     * @param unit    the time unit
     * @return the target value
     * @throws InterruptedException  if the current thread was interrupted while waiting
     * @throws ExecutionException    if there is any exception in the execution processing
     * @throws TimeoutException      if the time is out
     * @throws CancellationException if the task is cancelled
     */
    @Override
    V get(long timeout, @NotNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException, CancellationException;

    @Override
    default void join() throws ExecutionException, CancellationException, InterruptedException {
        this.waitCall();
    }

    /**
     * Set the exception handler
     * <p>
     * Note: this handler will clear the exception mark of this task
     *
     * @param handler the exception handler, the {@link Function} return value will be used in the {@link #call()} method
     */
    void setExceptionHandler(Function<ExecutionException,V> handler);
}
