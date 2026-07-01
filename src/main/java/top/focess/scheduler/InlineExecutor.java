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

    private volatile Thread runningThread;
    private volatile FocessTask currentTask;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    @Override
    public void setScheduler(@NonNull AbstractScheduler scheduler) {
        // InlineExecutor runs on the dispatcher thread and doesn't need
        // a direct scheduler reference — the dispatcher mediates all callbacks.
    }

    @Override
    public void execute(@NonNull FocessTask task, @NonNull Runnable completionCallback) {
        runningThread = Thread.currentThread();
        currentTask = task;
        try {
            task.run();
        } catch (ExecutionException e) {
            // If the cause is an Error (e.g. InternalError, OutOfMemoryError),
            // re-throw it so the dispatcher thread's uncaught exception handler
            // can shut down the scheduler. Errors indicate an unrecoverable state.
            if (e.getCause() instanceof Error) {
                task.setException(e);
                task.endRun();
                runningThread = null;
                currentTask = null;
                throw (Error) e.getCause();
            }
            task.setException(e);
        } catch (Error e) {
            // Error thrown directly (not wrapped in ExecutionException) —
            // mark the task as failed and re-throw to trigger shutdown.
            task.setException(new ExecutionException(e));
            task.endRun();
            runningThread = null;
            currentTask = null;
            throw e;
        } finally {
            Thread.interrupted(); // clear leaked interrupt from cancel(true)
            // Only clear if we haven't already (in the Error paths above)
            if (currentTask != null) {
                runningThread = null;
                currentTask = null;
            }
        }
        task.endRun();
        if (!task.isCancelled()) {
            completionCallback.run();
        }
    }

    @Override
    public void interruptTask(@NonNull FocessTask task) {
        Thread t = runningThread;
        if (currentTask == task && t != null) {
            t.interrupt();
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
        return currentTask == null;
    }
}
