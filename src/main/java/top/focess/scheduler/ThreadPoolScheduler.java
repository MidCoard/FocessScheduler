package top.focess.scheduler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ThreadPoolScheduler extends AScheduler {

    final Map<ITask, ThreadPoolSchedulerThread> taskThreadMap = Maps.newConcurrentMap(); // can be opt to Non-Concurrent, use synchronized
    private final List<ThreadPoolSchedulerThread> threads = Lists.newCopyOnWriteArrayList();
    private final boolean immediate;
    private int currentThread;

    protected final Object AVAILABLE_THREAD_LOCK = new Object();

    /**
     * The uncaught exception handler for worker threads.
     */
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;


    /**
     * Creates a new {@code ThreadPoolScheduler}.
     * <p>
     * This is a multi-threaded scheduler: tasks are dispatched to a pool of worker threads
     * and execute in parallel. A running task can be cooperatively cancelled via
     * {@link Task#cancel(boolean) cancel(true)}, which interrupts the worker thread
     * executing it.
     *
     * @param poolSize  the number of worker threads
     * @param immediate if {@code true}, the pool expands dynamically when all workers are busy;
     *                  if {@code false}, tasks wait for an available worker
     * @param name      the scheduler name
     * @param isDaemon  {@code true} to create daemon worker threads
     */
    public ThreadPoolScheduler(final int poolSize, final boolean immediate, final String name,final boolean isDaemon) {
        super(name);
        this.immediate = immediate;
        for (int i = 0; i < poolSize; i++)
            this.threads.add(new ThreadPoolSchedulerThread(this, this.getName() + "-" + i));
        Thread thread = new SchedulerThread(this.getName());
        thread.setDaemon(isDaemon);
        thread.start();
    }

    /**
     * Creates a new {@code ThreadPoolScheduler} with non-daemon worker threads.
     *
     * @param poolSize  the number of worker threads
     * @param immediate if {@code true}, the pool expands dynamically when all workers are busy
     * @param name      the scheduler name
     */
    public ThreadPoolScheduler(final int poolSize, final boolean immediate, final String name) {
        this(poolSize, immediate, name, false);
    }

    /**
     * Creates a new {@code ThreadPoolScheduler} with non-immediate, non-daemon defaults
     * and an auto-generated name.
     *
     * @param prefix   the prefix for the generated scheduler name
     * @param poolSize the number of worker threads
     */
    public ThreadPoolScheduler(final String prefix, final int poolSize) {
        this(poolSize, false, prefix + "-ThreadPoolScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Override
    public synchronized void shutdown() {
        if (this.shouldStop)
            return;
        super.shutdown();
        this.shouldStop = true;
        // Wake the SchedulerThread first so it observes shouldStop on its next loop iteration
        synchronized (this.AVAILABLE_THREAD_LOCK) {
            this.AVAILABLE_THREAD_LOCK.notifyAll();
        }
        this.cancelAll();
        for (final ThreadPoolSchedulerThread thread : this.threads)
            thread.shutdown();
        this.notify();
    }

    @Override
    public synchronized void shutdownNow() {
        if (this.shouldStop)
            return;
        super.shutdown();
        this.shouldStop = true;
        synchronized (this.AVAILABLE_THREAD_LOCK) {
            this.AVAILABLE_THREAD_LOCK.notifyAll();
        }
        this.cancelAll();
        for (final ThreadPoolSchedulerThread thread : this.threads)
            thread.shutdownNow();
        this.notify();
    }

    synchronized void rerun(final FocessTask task) {
        if (this.shouldStop) {
            // period task cannot re-arm itself during shutdown — mark cancelled so observers see a terminal state
            task.cancel(false);
            return;
        }
        task.clear();
        task.setTime(System.currentTimeMillis() + task.getPeriod().toMillis());
        this.tasks.add(task);
        this.notify();
    }

    @Nullable
    public Thread.UncaughtExceptionHandler getThreadUncaughtExceptionHandler() {
        return this.uncaughtExceptionHandler;
    }

    public void setThreadUncaughtExceptionHandler(final Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

    @Override
    protected void interruptTaskIfRunning(final FocessTask task) {
        final ThreadPoolSchedulerThread thread = this.taskThreadMap.get(task);
        if (thread != null)
            thread.interrupt();
    }

    private class SchedulerThread extends Thread {

        public SchedulerThread(final String name) {
            super(name);
            this.setUncaughtExceptionHandler((t, e) -> {
                ThreadPoolScheduler.this.shutdown();
                if (ThreadPoolScheduler.this.getUncaughtExceptionHandler() != null)
                    ThreadPoolScheduler.this.getUncaughtExceptionHandler().uncaughtException(t, e);
            });
        }

        @Nullable
        private ThreadPoolSchedulerThread getAvailableThread() {
            final int size = ThreadPoolScheduler.this.threads.size();
            for (int i = 0; i < size; i++) {
                final int next = (ThreadPoolScheduler.this.currentThread + 1 + i) % size;
                if (ThreadPoolScheduler.this.threads.get(next).isAvailable()) {
                    ThreadPoolScheduler.this.currentThread = next;
                    return ThreadPoolScheduler.this.threads.get(next);
                }
            }
            if (ThreadPoolScheduler.this.immediate) {
                final ThreadPoolSchedulerThread thread = new ThreadPoolSchedulerThread(ThreadPoolScheduler.this, ThreadPoolScheduler.this.getName() + "-" + ThreadPoolScheduler.this.threads.size());
                ThreadPoolScheduler.this.threads.add(thread);
                return thread;
            }
            return null;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    final FocessTask task;
                    synchronized (ThreadPoolScheduler.this) {
                        if (ThreadPoolScheduler.this.shouldStop)
                            break;
                        if (ThreadPoolScheduler.this.tasks.isEmpty())
                            ThreadPoolScheduler.this.wait();
                        task = ThreadPoolScheduler.this.tasks.poll();
                        // if task is null, means the scheduler is closed
                        if (task != null && !task.isCancelled()) {
                            final long now = System.currentTimeMillis();
                            ThreadPoolScheduler.this.wait0(task.getTime() - now);
                            if (task.getTime() > System.currentTimeMillis()) {
                                ThreadPoolScheduler.this.tasks.add(task);
                                continue;
                            }
                            final FocessTask peek = ThreadPoolScheduler.this.tasks.peek();
                            if (peek != null && peek.getTime() < task.getTime()) {
                                ThreadPoolScheduler.this.tasks.add(task);
                                continue;
                            }
                        } else continue;
                    }

                    final ThreadPoolSchedulerThread thread;
                    synchronized (ThreadPoolScheduler.this.AVAILABLE_THREAD_LOCK) {
                        thread = this.getAvailableThread();
                        if (thread == null) {

                            // the thread is null, so the task will be executed later
                            synchronized (ThreadPoolScheduler.this) {
                                ThreadPoolScheduler.this.tasks.add(task);
                            }
                            // the task will be executed later, but the thread is still not available,
                            // this cycle will run many times until the thread is available,
                            // so we use the AVAILABLE_THREAD_LOCK to wait the thread is available
                            ThreadPoolScheduler.this.AVAILABLE_THREAD_LOCK.wait();
                            // because there may be many new tasks in the queue and a new state for this task,
                            // we back to the beginning of the cycle to check the new state of this task
                            continue;
                        }
                    }
                    synchronized (task) {
                        if (task.isCancelled())
                            continue;
                        ThreadPoolScheduler.this.taskThreadMap.put(task, thread);
                        thread.startTask(task);
                    }
                } catch (final Exception e) {
                    e.printStackTrace(System.err);
                    shutdown();
                }
            }
        }
    }
}
