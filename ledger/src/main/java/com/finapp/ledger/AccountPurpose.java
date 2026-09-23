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
