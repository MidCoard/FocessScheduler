package top.focess.scheduler;

/**
 * A task pool that completes when ANY task has finished.
 * <p>
 * When the first task finishes, the optional callback is scheduled and this pool is marked
 * finished. Other tasks in the pool continue to run to completion.
 */
public class OrTaskPool extends TaskPool {

    public OrTaskPool(Scheduler scheduler, Runnable callback) {
        super(scheduler, ANY, callback);
    }
}
