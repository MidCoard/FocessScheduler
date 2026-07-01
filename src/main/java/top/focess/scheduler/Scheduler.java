package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
 *   <li>{@link FocessScheduler} — single-threaded, executes tasks sequentially in time order</li>
 *   <li>{@link ThreadPoolScheduler} — multi-threaded, executes tasks in parallel across a worker pool</li>
 * </ul>
 * <p>
 * Both are composable: any {@link Dispatcher} works with any {@link TaskExecutor}
 * through the Scheduler's wiring API.
 * <p>
 * For {@link java.util.concurrent.ExecutorService} compatibility, use the concrete
 * implementations ({@link FocessScheduler}, {@link ThreadPoolScheduler}) which extend
 * {@link java.util.concurrent.AbstractExecutorService}.
 */
public interface Scheduler {

    /**
     * Schedules a task for immediate execution.
     */
    default @NonNull Task schedule(@NonNull Runnable runnable) {
        return this.schedule(runnable, Duration.ZERO);
    }

    /**
     * Schedules a task after the specified delay.
     */
    @NonNull Task schedule(@NonNull Runnable runnable, @NonNull Duration delay);

    /**
     * Schedules a named task for immediate execution.
     */
    default @NonNull Task schedule(@NonNull Runnable runnable, @NonNull String name) {
        return this.schedule(runnable, Duration.ZERO, name);
    }

    /**
     * Schedules a named task after the specified delay.
     */
    @NonNull Task schedule(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull String name);

    /**
     * Schedules a named task after the specified delay with an exception handler.
     */
    @NonNull Task schedule(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull String name, @Nullable Consumer<ExecutionException> handler);

    /**
     * Schedules a periodic task.
     */
    @NonNull Task scheduleAtFixedRate(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull Duration period);

    /**
     * Schedules a named periodic task.
     */
    @NonNull Task scheduleAtFixedRate(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull Duration period, @NonNull String name);

    /**
     * Schedules a named periodic task with an exception handler.
     */
    @NonNull Task scheduleAtFixedRate(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull Duration period, @NonNull String name, @Nullable Consumer<ExecutionException> handler);

    /**
     * Submits a callable after the specified delay.
     */
    <V> @NonNull Callback<V> submit(@NonNull Callable<V> callable, @NonNull Duration delay);

    /**
     * Submits a named callable after the specified delay.
     */
    <V> @NonNull Callback<V> submit(@NonNull Callable<V> callable, @NonNull Duration delay, @NonNull String name);

    /**
     * Submits a named callable after the specified delay with an exception handler.
     */
    <V> @NonNull Callback<V> submit(@NonNull Callable<V> callable, @NonNull Duration delay, @NonNull String name, @Nullable Function<ExecutionException, V> handler);

    /**
     * Cancels all pending tasks in the queue.
     */
    void cancelPending();

    /**
     * Returns the name of this scheduler.
     */
    @NonNull
    String getName();

    /**
     * Shuts this scheduler down gracefully, letting running tasks finish.
     */
    void shutdown();

    /**
     * Returns whether this scheduler has been shut down.
     */
    boolean isShutdown();

    /**
     * Shuts this scheduler down immediately, attempting to stop running tasks via interruption.
     *
     * @return a list of tasks that were awaiting execution (always empty for this implementation)
     */
    @NonNull
    List<Runnable> shutdownNow();

    /**
     * Sets the uncaught exception handler for scheduler threads.
     */
    void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler);

    /**
     * Returns the uncaught exception handler, or null.
     */
    Thread.UncaughtExceptionHandler getUncaughtExceptionHandler();

    /**
     * Returns the dispatcher used by this scheduler.
     */
    @NonNull
    Dispatcher getDispatcher();

    /**
     * Returns the executor used by this scheduler.
     */
    @NonNull
    TaskExecutor getTaskExecutor();

    /**
     * Interrupt the thread currently running the given task, if any.
     * Used internally for cooperative cancellation.
     */
    void interruptTaskIfRunning(@NonNull FocessTask task);
}
