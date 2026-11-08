package com.finapp.app.lifecycle;

import java.time.Duration;
import org.springframework.context.SmartLifecycle;

/**
 * A schedule's {@code SmartLifecycle}, stopped through its {@link ScheduleDrain} (`P15-TSK-008`). A schedule implements
 * {@code start()} - its executor and its ticks, handed to {@link #drain()} - and nothing else of the lifecycle: stopping,
 * running and the phase are the drain's, the same for every schedule, in {@link ShutdownOrder#SCHEDULES}.
 */
public interface DrainedSchedule extends SmartLifecycle, BoundedStop {

    /** @return the schedule's drain, which holds its executor */
    ScheduleDrain drain();

    @Override
    default void stop() {
        drain().stop();
    }

    @Override
    default void stop(Runnable callback) {
        drain().stop(callback);
    }

    @Override
    default boolean isRunning() {
        return drain().isRunning();
    }

    @Override
    default int getPhase() {
        return ShutdownOrder.SCHEDULES;
    }

    @Override
    default Duration stopBound() {
        return drain().stopBound();
    }
}
