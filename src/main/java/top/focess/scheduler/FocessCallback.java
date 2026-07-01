package top.focess.scheduler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Callback implementation that wraps a Callable and stores the result.
 * <p>
 * If an exception handler is configured via {@link #setExceptionHandler(Function)},
 * it can suppress the exception and produce a fallback value.
 * <p>
 * <b>Handler precedence:</b> If a {@link Function} handler is set, it takes priority
 * over a {@link Consumer} handler. Setting one type clears the other to avoid ambiguity.
 * <p>
 * <b>Consumer handler caveat:</b> When a {@link Consumer} handler suppresses an exception,
 * {@link #getNow()} throws {@link ExecutionException} rather than returning {@code null},
 * because returning {@code null} would be indistinguishable from a successful {@code null}
 * result. Callers that need a fallback value should use the {@link Function} handler
 * variant instead.
 *
 * @param <V> the result type
 */
public class FocessCallback<V> extends FocessTask implements Callback<V> {

    private final Callable<V> callback;
    private volatile V value;
    // Unified handler: stored as Function in the callback-specific field.
    // The Consumer variant bridges to this via the parent's handler field.
    private volatile Function<ExecutionException, V> functionHandler;
    /**
     * Set to the original exception when a Consumer handler consumed it.
     * This keeps join() happy (exception is null → no throw) while allowing
     * getNow() to detect the consumed-exception state and throw appropriately.
     */
    private volatile ExecutionException consumedException;

    FocessCallback(@NonNull Callable<V> callback, @NonNull Scheduler scheduler) {
        super(null, scheduler);
        this.callback = callback;
    }

    FocessCallback(@NonNull Callable<V> callback, @NonNull Scheduler scheduler, @NonNull String name) {
        super(null, scheduler, name);
        this.callback = callback;
    }

    FocessCallback(@NonNull Callable<V> callback, @NonNull Scheduler scheduler, @NonNull String name,
                   @Nullable Function<ExecutionException, V> handler) {
        super(null, scheduler, name, null); // no Consumer handler at parent level
        this.callback = callback;
        this.functionHandler = handler;
    }

    @Override
    public V getNow() throws ExecutionException, CancellationException, TaskNotFinishedException {
        if (getException() != null) throw getException();
        // If a Consumer handler consumed the exception, throw a wrapper so the caller
        // can distinguish this from a successful null result.
        ExecutionException consumed = consumedException;
        if (consumed != null) {
            throw new ExecutionException(
                    "Exception was consumed by Consumer handler; no fallback value available",
                    consumed.getCause());
        }
        if (isCancelled()) throw new CancellationException("Task is cancelled");
        if (!isDone()) throw new TaskNotFinishedException(this);
        return this.value;
    }

    @Override
    public void setException(@NonNull ExecutionException e) {
        Function<ExecutionException, V> h = this.functionHandler;
        if (h != null) {
            try {
                this.value = h.apply(e);
            } catch (Throwable ignored) {
                this.exception = e;
            }
        } else {
            // Check if a Consumer handler was set via setExceptionHandler(Consumer)
            Consumer<ExecutionException> ch = this.handler;
            if (ch != null) {
                try {
                    ch.accept(e);
                } catch (Throwable ignored) {
                    this.exception = e;
                    return;
                }
                // Consumer handled the exception; value stays null.
                // Record the original exception in consumedException so getNow()
                // can throw rather than returning null (which would be indistinguishable
                // from a successful null result). Do NOT set this.exception so that
                // join() does not throw (the exception was consumed/handled).
                this.consumedException = e;
            } else {
                this.exception = e;
            }
        }
    }

    @Override
    public void setExceptionHandler(@NonNull Function<ExecutionException, V> handler) {
        this.functionHandler = handler;
        // Clear any Consumer handler to avoid conflicts — Function takes priority
        this.handler = null;
    }

    @Override
    public void setExceptionHandler(@Nullable Consumer<ExecutionException> handler) {
        // Store as the parent's Consumer handler so setException() can find it.
        // Clear any Function handler to avoid conflicts.
        // Note: the two writes are volatile but not atomic together; a concurrent
        // setException() may see an inconsistent pair. This is acceptable because
        // setting handlers during task execution is inherently racy and the window
        // is negligible.
        this.handler = handler;
        this.functionHandler = null;
    }

    @Override
    Runnable asRunnable() {
        // Wrap the Callable as a Runnable so the caller of shutdownNow()
        // can re-execute it. The return value of the Callable is discarded,
        // matching the ExecutorService contract for List<Runnable>.
        return () -> {
            try {
                this.callback.call();
            } catch (Exception ignored) {
            }
        };
    }

    @Override
    public void run() throws ExecutionException {
        try {
            this.value = this.callback.call();
        } catch (Throwable e) {
            throw new ExecutionException(e);
        }
    }
}
