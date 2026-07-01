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
 * the worker dies but the pool relaunches a replacement worker so the pool
 * maintains its core size. The error is reported to the scheduler's
 * {@link Thread.UncaughtExceptionHandler} for logging/monitoring.
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
            // Interrupt all workers so they break out of poll immediately
            for (Worker w : taskWorkerMap.values()) {
                w.interrupt();
            }
        }
        // For graceful shutdown (now=false): workers drain the workQueue
        // and exit when they see shutdown=true + workQueue.isEmpty().
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

    /**
     * Relaunch a replacement worker after one dies (e.g. from an uncaught Error).
     * The dead worker already decremented workerCount via its exit path, so
     * we just need to start a new one.
     */
    private void relaunchWorker() {
        if (shutdown.get()) return;
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
                    // Clean up the task that was running when the Error occurred.
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
                    // This worker thread is dead — decrement workerCount so
                    // relaunchWorker() can increment it back for the replacement.
                    workerCount.decrementAndGet();
                    // Report the error to the scheduler's handler for logging/monitoring
                    AbstractScheduler s = scheduler;
                    if (s != null && s.getUncaughtExceptionHandler() != null) {
                        s.getUncaughtExceptionHandler().uncaughtException(t, e);
                    }
                    // Relaunch a replacement worker to maintain pool size
                    relaunchWorker();
                } catch (Throwable ex) {
                    ex.printStackTrace(System.err);
                }
            });
        }

        @Override
        public void run() {
            while (true) {
                try {
                    WorkItem item;
                    if (workerCount.get() > corePoolSize) {
                        // Extra worker: use timed poll so we can exit if idle
                        item = workQueue.poll(5, TimeUnit.SECONDS);
                        if (item == null) {
                            // Idle timeout — try to exit this extra worker
                            while (true) {
                                int current = workerCount.get();
                                if (current > corePoolSize) {
                                    if (workerCount.compareAndSet(current, current - 1)) {
                                        return; // exit
                                    }
                                    // CAS failed — re-check
                                } else {
                                    break; // no longer extra — stay as core
                                }
                            }
                            continue;
                        }
                    } else {
                        // Core worker: use timed poll (not take) so we periodically
                        // check the shutdown flag. take() would block forever if
                        // shutdown(false) is called with an empty queue, leaking
                        // the thread.
                        item = workQueue.poll(1, TimeUnit.SECONDS);
                        if (item == null) {
                            // After shutdown, core workers exit only after the dispatcher
                            // is truly dead — it may still be mid-dispatch.
                            if (shutdown.get() && workQueue.isEmpty()) {
                                AbstractScheduler s = scheduler;
                                if (s != null && !s.getDispatcher().isTerminated()) continue;
                                return;
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
                    item.completionCallback.run();
                    // After processing a task, check if we should exit:
                    // shutdown was called and the queue is empty.
                    if (shutdown.get() && workQueue.isEmpty()) {
                        AbstractScheduler s = scheduler;
                        if (s != null && !s.getDispatcher().isTerminated()) continue;
                        return;
                    }
                } catch (InterruptedException e) {
                    // shutdownNow() — if shutdown is set, queue is empty, and
                    // dispatcher is dead, exit
                    if (shutdown.get() && workQueue.isEmpty()) {
                        AbstractScheduler s = scheduler;
                        if (s != null && !s.getDispatcher().isTerminated()) continue;
                        return;
                    }
                }
            }
        }
    }
}
