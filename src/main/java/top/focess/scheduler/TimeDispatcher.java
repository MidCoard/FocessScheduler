package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Time-based dispatcher using a {@link DelayQueue} for thread-safe priority scheduling.
 * <p>
 * A single dispatcher thread blocks on {@code queue.poll(timeout)} until a task's scheduled
 * time arrives, then hands the task to the scheduler for execution via
 * {@link AbstractScheduler#onTaskReady(FocessTask)}.
 */
public class TimeDispatcher implements Dispatcher {

    private final DelayQueue<FocessTask> queue = new DelayQueue<>();
    private volatile AbstractScheduler scheduler;
    private final Thread thread;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    /** Whether to halt pending tasks (shutdownNow) vs let them drain (shutdown). */
    private volatile boolean haltPending = false;

    public TimeDispatcher(String name, boolean isDaemon) {
        this.thread = new Thread(this::runLoop, name + "-dispatcher");
        this.thread.setDaemon(isDaemon);
        this.thread.setUncaughtExceptionHandler((t, e) -> {
            AbstractScheduler s = scheduler;
            if (s != null) {
                s.shutdown();
                if (s.getUncaughtExceptionHandler() != null)
                    s.getUncaughtExceptionHandler().uncaughtException(t, e);
            }
        });
        this.thread.start();
    }

    /**
     * Set the scheduler reference. Called by AbstractScheduler after construction
     * to avoid leaking {@code this} in the constructor.
     *
     * @param scheduler the scheduler that owns this dispatcher
     */
    @Override
    public void setScheduler(@NonNull AbstractScheduler scheduler) {
        this.scheduler = scheduler;
    }

    private void runLoop() {
        while (!shutdown.get() || (!haltPending && !queue.isEmpty())) {
            try {
                // Use poll with a timeout instead of take() so the dispatcher
                // thread periodically checks the shutdown flag without needing
                // thread.interrupt() on graceful shutdown. This avoids disturbing
                // any inline task currently executing on the dispatcher thread
                // (FocessScheduler's InlineExecutor).
                FocessTask task = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (task == null) continue; // timeout — re-check flags
                if (haltPending) {
                    task.cancel(false);
                    continue;
                }
                if (!task.startRun()) continue;
                AbstractScheduler s = scheduler;
                if (s != null) {
                    s.onTaskReady(task);
                }
            } catch (InterruptedException e) {
                // shutdownNow() or cancel signal — loop will check flags
            }
        }
    }

    @Override
    public void dispatch(@NonNull FocessTask task) {
        if (shutdown.get()) throw new RejectedExecutionException("Scheduler " + (scheduler != null ? scheduler.getName() : "unknown") + " is closed");
        queue.add(task);
    }

    /**
     * Cancel all pending tasks in the queue, including tasks whose delay
     * has not yet expired. Uses {@link DelayQueue#drainTo} which removes
     * all elements regardless of their delay.
     */
    @Override
    public void cancelPending() {
        List<FocessTask> tasks = new ArrayList<>();
        queue.drainTo(tasks);
        for (FocessTask task : tasks) {
            task.cancel(false);
        }
    }

    @Override
    public void shutdown(boolean now) {
        if (shutdown.getAndSet(true))
            return;
        haltPending = now;
        if (now) {
            // Immediate shutdown: cancel pending tasks, interrupt the thread
            // to break out of any blocking wait (including an inline task
            // on the dispatcher thread).
            thread.interrupt();
        }
        // Graceful shutdown (now=false): keep dispatching pending tasks
        // until the queue is empty, then the thread exits naturally.
    }

    /**
     * Drain and cancel all pending tasks (including non-expired) from the queue.
     * Returns the list of tasks that were pending.
     */
    @Override
    @NonNull
    public List<FocessTask> drainPending() {
        List<FocessTask> tasks = new ArrayList<>();
        queue.drainTo(tasks);
        for (FocessTask task : tasks) {
            task.cancel(false);
        }
        return tasks;
    }

    /**
     * Whether the dispatcher thread has fully exited.
     */
    @Override
    public boolean isTerminated() {
        return !thread.isAlive();
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public String toString() {
        return thread.getName();
    }
}
