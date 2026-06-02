package top.focess.scheduler;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutionException;

public class ThreadPoolSchedulerThread extends Thread {
    private final ThreadPoolScheduler scheduler;
    private final String name;

    private boolean available = true;
    @Nullable
    private ITask task;
    private boolean shouldStop;

    public ThreadPoolSchedulerThread(final ThreadPoolScheduler scheduler, final String name) {
        super(name);
        this.scheduler = scheduler;
        this.name = name;
        this.setUncaughtExceptionHandler((t, e) -> {
            this.shutdown();
            if (this.task != null) {
                this.task.setException(new ExecutionException(e));
                this.task.endRun();
                scheduler.taskThreadMap.remove(this.task);
                if (this.task.isPeriod() && !this.task.isCancelled())
                    this.scheduler.rerun(this.task);
            }
            this.task = null;
            if (this.scheduler.getThreadUncaughtExceptionHandler() != null)
                this.scheduler.getThreadUncaughtExceptionHandler().uncaughtException(t, e);
            if (!this.scheduler.isShutdown())
                this.scheduler.recreate(this.name);
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
                    } catch (final Exception e) {
                        this.task.setException(new ExecutionException(e));
                    }
                    // consume any pending interrupt raised by cancel()/shutdownNow() so it does
                    // not leak into the next task this worker picks up
                    Thread.interrupted();
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
                e.printStackTrace();
            }
        }
    }

    public boolean isAvailable() {
        return this.available;
    }

    public synchronized void startTask(final ITask task) {
        this.task = task;
        this.task.startRun();
        this.available = false;
        this.notify();
    }

    public synchronized void shutdown() {
        this.shouldStop = true;
        this.notify();
        // if the thread is waiting, it will be notified and stop, and task will be null
        // if the thread is running, it will stop after the task is finished, and task will be null
    }

    public void shutdownNow() {
        this.shutdown();
        // interrupt the running task so a cooperative task can wind down instead of being
        // killed with the unsafe Thread#stop()
        this.interrupt();
    }

    public void cancel() {
        // interrupt the running task instead of using the unsafe Thread#stop(); the worker
        // observes the interrupt, finishes the current task and becomes available again
        this.interrupt();
    }

}
