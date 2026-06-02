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

public class FocessTask implements ITask {

    /**
     * Enum representing the possible states of a task
     */
    private enum TaskState {
        /** Task is pending execution */
        PENDING,
        /** Task is currently running */
        RUNNING,
        /** Task has finished execution */
        FINISHED,
        /** Task has been cancelled */
        CANCELLED
    }

    private final Runnable runnable;
    private final Scheduler scheduler;
    private final String name;
    private TaskState state = TaskState.PENDING;

    protected ExecutionException exception;
    private Duration period;
    private boolean isPeriod;
    private ComparableTask nativeTask;
    private Consumer<ExecutionException> handler;
    private final List<TaskPool> taskPools = Lists.newCopyOnWriteArrayList();

    FocessTask(@Nullable final Runnable runnable, @NotNull final Scheduler scheduler, final String name) {
        this.runnable = runnable;
        this.scheduler = scheduler;
        this.name = scheduler.getName() + "-" + name;
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
        this(runnable, period, scheduler, name, null);
    }

    FocessTask(final Runnable runnable, final Duration period, final Scheduler scheduler, final String name, final Consumer<ExecutionException> handler) {
        this(runnable, scheduler, name);
        this.isPeriod = true;
        this.period = period;
        this.handler = handler;
    }

    @Override
    public synchronized void setNativeTask(final ComparableTask nativeTask) {
        this.nativeTask = nativeTask;
    }

    @Override
    public synchronized void clear() {
        // When a periodic task completes, it resets to PENDING for the next execution
        if (this.isPeriod && this.state == TaskState.FINISHED) {
            this.state = TaskState.PENDING;
        }
        this.exception = null;
    }

    @Override
    public synchronized void startRun() {
        this.state = TaskState.RUNNING;
    }

    @Override
    public synchronized void endRun() {
        if (this.state == TaskState.RUNNING) {
            this.state = TaskState.FINISHED;
        }
        this.notifyAll();
        for (final TaskPool taskPool : this.taskPools) {
            taskPool.removeTask(this);
            taskPool.finishTask(this);
        }
    }

    @Override
    public synchronized void setException(final ExecutionException e) {
        if (this.handler != null)
            this.handler.accept(e);
        else this.exception = e;
    }

    @Override
    public synchronized void addTaskPool(final TaskPool taskPool) {
        this.taskPools.add(taskPool);
    }

    @Override
    public synchronized void removeTaskPool(final TaskPool taskPool) {
        this.taskPools.remove(taskPool);
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
        boolean cancelled = this.nativeTask.cancel(mayInterruptIfRunning);
        if (cancelled) {
            this.markCancelled();
        }
        return cancelled;
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
        return this.isPeriod;
    }

    @Override
    public synchronized boolean isFinished() {
        // A non-periodic task is finished when its state is FINISHED
        // A periodic task never truly "finishes" in the sense of this method
        return !this.isPeriod && this.state == TaskState.FINISHED;
    }

    @Override
    public synchronized boolean isCancelled() {
        // Check if the task is in CANCELLED state or if the native task is cancelled
        return this.state == TaskState.CANCELLED || this.nativeTask.isCancelled();
    }

    /**
     * Internal method to mark the task as cancelled in the state machine
     */
    private synchronized void markCancelled() {
        this.state = TaskState.CANCELLED;
    }

    @Override
    public synchronized void join() throws InterruptedException, CancellationException, ExecutionException {
        // loop to guard against spurious wakeups: only return once a terminal state is reached
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
