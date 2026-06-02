package top.focess.scheduler;

import com.google.common.collect.Lists;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AScheduler implements Scheduler {

    private static final List<Scheduler> SCHEDULER_LIST = Lists.newCopyOnWriteArrayList();

    /**
     * The uncaught exception handler for the scheduler thread.
     */
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    protected final Queue<FocessTask> tasks = new PriorityQueue<>();

    protected volatile boolean shouldStop;

    private final String name;

    public AScheduler(final String name) {
        this.name = name;
        AScheduler.SCHEDULER_LIST.add(this);
    }

    @Override
    public void shutdown() {
        AScheduler.SCHEDULER_LIST.remove(this);
    }

    /**
     * Returns an unmodifiable list of all registered schedulers.
     *
     * @return all registered schedulers
     */
    @Contract(pure = true)
    public static @NotNull @UnmodifiableView List<Scheduler> getSchedulers() {
        return Collections.unmodifiableList(AScheduler.SCHEDULER_LIST);
    }

    @Override
    public void setUncaughtExceptionHandler(final Thread.UncaughtExceptionHandler handler) {
        this.uncaughtExceptionHandler = handler;
    }

    @Override
    @Nullable
    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return this.uncaughtExceptionHandler;
    }

    @Override
    public synchronized Task run(final Runnable runnable, final Duration delay) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, this);
        task.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(task);
        this.notify();
        return task;
    }

    @Override
    public synchronized Task run(final Runnable runnable, final Duration delay, final String name) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, this, name);
        task.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(task);
        this.notify();
        return task;
    }

    @Override
    public synchronized Task runTimer(final Runnable runnable, final Duration delay, final Duration period) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, period, this);
        task.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(task);
        this.notify();
        return task;
    }

    @Override
    public synchronized Task runTimer(final Runnable runnable, final Duration delay, final Duration period, final String name) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, period, this, name);
        task.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(task);
        this.notify();
        return task;
    }

    @Override
    public synchronized <V> Callback<V> submit(final Callable<V> callable, final Duration delay) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessCallback<V> callback = new FocessCallback<>(callable, this);
        callback.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(callback);
        this.notify();
        return callback;
    }

    @Override
    public synchronized  <V> Callback<V> submit(final Callable<V> callable, final Duration delay, final String name) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessCallback<V> callback = new FocessCallback<>(callable, this, name);
        callback.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(callback);
        this.notify();
        return callback;
    }

    @Override
    public synchronized  <V> Callback<V> submit(final Callable<V> callable, final Duration delay, final String name, final Function<ExecutionException, V> handler) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessCallback<V> callback = new FocessCallback<>(callable, this, name, handler);
        callback.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(callback);
        this.notify();
        return callback;
    }

    @Override
    public synchronized Task run(final Runnable runnable, final Duration delay, final String name, final Consumer<ExecutionException> handler) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, this, name, handler);
        task.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(task);
        this.notify();
        return task;
    }

    @Override
    public synchronized Task runTimer(final Runnable runnable, final Duration delay, final Duration period, final String name, final Consumer<ExecutionException> handler) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, period, this, name, handler);
        task.setTime(System.currentTimeMillis() + delay.toMillis());
        this.tasks.add(task);
        this.notify();
        return task;
    }

    public boolean isShutdown() {
        return this.shouldStop;
    }

    @Override
    public String toString() {
        return this.getName();
    }

    @Override
    public synchronized @UnmodifiableView List<Task> getRemainingTasks() {
        return this.tasks.stream().map(task -> (Task) task).toList();
    }

    @Override
    public synchronized void cancelAll() {
        for (final FocessTask task : this.tasks)
            task.cancel(true);
        this.tasks.clear();
    }

    @Override
    public String getName() {
        return this.name;
    }

    /**
     * Waits on this scheduler's monitor for at most {@code timeout} milliseconds.
     * A non-positive timeout is a no-op.
     *
     * @param timeout the maximum time to wait in milliseconds
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    protected synchronized void wait0(final long timeout) throws InterruptedException {
        if (timeout <= 0)
            return;
        this.wait(timeout);
    }

    /**
     * Best-effort cooperative interruption for a running task.
     * <p>
     * Implementations should interrupt only the thread that is currently executing
     * the given task.
     *
     * @param task the task whose executing thread should be interrupted
     */
    protected abstract void interruptTaskIfRunning(FocessTask task);
}
