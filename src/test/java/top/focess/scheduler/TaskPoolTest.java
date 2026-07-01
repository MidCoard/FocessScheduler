package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.focess.scheduler.exceptions.PeriodTaskException;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TaskPool — AndTaskPool, OrTaskPool, custom CompletionStrategy, edge cases")
class TaskPoolTest {

    // ---- 1. AndTaskPool waits for all tasks ----

    @Test
    @DisplayName("AndTaskPool completes only when ALL tasks finish")
    void andTaskPoolWaitsForAll() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "pool-and");
        AtomicInteger callbackCount = new AtomicInteger(0);
        AndTaskPool pool = new AndTaskPool(scheduler, () -> callbackCount.incrementAndGet());

        AtomicBoolean task1Done = new AtomicBoolean(false);
        AtomicBoolean task2Done = new AtomicBoolean(false);
        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) {}
            task1Done.set(true);
        }));
        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) {}
            task2Done.set(true);
        }));

        pool.join();
        assertTrue(task1Done.get(), "task1 should be done");
        assertTrue(task2Done.get(), "task2 should be done");
        assertEquals(1, callbackCount.get(), "callback should have fired once");
        assertTrue(pool.isFinished(), "pool should be finished");
        scheduler.shutdown();
    }

    // ---- 2. OrTaskPool completes on first task ----

    @Test
    @DisplayName("OrTaskPool completes when ANY task finishes")
    void orTaskPoolCompletesOnFirst() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "pool-or");
        AtomicBoolean callbackFired = new AtomicBoolean(false);
        OrTaskPool pool = new OrTaskPool(scheduler, () -> callbackFired.set(true));

        // Task 1 is slow, task 2 is fast
        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) {}
        }));
        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) {}
        }));

        pool.join();
        assertTrue(callbackFired.get(), "callback should have fired");
        assertTrue(pool.isFinished(), "pool should be finished");
        scheduler.shutdown();
    }

    // ---- 3. Custom N-of-M strategy ----

    @Test
    @DisplayName("custom CompletionStrategy: 2-of-3 triggers pool completion")
    void customNOfMStrategy() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "pool-nofm");
        AtomicInteger completions = new AtomicInteger(0);
        AtomicBoolean poolFinished = new AtomicBoolean(false);

        TaskPool pool = new TaskPool(scheduler, (p, task, remaining) -> {
            return completions.incrementAndGet() >= 2;
        }, () -> poolFinished.set(true));

        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }));
        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }));
        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException e) {}
        }));

        pool.join();
        assertTrue(poolFinished.get(), "pool should be finished after 2-of-3");
        assertTrue(pool.isFinished());
        scheduler.shutdown();
    }

    // ---- 4. Adding a period task throws PeriodTaskException ----

    @Test
    @DisplayName("addTask(periodTask) throws PeriodTaskException")
    void addPeriodTaskThrows() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "pool-period");
        TaskPool pool = new TaskPool(scheduler, TaskPool.ALL, () -> {});
        Task periodTask = scheduler.scheduleAtFixedRate(
            () -> {}, Duration.ZERO, Duration.ofSeconds(1), "period"
        );
        assertThrows(PeriodTaskException.class, () -> pool.addTask(periodTask));
        periodTask.cancel();
        scheduler.shutdown();
    }

    // ---- 5. isFinished() is false before completion ----

    @Test
    @DisplayName("isFinished() returns false while tasks are still running")
    void isFinishedFalseBeforeCompletion() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "pool-not-finished");
        TaskPool pool = new TaskPool(scheduler, TaskPool.ALL, () -> {});
        pool.addTask(scheduler.schedule(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) {}
        }));
        Thread.sleep(300);
        assertFalse(pool.isFinished(), "pool should not be finished yet");
        scheduler.shutdown();
    }

    // ---- 6. isFinished() is true after completion ----

    @Test
    @DisplayName("isFinished() returns true after the strategy is satisfied")
    void isFinishedTrueAfterCompletion() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "pool-finished");
        OrTaskPool pool = new OrTaskPool(scheduler, () -> {});
        pool.addTask(scheduler.schedule(() -> {}));
        pool.join();
        assertTrue(pool.isFinished(), "pool should be finished");
        scheduler.shutdown();
    }

    // ---- 7. Callback fires on completion ----

    @Test
    @DisplayName("the Runnable callback is scheduled when the pool completes")
    void callbackFiresOnCompletion() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "pool-callback");
        AtomicBoolean callbackRan = new AtomicBoolean(false);
        OrTaskPool pool = new OrTaskPool(scheduler, () -> callbackRan.set(true));
        pool.addTask(scheduler.schedule(() -> {}));
        pool.join();
        // The callback is scheduled as a task, so we need a brief wait
        Thread.sleep(500);
        assertTrue(callbackRan.get(), "callback should have run");
        scheduler.shutdown();
    }

    // ---- 8. join() on already-finished pool returns immediately ----

    @Test
    @DisplayName("join() on an already-finished pool returns immediately")
    void joinOnAlreadyFinishedPool() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "pool-join-done");
        OrTaskPool pool = new OrTaskPool(scheduler, () -> {});
        pool.addTask(scheduler.schedule(() -> {}));
        pool.join();
        // Second join should not block or throw
        assertDoesNotThrow(() -> pool.join());
        scheduler.shutdown();
    }

    // ---- 9. Callback task exception propagates through join() ----

    @Test
    @DisplayName("if the pool's callback task throws, join() propagates the exception")
    void callbackExceptionPropagatesThroughJoin() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "pool-cb-exception");
        OrTaskPool pool = new OrTaskPool(scheduler, () -> { throw new RuntimeException("callback-fail"); });
        pool.addTask(scheduler.schedule(() -> {}));
        ExecutionException ex = assertThrows(ExecutionException.class, () -> pool.join());
        assertEquals("callback-fail", ex.getCause().getMessage());
        scheduler.shutdown();
    }
}
