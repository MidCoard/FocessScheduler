package top.focess.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Callback - getNow, get, get(timeout), exception handlers")
class FocessCallbackTest {

    @Test
    @DisplayName("getNow() returns the computed value after completion")
    void getNowReturnsValue() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cb-getnow");
        Callback<Integer> cb = scheduler.submit(() -> 42, Duration.ZERO);
        cb.join();
        assertEquals(42, cb.getNow(), "getNow should return 42");
        scheduler.shutdown();
    }

    @Test
    @DisplayName("get() blocks until completion and returns the value")
    void getBlocksAndReturnsValue() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cb-get");
        Callback<String> cb = scheduler.submit(() -> {
            Thread.sleep(300);
            return "hello";
        }, Duration.ZERO);
        assertEquals("hello", cb.get(), "get() should return 'hello'");
        scheduler.shutdown();
    }

    @Test
    @DisplayName("get(timeout) returns the value when the callback completes within the timeout")
    void getWithTimeoutReturnsValue() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cb-get-timeout");
        Callback<Integer> cb = scheduler.submit(() -> 99, Duration.ZERO);
        assertEquals(99, cb.get(5, TimeUnit.SECONDS), "get(timeout) should return 99");
        scheduler.shutdown();
    }

    @Test
    @DisplayName("get(short timeout) throws TimeoutException when the callback is slow")
    void getWithTimeoutThrowsTimeout() {
        FocessScheduler scheduler = new FocessScheduler("cb-get-timeout-fail");
        Callback<Integer> cb = scheduler.submit(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return 1;
        }, Duration.ZERO);
        assertThrows(TimeoutException.class, () -> cb.get(1, TimeUnit.MILLISECONDS));
        cb.cancel(true);
        scheduler.shutdown();
    }

    @Test
    @DisplayName("getNow() throws TaskNotFinishedException when the callback has not finished")
    void getNowThrowsIfNotFinished() throws Exception {
        ThreadPoolScheduler scheduler = new ThreadPoolScheduler(1, false, "cb-not-finished");
        Task blocker = scheduler.schedule(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread.sleep(200);
        Callback<Integer> cb = scheduler.submit(() -> 1, Duration.ZERO);
        assertThrows(TaskNotFinishedException.class, () -> cb.getNow());
        blocker.cancel(true);
        scheduler.shutdown();
    }

    @Test
    @DisplayName("getNow() throws CancellationException on a cancelled callback")
    void getNowThrowsCancellationOnCancel() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cb-cancel");
        Callback<Integer> cb = scheduler.submit(() -> 1, Duration.ofSeconds(10));
        cb.cancel();
        assertThrows(CancellationException.class, () -> cb.getNow());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("getNow() throws ExecutionException when the callback threw an exception")
    void getNowThrowsExecutionExceptionOnFailure() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cb-fail");
        Callback<Integer> cb = scheduler.submit(() -> { throw new RuntimeException("fail"); }, Duration.ZERO);
        try { cb.join(); } catch (ExecutionException e) { /* expected */ }
        assertThrows(ExecutionException.class, () -> cb.getNow());
        scheduler.shutdown();
    }

    @Test
    @DisplayName("submit with Function handler returns fallback value via getNow()")
    void setExceptionHandlerFunctionReturnsFallback() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cb-fn-handler");
        // Use the submit overload that sets the handler at construction time
        // because FocessScheduler runs tasks inline immediately
        Callback<Integer> cb = scheduler.submit(
            () -> { throw new RuntimeException("oops"); },
            Duration.ZERO,
            "fn-cb",
            ex -> -1
        );
        cb.join();
        assertEquals(-1, cb.getNow(), "getNow should return the fallback value from the handler");
        scheduler.shutdown();
    }

    @Test
    @DisplayName("setExceptionHandler(Consumer) suppresses join(); getNow throws ExecutionException")
    void setExceptionHandlerConsumerBridgesToFunction() throws Exception {
        // Use a delayed task so the handler can be set before execution
        FocessScheduler scheduler = new FocessScheduler("cb-consumer-handler");
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        Callback<Integer> cb = scheduler.submit(() -> { throw new RuntimeException("oops"); }, Duration.ofMillis(200));
        cb.setExceptionHandler((Consumer<ExecutionException>) e -> handlerCalled.set(true));
        cb.join(); // should not throw — Consumer consumed the exception
        assertTrue(handlerCalled.get(), "consumer handler should have been called");
        // After S3 fix: Consumer handler records a wrapper exception so getNow() throws
        // instead of returning null (which was indistinguishable from a successful null result).
        ExecutionException ex = assertThrows(ExecutionException.class, () -> cb.getNow(),
                "getNow should throw ExecutionException when Consumer handler suppressed the exception");
        assertTrue(ex.getMessage().contains("consumed by Consumer handler"),
                "exception message should indicate the exception was consumed by a Consumer handler");
        scheduler.shutdown();
    }

    @Test
    @DisplayName("submit(callable, Duration) completes after the specified delay")
    void submitWithDelay() throws Exception {
        FocessScheduler scheduler = new FocessScheduler("cb-delay");
        long start = System.nanoTime();
        Callback<String> cb = scheduler.submit(() -> "delayed", Duration.ofMillis(500));
        assertEquals("delayed", cb.get(5, TimeUnit.SECONDS));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 400, "should wait at least ~500ms, got " + elapsedMs + "ms");
        scheduler.shutdown();
    }
}
