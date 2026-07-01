package top.focess.scheduler;

/**
 * A task pool that completes when ALL tasks have finished.
 * <p>
 * When the last task finishes, the optional callback is scheduled and this pool is marked finished.
 * If a task finishes early, it is removed but the pool waits for others to complete.
 */
public class AndTaskPool extends TaskPool {

    public AndTaskPool(Scheduler scheduler, Runnable callback) {
        super(scheduler, ALL, callback);
    }
}
