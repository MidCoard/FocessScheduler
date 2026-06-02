package top.focess.scheduler;

import com.google.common.collect.Lists;

import java.util.concurrent.ExecutionException;

public class OrTaskPool extends TaskPool {

	private Task task;

	public OrTaskPool(final Scheduler scheduler, final Runnable runnable) {
		super(scheduler, runnable);
	}

	@Override
	public synchronized void finishTask(final Task task) {
		if (this.isFinished)
			return;
		// iterate over a snapshot: cancelling a task may mutate this.tasks via removeTask
		for (final Task task1 : Lists.newArrayList(this.tasks))
			try {
				task1.cancel(true);
			} catch (final UnsupportedOperationException ignored) {}
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
