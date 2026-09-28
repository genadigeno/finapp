package com.finapp.payments;

import java.util.Optional;

/**
 * The routing decision's meterable summary (`P7-TSK-003`, ADR-0060's operational impact) — a
 * port because the meters live with the composition root (the {@code MeteredPaymentProvider}
 * reasoning: what the running system observes must be what the wired instance does), while
 * the decision is made here.
 *
 * <p>Telemetry, never a record: the counts of record are the decision rows. Both tags are
 * bounded enums — a rail id from the declared directory, a {@link RoutingRejection} name —
 * so the meter's cardinality is the vocabulary's.
 */
public interface RoutingTelemetry {

    /**
     * One decision made: the chosen rail, or — for the recorded refusal — the trail's first
     * rejection (empty when no rule matched at all).
     */
    void decided(Optional<RailId> chosen, Optional<RoutingRejection> leadingRejection);

    /** For compositions that do not meter (tests, tools). */
    RoutingTelemetry NONE = (chosen, leadingRejection) -> {};
}
