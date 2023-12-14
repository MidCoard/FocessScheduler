package top.focess.scheduler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.jetbrains.annotations.Nullable;
import top.focess.scheduler.exceptions.TaskNotFoundError;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ThreadPoolScheduler extends AScheduler {

    final Map<ITask, ThreadPoolSchedulerThread> taskThreadMap = Maps.newConcurrentMap(); // can be opt to Non-Concurrent, use synchronized
    private final List<ThreadPoolSchedulerThread> threads = Lists.newArrayList();
    private final boolean immediate;
    private final boolean isDaemon;
    private int currentThread;

    protected final Object AVAILABLE_THREAD_LOCK = new Object();

    /**
     * The uncaught exception handler
     */
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;


    /**
     * New a ThreadPoolScheduler, the scheduler can run tasks in parallel.
     * The next task will be executed immediately if the immediate is true, otherwise the next task will be executed when there is an available thread.
     * As a result, the task running in this scheduler can be cancelled if it is already running.
     *
     * @param poolSize  the thread pool size
     * @param immediate true if the scheduler should run immediately, false otherwise
     * @param name      the scheduler name
     * @param isDaemon  true if the scheduler is a daemon thread, false otherwise
     */
    public ThreadPoolScheduler(final int poolSize, final boolean immediate, final String name,final boolean isDaemon) {
        super(name);
        for (int i = 0; i < poolSize; i++)
            this.threads.add(new ThreadPoolSchedulerThread(this, this.getName() + "-" + i));
        Thread thread = new SchedulerThread(this.getName());
        thread.setDaemon(isDaemon);
        thread.start();
        this.immediate = immediate;
        this.isDaemon = isDaemon;
    }

    /**
     * New a ThreadPoolScheduler with some default configuration (not daemon).
     *
     * @param poolSize  the thread pool size
     * @param immediate true if the scheduler should run immediately, false otherwise
     * @param name      the scheduler name
     */
    public ThreadPoolScheduler(final int poolSize, final boolean immediate, final String name) {
        this(poolSize, immediate, name, false);
    }

    /**
     * New a ThreadPoolScheduler with some default configuration (not immediate, not daemon).
     * @param prefix the prefix of the scheduler name
     * @param poolSize the thread pool size
     */
    public ThreadPoolScheduler(final String prefix, final int poolSize) {
        this(poolSize, false, prefix + "-ThreadPoolScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }
    @Override
    public synchronized void close() {
        super.close();
        this.shouldStop = true;
        this.cancelAll();
        for (final ThreadPoolSchedulerThread thread : this.threads)
            thread.close();
        this.notify();
    }

    @Override
    public void closeNow() {
        super.close();
        this.shouldStop = true;
        this.cancelAll();
        for (final ThreadPoolSchedulerThread thread : this.threads)
            thread.closeNow();
        this.notify();
    }

    public void cancel(final ITask task) {
        if (this.taskThreadMap.containsKey(task)) {
            this.taskThreadMap.get(task).cancel();
            this.taskThreadMap.remove(task);
        } else throw new TaskNotFoundError(task);
    }

    public void recreate(final String name) {
        for (int i = 0; i < this.threads.size(); i++)
            if (this.threads.get(i).getName().equals(name)) {
                this.threads.set(i, new ThreadPoolSchedulerThread(this, name));
                synchronized (this.AVAILABLE_THREAD_LOCK) {
                    this.AVAILABLE_THREAD_LOCK.notify();
                }
                break;
            }
    }

    public synchronized void rerun(final ITask task) {
        if (this.shouldStop)
            return;
        task.clear();
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + task.getPeriod().toMillis(), task));
        this.notify();
    }

    @Nullable
    public Thread.UncaughtExceptionHandler getThreadUncaughtExceptionHandler() {
        return this.uncaughtExceptionHandler;
    }

    public void setThreadUncaughtExceptionHandler(final Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

    private void wait0(long timeout) throws InterruptedException {
        if (timeout <= 0)
            return;
        this.wait(timeout);
    }

    private class SchedulerThread extends Thread {

        public SchedulerThread(final String name) {
            super(name);
            this.setDaemon(isDaemon);
            this.setUncaughtExceptionHandler((t, e) -> {
                ThreadPoolScheduler.this.close();
                if (ThreadPoolScheduler.this.getUncaughtExceptionHandler() != null)
                    ThreadPoolScheduler.this.getUncaughtExceptionHandler().uncaughtException(t, e);
            });
        }

        @Nullable
        private ThreadPoolSchedulerThread getAvailableThread() {
            for (int i = 1; i <= ThreadPoolScheduler.this.threads.size(); i++) {
                final int next = (ThreadPoolScheduler.this.currentThread + i) % ThreadPoolScheduler.this.threads.size();
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
                    final ComparableTask task;
                    synchronized (ThreadPoolScheduler.this) {
                        if (ThreadPoolScheduler.this.shouldStop)
                            break;
                        if (ThreadPoolScheduler.this.tasks.isEmpty())
                            ThreadPoolScheduler.this.wait();
                        task = ThreadPoolScheduler.this.tasks.poll();
                        // if task is null, means the scheduler is closed
                        if (task!= null && !task.isCancelled()) {
                            ThreadPoolScheduler.this.wait0(task.getTime() - System.currentTimeMillis());
                            final ComparableTask peek = ThreadPoolScheduler.this.tasks.peek();
                            if (peek != null && peek.getTime() < task.getTime()) {
                                ThreadPoolScheduler.this.tasks.add(task);
                                continue;
                            }
                        } else continue;
                    }

                    if (task.isCancelled())
                        continue;

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
                    ThreadPoolScheduler.this.taskThreadMap.put(task.getTask(), thread);
                    thread.startTask(task.getTask());
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
