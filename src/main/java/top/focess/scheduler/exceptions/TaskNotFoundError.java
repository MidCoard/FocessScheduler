package top.focess.scheduler.exceptions;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.Task;

/**
 * Thrown to indicate that the task is not found
 */
public class TaskNotFoundError extends Error {

    /**
     * Constructs a TaskNotFoundException
     *
     * @param task the task
     */
    public TaskNotFoundError(@NotNull final Task task) {
        super("Task " + task.getName() + " is not found.");
    }
}
