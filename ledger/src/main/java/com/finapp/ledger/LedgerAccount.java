package com.finapp.ledger;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An accounting position in the chart of accounts (`P3-TSK-002`, ADR-0040, ADR-0042).
 *
 * <p><strong>Not a product a customer "has".</strong> A Customer Account is an agreement with a
 * lifecycle and no balance; this is the position recording its money — one per currency, so a
 * two-currency product holds two of these. {@link #ownerRef()} is an <em>opaque</em> reference:
 * the ledger knows an owner kind and a value, and does not know what a Customer Account is
 * (`PHASE_3_PLAN.md` §3).
 *
 * <h2>The classification is not writable, and there is no method to write it</h2>
 *
 * <p>{@code INV-LED-06}: type and normal balance never change once postings exist. The aggregate
 * enforces the stronger form structurally — <strong>no mutator exists at all</strong>, so the
 * question "may this caller reclassify?" has no code path to arise in. The schema carries the
 * conditional half for writers that never run this code: a trigger frees type and normal balance
 * only while no journal line references the row, and freezes the identity fields — purpose,
 * currency, owner — unconditionally, because those are facts about which account this
 * <em>is</em>. An unposted account with the wrong currency is corrected by closing it and
 * opening another, never by editing it.
 *
 * <h2>Two derivations, no free choices</h2>
 *
 * <p>{@link NormalBalance} is derived from the type and {@link OwnerKind} from the purpose, each
 * in one function, each stored, each held to its derivation by a generated {@code CHECK} — so a
 * caller cannot construct the pairs the model has no meaning for.
 *
 * <h2>Only owned accounts are constructed in code</h2>
 *
 * <p>The operational chart — clearing, fees, FX position, rounding residual, suspense — is
 * seeded <em>by migration</em> (`P3-TSK-003`): a reviewed platform artefact, the consent-text
 * reasoning. So the one factory is {@link #owned}, and {@code OPERATIONAL}/{@code SUSPENSE} rows
 * reach Java only through {@link #rehydrate}.
 */
public final class LedgerAccount {

    private final LedgerAccountId id;
    private final AccountType accountType;
    private final NormalBalance normalBalance;
    private final CurrencyCode currency;
    private final OwnerKind ownerKind;
    private final UUID ownerRef;
    private final AccountPurpose purpose;
    private final String glCode;
    private final LedgerAccountStatus status;
    private final Instant createdAt;
    private final Instant statusChangedAt;

    private LedgerAccount(
            LedgerAccountId id,
            AccountType accountType,
            NormalBalance normalBalance,
            CurrencyCode currency,
            OwnerKind ownerKind,
            UUID ownerRef,
            AccountPurpose purpose,
            String glCode,
            LedgerAccountStatus status,
            Instant createdAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.accountType = Objects.requireNonNull(accountType, "accountType must not be null");
        this.normalBalance =
                Objects.requireNonNull(normalBalance, "normalBalance must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.ownerKind = Objects.requireNonNull(ownerKind, "ownerKind must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
        this.glCode = glCode;
        // The owner-kind coherence rule, refused at construction and again by the schema
        // CHECK: a customer account with no owner is unattributable value, and a platform
        // account WITH one silently hands the platform's money a customer.
        if (ownerKind.requiresOwnerRef() != (ownerRef != null)) {
            throw new IllegalArgumentException(
                    ownerKind.requiresOwnerRef()
                            ? "a " + ownerKind + " account must name the owner it belongs to"
                            : "a " + ownerKind + " account is the platform's and has no owner");
        }
        if (purpose.ownerKind() != ownerKind) {
            throw new IllegalArgumentException(
                    "purpose " + purpose + " is a " + purpose.ownerKind()
                            + " purpose and cannot be held as " + ownerKind);
        }
        this.ownerRef = ownerRef;
    }

    /**
     * A customer-owned account: the position behind one product in one currency.
     *
     * <p>Takes the type, never the normal balance or the owner kind — both derive. The caller
     * (`P3-TSK-012`, opening a product) decides the type because the seed migration decides it
     * for operational accounts, and a wallet is a {@code LIABILITY}: the customer's money is
     * what the platform owes them.
     */
    public static LedgerAccount owned(
            IdGenerator ids,
            Clock clock,
            AccountType accountType,
            AccountPurpose purpose,
            CurrencyCode currency,
            UUID ownerRef) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(accountType, "accountType must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        Instant now = Instant.now(clock);
        return new LedgerAccount(
                LedgerAccountId.next(ids),
                accountType,
                accountType.normalBalance(),
                currency,
                purpose.ownerKind(),
                ownerRef,
                purpose,
                null,
                LedgerAccountStatus.ACTIVE,
                now,
                now);
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static LedgerAccount rehydrate(
            LedgerAccountId id,
            AccountType accountType,
            NormalBalance normalBalance,
            CurrencyCode currency,
            OwnerKind ownerKind,
            UUID ownerRef,
            AccountPurpose purpose,
            String glCode,
            LedgerAccountStatus status,
            Instant createdAt,
            Instant statusChangedAt) {
        return new LedgerAccount(
                id, accountType, normalBalance, currency, ownerKind, ownerRef, purpose, glCode,
                status, createdAt, statusChangedAt);
    }

    /** {@code ACTIVE → POSTING_SUSPENDED}: an operational freeze. Reversible. */
    public LedgerAccount suspendPostings(Clock clock) {
        return transitionTo(LedgerAccountStatus.POSTING_SUSPENDED, clock);
    }

    /** {@code POSTING_SUSPENDED → ACTIVE}: the freeze is lifted. */
    public LedgerAccount resumePostings(Clock clock) {
        return transitionTo(LedgerAccountStatus.ACTIVE, clock);
    }

    /**
     * {@code → CLOSED}. Terminal ({@code INV-LIFE-04}): no postings ever again, and every
     * posted row stays — closing ends the account's acceptance of postings, never its history
     * ({@code INV-HIST-01}). The zero-balance precondition is the closer's (`P3-TSK-014`):
     * whether the account MAY close is a question over its postings, which this aggregate
     * deliberately cannot see.
     */
    public LedgerAccount close(Clock clock) {
        return transitionTo(LedgerAccountStatus.CLOSED, clock);
    }

    private LedgerAccount transitionTo(LedgerAccountStatus target, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalLedgerAccountTransitionException(id, status, target);
        }
        return new LedgerAccount(
                id, accountType, normalBalance, currency, ownerKind, ownerRef, purpose, glCode,
                target, createdAt, Instant.now(clock));
    }

    public LedgerAccountId id() {
        return id;
    }

    public AccountType accountType() {
        return accountType;
    }

    /** Derived from the type at construction, stored, frozen once posted to. */
    public NormalBalance normalBalance() {
        return normalBalance;
    }

    /** Explicit, one per account ({@code INV-MON-02}, ADR-0040). */
    public CurrencyCode currency() {
        return currency;
    }

    public OwnerKind ownerKind() {
        return ownerKind;
    }

    /** The owning customer account, opaque to the ledger; empty for the platform's own. */
    public Optional<UUID> ownerRef() {
        return Optional.ofNullable(ownerRef);
    }

    public AccountPurpose purpose() {
        return purpose;
    }

    /** The Phase 14 GL seam. Nothing writes it in Phase 3 and this aggregate never will. */
    public Optional<String> glCode() {
        return Optional.ofNullable(glCode);
    }

    public LedgerAccountStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LedgerAccount account && id.equals(account.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiers and enumerated names only; an account row holds no amount to leak. */
    @Override
    public String toString() {
        return "LedgerAccount[" + id + ", " + accountType + "/" + normalBalance + ", "
                + currency + ", " + ownerKind + ", " + purpose + ", " + status + "]";
    }
}
