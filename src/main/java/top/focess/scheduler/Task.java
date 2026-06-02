package top.focess.scheduler;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The wrapped task. You can use this to handle runnable processing
 */
public interface Task {

    /**
     * Cancel this task
     * <p>
     * When {@code mayInterruptIfRunning} is true and the task is already running, the executing
     * thread is interrupted (its interrupt status is set). Cancellation is therefore cooperative:
     * a blocking call such as {@link Thread#sleep} throws {@link InterruptedException}, but a task
     * that swallows the interrupt without reacting to it will keep running to completion. The
     * executing thread itself is never forcibly stopped and is reused for later tasks.
     *
     * @param mayInterruptIfRunning true if cancel it without its status, false otherwise
     * @return true if it is cancelled, false it cannot be cancelled, or it is already cancelled
     */
    boolean cancel(boolean mayInterruptIfRunning);

    /**
     * Cancel this task
     *
     * @return true if it is cancelled, false it cannot be cancelled, or it is already cancelled
     * @see #cancel(boolean)
     */
    default boolean cancel() {
        return this.cancel(false);
    }

    /**
     * Indicate whether this task is running or not
     *
     * @return true if the task is running, false otherwise
     */
    boolean isRunning();

    /**
     * Get the scheduler it belongs to
     *
     * @return the scheduler it belongs to
     */
    Scheduler getScheduler();

    /**
     * Get the name of the task
     *
     * @return the name of the task
     */
    String getName();

    /**
     * Indicate whether this task is a period-task or not
     *
     * @return true if it is a period-task, false otherwise
     */
    boolean isPeriod();

    /**
     * Indicate whether this task is finished or not
     *
     * @return true if this task is finished, false otherwise
     */
    boolean isFinished();

    /**
     * Indicate whether this task is cancelled or not
     *
     * @return true if it is cancelled, false otherwise
     */
    boolean isCancelled();

    /**
     * Wait until this task is finished
     *
     * @throws ExecutionException    if there is any exception in the execution processing
     * @throws InterruptedException  if the task is interrupted
     * @throws CancellationException if the task is cancelled
     */
    void join() throws ExecutionException, InterruptedException, CancellationException;

    /**
     * Wait for the time
     *
     * @param timeout the timeout
     * @param unit    the time unit
     * @throws InterruptedException  if the current thread was interrupted while waiting
     * @throws ExecutionException    if there is any exception in the execution processing
     * @throws TimeoutException      if the time is out
     * @throws CancellationException if the task is cancelled
     */
    void join(final long timeout, final TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException;

    /**
     * Set the uncaught exception handler
     * <p>
     * Note: this handler will clear exception mark of this task
     *
     * @param handler the handler
     */
    void setExceptionHandler(Consumer<ExecutionException> handler);

}
