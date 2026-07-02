package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the re-shutdown idempotency, shutdownNow return values,
 * halt flag behavior, and other edge cases introduced by the
 * dispatcher/executor refactoring.
 */
@DisplayName("Re-shutdown, shutdownNow return values, and edge cases")
class ReShutdownTest {

    // ---- 1. shutdown() + shutdownNow() on FocessScheduler ----

    @Test
    @DisplayName("shutdown() then shutdownNow() on FocessScheduler still drains and returns tasks")
    void shutdownThenShutdownNowFocess() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("re-focess");
        // Schedule a delayed task so it's still in the dispatcher queue
        Task delayed = scheduler.schedule(() -> {}, Duration.ofSeconds(10));
        Thread.sleep(100); // let the task enter the queue
        scheduler.shutdown();
        // Now call shutdownNow — should still drain the pending task
        List<Runnable> pending = scheduler.shutdownNow();
        assertFalse(pending.isEmpty(), "shutdownNow after shutdown should return pending tasks");
        assertTrue(delayed.isCancelled(), "the delayed task should be cancelled");
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS), "scheduler should terminate");
    }

    // ---- 2. shutdown() + shutdownNow() on ThreadPoolScheduler ----

    @Test
    @DisplayName("shutdown() then shutdownNow() on ThreadPoolScheduler still drains and interrupts")
    void shutdownThenShutdownNowThreadPool() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "re-tp");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Task running = scheduler.schedule(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException e) { interrupted.set(true); }
        });
        Task pending = scheduler.schedule(() -> {}, Duration.ofSeconds(10));
        Thread.sleep(200);
        scheduler.shutdown();
        // shutdownNow after shutdown — should still interrupt running + drain pending
        List<Runnable> remaining = scheduler.shutdownNow();
        Thread.sleep(200);
        assertTrue(interrupted.get(), "running task should have been interrupted");
        assertTrue(pending.isCancelled(), "pending task should be cancelled");
    }

    // ---- 3. shutdownNow() + shutdownNow() — second call returns empty list ----

    @Test
    @DisplayName("shutdownNow() twice — second call returns empty list")
    void shutdownNowTwiceReturnsEmpty() {
        FocessScheduler scheduler = new FocessScheduler("re-now2");
        scheduler.schedule(() -> {}, Duration.ofSeconds(10));
        List<Runnable> first = scheduler.shutdownNow();
        assertFalse(first.isEmpty(), "first shutdownNow should return pending tasks");
        List<Runnable> second = scheduler.shutdownNow();
        assertTrue(second.isEmpty(), "second shutdownNow should return empty list");
    }

    // ---- 4. shutdownNow() + shutdown() — no side effects ----

    @Test
    @DisplayName("shutdownNow() then shutdown() — no side effects, scheduler terminates")
    void shutdownNowThenShutdown() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "re-now-sh");
        scheduler.shutdownNow();
        scheduler.shutdown(); // should be idempotent
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS),
                "scheduler should terminate after shutdownNow + shutdown");
    }

    // ---- 5. shutdownNow() returns the original Runnable (asRunnable) ----

    @Test
    @DisplayName("shutdownNow() returns the original Runnable submitted by the caller")
    void shutdownNowReturnsOriginalRunnable() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "re-runnable");
        AtomicInteger counter = new AtomicInteger(0);
        Runnable originalRunnable = counter::incrementAndGet;
        // Schedule a delayed task so it stays in the dispatcher queue
        Task task = scheduler.schedule(originalRunnable, Duration.ofSeconds(10));
        Thread.sleep(100);
        List<Runnable> pending = scheduler.shutdownNow();
        assertFalse(pending.isEmpty(), "shutdownNow should return pending tasks");
        // The returned Runnable should be the same instance we submitted
        Runnable returned = pending.get(0);
        assertSame(originalRunnable, returned, "returned Runnable should be the original one");
        // Running it should execute the payload
        returned.run();
        assertEquals(1, counter.get(), "running the returned Runnable should execute the payload");
    }

    // ---- 6. shutdownNow() returns wrapped Callable for Callback tasks ----

    @Test
    @DisplayName("shutdownNow() returns a Runnable wrapping the Callable for Callback tasks")
    void shutdownNowReturnsWrappedCallable() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "re-callback");
        AtomicInteger counter = new AtomicInteger(0);
        // Submit a delayed Callback so it stays in the dispatcher queue
        Callback<Integer> cb = scheduler.submit(() -> {
            counter.incrementAndGet();
            return 42;
        }, Duration.ofSeconds(10));
        Thread.sleep(100);
        List<Runnable> pending = scheduler.shutdownNow();
        assertFalse(pending.isEmpty(), "shutdownNow should return pending tasks");
        // The returned Runnable should NOT be null (it wraps the Callable)
        Runnable returned = pending.get(0);
        assertNotNull(returned, "returned Runnable should not be null for Callback");
        // Running it should execute the Callable payload
        returned.run();
        assertEquals(1, counter.get(), "running the returned Runnable should execute the Callable");
    }

    // ---- 7. PoolTaskExecutor last-worker drain ----

    @Test
    @DisplayName("last worker drains remaining tasks on exit after shutdown")
    void lastWorkerDrainsOnExit() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "re-drain");
        CountDownLatch started = new CountDownLatch(1);
        // Fill the worker
        Task running = scheduler.schedule(() -> {
            started.countDown();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        started.await(5, TimeUnit.SECONDS);
        // Now shutdown — the running task finishes, the last worker exits
        // and should drain any remaining tasks
        scheduler.shutdown();
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS),
                "scheduler should terminate with all tasks drained");
    }

    // ---- 8. isShutdown() on both schedulers after shutdown ----

    @Test
    @DisplayName("isShutdown() returns true after shutdown on both scheduler types")
    void isShutdownAfterShutdown() {
        FocessScheduler fs = new FocessScheduler("re-shutdown-f");
        fs.shutdown();
        assertTrue(fs.isShutdown(), "FocessScheduler should report isShutdown=true");

        ThreadPoolScheduler ts = new ThreadPoolScheduler(2, false, "re-shutdown-t");
        ts.shutdown();
        assertTrue(ts.isShutdown(), "ThreadPoolScheduler should report isShutdown=true");
    }

    // ---- 9. Scheduling after shutdownNow throws RejectedExecutionException ----

    @Test
    @DisplayName("scheduling after shutdownNow throws RejectedExecutionException")
    void scheduleAfterShutdownNowThrows() {
        FocessScheduler fs = new FocessScheduler("re-reject-f");
        fs.shutdownNow();
        assertThrows(RejectedExecutionException.class, () -> fs.schedule(() -> {}),
                "scheduling after shutdownNow should throw");

        ThreadPoolScheduler ts = new ThreadPoolScheduler(2, false, "re-reject-t");
        ts.shutdownNow();
        assertThrows(RejectedExecutionException.class, () -> ts.schedule(() -> {}),
                "scheduling after shutdownNow should throw");
    }

    // ---- 10. ExecutorService submit works on ThreadPoolScheduler ----

    @Test
    @DisplayName("submit(Callable) via ExecutorService returns a Future with the result")
    void executorServiceSubmit() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "re-es-submit");
        Future<String> future = scheduler.submit(() -> "result");
        assertEquals("result", future.get(), "Future.get should return 'result'");
        assertTrue(future.isDone());
        scheduler.shutdown();
    }

    // ---- 11. InlineExecutor isTerminated after shutdown with no tasks ----

    @Test
    @DisplayName("InlineExecutor.isTerminated() returns true after shutdown with no running tasks")
    void inlineExecutorIsTerminatedAfterShutdown() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("re-idle");
        Task task = scheduler.schedule(() -> {});
        task.join();
        scheduler.shutdown();
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS),
                "scheduler should terminate");
        assertTrue(scheduler.getTaskExecutor().isTerminated(), "InlineExecutor should be terminated");
    }

    // ---- 12. PoolTaskExecutor isTerminated after shutdown with no tasks ----

    @Test
    @DisplayName("PoolTaskExecutor.isTerminated() returns true after shutdown with no running tasks")
    void poolExecutorIsTerminatedAfterShutdown() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "re-pool-idle");
        Task task = scheduler.schedule(() -> {});
        task.join(5, TimeUnit.SECONDS);
        scheduler.shutdown();
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS),
                "scheduler should terminate");
        assertTrue(scheduler.getTaskExecutor().isTerminated(), "PoolTaskExecutor should be terminated after tasks complete");
    }
}
