package com.finapp.app.lending;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.amendment{outcome}} (`P11-TSK-027`, PHASE_11_PLAN.md section 15, ADR-0099's operational impact). A
 * restructuring act is counted once, after its transaction committed or its refusal rolled back: {@code proposed},
 * {@code approved}, {@code rejected}, {@code accepted}, or the act's own refusal - {@code lapsed},
 * {@code terms_invalid}, {@code self_approval_refused}, {@code self_dealing_refused}, {@code loan_closed},
 * {@code proposal_pending}, {@code proposal_not_pending}, {@code document_changed}. A replayed key or a converged retry, an
 * unknown loan or amendment, a missing reason and a malformed request count nothing. Every series registered eagerly with
 * its closed tag. {@code self_dealing_refused} above zero is a signal for the security desk; {@code lapsed} a signal that
 * offers are expiring or racing payoffs. Never an amount, a rate, a party, a loan or an operator (ADR-0072).
 */
public final class LoanAmendmentMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_amendment_total}, the plan's name. */
    public static final String AMENDMENT = "finapp.lending.amendment";

    /** The closed set of outcomes. */
    public enum Outcome {
        PROPOSED,
        APPROVED,
        REJECTED,
        ACCEPTED,
        LAPSED,
        TERMS_INVALID,
        SELF_APPROVAL_REFUSED,
        SELF_DEALING_REFUSED,
        LOAN_CLOSED,
        PROPOSAL_PENDING,
        PROPOSAL_NOT_PENDING,
        DOCUMENT_CHANGED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public LoanAmendmentMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
    }

    /** One restructuring act, with its outcome. */
    public void record(Outcome outcome) {
        counter(Objects.requireNonNull(outcome, "outcome")).increment();
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(AMENDMENT)
                .description("Restructuring acts - proposed, approved, rejected, accepted, and refusals by reason - by"
                        + " outcome")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
