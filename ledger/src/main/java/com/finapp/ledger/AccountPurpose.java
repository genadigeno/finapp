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

    // ------------------------------------------------------------------------------------------
    // THE LOAN BOOK (`P11-TSK-003`, ledger `V026`; ADR-0096 section 1, PHASE_11_PLAN.md section
    // 12.8; INV-LND-01). Six accounts per loan or credit line, opened together in the loan's one
    // currency by lending's LoanAccounts at acceptance - due split from not-due and interest from
    // principal, so past-due, income and the subledger proof each have a ledger anchor rather than
    // a calculation over rows. Every figure a borrower is charged is a balance of one of these,
    // derived from journal lines; posted only by lending's operations (LendingBooksHaveOnePosterTest)
    // and closed to free adjustments. None is a reconciled position: lending moves no money across
    // a rail itself, so they open no expectations (no *_CLEARING purpose, INV-RAIL-04) and are
    // proven by the subledger proof against lending's own facts (`P11-TSK-029`).
    // ------------------------------------------------------------------------------------------

    /**
     * Principal outstanding and not yet due - for a credit line, drawn principal not yet billed.
     * An ASSET, debit-normal: the platform's claim on the borrower, raised by a disbursement or a
     * draw (rule 1), re-classified to {@link #LOAN_PRINCIPAL_DUE} by billing (rule 3).
     */
    LOAN_PRINCIPAL(OwnerKind.LOAN),

    /** Billed principal unpaid. An ASSET, debit-normal; days past due read from it (rule 3). */
    LOAN_PRINCIPAL_DUE(OwnerKind.LOAN),

    /** Interest earned and not yet due. An ASSET, debit-normal; a day's accrual debits it (rule 2). */
    LOAN_INTEREST_ACCRUED(OwnerKind.LOAN),

    /** Billed interest unpaid. An ASSET, debit-normal (rule 3). */
    LOAN_INTEREST_DUE(OwnerKind.LOAN),

    /** Assessed fees unpaid. An ASSET, debit-normal (rule 4). */
    LOAN_FEES_DUE(OwnerKind.LOAN),

    /**
     * Money received beyond every receivable, owed back to the borrower. A LIABILITY,
     * credit-normal: the one per-loan account the platform owes on (rules 5, 6, 7, 8a).
     */
    LOAN_CREDIT_BALANCE(OwnerKind.LOAN),

    // ------------------------------------------------------------------------------------------
    // THE BNPL BOOK (`P12-TSK-003`, ledger `V027`; ADR-0103 decision 1, PHASE_12_PLAN.md section
    // 12.11; INV-BNPL-01). Six accounts per customer repayment obligation, opened together in the
    // obligation's one currency by bnpl's BnplAccounts in the purchase's transaction - the loan
    // book's split (due from not-due, interest from principal) for the same reasons, but separate
    // purposes under their own owner kind (OQ-11): lending's posters, proofs and identities never
    // see a BNPL line, and bnpl's never a loan's. Every figure a BNPL customer owes is a balance of
    // one of these, derived from journal lines; posted only by bnpl's operations
    // (BnplBooksHaveOnePosterTest) and closed to free adjustments. None is a reconciled position:
    // bnpl moves no money across a rail, so they open no expectations (no *_CLEARING purpose,
    // INV-RAIL-04) and are proven by bnpl's subledger and three-way proofs (`P12-TSK-025`).
    // ------------------------------------------------------------------------------------------

    /**
     * Purchase principal not yet due. An ASSET, debit-normal: the platform's claim on the customer,
     * raised by the purchase's financing (rule F1), re-classified to {@link #BNPL_PRINCIPAL_DUE} by
     * billing (F2) and cancelled by a merchant refund, latest instalments first (F5).
     */
    BNPL_PRINCIPAL(OwnerKind.BNPL_OBLIGATION),

    /** Billed principal unpaid. An ASSET, debit-normal; days past due read from it (F2, F3). */
    BNPL_PRINCIPAL_DUE(OwnerKind.BNPL_OBLIGATION),

    /** Pay-in-N interest earned and not yet due. An ASSET, debit-normal (F2a); pay-in-4 holds none. */
    BNPL_INTEREST_ACCRUED(OwnerKind.BNPL_OBLIGATION),

    /** Pay-in-N billed interest unpaid. An ASSET, debit-normal (F2). */
    BNPL_INTEREST_DUE(OwnerKind.BNPL_OBLIGATION),

    /** Assessed late fees unpaid. An ASSET, debit-normal (F4; waived by F7). */
    BNPL_FEES_DUE(OwnerKind.BNPL_OBLIGATION),

    /**
     * Money received beyond every receivable, or a refund's excess the wallet could not take, owed
     * back to the customer. A LIABILITY, credit-normal: the one per-obligation account the platform
     * owes on (F3, F3a, F6).
     */
    BNPL_CREDIT_BALANCE(OwnerKind.BNPL_OBLIGATION),

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
     * The platform's cash at its settlement bank (ADR-0065 §3, `P8-TSK-016`): an operational
     * ASSET posted by EXACTLY ONE poster, the recognition of an accepted bank statement (DR
     * the statement's net credits / CR its net debits, netted to one line, against each
     * attributed counterparty's clearing position) — and, from `P8-TSK-023`, by the reversal
     * of that recognition when a statement is repudiated. Cash always follows the bank's own
     * statement, never a report ({@code INV-SET-06}); a static rule refuses any other code that
     * names it, and as a reconciled position it is closed to free adjustments. Added with its
     * first poster beside `V018`.
     */
    CASH_AT_BANK(OwnerKind.OPERATIONAL),

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

    /**
     * What unexplained value cost the platform (ADR-0071 §2, `P8-TSK-015`): an operational
     * EXPENSE posted ONLY by an approved four-eyes {@code WRITE_OFF} resolution — an INBOUND
     * remainder that will never arrive, or a DEBIT suspense item nobody can recover (DR here /
     * CR the position or {@code SUSPENSE_UNMATCHED}). A reconciled position: only a
     * {@code RECONCILIATION}-origin proposal reaches it, and only through the ledger's owned
     * approval. Added with its first poster beside `V017`.
     */
    RECONCILIATION_LOSSES(OwnerKind.OPERATIONAL),

    /** Fees earned. Phase 6's seam; nothing posts to it before then. */
    FEE_REVENUE(OwnerKind.OPERATIONAL),

    /**
     * Unclaimed value the platform recognised as its own (ADR-0070 §4, ADR-0071 §2,
     * `P8-TSK-015`): operational REVENUE posted ONLY by an approved four-eyes
     * {@code RECOGNISE_GAIN} resolution of a CREDIT suspense item older than the pinned
     * {@code gain_min_age_days} (DR {@code SUSPENSE_UNMATCHED} / CR here). A reconciled
     * position, closed to free adjustments like its loss twin. Added beside `V017`.
     */
    RECONCILIATION_GAINS(OwnerKind.OPERATIONAL),

    /** The platform's position from currency conversion. Phase 9's seam ({@code INV-FX-01}). */
    FX_POSITION(OwnerKind.OPERATIONAL),

    /** Where allocation residuals are posted, never absorbed ({@code INV-BAL-03}). */
    ROUNDING_RESIDUAL(OwnerKind.OPERATIONAL),

    /**
     * The spread and markup a wallet conversion earns, recognised explicitly in the computed
     * leg's currency ({@code INV-FX-03}). Posted only by {@code fx}'s {@code ConversionLines}
     * ({@code FxBooksHaveOnePosterTest}); a REVENUE account, credit-normal. Added beside `V020`
     * with its first poster (`P9-TSK-009`).
     */
    FX_SPREAD_REVENUE(OwnerKind.OPERATIONAL),

    /**
     * A cover's realised gain: the provider executed better than the plan's position leg, in that
     * leg's currency (`P9-TSK-012`; ADR-0077 section 6, PHASE_9_PLAN.md section 12.4(f)). Posted
     * only by {@code fx}'s {@code CoverLines} ({@code FxBooksHaveOnePosterTest}); REVENUE,
     * credit-normal; never netted with {@link #FX_REALISED_LOSSES}. Added beside `V023`.
     */
    FX_REALISED_GAINS(OwnerKind.OPERATIONAL),

    /**
     * A cover's realised loss: the provider executed worse than the plan's position leg, in that
     * leg's currency (`P9-TSK-012`). Posted only by {@code CoverLines}; EXPENSE, debit-normal.
     * Added beside `V023`.
     */
    FX_REALISED_LOSSES(OwnerKind.OPERATIONAL),

    /**
     * What one FX provider owes the platform, per currency - the first counterparty-owned
     * purpose (ADR-0078; {@code INV-RAIL-04}): there is no "the" FX provider clearing, only
     * {@code fx-sim-a}'s, resolved by {@link ChartOfAccounts#resolve(Object, AccountPurpose,
     * String, CurrencyCode)}. An ASSET, debit-normal (pinned in {@code OperationalChartMigrationTest}).
     * Admitted by `V021` with the mechanism (`P9-TSK-010`) so every counterparty rule is proven
     * against a real purpose; its counterparty and accounts arrived together in `V022`
     * (`P9-TSK-011`), when it joined {@link #reconciledPositions()} with its source.
     */
    FX_PROVIDER_CLEARING(OwnerKind.COUNTERPARTY),

    /**
     * What the platform owes one corridor provider, per currency (`P9-TSK-014`, ADR-0078, ADR-0080
     * section 1; {@code INV-RAIL-04}): the corridor rail's clearing position, counterparty-owned like
     * {@link #FX_PROVIDER_CLEARING} - {@code corridor-sim-a}'s, never a shared one. A LIABILITY,
     * credit-normal (pinned in {@code OperationalChartMigrationTest}): an accepted outbound credit
     * (`P9-TSK-019`) credits it, the provider's own report discharges it. Admitted with its
     * counterparty, its three accounts and its source in `V024`, joining {@link #reconciledPositions()}.
     */
    CORRIDOR_CLEARING(OwnerKind.COUNTERPARTY),

    /**
     * Interest a loan has earned, per currency (`P11-TSK-003`, `V026`; ADR-0096 rule 2): REVENUE,
     * credit-normal, credited by a day's accrual and debited only by an interest waiver (rule 9).
     * Posted only by lending's operations; closed to free adjustments.
     */
    LOAN_INTEREST_INCOME(OwnerKind.OPERATIONAL),

    /**
     * Fees a loan has earned, per currency (`V026`; rules 1, 4, 8, 8a): REVENUE, credit-normal -
     * the origination fee recognised upfront (A17) and the late fees assessed. Posted only by
     * lending's operations; closed to free adjustments.
     */
    LOAN_FEE_INCOME(OwnerKind.OPERATIONAL),

    /**
     * A loan written off, per currency (`V026`; rule 14): EXPENSE, debit-normal. Seeded now so the
     * account plan never changes when Phase 14 builds the write-off; <strong>posted by nothing in
     * Phase 11</strong>, and closed to free adjustments so no person can post a loss here either.
     */
    LOAN_WRITE_OFF_EXPENSE(OwnerKind.OPERATIONAL),

    /**
     * The platform's own funds committed to lending, per currency (`V026`; ADR-0096 section 4,
     * PHASE_11_PLAN.md section 12.9; {@code INV-LND-13}): EQUITY, credit-normal. Credited ONLY by
     * bank recognition of a statement line matched to a four-eyes capital contribution's
     * expectation (rule 0, `P11-TSK-004`) - capital is never typed, adjusted to fit or posted by a
     * person. A reconciled position: completeness reports any line no recognition explains, and a
     * free adjustment is refused. Deliberately EQUITY and never debited by a disbursement: capital
     * is a source of funds and the loan a use; the headroom invariant, judged under this account's
     * row lock, is what makes "funded from capital" binding.
     */
    LENDING_CAPITAL(OwnerKind.OPERATIONAL),

    /**
     * The merchant discount a BNPL purchase earns, per currency (`P12-TSK-003`, `V027`; ADR-0103
     * decisions 1 and 2, OQ-19): REVENUE, credit-normal - credited by the financing (F1) out of the
     * merchant's payable, debited by a refund's pro-rated return (F5). Its own purpose, never
     * merchant's {@link #FEE_REVENUE}: a card processing fee and a credit-funded discount are
     * different income whose Phase 14 treatment may part. Posted only by bnpl's operations; closed
     * to free adjustments. <strong>Not capital</strong> (lending A29).
     */
    BNPL_MERCHANT_FEE_INCOME(OwnerKind.OPERATIONAL),

    /**
     * Pay-in-N interest earned, per currency (`V027`; F2a): REVENUE, credit-normal, released only by
     * a refund's cancellation (F5a). Posted only by bnpl's operations; closed to free adjustments.
     */
    BNPL_INTEREST_INCOME(OwnerKind.OPERATIONAL),

    /**
     * BNPL late fees earned, per currency (`V027`; F4): REVENUE, credit-normal, debited by a
     * four-eyes waiver (F7) or a refund's release (F5a). Posted only by bnpl's operations; closed to
     * free adjustments.
     */
    BNPL_FEE_INCOME(OwnerKind.OPERATIONAL),

    /**
     * A BNPL obligation written off, per currency (`V027`; F11): EXPENSE, debit-normal. Seeded now so
     * the account plan never changes when Phase 14 builds the write-off; <strong>posted by nothing in
     * Phase 12</strong>, and closed to free adjustments so no person can post a loss here either.
     */
    BNPL_WRITE_OFF_EXPENSE(OwnerKind.OPERATIONAL),

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
     * `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` joined with their poster, the
     * four-eyes resolution (`P8-TSK-015`, `V017`): posted by nothing but approved
     * resolutions. `CASH_AT_BANK` joined with its one poster, the bank statement's recognition
     * (`P8-TSK-016`, `V018`): cash is never adjusted to fit (`INV-SET-06`). `FX_PROVIDER_CLEARING`
     * joined with its counterparty and source (`P9-TSK-011`, `V022`): every account of it is a
     * counterparty's own position, discharged only by that counterparty's evidence; so does
     * `CORRIDOR_CLEARING` (`P9-TSK-014`, `V024`), the corridor provider's. `LENDING_CAPITAL`
     * joined with the account (`P11-TSK-003`, `V026`; {@code INV-LND-13}) ahead of its one poster,
     * bank recognition of a contribution's statement line (`P11-TSK-004`): until then it holds no
     * line, and from then completeness reports any line on it that no recognition explains - the
     * every-writer half of the single-poster discipline {@code LendingBooksHaveOnePosterTest}
     * holds statically. The six per-loan purposes and the three loan P&amp;L purposes do NOT join:
     * lending opens no expectations, so completeness would report every lending line unattributed;
     * they are proven by the subledger proof instead (`P11-TSK-029`). Nor does any of the ten BNPL
     * purposes (`P12-TSK-003`, `V027`; ADR-0103 decision 1): bnpl opens no expectation either, so
     * {@code finapp.reconciliation.line.unattributed} - one series per member of this set - gains no
     * BNPL series; the BNPL book is proven by bnpl's subledger and three-way proofs (`P12-TSK-025`).
     */
    public static java.util.Set<AccountPurpose> reconciledPositions() {
        return java.util.EnumSet.of(
                SETTLEMENT_CLEARING,
                INSTANT_CLEARING,
                PAYOUT_CLEARING,
                CASH_AT_BANK,
                SUSPENSE_UNMATCHED,
                PROCESSING_COSTS,
                RECONCILIATION_LOSSES,
                RECONCILIATION_GAINS,
                FX_PROVIDER_CLEARING,
                CORRIDOR_CLEARING,
                LENDING_CAPITAL);
    }

    /** The reconciled positions as a SQL literal list, for `V015`'s binding trigger. */
    public static String sqlReconciledPositionsList() {
        return reconciledPositions().stream()
                .map(purpose -> "'" + purpose.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * Every purpose a free {@code MANUAL} adjustment may never touch (`P9-TSK-009`;
     * PHASE_9_PLAN.md section 12.6): the reconciled positions, and the FX books - the position,
     * the spread revenue, the rounding residual and the realised results - whose every line is a
     * conversion's or a cover's (`FX_REALISED_GAINS`/`_LOSSES` beside `V023`, `P9-TSK-012`). The FX books do NOT join {@link #reconciledPositions()}: they open
     * no expectations, and the completeness proof would report every conversion line unattributed.
     * `V020` re-states `V015`'s binding trigger with this list; `ReversalService` mirrors and
     * reconciliation-origin resolutions stay admitted.
     *
     * <p>The loan book joined (`P11-TSK-003`, `V026`; ADR-0096 section 1, {@code INV-LND-01}): the
     * six per-loan purposes and {@code LOAN_INTEREST_INCOME}, {@code LOAN_FEE_INCOME} and
     * {@code LOAN_WRITE_OFF_EXPENSE} - every line on them is a lending operation's, and lending's
     * own corrections (waivers, reversals, amendments) are four-eyes lending acts that post
     * through {@code PostingService} / {@code ReversalService}, never a ledger free adjustment.
     * {@code LENDING_CAPITAL} is closed through {@link #reconciledPositions()}.
     *
     * <p>The BNPL book joined (`P12-TSK-003`, `V027`; ADR-0103 decision 1, {@code INV-BNPL-01}): the
     * six per-obligation purposes and {@code BNPL_MERCHANT_FEE_INCOME}, {@code BNPL_INTEREST_INCOME},
     * {@code BNPL_FEE_INCOME} and {@code BNPL_WRITE_OFF_EXPENSE} - every line on them is a bnpl
     * operation's, and bnpl's own corrections (a fee waived, a repayment reversed) are four-eyes bnpl
     * acts through {@code ReversalService} (F7, F8), never a ledger free adjustment.
     */
    public static java.util.Set<AccountPurpose> closedToFreeAdjustments() {
        java.util.Set<AccountPurpose> closed = java.util.EnumSet.copyOf(reconciledPositions());
        closed.add(FX_POSITION);
        closed.add(FX_SPREAD_REVENUE);
        closed.add(ROUNDING_RESIDUAL);
        closed.add(FX_REALISED_GAINS);
        closed.add(FX_REALISED_LOSSES);
        closed.add(LOAN_PRINCIPAL);
        closed.add(LOAN_PRINCIPAL_DUE);
        closed.add(LOAN_INTEREST_ACCRUED);
        closed.add(LOAN_INTEREST_DUE);
        closed.add(LOAN_FEES_DUE);
        closed.add(LOAN_CREDIT_BALANCE);
        closed.add(LOAN_INTEREST_INCOME);
        closed.add(LOAN_FEE_INCOME);
        closed.add(LOAN_WRITE_OFF_EXPENSE);
        closed.add(BNPL_PRINCIPAL);
        closed.add(BNPL_PRINCIPAL_DUE);
        closed.add(BNPL_INTEREST_ACCRUED);
        closed.add(BNPL_INTEREST_DUE);
        closed.add(BNPL_FEES_DUE);
        closed.add(BNPL_CREDIT_BALANCE);
        closed.add(BNPL_MERCHANT_FEE_INCOME);
        closed.add(BNPL_INTEREST_INCOME);
        closed.add(BNPL_FEE_INCOME);
        closed.add(BNPL_WRITE_OFF_EXPENSE);
        return closed;
    }

    /** {@link #closedToFreeAdjustments()} as a SQL literal list, for the binding trigger. */
    public static String sqlClosedToFreeAdjustmentsList() {
        return closedToFreeAdjustments().stream()
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
