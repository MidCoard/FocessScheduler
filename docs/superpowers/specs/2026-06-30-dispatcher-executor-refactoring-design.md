# FocessScheduler 2.1.0 — Dispatcher-Executor Refactoring Design

**Date:** 2026-06-30
**Version target:** 2.1.0
**Approach:** Clean-slate internal rewrite, preserving public API semantics

---

## 1. Overview

Refactor FocessScheduler from a monolithic `synchronized`/monitor-based architecture to a composable **Dispatcher + Executor** model with **CAS-based state management** and full **`ExecutorService`** compatibility.

### Goals

1. **Dispatcher-Executor separation** — scheduling (when) is independent from execution (how)
2. **CAS-first concurrency** — prefer `AtomicReference`/`AtomicLong`/`CountDownLatch` over `synchronized`/`wait/notify`; use locks only where CAS is not natural
3. **Full `ExecutorService` compatibility** — `Scheduler extends ExecutorService`
4. **Composability** — any `Dispatcher` works with any `TaskExecutor` through the Scheduler's wiring API
5. **Remove Guava dependency** — replace with `java.util.concurrent` equivalents
6. **Switch to `System.nanoTime()`** — immune to wall-clock adjustments

### Non-goals

- Backward API compatibility at the method-name level (renaming `run` → `schedule`, `isFinished` → `isDone`, etc.)
- Supporting Java versions below 17
- Changing the cooperative cancellation model

---

## 2. Architecture

### 2.1 Component Diagram

```
┌──────────────────────────────────────────────────┐
│                   Scheduler                       │
│  (composition root — wires Dispatcher + Executor) │
│  implements ExecutorService                       │
│                                                   │
│   ┌────────────┐    task ready    ┌───────────┐  │
│   │ Dispatcher  │ ──────────────→ │  Executor  │  │
│   │             │  (via Scheduler) │            │  │
│   │ dispatch()  │                 │ execute()  │  │
│   │ cancelPend()│                 │ interrupt()│  │
│   │ shutdown()  │                 │ shutdown() │  │
│   └────────────┘                 └─────┬──────┘  │
│         ↑                               │         │
│         │  re-dispatch periodic         │         │
│         └───────────────────────────────┘         │
│              (via Scheduler callback)             │
└──────────────────────────────────────────────────┘
```

### 2.2 Communication Flow

```
1. User calls scheduler.schedule(runnable, delay)
2. Scheduler creates FocessTask, calls dispatcher.dispatch(task)
3. Dispatcher waits until task's scheduled time arrives (via DelayQueue)
4. Dispatcher calls scheduler.onTaskReady(task)
5. Scheduler calls executor.execute(task, completionCallback)
6. Executor runs the task
7. Executor calls completionCallback.run()
8. Scheduler.onTaskComplete(task) — re-dispatches if periodic
```

Dispatcher and Executor never reference each other directly. The Scheduler mediates all communication.

### 2.3 Composability

Any Dispatcher works with any TaskExecutor. Convenience constructors provide common setups:

```java
// Single-thread (1 thread: dispatcher + executor inline)
Scheduler s1 = new FocessScheduler("name");
// Internally: new TimeDispatcher("name") + new InlineExecutor("name")

// Thread-pool (1 dispatcher thread + N worker threads)
Scheduler s2 = new ThreadPoolScheduler(4, false, "name");
// Internally: new TimeDispatcher("name") + new PoolTaskExecutor(4, false, "name")

// Custom composition
Scheduler s3 = new FocessScheduler(customDispatcher, customExecutor);
```

---

## 3. Task State Management (CAS-based)

### 3.1 State Machine

```
PENDING ──CAS──→ RUNNING ──CAS──→ FINISHED
   │                                     ↑
   └────CAS──→ CANCELLED ←──CAS─────────┘
                  ↑
              RUNNING (cancel(true))
```

CAS transitions enforce correctness:
- `PENDING → RUNNING`: Only the dispatcher (via `onTaskReady`)
- `RUNNING → FINISHED`: Only the executing thread (via `endRun`)
- `PENDING/RUNNING → CANCELLED`: Any thread (via `cancel`)
- `FINISHED → PENDING`: Only for periodic tasks (via `clear` for re-scheduling)
- CAS failure = someone else transitioned first → return false or retry

### 3.2 Implementation

```java
enum TaskState {
    PENDING, RUNNING, FINISHED, CANCELLED
}

class FocessTask implements TaskInternal, Comparable<FocessTask>, Delayed {
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.PENDING);
    private final AtomicLong scheduledTime = new AtomicLong();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final CopyOnWriteArrayList<Runnable> completionListeners = new CopyOnWriteArrayList<>();

    boolean casState(TaskState expected, TaskState newValue) {
        return state.compareAndSet(expected, newValue);
    }

    TaskState getState() { return state.get(); }

    boolean cancel(boolean mayInterruptIfRunning) {
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
                return true;
            }
            // CAS failed — retry
        }
    }

    void endRun() {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.FINISHED)) {
            completionLatch.countDown();
            fireCompletionListeners();
        }
    }

    void fireCompletionListeners() {
        for (Runnable listener : completionListeners) {
            try { listener.run(); }
            catch (Throwable ignored) { /* don't let one bad listener break others */ }
        }
    }

    void onComplete(Runnable listener) {
        completionListeners.add(listener);
        if (isDone()) {
            // Double-check: if task completed between add and this check,
            // fire the listener. If it was already fired by endRun(),
            // the listener may be called twice — use a fire-once wrapper.
            listener.run();
        }
    }

    // In practice, callers should wrap listeners in a fire-once wrapper:
    // task.onComplete(RunnableOnce.wrap(() -> ...));

    void join() throws InterruptedException, ExecutionException, CancellationException {
        completionLatch.await();
        if (exception != null) throw exception;
        if (isCancelled()) throw new CancellationException("Task is cancelled");
    }

    void join(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
            CancellationException, TimeoutException {
        if (!completionLatch.await(timeout, unit))
            throw new TimeoutException("Task is not finished in " + timeout + " " + unit.name());
        if (exception != null) throw exception;
        if (isCancelled()) throw new CancellationException("Task is cancelled");
    }

    // Delayed interface for DelayQueue
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(scheduledTime.get() - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        return Long.compare(this.scheduledTime.get(), ((FocessTask) o).scheduledTime.get());
    }
}
```

### 3.3 Lock Strategy Summary

| Component | Mechanism | Why |
|---|---|---|
| Task state transitions | `AtomicReference<TaskState>` + CAS | Lock-free, no contention on the common path |
| Scheduled time | `AtomicLong` | Lock-free reads/writes |
| Join/completion | `CountDownLatch` | Lock-free await, no synchronized wait/notify |
| Completion listeners | `CopyOnWriteArrayList<Runnable>` | Read-heavy (fire on completion), write-rare (register) |
| Dispatcher queue | `DelayQueue` (thread-safe) | Built-in concurrency, no external lock needed |
| Executor work queue | `LinkedBlockingQueue` (thread-safe) | Built-in concurrency |
| TaskPool finish flag | `AtomicBoolean` + CAS | Lock-free completion check |
| TaskPool join | `CountDownLatch` | Lock-free await |
| TaskPool remaining count | `AtomicInteger` | Lock-free decrement |

Locks are used only where CAS is not natural (e.g., complex multi-step operations in TaskPool that need atomicity across multiple fields).

---

## 4. Dispatcher

### 4.1 Interface

```java
public interface Dispatcher {
    /** Submit a task for dispatching. */
    void dispatch(FocessTask task);

    /** Cancel all pending (not-yet-dispatched) tasks. */
    void cancelPending();

    /** Shutdown the dispatcher. */
    void shutdown(boolean now);

    /** Whether the dispatcher has been shut down. */
    boolean isShutdown();
}
```

### 4.2 TimeDispatcher

Uses `java.util.concurrent.DelayQueue` for thread-safe, lock-free priority scheduling:

```java
public class TimeDispatcher implements Dispatcher {
    private final DelayQueue<FocessTask> queue = new DelayQueue<>();
    private final Scheduler scheduler;
    private final Thread thread;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public TimeDispatcher(String name, boolean isDaemon, Scheduler scheduler) {
        this.scheduler = scheduler;
        this.thread = new Thread(this::runLoop, name + "-dispatcher");
        this.thread.setDaemon(isDaemon);
        this.thread.start();
    }

    void runLoop() {
        while (!shutdown.get()) {
            try {
                FocessTask task = queue.take();  // blocks until time arrives
                if (task.isCancelled()) continue;
                if (!task.casState(TaskState.PENDING, TaskState.RUNNING)) continue;
                // Hand to scheduler, which delegates to executor
                scheduler.onTaskReady(task);
            } catch (InterruptedException e) {
                // shutdownNow() or cancel signal — loop will check shutdown flag
            }
        }
        // Drain remaining tasks on shutdown
        drainAndCancel();
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
        shutdown.set(true);
        if (now) thread.interrupt();
        else {
            cancelPending();
            thread.interrupt();  // wake from queue.take()
        }
    }
}
```

---

## 5. Executor

### 5.1 Interface

```java
public interface TaskExecutor {
    /** Execute a task. Call completionCallback when done. */
    void execute(FocessTask task, Runnable completionCallback);

    /** Interrupt the thread currently running the given task. */
    void interruptTask(FocessTask task);

    /** Shutdown the executor. */
    void shutdown(boolean now);

    /** Whether the executor has been shut down. */
    boolean isShutdown();

    /** Whether the executor has no running tasks. */
    boolean isIdle();
}
```

### 5.2 InlineExecutor

Runs tasks on the dispatcher's thread (single-thread scheduler):

```java
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
            Thread.interrupted();  // clear leaked interrupt
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
    public boolean isShutdown() { return shutdown.get(); }

    @Override
    public boolean isIdle() { return currentTask == null; }
}
```

### 5.3 PoolTaskExecutor

Multi-threaded executor with a shared work queue:

```java
public class PoolTaskExecutor implements TaskExecutor {
    private final LinkedBlockingQueue<WorkItem> workQueue = new LinkedBlockingQueue<>();
    private final Set<Worker> workers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<FocessTask, Worker> taskWorkerMap = new ConcurrentHashMap<>();
    private final int corePoolSize;
    private final boolean immediate;
    private final String name;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    static class WorkItem {
        final FocessTask task;
        final Runnable completionCallback;
        WorkItem(FocessTask task, Runnable completionCallback) {
            this.task = task;
            this.completionCallback = completionCallback;
        }
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

    class Worker extends Thread {
        Worker(String name) {
            super(name);
            setDaemon(true);
            setUncaughtExceptionHandler((t, e) -> {
                // Handle uncaught exception — set on task, shutdown scheduler
                handleUncaughtException(t, e);
            });
        }

        void run() {
            while (!shouldStop()) {
                try {
                    WorkItem item = workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS);
                    if (item == null) {
                        maybeTerminateWorker();
                        continue;
                    }
                    taskWorkerMap.put(item.task, this);
                    try {
                        item.task.run();
                    } catch (ExecutionException e) {
                        item.task.setException(e);
                    } finally {
                        Thread.interrupted();
                    }
                    item.task.endRun();
                    taskWorkerMap.remove(item.task);
                    item.completionCallback.run();
                } catch (InterruptedException e) {
                    // shutdown or cancel — check flag
                }
            }
            workers.remove(this);
        }
    }
}
```

---

## 6. Scheduler (Composition Root)

### 6.1 AbstractScheduler

```java
public abstract class AbstractScheduler extends AbstractExecutorService implements Scheduler {
    protected final Dispatcher dispatcher;
    protected final TaskExecutor executor;
    private final String name;

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
    public Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessTask task = new FocessTask(runnable, period, this);
        task.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(task);
        return task;
    }

    // ... other schedule/submit overloads follow the same pattern

    @Override
    public <V> Callback<V> submit(Callable<V> callable, Duration delay) {
        if (dispatcher.isShutdown()) throw new SchedulerClosedException(this);
        FocessCallback<V> callback = new FocessCallback<>(callable, this);
        callback.setScheduledTime(System.nanoTime() + delay.toNanos());
        dispatcher.dispatch(callback);
        return callback;
    }

    // --- Dispatcher → Executor wiring ---

    /** Called by the Dispatcher when a task's scheduled time has arrived. */
    void onTaskReady(FocessTask task) {
        executor.execute(task, () -> onTaskComplete(task));
    }

    /** Called by the Executor when a task has finished execution. */
    void onTaskComplete(FocessTask task) {
        if (task.isPeriod() && !task.isCancelled()) {
            task.casState(TaskState.FINISHED, TaskState.PENDING);
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
        return Collections.emptyList();  // remaining tasks are cancelled, not returned
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
            LockSupport.parkNanos(Math.min(remaining, 100_000_000L));  // 100ms max poll
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
}
```

### 6.2 FocessScheduler (single-thread convenience)

```java
public class FocessScheduler extends AbstractScheduler {

    public FocessScheduler(Dispatcher dispatcher, TaskExecutor executor) {
        super(dispatcher, executor, deriveName(dispatcher, executor));
    }

    public FocessScheduler(String name) {
        this(name, false);
    }

    public FocessScheduler(String name, boolean isDaemon) {
        super(new TimeDispatcher(name, isDaemon, /* scheduler ref */),
              new InlineExecutor(),
              name);
    }

    public static FocessScheduler newPrefixScheduler(String prefix) {
        return new FocessScheduler(prefix + "-FocessScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
```

### 6.3 ThreadPoolScheduler (multi-thread convenience)

```java
public class ThreadPoolScheduler extends AbstractScheduler {

    public ThreadPoolScheduler(Dispatcher dispatcher, TaskExecutor executor) {
        super(dispatcher, executor, deriveName(dispatcher, executor));
    }

    public ThreadPoolScheduler(int poolSize, boolean immediate, String name) {
        this(poolSize, immediate, name, false);
    }

    public ThreadPoolScheduler(int poolSize, boolean immediate, String name, boolean isDaemon) {
        super(new TimeDispatcher(name, isDaemon, /* scheduler ref */),
              new PoolTaskExecutor(poolSize, immediate, name),
              name);
    }

    public ThreadPoolScheduler(String prefix, int poolSize) {
        this(poolSize, false, prefix + "-ThreadPoolScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
```

---

## 7. Interface Changes

### 7.1 Task (renamed methods)

```java
public interface Task extends Future<Void> {
    boolean cancel(boolean mayInterruptIfRunning);  // from Future
    boolean isCancelled();                           // from Future
    boolean isDone();                                // from Future (was isFinished)
    boolean isRunning();
    Scheduler getScheduler();
    String getName();
    boolean isPeriod();
    void join() throws ExecutionException, InterruptedException, CancellationException;
    void join(long timeout, TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException;
    void setExceptionHandler(Consumer<ExecutionException> handler);
    void onComplete(Runnable listener);              // generic completion listener
}
```

### 7.2 Callback (renamed methods)

```java
public interface Callback<V> extends Task, Future<V> {
    V getNow() throws ExecutionException, CancellationException, TaskNotFinishedException;  // was call()
    void setExceptionHandler(Function<ExecutionException, V> handler);

    // Future.get() → join() + getNow()
    @Override default V get() throws InterruptedException, ExecutionException {
        join();
        return getNow();
    }

    @Override default V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException, CancellationException {
        join(timeout, unit);
        return getNow();
    }
}
```

### 7.3 Scheduler (renamed methods, extends ExecutorService)

```java
public interface Scheduler extends ExecutorService {
    Task schedule(Runnable runnable, Duration delay);                    // was run(r, delay)
    Task schedule(Runnable runnable, Duration delay, String name);       // was run(r, delay, name)
    Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period);  // was runTimer
    Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period, String name);
    Task scheduleAtFixedRate(Runnable runnable, Duration delay, Duration period, String name, Consumer<ExecutionException> handler);
    Task schedule(Runnable runnable, Duration delay, String name, Consumer<ExecutionException> handler);
    <V> Callback<V> submit(Callable<V> callable, Duration delay);
    <V> Callback<V> submit(Callable<V> callable, Duration delay, String name);
    <V> Callback<V> submit(Callable<V> callable, Duration delay, String name, Function<ExecutionException, V> handler);
    void cancelPending();   // was cancelAll
    String getName();
    Dispatcher getDispatcher();
    TaskExecutor getTaskExecutor();
}
```

### 7.4 TaskInternal (was ITask)

```java
interface TaskInternal extends Task {
    void run() throws ExecutionException;
    Duration getPeriod();
    void clear();
    void startRun();
    void endRun();
    void setException(ExecutionException e);
    ExecutionException getException();  // for custom TaskPool strategies
}
```

Note: `addTaskPool`/`removeTaskPool` removed — replaced by `Task.onComplete(Runnable)`.

---

## 8. TaskPool Redesign — Composable Strategy

### 8.1 Current Problems

1. `ITask.addTaskPool()/removeTaskPool()` — tasks know about pools, tight coupling
2. `TaskPool.finishTask()` is called from `FocessTask.endRun()` — tasks drive pool logic
3. Only two strategies (And, Or) — hard to extend
4. `synchronized` everywhere in TaskPool

### 8.2 New Design: Completion Listener + Strategy

**Principle:** Tasks don't know about pools. Pools observe task completion through a generic `onComplete` listener, using a pluggable `CompletionStrategy` for completion semantics.

#### Task Completion Listener

```java
public interface Task extends Future<Void> {
    // ... existing methods ...

    /**
     * Register a callback to be invoked when this task completes
     * (finishes, is cancelled, or throws an exception).
     */
    void onComplete(Runnable listener);
}
```

In `FocessTask`:

```java
class FocessTask {
    private final CopyOnWriteArrayList<Runnable> completionListeners = new CopyOnWriteArrayList<>();

    void onComplete(Runnable listener) {
        if (isDone()) {
            listener.run();  // already done, call immediately
        } else {
            completionListeners.add(listener);
        }
    }

    void endRun() {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.FINISHED)) {
            completionLatch.countDown();
            fireCompletionListeners();
        }
    }

    private void fireCompletionListeners() {
        for (Runnable listener : completionListeners) {
            try { listener.run(); }
            catch (Throwable ignored) { /* don't let one bad listener break others */ }
        }
    }
}
```

#### TaskPool with CompletionStrategy

```java
public class TaskPool {

    @FunctionalInterface
    public interface CompletionStrategy {
        /**
         * @param pool      the pool
         * @param task      the task that finished
         * @param remaining the number of tasks still pending in the pool
         * @return true if the pool should be marked complete
         */
        boolean onComplete(TaskPool pool, Task task, int remaining);
    }

    // Built-in strategies
    public static final CompletionStrategy ALL = (pool, task, remaining) -> remaining == 0;
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

    public void addTask(Task task) {
        if (task.isPeriod()) throw new PeriodTaskException(task);
        remaining.incrementAndGet();
        // Use AtomicBoolean to ensure onTaskComplete fires exactly once
        // (onComplete may fire the listener twice in a race)
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

    public void join() throws ExecutionException, InterruptedException {
        completionLatch.await();
        if (callbackTask != null) callbackTask.join();
    }

    public boolean isFinished() {
        return finished.get();
    }
}
```

#### Convenience Subclasses

```java
public class AndTaskPool extends TaskPool {
    public AndTaskPool(Scheduler scheduler, Runnable callback) {
        super(scheduler, ALL, callback);
    }
}

public class OrTaskPool extends TaskPool {
    public OrTaskPool(Scheduler scheduler, Runnable callback) {
        super(scheduler, ANY, callback);
    }
}
```

#### Custom Strategy Examples

```java
// N-of-M: 3 out of 5 must complete
AtomicInteger count = new AtomicInteger(0);
TaskPool nOfM = new TaskPool(scheduler, (pool, task, remaining) -> count.incrementAndGet() >= 3, callback);

// First-success: first task that doesn't throw
TaskPool firstSuccess = new TaskPool(scheduler, (pool, task, remaining) -> {
    if (((TaskInternal)task).getException() == null) return true;
    if (remaining == 0) return true;  // all failed, complete anyway
    return false;
}, callback);
```

#### Removed

- `ITask.addTaskPool(TaskPool)` — replaced by `Task.onComplete(Runnable)`
- `ITask.removeTaskPool(TaskPool)` — replaced by fire-once `onComplete` semantics
- `TASK_POOL_LOCK` — eliminated entirely

---

## 9. Remove Guava Dependency

All Guava usages replaced:

| Guava | Replacement |
|---|---|
| `Lists.newArrayList()` | `new ArrayList<>()` |
| `Lists.newCopyOnWriteArrayList()` | `new CopyOnWriteArrayList<>()` |
| `Maps.newConcurrentMap()` | `new ConcurrentHashMap<>()` |
| `Sets.newHashSet()` | `ConcurrentHashMap.newKeySet()` or `new HashSet<>()` |

Remove Guava from `pom.xml`.

---

## 10. Timing: Switch to nanoTime

All scheduling uses `System.nanoTime()` instead of `System.currentTimeMillis()`. This makes tasks immune to wall-clock adjustments (NTP sync, manual clock changes). `DelayQueue` natively works with nanosecond precision.

---

## 11. File Structure

```
src/main/java/top/focess/scheduler/
├── Scheduler.java              (interface, extends ExecutorService)
├── AbstractScheduler.java      (was AScheduler)
├── FocessScheduler.java        (single-thread convenience)
├── ThreadPoolScheduler.java    (multi-thread convenience)
├── Task.java                   (interface, extends Future<Void>)
├── TaskInternal.java           (was ITask)
├── FocessTask.java             (CAS-based, implements Delayed)
├── Callback.java               (interface)
├── FocessCallback.java         (extends FocessTask)
├── TaskPool.java               (CAS-based)
├── AndTaskPool.java
├── OrTaskPool.java
├── Dispatcher.java             (new interface)
├── TimeDispatcher.java         (new, uses DelayQueue)
├── TaskExecutor.java           (new interface)
├── InlineExecutor.java         (new, runs on dispatcher thread)
├── PoolTaskExecutor.java       (new, multi-threaded)
└── exceptions/
    ├── SchedulerClosedException.java
    ├── TaskNotFinishedException.java
    └── PeriodTaskException.java
```

Removed files:
- `AScheduler.java` → `AbstractScheduler.java`
- `ITask.java` → `TaskInternal.java`
- `ThreadPoolSchedulerThread.java` → absorbed into `PoolTaskExecutor.Worker`

---

## 12. Behavior Preservation

The following behaviors from v2.0.0 are preserved:

1. **Cooperative cancellation** — `cancel(true)` interrupts the executing thread but doesn't forcibly stop it
2. **Interrupt isolation** — interrupting one task doesn't leak to another task on the same worker
3. **Periodic task exception handling** — period tasks continue after exceptions; exception is preserved
4. **Uncaught exception → scheduler shutdown** — an uncaught Error/Exception in a worker shuts down the entire scheduler
5. **Single-thread ordering** — FocessScheduler executes tasks in time order
6. **Dynamic pool expansion** — `immediate=true` creates new workers when all are busy
7. **Daemon thread option** — `isDaemon` parameter controls dispatcher thread daemon status
