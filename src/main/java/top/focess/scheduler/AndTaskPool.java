package top.focess.scheduler;

import java.util.concurrent.ExecutionException;

public class AndTaskPool extends TaskPool {

	private Task task;

	public AndTaskPool(final Scheduler scheduler, final Runnable runnable) {
		super(scheduler, runnable);
	}

	@Override
	public synchronized void finishTask(final Task task) {
		if (this.tasks.size() != 0 || this.isFinished)
			return;
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
