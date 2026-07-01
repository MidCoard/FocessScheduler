package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import top.focess.scheduler.exceptions.PeriodTaskException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A composable task pool that groups tasks and completes based on a
 * pluggable {@link CompletionStrategy}.
 * <p>
 * Tasks don't know about pools. Pools observe task completion through
 * the {@link Task#onComplete(Runnable)} listener, using a strategy to
 * determine when the pool is complete.
 * <p>
 * <b>Contract:</b> Only non-period tasks can be added; period tasks are rejected with
 * {@link PeriodTaskException}, because they cycle indefinitely and never produce
 * the terminal completion event a pool depends on.
 */
public class TaskPool {

    /**
     * Strategy that determines when a pool is "complete".
     */
    @FunctionalInterface
    public interface CompletionStrategy {
        /**
         * Called when a task in the pool finishes.
         *
         * @param pool      the pool
         * @param task      the task that finished
         * @param remaining the number of tasks still pending in the pool
         * @return true if the pool should be marked complete
         */
        boolean onComplete(TaskPool pool, Task task, int remaining);
    }

    /** All tasks must complete. */
    public static final CompletionStrategy ALL = (pool, task, remaining) -> remaining == 0;
    /** Any task completing triggers pool completion. */
    public static final CompletionStrategy ANY = (pool, task, remaining) -> true;

    private final Scheduler scheduler;
    private final CompletionStrategy strategy;
    private final Runnable callback;
    private final AtomicInteger remaining = new AtomicInteger(0);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private volatile Task callbackTask;

    public TaskPool(Scheduler scheduler, CompletionStrategy strategy, Runnable callback) {
        this.scheduler = scheduler;
        this.strategy = strategy;
        this.callback = callback;
    }

    /**
     * Add a non-periodic task to this pool.
     *
     * @param task the task to add (must be non-period)
     * @throws PeriodTaskException if the task is a period task
     */
    public void addTask(@NonNull Task task) {
        if (task.isPeriod()) throw new PeriodTaskException(task);
        remaining.incrementAndGet();
        // Use AtomicBoolean to ensure onTaskComplete fires exactly once
        // (onComplete may fire the listener twice in a race)
        AtomicBoolean fired = new AtomicBoolean(false);
        task.onComplete(() -> {
            if (fired.compareAndSet(false, true)) {
                onTaskComplete(task);
            }
        });
    }

    private void onTaskComplete(Task task) {
        int left = remaining.decrementAndGet();
        if (strategy.onComplete(this, task, left)) {
            markFinished();
        }
    }

    /**
     * Mark this pool as finished and wake up any threads waiting in {@link #join()}.
     */
    protected void markFinished() {
        if (finished.compareAndSet(false, true)) {
            if (callback != null) callbackTask = scheduler.schedule(callback);
            completionLatch.countDown();
        }
    }

    /**
     * Wait for the pool's completion condition, then for the callback task if any.
     *
     * @throws ExecutionException   if the callback task threw an exception
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public void join() throws ExecutionException, InterruptedException {
        completionLatch.await();
        if (callbackTask != null) callbackTask.join();
    }

    /**
     * Whether the pool's completion condition has been met.
     *
     * @return {@code true} if finished, {@code false} otherwise
     */
    public boolean isFinished() {
        return finished.get();
    }
}
