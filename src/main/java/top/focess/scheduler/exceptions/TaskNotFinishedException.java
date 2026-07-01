package top.focess.scheduler.exceptions;

import org.jspecify.annotations.NonNull;
import top.focess.scheduler.Callback;

/**
 * Thrown when {@link Callback#call()} is invoked on a task that has not yet finished.
 */
public class TaskNotFinishedException extends IllegalStateException {

    /**
     * Constructs a new {@code TaskNotFinishedException}.
     *
     * @param callback the callback that has not finished
     * @param <V>      the result type of the callback
     */
    public <V> TaskNotFinishedException(@NonNull final Callback<V> callback) {
        super("Task " + callback.getName() + " is not finished.");
    }
}
