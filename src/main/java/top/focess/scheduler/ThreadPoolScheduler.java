package top.focess.scheduler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;

public class ThreadPoolScheduler extends AScheduler {

    final Map<ITask, ThreadPoolSchedulerThread> taskThreadMap = Maps.newConcurrentMap();
    private final Queue<ComparableTask> tasks = Queues.newPriorityBlockingQueue();
    private final List<ThreadPoolSchedulerThread> threads = Lists.newArrayList();
    private final boolean immediate;
    private final String name;
    private volatile boolean shouldStop;
    private int currentThread;

    /**
     * The uncaught exception handler
     */
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    /**
     * The catch exception handler
     */
    private CatchExceptionHandler catchExceptionHandler;

    /**
     * New a ThreadPoolScheduler, the scheduler can run tasks in parallel.
     * The next task will be executed immediately if the immediate is true, otherwise the next task will be executed when there is an available thread.
     * As a result, the task running in this scheduler can be cancelled if it is already running.
     *
     * @param poolSize  the thread pool size
     * @param immediate true if the scheduler should run immediately, false otherwise
     * @param name      the scheduler name
     */
    public ThreadPoolScheduler(final int poolSize, final boolean immediate, final String name) {
        this.name = name;
        for (int i = 0; i < poolSize; i++)
            this.threads.add(new ThreadPoolSchedulerThread(this, this.getName() + "-" + i));
        new SchedulerThread(this.getName()).start();
        this.immediate = immediate;
    }

    public ThreadPoolScheduler(final String prefix, final int poolSize) {
        this(poolSize, false, prefix + "-ThreadPoolScheduler-" + UUID.randomUUID().toString().substring(0, 8));
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
        for (final ThreadPoolSchedulerThread thread : this.threads)
            thread.close();
        this.notify();
    }

    @Override
    public boolean isClosed() {
        return this.shouldStop;
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
                break;
            }
    }

    public void rerun(final ITask task) {
        if (this.shouldStop)
            return;
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + task.getPeriod().toMillis(), task));
    }

    @Override
    public String toString() {
        return this.getName();
    }

    @Nullable
    @Override
    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler;
    }

    @Override
    public void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

    @Nullable
    @Override
    public CatchExceptionHandler getCatchExceptionHandler() {
        return catchExceptionHandler;
    }

    @Override
    public void setCatchExceptionHandler(CatchExceptionHandler catchExceptionHandler) {
        this.catchExceptionHandler = catchExceptionHandler;
    }

    private class SchedulerThread extends Thread {

        public SchedulerThread(final String name) {
            super(name);
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
                    synchronized (ThreadPoolScheduler.this) {
                        if (ThreadPoolScheduler.this.shouldStop)
                            break;
                        if (ThreadPoolScheduler.this.tasks.isEmpty())
                            ThreadPoolScheduler.this.wait();
                    }
                    final ComparableTask task = ThreadPoolScheduler.this.tasks.peek();
                    if (task != null)
                        synchronized (task.getTask()) {
                            if (task.isCancelled()) {
                                ThreadPoolScheduler.this.tasks.poll();
                                continue;
                            }
                            if (task.getTime() <= System.currentTimeMillis()) {
                                final ThreadPoolSchedulerThread thread = this.getAvailableThread();
                                if (thread == null)
                                    continue;
                                ThreadPoolScheduler.this.tasks.poll();
                                ThreadPoolScheduler.this.taskThreadMap.put(task.getTask(), thread);
                                thread.startTask(task.getTask());
                            }
                        }
                } catch (final Exception e) {
                    if (ThreadPoolScheduler.this.getCatchExceptionHandler() != null)
                        ThreadPoolScheduler.this.getCatchExceptionHandler().catchException(this,e);
                }
            }
        }
    }
}
