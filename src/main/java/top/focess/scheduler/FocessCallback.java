package top.focess.scheduler;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FocessCallback<V> extends FocessTask implements Callback<V> {

    private static final Scheduler DEFAULT_SCHEDULER = new ThreadPoolScheduler(7, false, "FocessCallback");
    private final Callable<V> callback;
    private V value;

    FocessCallback(final Callable<V> callback, final Scheduler scheduler) {
        super(null, scheduler);
        this.callback = callback;
    }

    @Override
    public V call() throws TaskNotFinishedException, CancellationException, ExecutionException {
        if (this.isCancelled())
            throw new CancellationException("Task is cancelled");
        if (!this.isFinished)
            throw new TaskNotFinishedException(this);
        if (this.exception != null)
            throw this.exception;
        return this.value;
    }

    @Override
    public V waitCall() throws InterruptedException, ExecutionException {
        join();
        return this.call();
    }

    @Override
    public synchronized V get(final long timeout, @NotNull final TimeUnit unit) throws InterruptedException, TimeoutException, ExecutionException {
        if (this.isFinished())
            return this.value;
        if (this.isCancelled())
            throw new CancellationException("Task is cancelled");
        final AtomicBoolean out = new AtomicBoolean(false);
        final Task task = DEFAULT_SCHEDULER.run(() -> {
            out.set(true);
            synchronized (FocessCallback.this) {
                FocessCallback.this.notifyAll();
            }
        }, Duration.ofMillis(unit.toMillis(timeout)));
        while (true) {
            this.wait();
            if (this.isCancelled() || this.isFinished())
                break;
            if (out.get())
                throw new TimeoutException();
        }
        task.cancel();
        return this.call();
    }

    @Override
    public void run() throws ExecutionException {
        try {
            this.value = this.callback.call();
        } catch (final Exception e) {
            throw new ExecutionException(e);
        }
    }

}
