package top.focess.scheduler;

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
		for (final Task task1 : this.tasks)
			try {
				task1.cancel(true);
			} catch (final UnsupportedOperationException ignored) {}
		if (this.runnable != null)
			this.task = this.scheduler.run(this.runnable);
		this.isFinished = true;
	}

	@Override
	public void join() throws ExecutionException, InterruptedException {
		super.join();
		if (this.task != null)
			this.task.join();
	}
}
