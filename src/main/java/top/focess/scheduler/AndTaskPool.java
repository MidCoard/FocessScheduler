package top.focess.scheduler;

/**
 * A task pool that completes when ALL tasks have finished.
 * <p>
 * When the last task finishes, the optional callback is scheduled and this pool is marked finished.
 * If a task finishes early, it is removed but the pool waits for others to complete.
 */
public class AndTaskPool extends TaskPool {

	public AndTaskPool(final Scheduler scheduler, final Runnable runnable) {
		super(scheduler, runnable);
	}

	/**
	 * Called when a task in this pool finishes.
	 * If all tasks are done, schedules the callback and marks the pool finished.
	 */
	@Override
	public synchronized void finishTask(final Task task) {
		// If already finished, or there are still other tasks pending, do nothing
		if (this.isFinished || !this.tasks.isEmpty())
			return;
		// All tasks are done; schedule callback (if any)
		if (this.runnable != null)
			this.task = this.scheduler.run(this.runnable);
		this.markFinished();
	}

}
