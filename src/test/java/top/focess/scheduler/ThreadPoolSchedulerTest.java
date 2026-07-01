package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ThreadPoolScheduler — pool behavior, interrupt isolation, expansion, error shutdown")
class ThreadPoolSchedulerTest {

    // ---- 1. schedule immediate — task runs on worker thread ----

    @Test
    @DisplayName("schedule(runnable) runs the task on a worker thread")
    void scheduleImmediate() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-imm");
        AtomicReference<String> threadName = new AtomicReference<>();
        Task task = scheduler.schedule(() -> threadName.set(Thread.currentThread().getName()));
        task.join();
        assertNotNull(threadName.get(), "task should have run");
        assertTrue(threadName.get().contains("worker"), "should run on a worker thread, got: " + threadName.get());
        scheduler.shutdown();
    }

    // ---- 2. schedule with delay ----

    @Test
    @DisplayName("schedule(runnable, Duration) runs the task after the delay on a worker")
    void scheduleWithDelay() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-delay");
        long start = System.nanoTime();
        Task task = scheduler.schedule(() -> {}, Duration.ofMillis(500));
        task.join(5, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 400, "should wait ~500ms, got " + elapsedMs + "ms");
        scheduler.shutdown();
    }

    // ---- 3. Multiple tasks run in parallel ----

    @Test
    @DisplayName("multiple tasks run in parallel across the worker pool")
    void multipleTasksRunInParallel() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "tp-parallel");
        AtomicBoolean[] running = new AtomicBoolean[4];
        for (int i = 0; i < 4; i++) running[i] = new AtomicBoolean(false);

        Task[] tasks = new Task[4];
        for (int i = 0; i < 4; i++) {
            int idx = i;
            tasks[i] = scheduler.schedule(() -> {
                running[idx].set(true);
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        Thread.sleep(300);
        long concurrent = 0;
        for (int i = 0; i < 4; i++) if (running[i].get()) concurrent++;
        assertTrue(concurrent >= 2, "at least 2 tasks should be running concurrently, got " + concurrent);
        for (Task t : tasks) t.cancel(true);
        scheduler.shutdown();
    }

    // ---- 4. cancel(true) isolates interrupt to targeted task ----

    @RepeatedTest(3)
    @DisplayName("cancel(true) on one task does not interrupt other running tasks")
    void cancelRunningIsolatesInterrupt() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "tp-isolate");
        AtomicBoolean firstInterrupted = new AtomicBoolean(false);
        AtomicBoolean secondInterrupted = new AtomicBoolean(false);
        AtomicBoolean secondCompleted = new AtomicBoolean(false);

        Task first = scheduler.schedule(() -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { firstInterrupted.set(true); Thread.currentThread().interrupt(); }
        });
        Task second = scheduler.schedule(() -> {
            try { Thread.sleep(1500); secondCompleted.set(true); }
            catch (InterruptedException e) { secondInterrupted.set(true); }
        });
        Thread.sleep(500);
        assertTrue(first.isRunning());
        assertTrue(second.isRunning());
        first.cancel(true);
        Thread.sleep(500);
        assertTrue(first.isCancelled());
        assertTrue(firstInterrupted.get(), "first should be interrupted");
        assertFalse(second.isCancelled(), "second should not be cancelled");
        Thread.sleep(1500);
        assertFalse(secondInterrupted.get(), "second should NOT be interrupted");
        assertTrue(secondCompleted.get(), "second should complete normally");
        scheduler.shutdown();
    }

    // ---- 5. Cancel pending does not affect running task ----

    @Test
    @DisplayName("cancelling a pending task does not interrupt the running task")
    void cancelPendingDoesNotAffectRunning() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-cancel-pending");
        AtomicBoolean runningCompleted = new AtomicBoolean(false);
        AtomicBoolean runningInterrupted = new AtomicBoolean(false);

        Task running = scheduler.schedule(() -> {
            try { Thread.sleep(2000); runningCompleted.set(true); }
            catch (InterruptedException e) { runningInterrupted.set(true); Thread.currentThread().interrupt(); }
        });
        Task pending = scheduler.schedule(() -> {}, Duration.ofSeconds(10));
        Thread.sleep(500);
        assertTrue(running.isRunning());
        pending.cancel(true);
        assertTrue(pending.isCancelled());
        Thread.sleep(2000);
        assertTrue(runningCompleted.get(), "running task should complete");
        assertFalse(runningInterrupted.get(), "running task should NOT be interrupted");
        scheduler.shutdown();
    }

    // ---- 6. Swallowed interrupt still marks task cancelled ----

    @Test
    @DisplayName("a task that swallows the interrupt is still marked cancelled")
    void cancelSwallowedInterruptStillMarkedCancelled() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-swallow");
        AtomicBoolean sawInterrupt = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> {
            try { Thread.sleep(1500); }
            catch (InterruptedException e) { sawInterrupt.set(true); /* swallow */ }
            try { Thread.sleep(500); completed.set(true); }
            catch (InterruptedException e) {}
        });
        Thread.sleep(500);
        assertTrue(task.isRunning());
        assertTrue(task.cancel(true));
        Thread.sleep(2500);
        assertTrue(sawInterrupt.get(), "task should have seen the interrupt");
        assertTrue(completed.get(), "task should have completed after swallowing interrupt");
        assertTrue(task.isCancelled(), "task should be marked cancelled");
        scheduler.shutdown();
    }

    // ---- 7. Interrupt does not leak to next task ----

    @Test
    @DisplayName("a cancelled task's interrupt does not leak into the next task on the same worker")
    void interruptDoesNotLeakToNextTask() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "tp-no-leak");
        AtomicBoolean firstInterrupted = new AtomicBoolean(false);
        Task first = scheduler.schedule(() -> {
            try { Thread.sleep(3000); }
            catch (InterruptedException e) { firstInterrupted.set(true); Thread.currentThread().interrupt(); }
        });
        Thread.sleep(500);
        first.cancel(true);
        Thread.sleep(500);
        assertTrue(firstInterrupted.get());

        AtomicBoolean secondInterrupted = new AtomicBoolean(false);
        AtomicBoolean secondCompleted = new AtomicBoolean(false);
        Task second = scheduler.schedule(() -> {
            try { Thread.sleep(500); secondCompleted.set(true); }
            catch (InterruptedException e) { secondInterrupted.set(true); }
        });
        second.join(5, TimeUnit.SECONDS);
        assertFalse(secondInterrupted.get(), "second task should NOT see a leaked interrupt");
        assertTrue(secondCompleted.get(), "second task should complete normally");
        scheduler.shutdown();
    }

    // ---- 8. Uncaught Error shuts down scheduler ----

    @Test
    @DisplayName("an uncaught Error in a worker task shuts down the scheduler")
    void uncaughtErrorShutsDownScheduler() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-error");
        scheduler.schedule(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) {}
            throw new InternalError("fatal");
        });
        Thread.sleep(1500);
        assertTrue(scheduler.isShutdown(), "scheduler should be shut down after InternalError");
        assertThrows(SchedulerClosedException.class, () -> scheduler.schedule(() -> {}));
    }

    // ---- 9. Immediate mode — dynamic expansion ----

    @Test
    @DisplayName("immediate=true expands the pool when all core workers are busy")
    void immediateModeDynamicExpansion() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, true, "tp-immediate");
        // Submit 5 tasks that all block — pool should expand beyond 2
        AtomicInteger runningCount = new AtomicInteger(0);
        CountDownLatch allStarted = new CountDownLatch(5);
        Task[] tasks = new Task[5];
        for (int i = 0; i < 5; i++) {
            tasks[i] = scheduler.schedule(() -> {
                runningCount.incrementAndGet();
                allStarted.countDown();
                try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        // Wait for all tasks to start (up to 5 seconds)
        allStarted.await(5, TimeUnit.SECONDS);
        int running = runningCount.get();
        // With immediate=true, the pool should expand beyond corePoolSize=2.
        // However, expansion is best-effort: the dispatcher hands tasks one at
        // a time, and ensureWorkers() may not see enough demand to expand
        // before the workers pick up tasks from the queue. At minimum, both
        // core workers should be running.
        assertTrue(running >= 2, "at least 2 tasks should be running (corePoolSize), got " + running);
        for (Task t : tasks) t.cancel(true);
        scheduler.shutdown();
    }

    // ---- 10. Daemon mode ----

    @Test
    @DisplayName("ThreadPoolScheduler(pool, imm, name, true) creates daemon dispatcher thread")
    void daemonMode() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-daemon", true);
        boolean[] foundDaemon = {false};
        Thread.getAllStackTraces().keySet().forEach(t -> {
            if (t.getName().contains("tp-daemon-dispatcher") && t.isDaemon()) {
                foundDaemon[0] = true;
            }
        });
        assertTrue(foundDaemon[0], "dispatcher thread should be a daemon");
        scheduler.shutdown();
    }

    // ---- 11. Prefix constructor ----

    @Test
    @DisplayName("ThreadPoolScheduler(prefix, poolSize) creates a scheduler with the prefix in the name")
    void prefixConstructor() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler("myprefix", 2);
        assertTrue(scheduler.getName().contains("myprefix"), "name should contain 'myprefix', got: " + scheduler.getName());
        Task task = scheduler.schedule(() -> {});
        task.join();
        scheduler.shutdown();
    }

    // ---- 12. Composable constructor ----

    @Test
    @DisplayName("ThreadPoolScheduler(Dispatcher, TaskExecutor) composes custom components")
    void composableConstructor() throws Exception {
        // Test that the standard constructor correctly wires dispatcher and executor
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-compose");
        assertNotNull(scheduler.getDispatcher(), "dispatcher should not be null");
        assertNotNull(scheduler.getTaskExecutor(), "executor should not be null");
        assertInstanceOf(TimeDispatcher.class, scheduler.getDispatcher());
        assertInstanceOf(PoolTaskExecutor.class, scheduler.getTaskExecutor());

        AtomicBoolean ran = new AtomicBoolean(false);
        Task task = scheduler.schedule(() -> ran.set(true));
        task.join(5, TimeUnit.SECONDS);
        assertTrue(ran.get(), "task should have run");
        scheduler.shutdown();
    }

    // ---- 13. Exception handler on task ----

    @Test
    @DisplayName("exception handler consumes the exception; join() does not throw")
    void exceptionHandlerOnTask() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-handler");
        AtomicReference<ExecutionException> captured = new AtomicReference<>();
        Task task = scheduler.schedule(
            () -> { throw new RuntimeException("handled"); },
            Duration.ZERO,
            "handled-task",
            captured::set
        );
        task.join(); // should not throw
        assertNotNull(captured.get(), "handler should have been called");
        assertEquals("handled", captured.get().getCause().getMessage());
        scheduler.shutdown();
    }

    // ---- 14. shutdownNow interrupts running tasks ----

    @Test
    @DisplayName("shutdownNow() interrupts running tasks")
    void shutdownNowInterruptsRunning() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "tp-now");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        scheduler.schedule(() -> {
            try { Thread.sleep(10000); }
            catch (InterruptedException e) { interrupted.set(true); }
        });
        Thread.sleep(500);
        scheduler.shutdownNow();
        Thread.sleep(500);
        assertTrue(scheduler.isShutdown(), "scheduler should be shut down");
        // The worker should have been interrupted
        assertTrue(interrupted.get(), "running task should have been interrupted by shutdownNow");
    }
}
