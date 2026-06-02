# FocessScheduler

A lightweight, cooperative task scheduling library for Java.

Two scheduler implementations are provided:

- **`FocessScheduler`** — single-threaded, executes tasks sequentially in time order.
- **`ThreadPoolScheduler`** — multi-threaded, executes tasks in parallel across a worker pool.

## Quick Start

### Maven

```xml
<dependency>
    <groupId>top.focess</groupId>
    <artifactId>focess-scheduler</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Gradle

```gradle
implementation 'top.focess:focess-scheduler:2.0.0'
```

## Usage

```java
// Create a single-threaded scheduler
Scheduler scheduler = new FocessScheduler("my-scheduler");

// Run a task immediately
Task task = scheduler.run(() -> System.out.println("Hello"));

// Run a task after a delay
Task delayed = scheduler.run(() -> System.out.println("World"), Duration.ofSeconds(2));

// Run a periodic task
Task timer = scheduler.runTimer(
    () -> System.out.println("Tick"),
    Duration.ZERO,          // initial delay
    Duration.ofSeconds(1)   // period
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

### Task Pools

Group tasks together and wait for the group to complete:

```java
ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "pool");

// AndTaskPool — completes when ALL tasks finish
AndTaskPool all = new AndTaskPool(scheduler, () -> System.out.println("All done"));
all.addTask(scheduler.run(() -> doWork(), Duration.ofSeconds(1)));
all.addTask(scheduler.run(() -> doMore(), Duration.ofSeconds(3)));
all.join(); // blocks until both tasks finish

// OrTaskPool — completes when ANY task finishes
OrTaskPool any = new OrTaskPool(scheduler, () -> System.out.println("One done"));
any.addTask(scheduler.run(() -> slowWork(), Duration.ofSeconds(10)));
any.addTask(scheduler.run(() -> fastWork(), Duration.ofMillis(100)));
any.join(); // blocks until the first task finishes
```

### Callbacks

Submit a `Callable` and retrieve the result:

```java
Callback<Integer> cb = scheduler.submit(() -> {
    Thread.sleep(1000);
    return 42;
});

int result = cb.get(); // blocks until done, returns 42
```

### Exception Handling

```java
Task task = scheduler.run(() -> {
    throw new RuntimeException("oops");
}, "named-task", executionException -> {
    System.err.println("Task failed: " + executionException.getCause().getMessage());
});

task.join(); // does not throw — the handler consumed the exception
```

## Cancellation

Cancellation is **cooperative**: `cancel(true)` interrupts the executing thread but does
not forcibly stop it. Tasks that block on `Thread.sleep()` or `Object.wait()` will receive
`InterruptedException`; tasks that ignore the interrupt flag will continue to completion.
The thread itself is never forcibly stopped and is reused for later tasks.

## Migrating from 1.x

| 1.x | 2.x |
|-----|-----|
| `scheduler.close()` | `scheduler.shutdown()` |
| `scheduler.closeNow()` | `scheduler.shutdownNow()` |
| `scheduler.isClosed()` | `scheduler.isShutdown()` |
| `Thread.stop()`-based cancellation | Cooperative `Thread.interrupt()`-based cancellation |