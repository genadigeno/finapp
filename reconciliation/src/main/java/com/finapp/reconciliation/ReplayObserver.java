package com.finapp.reconciliation;

/**
 * The replay's one meter seam (`P8-TSK-022`, ADR-0072): {@code finapp.reconciliation.replay} by
 * {@code outcome}, implemented in {@code app}. A verdict, never an amount; reconciliation names
 * no meter registry.
 */
@FunctionalInterface
public interface ReplayObserver {

    /** {@code identical} or {@code diverged}, once per appended replay. */
    void replayed(String outcome);

    ReplayObserver NONE = outcome -> {};
}
