package top.focess.scheduler.exceptions;

import org.jspecify.annotations.NonNull;
import top.focess.scheduler.Task;

/**
 * Thrown when a period task is added to a task pool, which only accepts one-off tasks.
 * <p>
 * Task pools depend on a terminal completion event to finalize. Period tasks cycle
 * indefinitely and never produce such an event, so they cannot be pooled.
 */
public class PeriodTaskException extends IllegalStateException {

    /**
     * Constructs a new {@code PeriodTaskException}.
     *
     * @param task the period task that was rejected
     */
    public PeriodTaskException(@NonNull final Task task) {
        super("Period task " + task.getName() + " cannot be added to a task pool. Task pools are for non-period tasks only.");
    }
}

