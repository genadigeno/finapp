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
    CROSSBORDER_RETURN,

    /**
     * The platform's own corporate transfer funding lending (`P11-TSK-004`, ADR-0096 section 4):
     * INBOUND on {@code LENDING_CAPITAL}, opened by a four-eyes capital contribution's approval
     * under the settlement bank's statement source, keyed {@code REMITTANCE_REF} = the
     * contribution's reference. It records NO posting of ours (the approval posts nothing - capital
     * exists only once the bank's statement shows the cash) and is never aged: an approved
     * contribution whose window passes unmatched is reported lapsed by lending, never a break a
     * person could resolve into capital. Matched only by bank recognition, in the statement's own
     * acceptance ({@code CapitalRecognition}), never by the run's matcher.
     */
    LENDING_CAPITAL_CONTRIBUTION;

    /**
     * Whether an expectation of this kind records the journal entry its completion posted - every
     * kind but the bank statement's two: a {@code REMITTANCE} (opened at report acceptance) and a
     * {@code LENDING_CAPITAL_CONTRIBUTION} (opened by an approval that posts nothing). The
     * {@code expectation_records_its_posting} {@code CHECK}'s rule (reconciliation `V022`).
     */
    public boolean recordsAPosting() {
        return this != REMITTANCE && this != LENDING_CAPITAL_CONTRIBUTION;
    }

    /**
     * Whether the ageing sweep ages this kind into an overdue {@code MISSING_EXTERNAL} - every kind
     * but {@code LENDING_CAPITAL_CONTRIBUTION} (`P11-TSK-004`): its remainder is no position of the
     * books (the approval posted nothing), so a break on it would hold nothing a person could
     * dispose of, and a write-off would credit capital into being. Held for every writer by
     * `V022`'s {@code expectation_capital_is_never_aged} {@code CHECK}.
     */
    public boolean agedByReconciliation() {
        return this != LENDING_CAPITAL_CONTRIBUTION;
    }

    /** The kinds the ageing sweep never ages, as a SQL literal list. */
    public static String sqlNotAgedList() {
        java.util.Set<ExpectationKind> notAged = java.util.EnumSet.noneOf(ExpectationKind.class);
        for (ExpectationKind kind : values()) {
            if (!kind.agedByReconciliation()) {
                notAged.add(kind);
            }
        }
        return sqlValueList(notAged);
    }

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
