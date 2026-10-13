package com.finapp.app.lending;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.reversal{outcome}} (`P11-TSK-020`, PHASE_11_PLAN.md section 15, ADR-0099's operational impact). A
 * reversal act is counted once, after its transaction committed or its refusal rolled back: {@code proposed},
 * {@code approved}, {@code rejected}, or the act's own refusal - {@code self_approval_refused}, {@code self_dealing_refused},
 * {@code loan_closed}, {@code not_reversible}, {@code proposal_pending}, {@code proposal_not_pending},
 * {@code capital_unavailable}, {@code wallet_unavailable}. A replayed key or a converged retry, an unknown loan, repayment
 * or proposal, a missing reason and a malformed id count nothing. Every series registered eagerly with its closed tag.
 * {@code self_dealing_refused} above zero is a signal for the security desk - an insider tried their own loan. Never an
 * amount, a party, a loan or an operator (ADR-0072).
 */
public final class RepaymentReversalMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_reversal_total}, the plan's name. */
    public static final String REVERSAL = "finapp.lending.reversal";

    /** The closed set of outcomes. */
    public enum Outcome {
        PROPOSED,
        APPROVED,
        REJECTED,
        SELF_APPROVAL_REFUSED,
        SELF_DEALING_REFUSED,
        LOAN_CLOSED,
        NOT_REVERSIBLE,
        PROPOSAL_PENDING,
        PROPOSAL_NOT_PENDING,
        CAPITAL_UNAVAILABLE,
        WALLET_UNAVAILABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public RepaymentReversalMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
    }

    /** One reversal act, with its outcome. */
    public void record(Outcome outcome) {
        counter(outcome).increment();
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(REVERSAL)
                .description("Repayment reversal acts - proposed, approved, rejected, and refusals by reason - by outcome")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
