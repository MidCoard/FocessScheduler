package top.focess.scheduler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class FocessTask implements ITask {

    static final Scheduler DEFAULT_SCHEDULER = new ThreadPoolScheduler(7, false, "FocessTask",true);
    private final Runnable runnable;
    private final Scheduler scheduler;
    private final String name;
    protected boolean isRunning;
    protected boolean isFinished;

    protected ExecutionException exception;
    private Duration period;
    private boolean isPeriod;
    private ComparableTask nativeTask;

    private static final Map<Task,Boolean> TASK_SET = new WeakHashMap<>();
    private Consumer<ExecutionException> handler;

    /**
     * Get all the tasks which are not gc yet
     *
     * Note: this is only for debug.
     *
     * @return all the tasks which are not gc yet
     */
    public static Set<Task> getTasks() {
        return Collections.unmodifiableSet(TASK_SET.keySet());
    }


    FocessTask(@Nullable final Runnable runnable, @NotNull final Scheduler scheduler, final String name) {
        this.runnable = runnable;
        this.scheduler = scheduler;
        this.name = scheduler.getName() + "-" + name;
        TASK_SET.put(this,true);
    }

    FocessTask(@Nullable final Runnable runnable, @NotNull final Scheduler scheduler) {
        this(runnable,scheduler, UUID.randomUUID().toString().substring(0, 8));
    }

    FocessTask(final Runnable runnable, final Scheduler scheduler, final String name, final Consumer<ExecutionException> handler) {
        this(runnable, scheduler, name);
        this.handler = handler;
    }

    FocessTask(final Runnable runnable, final Duration period, final Scheduler scheduler) {
        this(runnable, scheduler);
        this.isPeriod = true;
        this.period = period;
    }

    FocessTask(final Runnable runnable, final Duration period, final Scheduler scheduler, final String name) {
        this(runnable, scheduler, name);
        this.isPeriod = true;
        this.period = period;
    }

    @Override
    public void setNativeTask(final ComparableTask nativeTask) {
        this.nativeTask = nativeTask;
    }

    @Override
    public synchronized void clear() {
        this.isFinished = false;
        this.isRunning = false;
    }

    @Override
    public synchronized void startRun() {
        this.isRunning = true;
    }

    @Override
    public synchronized void endRun() {
        this.isRunning = false;
        this.isFinished = true;
        this.notifyAll();
    }

    @Override
    public synchronized void setException(final ExecutionException e) {
        if (this.handler != null)
            this.handler.accept(e);
        else this.exception = e;
    }

    @Override
    public boolean isSingleThread() {
        return this.scheduler instanceof ThreadPoolScheduler;
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
        return this.nativeTask.cancel(mayInterruptIfRunning);
    }

    @Override
    public synchronized boolean isRunning() {
        return this.isRunning;
    }

    @Override
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public boolean isPeriod() {
        return this.isPeriod;
    }

    @Override
    public synchronized boolean isFinished() {
        return !this.isPeriod && this.isFinished;
    }

    @Override
    public synchronized boolean isCancelled() {
        return this.nativeTask.isCancelled();
    }

    @Override
    public synchronized void join() throws InterruptedException, CancellationException, ExecutionException {
        if (this.exception != null)
            throw this.exception;
        if (this.isFinished())
            return;
        if (this.isCancelled())
            throw new CancellationException("Task is cancelled");
        this.wait();
        if (this.isCancelled())
            throw new CancellationException("Task is cancelled");
        if (this.exception != null)
            throw this.exception;
    }

    @Override
    public synchronized void join(final long timeout, final TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException {
        if (this.exception != null)
            throw this.exception;
        if (this.isFinished())
            return;
        if (this.isCancelled())
            throw new CancellationException("Task is cancelled");
        final AtomicBoolean out = new AtomicBoolean(false);
        final Task task = DEFAULT_SCHEDULER.run(() -> {
            out.set(true);
            synchronized (FocessTask.this) {
                FocessTask.this.notifyAll();
            }
        }, Duration.ofMillis(unit.toMillis(timeout)));
        while (true) {
            this.wait();
            if (this.isCancelled() || this.isFinished()) {
                // because of this task is finished or cancelled, even if the cancellation task cannot be cancelled (means it has already run), this task will be finished.
                // So ignore its return value.
                task.cancel();
                break;
            }
            if (out.get())
                throw new TimeoutException();
        }
        this.join();
    }

    @Override
    public synchronized void setExceptionHandler(Consumer<ExecutionException> handler) {
        this.handler = handler;
    }

    @Override
    public void run() throws ExecutionException {
        this.runnable.run();
    }

    @Override
    public Duration getPeriod() {
        return this.period;
    }

    @Override
    public String toString() {
        return this.getName();
    }
}
