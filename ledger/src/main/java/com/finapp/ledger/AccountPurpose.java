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
 * capability needing it — the {@code ConsentPurpose} shape. In Phase 3 four of the first six had
 * <em>no producer</em>, deliberately: they were the recorded seams ({@code ROADMAP.md} §Seam
 * register), and value parked in suspense had to be trackable before anything could park value
 * there ({@code INV-REC-05}). Since then each arrived with its producer — {@code FEE_REVENUE} in
 * Phase 6, {@code SETTLEMENT_CLEARING} with the card capture, {@code SUSPENSE_UNMATCHED} with the
 * instant rail's parking (Phase 7) — and the clearings and dispute accounts joined with theirs;
 * {@code FX_POSITION} still waits for Phase 9. (This read "Four of the six have no producer"
 * until the Phase 7 -&gt; 8 transition's gate.)
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

    /**
     * The CARD rail's value in flight: what the card PSP owes the platform for captures, net of
     * refunds, chargebacks and fees, until its clearing reports discharge it. Phase 8's seam.
     * ADR-0059 section 4 narrowed this member to the card rail - every external rail has its
     * own position, and the instant scheme's is {@link #INSTANT_CLEARING} - and the Phase 7
     * review carried the narrowing into this javadoc, which still read "an external
     * counterparty". Which rail posts where is the rail's declaration, never this name.
     */
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

    /**
     * What external processing costs the platform (ADR-0065 §2, `P8-TSK-009`): an operational
     * EXPENSE — the counterparty's own fees, recognised once per ACCEPTED settlement batch
     * from the report's fee lines (DR here / CR that counterparty's clearing position; the
     * mirror for a net rebate), keyed {@code settlement-batch:<batchId>}. This settles the
     * per-rail cost-meter deferral (`DECISIONS.md`:671, ADR-0060 §6) as a LEDGER fact — the
     * provider-costs question is answered by an audited report over these rows, never a
     * metric (ADR-0072). Distinct from {@link #DISPUTE_COSTS} on purpose: a dispute fee is
     * posted at its stage and allocates at matching; a processing fee is learned only from
     * the report. Added with its first poster beside `V016`, which also re-states the
     * reconciled-positions binding with this member (the `V014` pattern).
     */
    PROCESSING_COSTS(OwnerKind.OPERATIONAL),

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

    /**
     * The positions reconciliation explains and therefore owns the correction of
     * (`P8-TSK-006`, ADR-0071, {@code INV-REC-06}): value here moves only through a system
     * posting or a reconciliation-owned resolution — a {@code MANUAL}-origin adjustment line
     * on any of them is refused at the domain and by `V015`'s trigger, because a free
     * adjustment on a reconciled position is either unexplained value or destroyed evidence
     * that a break existed. `PROCESSING_COSTS` joined with its poster (`P8-TSK-009`, `V016`
     * re-stating the generated binding list — the `V014` pattern): its every line is a
     * recognition entry's, so a free adjustment there would un-explain an accepted report.
     * The remaining Phase 8 purposes (`RECONCILIATION_LOSSES`, `RECONCILIATION_GAINS`,
     * `CASH_AT_BANK`) join with their tasks (`-015`, `-016`), re-stating it again
     * (`V017`–`V018`).
     */
    public static java.util.Set<AccountPurpose> reconciledPositions() {
        return java.util.EnumSet.of(
                SETTLEMENT_CLEARING,
                INSTANT_CLEARING,
                PAYOUT_CLEARING,
                SUSPENSE_UNMATCHED,
                PROCESSING_COSTS);
    }

    /** The reconciled positions as a SQL literal list, for `V015`'s binding trigger. */
    public static String sqlReconciledPositionsList() {
        return reconciledPositions().stream()
                .map(purpose -> "'" + purpose.name() + "'")
                .collect(Collectors.joining(", "));
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
