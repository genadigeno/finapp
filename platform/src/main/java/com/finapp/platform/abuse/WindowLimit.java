package com.finapp.platform.abuse;

import java.time.Duration;
import java.util.Objects;

/**
 * How many requests one key may have admitted in one fixed window (`P15-TSK-022`).
 *
 * <p>A window is whole seconds because it is a stride for the database's {@code date_bin}: fixed and aligned, so every replica names
 * the same window for the same instant.
 *
 * @param limit how many are admitted in a window, at least one
 * @param window the window's length, at least a second and a whole number of seconds
 */
public record WindowLimit(int limit, Duration window) {

    public WindowLimit {
        Objects.requireNonNull(window, "window must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1 but was " + limit);
        }
        if (window.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("window must be at least a second but was " + window);
        }
        if (window.getNano() != 0) {
            throw new IllegalArgumentException("window must be whole seconds but was " + window);
        }
    }

    long windowSeconds() {
        return window.toSeconds();
    }
}
