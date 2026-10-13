package com.finapp.app.lending;

import com.finapp.lending.WaiverKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.lending.waiver{kind, outcome}} (`P11-TSK-025`, PHASE_11_PLAN.md section 15, ADR-0099's operational impact).
 * A waiver act is counted once, after its transaction committed or its refusal rolled back, tagged with the waiver's kind
 * ({@code fee}, {@code fee_refund}, {@code interest}): {@code proposed}, {@code approved}, {@code rejected}, or the act's own
 * refusal - {@code exceeds_due}, {@code self_approval_refused}, {@code self_dealing_refused}, {@code loan_closed},
 * {@code proposal_pending}, {@code proposal_not_pending}. A self-dealing approval or rejection is refused before the waiver
 * is read, so its kind is {@code unknown}. A replayed key or a converged retry, an unknown loan, assessment, item or waiver,
 * a missing reason and a malformed request count nothing. Every series registered eagerly with its closed tags.
 * {@code self_dealing_refused} above zero is a signal for the security desk. Never an amount, a party, a loan or an
 * operator (ADR-0072).
 */
public final class LoanWaiverMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_waiver_total}, the plan's name. */
    public static final String WAIVER = "finapp.lending.waiver";

    /** The kind tag of an act refused before its waiver was read. */
    static final String UNKNOWN_KIND = "unknown";

    /** The closed set of outcomes. */
    public enum Outcome {
        PROPOSED,
        APPROVED,
        REJECTED,
        EXCEEDS_DUE,
        SELF_APPROVAL_REFUSED,
        SELF_DEALING_REFUSED,
        LOAN_CLOSED,
        PROPOSAL_PENDING,
        PROPOSAL_NOT_PENDING;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public LoanWaiverMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            for (WaiverKind kind : WaiverKind.values()) {
                counter(tag(kind), outcome);
            }
        }
        counter(UNKNOWN_KIND, Outcome.SELF_DEALING_REFUSED);
    }

    /** One waiver act on a waiver of {@code kind}, with its outcome. */
    public void record(WaiverKind kind, Outcome outcome) {
        counter(tag(Objects.requireNonNull(kind, "kind")), outcome).increment();
    }

    /** A self-dealing approval or rejection, refused before its waiver was read. */
    public void recordSelfDealingOfUnknownKind() {
        counter(UNKNOWN_KIND, Outcome.SELF_DEALING_REFUSED).increment();
    }

    static String tag(WaiverKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private Counter counter(String kind, Outcome outcome) {
        return Counter.builder(WAIVER)
                .description("Fee and interest waiver acts - proposed, approved, rejected, and refusals by reason - by the"
                        + " waiver's kind and outcome")
                .tags("kind", kind, "outcome", outcome.tag())
                .register(registry);
    }
}
