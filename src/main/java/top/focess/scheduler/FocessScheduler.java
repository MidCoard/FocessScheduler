package top.focess.scheduler;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class FocessScheduler extends AScheduler {
    private final SchedulerThread thread;

    /**
     * Creates a new {@code FocessScheduler} with a non-daemon scheduler thread.
     *
     * @param name the scheduler name
     */
    public FocessScheduler(final String name) {
        this(name, false);
    }

    /**
     * Creates a new {@code FocessScheduler}.
     * <p>
     * This is a single-threaded scheduler: all tasks are executed sequentially in time order.
     * If a task runs longer than expected, subsequent tasks are delayed accordingly.
     * A running task can be cooperatively cancelled via {@link Task#cancel(boolean) cancel(true)},
     * which interrupts the scheduler thread.
     *
     * @param name     the scheduler name
     * @param isDaemon {@code true} to create a daemon scheduler thread
     */
    public FocessScheduler(final String name, boolean isDaemon) {
        super(name);
        this.thread = new SchedulerThread(this.getName());
        this.thread.setDaemon(isDaemon);
        this.thread.start();
    }

    /**
     * Creates a new {@code FocessScheduler} whose name is auto-generated from the given prefix.
     *
     * @param prefix the prefix for the generated scheduler name
     * @return a new non-daemon {@code FocessScheduler}
     */
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
    protected synchronized void interruptTaskIfRunning(final FocessTask task) {
        if (this.thread.task == task)
            this.thread.interrupt();
    }

    private class SchedulerThread extends Thread {

        @Nullable
        private FocessTask task;

        public SchedulerThread(final String name) {
            super(name);
            this.setUncaughtExceptionHandler((t, e) -> {
                FocessScheduler.this.shutdown();
                if (this.task != null) {
                    this.task.setException(new ExecutionException(e));
                    this.task.endRun();
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
                    // Clear any leftover interrupt from a cancelled task so it does not spin the
                    // next wait(). Shutdown does not rely on this flag (it uses shouldStop + notify),
                    // so clearing here cannot swallow a shutdown signal.
                    Thread.interrupted();
                    synchronized (FocessScheduler.this) {
                        if (FocessScheduler.this.shouldStop)
                            break;
                        if (FocessScheduler.this.tasks.isEmpty())
                            FocessScheduler.this.wait();
                        this.task = FocessScheduler.this.tasks.poll();
                        // if task is null, the scheduler may be stopped, continue to loopback and check shouldStop
                        if (this.task != null && !this.task.isCancelled()) {
                            final long now = System.currentTimeMillis();
                            FocessScheduler.this.wait0(this.task.getTime() - now);
                            if (this.task.getTime() > System.currentTimeMillis()) {
                                FocessScheduler.this.tasks.add(this.task);
                                continue;
                            }
                            final FocessTask task = FocessScheduler.this.tasks.peek();
                            // in fact, here is no need to compare the time of the next task, but we need to make sure the order
                            // of the tasks execution meets the user time order
                            if (task != null && task.getTime() < this.task.getTime()) {
                                FocessScheduler.this.tasks.add(this.task);
                                continue;
                            }
                        } else continue;
                    }
                    synchronized (this.task) {
                        if (this.task.isCancelled())
                            continue;
                        this.task.startRun();
                    }
                    try {
                        this.task.run();
                    } catch (final ExecutionException e) {
                        this.task.setException(e);
                    } finally {
                        // consume any interrupt raised by cancel(true) so it does not leak into
                        // the scheduler thread's next wait() and spin the run loop
                        Thread.interrupted();
                    }
                    this.task.endRun();
                    if (this.task.isPeriod() && !this.task.isCancelled()) {
                        this.task.clear();
                        synchronized (FocessScheduler.this) {
                            this.task.setTime(System.currentTimeMillis() + this.task.getPeriod().toMillis());
                            FocessScheduler.this.tasks.add(this.task);
                        }
                    }
                    synchronized (FocessScheduler.this) {
                        this.task = null;
                    }
                } catch (final Exception e) {
                    e.printStackTrace(System.err);
                    shutdown();
                }
            }
        }
    }
}
