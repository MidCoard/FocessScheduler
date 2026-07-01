package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs tasks on the calling thread (which is the dispatcher's thread).
 * Used for single-thread schedulers where dispatching and execution
 * happen on the same thread.
 * <p>
 * When a task's scheduled time arrives, the dispatcher calls the scheduler's
 * {@link AbstractScheduler#onTaskReady(FocessTask)}, which delegates to this executor's
 * {@link #execute(FocessTask, Runnable)}. Since this executor runs inline, the task
 * executes on the dispatcher thread — no additional thread overhead.
 */
public class InlineExecutor implements TaskExecutor {

    private Thread runningThread;
    private FocessTask currentTask;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    /** Lock object for atomic check-and-interrupt in {@link #interruptTask}. */
    private final Object taskLock = new Object();

    @Override
    public void setScheduler(@NonNull AbstractScheduler scheduler) {
        // InlineExecutor runs on the dispatcher thread and doesn't need
        // a direct scheduler reference — the dispatcher mediates all callbacks.
    }

    @Override
    public void execute(@NonNull FocessTask task, @NonNull Runnable completionCallback) {
        synchronized (taskLock) {
            runningThread = Thread.currentThread();
            currentTask = task;
        }
        try {
            try {
                task.run();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Error) {
                    task.setException(e);
                    task.endRun();
                    throw (Error) e.getCause();
                }
                task.setException(e);
            }
            task.endRun();
        } finally {
            // Clear the interrupt flag and release the task assignment atomically.
            // This must be inside the lock so that interruptTask() cannot call
            // t.interrupt() between endRun() and the field clear — which would
            // leak a spurious interrupt into the next task on this thread.
            synchronized (taskLock) {
                Thread.interrupted();
                runningThread = null;
                currentTask = null;
            }
        }
        completionCallback.run();
    }

    @Override
    public void interruptTask(@NonNull FocessTask task) {
        synchronized (taskLock) {
            if (currentTask == task && runningThread != null) {
                runningThread.interrupt();
            }
        }
    }

    @Override
    public void shutdown(boolean now) {
        shutdown.set(true);
        // InlineExecutor has no threads of its own to stop
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isIdle() {
        synchronized (taskLock) {
            return currentTask == null;
        }
    }
}
