package top.focess.scheduler.exceptions;

import org.jspecify.annotations.NonNull;
import top.focess.scheduler.Scheduler;

/**
 * Thrown when an operation is attempted on a scheduler that has been shut down.
 */
public class SchedulerClosedException extends IllegalStateException {

    /**
     * Constructs a new {@code SchedulerClosedException}.
     *
     * @param scheduler the scheduler that has been shut down
     */
    public SchedulerClosedException(@NonNull final Scheduler scheduler) {
        super("Scheduler " + scheduler.getName() + " is closed.");
    }
}
