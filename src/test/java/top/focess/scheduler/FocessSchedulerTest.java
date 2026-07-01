package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FocessScheduler — single-threaded scheduling, cancel, shutdown, constructors")
class FocessSchedulerTest {

    // ---- 1. schedule(runnable) — immediate ----

    @Test
    @DisplayName("schedule(runnable) runs the task immediately")
    void scheduleImmediate() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("imm");
        AtomicBoolean ran = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> ran.set(true));
        task.join();
        assertTrue(ran.get(), "task should have run");
        scheduler.shutdown();
    }

    // ---- 2. schedule(runnable, Duration) — delayed ----

    @Test
    @DisplayName("schedule(runnable, Duration) runs the task after the specified delay")
    void scheduleWithDelay() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("delayed");
        long start = System.nanoTime();
        Task task = scheduler.schedule(() -> {}, Duration.ofMillis(500));
        task.join(5, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 400, "should wait ~500ms, got " + elapsedMs + "ms");
        scheduler.shutdown();
    }

    // ---- 3. schedule(runnable, Duration, String) — named task ----

    @Test
    @DisplayName("schedule(runnable, Duration, String) sets the task name correctly")
    void scheduleNamed() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("named");
        Task task = scheduler.schedule(() -> {}, Duration.ZERO, "my-name");
        assertTrue(task.getName().contains("my-name"), "name should contain 'my-name', got: " + task.getName());
        task.join();
        scheduler.shutdown();
    }

    // ---- 4. schedule(runnable, Duration, String, Consumer) — with handler ----

    @Test
    @DisplayName("schedule(runnable, Duration, String, Consumer) invokes the exception handler")
    void scheduleWithHandler() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("handler");
        AtomicReference<ExecutionException> captured = new AtomicReference<>();
        Task task = scheduler.schedule(
            () -> { throw new RuntimeException("boom"); },
            Duration.ZERO,
            "handled",
            captured::set
        );
        task.join(); // handler should suppress the exception
        assertNotNull(captured.get(), "handler should have been called");
        assertEquals("boom", captured.get().getCause().getMessage());
        scheduler.shutdown();
    }

    // ---- 5. schedule(runnable, String) — default method bridge ----

    @Test
    @DisplayName("schedule(runnable, String) schedules immediately with the given name")
    void scheduleNamedImmediate() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("named-imm");
        AtomicBoolean ran = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> ran.set(true), "quick");
        assertTrue(task.getName().contains("quick"), "name should contain 'quick'");
        task.join();
        assertTrue(ran.get(), "task should have run");
        scheduler.shutdown();
    }

    // ---- 6. scheduleAtFixedRate — periodic task runs multiple times ----

    @Test
    @DisplayName("scheduleAtFixedRate runs the task multiple times at the specified rate")
    void scheduleAtFixedRate() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("periodic");
        AtomicInteger count = new AtomicInteger(0);
        Task task = scheduler.scheduleAtFixedRate(
            count::incrementAndGet,
            Duration.ZERO,
            Duration.ofMillis(200),
            "tick"
        );
        Thread.sleep(1000);
        task.cancel();
        int c = count.get();
        assertTrue(c >= 3, "should have run at least 3 times, got " + c);
        assertTrue(task.isPeriod(), "should be a period task");
        scheduler.shutdown();
    }

    // ---- 7. scheduleAtFixedRate with name ----

    @Test
    @DisplayName("scheduleAtFixedRate with name sets the name correctly")
    void scheduleAtFixedRateNamed() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("periodic-named");
        Task task = scheduler.scheduleAtFixedRate(
            () -> {},
            Duration.ZERO,
            Duration.ofSeconds(10),
            "periodic-name"
        );
        assertTrue(task.getName().contains("periodic-name"), "name should contain 'periodic-name'");
        assertTrue(task.isPeriod());
        task.cancel();
        scheduler.shutdown();
    }

    // ---- 8. scheduleAtFixedRate with exception handler — continues after handled exception ----

    @Test
    @DisplayName("scheduleAtFixedRate with handler continues scheduling after handled exception")
    void scheduleAtFixedRateWithHandler() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("periodic-handler");
        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger handlerCalls = new AtomicInteger(0);
        Task task = scheduler.scheduleAtFixedRate(
            () -> {
                count.incrementAndGet();
                throw new RuntimeException("periodic-fail");
            },
            Duration.ZERO,
            Duration.ofMillis(300),
            "periodic-handled",
            ex -> handlerCalls.incrementAndGet()
        );
        Thread.sleep(1000);
        task.cancel();
        assertTrue(count.get() >= 2, "should have run at least 2 times, got " + count.get());
        assertTrue(handlerCalls.get() >= 2, "handler should have been called at least 2 times, got " + handlerCalls.get());
        scheduler.shutdown();
    }

    // ---- 9. submit(callable, Duration) — returns value ----

    @Test
    @DisplayName("submit(callable, Duration.ZERO) returns the computed value")
    void submitCallback() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("submit");
        Callback<Double> cb = scheduler.submit(() -> 3.14, Duration.ZERO);
        assertEquals(3.14, cb.get(), "should return 3.14");
        scheduler.shutdown();
    }

    // ---- 10. submit(callable, Duration, String) — named callback ----

    @Test
    @DisplayName("submit(callable, Duration, String) returns a named callback")
    void submitNamedCallback() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("submit-named");
        Callback<Integer> cb = scheduler.submit(() -> 7, Duration.ZERO, "my-cb");
        assertTrue(cb.getName().contains("my-cb"), "name should contain 'my-cb'");
        assertEquals(7, cb.get());
        scheduler.shutdown();
    }

    // ---- 11. submit(callable, Duration, String, Function) — function handler ----

    @Test
    @DisplayName("submit(callable, Duration, String, Function) uses the function handler for fallback")
    void submitCallbackWithFunctionHandler() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("submit-fn");
        Callback<Integer> cb = scheduler.submit(
            () -> { throw new RuntimeException("nope"); },
            Duration.ZERO,
            "fn-cb",
            ex -> -999
        );
        cb.join();
        assertEquals(-999, cb.getNow(), "should return fallback from function handler");
        scheduler.shutdown();
    }

    // ---- 12. Cancel pending task ----

    @Test
    @DisplayName("pending task is cancelled and never executes")
    void cancelPendingTask() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cancel-pending");
        AtomicBoolean ran = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> ran.set(true), Duration.ofSeconds(10));
        assertTrue(task.cancel());
        assertTrue(task.isCancelled());
        Thread.sleep(300);
        assertFalse(ran.get(), "task should not have run");
        scheduler.shutdown();
    }

    // ---- 13. Cancel running task with interrupt ----

    @RepeatedTest(3)
    @DisplayName("cancel(true) on running FocessScheduler task interrupts it")
    void cancelRunningTaskWithInterrupt() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cancel-run");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); }
        });
        Thread.sleep(500);
        assertTrue(task.isRunning());
        assertTrue(task.cancel(true));
        Thread.sleep(300);
        assertTrue(interrupted.get(), "task should have been interrupted");
        assertTrue(task.isCancelled());
        scheduler.shutdown();
    }

    // ---- 14. SchedulerClosedException after shutdown ----

    @Test
    @DisplayName("scheduling on a shut-down FocessScheduler throws SchedulerClosedException")
    void shutdownPreventsNewTasks() {
        FocessScheduler scheduler = new FocessScheduler("closed");
        scheduler.shutdown();
        assertThrows(SchedulerClosedException.class, () -> scheduler.schedule(() -> {}));
    }

    // ---- 15. Daemon thread mode ----

    @Test
    @DisplayName("FocessScheduler(name, true) creates a daemon dispatcher thread")
    void daemonThreadMode() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("daemon-test", true);
        // Find the dispatcher thread and check it's a daemon
        boolean[] foundDaemon = {false};
        Thread.getAllStackTraces().keySet().forEach(t -> {
            if (t.getName().contains("daemon-test-dispatcher") && t.isDaemon()) {
                foundDaemon[0] = true;
            }
        });
        assertTrue(foundDaemon[0], "dispatcher thread should be a daemon");
        scheduler.shutdown();
    }

    // ---- 16. newPrefixScheduler static factory ----

    @Test
    @DisplayName("FocessScheduler.newPrefixScheduler creates a scheduler with the given prefix")
    void newPrefixScheduler() throws Exception {
        FocessScheduler scheduler = FocessScheduler.newPrefixScheduler("prefix");
        assertTrue(scheduler.getName().contains("prefix"), "name should contain 'prefix', got: " + scheduler.getName());
        Task task = scheduler.schedule(() -> {});
        task.join();
        scheduler.shutdown();
    }
}
