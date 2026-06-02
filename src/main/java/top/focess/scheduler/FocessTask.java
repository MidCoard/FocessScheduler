package top.focess.scheduler;

import com.google.common.collect.Lists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The default implementation of {@link ITask}.
 * <p>
 * Manages task lifecycle through a state machine:
 * {@code PENDING → RUNNING → FINISHED} (or {@code CANCELLED} at any point).
 * Period tasks reset to {@code PENDING} after each execution.
 * <p>
 * Tasks are ordered by their scheduled execution time via {@link Comparable}.
 */
public class FocessTask implements ITask, Comparable<FocessTask> {

    /**
     * The lifecycle states of a task.
     */
    private enum TaskState {
        /** The task is waiting to be executed. */
        PENDING,
        /** The task is currently executing. */
        RUNNING,
        /** The task has completed execution. */
        FINISHED,
        /** The task was cancelled before or during execution. */
        CANCELLED
    }

    private final Runnable runnable;
    private final AScheduler scheduler;
    private final String name;
    private long time;
    private TaskState state = TaskState.PENDING;

    protected ExecutionException exception;
    private Duration period;
    private Consumer<ExecutionException> handler;
    private List<TaskPool> taskPools;

    private final Object TASK_POOL_LOCK =  new Object();

    FocessTask(@Nullable final Runnable runnable, @NotNull final AScheduler scheduler, final String name) {
        this.runnable = runnable;
        this.scheduler = scheduler;
        this.name = scheduler.getName() + "-" + name;
    }

    FocessTask(@Nullable final Runnable runnable, @NotNull final AScheduler scheduler) {
        this(runnable,scheduler, UUID.randomUUID().toString().substring(0, 8));
    }

    FocessTask(final Runnable runnable, final AScheduler scheduler, final String name, final Consumer<ExecutionException> handler) {
        this(runnable, scheduler, name);
        this.handler = handler;
    }

    FocessTask(final Runnable runnable, final Duration period, final AScheduler scheduler) {
        this(runnable, scheduler);
        this.period = period;
    }

    FocessTask(final Runnable runnable, final Duration period, final AScheduler scheduler, final String name) {
        this(runnable, period, scheduler, name, null);
    }

    FocessTask(final Runnable runnable, final Duration period, final AScheduler scheduler, final String name, final Consumer<ExecutionException> handler) {
        this(runnable, scheduler, name);
        this.period = period;
        this.handler = handler;
    }

    @Override
    public synchronized void clear() {
        // Only periodic tasks are reusable; cancellation and terminal finish stay terminal.
        if (this.period != null && this.state == TaskState.FINISHED) {
            this.state = TaskState.PENDING;
        }
        this.exception = null;
    }

    void setTime(final long time) {
        this.time = time;
    }

    long getTime() {
        return time;
    }

    @Override
    public int compareTo(@NotNull final FocessTask o) {
        return Long.compare(this.time, o.time);
    }

    @Override
    public synchronized void startRun() {
        this.state = TaskState.RUNNING;
    }

    @Override
    public void endRun() {
        synchronized (this) {
            if (this.state == TaskState.RUNNING) {
                this.state = TaskState.FINISHED;
            }
            this.notifyAll();
        }
        synchronized (TASK_POOL_LOCK) {
            if (this.taskPools != null) {
                for (final TaskPool taskPool : this.taskPools) {
                    taskPool.removeTask(this);
                    taskPool.finishTask(this);
                }
            }
        }
    }

    @Override
    public synchronized void setException(final ExecutionException e) {
        if (this.handler != null)
            try {
                this.handler.accept(e);
            } catch (final Throwable ignored) {
                this.exception = e;
            }
        else this.exception = e;
    }

    @Override
    public void addTaskPool(final TaskPool taskPool) {
        synchronized (TASK_POOL_LOCK) {
            if (this.taskPools == null)
                this.taskPools = Lists.newCopyOnWriteArrayList();
            this.taskPools.add(taskPool);
        }
    }

    @Override
    public void removeTaskPool(final TaskPool taskPool) {
        synchronized (TASK_POOL_LOCK) {
            if (this.taskPools != null)
                this.taskPools.remove(taskPool);
        }
    }

    @Override
    public synchronized boolean cancel(final boolean mayInterruptIfRunning) {
        if (this.state == TaskState.CANCELLED || this.state == TaskState.FINISHED)
            return false;
        if (!mayInterruptIfRunning && this.state == TaskState.RUNNING && this.period == null)
            return false;
        if (mayInterruptIfRunning && this.state == TaskState.RUNNING)
            scheduler.interruptTaskIfRunning(this);
        this.state = TaskState.CANCELLED;
        this.notifyAll();
        return true;
    }

    @Override
    public synchronized boolean isRunning() {
        return this.state == TaskState.RUNNING;
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
        return this.period != null;
    }

    @Override
    public synchronized boolean isFinished() {
        return this.period == null && this.state == TaskState.FINISHED;
    }

    @Override
    public synchronized boolean isCancelled() {
        return this.state == TaskState.CANCELLED;
    }

    @Override
    public synchronized void join() throws InterruptedException, CancellationException, ExecutionException {
        while (true) {
            if (this.exception != null)
                throw this.exception;
            if (this.isFinished())
                return;
            if (this.isCancelled())
                throw new CancellationException("Task is cancelled");
            this.wait();
        }
    }

    @Override
    public synchronized void join(final long timeout, final TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException {
        final long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        // loop to guard against spurious wakeups and to honor the remaining timeout
        while (true) {
            if (this.exception != null)
                throw this.exception;
            if (this.isFinished())
                return;
            if (this.isCancelled())
                throw new CancellationException("Task is cancelled");
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
                throw new TimeoutException("Task is not finished in " + timeout + " " + unit.name());
            this.wait(remaining);
        }
    }

    @Override
    public synchronized void setExceptionHandler(Consumer<ExecutionException> handler) {
        this.handler = handler;
    }

    @Override
    public void run() throws ExecutionException {
        try {
            this.runnable.run();
        } catch (Exception e) {
            throw new ExecutionException(e);
        }
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
