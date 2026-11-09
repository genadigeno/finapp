package com.finapp.app.abuse;

import com.finapp.platform.abuse.SourceBucket;
import com.finapp.platform.abuse.WindowLimit;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The limits of each {@link Door}, read from configuration at start (`P15-TSK-022`).
 *
 * <p>Every door must be present, and an absent or invalid one stops the instance: a door with no limit would be a door with no
 * bound. A door may also have a limit per IPv6 /48 (a <em>site</em>) in the same window; a door without one is counted per source
 * alone.
 *
 * <p><strong>The table's size is held here, for whatever is configured.</strong> One count creates at most {@code BUCKETS + 1} rows
 * per window, so the windows set how fast the doors can create rows. A configuration whose worst rate is above one instance's sweep
 * capacity stops the instance at start: the table would otherwise grow without limit for as long as a flood lasted.
 */
public final class SourceLimits {

    /** The shortest window a door may have. The sweep's capacity is a separate check, below. */
    static final Duration MINIMUM_WINDOW = Duration.ofMinutes(1);

    private static final long SECONDS_PER_HOUR = 3_600;

    private final Map<Door, WindowLimit> limits;
    private final Map<Door, WindowLimit> siteLimits;

    /**
     * @param limits each door's limit per source
     * @param siteLimits the doors counted per /48 as well, each its limit in the door's window, at least the door's own
     * @param sweptRowsPerHour how many rows one instance's sweep can delete in an hour
     */
    SourceLimits(Map<Door, WindowLimit> limits, Map<Door, Integer> siteLimits, long sweptRowsPerHour) {
        Objects.requireNonNull(limits, "limits must not be null");
        Objects.requireNonNull(siteLimits, "siteLimits must not be null");
        Map<Door, WindowLimit> perSource = new EnumMap<>(Door.class);
        Map<Door, WindowLimit> perSite = new EnumMap<>(Door.class);
        long worstRowsPerHour = 0;
        for (Door door : Door.values()) {
            WindowLimit limit = Objects.requireNonNull(limits.get(door), () -> "no limit is configured for door " + door.label());
            if (limit.window().compareTo(MINIMUM_WINDOW) < 0) {
                throw new IllegalArgumentException("door " + door.label() + "'s window is below " + MINIMUM_WINDOW);
            }
            perSource.put(door, limit);
            long counts = 1;
            Integer site = siteLimits.get(door);
            if (site != null) {
                if (site < limit.limit()) {
                    throw new IllegalArgumentException("door " + door.label() + "'s limit per site is below its limit per source");
                }
                perSite.put(door, new WindowLimit(site, limit.window()));
                counts = 2;
            }
            worstRowsPerHour += counts * (SourceBucket.BUCKETS + 1) * SECONDS_PER_HOUR / limit.window().toSeconds();
        }
        if (worstRowsPerHour > sweptRowsPerHour) {
            throw new IllegalArgumentException("the doors can create " + worstRowsPerHour + " rows an hour at worst, above the "
                    + sweptRowsPerHour + " one instance's sweep deletes: lengthen a window or raise the sweep's capacity");
        }
        this.limits = perSource;
        this.siteLimits = perSite;
    }

    WindowLimit of(Door door) {
        return limits.get(door);
    }

    Optional<WindowLimit> siteOf(Door door) {
        return Optional.ofNullable(siteLimits.get(door));
    }
}
