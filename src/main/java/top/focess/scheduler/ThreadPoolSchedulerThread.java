package top.focess.scheduler;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutionException;

public class ThreadPoolSchedulerThread extends Thread {
    private final ThreadPoolScheduler scheduler;
    private final String name;

    private boolean isAvailable = true;
    @Nullable
    private ITask task;
    private boolean shouldStop;

    public ThreadPoolSchedulerThread(final ThreadPoolScheduler scheduler, final String name) {
        super(name);
        this.scheduler = scheduler;
        this.name = name;
        this.setUncaughtExceptionHandler((t, e) -> {
            this.close();
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
            if (!this.scheduler.isClosed())
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
                    if (this.isAvailable)
                        this.wait();
                }
                if (this.task != null) {
                    this.task.startRun();
                    try {
                        this.task.run();
                    } catch (final Exception e) {
                        this.task.setException(new ExecutionException(e));
                    }
                    this.task.endRun();
                    this.scheduler.taskThreadMap.remove(this.task);
                    if (this.task.isPeriod() && !this.task.isCancelled())
                        this.scheduler.rerun(this.task);
                    this.task = null;
                }
                this.isAvailable = true;
            } catch (final Exception e) {
                if (this.scheduler.getThreadCatchExceptionHandler() != null)
                    this.scheduler.getThreadCatchExceptionHandler().catchException(this,e);
            }
        }
    }

    public boolean isAvailable() {
        return this.isAvailable;
    }

    public synchronized void startTask(final ITask task) {
        this.isAvailable = false;
        this.task = task;
        this.notify();
    }

    public synchronized void close() {
        this.shouldStop = true;
        this.isAvailable = false;
        this.notify();
    }

    public void closeNow() {
        this.close();
        this.stop();
    }

    public void cancel() {
        this.stop();
        // no need for recreate, because the stop method will throw an uncaught exception
        // and recreate the thread will be called in the uncaught exception handler
    }

}
