package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-threaded executor with a shared work queue.
 * <p>
 * Workers compete for tasks from a {@link LinkedBlockingQueue}.
 * Supports dynamic expansion ({@code immediate} mode) when all workers are busy,
 * up to an optional {@code maxPoolSize} cap.
 * <p>
 * Each worker is a daemon thread. When a task throws an uncaught {@link Error},
 * the worker's uncaught exception handler shuts down the entire scheduler
 * (by design — an uncaught error typically indicates an unrecoverable state).
 */
public class PoolTaskExecutor implements TaskExecutor {

    private final LinkedBlockingQueue<WorkItem> workQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<FocessTask, Worker> taskWorkerMap = new ConcurrentHashMap<>();
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private final int corePoolSize;
    private final int maxPoolSize;
    private final boolean immediate;
    private final String name;
    private volatile AbstractScheduler scheduler;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger runningCount = new AtomicInteger(0);

    static class WorkItem {
        final FocessTask task;
        final Runnable completionCallback;

        WorkItem(FocessTask task, Runnable completionCallback) {
            this.task = task;
            this.completionCallback = completionCallback;
        }
    }

    /**
     * Creates a new PoolTaskExecutor with no upper bound on pool size.
     *
     * @param corePoolSize the number of core (permanent) worker threads
     * @param immediate    if {@code true}, the pool expands dynamically when all workers are busy
     * @param name         the name prefix for worker threads
     */
    public PoolTaskExecutor(int corePoolSize, boolean immediate, String name) {
        this(corePoolSize, immediate, name, Integer.MAX_VALUE);
    }

    /**
     * Creates a new PoolTaskExecutor with an upper bound on pool size.
     *
     * @param corePoolSize the number of core (permanent) worker threads
     * @param immediate    if {@code true}, the pool expands dynamically when all workers are busy
     * @param name         the name prefix for worker threads
     * @param maxPoolSize  the maximum number of worker threads (caps unbounded spawning in immediate mode)
     */
    public PoolTaskExecutor(int corePoolSize, boolean immediate, String name, int maxPoolSize) {
        this.corePoolSize = corePoolSize;
        this.immediate = immediate;
        this.name = name;
        this.maxPoolSize = maxPoolSize;
        // Pre-start core workers
        for (int i = 0; i < corePoolSize; i++) {
            startWorker();
        }
    }

    /**
     * Set the scheduler reference. Called by AbstractScheduler after construction.
     *
     * @param scheduler the scheduler that owns this executor
     */
    @Override
    public void setScheduler(@NonNull AbstractScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void execute(@NonNull FocessTask task, @NonNull Runnable completionCallback) {
        workQueue.add(new WorkItem(task, completionCallback));
        ensureWorkers();
    }

    @Override
    public void interruptTask(@NonNull FocessTask task) {
        Worker worker = taskWorkerMap.get(task);
        if (worker != null) {
            synchronized (worker.taskLock) {
                // Re-verify under lock: the worker still owns this task
                if (worker.currentTaskField == task) {
                    worker.interrupt();
                }
            }
        }
    }

    @Override
    public void shutdown(boolean now) {
        if (shutdown.getAndSet(true))
            return;
        if (now) {
            // Interrupt all workers
            for (Worker w : taskWorkerMap.values()) {
                w.interrupt();
            }
        }
        // Non-now: workers will drain naturally and exit
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isIdle() {
        return runningCount.get() == 0 && workQueue.isEmpty();
    }

    private void ensureWorkers() {
        // If all core workers are busy and immediate mode, add a worker (up to maxPoolSize)
        if (immediate && runningCount.get() >= workerCount.get() && !shutdown.get()) {
            while (true) {
                int current = workerCount.get();
                if (current >= maxPoolSize) break;
                if (workerCount.compareAndSet(current, current + 1)) {
                    Worker worker = new Worker(name + "-worker-" + current);
                    worker.setDaemon(true);
                    worker.start();
                    break;
                }
                // CAS failed — another thread modified workerCount; re-check
            }
        }
    }

    private void startWorker() {
        // Used only during construction for core workers — no CAS needed
        int idx = workerCount.getAndIncrement();
        Worker worker = new Worker(name + "-worker-" + idx);
        worker.setDaemon(true);
        worker.start();
    }

    class Worker extends Thread {

        /** The task currently being executed by this worker, or null. */
        private FocessTask currentTaskField;
        /** Lock object for atomic check-and-interrupt in {@link PoolTaskExecutor#interruptTask}. */
        final Object taskLock = new Object();

        Worker(String name) {
            super(name);
            setUncaughtExceptionHandler((t, e) -> {
                try {
                    // Use the volatile field for O(1) lookup instead of scanning taskWorkerMap.
                    // If the Error was caught by the Worker's catch(ExecutionException) block,
                    // currentTaskField will already be null (the inline Error path cleaned up
                    // the task, taskWorkerMap, and runningCount before re-throwing).
                    // Only clean up here if the Error bypassed that path (e.g. raw Error).
                    FocessTask current;
                    synchronized (taskLock) {
                        current = currentTaskField;
                        if (current != null) {
                            taskWorkerMap.remove(current);
                            currentTaskField = null;
                        }
                    }
                    if (current != null) {
                        if (!current.isDone()) {
                            current.setException(new ExecutionException(e));
                            current.endRun();
                        }
                        runningCount.decrementAndGet();
                    }
                    // BY DESIGN: a single failing task shuts down the entire scheduler
                    AbstractScheduler s = scheduler;
                    if (s != null) {
                        s.shutdown();
                        if (s.getUncaughtExceptionHandler() != null)
                            s.getUncaughtExceptionHandler().uncaughtException(t, e);
                    }
                } catch (Throwable ex) {
                    ex.printStackTrace(System.err);
                }
            });
        }

        @Override
        public void run() {
            while (!shutdown.get()) {
                try {
                    Thread.interrupted(); // clear any leftover interrupt
                    WorkItem item = workQueue.poll();
                    if (item == null) {
                        // Wait briefly for work
                        item = workQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (item == null) {
                            // If we're an extra worker (beyond core), consider terminating.
                            // Use CAS loop to prevent multiple workers from decrementing
                            // workerCount below corePoolSize simultaneously.
                            while (true) {
                                int current = workerCount.get();
                                if (current > corePoolSize) {
                                    if (workerCount.compareAndSet(current, current - 1)) {
                                        return; // exit this extra worker
                                    }
                                    // CAS failed — re-check
                                } else {
                                    break; // core worker — don't exit
                                }
                            }
                            continue;
                        }
                    }

                    FocessTask task = item.task;
                    if (task.isCancelled()) continue;

                    synchronized (taskLock) {
                        taskWorkerMap.put(task, this);
                        currentTaskField = task;
                    }
                    runningCount.incrementAndGet();
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
                        // worker.interrupt() between endRun() and the field clear — which would
                        // leak a spurious interrupt into the next task on this worker.
                        synchronized (taskLock) {
                            Thread.interrupted();
                            taskWorkerMap.remove(task);
                            currentTaskField = null;
                        }
                        runningCount.decrementAndGet();
                    }
                    // Skip completion callback for cancelled tasks — the scheduler
                    // should not re-dispatch or report completion for a cancelled task.
                    if (!task.isCancelled()) {
                        item.completionCallback.run();
                    }
                } catch (InterruptedException e) {
                    // shutdown or cancel — check flag
                }
            }
        }
    }
}
