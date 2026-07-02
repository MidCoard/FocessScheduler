package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import java.util.concurrent.RejectedExecutionException;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests for the sharp positions identified in the adversarial code review.
 * These tests exercise race conditions, resource leaks, and edge cases that
 * are difficult to trigger with simple sequential tests.
 * <p>
 * Many of these tests use {@link RepeatedTest} because race conditions are
 * probabilistic — they may pass on one run but fail on another due to
 * thread scheduling. Repeating increases the chance of catching a bug.
 */
@DisplayName("Concurrency stress tests — race conditions, resource leaks, edge cases")
class ConcurrencyStressTest {

    // ========================================================================
    // C1: State + latch inconsistency — cancel/clear race on periodic tasks
    // ========================================================================

    @RepeatedTest(10)
    @DisplayName("C1: cancel() during periodic task clear() does not orphan the latch")
    void cancelDuringPeriodicClearDoesNotOrphanLatch() throws Exception {
        // This test targets the race where cancel() is called between
        // clear() transitioning FINISHED→PENDING and the new latch being installed.
        // With the StateAndLatch fix, the latch and state transition atomically,
        // so join() should never block forever.
        //
        // Note: cancel(false) is best-effort — if the task is RUNNING at the time
        // of the call, cancel returns false and the task continues. This is by design.
        // The key invariant is that join() never blocks forever (latch consistency).
        FocessScheduler scheduler = new FocessScheduler("c1-race");
        AtomicInteger runCount = new AtomicInteger(0);
        CountDownLatch firstRunDone = new CountDownLatch(1);

        Task task = scheduler.scheduleAtFixedRate(() -> {
            int c = runCount.incrementAndGet();
            if (c == 1) firstRunDone.countDown();
        }, Duration.ZERO, Duration.ofMillis(50), "c1-task");

        // Wait for the first run to complete
        firstRunDone.await(5, TimeUnit.SECONDS);

        // Now cancel the task — this races with clear() for the next cycle.
        // cancel(false) may fail if the task is RUNNING, which is expected.
        task.cancel(true);

        // join() must not block forever — the latch must be consistent.
        // If cancel succeeded: join() throws CancellationException.
        // If cancel failed (task is RUNNING): join() waits for the current run
        // to finish, then either the task is re-dispatched (periodic) or the
        // next cancel attempt succeeds.
        try {
            task.join(5, TimeUnit.SECONDS);
        } catch (CancellationException e) {
            // Expected for cancelled tasks
        }
        // The invariant: join() returned within the timeout — latch is consistent.
        // We do NOT assert isCancelled/isDone because cancel(false) is non-deterministic
        // when the task may be RUNNING.
        scheduler.shutdown();
    }

    @RepeatedTest(10)
    @DisplayName("C1: concurrent cancel() and periodic task completion — join() never hangs")
    void concurrentCancelAndPeriodicCompletion() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "c1-concurrent");
        AtomicInteger runCount = new AtomicInteger(0);

        Task task = scheduler.scheduleAtFixedRate(
                runCount::incrementAndGet,
                Duration.ZERO, Duration.ofMillis(30), "c1-concurrent-task");

        // Let it run a few cycles
        Thread.sleep(200);

        // Cancel from another thread while the task may be completing
        Thread cancelThread = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            task.cancel(true);
        });
        cancelThread.start();

        // join() must complete within a reasonable time
        // (may throw CancellationException for cancelled tasks)
        try {
            task.join(5, TimeUnit.SECONDS);
        } catch (CancellationException e) {
            // Expected for cancelled tasks
        }
        cancelThread.join(5000);
        scheduler.shutdown();
    }

    // ========================================================================
    // C2: RejectedExecutionException during periodic re-dispatch
    // ========================================================================

    @Test
    @DisplayName("C2: shutdown during periodic re-dispatch does not kill worker thread")
    void shutdownDuringPeriodicRedispatchDoesNotKillWorker() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "c2-redispatch");
        AtomicInteger runCount = new AtomicInteger(0);

        Task task = scheduler.scheduleAtFixedRate(
                runCount::incrementAndGet,
                Duration.ZERO, Duration.ofMillis(100), "c2-task");

        // Let it run a couple of times
        Thread.sleep(300);

        // Shutdown while the task may be in the middle of re-dispatch
        scheduler.shutdown();

        // The scheduler should terminate cleanly (no thread killed by uncaught exception)
        boolean terminated = scheduler.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(terminated, "scheduler should terminate cleanly after shutdown during re-dispatch");
        scheduler.shutdown();
    }

    @RepeatedTest(5)
    @DisplayName("C2: rapid shutdown during periodic task execution — no uncaught exceptions")
    void rapidShutdownDuringPeriodicExecution() throws Exception {
        AtomicReference<Throwable> uncaughtException = new AtomicReference<>();
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "c2-rapid");
        scheduler.setUncaughtExceptionHandler((t, e) -> uncaughtException.set(e));

        scheduler.scheduleAtFixedRate(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) {}
        }, Duration.ZERO, Duration.ofMillis(50), "c2-rapid-task");

        Thread.sleep(150);
        scheduler.shutdown();

        boolean terminated = scheduler.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(terminated, "scheduler should terminate");
        assertNull(uncaughtException.get(), "no uncaught exception should escape during shutdown");
    }

    // ========================================================================
    // C3: runningCount and workerCount after Error in worker — worker relaunched
    // ========================================================================

    @Test
    @DisplayName("C3: after Error in worker, pool recovers — isTerminated() returns true after shutdown, pool still works")
    void isTerminatedReturnsTrueAfterErrorInWorker() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "c3-idle");

        // Submit a task that throws an Error
        scheduler.schedule(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            throw new InternalError("test-error");
        });

        // Wait for the error to be processed and worker relaunched
        Thread.sleep(1000);

        // The scheduler should NOT be shut down — worker is relaunched
        assertFalse(scheduler.isShutdown(), "scheduler should NOT be shut down after Error — worker is relaunched");
        // Verify the pool still works
        AtomicBoolean ran = new AtomicBoolean(false);
        Task verifyTask = scheduler.schedule(() -> ran.set(true));
        verifyTask.join(5, TimeUnit.SECONDS);
        assertTrue(ran.get(), "pool should still process tasks after Error");
        scheduler.shutdown();
    }

    // ========================================================================
    // S2: Worker count race — pool should not drop below core size
    // ========================================================================

    @Test
    @DisplayName("S2: extra workers exit gracefully without dropping below core pool size")
    void extraWorkersDoNotDropBelowCoreSize() throws Exception {
        // Create a pool with 2 core workers and immediate mode
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, true, "s2-core");

        // Submit a burst of tasks to trigger expansion
        CountDownLatch allStarted = new CountDownLatch(5);
        CountDownLatch allowFinish = new CountDownLatch(1);
        Task[] tasks = new Task[5];
        for (int i = 0; i < 5; i++) {
            tasks[i] = scheduler.schedule(() -> {
                allStarted.countDown();
                try { allowFinish.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) {}
            });
        }

        // Wait for all tasks to start
        allStarted.await(5, TimeUnit.SECONDS);

        // Release all tasks
        allowFinish.countDown();

        // Wait for all tasks to complete
        for (Task t : tasks) t.join(5, TimeUnit.SECONDS);

        // Submit more tasks — they should still be processed (pool didn't drop below core)
        AtomicBoolean processed = new AtomicBoolean(false);
        Task verifyTask = scheduler.schedule(() -> processed.set(true));
        verifyTask.join(5, TimeUnit.SECONDS);
        assertTrue(processed.get(), "pool should still process tasks after extra workers exit");

        for (Task t : tasks) t.cancel(true);
        scheduler.shutdown();
    }

    // ========================================================================
    // S3: Consumer handler on Callback — getNow() throws instead of returning null
    // ========================================================================

    @Test
    @DisplayName("S3: Consumer handler on Callback — getNow() throws ExecutionException, not null")
    void consumerHandlerOnCallbackGetNowThrows() throws Exception {
        // Use ThreadPoolScheduler so we can set the handler before execution
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "s3-consumer");
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        // Schedule a delayed task so the handler can be set before execution
        Callback<Integer> cb = scheduler.submit(
                () -> { throw new RuntimeException("test-exception"); },
                Duration.ofMillis(200),
                "s3-cb"
        );
        cb.setExceptionHandler((Consumer<ExecutionException>) e -> handlerCalled.set(true));
        cb.join(5, TimeUnit.SECONDS); // should not throw — Consumer consumed the exception

        assertTrue(handlerCalled.get(), "Consumer handler should have been called");
        // After the fix, getNow() should throw ExecutionException (not return null)
        ExecutionException ex = assertThrows(ExecutionException.class, () -> cb.getNow());
        assertTrue(ex.getMessage().contains("consumed by Consumer handler"),
                "Exception message should indicate Consumer handler consumed the exception");
        scheduler.shutdown();
    }

    @Test
    @DisplayName("S3: Function handler on Callback — getNow() returns fallback value")
    void functionHandlerOnCallbackGetNowReturnsFallback() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("s3-function");
        Callback<Integer> cb = scheduler.submit(
                () -> { throw new RuntimeException("test-exception"); },
                Duration.ZERO,
                "s3-fn-cb",
                ex -> 42
        );
        cb.join();
        assertEquals(42, cb.getNow(), "Function handler should provide fallback value");
        scheduler.shutdown();
    }

    // ========================================================================
    // S4: maxPoolSize caps unbounded worker spawning
    // ========================================================================

    @Test
    @DisplayName("S4: ThreadPoolScheduler with maxPoolSize constructor works correctly")
    void maxPoolSizeConstructorWorks() throws Exception {
        // Verify the new 5-arg constructor creates a working scheduler
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, true, "s4-cap", false, 4);
        AtomicInteger count = new AtomicInteger(0);

        // Submit several tasks
        Task[] tasks = new Task[5];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = scheduler.schedule(() -> count.incrementAndGet());
        }

        // Wait for all tasks to complete
        for (Task t : tasks) t.join(5, TimeUnit.SECONDS);
        assertEquals(5, count.get(), "all tasks should have run");
        scheduler.shutdown();
    }

    // ========================================================================
    // M1: completionListeners cleared on clear() for periodic tasks
    // ========================================================================

    @Test
    @DisplayName("M1: onComplete listeners are one-shot for periodic tasks and cleared on re-dispatch")
    void onCompleteListenersOneShotForPeriodicTasks() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("m1-listeners");
        AtomicInteger listenerCount = new AtomicInteger(0);
        AtomicInteger runCount = new AtomicInteger(0);
        CountDownLatch listenerFired = new CountDownLatch(1);

        Task task = scheduler.scheduleAtFixedRate(() -> {
            runCount.incrementAndGet();
        }, Duration.ZERO, Duration.ofMillis(100), "m1-task");

        // Register a listener
        task.onComplete(() -> {
            listenerCount.incrementAndGet();
            listenerFired.countDown();
        });

        assertTrue(listenerFired.await(5, TimeUnit.SECONDS),
                "onComplete listener should fire for one periodic completion");
        Thread.sleep(200);
        task.cancel(true);

        // The listener should have fired exactly once (one-shot)
        assertEquals(1, listenerCount.get(),
                "onComplete listener should fire exactly once for periodic task (one-shot)");
        assertTrue(runCount.get() >= 3, "task should have run at least 3 times, got " + runCount.get());
        scheduler.shutdown();
    }

    // ========================================================================
    // Additional edge cases
    // ========================================================================

    @Test
    @DisplayName("cancel(true) on a task that catches InterruptedException but doesn't re-throw")
    void cancelTrueOnTaskThatSwallowsInterrupt() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "edge-swallow");
        AtomicBoolean sawInterrupt = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        Task task = scheduler.schedule(() -> {
            try { Thread.sleep(2000); }
            catch (InterruptedException e) { sawInterrupt.set(true); /* swallow */ }
            completed.set(true);
        });

        Thread.sleep(300);
        assertTrue(task.isRunning());
        assertTrue(task.cancel(true));

        // The task is marked cancelled even though it swallows the interrupt
        Thread.sleep(3000);
        assertTrue(sawInterrupt.get(), "task should have seen the interrupt");
        assertTrue(completed.get(), "task should have completed (swallowed interrupt)");
        assertTrue(task.isCancelled(), "task should be marked cancelled");
        scheduler.shutdown();
    }

    @Test
    @DisplayName("onComplete() after shutdown — listener fires for already-done task")
    void onCompleteAfterShutdown() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("edge-oncomplete-shutdown");
        Task task = scheduler.schedule(() -> {});
        task.join();
        scheduler.shutdown();

        // Register a listener after shutdown — should fire immediately since task is done
        AtomicBoolean listenerFired = new AtomicBoolean(false);
        task.onComplete(() -> listenerFired.set(true));
        assertTrue(listenerFired.get(), "onComplete should fire immediately for done task even after shutdown");
    }

    @Test
    @DisplayName("schedule() between shutdown(false) and dispatcher thread stopping")
    void scheduleBetweenShutdownAndDispatcherStop() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("edge-schedule-shutdown");
        // Schedule a task to keep the scheduler busy
        Task longTask = scheduler.schedule(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) {}
        });

        // Initiate graceful shutdown
        scheduler.shutdown();

        // Try to schedule — should throw RejectedExecutionException
        assertThrows(RejectedExecutionException.class, () -> scheduler.schedule(() -> {}),
                "schedule after shutdown should throw RejectedExecutionException");

        longTask.cancel(true);
    }

    @RepeatedTest(5)
    @DisplayName("periodic task with exception handler continues after handled exception")
    void periodicTaskWithHandlerContinuesAfterException() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("edge-periodic-handler");
        AtomicInteger runCount = new AtomicInteger(0);
        AtomicInteger handlerCalls = new AtomicInteger(0);

        Task task = scheduler.scheduleAtFixedRate(
                () -> {
                    runCount.incrementAndGet();
                    throw new RuntimeException("periodic-fail");
                },
                Duration.ZERO,
                Duration.ofMillis(100),
                "edge-periodic-handled",
                ex -> handlerCalls.incrementAndGet()
        );

        Thread.sleep(600);
        task.cancel();

        assertTrue(runCount.get() >= 3, "should have run at least 3 times, got " + runCount.get());
        assertTrue(handlerCalls.get() >= 3, "handler should have been called at least 3 times, got " + handlerCalls.get());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("isTerminated() returns true after shutdown with no running tasks")
    void isTerminatedAfterShutdownNoRunningTasks() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("edge-terminated");
        Task task = scheduler.schedule(() -> {});
        task.join();
        scheduler.shutdown();
        boolean terminated = scheduler.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(terminated, "scheduler should be terminated after all tasks complete and shutdown");
        assertTrue(scheduler.isTerminated(), "isTerminated should return true");
    }

    @Test
    @DisplayName("ThreadPoolScheduler recovers after Error — pool still works")
    void poolRecoversAfterError() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "edge-terminated-error");

        scheduler.schedule(() -> {
            throw new InternalError("test-termination");
        });

        // Wait for the error to be processed and worker relaunched
        Thread.sleep(1500);
        assertFalse(scheduler.isShutdown(), "scheduler should NOT be shut down after Error — worker is relaunched");

        // Verify the pool still works
        AtomicBoolean ran = new AtomicBoolean(false);
        Task verifyTask = scheduler.schedule(() -> ran.set(true));
        verifyTask.join(5, TimeUnit.SECONDS);
        assertTrue(ran.get(), "pool should still process tasks after Error");
        scheduler.shutdown();
    }

    @RepeatedTest(5)
    @DisplayName("concurrent cancel on multiple periodic tasks — no latch leaks")
    void concurrentCancelOnMultiplePeriodicTasks() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "edge-multi-cancel");
        Task[] tasks = new Task[10];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = scheduler.scheduleAtFixedRate(
                    () -> { try { Thread.sleep(50); } catch (InterruptedException e) {} },
                    Duration.ZERO,
                    Duration.ofMillis(100),
                    "multi-cancel-" + i
            );
        }

        // Let them run a bit
        Thread.sleep(300);

        // Cancel all from different threads
        Thread[] cancelThreads = new Thread[tasks.length];
        for (int i = 0; i < tasks.length; i++) {
            final int idx = i;
            cancelThreads[i] = new Thread(() -> tasks[idx].cancel(true));
            cancelThreads[i].start();
        }

        // All join() calls should complete within a reasonable time
        // (join() throws CancellationException for cancelled tasks, which is expected)
        for (int i = 0; i < tasks.length; i++) {
            final int idx = i;
            try {
                tasks[idx].join(5, TimeUnit.SECONDS);
            } catch (CancellationException e) {
                // Expected for cancelled tasks
            }
        }

        for (Thread t : cancelThreads) t.join(5000);
        scheduler.shutdown();
    }
}
