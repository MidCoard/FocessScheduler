package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Time-based dispatcher using a {@link DelayQueue} for thread-safe priority scheduling.
 * <p>
 * A single dispatcher thread polls the queue for tasks whose delay has elapsed,
 * then hands them to the scheduler for execution via
 * {@link AbstractScheduler#onTaskReady(FocessTask)}.
 * <p>
 * {@code synchronized(this)} serializes {@link #dispatch} and {@link #shutdownNow()}
 * so that the compound {@code toArray + clear} in {@code shutdownNow} is atomic
 * with respect to {@code dispatch}'s {@code add}. The dispatcher thread's
 * {@code poll()} is <em>not</em> locked — the race with {@code shutdownNow} is
 * harmless because {@code halt} is set before clearing the queue, so any
 * task the dispatcher polls after that point is simply cancelled (a task cancelled
 * twice is a no-op).
 */
public class TimeDispatcher implements Dispatcher {

    private final DelayQueue<FocessTask> queue = new DelayQueue<>();
    private volatile AbstractScheduler scheduler;
    private final Thread thread;
    /** Whether the dispatcher has been shut down. */
    private volatile boolean shutdown = false;
    /** Whether to halt pending tasks (shutdownNow) vs let them drain (shutdown). */
    private volatile boolean halt = false;

    public TimeDispatcher(String name, boolean isDaemon) {
        this.thread = new Thread(this::runLoop, name + "-dispatcher");
        this.thread.setDaemon(isDaemon);
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
        while (!shutdown || (!halt && !queue.isEmpty())) {
            FocessTask task;
            try {
                task = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // shutdownNow() interrupted us — loop will check flags
                continue;
            }
            if (task == null) continue; // timeout — re-check flags
            if (halt) {
                task.cancel(false);
                continue;
            }
            if (!task.startRun()) continue;
            AbstractScheduler s = scheduler;
            if (s != null) {
                s.onTaskReady(task);
            }
        }
    }

    @Override
    public synchronized void dispatch(@NonNull FocessTask task) {
        if (shutdown)
            throw new RejectedExecutionException("Scheduler " + (scheduler != null ? scheduler.getName() : "unknown") + " is closed");
        queue.add(task);
    }

    /**
     * Graceful shutdown: the dispatcher continues to dispatch pending tasks
     * until the queue is empty, then the thread exits naturally.
     */
    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    /**
     * Immediate shutdown: halts the dispatcher thread, drains and cancels
     * all pending tasks (including those whose delay has not yet expired),
     * and returns them. This satisfies the {@code ExecutorService.shutdownNow()}
     * contract.
     * <p>
     * The lock ensures that no concurrent {@link #dispatch} call can slip
     * a task into the queue between the snapshot and the clear.
     * <p>
     * The dispatcher thread's {@code poll()} is not locked — if it polls a
     * task after we set {@code halt}, it simply cancels that task
     * (harmless double-cancel).
     *
     * @return the tasks that were awaiting execution
     */
    @Override
    @NonNull
    public synchronized List<FocessTask> shutdownNow() {
        if (halt) return List.of();
        shutdown = true;
        halt = true;
        // Drain all remaining tasks (including non-expired).
        // DelayQueue.drainTo only removes expired elements, so we
        // use toArray + clear to capture everything.
        Object[] array = queue.toArray();
        queue.clear();
        List<FocessTask> tasks = new ArrayList<>(array.length);
        for (Object o : array) {
            FocessTask task = (FocessTask) o;
            task.cancel(false);
            tasks.add(task);
        }
        // Interrupt the dispatcher thread outside the lock so it wakes
        // from poll() immediately.
        thread.interrupt();
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
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public String toString() {
        return thread.getName();
    }
}
