package top.focess.scheduler;


import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Used to schedule task
 */
public interface Scheduler {

    /**
     * Run a task now
     *
     * @param runnable the task
     * @return the wrapped task
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    default Task run(final Runnable runnable) {
        return this.run(runnable, Duration.ZERO);
    }

    /**
     * Run a task later
     *
     * @param runnable the task
     * @param delay    the delay
     * @return the wrapped task
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    Task run(Runnable runnable, Duration delay);

    /**
     * Run a task timer
     *
     * @param runnable the task
     * @param delay    the delay
     * @param period   the period
     * @return the wrapped task
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    Task runTimer(Runnable runnable, Duration delay, Duration period);

    /**
     * Submit a task now
     *
     * @param callable the task
     * @param <V>      the return type
     * @return the wrapped callback
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    default <V> Callback<V> submit(final Callable<V> callable) {
        return this.submit(callable, Duration.ZERO);
    }

    /**
     * Submit a task later
     *
     * @param callable the task
     * @param delay    the delay
     * @param <V>      the return type
     * @return the wrapped callback
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    <V> Callback<V> submit(Callable<V> callable, Duration delay);

    /**
     * Cancel all the tasks
     */
    void cancelAll();

    /**
     * Get the name of the scheduler
     *
     * @return the name of the scheduler
     */
    String getName();

    /**
     * Close this scheduler
     */
    void close();

    /**
     * Indicate whether this scheduler is closed or not
     *
     * @return true if this scheduler is closed, false otherwise
     */
    boolean isClosed();

    /**
     * Close this scheduler even if there are tasks running
     */
    void closeNow();

    void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler);

    @Nullable Thread.UncaughtExceptionHandler getUncaughtExceptionHandler();

    @Nullable CatchExceptionHandler getCatchExceptionHandler();

    void setCatchExceptionHandler(CatchExceptionHandler catchExceptionHandler);
}
