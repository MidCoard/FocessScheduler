package top.focess.scheduler;

import org.jspecify.annotations.NonNull;

import java.util.Set;
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
 * Each worker is a daemon thread. Since {@link FocessTask#run()} catches all
 * {@link Throwable} (including {@link Error}) and wraps them in
 * {@link ExecutionException}, no exception can escape to the worker loop.
 * Workers never die from task failures — they simply continue to the next task.
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
    /** Whether the now-shutdown (drain + interrupt) has been executed. */
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final AtomicInteger runningCount = new AtomicInteger(0);
    /** All live worker threads, for interrupting on {@code shutdownNow}. */
    private final Set<Worker> allWorkers = ConcurrentHashMap.newKeySet();

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
        if (corePoolSize < 0)
            throw new IllegalArgumentException("corePoolSize must be >= 0");
        if (maxPoolSize < corePoolSize)
            throw new IllegalArgumentException("maxPoolSize must be >= corePoolSize");
        if (corePoolSize == 0 && !immediate)
            throw new IllegalArgumentException(
                    "corePoolSize=0 with immediate=false will never execute any task");
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
            synchronized (worker) {
                // Re-verify under lock: the worker still owns this task
                if (worker.currentTaskField == task) {
                    worker.interrupt();
                }
            }
        }
    }

    @Override
    public void shutdown(boolean now) {
        shutdown.set(true);
        if (now && halted.compareAndSet(false, true)) {
            // Halt waiting tasks: cancel everything already dispatched but not yet
            // running, so workers do not pick them up after shutdownNow().
            WorkItem item;
            while ((item = workQueue.poll()) != null) {
                item.task.cancel(true);
            }
            // Interrupt all workers (running and idle) so running tasks get a
            // best-effort stop and idle workers break out of poll() immediately.
            for (Worker w : allWorkers) {
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
    public boolean isTerminated() {
        return shutdown.get() && allWorkers.isEmpty();
    }

    private void ensureWorkers() {
        // If all core workers are busy and immediate mode, add a worker (up to maxPoolSize)
        if (immediate && runningCount.get() >= workerCount.get()) {
            while (true) {
                int current = workerCount.get();
                if (current >= maxPoolSize) break;
                if (workerCount.compareAndSet(current, current + 1)) {
                    Worker worker = new Worker(name + "-worker-" + current);
                    worker.setDaemon(true);
                    allWorkers.add(worker);
                    try {
                        worker.start();
                    } catch (Throwable t) {
                        // start() failed — release the reservation.
                        allWorkers.remove(worker);
                        workerCount.decrementAndGet();
                        throw t;
                    }
                    break;
                }
                // CAS failed — another thread modified workerCount; re-check
            }
        }
    }

    /**
     * Start a new worker thread, reserving a slot in {@code workerCount} and
     * registering it in {@code allWorkers}.
     */
    private void startWorker() {
        int idx = workerCount.getAndIncrement();
        Worker worker = new Worker(name + "-worker-" + idx);
        worker.setDaemon(true);
        allWorkers.add(worker);
        try {
            worker.start();
        } catch (Throwable t) {
            // start() failed (e.g. OOM spawning the thread) — release the slot
            // and registration so workerCount/allWorkers stay consistent.
            allWorkers.remove(worker);
            workerCount.decrementAndGet();
            throw t;
        }
    }

    class Worker extends Thread {

        /** The task currently being executed by this worker, or null. */
        private FocessTask currentTaskField;

        Worker(String name) {
            super(name);
        }

        @Override
        public void run() {
            try {
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

                        synchronized (this) {
                            taskWorkerMap.put(task, this);
                            currentTaskField = task;
                        }
                        runningCount.incrementAndGet();
                        try {
                            task.run();
                        } catch (ExecutionException e) {
                            task.setException(e);
                        }
                        task.endRun();
                        runningCount.decrementAndGet();
                        // Clear the interrupt flag and release the task assignment atomically.
                        // This must be inside the lock so that interruptTask() cannot call
                        // worker.interrupt() between Thread.interrupted() and the field
                        // clear — which would leak a spurious interrupt into the next
                        // task on this worker.
                        synchronized (this) {
                            Thread.interrupted();
                            taskWorkerMap.remove(task);
                            currentTaskField = null;
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
            } finally {
                allWorkers.remove(this);
                // Last worker out: if we're shut down, drain any remaining
                // tasks that no worker will ever pick up.
                if (shutdown.get() && allWorkers.isEmpty()) {
                    WorkItem item;
                    while ((item = workQueue.poll()) != null) {
                        item.task.cancel(true);
                    }
                }
            }
        }
    }
}
