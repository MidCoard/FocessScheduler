package top.focess.scheduler;

import com.google.common.collect.Sets;

import java.util.Set;
import java.util.concurrent.ExecutionException;

public abstract class TaskPool {

	protected final Scheduler scheduler;
	protected final Runnable runnable;

	protected final Set<Task> tasks = Sets.newHashSet();

	protected volatile boolean isFinished;

	public TaskPool(final Scheduler scheduler, final Runnable runnable) {
		this.scheduler = scheduler;
		this.runnable = runnable;
	}

	public synchronized void addTask(final Task task) {
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
	 */
	protected synchronized void markFinished() {
		this.isFinished = true;
		this.notifyAll();
	}

	public abstract void finishTask(final Task task);
}
