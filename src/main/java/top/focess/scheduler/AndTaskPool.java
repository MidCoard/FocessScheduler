package top.focess.scheduler;

public class AndTaskPool extends TaskPool {

	public AndTaskPool(final Scheduler scheduler, final Runnable runnable) {
		super(scheduler, runnable);
	}

	@Override
	public synchronized void finishTask(final Task task) {
		if (this.tasks.size() != 0 || this.isFinished)
			return;
		if (this.runnable != null)
			this.scheduler.run(this.runnable);
		this.isFinished = true;
	}

}
