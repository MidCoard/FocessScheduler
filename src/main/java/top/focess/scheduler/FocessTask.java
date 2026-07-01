package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * CAS-based task implementation with lock-free state transitions.
 * <p>
 * State machine: PENDING → RUNNING → FINISHED (or CANCELLED at any point).
 * Period tasks reset to PENDING after each execution via {@link #clear()},
 * unless the task was cancelled or terminated by an unhandled exception.
 * <p>
 * <b>Periodic task termination (JDK {@code ScheduledExecutorService} contract):</b>
 * A periodic task runs indefinitely until one of the following occurs:
 * <ul>
 *   <li>The task is explicitly cancelled via {@link #cancel(boolean)}.</li>
 *   <li>The scheduler terminates, resulting in task cancellation.</li>
 *   <li>An execution throws an exception. In this case, subsequent executions
 *       are suppressed and {@link #isDone()} returns {@code true}.</li>
 * </ul>
 * If an exception handler (Consumer or Function) is configured and successfully
 * suppresses the exception, the periodic task continues — this is an opt-in
 * extension beyond the JDK contract.
 * <p>
 * Tasks are ordered by their scheduled execution time via {@link Comparable}
 * and implement {@link Delayed} for use with {@link java.util.concurrent.DelayQueue}.
 * <p>
 * The task state and completion latch are bundled into a single
 * {@link StateAndLatch} object so that all state transitions are atomic
 * with respect to the latch. This prevents orphaned latches that could
 * cause {@link #join()} to block forever under concurrent cancel/clear
 * interleavings on periodic tasks.
 */
public class FocessTask implements TaskInternal, Delayed {

    /**
     * The lifecycle states of a task.
     */
    enum TaskState {
        /** The task is waiting to be executed. */
        PENDING,
        /** The task is currently executing. */
        RUNNING,
        /** The task has completed execution. */
        FINISHED,
        /** The task was cancelled before or during execution. */
        CANCELLED
    }

    /**
         * Compound atomic value bundling the task state with its completion latch.
         * Because both must transition together (e.g. FINISHED→countDown, CANCELLED→countDown,
         * clear()→new latch + PENDING), a single CAS on this object makes the update atomic.
         */
        record StateAndLatch(TaskState state, CountDownLatch latch) {
    }

    private final Runnable runnable;
    private final Scheduler scheduler;
    private final String name;
    private final Duration period;
    private final AtomicReference<StateAndLatch> stateRef =
            new AtomicReference<>(new StateAndLatch(TaskState.PENDING, new CountDownLatch(1)));
    private final AtomicLong scheduledTime = new AtomicLong();
    private final CopyOnWriteArrayList<Runnable> completionListeners = new CopyOnWriteArrayList<>();

    protected volatile ExecutionException exception;
    protected volatile Consumer<ExecutionException> handler;

    FocessTask(@Nullable Runnable runnable, @NonNull Scheduler scheduler) {
        this(runnable, null, scheduler, UUID.randomUUID().toString().substring(0, 8), null);
    }

    FocessTask(@Nullable Runnable runnable, @NonNull Scheduler scheduler, @NonNull String name) {
        this(runnable, null, scheduler, name, null);
    }

    FocessTask(@Nullable Runnable runnable, @NonNull Scheduler scheduler, @NonNull String name,
               @Nullable Consumer<ExecutionException> handler) {
        this(runnable, null, scheduler, name, handler);
    }

    FocessTask(@Nullable Runnable runnable, @Nullable Duration period, @NonNull Scheduler scheduler) {
        this(runnable, period, scheduler, UUID.randomUUID().toString().substring(0, 8), null);
    }

    FocessTask(@Nullable Runnable runnable, @Nullable Duration period, @NonNull Scheduler scheduler,
               @NonNull String name) {
        this(runnable, period, scheduler, name, null);
    }

    FocessTask(@Nullable Runnable runnable, @Nullable Duration period, @NonNull Scheduler scheduler,
               @NonNull String name, @Nullable Consumer<ExecutionException> handler) {
        this.runnable = runnable;
        this.period = period;
        this.scheduler = scheduler;
        this.name = scheduler.getName() + "-" + name;
        this.handler = handler;
    }

    // --- State transitions ---

    TaskState getState() {
        return stateRef.get().state;
    }

    void setScheduledTime(long nanoTime) {
        this.scheduledTime.set(nanoTime);
    }

    long getScheduledTime() {
        return scheduledTime.get();
    }

    @Override
    public boolean startRun() {
        while (true) {
            StateAndLatch current = stateRef.get();
            if (current.state != TaskState.PENDING) return false;
            if (stateRef.compareAndSet(current, new StateAndLatch(TaskState.RUNNING, current.latch))) {
                return true;
            }
            // CAS failed — retry
        }
    }

    @Override
    public void endRun() {
        while (true) {
            StateAndLatch current = stateRef.get();
            if (current.state != TaskState.RUNNING) return;
            if (stateRef.compareAndSet(current, new StateAndLatch(TaskState.FINISHED, current.latch))) {
                current.latch.countDown();
                fireCompletionListeners();
                return;
            }
            // CAS failed — retry
        }
    }

    @Override
    public void clear() {
        // Only periodic tasks are reusable; reset FINISHED → PENDING
        if (this.period != null) {
            while (true) {
                StateAndLatch current = stateRef.get();
                if (current.state != TaskState.FINISHED) return;
                // Atomically transition to PENDING with a fresh latch
                if (stateRef.compareAndSet(current,
                        new StateAndLatch(TaskState.PENDING, new CountDownLatch(1)))) {
                    this.exception = null;
                    completionListeners.clear();
                    return;
                }
                // CAS failed — retry
            }
        }
    }

    // --- Task interface ---

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        while (true) {
            StateAndLatch current = stateRef.get();
            if (current.state == TaskState.CANCELLED)
                return false;
            if (current.state == TaskState.FINISHED) {
                // For periodic tasks, FINISHED is a mid-cycle state between
                // runs — the task should still be cancellable (JDK contract:
                // period tasks are only truly done when cancelled or
                // exception-terminated). For one-shot tasks, FINISHED is
                // terminal and cancel is a no-op.
                if (this.period == null) return false;
                // Periodic task: transition FINISHED → CANCELLED
            }
            if (current.state == TaskState.RUNNING && !mayInterruptIfRunning)
                return false;
            StateAndLatch next = new StateAndLatch(TaskState.CANCELLED, current.latch);
            if (stateRef.compareAndSet(current, next)) {
                current.latch.countDown();
                fireCompletionListeners();
                if (current.state == TaskState.RUNNING)
                    scheduler.interruptTaskIfRunning(this);
                return true;
            }
            // CAS failed — retry
        }
    }

    @Override
    public boolean isRunning() {
        return stateRef.get().state == TaskState.RUNNING;
    }

    @Override
    @NonNull
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    @Override
    @NonNull
    public String getName() {
        return this.name;
    }

    @Override
    public boolean isPeriod() {
        return this.period != null;
    }

    @Override
    public boolean isDone() {
        TaskState s = stateRef.get().state;
        if (s == TaskState.CANCELLED) return true;
        // For one-shot tasks, FINISHED is terminal.
        // For periodic tasks, FINISHED is terminal only when the task
        // has an unhandled exception (JDK contract: exception terminates
        // period tasks). Otherwise FINISHED is a mid-cycle state and
        // the task is not truly done — it will be re-dispatched.
        if (s == TaskState.FINISHED) {
            if (this.period == null) return true; // one-shot: FINISHED = done
            return this.exception != null;         // period: done only if exception-terminated
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return stateRef.get().state == TaskState.CANCELLED;
    }

    @Override
    public void join() throws ExecutionException, InterruptedException, CancellationException {
        stateRef.get().latch.await();
        if (exception != null) throw exception;
        if (isCancelled()) throw new CancellationException("Task is cancelled");
    }

    @Override
    public void join(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, CancellationException, ExecutionException, TimeoutException {
        if (!stateRef.get().latch.await(timeout, unit))
            throw new TimeoutException("Task is not finished in " + timeout + " " + unit.name());
        if (exception != null) throw exception;
        if (isCancelled()) throw new CancellationException("Task is cancelled");
    }

    @Override
    public void setExceptionHandler(@Nullable Consumer<ExecutionException> handler) {
        this.handler = handler;
    }

    @Override
    public void onComplete(@NonNull Runnable listener) {
        // Fire-once guard: prevents double-invocation in the race between
        // adding the listener and the task completing concurrently.
        // Note: For periodic tasks, listeners are one-shot — they fire on the
        // first completion only and are cleared on re-dispatch via clear().
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable guarded = () -> {
            if (fired.compareAndSet(false, true)) {
                listener.run();
            }
        };
        completionListeners.add(guarded);
        if (isDone()) {
            guarded.run();
        }
    }

    // --- TaskInternal interface ---

    @Override
    public void run() throws ExecutionException {
        try {
            this.runnable.run();
        } catch (Throwable e) {
            throw new ExecutionException(e);
        }
    }

    @Override
    public Duration getPeriod() {
        return this.period;
    }

    @Override
    public void setException(@NonNull ExecutionException e) {
        Consumer<ExecutionException> h = this.handler;
        if (h != null) {
            try {
                h.accept(e);
            } catch (Throwable ignored) {
                this.exception = e;
            }
        } else {
            this.exception = e;
        }
    }

    @Override
    public ExecutionException getException() {
        return this.exception;
    }

    // --- Delayed interface ---

    @Override
    public long getDelay(@NonNull TimeUnit unit) {
        return unit.convert(scheduledTime.get() - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(@NonNull Delayed o) {
        if (o instanceof FocessTask)
            return Long.compare(this.scheduledTime.get(), ((FocessTask) o).scheduledTime.get());
        return Long.compare(this.getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
    }

    // --- Completion listeners ---

    private void fireCompletionListeners() {
        for (Runnable listener : completionListeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // don't let one bad listener break others
            }
        }
    }

    @Override
    public String toString() {
        return this.getName();
    }
}
