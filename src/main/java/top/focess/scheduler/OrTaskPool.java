package top.focess.scheduler;

public class OrTaskPool extends TaskPool {
	public OrTaskPool(final Runnable runnable) {
		super(runnable);
	}

	@Override
	public synchronized void finishTask(final Task task) {
		if (this.isFinished)
			return;
		if (this.runnable != null)
			this.runnable.run();
		this.isFinished = true;
	}
}
