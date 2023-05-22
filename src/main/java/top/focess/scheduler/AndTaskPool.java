package top.focess.scheduler;

public class AndTaskPool extends TaskPool {

	public AndTaskPool(final Runnable runnable) {
		super(runnable);
	}

	@Override
	public synchronized void finishTask(final Task task) {
		if (this.tasks.size() != 0 || this.isFinished)
			return;
		if (this.runnable != null)
			this.runnable.run();
		this.isFinished = true;
	}

}
