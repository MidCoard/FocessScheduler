package top.focess.scheduler;

import com.google.common.collect.Lists;

import java.util.concurrent.ExecutionException;

/**
 * A task pool that completes when ANY task has finished.
 * <p>
 * When the first task finishes, all other pending tasks are cancelled, the optional callback
 * is scheduled, and this pool is marked finished.
 */
public class OrTaskPool extends TaskPool {

	private Task task;

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

	@Override
	public void join() throws ExecutionException, InterruptedException {
		super.join();
		if (this.task != null)
			this.task.join();
	}
}
