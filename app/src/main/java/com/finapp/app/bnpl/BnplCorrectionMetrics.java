package com.finapp.app.bnpl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.bnpl.reversal{outcome}} and {@code finapp.bnpl.waiver{outcome}} (`P12-TSK-024`; {@code PHASE_12_PLAN.md}
 * section 15 - the corrections' rates): each act counted once, after its transaction committed or its refusal rolled back -
 * {@code proposed}, {@code approved} (a waiver's {@code closed} when it closed the obligation), {@code rejected}, or the
 * refusal by reason. A replayed key, a converged retry, an unknown target, a malformed body or a missing reason count
 * nothing. Every series registered eagerly with its closed tags. Never an amount, an agreement, a person or a key
 * (ADR-0072).
 */
public final class BnplCorrectionMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_reversal_total}, the plan's name. */
    public static final String REVERSAL = "finapp.bnpl.reversal";

    /** Spelled with dots - exported as {@code finapp_bnpl_waiver_total}, the plan's name. */
    public static final String WAIVER = "finapp.bnpl.waiver";

    /** The closed set of a reversal's outcomes. */
    public enum ReversalOutcome {
        PROPOSED,
        APPROVED,
        REJECTED,
        SELF_APPROVAL_REFUSED,
        SELF_DEALING_REFUSED,
        OBLIGATION_CLOSED,
        PROPOSAL_PENDING,
        PROPOSAL_NOT_PENDING,
        NOT_REVERSIBLE,
        CAPITAL_UNAVAILABLE,
        WALLET_UNAVAILABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The closed set of a waiver's outcomes. */
    public enum WaiverOutcome {
        PROPOSED,
        APPROVED,
        CLOSED,
        REJECTED,
        SELF_APPROVAL_REFUSED,
        SELF_DEALING_REFUSED,
        OBLIGATION_CLOSED,
        PROPOSAL_PENDING,
        PROPOSAL_NOT_PENDING,
        EXCEEDS_DUE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public BnplCorrectionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (ReversalOutcome outcome : ReversalOutcome.values()) {
            reversal(outcome);
        }
        for (WaiverOutcome outcome : WaiverOutcome.values()) {
            waiver(outcome);
        }
    }

    /** One reversal act, with its outcome. */
    public void record(ReversalOutcome outcome) {
        reversal(outcome).increment();
    }

    /** One waiver act, with its outcome. */
    public void record(WaiverOutcome outcome) {
        waiver(outcome).increment();
    }

    private Counter reversal(ReversalOutcome outcome) {
        return Counter.builder(REVERSAL)
                .description("BNPL repayment reversals under four eyes - proposed, approved, rejected, and refusals by"
                        + " reason")
                .tag("outcome", outcome.tag())
                .register(registry);
    }

    private Counter waiver(WaiverOutcome outcome) {
        return Counter.builder(WAIVER)
                .description("BNPL fee waivers under four eyes - proposed, approved (closing or not), rejected, and refusals"
                        + " by reason")
                .tag("outcome", outcome.tag())
                .register(registry);
    }
}
