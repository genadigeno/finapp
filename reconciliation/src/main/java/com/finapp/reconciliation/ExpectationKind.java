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
    REMITTANCE,

    /**
     * A cover's sold leg on {@code FX_PROVIDER_CLEARING(provider)} - OUTBOUND, opened by the cover
     * entry through {@code FxSettlementExpectations} (`P9-TSK-011`; the cover posts at -012).
     */
    FX_SELL_LEG,

    /** A cover's bought leg - INBOUND, the same opening. */
    FX_BUY_LEG,

    /**
     * A cross-border outbound credit on {@code CORRIDOR_CLEARING(rail)} - OUTBOUND, keyed
     * {@code END_TO_END_REF} = {@code E} with the provider's reference as its alias (`P9-TSK-014`,
     * ADR-0082 section 3). Admitted with the corridor's source; its opener is the outbound credit's
     * completion (`P9-TSK-019`), when payments' port gains the kind.
     */
    CROSSBORDER_PAYOUT,

    /**
     * A cross-border credit coming back - INBOUND and operation-anchored (no key of its own: its
     * line reaches the anchor, the payout-return precedent). Its opener is the return fact
     * (`P9-TSK-023`).
     */
    CROSSBORDER_RETURN;

    /** The `V002` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The members of {@code members}, in declaration order, as a SQL literal list. */
    public static String sqlValueList(java.util.Set<ExpectationKind> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
