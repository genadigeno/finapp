package com.finapp.payments;

import java.time.Instant;
import java.util.Objects;

/**
 * The recorded operator fact about a rail's availability (`P7-TSK-003`, ADR-0060 §4): in or
 * out of service, why, by whom, since when — one row per rail in the shared database, read
 * inside the decision's transaction so N instances deciding in the same second read the same
 * answer, and recorded on the step that used it.
 *
 * <p><strong>An absent row means available.</strong> A rail is taken out of service by an
 * act, never by silence — the default keeps a fresh deployment routable without an operator
 * ceremony, and the decision records which observation it used either way
 * ({@code availabilityObserved} on the step).
 *
 * <p>No history table, deliberately: the acts are audited ({@code RAIL_AVAILABILITY_CHANGED},
 * with the operator's reason verbatim), and the decisions snapshot the observation they used —
 * which is the history that matters financially. Automatic availability from failure rates
 * (Phase 15/16) will write this same recorded fact, never a hidden switch.
 */
public record RailAvailability(
        RailId rail, boolean available, String reason, String changedBy, Instant changedAt) {

    public RailAvailability {
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(reason, "reason must not be null - the act is a judgement");
        Objects.requireNonNull(changedBy, "changedBy must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "an availability change requires its reason (INV-AUD-03): taking a rail"
                            + " out of service - or returning it - is an operational judgement");
        }
    }
}
