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
    SUSPENSE;

    /** True when an account of this kind names the owner it belongs to. */
    public boolean requiresOwnerRef() {
        return this == CUSTOMER || this == MERCHANT;
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
