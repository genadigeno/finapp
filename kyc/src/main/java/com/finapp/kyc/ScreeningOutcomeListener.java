package com.finapp.kyc;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;

/**
 * Who moves with a counterparty screening's decision (`P9-TSK-016`, ADR-0081 point 4; PHASE_9_PLAN.md
 * section 3's ports table) - declared by kyc, implemented by {@code crossborder} (the beneficiary,
 * `P9-TSK-017`), called <strong>inside the decision's own transaction</strong> (T-e), so the
 * beneficiary's projection and kyc's decision commit together or not at all. A no-op on a revoked
 * beneficiary is the implementer's rule; a failure here rolls the decision back.
 */
@FunctionalInterface
public interface ScreeningOutcomeListener {

    /** The decision just written for {@code screening}, on the decision's unit of work. */
    void decided(Connection unitOfWork, Outcome outcome);

    /** What was decided - identifiers, the outcome, when, and under which correlation; never the name. */
    record Outcome(
            CounterpartyScreeningId screening,
            String requestReference,
            CounterpartyScreeningStatus status,
            Instant decidedAt,
            CorrelationId correlation) {
        public Outcome {
            Objects.requireNonNull(screening, "screening must not be null");
            Objects.requireNonNull(requestReference, "requestReference must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    /**
     * The default until a beneficiary listens (`P9-TSK-017`): refuses, so a screening decided before
     * its listener exists rolls back instead of leaving a projection behind - nothing calls kyc's
     * screening before then.
     */
    ScreeningOutcomeListener REFUSING = (unitOfWork, outcome) -> {
        throw new IllegalStateException(
                "no screening outcome listener is composed yet: the cross-border beneficiary listens from"
                        + " P9-TSK-017");
    };
}
