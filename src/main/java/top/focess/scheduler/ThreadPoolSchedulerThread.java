package top.focess.scheduler;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutionException;

/**
 * A worker thread in a {@link ThreadPoolScheduler}.
 * <p>
 * Waits for tasks to be assigned via {@link #startTask(FocessTask)}, executes them,
 * and returns to an available state. Supports graceful shutdown via {@link #shutdown()}
 * and immediate shutdown via {@link #shutdownNow()}.
 */
public class ThreadPoolSchedulerThread extends Thread {
    private final ThreadPoolScheduler scheduler;
    private final String name;

    private boolean available = true;
    @Nullable
    private FocessTask task;
    private boolean shouldStop;

    public ThreadPoolSchedulerThread(final ThreadPoolScheduler scheduler, final String name) {
        super(name);
        this.scheduler = scheduler;
        this.name = name;
        this.setUncaughtExceptionHandler((t, e) -> {
            try {
                this.shutdown();
                if (this.task != null) {
                    this.task.setException(new ExecutionException(e));
                    this.task.endRun();
                    scheduler.taskThreadMap.remove(this.task);
                }
                this.task = null;
                if (this.scheduler.getThreadUncaughtExceptionHandler() != null)
                    this.scheduler.getThreadUncaughtExceptionHandler().uncaughtException(t, e);
                this.scheduler.shutdown();
            } catch (final Throwable ex) {
                ex.printStackTrace(System.err);
            }
        });
        this.setDaemon(true);
        this.start();
    }

    @Override
    public void run() {
        while (true) {
            try {
                synchronized (this) {
                    if (this.shouldStop)
                        break;
                    if (this.available)
                        this.wait();
                    // task == null -> run once and stop -> close
                    // task != null -> run once and wait -> startTask
                }
                if (this.task != null) {
                    try {
                        this.task.run();
                    } catch (final ExecutionException e) {
                        this.task.setException(e);
                    } finally {
                        // consume any pending interrupt raised by cancel()/shutdownNow() so it does
                        // not leak into the next task this worker picks up
                        Thread.interrupted();
                    }
                    this.task.endRun();
                    this.scheduler.taskThreadMap.remove(this.task);
                    if (this.task.isPeriod() && !this.task.isCancelled())
                        this.scheduler.rerun(this.task);
                    this.task = null;
                    synchronized (this.scheduler.AVAILABLE_THREAD_LOCK) {
                        this.available = true;
                        this.scheduler.AVAILABLE_THREAD_LOCK.notify();
                    }
                }
            } catch (final Exception e) {
                e.printStackTrace(System.err);
                shutdown();
            }
        }
    }

    public boolean isAvailable() {
        return this.available;
    }

    synchronized void startTask(final FocessTask task) {
        this.task = task;
        this.task.startRun();
        this.available = false;
        this.notify();
    }

    public synchronized void shutdown() {
        this.shouldStop = true;
        this.notify();
    }

    public void shutdownNow() {
        this.shutdown();
        // interrupt the running task so a cooperative task can wind down instead of being
        // killed with the unsafe Thread#stop()
        this.interrupt();
    }

}
