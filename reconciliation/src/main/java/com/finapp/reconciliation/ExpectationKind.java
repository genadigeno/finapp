package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of completion a settlement expectation tracks (`P8-TSK-004`, ADR-0067 §2).
 *
 * <p>One value per externally settling completion the platform can make, plus the bank
 * statement's {@code REMITTANCE} — the whole decided vocabulary is stated now, because rule
 * set v1 names these kinds in its seeded rows and `V002`'s {@code CHECK} admits them, while
 * the openers arrive task by task: the card pair with `P8-TSK-004`, the dispute and push
 * kinds with `-005`, the payout pair with `-005`/`-019`, the remittance with `-009`.
 *
 * <p>{@code UNIQUE (kind, operation_ref)} makes the kind half of an expectation's identity:
 * one operation may legitimately open several expectations (a payout and its return; a
 * dispute's three stages ride distinct dispute-stage kinds), and the kind is what holds them
 * apart.
 */
public enum ExpectationKind {
    CARD_CAPTURE,
    CARD_REFUND,
    CHARGEBACK,
    CHARGEBACK_REVERSAL,
    DISPUTE_FEE,
    PUSH_PAY_IN,
    UNMATCHED_CONFIRMATION,
    PUSH_WITHDRAWAL,
    PUSH_RETURN,
    MERCHANT_PAYOUT,
    PAYOUT_RETURN,
    REMITTANCE;

    /** The `V002` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
