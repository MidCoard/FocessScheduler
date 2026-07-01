package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.util.concurrent.ExecutionException;

/**
 * Runs tasks on the calling thread (which is the dispatcher's thread).
 * Used for single-thread schedulers where dispatching and execution
 * happen on the same thread.
 * <p>
 * When a task's scheduled time arrives, the dispatcher calls the scheduler's
 * {@link AbstractScheduler#onTaskReady(FocessTask)}, which delegates to this executor's
 * {@link #execute(FocessTask, Runnable)}. Since this executor runs inline, the task
 * executes on the dispatcher thread — no additional thread overhead.
 * <p>
 * Since {@link FocessTask#run()} catches all {@link Throwable} (including
 * {@link Error}) and wraps them in {@link ExecutionException}, no exception
 * can escape the execute method.
 */
public class InlineExecutor implements TaskExecutor {

    private Thread runningThread;
    private FocessTask currentTask;
    /** Whether the executor has been shut down. Guarded by {@code this}. */
    private boolean shutdown = false;

    @Override
    public void setScheduler(@NonNull AbstractScheduler scheduler) {
        // InlineExecutor runs on the dispatcher thread and doesn't need
        // a direct scheduler reference — the dispatcher mediates all callbacks.
    }

    @Override
    public void execute(@NonNull FocessTask task, @NonNull Runnable completionCallback) {
        synchronized (this) {
            runningThread = Thread.currentThread();
            currentTask = task;
        }
        try {
            task.run();
        } catch (ExecutionException e) {
            task.setException(e);
        }
        task.endRun();
        // Clear the interrupt flag and release the task assignment atomically.
        // This must be inside the lock so that interruptTask() cannot call
        // t.interrupt() between Thread.interrupted() and the field clear —
        // which would leak a spurious interrupt into the next task on this thread.
        synchronized (this) {
            Thread.interrupted();
            runningThread = null;
            currentTask = null;
        }
        completionCallback.run();
    }

    @Override
    public synchronized void interruptTask(@NonNull FocessTask task) {
        if (currentTask == task && runningThread != null) {
            runningThread.interrupt();
        }
    }

    @Override
    public synchronized void shutdown(boolean now) {
        shutdown = true;
        // InlineExecutor has no threads of its own to stop
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isIdle() {
        return currentTask == null;
    }
}
