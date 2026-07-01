package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Time-based dispatcher using a {@link DelayQueue} for thread-safe priority scheduling.
 * <p>
 * A single dispatcher thread blocks on {@code queue.take()} until a task's scheduled
 * time arrives, then hands the task to the scheduler for execution via
 * {@link AbstractScheduler#onTaskReady(FocessTask)}.
 */
public class TimeDispatcher implements Dispatcher {

    private final DelayQueue<FocessTask> queue = new DelayQueue<>();
    private volatile AbstractScheduler scheduler;
    private final Thread thread;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

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
        while (!shutdown.get()) {
            try {
                // Use poll with a timeout instead of take() so the dispatcher
                // thread periodically checks the shutdown flag without needing
                // thread.interrupt() on graceful shutdown. This avoids disturbing
                // any inline task currently executing on the dispatcher thread
                // (FocessScheduler's InlineExecutor).
                FocessTask task = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (task == null) continue; // timeout — re-check shutdown flag
                if (shutdown.get()) {
                    task.cancel(false);
                    continue;
                }
                if (!task.startRun()) continue;
                AbstractScheduler s = scheduler;
                if (s != null) {
                    s.onTaskReady(task);
                }
            } catch (InterruptedException e) {
                // shutdownNow() or cancel signal — loop will check shutdown flag
            }
        }
        drainAndCancel();
    }

    private void drainAndCancel() {
        FocessTask task;
        while ((task = queue.poll()) != null) {
            task.cancel(false);
        }
    }

    @Override
    public void dispatch(@NonNull FocessTask task) {
        if (shutdown.get()) throw new SchedulerClosedException(scheduler);
        queue.add(task);
    }

    @Override
    public void cancelPending() {
        FocessTask task;
        while ((task = queue.poll()) != null) {
            task.cancel(false);
        }
    }

    @Override
    public void shutdown(boolean now) {
        if (shutdown.getAndSet(true))
            return;
        cancelPending();
        if (now) {
            // Immediate shutdown: interrupt the thread to break out of any
            // blocking wait (including an inline task on the dispatcher thread).
            thread.interrupt();
        }
        // Graceful shutdown (now=false): the dispatcher thread will notice the
        // shutdown flag on its next poll timeout (within 1 second) — no need
        // to interrupt, which could disturb an inline task.
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
