package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * What a routing decision is <em>for</em> (`P7-TSK-008`, ADR-0060 §2's own sentence:
 * "routing happens when an intent is confirmed, and when an outbound payment — a
 * withdrawal, or a return payment — is initiated"). Exactly one subject, always: the
 * payment intent the confirmation routed, or the withdrawal the dispatch routed.
 *
 * <p>`V016` carries the same rule for every writer (the intent/withdrawal XOR {@code CHECK}
 * on {@code routing_decision}), and each subject keeps its own one-chosen partial index —
 * {@code INV-RAIL-02}'s "routed once" arbitrated per subject, refusals still lawfully
 * accumulating beneath it.
 */
public record RoutingSubject(
        Optional<PaymentIntentId> intent, Optional<WithdrawalId> withdrawal) {

    public RoutingSubject {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(withdrawal, "withdrawal must not be null");
        if (intent.isPresent() == withdrawal.isPresent()) {
            throw new IllegalArgumentException(
                    "a routing decision has exactly one subject: an intent or a withdrawal"
                            + " (ADR-0060 §2)");
        }
    }

    public static RoutingSubject ofIntent(PaymentIntentId intent) {
        return new RoutingSubject(
                Optional.of(Objects.requireNonNull(intent, "intent must not be null")),
                Optional.empty());
    }

    public static RoutingSubject ofWithdrawal(WithdrawalId withdrawal) {
        return new RoutingSubject(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(withdrawal, "withdrawal must not be null")));
    }
}
