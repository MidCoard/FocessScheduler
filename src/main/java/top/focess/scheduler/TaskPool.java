package top.focess.scheduler;

import com.google.common.collect.Sets;
import top.focess.scheduler.exceptions.PeriodTaskException;

import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Abstract base class for task pools that group tasks together.
 * <p>
 * Task pools are designed for one-off (non-period) tasks. When all tasks in the pool
 * have finished, an optional final callback ({@code runnable}) is executed.
 * <p>
 * <b>Contract:</b> Only non-period tasks can be added. Period tasks are rejected with
 * {@link PeriodTaskException}. This is by design because period tasks cycle indefinitely
 * and do not have a final "completion" event suitable for pool finalization.
 */
public abstract class TaskPool {

	protected final Scheduler scheduler;
	protected final Runnable runnable;

	protected final Set<Task> tasks = Sets.newHashSet();

	protected volatile boolean isFinished;

	public TaskPool(final Scheduler scheduler, final Runnable runnable) {
		this.scheduler = scheduler;
		this.runnable = runnable;
	}

	/**
	 * Add a non-period task to this pool.
	 *
	 * @param task the task to add (must be non-period)
	 * @throws PeriodTaskException if the task is a period task
	 */
	public synchronized void addTask(final Task task) {
		if (task.isPeriod()) {
			throw new PeriodTaskException(task);
		}
		final ITask iTask = (ITask) task;
		iTask.addTaskPool(this);
		this.tasks.add(task);
	}

	public synchronized void join() throws ExecutionException, InterruptedException {
		while (!this.isFinished)
			this.wait();
	}

	public synchronized void removeTask(final Task task) {
		final ITask iTask = (ITask) task;
		iTask.removeTaskPool(this);
		this.tasks.remove(task);
	}

	/**
	 * Mark this pool as finished and wake up any threads waiting in {@link #join()}.
	 * This is called when the pool's completion condition is met and the final callback (if any) has been scheduled.
	 */
	protected synchronized void markFinished() {
		this.isFinished = true;
		this.notifyAll();
	}

	/**
	 * Called when a task in this pool finishes. Subclasses implement pool-specific
	 * completion logic (e.g., "all tasks finished" vs "any task finished").
	 *
	 * @param task the task that has finished
	 */
	public abstract void finishTask(final Task task);
}
