package top.focess.scheduler;

/**
 * A task pool that completes when ANY task has finished.
 * <p>
 * When the first task finishes, the optional callback is scheduled and this pool is marked
 * finished. Other tasks in the pool continue to run to completion.
 */
public class OrTaskPool extends TaskPool {

	public OrTaskPool(final Scheduler scheduler, final Runnable runnable) {
		super(scheduler, runnable);
	}

	/**
	 * Called when a task in this pool finishes.
     * Schedules the callback and marks the pool finished.
	 */
	@Override
	public synchronized void finishTask(final Task task) {
		if (this.isFinished)
			return;
		if (this.runnable != null)
			this.task = this.scheduler.run(this.runnable);
		this.markFinished();
	}

}
