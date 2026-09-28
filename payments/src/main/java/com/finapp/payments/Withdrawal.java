package com.finapp.payments;

import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One withdrawal of a customer's wallet money to their external bank account
 * (`P7-TSK-008`, ADR-0062 §6) — the merchant payout's discipline (ADR-0057) transplanted to
 * the customer's wallet and the push rail, deliberately a transplant and not an abstraction
 * (ADR-0062 §7 keeps the payout its own port).
 *
 * <h2>What the row is, and what it is not</h2>
 *
 * <p>The amount, the wallet account, the instrument and its destination copy, our
 * end-to-end reference, the routed rail and the hold are fixed at birth: the dispatch
 * transaction judged the bound under the wallet account's lock ({@code INV-BAL-04}), pinned
 * the routing decision (ADR-0060 §2's outbound moment) and committed them together. What the
 * scheme says moves only {@link #status()}, the failure reason and the scheme's own
 * reference and cycle — each exactly when its state says so. The withdrawal is <strong>not a
 * balance</strong>: what it did to the wallet is the hold while in flight and the
 * {@code wallet-withdrawal:<id>} posting once the scheme accepts ({@code INV-RAIL-04}:
 * DEBIT the wallet, CREDIT {@code INSTANT_CLEARING} and never another rail's position).
 *
 * <h2>The destination rides the row, by design</h2>
 *
 * <p>{@link #destination()} is the platform's copy of the instrument's opaque reference,
 * re-wrapped across the PCI boundary at the one registered bridge — so a takeover's re-send
 * and the sweep's inquiry never reach into {@code paymentmethods}, and a later detach of the
 * instrument cannot strand an in-flight withdrawal ({@code INV-RAIL-03}'s classification
 * follows the copy: `V016` carries the same shape {@code CHECK}s and the register the same
 * {@code RESTRICTED-PII} row).
 *
 * <h2>The send permit</h2>
 *
 * <p>{@link #lastDispatchedAt()} is the one field that moves without a state change
 * (ADR-0057 §4, adopted by ADR-0062 §3): every send of our reference is preceded by a
 * committed permit — the first by the dispatch itself, every re-send by a takeover's
 * conditional renewal — and the sweep concludes {@code NEVER_RECEIVED} only when the latest
 * permit is older than the rail's <em>declared</em> outcome deadline plus the configured
 * margin, judged on the locked row. Never a clock alone.
 *
 * <h2>Irrevocable, structurally</h2>
 *
 * <p>{@code COMPLETED} has no outgoing edge and this aggregate has no reversal method: on a
 * rail declaring {@code FINAL_ON_ACCEPTANCE} with no reversal capability, {@code INV-REV-03}
 * is a property of the machine's shape, and the engine's capability gate refuses the request
 * before any transaction or wire call exists.
 */
public final class Withdrawal {

    private final WithdrawalId id;
    private final UUID partyId;
    private final UUID customerId;
    private final LedgerAccountId walletAccountId;
    private final UUID paymentMethodId;
    private final ProviderReference destination;
    private final Money amount;
    private final EndToEndReference reference;
    private final RailId railId;
    private final WithdrawalStatus status;
    private final Optional<WithdrawalFailureReason> failureReason;
    private final Optional<ProviderReference> schemeReference;
    private final Optional<String> settlementCycle;
    private final HoldId holdId;
    private final Instant createdAt;
    private final Instant lastDispatchedAt;

    private Withdrawal(
            WithdrawalId id,
            UUID partyId,
            UUID customerId,
            LedgerAccountId walletAccountId,
            UUID paymentMethodId,
            ProviderReference destination,
            Money amount,
            EndToEndReference reference,
            RailId railId,
            WithdrawalStatus status,
            Optional<WithdrawalFailureReason> failureReason,
            Optional<ProviderReference> schemeReference,
            Optional<String> settlementCycle,
            HoldId holdId,
            Instant createdAt,
            Instant lastDispatchedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.partyId = Objects.requireNonNull(partyId, "partyId must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.walletAccountId =
                Objects.requireNonNull(walletAccountId, "walletAccountId must not be null");
        this.paymentMethodId =
                Objects.requireNonNull(paymentMethodId, "paymentMethodId must not be null");
        this.destination = Objects.requireNonNull(destination, "destination must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.railId = Objects.requireNonNull(railId, "railId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failureReason =
                Objects.requireNonNull(failureReason, "failureReason must not be null");
        this.schemeReference =
                Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        this.settlementCycle =
                Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
        this.holdId = Objects.requireNonNull(holdId, "holdId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.lastDispatchedAt =
                Objects.requireNonNull(lastDispatchedAt, "lastDispatchedAt must not be null");

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a withdrawal amount must be positive");
        }
        // Every rule V016's CHECKs hold is held here too, so a corrupt row is refused at
        // read rather than acted on (the MerchantPayout constructor's stance).
        if ((status == WithdrawalStatus.FAILED) != failureReason.isPresent()) {
            throw new IllegalArgumentException("a failure reason is recorded exactly when FAILED");
        }
        if ((status == WithdrawalStatus.COMPLETED) != schemeReference.isPresent()) {
            throw new IllegalArgumentException(
                    "the scheme's reference arrives exactly when COMPLETED");
        }
        if (settlementCycle.isPresent() && status != WithdrawalStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "a settlement cycle rides only an accepted withdrawal");
        }
        if (settlementCycle.isPresent()
                && (settlementCycle.get().isBlank()
                        || settlementCycle.get().length() > PushAnswer.MAX_CYCLE_LENGTH)) {
            throw new IllegalArgumentException(
                    "a settlement cycle is 1-" + PushAnswer.MAX_CYCLE_LENGTH + " characters");
        }
        if (lastDispatchedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("a send permit cannot precede its withdrawal");
        }
    }

    /**
     * The dispatch transaction's birth: judged under the wallet's lock, held, routed,
     * referenced, {@code DISPATCHED}. The first send permit is the birth instant — the send
     * follows this transaction's commit (ADR-0046).
     */
    public static Withdrawal dispatch(
            IdGenerator ids,
            Clock clock,
            UUID partyId,
            UUID customerId,
            LedgerAccountId walletAccountId,
            UUID paymentMethodId,
            ProviderReference destination,
            Money amount,
            RailId railId,
            HoldId hold) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        // Truncated to the schema's own microsecond resolution (the P7-TSK-004 clock
        // lesson): the permit is COMPARED for identity by the first-send rule, and a
        // nanosecond the column cannot hold would make the row disagree with the flight
        // that wrote it.
        Instant now = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new Withdrawal(
                WithdrawalId.next(ids),
                partyId,
                customerId,
                walletAccountId,
                paymentMethodId,
                destination,
                amount,
                // Our end-to-end reference, minted before anything is sent (INV-PAY-04):
                // a UUIDv7's 32 hex characters, inside ISO 20022's own 35-character bound.
                new EndToEndReference(ids.next().toString().replace("-", "")),
                railId,
                WithdrawalStatus.DISPATCHED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                hold,
                now,
                now);
    }

    /** A row read back from storage — the constructor's coherence refuses a corrupt one. */
    public static Withdrawal rehydrate(
            WithdrawalId id,
            UUID partyId,
            UUID customerId,
            LedgerAccountId walletAccountId,
            UUID paymentMethodId,
            ProviderReference destination,
            Money amount,
            EndToEndReference reference,
            RailId railId,
            WithdrawalStatus status,
            Optional<WithdrawalFailureReason> failureReason,
            Optional<ProviderReference> schemeReference,
            Optional<String> settlementCycle,
            HoldId holdId,
            Instant createdAt,
            Instant lastDispatchedAt) {
        return new Withdrawal(
                id,
                partyId,
                customerId,
                walletAccountId,
                paymentMethodId,
                destination,
                amount,
                reference,
                railId,
                status,
                failureReason,
                schemeReference,
                settlementCycle,
                holdId,
                createdAt,
                lastDispatchedAt);
    }

    /** The scheme accepted — irrevocably; its reference and cycle are Phase 8's keys. */
    public Withdrawal complete(ProviderReference theirs, Optional<String> cycle) {
        Objects.requireNonNull(theirs, "the scheme's reference must not be null");
        Objects.requireNonNull(cycle, "cycle must not be null");
        requireEdge(WithdrawalStatus.COMPLETED);
        return moved(WithdrawalStatus.COMPLETED, Optional.empty(), Optional.of(theirs), cycle);
    }

    /** The scheme refused, or provably never received it. */
    public Withdrawal fail(WithdrawalFailureReason why) {
        Objects.requireNonNull(why, "why must not be null");
        requireEdge(WithdrawalStatus.FAILED);
        return moved(WithdrawalStatus.FAILED, Optional.of(why), Optional.empty(), Optional.empty());
    }

    /** The scheme's answer was missing or ambiguous: the hold stands. */
    public Withdrawal outcomeUnknown() {
        requireEdge(WithdrawalStatus.UNKNOWN);
        return moved(WithdrawalStatus.UNKNOWN, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * A renewed send permit, committed before a takeover re-sends our reference — only while
     * the withdrawal still awaits the scheme's word, and only forward in time (ADR-0057 §4).
     */
    public Withdrawal withSendPermit(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (!status.isResolvable()) {
            throw new IllegalWithdrawalTransitionException(status, status);
        }
        Instant granted = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (granted.isBefore(lastDispatchedAt)) {
            throw new IllegalArgumentException("a send permit only moves forward");
        }
        return new Withdrawal(
                id,
                partyId,
                customerId,
                walletAccountId,
                paymentMethodId,
                destination,
                amount,
                reference,
                railId,
                status,
                failureReason,
                schemeReference,
                settlementCycle,
                holdId,
                createdAt,
                granted);
    }

    /**
     * Whether the latest send permit is at or before {@code bound} — the condition under
     * which the scheme's explicit {@code UNRECOGNISED} may be concluded
     * {@code NEVER_RECEIVED} (ADR-0062 §3: the declared deadline plus margin, never a clock
     * alone).
     */
    public boolean sendPermitAtOrBefore(Instant bound) {
        Objects.requireNonNull(bound, "bound must not be null");
        return !lastDispatchedAt.isAfter(bound);
    }

    private void requireEdge(WithdrawalStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalWithdrawalTransitionException(status, target);
        }
    }

    private Withdrawal moved(
            WithdrawalStatus target,
            Optional<WithdrawalFailureReason> why,
            Optional<ProviderReference> theirs,
            Optional<String> cycle) {
        return new Withdrawal(
                id,
                partyId,
                customerId,
                walletAccountId,
                paymentMethodId,
                destination,
                amount,
                reference,
                railId,
                target,
                why,
                theirs,
                cycle,
                holdId,
                createdAt,
                lastDispatchedAt);
    }

    public WithdrawalId id() {
        return id;
    }

    public UUID partyId() {
        return partyId;
    }

    public UUID customerId() {
        return customerId;
    }

    public LedgerAccountId walletAccountId() {
        return walletAccountId;
    }

    public UUID paymentMethodId() {
        return paymentMethodId;
    }

    public ProviderReference destination() {
        return destination;
    }

    public Money amount() {
        return amount;
    }

    public EndToEndReference reference() {
        return reference;
    }

    public RailId railId() {
        return railId;
    }

    public WithdrawalStatus status() {
        return status;
    }

    public Optional<WithdrawalFailureReason> failureReason() {
        return failureReason;
    }

    public Optional<ProviderReference> schemeReference() {
        return schemeReference;
    }

    public Optional<String> settlementCycle() {
        return settlementCycle;
    }

    public HoldId holdId() {
        return holdId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastDispatchedAt() {
        return lastDispatchedAt;
    }

    /** Identifiers and state only — never the amount, never a reference (INV-AUD-02). */
    @Override
    public String toString() {
        return "Withdrawal[" + id + ", " + status + "]";
    }
}
