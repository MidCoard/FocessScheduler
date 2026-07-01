package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract scheduler that wires {@link Dispatcher} and {@link TaskExecutor} together.
 * <p>
 * Extends {@link java.util.concurrent.AbstractExecutorService} to provide
 * full {@link java.util.concurrent.ExecutorService} compatibility while the
 * {@link Scheduler} interface defines the scheduling-specific API.
 * <p>
 * The Scheduler is the composition root — it mediates all communication between
 * the Dispatcher (which decides <em>when</em> a task runs) and the TaskExecutor
 * (which decides <em>how</em> a task runs).
 */
public abstract class AbstractScheduler extends java.util.concurrent.AbstractExecutorService implements Scheduler {

    private final Dispatcher dispatcher;
    private final TaskExecutor executor;
    private final String name;
    private volatile Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    protected AbstractScheduler(Dispatcher dispatcher, TaskExecutor executor, String name) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.name = name;
        // Wire the scheduler reference after construction to avoid leaking
        // 'this' in the constructor. Both Dispatcher and TaskExecutor need
        // the reference for uncaught-exception handling and task handoff.
        dispatcher.setScheduler(this);
        executor.setScheduler(this);
    }

    // --- Scheduler API ---

    @Override
    @NonNull
    public Task schedule(@NonNull Runnable runnable, @NonNull Duration delay) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, this);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    @NonNull
    public Task schedule(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull String name) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, this, name);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    @NonNull
    public Task schedule(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull String name, @Nullable Consumer<ExecutionException> handler) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, this, name, handler);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    @NonNull
    public Task scheduleAtFixedRate(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull Duration period) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, period, this);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    @NonNull
    public Task scheduleAtFixedRate(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull Duration period, @NonNull String name) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, period, this, name);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    @NonNull
    public Task scheduleAtFixedRate(@NonNull Runnable runnable, @NonNull Duration delay, @NonNull Duration period, @NonNull String name, @Nullable Consumer<ExecutionException> handler) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, period, this, name, handler);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    @NonNull
    public <V> Callback<V> submit(@NonNull Callable<V> callable, @NonNull Duration delay) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessCallback<V> callback = new FocessCallback<>(callable, this);
        callback.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(callback);
        return callback;
    }

    @Override
    @NonNull
    public <V> Callback<V> submit(@NonNull Callable<V> callable, @NonNull Duration delay, @NonNull String name) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessCallback<V> callback = new FocessCallback<>(callable, this, name);
        callback.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(callback);
        return callback;
    }

    @Override
    @NonNull
    public <V> Callback<V> submit(@NonNull Callable<V> callable, @NonNull Duration delay, @NonNull String name, @Nullable Function<ExecutionException, V> handler) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessCallback<V> callback = new FocessCallback<>(callable, this, name, handler);
        callback.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(callback);
        return callback;
    }

    // --- ExecutorService submit overrides ---
    // These bridge Scheduler's schedule/submit API with ExecutorService's submit API.
    // Scheduler.submit(Callable, Duration) returns Callback<V> which extends Future<V>.
    // ExecutorService.submit() returns Future<V>. The return types are compatible.

    @Override
    public <V> Future<V> submit(@NonNull Callable<V> task) {
        return submit(task, Duration.ZERO);
    }

    @Override
    public Future<?> submit(@NonNull Runnable task) {
        return submit(() -> { task.run(); return null; }, Duration.ZERO);
    }

    @Override
    public <V> Future<V> submit(@NonNull Runnable task, V result) {
        return submit(() -> { task.run(); return result; }, Duration.ZERO);
    }

    // --- Dispatcher ↔ Executor wiring ---

    /**
     * Called by the Dispatcher when a task's scheduled time has arrived.
     * Delegates to the executor for actual execution.
     */
    void onTaskReady(FocessTask task) {
        executor.execute(task, () -> onTaskComplete(task));
    }

    /**
     * Called by the Executor when a task has finished execution.
     * Re-dispatches periodic tasks; one-off tasks are done.
     */
    void onTaskComplete(FocessTask task) {
        if (task.isPeriod() && !task.isCancelled()) {
            task.clear();
            task.setScheduledTime(System.nanoTime() + task.getPeriod().toNanos());
            try {
                dispatcher.dispatch(task);
            } catch (SchedulerClosedException e) {
                // Scheduler was shut down between the isShutdown check and dispatch —
                // cancel the periodic task gracefully instead of letting the exception
                // propagate and kill the worker or dispatcher thread.
                task.cancel(false);
            }
        }
    }

    // --- ExecutorService ---

    @Override
    public void execute(@NonNull Runnable command) {
        schedule(command, Duration.ZERO);
    }

    @Override
    public void shutdown() {
        dispatcher.shutdown(false);
        executor.shutdown(false);
    }

    @Override
    @NonNull
    public List<Runnable> shutdownNow() {
        dispatcher.shutdown(true);
        executor.shutdown(true);
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return dispatcher.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return dispatcher.isShutdown() && executor.isIdle();
    }

    @Override
    public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            LockSupport.parkNanos(Math.min(remaining, 100_000_000L)); // 100ms max poll
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return true;
    }

    // --- Cancellation ---

    @Override
    public void interruptTaskIfRunning(@NonNull FocessTask task) {
        executor.interruptTask(task);
    }

    @Override
    public void cancelPending() {
        dispatcher.cancelPending();
    }

    // --- Name and Thread.UncaughtExceptionHandler ---

    @Override
    @NonNull
    public String getName() {
        return this.name;
    }

    @Override
    public void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler) {
        this.uncaughtExceptionHandler = handler;
    }

    @Override
    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return this.uncaughtExceptionHandler;
    }

    @Override
    @NonNull
    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    @NonNull
    public TaskExecutor getTaskExecutor() {
        return executor;
    }

    @Override
    public String toString() {
        return this.getName();
    }
}
