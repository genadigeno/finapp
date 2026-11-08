package com.finapp.platform.inbox;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;

/**
 * The inbox's parked state (`P15-TSK-028`): the durable count of a record's failures, and the decision to set it aside.
 *
 * <p>{@code platform.inbox_message} records a handled message and only that. A failure has no effect, so it is written here, on the
 * same connection after the handler's transaction rolled back, and that write commits before the offset does: a record is never
 * acknowledged on the strength of a park that did not happen.
 */
public interface InboxParking {

    /** What is known of a record before it is handled. */
    enum Standing {
        NONE,
        FAILING,
        PARKED,
        HANDLED,
        DISCARDED;

        /** Decided, so a delivery of it is acknowledged without running the handler. */
        public boolean settled() {
            return this == PARKED || this == DISCARDED;
        }
    }

    /** What recording one more failure decided. */
    enum Verdict {
        /** Within its budget: the partition stalls and the record is retried. */
        RETRY,

        /** This failure spent the budget, and the record is parked now. */
        PARKED,

        /** Another instance parked, handled or discarded it first; there is nothing to retry. */
        ALREADY_SETTLED
    }

    /**
     * A record to count a failure against.
     *
     * @param correlation empty for a record whose headers could not be read
     */
    record Failure(InboxKey key, String group, String messageType, Optional<CorrelationId> correlation, String topic, int partition, long offset) {

        public Failure {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(group, "group must not be null");
            Objects.requireNonNull(messageType, "messageType must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            Objects.requireNonNull(topic, "topic must not be null");
        }
    }

    /** Where this record stands, read in the caller's unit of work. */
    Standing standing(Connection unitOfWork, InboxKey key);

    /** The handler has now succeeded; written with its effect, so a parked or failing row says so only if it did. */
    void handled(Connection unitOfWork, InboxKey key);

    /**
     * Counts one failure of the record's own, deciding in SQL whether it is the one that spends the budget. The caller commits;
     * a parked record's offset moves only after that.
     *
     * @param failureClass the failure's class name and nothing else
     */
    Verdict recordFailure(Connection unitOfWork, Failure failure, String failureClass);
}
