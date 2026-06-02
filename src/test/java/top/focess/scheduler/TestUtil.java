package top.focess.scheduler;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import top.focess.scheduler.exceptions.SchedulerClosedException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Util Test")
public class TestUtil {

    @Test
    void testScheduler() {
        Scheduler scheduler = new FocessScheduler("Test");
        Task task = scheduler.run(() -> {
            System.out.println(1);
        });
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            fail();
        }
        assertFalse(task.cancel());
        assertFalse(task.isCancelled());
        assertTrue(task.isFinished());
        assertFalse(task.isRunning());
        Task task1 = scheduler.run(() -> {
            System.out.println(2);
        }, Duration.ofSeconds(1));
        assertTimeoutPreemptively(Duration.ofSeconds(2), ()->task1.join());
        assertTrue(task1.isFinished());
        Task task2 = scheduler.run(() -> {
            try {
                Thread.sleep(2000);
                System.out.println(3);
            } catch (InterruptedException e) {
                fail();
            }
        });
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            fail();
        }
        assertTrue(task2.isRunning());
        try {
            task2.join();
        } catch (Exception e) {
            fail();
        }
        assertTrue(task2.isFinished());
        Task task3 = scheduler.run(() -> {
            System.out.println(4);
        }, Duration.ofSeconds(1));
        assertTrue(task3.cancel());
        assertTrue(task3.isCancelled());
        scheduler.shutdown();
    }

    @RepeatedTest(5)
    void testScheduler2() {
        Scheduler scheduler = new ThreadPoolScheduler( 10,false,"test-2");
        List<Task> taskList = Lists.newArrayList();
        for (int i = 0; i < 20; i++) {
            int finalI = i;
            taskList.add(scheduler.run(() -> {
                try {
                    sleep(3000);
                } catch (InterruptedException e) {
                    // cancel(true) now interrupts the running task instead of using the
                    // unsafe Thread#stop(); treat the interruption as the cancellation signal
                    Thread.currentThread().interrupt();
                    return;
                }
                System.out.println(finalI);
            }));
        }
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            fail();
        }
        assertEquals(10, taskList.stream().filter(Task::isRunning).count());
        assertTrue(taskList.get(0).cancel(true));
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            fail();
        }
        assertEquals(10, taskList.stream().filter(Task::isRunning).count());
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            fail();
        }
        scheduler.shutdown();
    }

    @RepeatedTest(5)
    void testSchedulerTaskException() {
        final Scheduler scheduler = new ThreadPoolScheduler(5, false, "test-exception");

        final Task task = scheduler.run(() -> {
            try {
                sleep(1000);
            } catch (final InterruptedException e) {
                fail();
            }
            throw new NullPointerException();
        });

        assertThrows(ExecutionException.class, task::join);
        assertTrue(task.isFinished());
        scheduler.shutdown();
    }

    @RepeatedTest(5)
    void testSchedulerExceptionHandler() {
        final Scheduler scheduler = new ThreadPoolScheduler(5, false, "test-handler");
        final AtomicInteger count = new AtomicInteger(0);
        final List<Task> tasks = Lists.newArrayList();
        for (int i = 0; i < 5; i++) {
            final int delay = i;
            tasks.add(scheduler.run(() -> {
                try {
                    sleep(1000 * delay);
                } catch (final InterruptedException e) {
                    fail();
                }
                throw new RuntimeException();
            }, "test-" + i, executionException -> {
                assertInstanceOf(RuntimeException.class, executionException.getCause());
                count.incrementAndGet();
            }));
        }
        for (final Task task : tasks)
            assertDoesNotThrow(() -> task.join());
        assertEquals(5, count.get());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("an uncaught Error in a worker task shuts down the scheduler")
    void testSchedulerInternalErrorShutsDown() throws InterruptedException {
        final ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "test-error-shutdown");

        scheduler.run(() -> {
            try {
                sleep(500);
            } catch (final InterruptedException e) {
                fail();
            }
            throw new InternalError();
        });

        // give the worker time to hit the error and the UCE handler to call scheduler.shutdown()
        sleep(1500);
        assertTrue(scheduler.isShutdown());

        // submitting a new task on a shut-down scheduler must throw
        assertThrows(SchedulerClosedException.class, () -> scheduler.run(() -> {}));
    }

    @Test
    void testScheduler4() throws Exception {
        Scheduler scheduler = new ThreadPoolScheduler(10, true, "test-4");
        List<Task> tasks = Lists.newArrayList();
        for (int i = 0;i<5;i++) {
            int finalI = i;
            tasks.add(scheduler.run(()-> System.out.println(finalI),Duration.ofSeconds(1),finalI + ""));
        }
        for (Task task : tasks)
            assertDoesNotThrow(()->task.join());
        scheduler.shutdown();
    }

    @Test
    void testTaskPool() throws ExecutionException, InterruptedException {
        ThreadPoolScheduler threadPoolScheduler = new ThreadPoolScheduler(10, false, "ff",true);
        Task task1 = threadPoolScheduler.run(()->{
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
            }
        });
        Task task2 = threadPoolScheduler.run(()->{
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        });
        long now = System.currentTimeMillis();
        AtomicBoolean flag = new AtomicBoolean(false);
        TaskPool andTaskPool = new OrTaskPool(threadPoolScheduler, ()-> {
            flag.set(true);
            assertTrue(System.currentTimeMillis() - now > 1000);
        });
        andTaskPool.addTask(task1);
        andTaskPool.addTask(task2);

        andTaskPool.join();
        assertTrue(flag.get());
        threadPoolScheduler.shutdown();
    }

    @Test
    void testFocessScheduler() throws InterruptedException, ExecutionException {
        FocessScheduler focessScheduler = new FocessScheduler("Test");
        Task task0 = focessScheduler.run(() -> {
            System.out.println(1);
        }, Duration.ofSeconds(9));
        Task task = focessScheduler.run(() -> {
            System.out.println(10);
        }, Duration.ofSeconds(3));
        Thread.sleep(4000);
        assertTrue(task.isFinished());
        assertTrue(task0.cancel(true));
        assertTrue(task0.isCancelled());
        long current = System.currentTimeMillis();
        Task task1 = focessScheduler.run(() -> {
            System.out.println(11);
        }, Duration.ofSeconds(3));
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            task1.join();
            assertTrue(System.currentTimeMillis() - current > 3000);
            System.out.println(System.currentTimeMillis() - current );
        });
        focessScheduler.shutdown();
    }

    @Test
    void testFocessSchedulerCancelRunning() throws InterruptedException {
        FocessScheduler focessScheduler = new FocessScheduler("cancel-running");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        Task task = focessScheduler.run(() -> {
            try {
                sleep(5000);
                completed.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        sleep(1000);
        assertTrue(task.isRunning());
        assertTrue(task.cancel(true));
        sleep(500);
        assertTrue(task.isCancelled());
        assertTrue(interrupted.get());
        assertFalse(completed.get());
        // the scheduler thread must survive the interrupt and keep running later tasks
        AtomicBoolean ran = new AtomicBoolean(false);
        Task after = focessScheduler.run(() -> ran.set(true));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> after.join());
        assertTrue(ran.get());
        focessScheduler.shutdown();
    }

    @RepeatedTest(5)
    void testScheduler5() {
        Scheduler scheduler = new FocessScheduler("test-5");
        AtomicInteger atomicInteger = new AtomicInteger(0);
        Task task = scheduler.runTimer(()->{
            atomicInteger.incrementAndGet();
            throw new NullPointerException();
        }, Duration.ofSeconds(0), Duration.ofSeconds(1),"test");
        try {
            sleep(3000);
        } catch (InterruptedException e) {
            fail();
        }
        assertTrue(task.isPeriod());
        task.cancel();
        try {
            sleep(500);
        } catch (InterruptedException e) {
            fail();
        }
        assertTrue(task.isCancelled());
        assertNotEquals(1, atomicInteger.get());
        scheduler.shutdown();
    }

    private static void sleepQuietly(final long millis) {
        try {
            sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("the controlling thread was unexpectedly interrupted");
        }
    }

    @Test
    @DisplayName("cancel(false) on a pending task removes it before it runs")
    void testCancelPendingTask() {
        final FocessScheduler scheduler = new FocessScheduler("cancel-pending");
        final AtomicBoolean ran = new AtomicBoolean(false);
        final Task task = scheduler.run(() -> ran.set(true), Duration.ofSeconds(2));
        // a task that has not started yet can always be cancelled, even without interruption
        assertTrue(task.cancel());
        assertTrue(task.isCancelled());
        // cancelling an already-cancelled task is a no-op
        assertFalse(task.cancel());
        assertFalse(task.cancel(true));
        sleepQuietly(2500);
        assertFalse(ran.get());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("cancelling a finished task returns false and does not mark it cancelled")
    void testCancelFinishedTask() throws Exception {
        final FocessScheduler scheduler = new FocessScheduler("cancel-finished");
        final Task task = scheduler.run(() -> {});
        task.join();
        assertTrue(task.isFinished());
        assertFalse(task.cancel());
        assertFalse(task.cancel(true));
        assertFalse(task.isCancelled());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("cancel(false) cannot stop a running non-period task; it runs to completion")
    void testCancelRunningWithoutInterrupt() {
        final FocessScheduler scheduler = new FocessScheduler("cancel-false");
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Task task = scheduler.run(() -> {
            sleepQuietly(2000);
            completed.set(true);
        });
        sleepQuietly(700);
        assertTrue(task.isRunning());
        // cancel(false) must not interrupt a running non-period task
        assertFalse(task.cancel());
        assertFalse(task.isCancelled());
        sleepQuietly(2000);
        assertTrue(completed.get());
        assertTrue(task.isFinished());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("join() on a cancelled task throws CancellationException")
    void testJoinCancelledThrows() {
        final FocessScheduler scheduler = new FocessScheduler("join-cancel");
        final Task task = scheduler.run(() -> {}, Duration.ofSeconds(3));
        assertTrue(task.cancel());
        assertThrows(CancellationException.class, task::join);
        scheduler.shutdown();
    }

    @RepeatedTest(3)
    @DisplayName("ThreadPoolScheduler cancel(true) interrupts only the targeted running task")
    void testThreadPoolCancelRunningIsolated() {
        final ThreadPoolScheduler scheduler = new ThreadPoolScheduler(4, false, "cancel-isolated");
        final AtomicBoolean firstInterrupted = new AtomicBoolean(false);
        final AtomicBoolean secondInterrupted = new AtomicBoolean(false);
        final AtomicBoolean secondCompleted = new AtomicBoolean(false);
        final Task first = scheduler.run(() -> {
            try {
                sleep(3000);
            } catch (final InterruptedException e) {
                firstInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        final Task second = scheduler.run(() -> {
            try {
                sleep(1500);
                secondCompleted.set(true);
            } catch (final InterruptedException e) {
                secondInterrupted.set(true);
            }
        });
        sleepQuietly(700);
        assertTrue(first.isRunning());
        assertTrue(second.isRunning());
        assertTrue(first.cancel(true));
        sleepQuietly(500);
        assertTrue(first.isCancelled());
        assertTrue(firstInterrupted.get());
        // the interrupt must be isolated to the first task's worker thread
        assertFalse(second.isCancelled());
        sleepQuietly(1500);
        assertFalse(secondInterrupted.get());
        assertTrue(secondCompleted.get());
        assertTrue(second.isFinished());
        // the pool must keep serving new tasks after a cancellation
        final AtomicBoolean ran = new AtomicBoolean(false);
        final Task after = scheduler.run(() -> ran.set(true));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> after.join());
        assertTrue(ran.get());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("a running task that swallows the interrupt finishes but is still marked cancelled")
    void testCancelRunningSwallowInterrupt() {
        final ThreadPoolScheduler scheduler = new ThreadPoolScheduler(2, false, "swallow");
        final AtomicBoolean sawInterrupt = new AtomicBoolean(false);
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Task task = scheduler.run(() -> {
            try {
                sleep(1500);
            } catch (final InterruptedException e) {
                sawInterrupt.set(true);
                // deliberately swallow the interrupt and keep working
            }
            sleepQuietly(500);
            completed.set(true);
        });
        sleepQuietly(500);
        assertTrue(task.isRunning());
        assertTrue(task.cancel(true));
        sleepQuietly(1800);
        assertTrue(sawInterrupt.get());
        // cooperative cancellation: a swallowed interrupt still runs to completion
        assertTrue(completed.get());
        // but the task is still reported as cancelled
        assertTrue(task.isCancelled());
        // the worker that ran the swallowing task must be reusable, without a leftover interrupt
        final AtomicBoolean ran = new AtomicBoolean(false);
        final Task after = scheduler.run(() -> ran.set(true));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> after.join());
        assertTrue(ran.get());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("a cancelled interrupt does not leak into the next task on the same worker")
    void testInterruptDoesNotLeakToNextTask() {
        final ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "no-leak");
        final AtomicBoolean firstInterrupted = new AtomicBoolean(false);
        final Task first = scheduler.run(() -> {
            try {
                sleep(3000);
            } catch (final InterruptedException e) {
                firstInterrupted.set(true);
                // re-assert the interrupt flag; the worker must clear it before the next task
                Thread.currentThread().interrupt();
            }
        });
        sleepQuietly(500);
        assertTrue(first.cancel(true));
        sleepQuietly(500);
        assertTrue(firstInterrupted.get());
        final AtomicBoolean secondInterrupted = new AtomicBoolean(false);
        final AtomicBoolean secondCompleted = new AtomicBoolean(false);
        final Task second = scheduler.run(() -> {
            try {
                sleep(800);
                secondCompleted.set(true);
            } catch (final InterruptedException e) {
                secondInterrupted.set(true);
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> second.join());
        assertFalse(secondInterrupted.get());
        assertTrue(secondCompleted.get());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("FocessScheduler: cancelling a pending task does not interrupt the running one")
    void testFocessSchedulerCancelPendingKeepsRunning() {
        final FocessScheduler scheduler = new FocessScheduler("cancel-pending-running");
        final AtomicBoolean firstCompleted = new AtomicBoolean(false);
        final AtomicBoolean firstInterrupted = new AtomicBoolean(false);
        final AtomicBoolean secondRan = new AtomicBoolean(false);
        final Task running = scheduler.run(() -> {
            try {
                sleep(2000);
                firstCompleted.set(true);
            } catch (final InterruptedException e) {
                firstInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        final Task pending = scheduler.run(() -> secondRan.set(true), Duration.ofSeconds(5));
        sleepQuietly(700);
        assertTrue(running.isRunning());
        // cancelling a not-yet-running task with interrupt must not disturb the running one
        assertTrue(pending.cancel(true));
        sleepQuietly(2000);
        assertTrue(firstCompleted.get());
        assertFalse(firstInterrupted.get());
        assertTrue(pending.isCancelled());
        assertFalse(secondRan.get());
        scheduler.shutdown();
    }
}
