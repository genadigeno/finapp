package com.finapp.app.bnpl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.bnpl.dispute{outcome}} (`P12-TSK-023`; {@code PHASE_12_PLAN.md} section 15, ADR-0106 decision 12). A
 * dispute act is counted once, after its transaction committed or its refusal rolled back: the act's own outcome
 * ({@code opened}, {@code responded}, {@code resolved_refund}, {@code resolved_upheld}, {@code withdrawn}, {@code proposed},
 * {@code proposal_rejected}), or its refusal ({@code dispute_open}, {@code not_actionable}, {@code exceeds_purchase},
 * {@code fully_refunded}, {@code not_postable}, {@code self_approval_refused}, {@code self_dealing_refused}), and
 * {@code sweep_failed} for a deadline step that rolled back. A replay, an unknown dispute, a malformed body or a missing
 * reason counts nothing. Every series registered eagerly. Never an amount, a dispute, a purchase or a party (ADR-0072).
 */
public final class BnplDisputeMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_dispute_total}, the plan's name. */
    public static final String DISPUTE = "finapp.bnpl.dispute";

    /** The closed set of outcomes. */
    public enum Outcome {
        OPENED,
        RESPONDED,
        RESOLVED_REFUND,
        RESOLVED_UPHELD,
        WITHDRAWN,
        PROPOSED,
        PROPOSAL_REJECTED,
        DISPUTE_OPEN,
        NOT_ACTIONABLE,
        EXCEEDS_PURCHASE,
        FULLY_REFUNDED,
        NOT_POSTABLE,
        SELF_APPROVAL_REFUSED,
        SELF_DEALING_REFUSED,
        SWEEP_FAILED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The meter's outcome of a committed act's own. */
        static Outcome of(com.finapp.bnpl.BnplDisputes.Outcome acted) {
            return Outcome.valueOf(acted.name());
        }
    }

    private final MeterRegistry registry;

    public BnplDisputeMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
    }

    /** One act, with its outcome. */
    public void record(Outcome outcome) {
        counter(outcome).increment();
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(DISPUTE)
                .description("BNPL disputes - openings, the merchant's answers, resolutions, withdrawals, proposals and"
                        + " refusals by reason")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
