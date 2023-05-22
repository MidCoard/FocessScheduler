package top.focess.scheduler;

import com.google.common.collect.Sets;

import java.util.Set;

public abstract class TaskPool {

	protected final Runnable runnable;

	protected final Set<Task> tasks = Sets.newHashSet();

	protected boolean isFinished = false;

	public TaskPool(final Runnable runnable) {
		this.runnable = runnable;
	}

	public synchronized void addTask(final Task task) {
		final ITask iTask = (ITask) task;
		iTask.addTaskPool(this);
		this.tasks.add(task);
	}

	public synchronized void removeTask(final Task task) {
		final ITask iTask = (ITask) task;
		iTask.removeTaskPool(this);
		this.tasks.remove(task);
	}

	public abstract void finishTask(final Task task);
}
