package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whose account a ledger account is (ADR-0040, ADR-0042).
 *
 * <p>The distinction is not decoration: <strong>mixing a customer's money and the platform's own
 * is how a shortfall becomes invisible</strong> ({@code LEDGER_MODEL.md} §1). Every customer
 * credit is a platform liability — both sides of a double entry are accounts, and a model with
 * only customer accounts cannot post anything.
 *
 * <p>The kind is <em>derived from the purpose</em> ({@link AccountPurpose#ownerKind()}) and
 * stored, the {@code normal_balance} pattern: a wallet is a customer's by definition and an FX
 * position the platform's, so letting a caller pick the pair independently only creates invalid
 * states for a {@code CHECK} to refuse.
 *
 * <p>Persisted values, in a generated {@code CHECK} ({@code LedgerAccountMigrationTest}
 * reconciles).
 */
public enum OwnerKind {

    /** Owned by a customer account (the product). {@code owner_ref} names it — required. */
    CUSTOMER,

    /**
     * Owned by a merchant — the platform's liability to a commercial counterparty
     * ({@code P6-TSK-003}, ADR-0050/0051). {@code owner_ref} names the merchant, by value and
     * opaque, exactly as {@code CUSTOMER} names the customer account: the ledger does not know
     * what a Merchant is ({@code ADR-0042}'s reasoning at the new boundary). Deliberately not
     * {@code CUSTOMER}: a merchant's money is owed <em>to a counterparty the platform pays
     * out to</em>, not held <em>for a person who spends it</em> — folding the two together is
     * how a payout draws on customer funds without anyone writing that sentence.
     */
    MERCHANT,

    /** The platform's own position: clearing, fees, FX, rounding. No owner, ever. */
    OPERATIONAL,

    /**
     * Value parked because it could not be attributed — the Phase 8 seam ({@code INV-REC-05}).
     * Deliberately not {@code OPERATIONAL}: suspense is a question awaiting an answer, not a
     * position the platform holds on purpose, and folding the two together is how parked value
     * stops being tracked and aged.
     */
    SUSPENSE,

    /**
     * Owned by a declared external counterparty - an FX provider, a corridor provider - whose
     * clearing position it is (`P9-TSK-010`, ADR-0078). {@code owner_ref} names the
     * counterparty's row in the ledger's own registry ({@code ledger.counterparty}, a trigger
     * holding the reference for every writer), never a code: a code does not fit a uuid, and
     * widening the column for one kind would weaken every other kind's reference. Deliberately
     * not {@code OPERATIONAL}: two providers on one purpose must never net ({@code INV-RAIL-04}),
     * and an account shared by the purpose is exactly where they would meet - so the account
     * itself says who owes.
     */
    COUNTERPARTY,

    /**
     * Owned by one loan or credit line - the platform's claims on a borrower and what it owes the
     * borrower back under that agreement (`P11-TSK-003`, ADR-0096 section 1; {@code INV-LND-01}).
     * {@code owner_ref} names the loan, by value and opaque, exactly as {@code CUSTOMER} names
     * the customer account: the ledger does not know what a Loan is, and no foreign key reaches
     * lending's schema (ADR-0029). Deliberately not {@code CUSTOMER}: a loan's receivables are
     * the <em>platform's</em> assets on a contract, not money held for a person who spends it -
     * folding the two together is how a principal outstanding would read as a wallet balance and
     * the borrower's debt as the borrower's money. One kind for both products (an instalment loan
     * and a revolving line): the product is the contract's, not the account's.
     */
    LOAN,

    /**
     * Owned by one BNPL customer repayment obligation - the platform's claims on the customer for a
     * financed purchase and what it owes the customer back under that obligation (`P12-TSK-003`,
     * ADR-0103 decision 1; {@code INV-BNPL-01}). {@code owner_ref} names the obligation, by value and
     * opaque, exactly as {@link #LOAN} names the loan: the ledger does not know what an obligation
     * is, and no foreign key reaches bnpl's schema (ADR-0029). Deliberately not {@link #LOAN}: a
     * BNPL obligation is another contract with another poster (bnpl, never lending - OQ-11), and one
     * kind for both would let a loan-book reader count a BNPL receivable without anyone writing that
     * sentence. Deliberately not {@link #CUSTOMER}, for the loan's reason: the receivables are the
     * platform's assets on a contract, not money held for the customer.
     */
    BNPL_OBLIGATION;

    /** True when an account of this kind names the owner it belongs to. */
    public boolean requiresOwnerRef() {
        return this == CUSTOMER
                || this == MERCHANT
                || this == COUNTERPARTY
                || this == LOAN
                || this == BNPL_OBLIGATION;
    }

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(kind -> "'" + kind.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The owner-ref presence rule as a SQL predicate, for the coherence {@code CHECK} — one
     * definition, two artefacts ({@link AccountPurpose#sqlOwnerKindRule()}'s pattern). Replaces
     * `V002`'s hand-written {@code (owner_kind = 'CUSTOMER') = (owner_ref IS NOT NULL)}, which
     * was correct while exactly one kind had an owner and became a generated rule the moment a
     * second did (`P6-TSK-003`) — the equality form keeps both defect directions caught: an
     * ownerless owned account, and an owner smuggled onto a platform account.
     */
    public static String sqlOwnerRefRule() {
        String owned =
                Arrays.stream(values())
                        .filter(OwnerKind::requiresOwnerRef)
                        .map(kind -> "'" + kind.name() + "'")
                        .collect(Collectors.joining(", "));
        return "(owner_kind IN (" + owned + ")) = (owner_ref IS NOT NULL)";
    }
}
