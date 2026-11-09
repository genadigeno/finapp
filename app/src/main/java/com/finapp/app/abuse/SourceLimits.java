package com.finapp.app.abuse;

import com.finapp.platform.abuse.WindowLimit;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The limit of each {@link Door}, read from configuration at start (`P15-TSK-022`).
 *
 * <p>Every door must be present, and an absent or invalid one stops the instance: a door with no limit would be a door with no
 * bound. The windows have a floor because the table's size and the sweep's capacity are sized on them ({@code SourceLimitBudgetTest}).
 */
public final class SourceLimits {

    /** The shortest window a door may have: shorter ones create rows faster than the sweep's budget is sized for. */
    static final Duration MINIMUM_WINDOW = Duration.ofMinutes(1);

    private final Map<Door, WindowLimit> limits;

    SourceLimits(Map<Door, WindowLimit> limits) {
        Objects.requireNonNull(limits, "limits must not be null");
        Map<Door, WindowLimit> copy = new EnumMap<>(Door.class);
        for (Door door : Door.values()) {
            WindowLimit limit = Objects.requireNonNull(limits.get(door), () -> "no limit is configured for door " + door.label());
            if (limit.window().compareTo(MINIMUM_WINDOW) < 0) {
                throw new IllegalArgumentException("door " + door.label() + "'s window is below " + MINIMUM_WINDOW);
            }
            copy.put(door, limit);
        }
        this.limits = copy;
    }

    WindowLimit of(Door door) {
        return limits.get(door);
    }
}
