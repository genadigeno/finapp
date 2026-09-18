package com.finapp.transfers;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The Transfer: one commanded movement of funds between two internal accounts
 * ({@code P4-TSK-003}, ADR-0044) — the judgement and its record, never the money itself. The
 * money is a ledger posting this aggregate holds the <em>evidence identifier</em> of
 * ({@link #journalEntryId()}), commanded through the ledger and written by nothing here
 * ({@code INV-LED-04}, ADR-0043).
 *
 * <p><strong>The customer and the actors are raw {@code UUID}s, deliberately.</strong>
 * {@code party} owns the typed {@code CustomerId} and {@code identity} the actor's, and this
 * module can see neither — the values arrive resolved from authoritative state by the execution
 * command's ports ({@code P4-TSK-005}), never from a request (the
 * {@code CustomerAccount.customerId} precedent). The account and entry references are typed
 * ({@link LedgerAccountId}, {@link JournalEntryId}) because the {@code transfers -> ledger} edge
 * exists for exactly this.
 *
 * <p><strong>One constructor holds every invariant, and every path shares it</strong> — birth,
 * transition and {@link #rehydrate}, so a corrupt row (a {@code FAILED} without its reason, a
 * {@code COMPLETED} without its entry) is refused on read-back as defence in depth ahead of
 * {@code V002}'s {@code CHECK}s ({@code P4-TSK-004}). The coherence rules are the machine's:
 * the reason exists exactly when {@code FAILED}; the entry exists exactly when money moved
 * ({@code COMPLETED}/{@code REVERSED}); the reversal triple exists exactly when {@code REVERSED};
 * and the account pair is unequal everywhere except the two shapes that mean something else —
 * {@code INITIATED} (the caller's unjudged input; judging is the execution's act) and
 * {@code FAILED(SELF_TRANSFER)} (the committed record of refusing exactly that mistake, the one
 * {@link FailureReason} whose row legitimately stores the equal pair). A consequence the
 * constructor enforces for free: an equal-pair {@code INITIATED} transfer has exactly one legal
 * exit, {@code fail(SELF_TRANSFER)}.
 *
 * <p><strong>Deliberately no {@code statusChangedAt}.</strong> The reversal is the only
 * post-insert transition ({@code INITIATED} is never durably observed — ADR-0043), its columns
 * are the narrowed {@code UPDATE} grant's ({@code PHASE_4_PLAN.md} §8), and transition instants
 * are the append-only history table's evidence, not this row's.
 *
 * <p>Transition methods are per-outcome because each carries a distinct payload; all route
 * through the same machine check ({@code INV-LIFE-02}'s one door). Production callers:
 * {@link #complete} and {@link #fail} are {@code P4-TSK-005}'s, {@link #reverse} is
 * {@code P4-TSK-009}'s — the {@code CustomerAccount.moveTo} precedent, shipped with the machine
 * and exercised by the exhaustive sweep until they arrive.
 */
public final class Transfer {

    /**
     * The reference's bound — `V002`'s {@code transfer_reference_is_bounded} literal, restated
     * here as the one definition the boundary DTO references directly (the
     * {@code Beneficiary.MAX_DISPLAY_NAME_LENGTH} idiom) and {@code TransferMigrationTest}
     * reconciles against the migration. Deliberately not enforced by this constructor: the
     * boundary owns the caller's 422 and the column owns every other writer's refusal.
     */
    public static final int MAX_REFERENCE_LENGTH = 200;

    private final TransferId id;
    private final UUID customerId;
    private final LedgerAccountId sourceAccount;
    private final LedgerAccountId destinationAccount;
    private final Money amount;
    private final String reference;
    private final TransferStatus status;
    private final FailureReason failureReason;
    private final JournalEntryId journalEntryId;
    private final JournalEntryId reversalEntryId;
    private final UUID reversedBy;
    private final Instant reversedAt;
    private final UUID initiatedBy;
    private final Instant initiatedAt;

    private Transfer(
            TransferId id,
            UUID customerId,
            LedgerAccountId sourceAccount,
            LedgerAccountId destinationAccount,
            Money amount,
            String reference,
            TransferStatus status,
            FailureReason failureReason,
            JournalEntryId journalEntryId,
            JournalEntryId reversalEntryId,
            UUID reversedBy,
            Instant reversedAt,
            UUID initiatedBy,
            Instant initiatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.sourceAccount =
                Objects.requireNonNull(sourceAccount, "sourceAccount must not be null");
        this.destinationAccount =
                Objects.requireNonNull(destinationAccount, "destinationAccount must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.reference = reference;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failureReason = failureReason;
        this.journalEntryId = journalEntryId;
        this.reversalEntryId = reversalEntryId;
        this.reversedBy = reversedBy;
        this.reversedAt = reversedAt;
        this.initiatedBy = Objects.requireNonNull(initiatedBy, "initiatedBy must not be null");
        this.initiatedAt = Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");

        // A zero amount moves nothing and a negative one is a credit wearing a debit's clothes
        // (P3-TSK-004's argument). Never a committed outcome — it is not in FailureReason — so
        // the boundary 422s it and this refusal is defence in depth. The message names the fact
        // and the currency, NEVER the value: it reaches logs, and amounts are
        // RESTRICTED-FINANCIAL (INV-AUD-02).
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "a transfer amount must be strictly positive; refused a non-positive amount in "
                            + amount.currency());
        }

        // The reason exists exactly when FAILED: a refusal without its reason is a support
        // ticket the platform caused (ADR-0044), and a reason on anything else is incoherent.
        if ((failureReason != null) != (status == TransferStatus.FAILED)) {
            throw new IllegalArgumentException(
                    "a transfer carries a failure reason exactly when FAILED; status "
                            + status + " with reason " + failureReason + " is incoherent");
        }

        // The entry exists exactly when money moved. REVERSED keeps the original entry: the
        // reversal is a NEW entry referencing it (INV-REV-01), never its removal.
        boolean moneyMoved =
                status == TransferStatus.COMPLETED || status == TransferStatus.REVERSED;
        if ((journalEntryId != null) != moneyMoved) {
            throw new IllegalArgumentException(
                    "a transfer carries its journal entry exactly when money moved; status "
                            + status + (journalEntryId == null
                                    ? " without an entry is incoherent"
                                    : " with an entry is incoherent"));
        }

        // The reversal triple exists exactly when REVERSED — all three or none, because a
        // reversal without its actor or instant is an unattributable correction (INV-AUD-01's
        // reasoning at the row).
        boolean reversed = status == TransferStatus.REVERSED;
        if ((reversalEntryId != null) != reversed
                || (reversedBy != null) != reversed
                || (reversedAt != null) != reversed) {
            throw new IllegalArgumentException(
                    "a transfer carries the reversal entry, actor and instant exactly when "
                            + "REVERSED; status " + status + " is incoherent with what it carries");
        }

        // The pair rule. Equal source and destination is legal in exactly two shapes: INITIATED
        // (unjudged input) and FAILED(SELF_TRANSFER) (the committed record of refusing exactly
        // that mistake). Everywhere else — money moved, or any other refusal — the equal pair is
        // a corrupt record: a completed self-transfer would mean the balanced no-op entry the
        // validation exists to prevent (PHASE_4_PLAN.md §14.6).
        boolean equalPair = sourceAccount.equals(destinationAccount);
        boolean selfTransferRecord =
                status == TransferStatus.FAILED && failureReason == FailureReason.SELF_TRANSFER;
        if (equalPair && status != TransferStatus.INITIATED && !selfTransferRecord) {
            throw new IllegalArgumentException(
                    "source and destination must differ; the equal pair is legal only while "
                            + "unjudged or as the committed SELF_TRANSFER refusal, not at "
                            + status);
        }
        if (selfTransferRecord && !equalPair) {
            throw new IllegalArgumentException(
                    "a SELF_TRANSFER refusal must record the equal account pair it refused; "
                            + "two different accounts are incoherent with that reason");
        }
    }

    /**
     * A new transfer, born {@code INITIATED} inside the execution transaction (ADR-0044). The
     * pair is the caller's unjudged input here — an equal pair is admitted and has exactly one
     * legal exit, {@code fail(SELF_TRANSFER)}.
     */
    public static Transfer initiate(
            IdGenerator ids,
            Clock clock,
            UUID customerId,
            LedgerAccountId sourceAccount,
            LedgerAccountId destinationAccount,
            Money amount,
            String reference,
            UUID initiatedBy) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new Transfer(
                TransferId.next(ids),
                customerId,
                sourceAccount,
                destinationAccount,
                amount,
                reference,
                TransferStatus.INITIATED,
                null,
                null,
                null,
                null,
                null,
                initiatedBy,
                Instant.now(clock));
    }

    /**
     * A row read back from storage, through the same constructor — so a corrupt row is refused
     * on read-back, ahead of the schema's own {@code CHECK}s.
     */
    public static Transfer rehydrate(
            TransferId id,
            UUID customerId,
            LedgerAccountId sourceAccount,
            LedgerAccountId destinationAccount,
            Money amount,
            String reference,
            TransferStatus status,
            FailureReason failureReason,
            JournalEntryId journalEntryId,
            JournalEntryId reversalEntryId,
            UUID reversedBy,
            Instant reversedAt,
            UUID initiatedBy,
            Instant initiatedAt) {
        return new Transfer(
                id,
                customerId,
                sourceAccount,
                destinationAccount,
                amount,
                reference,
                status,
                failureReason,
                journalEntryId,
                reversalEntryId,
                reversedBy,
                reversedAt,
                initiatedBy,
                initiatedAt);
    }

    /** The posting committed: this transfer {@code COMPLETED}, carrying its entry. */
    public Transfer complete(JournalEntryId entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        requireTransition(TransferStatus.COMPLETED);
        return new Transfer(
                id, customerId, sourceAccount, destinationAccount, amount, reference,
                TransferStatus.COMPLETED, null, entryId, null, null, null,
                initiatedBy, initiatedAt);
    }

    /** A committed domain refusal: this transfer {@code FAILED}, carrying its reason. */
    public Transfer fail(FailureReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        requireTransition(TransferStatus.FAILED);
        return new Transfer(
                id, customerId, sourceAccount, destinationAccount, amount, reference,
                TransferStatus.FAILED, reason, null, null, null, null,
                initiatedBy, initiatedAt);
    }

    /**
     * Reversed by its own command ({@code P4-TSK-009}): the new referencing entry, the actor and
     * the instant — the one transition that stamps this row, which is why it alone reads the
     * clock. The original entry is kept: money moved, and the reversal is more evidence, not
     * less.
     */
    public Transfer reverse(JournalEntryId reversalEntry, UUID actor, Clock clock) {
        Objects.requireNonNull(reversalEntry, "reversalEntry must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        requireTransition(TransferStatus.REVERSED);
        return new Transfer(
                id, customerId, sourceAccount, destinationAccount, amount, reference,
                TransferStatus.REVERSED, null, journalEntryId, reversalEntry, actor,
                Instant.now(clock), initiatedBy, initiatedAt);
    }

    /** The machine's one check ({@code INV-LIFE-02}), whichever door the transition arrives by. */
    private void requireTransition(TransferStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalTransferTransitionException(id, status, target);
        }
    }

    public TransferId id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public LedgerAccountId sourceAccount() {
        return sourceAccount;
    }

    public LedgerAccountId destinationAccount() {
        return destinationAccount;
    }

    public Money amount() {
        return amount;
    }

    /** The caller's memo. Nullable; bounded at the boundary and the column, not here. */
    public String reference() {
        return reference;
    }

    public TransferStatus status() {
        return status;
    }

    /** Present exactly when {@link #status()} is {@code FAILED}. */
    public FailureReason failureReason() {
        return failureReason;
    }

    /** Present exactly when money moved ({@code COMPLETED} or {@code REVERSED}). */
    public JournalEntryId journalEntryId() {
        return journalEntryId;
    }

    /** Present exactly when {@code REVERSED}, with {@link #reversedBy()} and {@link #reversedAt()}. */
    public JournalEntryId reversalEntryId() {
        return reversalEntryId;
    }

    public UUID reversedBy() {
        return reversedBy;
    }

    public Instant reversedAt() {
        return reversedAt;
    }

    public UUID initiatedBy() {
        return initiatedBy;
    }

    public Instant initiatedAt() {
        return initiatedAt;
    }
}
