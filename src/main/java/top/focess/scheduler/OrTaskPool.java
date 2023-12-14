package top.focess.scheduler;

public class OrTaskPool extends TaskPool {
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
			this.scheduler.run(this.runnable);
		this.isFinished = true;
	}
}
