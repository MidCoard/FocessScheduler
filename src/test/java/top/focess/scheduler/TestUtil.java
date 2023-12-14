package top.focess.scheduler;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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
        scheduler.close();
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
                    fail();
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
        scheduler.close();
    }

    @RepeatedTest(5)
    void testScheduler3() {
        Scheduler scheduler = new ThreadPoolScheduler( 5,false,"test-3");

        Task task = scheduler.run(()->{
            try {
                sleep(1000);
            } catch (InterruptedException e) {
                fail();
            }
            throw new NullPointerException();
        });

        assertThrows(ExecutionException.class, task::join);
        assertTrue(task.isFinished());

        Task task1 = scheduler.run(()->{
            try {
                sleep(1000);
            } catch (InterruptedException e) {
                fail();
            }
            throw new InternalError();
        });
        assertThrows(ExecutionException.class, task::join);
        assertTrue(task.isFinished());
        AtomicInteger count = new AtomicInteger(0);
        List<Task> tasks = Lists.newArrayList();
        for (int i = 0; i<5;i++) {
            int finalI = i;
            tasks.add(scheduler.run(()->{
                try {
                    sleep(1000 * (finalI));
                } catch (InterruptedException e) {
                    fail();
                }
                throw new InternalError();
            }, "test-" + i, (executionException)->{
                assertInstanceOf(InternalError.class, executionException.getCause());
                count.incrementAndGet();
            }));
        }
        for (Task task2 : tasks)
            assertDoesNotThrow(()->task2.join());
        assertEquals(5, count.get());
        scheduler.close();
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
        scheduler.close();
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
    }
}
