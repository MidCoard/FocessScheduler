package top.focess.scheduler;

import java.util.UUID;

/**
 * Multi-threaded scheduler: a dispatcher thread hands tasks to a pool of workers.
 * <p>
 * Uses {@link TimeDispatcher} + {@link PoolTaskExecutor} internally.
 * The dispatcher thread blocks on the {@link java.util.concurrent.DelayQueue}
 * until a task's scheduled time arrives, then hands it to a worker thread for execution.
 * <p>
 * A running task can be cooperatively cancelled via {@link Task#cancel(boolean) cancel(true)},
 * which interrupts the worker thread executing it. The interrupt is isolated to that
 * specific worker — other tasks are not affected.
 */
public class ThreadPoolScheduler extends AbstractScheduler {

    /**
     * Composable constructor — any Dispatcher with any TaskExecutor.
     *
     * @param dispatcher the dispatcher to use
     * @param executor   the executor to use
     */
    public ThreadPoolScheduler(Dispatcher dispatcher, TaskExecutor executor) {
        super(dispatcher, executor, deriveName(dispatcher));
    }

    /**
     * Creates a new {@code ThreadPoolScheduler} with non-daemon dispatcher thread.
     *
     * @param poolSize  the number of worker threads
     * @param immediate if {@code true}, the pool expands dynamically when all workers are busy;
     *                  if {@code false}, tasks wait for an available worker
     * @param name      the scheduler name
     */
    public ThreadPoolScheduler(int poolSize, boolean immediate, String name) {
        this(poolSize, immediate, name, false);
    }

    /**
     * Creates a new {@code ThreadPoolScheduler}.
     *
     * @param poolSize  the number of worker threads
     * @param immediate if {@code true}, the pool expands dynamically when all workers are busy
     * @param name      the scheduler name
     * @param isDaemon  {@code true} to create a daemon dispatcher thread
     *                   (workers are always daemon threads)
     */
    public ThreadPoolScheduler(int poolSize, boolean immediate, String name, boolean isDaemon) {
        this(poolSize, immediate, name, isDaemon, Integer.MAX_VALUE);
    }

    /**
     * Creates a new {@code ThreadPoolScheduler} with a maximum pool size cap.
     *
     * @param poolSize    the number of core worker threads
     * @param immediate   if {@code true}, the pool expands dynamically when all workers are busy
     * @param name        the scheduler name
     * @param isDaemon    {@code true} to create a daemon dispatcher thread
     * @param maxPoolSize the maximum number of worker threads (caps unbounded spawning in immediate mode)
     */
    public ThreadPoolScheduler(int poolSize, boolean immediate, String name, boolean isDaemon, int maxPoolSize) {
        super(new TimeDispatcher(name, isDaemon),
              new PoolTaskExecutor(poolSize, immediate, name, maxPoolSize), name);
    }

    /**
     * Creates a new {@code ThreadPoolScheduler} with non-immediate, non-daemon defaults
     * and an auto-generated name.
     *
     * @param prefix   the prefix for the generated scheduler name
     * @param poolSize the number of worker threads
     */
    public ThreadPoolScheduler(String prefix, int poolSize) {
        this(poolSize, false, prefix + "-ThreadPoolScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String deriveName(Dispatcher dispatcher) {
        return dispatcher.toString();
    }
}
