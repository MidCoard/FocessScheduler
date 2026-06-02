package top.focess.scheduler;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class FocessScheduler extends AScheduler {
    private final Thread thread;

    /**
     * New a FocessScheduler with some default configuration (not daemon).
     *
     * @param name the plugin
     */
    public FocessScheduler(final String name) {
        this(name, false);
    }

    /**
     * New a FocessScheduler, the scheduler will run all tasks in time order.
     * For example, if the finish-time of the last task is after the start-time of the next task, the next task will only be executed after the last task is finished.
     * As a result, the task running in this scheduler cannot be cancelled if it is already running.
     *
     * @param name the plugin
     * @param isDaemon whether the scheduler is a daemon thread
     */
    public FocessScheduler(final String name, boolean isDaemon) {
        super(name);
        this.thread = new SchedulerThread(this.getName());
        this.thread.setDaemon(isDaemon);
        this.thread.start();
    }

    public static FocessScheduler newPrefixFocessScheduler(final String prefix) {
        return new FocessScheduler(prefix + "-FocessScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public synchronized void shutdown() {
        super.shutdown();
        this.shouldStop = true;
        this.cancelAll();
        this.notify();
    }

    @Override
    public synchronized void shutdownNow() {
        this.shutdown();
        // interrupt the scheduler thread so that a blocking task (or wait) wakes up and
        // the run loop can observe shouldStop and terminate cooperatively
        this.thread.interrupt();
    }

    @Override
    public void cancel(final ITask task) {
        // FocessScheduler runs tasks one-at-a-time on its single scheduler thread, so cancelling
        // the running task means interrupting that thread. The task must cooperate by reacting to
        // the interrupt (e.g. letting InterruptedException propagate or returning); the scheduler
        // thread clears any leftover interrupt before picking up the next task.
        this.thread.interrupt();
    }

    private synchronized void wait0(long timeout) throws InterruptedException {
        if (timeout <= 0)
            return;
        this.wait(timeout);
    }

    private class SchedulerThread extends Thread {

        @Nullable
        private ComparableTask task;

        public SchedulerThread(final String name) {
            super(name);
            this.setUncaughtExceptionHandler((t, e) -> {
                FocessScheduler.this.shutdown();
                if (this.task != null) {
                    this.task.getTask().setException(new ExecutionException(e));
                    this.task.getTask().endRun();
                }
                this.task = null;
                if (FocessScheduler.this.getUncaughtExceptionHandler() != null)
                    FocessScheduler.this.getUncaughtExceptionHandler().uncaughtException(t, e);
            });
        }

        @Override
        public void run() {
            while (true) {
                try {
                    // clear any leftover interrupt from a cancelled task before dispatching again
                    Thread.interrupted();
                    synchronized (FocessScheduler.this) {
                        if (FocessScheduler.this.shouldStop)
                            break;
                        if (FocessScheduler.this.tasks.isEmpty())
                            FocessScheduler.this.wait();
                        this.task = FocessScheduler.this.tasks.poll();
                        // if task is null, means the scheduler is closed
                        if (this.task != null && !this.task.isCancelled()) {
                            FocessScheduler.this.wait0(this.task.getTime() - System.currentTimeMillis());
                            if (this.task.getTime() > System.currentTimeMillis()) {
                                FocessScheduler.this.tasks.add(this.task);
                                continue;
                            }
                            final ComparableTask task = FocessScheduler.this.tasks.peek();
                            if (task != null && task.getTime() < this.task.getTime()) {
                                FocessScheduler.this.tasks.add(this.task);
                                continue;
                            }
                        } else continue;
                    }
                    synchronized (this.task.getTask()) {
                        if (this.task.isCancelled())
                            continue;
                        this.task.getTask().startRun();
                    }
                    try {
                        this.task.getTask().run();
                    } catch (final Throwable e) {
                        this.task.getTask().setException(new ExecutionException(e));
                    } finally {
                        // consume any interrupt raised by cancel(true) so it does not leak into
                        // the scheduler thread's next wait() and spin the run loop
                        Thread.interrupted();
                    }
                    this.task.getTask().endRun();
                    if (this.task.getTask().isPeriod() && !this.task.isCancelled()) {
                        // equivalent to the AScheduler#runTimer method (first check whether the scheduler is closed)
                        if (FocessScheduler.this.shouldStop) {
                            this.task = null;
                            return;
                        }
                        this.task.getTask().clear();
                        synchronized (FocessScheduler.this) {
                            FocessScheduler.this.tasks.add(new ComparableTask(System.currentTimeMillis() + this.task.getTask().getPeriod().toMillis(), this.task.getTask()));
                        }
                    }
                    this.task = null;
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
