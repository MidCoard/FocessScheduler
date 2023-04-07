package top.focess.scheduler;

import top.focess.scheduler.exceptions.TaskNotFinishedException;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

public class FocessCallback<V> extends FocessTask implements Callback<V> {

    private final Callable<V> callback;
    private V value;
    private Function<ExecutionException, V> handler;

    FocessCallback(final Callable<V> callback, final Scheduler scheduler, final String name) {
        super(null, scheduler, name);
        this.callback = callback;
    }

    FocessCallback(final Callable<V> callback, final Scheduler scheduler) {
        super(null, scheduler);
        this.callback = callback;
    }

    FocessCallback(final Callable<V> callback, final Scheduler scheduler, final String name, final Function<ExecutionException, V> handler) {
        this(callback, scheduler, name);
        this.handler = handler;
    }

    @Override
    public synchronized V call() throws TaskNotFinishedException, CancellationException, ExecutionException {
        if (this.exception != null)
            throw this.exception;
        if (this.isCancelled())
            throw new CancellationException("Task is cancelled");
        // if the task is cancelled, the task is not finished. So first check if the task is cancelled.
        if (!this.isFinished)
            throw new TaskNotFinishedException(this);
        return this.value;
    }

    @Override
    public synchronized void setException(final ExecutionException e) {
        if (this.handler != null)
            this.value = this.handler.apply(e);
        else this.exception = e;
    }

    @Override
    public synchronized void setExceptionHandler(Function<ExecutionException, V> handler) {
        this.handler = handler;
    }

    @Override
    public void run() throws ExecutionException {
        try {
            this.value = this.callback.call();
        } catch (final Exception e) {
            throw new ExecutionException(e);
        }
    }


    @Override
    public synchronized void setExceptionHandler(Consumer<ExecutionException> handler) {
        this.handler = e -> {
            handler.accept(e);
            return null;
        };
    }
}
