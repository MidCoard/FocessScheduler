package top.focess.scheduler.exceptions;

import org.jetbrains.annotations.NotNull;
import top.focess.scheduler.Task;

/**
 * Thrown to indicate that a period task cannot be added to a task pool.
 * Task pools are designed for one-off tasks that complete and trigger a final callback.
 * Period tasks cycle indefinitely and do not have a final "completion" event.
 */
public class PeriodTaskException extends IllegalArgumentException {

    /**
     * Constructs a PeriodTaskException
     *
     * @param task the period task that cannot be added to a pool
     */
    public PeriodTaskException(@NotNull final Task task) {
        super("Period task " + task.getName() + " cannot be added to a task pool. Task pools are for non-period tasks only.");
    }
}

