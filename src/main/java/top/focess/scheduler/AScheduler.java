package top.focess.scheduler;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AScheduler implements Scheduler {

    private static final List<Scheduler> SCHEDULER_LIST = Lists.newArrayList();
    /**
     * The uncaught exception handler
     */
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    protected final Queue<ComparableTask> tasks = Queues.newPriorityQueue();

    protected volatile boolean shouldStop;

    private final String name;

    public AScheduler(final String name) {
        this.name = name;
        AScheduler.SCHEDULER_LIST.add(this);
    }

    @Override
    public void close() {
        AScheduler.SCHEDULER_LIST.remove(this);
    }

    /**
     * Get the schedulers as list
     * @return the schedulers as list
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
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), task));
        this.notify();
        return task;
    }

    @Override
    public synchronized Task run(final Runnable runnable, final Duration delay, final String name) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, this,name);
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
    public synchronized Task runTimer(final Runnable runnable, final Duration delay, final Duration period, final String name) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, period, this, name);
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
    public synchronized  <V> Callback<V> submit(final Callable<V> callable, final Duration delay, final String name) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessCallback<V> callback = new FocessCallback<>(callable, this, name);
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), callback));
        this.notify();
        return callback;
    }

    @Override
    public synchronized  <V> Callback<V> submit(final Callable<V> callable, final Duration delay, final String name, final Function<ExecutionException, V> handler) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessCallback<V> callback = new FocessCallback<>(callable, this, name, handler);
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), callback));
        this.notify();
        return callback;
    }

    @Override
    public synchronized Task run(final Runnable runnable, final Duration delay, final String name, final Consumer<ExecutionException> handler) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, this, name, handler);
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), task));
        this.notify();
        return task;
    }

    @Override
    public synchronized Task runTimer(final Runnable runnable, final Duration delay, final Duration period, final String name, final Consumer<ExecutionException> handler) {
        if (this.shouldStop)
            throw new SchedulerClosedException(this);
        final FocessTask task = new FocessTask(runnable, period, this, name, handler);
        this.tasks.add(new ComparableTask(System.currentTimeMillis() + delay.toMillis(), task));
        this.notify();
        return task;
    }

    public boolean isClosed() {
        return this.shouldStop;
    }

    @Override
    public String toString() {
        return this.getName();
    }

    @Override
    public synchronized @UnmodifiableView List<Task> getRemainingTasks() {
        return this.tasks.stream().map(ComparableTask::getTask).collect(Collectors.toUnmodifiableList());
    }

    @Override
    public synchronized void cancelAll() {
        this.tasks.clear();
    }

    @Override
    public String getName() {
        return this.name;
    }
}
