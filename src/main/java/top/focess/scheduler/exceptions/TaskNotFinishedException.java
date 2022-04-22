package top.focess.scheduler.exceptions;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.Callback;

/**
 * Thrown to indicate the task is not finished
 */
public class TaskNotFinishedException extends IllegalStateException {

    /**
     * Constructs a TaskNotFinishedException
     *
     * @param callback the task
     * @param <V>      the task return type
     */
    public <V> TaskNotFinishedException(@NotNull final Callback<V> callback) {
        super("Task " + callback.getName() + " is not finished.");
    }
}
