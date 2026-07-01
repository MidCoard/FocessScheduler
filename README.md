# FocessScheduler

A lightweight, composable task scheduling library for Java with a Dispatcher-Executor architecture.

Two scheduler implementations are provided:

- **`FocessScheduler`** — single-threaded, dispatching and execution happen on the same thread.
- **`ThreadPoolScheduler`** — multi-threaded, a dispatcher thread hands tasks to a pool of workers.

Both are fully composable: any `Dispatcher` works with any `TaskExecutor` through the Scheduler's wiring API. Both implement `ExecutorService` for standard Java compatibility.

## Quick Start

### Maven

```xml
<dependency>
    <groupId>top.focess</groupId>
    <artifactId>focess-scheduler</artifactId>
    <version>2.1.0</version>
</dependency>
```

### Gradle

```gradle
implementation 'top.focess:focess-scheduler:2.1.0'
```

## Usage

```java
// Create a single-threaded scheduler
Scheduler scheduler = new FocessScheduler("my-scheduler");

// Schedule a task for immediate execution
Task task = scheduler.schedule(() -> System.out.println("Hello"));

// Schedule a task after a delay
Task delayed = scheduler.schedule(() -> System.out.println("World"), Duration.ofSeconds(2));

// Schedule a named task with an exception handler
Task handled = scheduler.schedule(
    () -> System.out.println("Named"),
    Duration.ZERO,
    "my-task",
    ex -> System.err.println("Failed: " + ex.getCause().getMessage())
);

// Schedule a periodic task
Task timer = scheduler.scheduleAtFixedRate(
    () -> System.out.println("Tick"),
    Duration.ZERO,          // initial delay
    Duration.ofSeconds(1),  // period
    "tick-task"
);

// Wait for a task to complete
task.join();

// Cancel a task
timer.cancel();

// Cancel with interruption of the running thread
delayed.cancel(true);

// Shut down the scheduler
scheduler.shutdown();       // graceful — running tasks finish
scheduler.shutdownNow();    // immediate — interrupts running tasks
```

### ExecutorService Compatibility

Both `FocessScheduler` and `ThreadPoolScheduler` extend `AbstractExecutorService`, so they work with any API that expects an `ExecutorService`:

```java
ExecutorService exec = new ThreadPoolScheduler(4, false, "pool");

// Standard ExecutorService methods
exec.execute(() -> System.out.println("run"));
Future<String> future = exec.submit(() -> "result");
exec.invokeAll(List.of(() -> "a", () -> "b"));
```

### Callbacks

Submit a `Callable` and retrieve the result:

```java
Callback<Integer> cb = scheduler.submit(() -> {
    Thread.sleep(1000);
    return 42;
}, Duration.ZERO);

int result = cb.get();    // blocks until done, returns 42
int now = cb.getNow();    // non-blocking, throws if not done
```

### Exception Handling

```java
Task task = scheduler.schedule(
    () -> { throw new RuntimeException("oops"); },
    Duration.ZERO,
    "named-task",
    executionException -> {
        System.err.println("Task failed: " + executionException.getCause().getMessage());
    }
);

task.join(); // does not throw — the handler consumed the exception
```

### Task Pools

Group tasks together with a composable `CompletionStrategy`:

```java
ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "pool");

// AndTaskPool — completes when ALL tasks finish
AndTaskPool all = new AndTaskPool(scheduler, () -> System.out.println("All done"));
all.addTask(scheduler.schedule(() -> doWork(), Duration.ofSeconds(1)));
all.addTask(scheduler.schedule(() -> doMore(), Duration.ofSeconds(3)));
all.join(); // blocks until both tasks finish

// OrTaskPool — completes when ANY task finishes
OrTaskPool any = new OrTaskPool(scheduler, () -> System.out.println("One done"));
any.addTask(scheduler.schedule(() -> slowWork(), Duration.ofSeconds(10)));
any.addTask(scheduler.schedule(() -> fastWork(), Duration.ofMillis(100)));
any.join(); // blocks until the first task finishes

// Custom strategy — e.g. 2-of-3
TaskPool twoOfThree = new TaskPool(scheduler, (pool, task, remaining) -> remaining <= 1, () -> {});
```

## Architecture

The 2.1.0 release introduces a **Dispatcher-Executor** architecture:

- **Dispatcher** decides *when* a task runs (e.g., `TimeDispatcher` uses a `DelayQueue` for time-based scheduling).
- **TaskExecutor** decides *how* a task runs (e.g., `InlineExecutor` runs on the dispatcher thread; `PoolTaskExecutor` uses a worker pool).
- **Scheduler** is the composition root that wires Dispatcher + Executor together.

This means you can plug in any Dispatcher with any Executor:

```java
// Custom composition
Scheduler custom = new FocessScheduler(myDispatcher, myExecutor);
```

### Key Design Decisions

- **CAS-based state management** — `FocessTask` uses `AtomicReference<TaskState>` for lock-free state transitions (PENDING → RUNNING → FINISHED/CANCELLED).
- **`CountDownLatch` for `join()`** — replaces `synchronized`/`wait`/`notify` with a latch-based approach.
- **`DelayQueue` for scheduling** — thread-safe priority queue replaces `PriorityQueue` + `synchronized`.
- **`System.nanoTime()`** — used for all scheduling timing (not `currentTimeMillis`).
- **No Guava dependency** — removed in 2.1.0.

## Cancellation

Cancellation is **cooperative**: `cancel(true)` interrupts the executing thread but does
not forcibly stop it. Tasks that block on `Thread.sleep()` or `Object.wait()` will receive
`InterruptedException`; tasks that ignore the interrupt flag will continue to completion.
The thread itself is never forcibly stopped and is reused for later tasks.

Interrupts are **isolated**: cancelling one task in a `ThreadPoolScheduler` only interrupts
that task's worker thread. Other tasks are not affected, and the interrupt flag is cleared
before the next task runs on the same worker.

## Migrating from 2.0.x

| 2.0.x | 2.1.0 |
|-------|-------|
| `scheduler.run(runnable)` | `scheduler.schedule(runnable)` |
| `scheduler.run(runnable, duration)` | `scheduler.schedule(runnable, duration)` |
| `scheduler.runTimer(runnable, delay, period)` | `scheduler.scheduleAtFixedRate(runnable, delay, period)` |
| `scheduler.submit(callable)` | `scheduler.submit(callable, Duration.ZERO)` |
| `scheduler.cancelAll()` | `scheduler.cancelPending()` |
| `task.isFinished()` | `task.isDone()` |
| `ITask` (internal) | `TaskInternal` (package-private) |
| `AScheduler` | `AbstractScheduler` |
| `ThreadPoolSchedulerThread` | `PoolTaskExecutor` |
| Guava dependency | Removed |

## Migrating from 1.x

| 1.x | 2.x |
|-----|-----|
| `scheduler.close()` | `scheduler.shutdown()` |
| `scheduler.closeNow()` | `scheduler.shutdownNow()` |
| `scheduler.isClosed()` | `scheduler.isShutdown()` |
| `Thread.stop()`-based cancellation | Cooperative `Thread.interrupt()`-based cancellation |
