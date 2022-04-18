package top.focess.scheduler;

import com.google.common.collect.Queues;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class FocessScheduler extends AScheduler {

    private final Queue<ComparableTask> tasks = Queues.newPriorityBlockingQueue();
    private final String name;
    private final Thread thread;

    private volatile boolean shouldStop;

    public FocessScheduler(final String name) {
        this.name = name;
        this.thread = new SchedulerThread(this.getName());
        this.thread.start();
    }

    public static FocessScheduler newPrefixFocessScheduler(String prefix) {
        return new FocessScheduler(prefix + "-FocessScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public synchronized Task run(final Runnable runnable, final Duration delay) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, this);
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), task));
        this.notify();
        return task;
    }

    @Override
    public synchronized Task runTimer(final Runnable runnable, final Duration delay, final Duration period) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, period, this);
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), task));
        this.notify();
        return task;
    }

    @Override
    public synchronized <V> Callback<V> submit(final Callable<V> callable, final Duration delay) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessCallback<V> callback = new FocessCallback<>(callable, this);
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), callback));
        this.notify();
        return callback;
    }

    @Override
    public void cancelAll() {
        this.tasks.clear();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public synchronized void close() {
        super.close();
        this.shouldStop = true;
        this.cancelAll();
        this.notify();
    }

    @Override
    public boolean isClosed() {
        return this.shouldStop;
    }

    @Override
    public synchronized void closeNow() {
        this.close();
        this.thread.stop();
    }

    @Override
    public String toString() {
        return this.getName();
    }

    private class SchedulerThread extends Thread {

        @Nullable
        private ComparableTask task;

        public SchedulerThread(final String name) {
            super(name);
            this.setUncaughtExceptionHandler((t, e) -> {
                FocessScheduler.this.close();
                if (this.task != null) {
                    this.task.getTask().setException(new ExecutionException(e));
                    this.task.getTask().endRun();
                }
                if (FocessScheduler.this.getUncaughtExceptionHandler() != null)
                    FocessScheduler.this.getUncaughtExceptionHandler().uncaughtException(t, e);
            });
        }

        @Override
        public void run() {
            while (true) {
                try {
                    synchronized (FocessScheduler.this) {
                        if (FocessScheduler.this.shouldStop)
                            break;
                        if (FocessScheduler.this.tasks.isEmpty())
                            FocessScheduler.this.wait();
                    }
                    this.task = FocessScheduler.this.tasks.peek();
                    if (this.task != null) {
                        synchronized (this.task.getTask()) {
                            if (this.task.isCancelled()) {
                                FocessScheduler.this.tasks.poll();
                                continue;
                            }
                            if (this.task.getTime() <= System.currentTimeMillis()) {
                                FocessScheduler.this.tasks.poll();
                                this.task.getTask().startRun();
                            }
                        }
                        if (this.task.getTask().isRunning()) {
                            try {
                                this.task.getTask().run();
                            } catch (final Exception e) {
                                this.task.getTask().setException(new ExecutionException(e));
                            }
                            this.task.getTask().endRun();
                            if (this.task.getTask().isPeriod())
                                FocessScheduler.this.tasks.add(new ComparableTask(System.currentTimeMillis() + this.task.getTask().getPeriod().toMillis(), this.task.getTask()));
                        }
                    }
                } catch (final Exception e) {
                    if (FocessScheduler.this.getCatchExceptionHandler() != null)
                        FocessScheduler.this.getCatchExceptionHandler().catchException(this,e);
                }
            }
        }
    }
}
