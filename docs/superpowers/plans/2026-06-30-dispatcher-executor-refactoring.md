# Dispatcher-Executor Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor FocessScheduler from synchronized/monitor-based to composable Dispatcher+Executor with CAS state management and full ExecutorService compatibility.

**Architecture:** Scheduler is the composition root that wires an independent Dispatcher (decides when) and TaskExecutor (decides how). Tasks use AtomicReference+CAS for state transitions and CountDownLatch for join. DelayQueue replaces PriorityQueue+synchronized.

**Tech Stack:** Java 17, java.util.concurrent (no Guava), JUnit 5

## Global Constraints

- Java 17 minimum (maven.compiler.release=17)
- No Guava dependency — all Guava usages replaced with java.util.concurrent equivalents
- All scheduling uses System.nanoTime(), not System.currentTimeMillis()
- CAS-first: prefer AtomicReference/AtomicLong/CountDownLatch over synchronized/wait/notify; use locks only where CAS is not natural
- Version target: 2.1.0
- Package: top.focess.scheduler
- Test class: top.focess.scheduler.TestUtil

---

## File Structure

```
src/main/java/top/focess/scheduler/
├── Task.java                   (interface, extends Future<Void>)
├── TaskInternal.java           (package-private interface, extends Task)
├── FocessTask.java             (CAS-based, implements TaskInternal + Delayed)
├── Callback.java               (interface, extends Task + Future<V>)
├── FocessCallback.java         (extends FocessTask, implements Callback)
├── Scheduler.java              (interface, extends ExecutorService)
├── AbstractScheduler.java      (abstract, extends AbstractExecutorService)
├── FocessScheduler.java        (concrete, single-thread convenience)
├── ThreadPoolScheduler.java    (concrete, multi-thread convenience)
├── Dispatcher.java             (interface)
├── TimeDispatcher.java         (concrete, uses DelayQueue)
├── TaskExecutor.java           (interface)
├── InlineExecutor.java         (concrete, runs on dispatcher thread)
├── PoolTaskExecutor.java       (concrete, multi-threaded with Worker inner class)
├── TaskPool.java               (concrete, CompletionStrategy pattern)
├── AndTaskPool.java            (convenience, ALL strategy)
├── OrTaskPool.java             (convenience, ANY strategy)
└── exceptions/
    ├── SchedulerClosedException.java
    ├── TaskNotFinishedException.java
    └── PeriodTaskException.java

src/test/java/top/focess/scheduler/
└── TestUtil.java               (rewritten tests)
```

---

### Task 1: Task Interface + TaskInternal + TaskState enum

**Files:**
- Create: `src/main/java/top/focess/scheduler/Task.java`
- Create: `src/main/java/top/focess/scheduler/TaskInternal.java`

**Interfaces:**
- Consumes: Nothing (foundational)
- Produces: `Task` interface (extends `Future<Void>`) with methods: `cancel(boolean)`, `isCancelled()`, `isDone()`, `isRunning()`, `getScheduler()`, `getName()`, `isPeriod()`, `join()`, `join(long,TimeUnit)`, `setExceptionHandler(Consumer<ExecutionException>)`, `onComplete(Runnable)`. `TaskInternal` interface (extends Task) with methods: `run()`, `getPeriod()`, `clear()`, `startRun()`, `endRun()`, `setException(ExecutionException)`, `getException()`.

- [ ] **Step 1: Write the Task interface**

```java
package top.focess.scheduler;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * A handle to a scheduled task, providing methods to query execution state,
 * wait for completion, and request cancellation.
 */
public interface Task extends Future<Void> {

    /**
     * Cancels this task.
     */
    @Override
    boolean cancel(boolean mayInterruptIfRunning);

    /**
     * Cancels this task without interrupting the executing thread.
     */
    default boolean cancel() {
        return this.cancel(false);
    }

    /**
     * Returns whether this task is currently executing.
     */
    boolean isRunning();

    /**
     * Returns the scheduler this task belongs to.
     */
    Scheduler getScheduler();

    /**
     * Returns the name of this task.
     */
    String getName();

    /**
     * Returns whether this task is a periodic task.
     */
    boolean isPeriod();

    /**
     * Waits until this task finishes, is cancelled, or throws an exception.
     */
    void join() throws ExecutionException, InterruptedException, CancellationException;

    /**
     * Waits at most the given time for this task to finish.
     */
    void join(long timeout, TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException;

    /**
     * Sets the exception handler for this task.
     */
    void setExceptionHandler(Consumer<ExecutionException> handler);

    /**
     * Register a callback to be invoked when this task completes
     * (finishes, is cancelled, or throws an exception).
     * If the task is already done, the listener is called immediately.
     * Callers should wrap the listener in a fire-once guard to avoid
     * double-invocation in a race.
     */
    void onComplete(Runnable listener);
}
```

- [ ] **Step 2: Write the TaskInternal interface**

```java
package top.focess.scheduler;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

/**
 * Internal task interface used by Dispatcher and Executor.
 */
interface TaskInternal extends Task {

    /**
     * Execute the task's payload.
     */
    void run() throws ExecutionException;

    /**
     * Returns the period for periodic tasks, or null for one-off tasks.
     */
    Duration getPeriod();

    /**
     * Reset a periodic task's state for re-scheduling.
     */
    void clear();

    /**
     * Transition to RUNNING state.
     */
    void startRun();

    /**
     * Transition from RUNNING to FINISHED and fire completion listeners.
     */
    void endRun();

    /**
     * Set the exception produced by this task.
     */
    void setException(ExecutionException e);

    /**
     * Get the exception produced by this task, or null.
     */
    ExecutionException getException();
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/top/focess/scheduler/Task.java src/main/java/top/focess/scheduler/TaskInternal.java
git commit -m "feat: add Task and TaskInternal interfaces for v2.1.0"
```

---

### Task 2: FocessTask — CAS-based Task Implementation

**Files:**
- Create: `src/main/java/top/focess/scheduler/FocessTask.java`

**Interfaces:**
- Consumes: `Task`, `TaskInternal`, `Scheduler` (referenced by type only for `interruptTaskIfRunning`)
- Produces: `FocessTask` class with: `TaskState` enum, `AtomicReference<TaskState> state`, `AtomicLong scheduledTime`, `CountDownLatch completionLatch`, `CopyOnWriteArrayList<Runnable> completionListeners`. Methods: all TaskInternal methods + `casState(TaskState, TaskState)`, `setScheduledTime(long)`, `getScheduledTime()`, `getDelay(TimeUnit)`, `compareTo(Delayed)`.

- [ ] **Step 1: Write the FocessTask class**

```java
package top.focess.scheduler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * CAS-based task implementation with lock-free state transitions.
 * <p>
 * State machine: PENDING → RUNNING → FINISHED (or CANCELLED at any point).
 * Period tasks reset to PENDING after each execution via {@link #clear()}.
 */
public class FocessTask implements TaskInternal, Comparable<FocessTask>, Delayed {

    enum TaskState {
        PENDING, RUNNING, FINISHED, CANCELLED
    }

    private final Runnable runnable;
    private final Scheduler scheduler;
    private final String name;
    private final Duration period;
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.PENDING);
    private final AtomicLong scheduledTime = new AtomicLong();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final CopyOnWriteArrayList<Runnable> completionListeners = new CopyOnWriteArrayList<>();

    private volatile ExecutionException exception;
    private volatile Consumer<ExecutionException> handler;

    FocessTask(@Nullable Runnable runnable, @NotNull Scheduler scheduler) {
        this(runnable, null, scheduler, UUID.randomUUID().toString().substring(0, 8), null);
    }

    FocessTask(@Nullable Runnable runnable, @NotNull Scheduler scheduler, @NotNull String name) {
        this(runnable, null, scheduler, name, null);
    }

    FocessTask(@Nullable Runnable runnable, @NotNull Scheduler scheduler, @NotNull String name,
               @Nullable Consumer<ExecutionException> handler) {
        this(runnable, null, scheduler, name, handler);
    }

    FocessTask(@Nullable Runnable runnable, @Nullable Duration period, @NotNull Scheduler scheduler) {
        this(runnable, period, scheduler, UUID.randomUUID().toString().substring(0, 8), null);
    }

    FocessTask(@Nullable Runnable runnable, @Nullable Duration period, @NotNull Scheduler scheduler,
               @NotNull String name) {
        this(runnable, period, scheduler, name, null);
    }

    FocessTask(@Nullable Runnable runnable, @Nullable Duration period, @NotNull Scheduler scheduler,
               @NotNull String name, @Nullable Consumer<ExecutionException> handler) {
        this.runnable = runnable;
        this.period = period;
        this.scheduler = scheduler;
        this.name = scheduler.getName() + "-" + name;
        this.handler = handler;
    }

    // --- State transitions ---

    boolean casState(TaskState expected, TaskState newValue) {
        return state.compareAndSet(expected, newValue);
    }

    TaskState getState() {
        return state.get();
    }

    void setScheduledTime(long nanoTime) {
        this.scheduledTime.set(nanoTime);
    }

    long getScheduledTime() {
        return scheduledTime.get();
    }

    @Override
    public void startRun() {
        // CAS: PENDING → RUNNING (called by dispatcher)
        state.compareAndSet(TaskState.PENDING, TaskState.RUNNING);
    }

    @Override
    public void endRun() {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.FINISHED)) {
            completionLatch.countDown();
            fireCompletionListeners();
        }
    }

    @Override
    public void clear() {
        // Only periodic tasks are reusable; reset FINISHED → PENDING
        if (this.period != null) {
            state.compareAndSet(TaskState.FINISHED, TaskState.PENDING);
        }
    }

    // --- Task interface ---

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        while (true) {
            TaskState current = state.get();
            if (current == TaskState.CANCELLED || current == TaskState.FINISHED)
                return false;
            if (current == TaskState.RUNNING && !mayInterruptIfRunning)
                return false;
            if (state.compareAndSet(current, TaskState.CANCELLED)) {
                if (current == TaskState.RUNNING)
                    scheduler.interruptTaskIfRunning(this);
                completionLatch.countDown();
                fireCompletionListeners();
                return true;
            }
            // CAS failed — retry
        }
    }

    @Override
    public boolean isRunning() {
        return state.get() == TaskState.RUNNING;
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
    public boolean isDone() {
        TaskState s = state.get();
        return s == TaskState.FINISHED || s == TaskState.CANCELLED;
    }

    @Override
    public boolean isCancelled() {
        return state.get() == TaskState.CANCELLED;
    }

    @Override
    public void join() throws ExecutionException, InterruptedException, CancellationException {
        completionLatch.await();
        if (exception != null) throw exception;
        if (isCancelled()) throw new CancellationException("Task is cancelled");
    }

    @Override
    public void join(long timeout, TimeUnit unit)
            throws InterruptedException, CancellationException, ExecutionException, TimeoutException {
        if (!completionLatch.await(timeout, unit))
            throw new TimeoutException("Task is not finished in " + timeout + " " + unit.name());
        if (exception != null) throw exception;
        if (isCancelled()) throw new CancellationException("Task is cancelled");
    }

    @Override
    public void setExceptionHandler(Consumer<ExecutionException> handler) {
        this.handler = handler;
    }

    @Override
    public void onComplete(Runnable listener) {
        completionListeners.add(listener);
        if (isDone()) {
            listener.run();
        }
    }

    // --- TaskInternal interface ---

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
    public void setException(ExecutionException e) {
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
    public long getDelay(@NotNull TimeUnit unit) {
        return unit.convert(scheduledTime.get() - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(@NotNull Delayed o) {
        if (o instanceof FocessTask)
            return Long.compare(this.scheduledTime.get(), ((FocessTask) o).scheduledTime.get());
        return Long.compare(this.getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
    }

    @Override
    public int compareTo(@NotNull FocessTask o) {
        return Long.compare(this.scheduledTime.get(), o.scheduledTime.get());
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/focess/scheduler/FocessTask.java
git commit -m "feat: add CAS-based FocessTask with Delayed support"
```

---

### Task 3: Callback + FocessCallback

**Files:**
- Create: `src/main/java/top/focess/scheduler/Callback.java`
- Create: `src/main/java/top/focess/scheduler/FocessCallback.java`

**Interfaces:**
- Consumes: `Task`, `FocessTask`
- Produces: `Callback<V>` interface (extends Task + Future<V>) with `getNow()`, `setExceptionHandler(Function)`. `FocessCallback<V>` class extending FocessTask implementing Callback.

- [ ] **Step 1: Write the Callback interface**

```java
package top.focess.scheduler;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * A handle to a scheduled callable task that produces a value.
 * Extends Task with the ability to retrieve the computed result and
 * implements Future for compatibility with standard Java concurrency APIs.
 */
public interface Callback<V> extends Task, Future<V> {

    /**
     * Returns the computed result without blocking.
     *
     * @throws CancellationException    if the task was cancelled
     * @throws TaskNotFinishedException if the task has not yet finished
     * @throws ExecutionException       if the task threw an exception
     */
    V getNow() throws ExecutionException, CancellationException, TaskNotFinishedException;

    /**
     * Waits for the task to finish, then returns the computed result.
     */
    @Override
    default V get() throws InterruptedException, ExecutionException {
        join();
        return getNow();
    }

    /**
     * Waits at most the given time, then returns the computed result.
     */
    @Override
    default V get(long timeout, @NotNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException, CancellationException {
        join(timeout, unit);
        return getNow();
    }

    /**
     * Sets the exception handler for this callback.
     * When the task throws an exception, the handler's return value
     * is used as the result of getNow(), suppressing the exception.
     */
    void setExceptionHandler(Function<ExecutionException, V> handler);
}
```

- [ ] **Step 2: Write the FocessCallback class**

```java
package top.focess.scheduler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Callback implementation that wraps a Callable and stores the result.
 */
public class FocessCallback<V> extends FocessTask implements Callback<V> {

    private final Callable<V> callback;
    private volatile V value;
    private volatile Function<ExecutionException, V> functionHandler;

    FocessCallback(@NotNull Callable<V> callback, @NotNull Scheduler scheduler) {
        super(null, scheduler);
        this.callback = callback;
    }

    FocessCallback(@NotNull Callable<V> callback, @NotNull Scheduler scheduler, @NotNull String name) {
        super(null, scheduler, name);
        this.callback = callback;
    }

    FocessCallback(@NotNull Callable<V> callback, @NotNull Scheduler scheduler, @NotNull String name,
                   @Nullable Function<ExecutionException, V> handler) {
        super(null, scheduler, name);
        this.callback = callback;
        this.functionHandler = handler;
    }

    @Override
    public V getNow() throws ExecutionException, CancellationException, TaskNotFinishedException {
        if (getException() != null) throw getException();
        if (isCancelled()) throw new CancellationException("Task is cancelled");
        if (!isDone()) throw new TaskNotFinishedException(this);
        return this.value;
    }

    @Override
    public void setException(ExecutionException e) {
        Function<ExecutionException, V> h = this.functionHandler;
        if (h != null) {
            try {
                this.value = h.apply(e);
            } catch (Throwable ignored) {
                super.setException(e);
            }
        } else {
            super.setException(e);
        }
    }

    @Override
    public void setExceptionHandler(Function<ExecutionException, V> handler) {
        this.functionHandler = handler;
    }

    @Override
    public void setExceptionHandler(Consumer<ExecutionException> handler) {
        this.functionHandler = e -> {
            handler.accept(e);
            return null;
        };
    }

    @Override
    public void run() throws ExecutionException {
        try {
            this.value = this.callback.call();
        } catch (Exception e) {
            throw new ExecutionException(e);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/top/focess/scheduler/Callback.java src/main/java/top/focess/scheduler/FocessCallback.java
git commit -m "feat: add Callback and FocessCallback with Future compatibility"
```

---

### Task 4: Dispatcher Interface + TimeDispatcher

**Files:**
- Create: `src/main/java/top/focess/scheduler/Dispatcher.java`
- Create: `src/main/java/top/focess/scheduler/TimeDispatcher.java`

**Interfaces:**
- Consumes: `FocessTask` (for DelayQueue), `Scheduler` (for onTaskReady callback), `SchedulerClosedException`
- Produces: `Dispatcher` interface with `dispatch(FocessTask)`, `cancelPending()`, `shutdown(boolean)`, `isShutdown()`. `TimeDispatcher` class using `DelayQueue<FocessTask>`.

- [ ] **Step 1: Write the Dispatcher interface**

```java
package top.focess.scheduler;

/**
 * Dispatches tasks based on timing.
 * When a task's scheduled time arrives, the dispatcher notifies the scheduler
 * via onTaskReady(), which then delegates to the executor.
 */
public interface Dispatcher {

    /**
     * Submit a task for dispatching.
     */
    void dispatch(FocessTask task);

    /**
     * Cancel all pending (not-yet-dispatched) tasks.
     */
    void cancelPending();

    /**
     * Shutdown the dispatcher.
     *
     * @param now if true, stop immediately; if false, cancel pending and stop
     */
    void shutdown(boolean now);

    /**
     * Whether the dispatcher has been shut down.
     */
    boolean isShutdown();
}
```

- [ ] **Step 2: Write the TimeDispatcher class**

```java
package top.focess.scheduler;

import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Time-based dispatcher using a DelayQueue for thread-safe priority scheduling.
 * A single dispatcher thread blocks on queue.take() until a task's scheduled
 * time arrives, then hands the task to the scheduler for execution.
 */
public class TimeDispatcher implements Dispatcher {

    private final DelayQueue<FocessTask> queue = new DelayQueue<>();
    private final AbstractScheduler scheduler;
    private final Thread thread;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private volatile AbstractScheduler scheduler;

    public TimeDispatcher(String name, boolean isDaemon) {
        this.thread = new Thread(this::runLoop, name + "-dispatcher");
        this.thread.setDaemon(isDaemon);
        this.thread.setUncaughtExceptionHandler((t, e) -> {
            AbstractScheduler s = scheduler;
            if (s != null) {
                s.shutdown();
                if (s.getUncaughtExceptionHandler() != null)
                    s.getUncaughtExceptionHandler().uncaughtException(t, e);
            }
        });
        this.thread.start();
    }

    /**
     * Set the scheduler reference. Called by AbstractScheduler after construction.
     */
    void setScheduler(AbstractScheduler scheduler) {
        this.scheduler = scheduler;
    }

    private void runLoop() {
        while (!shutdown.get()) {
            try {
                FocessTask task = queue.take();
                if (shutdown.get()) {
                    task.cancel(false);
                    continue;
                }
                if (task.isCancelled()) continue;
                if (!task.casState(FocessTask.TaskState.PENDING, FocessTask.TaskState.RUNNING)) continue;
                scheduler.onTaskReady(task);
            } catch (InterruptedException e) {
                // shutdownNow() or cancel signal — loop will check shutdown flag
            }
        }
        drainAndCancel();
    }

    private void drainAndCancel() {
        FocessTask task;
        while ((task = queue.poll()) != null) {
            task.cancel(false);
        }
    }

    @Override
    public void dispatch(FocessTask task) {
        if (shutdown.get()) throw new SchedulerClosedException(scheduler);
        queue.add(task);
    }

    @Override
    public void cancelPending() {
        FocessTask task;
        while ((task = queue.poll()) != null) {
            task.cancel(false);
        }
    }

    @Override
    public void shutdown(boolean now) {
        if (shutdown.getAndSet(true))
            return;
        if (now) {
            thread.interrupt();
        } else {
            cancelPending();
            thread.interrupt();
        }
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/top/focess/scheduler/Dispatcher.java src/main/java/top/focess/scheduler/TimeDispatcher.java
git commit -m "feat: add Dispatcher interface and TimeDispatcher with DelayQueue"
```

---

### Task 5: TaskExecutor Interface + InlineExecutor + PoolTaskExecutor

**Files:**
- Create: `src/main/java/top/focess/scheduler/TaskExecutor.java`
- Create: `src/main/java/top/focess/scheduler/InlineExecutor.java`
- Create: `src/main/java/top/focess/scheduler/PoolTaskExecutor.java`

**Interfaces:**
- Consumes: `FocessTask` (for execute/interrupt), `Scheduler` (for shutdown callback in PoolTaskExecutor)
- Produces: `TaskExecutor` interface with `execute(FocessTask, Runnable)`, `interruptTask(FocessTask)`, `shutdown(boolean)`, `isShutdown()`, `isIdle()`. `InlineExecutor` and `PoolTaskExecutor` implementations.

- [ ] **Step 1: Write the TaskExecutor interface**

```java
package top.focess.scheduler;

/**
 * Executes tasks that the dispatcher has handed off.
 * Reports completion via a callback.
 */
public interface TaskExecutor {

    /**
     * Execute a task. When done (success, failure, or cancellation),
     * call the completionCallback.
     */
    void execute(FocessTask task, Runnable completionCallback);

    /**
     * Interrupt the thread currently running the given task, if any.
     */
    void interruptTask(FocessTask task);

    /**
     * Shutdown the executor.
     *
     * @param now if true, interrupt running tasks; if false, let them finish
     */
    void shutdown(boolean now);

    /**
     * Whether the executor has been shut down.
     */
    boolean isShutdown();

    /**
     * Whether the executor has no running tasks.
     */
    boolean isIdle();
}
```

- [ ] **Step 2: Write the InlineExecutor class**

```java
package top.focess.scheduler;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs tasks on the calling thread (which is the dispatcher's thread).
 * Used for single-thread schedulers where dispatching and execution
 * happen on the same thread.
 */
public class InlineExecutor implements TaskExecutor {

    private volatile Thread runningThread;
    private volatile FocessTask currentTask;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    @Override
    public void execute(FocessTask task, Runnable completionCallback) {
        runningThread = Thread.currentThread();
        currentTask = task;
        try {
            task.run();
        } catch (ExecutionException e) {
            task.setException(e);
        } finally {
            Thread.interrupted(); // clear leaked interrupt
            runningThread = null;
            currentTask = null;
        }
        task.endRun();
        completionCallback.run();
    }

    @Override
    public void interruptTask(FocessTask task) {
        if (currentTask == task && runningThread != null) {
            runningThread.interrupt();
        }
    }

    @Override
    public void shutdown(boolean now) {
        shutdown.set(true);
        // InlineExecutor has no threads of its own to stop
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isIdle() {
        return currentTask == null;
    }
}
```

- [ ] **Step 3: Write the PoolTaskExecutor class**

```java
package top.focess.scheduler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-threaded executor with a shared work queue.
 * Workers compete for tasks from the queue.
 * Supports dynamic expansion (immediate mode) when all workers are busy.
 */
public class PoolTaskExecutor implements TaskExecutor {

    private final LinkedBlockingQueue<WorkItem> workQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<FocessTask, Worker> taskWorkerMap = new ConcurrentHashMap<>();
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private final int corePoolSize;
    private final boolean immediate;
    private final String name;
    private volatile AbstractScheduler scheduler;

    static class WorkItem {
        final FocessTask task;
        final Runnable completionCallback;

        WorkItem(FocessTask task, Runnable completionCallback) {
            this.task = task;
            this.completionCallback = completionCallback;
        }
    }

    public PoolTaskExecutor(int corePoolSize, boolean immediate, String name) {
        this.corePoolSize = corePoolSize;
        this.immediate = immediate;
        this.name = name;
        // Pre-start core workers
        for (int i = 0; i < corePoolSize; i++) {
            startWorker();
        }
    }

    /**
     * Set the scheduler reference. Called by AbstractScheduler after construction.
     */
    void setScheduler(AbstractScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void execute(FocessTask task, Runnable completionCallback) {
        workQueue.add(new WorkItem(task, completionCallback));
        ensureWorkers();
    }

    @Override
    public void interruptTask(FocessTask task) {
        Worker worker = taskWorkerMap.get(task);
        if (worker != null) worker.interrupt();
    }

    @Override
    public void shutdown(boolean now) {
        if (shutdown.getAndSet(true))
            return;
        if (now) {
            // Interrupt all workers
            for (Worker w : taskWorkerMap.values()) {
                w.interrupt();
            }
        }
        // Non-now: workers will drain naturally and exit
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isIdle() {
        return runningCount.get() == 0 && workQueue.isEmpty();
    }

    private void ensureWorkers() {
        // If all core workers are busy and immediate mode, add a worker
        if (immediate && runningCount.get() >= workerCount.get() && !shutdown.get()) {
            startWorker();
        }
    }

    private void startWorker() {
        int idx = workerCount.getAndIncrement();
        Worker worker = new Worker(name + "-worker-" + idx);
        worker.setDaemon(true);
        worker.start();
    }

    class Worker extends Thread {

        Worker(String name) {
            super(name);
            setUncaughtExceptionHandler((t, e) -> {
                try {
                    // Find the task this worker was running
                    FocessTask current = null;
                    for (var entry : taskWorkerMap.entrySet()) {
                        if (entry.getValue() == this) {
                            current = entry.getKey();
                            break;
                        }
                    }
                    if (current != null) {
                        current.setException(new ExecutionException(e));
                        current.endRun();
                        taskWorkerMap.remove(current);
                    }
                    // BY DESIGN: a single failing task shuts down the entire scheduler
                    scheduler.shutdown();
                    if (scheduler.getUncaughtExceptionHandler() != null)
                        scheduler.getUncaughtExceptionHandler().uncaughtException(t, e);
                } catch (Throwable ex) {
                    ex.printStackTrace(System.err);
                }
            });
        }

        @Override
        public void run() {
            while (!shutdown.get()) {
                try {
                    Thread.interrupted(); // clear any leftover interrupt
                    WorkItem item = workQueue.poll();
                    if (item == null) {
                        // Wait briefly for work
                        item = workQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (item == null) {
                            // If we're an extra worker (beyond core), consider terminating
                            if (workerCount.get() > corePoolSize) {
                                workerCount.decrementAndGet();
                                return;
                            }
                            continue;
                        }
                    }

                    FocessTask task = item.task;
                    if (task.isCancelled()) continue;

                    taskWorkerMap.put(task, this);
                    runningCount.incrementAndGet();
                    try {
                        task.run();
                    } catch (ExecutionException e) {
                        task.setException(e);
                    } finally {
                        Thread.interrupted(); // clear leaked interrupt
                    }
                    task.endRun();
                    taskWorkerMap.remove(task);
                    runningCount.decrementAndGet();
                    item.completionCallback.run();
                } catch (InterruptedException e) {
                    // shutdown or cancel — check flag
                }
            }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/top/focess/scheduler/TaskExecutor.java src/main/java/top/focess/scheduler/InlineExecutor.java src/main/java/top/focess/scheduler/PoolTaskExecutor.java
git commit -m "feat: add TaskExecutor interface with InlineExecutor and PoolTaskExecutor"
```

---

### Task 6: Scheduler Interface + AbstractScheduler + FocessScheduler + ThreadPoolScheduler

**Files:**
- Create: `src/main/java/top/focess/scheduler/Scheduler.java`
- Create: `src/main/java/top/focess/scheduler/AbstractScheduler.java`
- Create: `src/main/java/top/focess/scheduler/FocessScheduler.java`
- Create: `src/main/java/top/focess/scheduler/ThreadPoolScheduler.java`

**Interfaces:**
- Consumes: `Task`, `Callback`, `FocessTask`, `FocessCallback`, `Dispatcher`, `TimeDispatcher`, `TaskExecutor`, `InlineExecutor`, `PoolTaskExecutor`, `SchedulerClosedException`
- Produces: `Scheduler` interface (extends ExecutorService). `AbstractScheduler` abstract class. `FocessScheduler` and `ThreadPoolScheduler` concrete classes.

- [ ] **Step 1: Write the Scheduler interface**

```java
package top.focess.scheduler;

import org.jetbrains.annotations.Nullable;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Schedules tasks for execution, either immediately or after a delay.
 * Extends ExecutorService for compatibility with standard Java concurrency APIs.
 */
public interface Scheduler extends ExecutorService {

    /**
     * Schedules a task for immediate execution.
     */
    default Task schedule(Runnable runnable) {
        return this.schedule(runnable, Duration.ZERO);
    }

    /**
     * Schedules a task after the specified delay.
     */
    Task schedule(Runnable runnable, Duration delay);

    /**
     * Schedules a named task for immediate execution.
     */
    default Task schedule(Runnable runnable, String name) {
        return this.schedule(runnable, Duration.ZERO, name);
    }

    /**
     * Schedules a named task after the specified delay.
     */
    Task schedule(Runnable runnable, Duration delay, String name);

    /**
     * Schedules a named task after the specified delay with an exception handler.
     */
    Task schedule(Runnable runnable, Duration delay, String name, Consumer<ExecutionException> handler);

    /**
     * Schedules a periodic task.
     */
    Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period);

    /**
     * Schedules a named periodic task.
     */
    Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period, String name);

    /**
     * Schedules a named periodic task with an exception handler.
     */
    Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period, String name, Consumer<ExecutionException> handler);

    /**
     * Submits a callable for immediate execution.
     */
    default <V> Callback<V> submit(Callable<V> callable) {
        return this.submit(callable, Duration.ZERO);
    }

    /**
     * Submits a callable after the specified delay.
     */
    <V> Callback<V> submit(Callable<V> callable, Duration delay);

    /**
     * Submits a named callable for immediate execution.
     */
    default <V> Callback<V> submit(Callable<V> callable, String name) {
        return this.submit(callable, Duration.ZERO, name);
    }

    /**
     * Submits a named callable after the specified delay.
     */
    <V> Callback<V> submit(Callable<V> callable, Duration delay, String name);

    /**
     * Submits a named callable with an exception handler.
     */
    default <V> Callback<V> submit(Callable<V> callable, String name, Function<ExecutionException, V> handler) {
        return this.submit(callable, Duration.ZERO, name, handler);
    }

    /**
     * Submits a named callable after the specified delay with an exception handler.
     */
    <V> Callback<V> submit(Callable<V> callable, Duration delay, String name, Function<ExecutionException, V> handler);

    /**
     * Cancels all pending tasks in the queue.
     */
    void cancelPending();

    /**
     * Returns the name of this scheduler.
     */
    String getName();

    /**
     * Shuts this scheduler down gracefully.
     */
    void shutdown();

    /**
     * Returns whether this scheduler has been shut down.
     */
    boolean isShutdown();

    /**
     * Shuts this scheduler down immediately.
     */
    void shutdownNow();

    /**
     * Sets the uncaught exception handler for scheduler threads.
     */
    void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler);

    /**
     * Returns the uncaught exception handler, or null.
     */
    @Nullable Thread.UncaughtExceptionHandler getUncaughtExceptionHandler();

    /**
     * Returns the dispatcher used by this scheduler.
     */
    Dispatcher getDispatcher();

    /**
     * Returns the executor used by this scheduler.
     */
    TaskExecutor getTaskExecutor();
}
```

Note: `Scheduler` references `java.util.concurrent.ExecutionException` — the `schedule` and `scheduleAtFixedRate` methods use `Consumer<ExecutionException>` from `java.util.concurrent`.

- [ ] **Step 2: Write the AbstractScheduler class**

```java
package top.focess.scheduler;

import org.jetbrains.annotations.Nullable;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract scheduler that wires Dispatcher and Executor together.
 * Implements Scheduler (extends ExecutorService via AbstractExecutorService).
 */
public abstract class AbstractScheduler extends java.util.concurrent.AbstractExecutorService implements Scheduler {

    private final Dispatcher dispatcher;
    private final TaskExecutor executor;
    private final String name;
    private final AtomicReference<Thread.UncaughtExceptionHandler> uncaughtExceptionHandler = new AtomicReference<>();

    protected AbstractScheduler(Dispatcher dispatcher, TaskExecutor executor, String name) {
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.name = name;
    }

    // --- Scheduler API ---

    @Override
    public Task schedule(Runnable runnable, Duration delay) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, this);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    public Task schedule(Runnable runnable, Duration delay, String name) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, this, name);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    public Task schedule(Runnable runnable, Duration delay, String name, Consumer<ExecutionException> handler) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, this, name, handler);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    public Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, period, this);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    public Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period, String name) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, period, this, name);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    public Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period, String name, Consumer<ExecutionException> handler) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, period, this, name, handler);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    @Override
    public <V> Callback<V> submit(Callable<V> callable, Duration delay) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessCallback<V> callback = new FocessCallback<>(callable, this);
        callback.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(callback);
        return callback;
    }

    @Override
    public <V> Callback<V> submit(Callable<V> callable, Duration delay, String name) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessCallback<V> callback = new FocessCallback<>(callable, this, name);
        callback.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(callback);
        return callback;
    }

    @Override
    public <V> Callback<V> submit(Callable<V> callable, Duration delay, String name, Function<ExecutionException, V> handler) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessCallback<V> callback = new FocessCallback<>(callable, this, name, handler);
        callback.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(callback);
        return callback;
    }

    // --- ExecutorService submit overrides (return proper Future types) ---

    @Override
    public <V> Future<V> submit(Callable<V> task) {
        return submit(task, Duration.ZERO);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return schedule(task, Duration.ZERO);
    }

    @Override
    public <V> Future<V> submit(Runnable task, V result) {
        return submit(() -> { task.run(); return result; }, Duration.ZERO);
    }

    // --- Dispatcher ↔ Executor wiring ---

    /**
     * Called by the Dispatcher when a task's scheduled time has arrived.
     */
    void onTaskReady(FocessTask task) {
        executor.execute(task, () -> onTaskComplete(task));
    }

    /**
     * Called by the Executor when a task has finished execution.
     */
    void onTaskComplete(FocessTask task) {
        if (task.isPeriod() && !task.isCancelled()) {
            task.clear();
            task.setScheduledTime(System.nanoTime() + task.getPeriod().toNanos());
            if (!dispatcher.isShutdown()) {
                dispatcher.dispatch(task);
            } else {
                task.cancel(false);
            }
        }
    }

    // --- ExecutorService ---

    @Override
    public void execute(Runnable command) {
        schedule(command, Duration.ZERO);
    }

    @Override
    public void shutdown() {
        dispatcher.shutdown(false);
        executor.shutdown(false);
    }

    @Override
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
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining, 100_000_000L));
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return true;
    }

    // --- Cancellation ---

    protected void interruptTaskIfRunning(FocessTask task) {
        executor.interruptTask(task);
    }

    @Override
    public void cancelPending() {
        dispatcher.cancelPending();
    }

    // --- Name and UncaughtExceptionHandler ---

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler) {
        uncaughtExceptionHandler.set(handler);
    }

    @Override
    @Nullable
    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler.get();
    }

    @Override
    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public TaskExecutor getTaskExecutor() {
        return executor;
    }

    @Override
    public String toString() {
        return this.getName();
    }
}
```

- [ ] **Step 3: Write the FocessScheduler class**

```java
package top.focess.scheduler;

import java.util.UUID;

/**
 * Single-threaded scheduler: dispatching and execution happen on the same thread.
 * Uses TimeDispatcher + InlineExecutor internally.
 */
public class FocessScheduler extends AbstractScheduler {

    /**
     * Composable constructor — any Dispatcher with any TaskExecutor.
     */
    public FocessScheduler(Dispatcher dispatcher, TaskExecutor executor) {
        super(dispatcher, executor, deriveName(dispatcher));
    }

    /**
     * Single-thread convenience constructor.
     */
    public FocessScheduler(String name) {
        this(name, false);
    }

    /**
     * Single-thread convenience constructor with daemon option.
     */
    public FocessScheduler(String name, boolean isDaemon) {
        super(new TimeDispatcher(name, isDaemon), new InlineExecutor(), name);
        // Wire the scheduler reference after construction
        ((TimeDispatcher) getDispatcher()).setScheduler(this);
    }

    /**
     * Auto-named constructor.
     */
    public static FocessScheduler newPrefixScheduler(String prefix) {
        return new FocessScheduler(prefix + "-FocessScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String deriveName(Dispatcher dispatcher) {
        return dispatcher.toString();
    }
}
```

**Note:** The convenience constructors use a two-phase init: `super()` creates the Dispatcher and Executor, then `setScheduler(this)` wires the scheduler reference. This avoids the "leaking this in constructor" problem because the dispatcher thread blocks on `queue.take()` and won't call `onTaskReady()` until a task is dispatched, which only happens after the constructor completes.

- [ ] **Step 4: Write the ThreadPoolScheduler class**

```java
package top.focess.scheduler;

import java.util.UUID;

/**
 * Multi-threaded scheduler: a dispatcher thread hands tasks to a pool of workers.
 * Uses TimeDispatcher + PoolTaskExecutor internally.
 */
public class ThreadPoolScheduler extends AbstractScheduler {

    /**
     * Composable constructor — any Dispatcher with any TaskExecutor.
     */
    public ThreadPoolScheduler(Dispatcher dispatcher, TaskExecutor executor) {
        super(dispatcher, executor, deriveName(dispatcher));
    }

    /**
     * Thread-pool convenience constructor.
     */
    public ThreadPoolScheduler(int poolSize, boolean immediate, String name) {
        this(poolSize, immediate, name, false);
    }

    /**
     * Thread-pool convenience constructor with daemon option.
     */
    public ThreadPoolScheduler(int poolSize, boolean immediate, String name, boolean isDaemon) {
        super(new TimeDispatcher(name, isDaemon),
              new PoolTaskExecutor(poolSize, immediate, name), name);
        // Wire the scheduler reference after construction
        ((TimeDispatcher) getDispatcher()).setScheduler(this);
        ((PoolTaskExecutor) getTaskExecutor()).setScheduler(this);
    }

    /**
     * Auto-named constructor.
     */
    public ThreadPoolScheduler(String prefix, int poolSize) {
        this(poolSize, false, prefix + "-ThreadPoolScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String deriveName(Dispatcher dispatcher) {
        return dispatcher.toString();
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/focess/scheduler/Scheduler.java src/main/java/top/focess/scheduler/AbstractScheduler.java src/main/java/top/focess/scheduler/FocessScheduler.java src/main/java/top/focess/scheduler/ThreadPoolScheduler.java
git commit -m "feat: add Scheduler, AbstractScheduler, FocessScheduler, ThreadPoolScheduler"
```

---

### Task 7: Exceptions (update for new types)

**Files:**
- Modify: `src/main/java/top/focess/scheduler/exceptions/SchedulerClosedException.java`
- Modify: `src/main/java/top/focess/scheduler/exceptions/TaskNotFinishedException.java`
- Modify: `src/main/java/top/focess/scheduler/exceptions/PeriodTaskException.java`

**Interfaces:**
- Consumes: `Scheduler`, `Callback`, `Task` (new interface types)
- Produces: Updated exception classes referencing new type names

- [ ] **Step 1: Update SchedulerClosedException**

```java
package top.focess.scheduler.exceptions;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.Scheduler;

/**
 * Thrown when an operation is attempted on a scheduler that has been shut down.
 */
public class SchedulerClosedException extends IllegalStateException {

    public SchedulerClosedException(@NotNull final Scheduler scheduler) {
        super("Scheduler " + scheduler.getName() + " is closed.");
    }
}
```

- [ ] **Step 2: Update TaskNotFinishedException**

```java
package top.focess.scheduler.exceptions;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.Callback;

/**
 * Thrown when Callback.getNow() is invoked on a task that has not yet finished.
 */
public class TaskNotFinishedException extends IllegalStateException {

    public <V> TaskNotFinishedException(@NotNull final Callback<V> callback) {
        super("Task " + callback.getName() + " is not finished.");
    }
}
```

- [ ] **Step 3: Update PeriodTaskException**

```java
package top.focess.scheduler.exceptions;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.Task;

/**
 * Thrown when a period task is added to a task pool.
 */
public class PeriodTaskException extends IllegalStateException {

    public PeriodTaskException(@NotNull final Task task) {
        super("Period task " + task.getName() + " cannot be added to a task pool. Task pools are for non-period tasks only.");
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/top/focess/scheduler/exceptions/
git commit -m "refactor: update exception classes for new type names"
```

---

### Task 8: TaskPool + AndTaskPool + OrTaskPool (Composable Strategy)

**Files:**
- Create: `src/main/java/top/focess/scheduler/TaskPool.java`
- Create: `src/main/java/top/focess/scheduler/AndTaskPool.java`
- Create: `src/main/java/top/focess/scheduler/OrTaskPool.java`

**Interfaces:**
- Consumes: `Task`, `Scheduler`, `PeriodTaskException`
- Produces: `TaskPool` with `CompletionStrategy`, `addTask(Task)`, `join()`, `isFinished()`. `AndTaskPool` and `OrTaskPool` convenience classes.

- [ ] **Step 1: Write TaskPool**

```java
package top.focess.scheduler;

import top.focess.scheduler.exceptions.PeriodTaskException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A composable task pool that groups tasks and completes based on a
 * pluggable CompletionStrategy.
 * <p>
 * Tasks don't know about pools. Pools observe task completion through
 * the Task.onComplete() listener.
 */
public class TaskPool {

    /**
     * Strategy that determines when a pool is "complete".
     */
    @FunctionalInterface
    public interface CompletionStrategy {
        /**
         * Called when a task in the pool finishes.
         *
         * @param pool      the pool
         * @param task      the task that finished
         * @param remaining the number of tasks still pending in the pool
         * @return true if the pool should be marked complete
         */
        boolean onComplete(TaskPool pool, Task task, int remaining);
    }

    /** All tasks must complete. */
    public static final CompletionStrategy ALL = (pool, task, remaining) -> remaining == 0;
    /** Any task completing triggers pool completion. */
    public static final CompletionStrategy ANY = (pool, task, remaining) -> true;

    private final Scheduler scheduler;
    private final CompletionStrategy strategy;
    private final Runnable callback;
    private final AtomicInteger remaining = new AtomicInteger(0);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private volatile Task callbackTask;

    public TaskPool(Scheduler scheduler, CompletionStrategy strategy, Runnable callback) {
        this.scheduler = scheduler;
        this.strategy = strategy;
        this.callback = callback;
    }

    /**
     * Add a non-periodic task to this pool.
     */
    public void addTask(Task task) {
        if (task.isPeriod()) throw new PeriodTaskException(task);
        remaining.incrementAndGet();
        // Use AtomicBoolean to ensure onTaskComplete fires exactly once
        AtomicBoolean fired = new AtomicBoolean(false);
        task.onComplete(() -> {
            if (fired.compareAndSet(false, true)) {
                onTaskComplete(task);
            }
        });
    }

    private void onTaskComplete(Task task) {
        int left = remaining.decrementAndGet();
        if (strategy.onComplete(this, task, left)) {
            markFinished();
        }
    }

    protected void markFinished() {
        if (finished.compareAndSet(false, true)) {
            if (callback != null) callbackTask = scheduler.schedule(callback);
            completionLatch.countDown();
        }
    }

    /**
     * Wait for the pool's completion condition, then for the callback task if any.
     */
    public void join() throws ExecutionException, InterruptedException {
        completionLatch.await();
        if (callbackTask != null) callbackTask.join();
    }

    /**
     * Whether the pool's completion condition has been met.
     */
    public boolean isFinished() {
        return finished.get();
    }
}
```

- [ ] **Step 2: Write AndTaskPool**

```java
package top.focess.scheduler;

/**
 * Task pool that completes when ALL tasks have finished.
 */
public class AndTaskPool extends TaskPool {

    public AndTaskPool(Scheduler scheduler, Runnable callback) {
        super(scheduler, ALL, callback);
    }
}
```

- [ ] **Step 3: Write OrTaskPool**

```java
package top.focess.scheduler;

/**
 * Task pool that completes when ANY task has finished.
 */
public class OrTaskPool extends TaskPool {

    public OrTaskPool(Scheduler scheduler, Runnable callback) {
        super(scheduler, ANY, callback);
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/top/focess/scheduler/TaskPool.java src/main/java/top/focess/scheduler/AndTaskPool.java src/main/java/top/focess/scheduler/OrTaskPool.java
git commit -m "feat: add composable TaskPool with CompletionStrategy pattern"
```

---

### Task 9: Delete old files + remove Guava from pom.xml

**Files:**
- Delete: `src/main/java/top/focess/scheduler/AScheduler.java`
- Delete: `src/main/java/top/focess/scheduler/ITask.java`
- Delete: `src/main/java/top/focess/scheduler/ThreadPoolSchedulerThread.java`
- Modify: `pom.xml` (remove Guava dependency)

- [ ] **Step 1: Delete old files**

```bash
rm src/main/java/top/focess/scheduler/AScheduler.java
rm src/main/java/top/focess/scheduler/ITask.java
rm src/main/java/top/focess/scheduler/ThreadPoolSchedulerThread.java
```

- [ ] **Step 2: Remove Guava from pom.xml**

Remove the Guava dependency block and the guava.version property from pom.xml. The remaining dependencies are JetBrains annotations and JUnit.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: remove old files (AScheduler, ITask, ThreadPoolSchedulerThread) and Guava dependency"
```

---

### Task 10: Rewrite tests for new API

**Files:**
- Modify: `src/test/java/top/focess/scheduler/TestUtil.java`

**Interfaces:**
- Consumes: All new classes: `Scheduler`, `FocessScheduler`, `ThreadPoolScheduler`, `Task`, `Callback`, `TaskPool`, `AndTaskPool`, `OrTaskPool`

- [ ] **Step 1: Rewrite TestUtil.java with the new API**

The test class must be fully rewritten to use the new method names (`schedule` instead of `run`, `isDone` instead of `isFinished`, `cancelPending` instead of `cancelAll`, `getNow` instead of `call`) and verify the same behaviors as before. Key tests to port:

1. `testScheduler` — basic FocessScheduler: schedule, join, cancel, delayed tasks
2. `testScheduler2` — ThreadPoolScheduler: multiple tasks, cancel(true)
3. `testSchedulerTaskException` — task throwing exception, join throws ExecutionException
4. `testSchedulerExceptionHandler` — exception handler consumes the exception
5. `testSchedulerInternalErrorShutsDown` — uncaught error shuts down scheduler
6. `testScheduler4` — ThreadPoolScheduler with immediate=true and delayed tasks
7. `testTaskPool` — OrTaskPool: any task completes the pool
8. `testFocessScheduler` — delayed tasks execute in time order
9. `testFocessSchedulerCancelRunning` — cancel(true) interrupts running task
10. `testScheduler5` — periodic task with exception continues running
11. `testCancelPendingTask` — cancel(false) on pending task
12. `testCancelFinishedTask` — cancel on finished task returns false
13. `testCancelRunningWithoutInterrupt` — cancel(false) on running task
14. `testJoinCancelledThrows` — join on cancelled task throws CancellationException
15. `testThreadPoolCancelRunningIsolated` — interrupt isolation
16. `testCancelRunningSwallowInterrupt` — cooperative cancellation
17. `testInterruptDoesNotLeakToNextTask` — no interrupt leak between tasks
18. `testFocessSchedulerCancelPendingKeepsRunning` — cancel pending doesn't disturb running

- [ ] **Step 2: Run tests**

```bash
mvn test
```

Expected: All tests pass.

- [ ] **Step 3: Fix any compilation or test failures**

Iterate until all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/top/focess/scheduler/TestUtil.java
git commit -m "test: rewrite tests for v2.1.0 API (schedule, isDone, cancelPending)"
```

---

### Task 11: Bump version and update README

**Files:**
- Modify: `pom.xml` (version 2.0.0 → 2.1.0)
- Modify: `README.md`

- [ ] **Step 1: Update version in pom.xml**

Change `<version>2.0.0</version>` to `<version>2.1.0</version>`.

- [ ] **Step 2: Update README.md**

Update the README to reflect the new API:
- `run()` → `schedule()`
- `runTimer()` → `scheduleAtFixedRate()`
- `isFinished()` → `isDone()`
- `cancelAll()` → `cancelPending()`
- `callback.call()` → `callback.getNow()`
- Add ExecutorService compatibility note
- Add Dispatcher/Executor composability section
- Add TaskPool CompletionStrategy section
- Update version numbers in Maven/Gradle snippets

- [ ] **Step 3: Commit**

```bash
git add pom.xml README.md
git commit -m "docs: bump version to 2.1.0 and update README for new API"
```
