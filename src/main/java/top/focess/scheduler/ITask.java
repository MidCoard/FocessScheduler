package top.focess.scheduler;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

interface ITask extends Task {

    void removeTaskPool(TaskPool taskPool);

    void run() throws ExecutionException;

    Duration getPeriod();

    void setNativeTask(ComparableTask task);

    default void cancel0() {
        ((AScheduler) this.getScheduler()).cancel(this);
        this.clear();
    }

    void clear();

    void startRun();

    void endRun();

    void setException(ExecutionException e);

    void addTaskPool(TaskPool taskPool);
}
