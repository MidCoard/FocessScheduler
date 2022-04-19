package top.focess.scheduler;

import com.google.common.collect.Lists;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Collections;
import java.util.List;

public abstract class AScheduler implements Scheduler {

    private static final List<Scheduler> SCHEDULER_LIST = Lists.newArrayList();
    /**
     * The uncaught exception handler
     */
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    /**
     * The catch exception handler
     */
    private CatchExceptionHandler catchExceptionHandler;

    public AScheduler() {
        SCHEDULER_LIST.add(this);
    }

    @Override
    public void close() {
        SCHEDULER_LIST.remove(this);
    }

    /**
     * Get the schedulers as list
     * @return the schedulers as list
     */
    @Contract(pure = true)
    public static @NotNull @UnmodifiableView List<Scheduler> getSchedulers() {
        return Collections.unmodifiableList(SCHEDULER_LIST);
    }

    @Override
    public void setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler) {
        this.uncaughtExceptionHandler = handler;
    }

    @Override
    @Nullable
    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return this.uncaughtExceptionHandler;
    }


    @Override
    @Nullable
    public CatchExceptionHandler getCatchExceptionHandler() {
        return catchExceptionHandler;
    }

    @Override
    public void setCatchExceptionHandler(CatchExceptionHandler catchExceptionHandler) {
        this.catchExceptionHandler = catchExceptionHandler;
    }
}
