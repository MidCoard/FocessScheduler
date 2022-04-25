package top.focess.scheduler;


import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

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
     * Run a task now
     * @param runnable the task
     * @param name     the name of the task
     * @return the wrapped task
     */
    default Task run(final Runnable runnable, final String name) {
        return this.run(runnable, Duration.ZERO, name);
    }

    /**
     * Run a task later
     * @param runnable the task
     * @param delay    the delay
     * @param name     the name of the task
     * @return the wrapped task
     */
    Task run(Runnable runnable, Duration delay, String name);

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
     * Run a task timer
     *
     * @param runnable the task
     * @param delay    the delay
     * @param period   the period
     * @param name     the name of the task
     * @return the wrapped task
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    Task runTimer(Runnable runnable, Duration delay, Duration period, String name);

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
     * Submit a task now
     *
     * @param callable the task
     * @param name    the name of the task
     * @param <V>      the return type
     * @return the wrapped callback
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    default <V> Callback<V> submit(final Callable<V> callable, final String name) {
        return this.submit(callable, Duration.ZERO, name);
    }

    /**
     * Submit a task later
     *
     * @param callable the task
     * @param delay    the delay
     * @param <V>      the return type
     * @param name     the name of the task
     * @return the wrapped callback
     *
     * @throws SchedulerClosedException if this scheduler is closed
     */
    <V> Callback<V> submit(Callable<V> callable, Duration delay, String name);

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

    /**
     * Set the uncaught exception handler
     * @param handler the uncaught exception handler
     */
    void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler);

    /**
     * Get the uncaught exception handler
     * @return the uncaught exception handler
     */
    @Nullable Thread.UncaughtExceptionHandler getUncaughtExceptionHandler();

    /**
     * Get the catch exception handler
     * @return the catch exception handler
     */
    @Nullable CatchExceptionHandler getCatchExceptionHandler();

    /**
     * Set the catch exception handler
     * @param catchExceptionHandler the catch exception handler
     */
    void setCatchExceptionHandler(CatchExceptionHandler catchExceptionHandler);

    /**
     * Get the remaining tasks that have not been executed
     * @return the remaining tasks
     */
    @UnmodifiableView
    List<Task> getRemainingTasks();

    /**
     * Submit a task now with exception handler
     * @param runnable the task
     * @param name the name of the task
     * @param handler the exception handler
     * @return the wrapped task
     */
    default Task run(final Runnable runnable, final String name, final Consumer<ExecutionException> handler) {
        return this.run(runnable, Duration.ZERO, name, handler);
    }

    /**
     * Submit a task later with exception handler
     * @param runnable the task
     * @param delay the delay
     * @param name the name of the task
     * @param handler the exception handler
     * @return the wrapped task
     */
    Task run(final Runnable runnable, final Duration delay, final String name, final Consumer<ExecutionException> handler);

    /**
     * Submit a task now with exception handler
     * @param callable the task
     * @param name the name of the task
     * @param handler the exception handler
     * @return the wrapped callback
     * @param <V> the return type
     */
    default <V> Callback<V> submit(final Callable<V> callable, final String name, final Function<ExecutionException,V> handler) {
        return this.submit(callable, Duration.ZERO, name, handler);
    }

    /**
     * Submit a task later with exception handler
     * @param callable the task
     * @param delay the delay
     * @param name the name of the task
     * @param handler the exception handler
     * @return the wrapped callback
     * @param <V> the return type
     */
    <V> Callback<V> submit(final Callable<V> callable, final Duration delay, final String name, final Function<ExecutionException,V> handler);
}
