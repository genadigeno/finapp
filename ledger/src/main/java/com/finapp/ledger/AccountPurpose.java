package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

/**
 * What a ledger account is <em>for</em> (ADR-0040).
 *
 * <p>{@link AccountType} answers the accountant's question — which side of the balance sheet.
 * This answers the engineer's — <em>which account do I post the fee to?</em> Collapsing them
 * produces either a forty-member "type" that is no accounting classification, or a codebase that
 * finds accounts by string-matching names. Roll-up is a {@code GROUP BY} over this and the type,
 * never a walk over a tree.
 *
 * <p><strong>A closed enum, and adding a member is a reviewed act</strong> that arrives with the
 * capability needing it — the {@code ConsentPurpose} shape. Four of the six have <em>no producer
 * in Phase 3</em>, deliberately: they are the recorded seams ({@code ROADMAP.md} §Seam register)
 * — {@code SETTLEMENT_CLEARING} and {@code SUSPENSE_UNMATCHED} for Phase 8, {@code FX_POSITION}
 * for Phase 9, {@code FEE_REVENUE} for Phase 6 — and value parked in suspense must be trackable
 * before anything can park value there ({@code INV-REC-05}).
 *
 * <p>The owner kind is derived here, one function ({@code OwnerKind}'s javadoc says why), and
 * the schema {@code CHECK} generated from {@link #sqlOwnerKindRule()} holds the stored pair to
 * it.
 */
@RequiredArgsConstructor
public enum AccountPurpose {

    /** A customer's stored value. The only customer-owned purpose in Phase 3. */
    CUSTOMER_WALLET(OwnerKind.CUSTOMER),

    /**
     * What the platform owes one merchant: captured minus fees minus refunds minus payouts —
     * and the <em>only</em> place that figure exists ({@code INV-MER-02}; ADR-0050's capture
     * credits it gross with the fee in the same entry, ADR-0051's payout debits it under a
     * hold). Added by `P6-TSK-003` with the capability needing it — the member's own doctrine
     * above — beside a new `V011` regenerating the four constraints this list feeds.
     */
    MERCHANT_PAYABLE(OwnerKind.MERCHANT),

    /** Value in flight between the platform and an external counterparty. Phase 8's seam. */
    SETTLEMENT_CLEARING(OwnerKind.OPERATIONAL),

    /**
     * Payouts the platform has irrevocably instructed and the rail has not yet settled
     * (ADR-0051 §3, ADR-0057): a completed payout credits it and debits the merchant's payable,
     * keyed {@code merchant-payout:<payoutId>}, and Phase 8's settlement will debit it against
     * cash. {@code INV-SET-01} on the outbound side — "completed" means instructed, never
     * settled. Added by `P6-TSK-012` with the capability needing it, beside a new `V012`
     * regenerating the four constraints this list feeds.
     */
    PAYOUT_CLEARING(OwnerKind.OPERATIONAL),

    /**
     * The instant scheme's own clearing position (ADR-0062 §4, ADR-0059 §4): every
     * accepted push lands here — a pay-in debits it (the scheme will settle to us), a
     * withdrawal credits it — and Phase 8 discharges it against the scheme's settlement
     * reports, per cycle. An operational ASSET: the net receivable on the scheme. The NAME
     * arrives with the rail's declaration (`P7-TSK-006` — a settling rail's descriptor
     * must state its clearing position at construction) beside `V013` regenerating the four
     * constraints and seeding the accounts; the first POSTING stays with the flows
     * (`P7-TSK-009`), which is the substance of the ADRs' "added with its first poster".
     */
    INSTANT_CLEARING(OwnerKind.OPERATIONAL),

    /**
     * What the card network took that the platform has not (yet) recovered from anyone
     * (ADR-0061 §3–§5, `P7-TSK-013`): an operational ASSET. A chargeback debits it by the whole
     * amount the network took against the card rail's clearing position — the external fact —
     * and the counterparty's attributed share is credited back out of it in a second entry, so
     * its balance is exactly the part nobody has been charged: the value the network took that
     * the platform had already returned (the excess), and any share parked because the
     * counterparty's account could no longer take a posting. A win empties it; a loss writes the
     * excess off to {@link #DISPUTE_COSTS}. Added with its first poster, beside `V014`.
     */
    CHARGEBACK_RECOVERABLE(OwnerKind.OPERATIONAL),

    /**
     * What disputes cost the platform (ADR-0061 §4, `P7-TSK-013`): an operational EXPENSE — the
     * excess of a lost chargeback written off, and the dispute fees the PSP reports. The
     * platform bears both in Phase 7; passing a fee on to merchants is a fee-schedule extension,
     * recorded as out of scope. Added with its first poster, beside `V014`.
     */
    DISPUTE_COSTS(OwnerKind.OPERATIONAL),

    /** Fees earned. Phase 6's seam; nothing posts to it before then. */
    FEE_REVENUE(OwnerKind.OPERATIONAL),

    /** The platform's position from currency conversion. Phase 9's seam ({@code INV-FX-01}). */
    FX_POSITION(OwnerKind.OPERATIONAL),

    /** Where allocation residuals are posted, never absorbed ({@code INV-BAL-03}). */
    ROUNDING_RESIDUAL(OwnerKind.OPERATIONAL),

    /** Unattributable value, parked, aged and reported ({@code INV-REC-05}). Phase 8's seam. */
    SUSPENSE_UNMATCHED(OwnerKind.SUSPENSE);

    private final OwnerKind ownerKind;

    /** Whose account an account of this purpose is. Derived, stored, and {@code CHECK}-held. */
    public OwnerKind ownerKind() {
        return ownerKind;
    }

    /** The purposes as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(purpose -> "'" + purpose.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The purpose→owner-kind derivation as a SQL predicate, for the coherence {@code CHECK} —
     * one definition, two artefacts, the {@link AccountType#sqlNormalBalanceRule()} pattern.
     */
    public static String sqlOwnerKindRule() {
        return Arrays.stream(values())
                .map(
                        purpose ->
                                "(purpose = '"
                                        + purpose.name()
                                        + "' AND owner_kind = '"
                                        + purpose.ownerKind().name()
                                        + "')")
                .collect(Collectors.joining(" OR "));
    }
}
