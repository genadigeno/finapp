package com.finapp.party;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The customers' figures for the operations overview (`P17-TSK-039`, gap G-150): how many customers stand in each lifecycle
 * status now, and of them how many opened, and how many last changed status, inside one window.
 *
 * <p>The platform keeps each customer's current status and the instant it last changed, not a history of every transition, so a
 * past day's standing counts cannot be read back: the standing counts are the committed state at the read, and the window
 * counts what the stored instants place inside it - a customer who changed twice in the window counts once, by the status it
 * reached last.
 */
public interface CustomerReports {

    /** One lifecycle status: the customers standing in it, and of them those opened and those last moved into it in the window. */
    record StatusCount(CustomerStatus status, long standing, long openedInWindow, long changedInWindow) {
        public StatusCount {
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /** Every status any customer stands in, with its counts; a status nobody stands in is absent. */
    List<StatusCount> counts(Connection unitOfWork, Instant from, Instant to);
}
