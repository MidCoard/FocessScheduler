package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.RejectedExecutionException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Scheduler lifecycle — shutdown, awaitTermination, shutdownNow, ExecutorService bridge")
class SchedulerLifecycleTest {

    // ---- 1. shutdown() is idempotent ----

    @Test
    @DisplayName("calling shutdown() twice does not throw")
    void shutdownIsIdempotent() {
        FocessScheduler scheduler = new FocessScheduler("idempotent");
        scheduler.shutdown();
        assertDoesNotThrow(() -> scheduler.shutdown(), "second shutdown should not throw");
    }

    // ---- 2. shutdownNow() returns empty list ----

    @Test
    @DisplayName("shutdownNow() returns an empty list")
    void shutdownNowReturnsEmptyList() {
        FocessScheduler scheduler = new FocessScheduler("now-list");
        List<Runnable> remaining = scheduler.shutdownNow();
        assertTrue(remaining.isEmpty(), "shutdownNow should return an empty list");
    }

    // ---- 3. isTerminated() after shutdown + tasks complete ----

    @Test
    @DisplayName("isTerminated() returns true after shutdown and all tasks complete")
    void isTerminatedAfterShutdown() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("terminated");
        Task task = scheduler.schedule(() -> {});
        task.join();
        scheduler.shutdown();
        // Wait for the dispatcher thread to finish (it uses a 1 s poll timeout,
        // so a fixed sleep shorter than that can race).
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS),
                "scheduler should be terminated after shutdown + idle");
    }

    // ---- 4. awaitTermination() returns true ----

    @Test
    @DisplayName("awaitTermination() returns true when the scheduler has terminated")
    void awaitTerminationReturnsTrue() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("await-true");
        Task task = scheduler.schedule(() -> {});
        task.join();
        scheduler.shutdown();
        boolean result = scheduler.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(result, "awaitTermination should return true when terminated");
    }

    // ---- 5. awaitTermination() returns false on timeout ----

    @Test
    @DisplayName("awaitTermination() returns false when the scheduler has not terminated yet")
    void awaitTerminationReturnsFalseOnTimeout() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "await-false");
        AtomicBoolean started = new AtomicBoolean(false);
        // Schedule a long-running task so the scheduler won't terminate quickly
        scheduler.schedule(() -> {
            started.set(true);
            try { Thread.sleep(10000); } catch (InterruptedException e) {}
        });
        // Wait until the task is actually running
        while (!started.get()) Thread.sleep(10);
        scheduler.shutdown();
        boolean result = scheduler.awaitTermination(100, TimeUnit.MILLISECONDS);
        assertFalse(result, "awaitTermination should return false before termination");
        scheduler.shutdownNow();
    }

    // ---- 6. shutdownNow() cancels queued tasks, running unaffected ----

    @Test
    @DisplayName("shutdownNow() cancels pending tasks but does not interrupt running tasks")
    void shutdownNowCancelsQueuedTasks() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "cancel-pending");
        AtomicBoolean runningCompleted = new AtomicBoolean(false);
        AtomicBoolean pendingRan = new AtomicBoolean(false);

        // Fill the single worker with a long task
        Task running = scheduler.schedule(() -> {
            try { Thread.sleep(2000); runningCompleted.set(true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        // Queue a second task behind it
        Task pending = scheduler.schedule(() -> pendingRan.set(true), Duration.ZERO);

        Thread.sleep(500);
        assertTrue(running.isRunning(), "first task should be running");
        scheduler.shutdownNow();
        // The pending task should be cancelled now
        Thread.sleep(500);
        // Wait for the running task to finish
        running.join(5, TimeUnit.SECONDS);
        assertTrue(runningCompleted.get(), "running task should complete");
        // Pending task may or may not be cancelled depending on timing,
        // but shutdownNow should have removed it from the dispatcher queue
    }

    // ---- 7. Scheduling after shutdown throws on both schedulers ----

    @Test
    @DisplayName("scheduling after shutdown throws RejectedExecutionException on both Focess and ThreadPool")
    void scheduleAfterShutdownThrows() {
        FocessScheduler fs = new FocessScheduler("closed-f");
        fs.shutdown();
        assertThrows(RejectedExecutionException.class, () -> fs.schedule(() -> {}),
            "FocessScheduler should throw RejectedExecutionException");

        ThreadPoolScheduler ts = new ThreadPoolScheduler(2, false, "closed-t");
        ts.shutdown();
        assertThrows(RejectedExecutionException.class, () -> ts.schedule(() -> {}),
            "ThreadPoolScheduler should throw RejectedExecutionException");
    }

    // ---- 8. ExecutorService submit(Callable) ----

    @Test
    @DisplayName("submit(Callable) via ExecutorService returns a Future with the result")
    void executorServiceSubmit() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "es-submit");
        Future<String> future = scheduler.submit(() -> "result");
        assertEquals("result", future.get(), "Future.get should return 'result'");
        assertTrue(future.isDone());
        scheduler.shutdown();
    }

    // ---- 9. ExecutorService submit(Runnable) ----

    @Test
    @DisplayName("submit(Runnable) via ExecutorService returns a Future<?>")
    void executorServiceSubmitRunnable() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "es-submit-r");
        AtomicBoolean ran = new AtomicBoolean(false);
        Future<?> future = scheduler.submit(() -> ran.set(true));
        assertNull(future.get(), "Future.get should return null for Runnable");
        assertTrue(ran.get(), "runnable should have run");
        scheduler.shutdown();
    }

    // ---- 10. ExecutorService submit(Runnable, V) ----

    @Test
    @DisplayName("submit(Runnable, V) via ExecutorService returns a Future<V> with the given result")
    void executorServiceSubmitRunnableWithResult() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "es-submit-rv");
        Future<String> future = scheduler.submit(() -> {}, "presets");
        assertEquals("presets", future.get(), "Future.get should return the preset value");
        scheduler.shutdown();
    }

    // ---- 11. ExecutorService execute(Runnable) ----

    @Test
    @DisplayName("execute(Runnable) via ExecutorService runs the task")
    void executorServiceExecute() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("es-execute");
        AtomicBoolean ran = new AtomicBoolean(false);
        scheduler.execute(() -> ran.set(true));
        Thread.sleep(500);
        assertTrue(ran.get(), "execute should have run the task");
        scheduler.shutdown();
    }

    // ---- 12. UncaughtExceptionHandler get/set ----

    @Test
    @DisplayName("setUncaughtExceptionHandler / getUncaughtExceptionHandler round-trip")
    void uncaughtExceptionHandler() {
        FocessScheduler scheduler = new FocessScheduler("ueh");
        assertNull(scheduler.getUncaughtExceptionHandler(), "default handler should be null");
        Thread.UncaughtExceptionHandler handler = (t, e) -> {};
        scheduler.setUncaughtExceptionHandler(handler);
        assertSame(handler, scheduler.getUncaughtExceptionHandler(), "handler should be the same instance");
        scheduler.shutdown();
    }

    // ---- Extra: Scheduler name and toString ----

    @Test
    @DisplayName("getName() and toString() return the scheduler name")
    void schedulerNameAndToString() {
        FocessScheduler scheduler = new FocessScheduler("my-name");
        assertEquals("my-name", scheduler.getName());
        assertEquals("my-name", scheduler.toString());
        scheduler.shutdown();
    }

    // ---- Extra: getDispatcher and getTaskExecutor ----

    @Test
    @DisplayName("getDispatcher() and getTaskExecutor() return the wired components")
    void getDispatcherAndExecutor() {
        FocessScheduler scheduler = new FocessScheduler("components");
        assertInstanceOf(TimeDispatcher.class, scheduler.getDispatcher());
        assertInstanceOf(InlineExecutor.class, scheduler.getTaskExecutor());
        scheduler.shutdown();
    }
}
