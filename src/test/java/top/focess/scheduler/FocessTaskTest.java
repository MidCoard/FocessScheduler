package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FocessTask — state machine, cancel, join, onComplete, properties")
class FocessTaskTest {

    // ---- 1. Immediate task completes: PENDING → RUNNING → FINISHED ----

    @Test
    @DisplayName("immediate task completes and transitions PENDING → RUNNING → FINISHED")
    void scheduleImmediateTaskCompletes() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("task-complete");
        Task task = scheduler.schedule(() -> {});
        task.join();
        assertTrue(task.isDone(), "task should be done");
        assertFalse(task.isRunning(), "task should not be running");
        assertFalse(task.isCancelled(), "task should not be cancelled");
        scheduler.shutdown();
    }

    // ---- 2. join() blocks until completion ----

    @Test
    @DisplayName("join() blocks until the task finishes")
    void joinBlocksUntilCompletion() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("join-blocks");
        AtomicBoolean ran = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> ran.set(true));
        task.join();
        assertTrue(ran.get(), "task should have run");
        scheduler.shutdown();
    }

    // ---- 3. join() throws ExecutionException on task failure ----

    @Test
    @DisplayName("join() throws ExecutionException when the task throws")
    void joinThrowsExecutionExceptionOnFailure() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("join-exception");
        Task task = scheduler.schedule(() -> { throw new RuntimeException("boom"); });
        ExecutionException ex = assertThrows(ExecutionException.class, () -> task.join());
        assertInstanceOf(RuntimeException.class, ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());
        assertTrue(task.isDone(), "task should be done even after exception");
        scheduler.shutdown();
    }

    // ---- 4. join() throws CancellationException on cancelled task ----

    @Test
    @DisplayName("join() throws CancellationException on a cancelled task")
    void joinThrowsCancellationException() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("join-cancel");
        Task task = scheduler.schedule(() -> {}, Duration.ofSeconds(10));
        assertTrue(task.cancel(), "cancel should succeed on pending task");
        assertThrows(CancellationException.class, () -> task.join());
        scheduler.shutdown();
    }

    // ---- 5. join(timeout) throws TimeoutException when time expires ----

    @Test
    @DisplayName("join(timeout) throws TimeoutException when the task does not finish in time")
    void joinTimeoutExpires() {
        FocessScheduler scheduler = new FocessScheduler("join-timeout");
        Task task = scheduler.schedule(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThrows(TimeoutException.class, () -> task.join(1, TimeUnit.MILLISECONDS));
        // clean up
        task.cancel(true);
        scheduler.shutdown();
    }

    // ---- 6. join(timeout) succeeds when task finishes within time ----

    @Test
    @DisplayName("join(timeout) returns normally when the task finishes within the timeout")
    void joinTimeoutSucceeds() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("join-timeout-ok");
        Task task = scheduler.schedule(() -> {});
        assertDoesNotThrow(() -> task.join(5, TimeUnit.SECONDS));
        assertTrue(task.isDone());
        scheduler.shutdown();
    }

    // ---- 7. cancel() on PENDING task succeeds ----

    @Test
    @DisplayName("cancel() on a pending task succeeds and prevents execution")
    void cancelPendingTaskSucceeds() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cancel-pending");
        AtomicBoolean ran = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> ran.set(true), Duration.ofSeconds(5));
        assertTrue(task.cancel(), "cancel should succeed on pending task");
        assertTrue(task.isCancelled(), "task should be cancelled");
        Thread.sleep(500);
        assertFalse(ran.get(), "task should not have run");
        scheduler.shutdown();
    }

    // ---- 8. cancel(true) on RUNNING task interrupts ----

    @RepeatedTest(3)
    @DisplayName("cancel(true) on a running task interrupts the thread")
    void cancelRunningWithInterrupt() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cancel-running-int");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); }
        });
        Thread.sleep(500);
        assertTrue(task.isRunning(), "task should be running");
        assertTrue(task.cancel(true), "cancel(true) should succeed");
        Thread.sleep(300);
        assertTrue(interrupted.get(), "task thread should have been interrupted");
        assertTrue(task.isCancelled(), "task should be cancelled");
        scheduler.shutdown();
    }

    // ---- 9. cancel(false) on RUNNING non-period task returns false ----

    @Test
    @DisplayName("cancel(false) on a running non-period task returns false; task completes")
    void cancelRunningWithoutInterrupt() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "cancel-false-running");
        AtomicBoolean completed = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> {
            try { Thread.sleep(2000); completed.set(true); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread.sleep(500);
        assertTrue(task.isRunning(), "task should be running");
        assertFalse(task.cancel(), "cancel(false) on running task should return false");
        assertFalse(task.isCancelled(), "task should not be cancelled");
        task.join(5, TimeUnit.SECONDS);
        assertTrue(completed.get(), "task should have completed");
        assertTrue(task.isDone(), "task should be done");
        scheduler.shutdown();
    }

    // ---- 10. cancel() on FINISHED non-period task returns false ----

    @Test
    @DisplayName("cancel() on a finished non-period task returns false and does not mark it cancelled")
    void cancelFinishedNonPeriodTaskReturnsFalse() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cancel-finished");
        Task task = scheduler.schedule(() -> {});
        task.join();
        assertTrue(task.isDone(), "task should be done");
        assertFalse(task.cancel(), "cancel on finished non-period task should return false");
        assertFalse(task.cancel(true), "cancel(true) on finished non-period task should return false");
        assertFalse(task.isCancelled(), "task should not be cancelled");
        scheduler.shutdown();
    }

    // ---- 10b. cancel() on FINISHED period task succeeds (JDK contract) ----

    @Test
    @DisplayName("cancel() on a finished period task succeeds — period tasks are cancellable between cycles")
    void cancelFinishedPeriodTaskSucceeds() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cancel-period-finished");
        Task task = scheduler.scheduleAtFixedRate(() -> {}, Duration.ZERO, Duration.ofMillis(100), "period");
        Thread.sleep(300); // let a cycle complete
        // The task may be in FINISHED state between cycles — cancel should work
        assertTrue(task.cancel(), "cancel on period task should succeed even when between cycles");
        assertTrue(task.isCancelled(), "period task should be cancelled");
        assertTrue(task.isDone(), "cancelled period task should be done");
        scheduler.shutdown();
    }

    // ---- 10c. Period task exception terminates the task (JDK contract) ----

    @Test
    @DisplayName("unhandled exception terminates a periodic task — JDK ScheduledExecutorService contract")
    void unhandledExceptionTerminatesPeriodicTask() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("period-exception");
        AtomicInteger count = new AtomicInteger(0);
        Task task = scheduler.scheduleAtFixedRate(() -> {
            count.incrementAndGet();
            throw new RuntimeException("periodic-fail");
        }, Duration.ZERO, Duration.ofMillis(100), "period-fail");

        // Wait for the first cycle to complete (with exception) and verify termination
        Thread.sleep(500);
        assertTrue(task.isDone(), "period task with unhandled exception should be done");
        assertFalse(task.isCancelled(), "task should not be cancelled — it terminated by exception");
        assertEquals(1, count.get(), "should have run exactly once before exception terminated it");
        // join() should throw the ExecutionException
        ExecutionException ex = assertThrows(ExecutionException.class, () -> task.join());
        assertEquals("periodic-fail", ex.getCause().getMessage());
        scheduler.shutdown();
    }

    // ---- 10d. Period task with handler continues after handled exception ----

    @Test
    @DisplayName("period task with Consumer handler continues after handled exception")
    void periodTaskWithHandlerContinuesAfterException() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("period-handler-continue");
        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger handlerCalls = new AtomicInteger(0);
        Task task = scheduler.scheduleAtFixedRate(() -> {
            count.incrementAndGet();
            throw new RuntimeException("periodic-fail");
        }, Duration.ZERO, Duration.ofMillis(200), "period-handled", ex -> handlerCalls.incrementAndGet());

        Thread.sleep(700);
        assertTrue(count.get() >= 2, "should have run at least 2 times with handler, got " + count.get());
        assertTrue(handlerCalls.get() >= 2, "handler should have been called at least 2 times, got " + handlerCalls.get());
        assertFalse(task.isDone(), "period task with handler should NOT be done — handler keeps it alive");
        task.cancel();
        assertTrue(task.isDone(), "after cancel, task should be done");
        scheduler.shutdown();
    }

    // ---- 11. Double cancel returns false ----

    @Test
    @DisplayName("calling cancel() twice — second call returns false")
    void doubleCancelReturnsFalse() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("double-cancel");
        Task task = scheduler.schedule(() -> {}, Duration.ofSeconds(10));
        assertTrue(task.cancel(), "first cancel should succeed");
        assertFalse(task.cancel(), "second cancel should return false");
        assertTrue(task.isCancelled());
        scheduler.shutdown();
    }

    // ---- 12. onComplete listener fires on completion ----

    @Test
    @DisplayName("onComplete listener fires when the task finishes")
    void onCompleteFiresOnCompletion() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("on-complete");
        AtomicBoolean listenerFired = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> {});
        task.onComplete(() -> listenerFired.set(true));
        task.join();
        // Brief yield to allow any deferred listener firing to complete
        Thread.sleep(50);
        assertTrue(listenerFired.get(), "completion listener should have fired");
        scheduler.shutdown();
    }

    // ---- 13. onComplete fires immediately if already done ----

    @Test
    @DisplayName("onComplete listener fires immediately when task is already done")
    void onCompleteFiresImmediatelyIfDone() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("on-complete-done");
        AtomicBoolean listenerFired = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> {});
        task.join();
        // Task is already done, so the listener should fire immediately
        task.onComplete(() -> listenerFired.set(true));
        assertTrue(listenerFired.get(), "listener should fire immediately for done task");
        scheduler.shutdown();
    }

    // ---- 14. setExceptionHandler suppresses join() exception ----

    @Test
    @DisplayName("setExceptionHandler suppresses the exception in join()")
    void setExceptionHandlerSuppressesJoinException() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("handler-suppress");
        AtomicReference<ExecutionException> captured = new AtomicReference<>();
        // Use the schedule overload that sets the handler at construction time,
        // because FocessScheduler runs tasks inline immediately
        Task task = scheduler.schedule(
            () -> { throw new RuntimeException("handled"); },
            Duration.ZERO,
            "handled-task",
            captured::set
        );
        task.join(); // should NOT throw
        assertNotNull(captured.get(), "handler should have been called");
        assertEquals("handled", captured.get().getCause().getMessage());
        scheduler.shutdown();
    }

    // ---- 15. Task properties: getName, getScheduler, isPeriod, getDelay, toString ----

    @Test
    @DisplayName("task properties: getName, getScheduler, isPeriod, getDelay, toString")
    void taskProperties() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("props");
        Task task = scheduler.schedule(() -> {}, Duration.ofMillis(500), "my-task");
        assertTrue(task.getName().contains("my-task"), "name should contain 'my-task', got: " + task.getName());
        assertSame(scheduler, task.getScheduler(), "scheduler should be the same instance");
        assertFalse(task.isPeriod(), "non-period task should not be period");
        // getDelay is on Delayed interface; FocessTask implements it
        long delayMs = ((Delayed) task).getDelay(TimeUnit.MILLISECONDS);
        assertTrue(delayMs > 0, "delay should be positive for pending task, got: " + delayMs);
        assertEquals(task.getName(), task.toString(), "toString should return name");
        task.cancel();
        scheduler.shutdown();
    }
}
