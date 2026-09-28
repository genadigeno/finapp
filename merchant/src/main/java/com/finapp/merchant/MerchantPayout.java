package com.finapp.merchant;

import com.finapp.ledger.HoldId;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One payout of a merchant's payable to its effective destination (`P6-TSK-012`, ADR-0051,
 * ADR-0057) — the first money the platform sends to an external party on its own initiative.
 *
 * <h2>What the row is, and what it is not</h2>
 *
 * <p>The amount, the destination version it was bound to, the hold that reserves it on the
 * payable, and our minted reference are fixed at birth: the dispatch transaction judged the
 * bound under the payable's lock and committed all four together. What the provider says moves
 * only {@link #status()}, the failure reason and the provider's reference — each exactly when
 * its state says so. The payout is <strong>not a balance</strong>: what it did to the payable is
 * the hold while it is in flight and the {@code merchant-payout:<payoutId>} posting once the
 * rail accepts it ({@code INV-MER-02}).
 *
 * <h2>The send permit</h2>
 *
 * <p>{@link #lastDispatchedAt()} is the one field that moves without a state change. Every send
 * of our reference is preceded by a committed permit — the first by the dispatch itself, every
 * re-send by a takeover's conditional renewal — and the resolution sweep may conclude
 * {@code NEVER_RECEIVED} only when the latest permit is older than its dispatched bound
 * ({@link #sendPermitAtOrBefore}). That ordering is what makes "the provider never received it"
 * a claim about the future as well as the past: no send can follow the conclusion (ADR-0057 §4).
 *
 * <h2>The constructor holds the coherence</h2>
 *
 * <p>Every rule `V007`'s {@code CHECK}s hold is also held here, so a corrupt row is refused at
 * read rather than acted on, and a transition cannot produce a state the schema would refuse.
 */
public final class MerchantPayout {

    /** Mirrors `V007`'s requester bound, which is the audit trail's actor bound. */
    public static final int MAX_ACTOR_LENGTH = 200;

    /** An operator's reason: the audit record's own bound, so a stored reason always audits. */
    public static final int MAX_REASON_LENGTH = AuditRecord.MAX_REASON_LENGTH;

    private final MerchantPayoutId id;
    private final MerchantId merchantId;
    private final Money amount;
    private final PayoutDestinationId destinationId;
    private final HoldId holdId;
    private final PayoutReference reference;
    private final MerchantPayoutStatus status;
    private final Optional<PayoutFailureReason> failureReason;
    private final Optional<PayoutProviderReference> providerReference;
    private final String requestedBy;
    private final ActorType requestedByType;
    private final Optional<String> reason;
    private final Instant createdAt;
    private final Instant lastDispatchedAt;

    private MerchantPayout(
            MerchantPayoutId id,
            MerchantId merchantId,
            Money amount,
            PayoutDestinationId destinationId,
            HoldId holdId,
            PayoutReference reference,
            MerchantPayoutStatus status,
            Optional<PayoutFailureReason> failureReason,
            Optional<PayoutProviderReference> providerReference,
            String requestedBy,
            ActorType requestedByType,
            Optional<String> reason,
            Instant createdAt,
            Instant lastDispatchedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.destinationId = Objects.requireNonNull(destinationId, "destinationId must not be null");
        this.holdId = Objects.requireNonNull(holdId, "holdId must not be null");
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failureReason = Objects.requireNonNull(failureReason, "failureReason must not be null");
        this.providerReference =
                Objects.requireNonNull(providerReference, "providerReference must not be null");
        this.requestedBy = bounded(requestedBy, "requestedBy", MAX_ACTOR_LENGTH);
        this.requestedByType =
                Objects.requireNonNull(requestedByType, "requestedByType must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        reason.ifPresent(why -> bounded(why, "reason", MAX_REASON_LENGTH));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.lastDispatchedAt =
                Objects.requireNonNull(lastDispatchedAt, "lastDispatchedAt must not be null");

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a payout amount must be positive");
        }
        // The merchant's own API-key request carries no reason; an operator acting on the
        // merchant's behalf always does (ADR-0057 §6) - the requester's type decides which.
        if ((requestedByType == ActorType.MERCHANT) == reason.isPresent()) {
            throw new IllegalArgumentException(
                    "a merchant's own payout carries no reason, and an operator's always does");
        }
        if ((status == MerchantPayoutStatus.FAILED) != failureReason.isPresent()) {
            throw new IllegalArgumentException("a failure reason is recorded exactly when FAILED");
        }
        if ((status == MerchantPayoutStatus.COMPLETED) != providerReference.isPresent()) {
            throw new IllegalArgumentException(
                    "the provider's reference arrives exactly when COMPLETED");
        }
        if (lastDispatchedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("a send permit cannot precede its payout");
        }
    }

    /**
     * The dispatch transaction's birth: judged, held, referenced, {@code DISPATCHED}. The first
     * send permit is the birth instant — the send follows this transaction's commit.
     */
    public static MerchantPayout dispatch(
            IdGenerator ids,
            Clock clock,
            MerchantId merchant,
            Money amount,
            PayoutDestinationId destination,
            HoldId hold,
            Actor requester,
            Optional<String> reason) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(requester, "requester must not be null");
        Instant now = Instant.now(clock);
        return new MerchantPayout(
                MerchantPayoutId.next(ids),
                merchant,
                amount,
                destination,
                hold,
                PayoutReference.mint(ids),
                MerchantPayoutStatus.DISPATCHED,
                Optional.empty(),
                Optional.empty(),
                requester.id(),
                requester.type(),
                reason,
                now,
                now);
    }

    /** A row read back from storage — the constructor's coherence refuses a corrupt one. */
    public static MerchantPayout rehydrate(
            MerchantPayoutId id,
            MerchantId merchantId,
            Money amount,
            PayoutDestinationId destinationId,
            HoldId holdId,
            PayoutReference reference,
            MerchantPayoutStatus status,
            Optional<PayoutFailureReason> failureReason,
            Optional<PayoutProviderReference> providerReference,
            String requestedBy,
            ActorType requestedByType,
            Optional<String> reason,
            Instant createdAt,
            Instant lastDispatchedAt) {
        return new MerchantPayout(
                id,
                merchantId,
                amount,
                destinationId,
                holdId,
                reference,
                status,
                failureReason,
                providerReference,
                requestedBy,
                requestedByType,
                reason,
                createdAt,
                lastDispatchedAt);
    }

    /** The rail accepted irrevocably. */
    public MerchantPayout complete(PayoutProviderReference theirs) {
        Objects.requireNonNull(theirs, "the provider's reference must not be null");
        requireEdge(MerchantPayoutStatus.COMPLETED);
        return moved(MerchantPayoutStatus.COMPLETED, Optional.empty(), Optional.of(theirs));
    }

    /** The rail refused, or never received it. */
    public MerchantPayout fail(PayoutFailureReason why) {
        Objects.requireNonNull(why, "why must not be null");
        requireEdge(MerchantPayoutStatus.FAILED);
        return moved(MerchantPayoutStatus.FAILED, Optional.of(why), Optional.empty());
    }

    /** The rail's answer was missing or ambiguous: the hold stands. */
    public MerchantPayout outcomeUnknown() {
        requireEdge(MerchantPayoutStatus.UNKNOWN);
        return moved(MerchantPayoutStatus.UNKNOWN, Optional.empty(), Optional.empty());
    }

    /**
     * A renewed send permit, committed before a takeover re-sends our reference. Only while
     * the payout still awaits the rail's word, and only forward in time.
     */
    public MerchantPayout withSendPermit(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (!status.isResolvable()) {
            throw new IllegalMerchantPayoutTransitionException(status, status);
        }
        if (at.isBefore(lastDispatchedAt)) {
            throw new IllegalArgumentException("a send permit only moves forward");
        }
        return new MerchantPayout(
                id,
                merchantId,
                amount,
                destinationId,
                holdId,
                reference,
                status,
                failureReason,
                providerReference,
                requestedBy,
                requestedByType,
                reason,
                createdAt,
                at);
    }

    /**
     * Whether the latest send permit is at or before {@code bound} — the condition under which
     * an unrecognised reference may be concluded {@code NEVER_RECEIVED}.
     */
    public boolean sendPermitAtOrBefore(Instant bound) {
        Objects.requireNonNull(bound, "bound must not be null");
        return !lastDispatchedAt.isAfter(bound);
    }

    private void requireEdge(MerchantPayoutStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalMerchantPayoutTransitionException(status, target);
        }
    }

    private MerchantPayout moved(
            MerchantPayoutStatus target,
            Optional<PayoutFailureReason> why,
            Optional<PayoutProviderReference> theirs) {
        return new MerchantPayout(
                id,
                merchantId,
                amount,
                destinationId,
                holdId,
                reference,
                target,
                why,
                theirs,
                requestedBy,
                requestedByType,
                reason,
                createdAt,
                lastDispatchedAt);
    }

    private static String bounded(String value, String what, int max) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(what + " must be 1-" + max + " characters");
        }
        return value;
    }

    public MerchantPayoutId id() {
        return id;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    public PayoutDestinationId destinationId() {
        return destinationId;
    }

    public HoldId holdId() {
        return holdId;
    }

    public PayoutReference reference() {
        return reference;
    }

    public MerchantPayoutStatus status() {
        return status;
    }

    public Optional<PayoutFailureReason> failureReason() {
        return failureReason;
    }

    public Optional<PayoutProviderReference> providerReference() {
        return providerReference;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public ActorType requestedByType() {
        return requestedByType;
    }

    public Optional<String> reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastDispatchedAt() {
        return lastDispatchedAt;
    }

    /** Identifiers and state only — never the amount, the reason or the destination. */
    @Override
    public String toString() {
        return "MerchantPayout[" + id + ", " + merchantId + ", " + status + "]";
    }
}
