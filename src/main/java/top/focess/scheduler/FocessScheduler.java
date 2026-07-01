package top.focess.scheduler;

import java.util.UUID;

/**
 * Single-threaded scheduler: dispatching and execution happen on the same thread.
 * <p>
 * Uses {@link TimeDispatcher} + {@link InlineExecutor} internally.
 * The dispatcher thread blocks on the {@link java.util.concurrent.DelayQueue}
 * until a task's scheduled time arrives, then runs the task inline on that same thread.
 * <p>
 * If a task runs longer than expected, subsequent tasks are delayed accordingly.
 * A running task can be cooperatively cancelled via {@link Task#cancel(boolean) cancel(true)},
 * which interrupts the dispatcher thread.
 */
public class FocessScheduler extends AbstractScheduler {

    /**
     * Composable constructor — any Dispatcher with any TaskExecutor.
     *
     * @param dispatcher the dispatcher to use
     * @param executor   the executor to use
     */
    public FocessScheduler(Dispatcher dispatcher, TaskExecutor executor) {
        super(dispatcher, executor, deriveName(dispatcher));
    }

    /**
     * Creates a new {@code FocessScheduler} with a non-daemon scheduler thread.
     *
     * @param name the scheduler name
     */
    public FocessScheduler(String name) {
        this(name, false);
    }

    /**
     * Creates a new {@code FocessScheduler}.
     *
     * @param name     the scheduler name
     * @param isDaemon {@code true} to create a daemon scheduler thread
     */
    public FocessScheduler(String name, boolean isDaemon) {
        super(new TimeDispatcher(name, isDaemon), new InlineExecutor(), name);
    }

    /**
     * Creates a new {@code FocessScheduler} whose name is auto-generated from the given prefix.
     *
     * @param prefix the prefix for the generated scheduler name
     * @return a new non-daemon {@code FocessScheduler}
     */
    public static FocessScheduler newPrefixScheduler(String prefix) {
        return new FocessScheduler(prefix + "-FocessScheduler-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static String deriveName(Dispatcher dispatcher) {
        return dispatcher.toString();
    }
}
