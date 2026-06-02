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
 * Schedules tasks for execution, either immediately or after a delay.
 * <p>
 * Two scheduler implementations are provided:
 * <ul>
 *   <li>{@link FocessScheduler} — single-threaded, executes tasks in time order</li>
 *   <li>{@link ThreadPoolScheduler} — multi-threaded, executes tasks in parallel</li>
 * </ul>
 */
public interface Scheduler {

    /**
     * Runs a task immediately.
     *
     * @param runnable the task to run
     * @return a handle to the scheduled task
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    default Task run(final Runnable runnable) {
        return this.run(runnable, Duration.ZERO);
    }

    /**
     * Runs a task after the specified delay.
     *
     * @param runnable the task to run
     * @param delay    the delay before execution
     * @return a handle to the scheduled task
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    Task run(Runnable runnable, Duration delay);


    /**
     * Runs a task immediately with the given name.
     *
     * @param runnable the task to run
     * @param name     the name of the task
     * @return a handle to the scheduled task
     */
    default Task run(final Runnable runnable, final String name) {
        return this.run(runnable, Duration.ZERO, name);
    }

    /**
     * Runs a task after the specified delay with the given name.
     *
     * @param runnable the task to run
     * @param delay    the delay before execution
     * @param name     the name of the task
     * @return a handle to the scheduled task
     */
    Task run(Runnable runnable, Duration delay, String name);

    /**
     * Runs a task periodically after an initial delay.
     *
     * @param runnable the task to run
     * @param delay    the delay before the first execution
     * @param period   the period between successive executions
     * @return a handle to the scheduled task
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    Task runTimer(Runnable runnable, Duration delay, Duration period);

    /**
     * Runs a task periodically after an initial delay with the given name.
     *
     * @param runnable the task to run
     * @param delay    the delay before the first execution
     * @param period   the period between successive executions
     * @param name     the name of the task
     * @return a handle to the scheduled task
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    Task runTimer(Runnable runnable, Duration delay, Duration period, String name);

    /**
     * Runs a task periodically with the given name and exception handler.
     *
     * @param runnable the task to run
     * @param delay    the delay before the first execution
     * @param period   the period between successive executions
     * @param name     the name of the task
     * @param handler  the exception handler, called when the task throws an exception
     * @return a handle to the scheduled task
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    Task runTimer(Runnable runnable, Duration delay, Duration period, String name, Consumer<ExecutionException> handler);

    /**
     * Submits a callable task for immediate execution.
     *
     * @param callable the callable to execute
     * @param <V>      the return type
     * @return a handle to the scheduled callback
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    default <V> Callback<V> submit(final Callable<V> callable) {
        return this.submit(callable, Duration.ZERO);
    }

    /**
     * Submits a callable task for execution after the specified delay.
     *
     * @param callable the callable to execute
     * @param delay    the delay before execution
     * @param <V>      the return type
     * @return a handle to the scheduled callback
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    <V> Callback<V> submit(Callable<V> callable, Duration delay);

    /**
     * Submits a callable task for immediate execution with the given name.
     *
     * @param callable the callable to execute
     * @param name     the name of the task
     * @param <V>      the return type
     * @return a handle to the scheduled callback
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    default <V> Callback<V> submit(final Callable<V> callable, final String name) {
        return this.submit(callable, Duration.ZERO, name);
    }

    /**
     * Submits a callable task for execution after the specified delay with the given name.
     *
     * @param callable the callable to execute
     * @param delay    the delay before execution
     * @param <V>      the return type
     * @param name     the name of the task
     * @return a handle to the scheduled callback
     * @throws SchedulerClosedException if the scheduler has been shut down
     */
    <V> Callback<V> submit(Callable<V> callable, Duration delay, String name);

    /**
     * Cancels all pending tasks in the queue.
     */
    void cancelAll();

    /**
     * Returns the name of this scheduler.
     *
     * @return the scheduler name
     */
    String getName();

    /**
     * Shuts this scheduler down gracefully, letting running tasks finish.
     */
    void shutdown();

    /**
     * Returns whether this scheduler has been shut down.
     *
     * @return {@code true} if the scheduler has been shut down, {@code false} otherwise
     */
    boolean isShutdown();

    /**
     * Shuts this scheduler down immediately, attempting to stop running tasks via interruption.
     */
    void shutdownNow();

    /**
     * Sets the uncaught exception handler for scheduler threads.
     *
     * @param handler the exception handler
     */
    void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler);

    /**
     * Returns the uncaught exception handler for scheduler threads.
     *
     * @return the exception handler, or {@code null} if none is set
     */
    @Nullable Thread.UncaughtExceptionHandler getUncaughtExceptionHandler();

    /**
     * Returns the tasks still pending execution.
     *
     * @return an unmodifiable view of the pending tasks
     */
    @UnmodifiableView
    List<Task> getRemainingTasks();

    /**
     * Runs a task immediately with the given name and exception handler.
     *
     * @param runnable the task to run
     * @param name     the name of the task
     * @param handler  the exception handler, called when the task throws an exception
     * @return a handle to the scheduled task
     */
    default Task run(final Runnable runnable, final String name, final Consumer<ExecutionException> handler) {
        return this.run(runnable, Duration.ZERO, name, handler);
    }

    /**
     * Runs a task after the specified delay with the given name and exception handler.
     *
     * @param runnable the task to run
     * @param delay    the delay before execution
     * @param name     the name of the task
     * @param handler  the exception handler, called when the task throws an exception
     * @return a handle to the scheduled task
     */
    Task run(final Runnable runnable, final Duration delay, final String name, final Consumer<ExecutionException> handler);

    /**
     * Submits a callable task for immediate execution with the given name and exception handler.
     *
     * @param callable the callable to execute
     * @param name     the name of the task
     * @param handler  the exception handler; its return value is used as the result of {@link Callback#call()}
     * @param <V>      the return type
     * @return a handle to the scheduled callback
     */
    default <V> Callback<V> submit(final Callable<V> callable, final String name, final Function<ExecutionException,V> handler) {
        return this.submit(callable, Duration.ZERO, name, handler);
    }

    /**
     * Submits a callable task for execution after the specified delay with the given name and exception handler.
     *
     * @param callable the callable to execute
     * @param delay    the delay before execution
     * @param name     the name of the task
     * @param handler  the exception handler; its return value is used as the result of {@link Callback#call()}
     * @param <V>      the return type
     * @return a handle to the scheduled callback
     */
    <V> Callback<V> submit(final Callable<V> callable, final Duration delay, final String name, final Function<ExecutionException,V> handler);


}
