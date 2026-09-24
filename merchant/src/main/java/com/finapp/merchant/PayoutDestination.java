package com.finapp.merchant;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where a merchant's payouts go — as a <strong>proposal flow, not a field</strong>
 * (`P6-TSK-011`, ADR-0056, {@code CHECKOUT_MERCHANT_LIFECYCLES.md} §6).
 *
 * <p>Every change is its own immutable row: an operator proposes, a <em>different</em> operator
 * approves ({@code INV-AUD-04}), the approval pins a cooling-off deadline, and only once it has
 * elapsed does the platform make the destination {@code EFFECTIVE}, superseding the previous one
 * in the same transaction. The row's identifier is therefore the destination's version, and a
 * payout that records it records exactly where it was sent.
 *
 * <p><strong>What this aggregate deliberately does not hold: bank details.</strong> It holds the
 * provider's opaque {@link PayoutDestinationReference} and a four-character display suffix; the
 * account itself exists only at the provider (ADR-0056 §7).
 *
 * <p><strong>One constructor holding the coherence</strong> (the {@link Merchant} idiom): each
 * state's facts present exactly when the state holds, the approver distinct from the proposer,
 * time ordered — and {@code effective} never before the cooling-off ends. {@code rehydrate}
 * applies the same checks, so a corrupt row is refused at read. `V006` states every one of them
 * again at {@code DB-CONSTRAINT} rank, for every writer that never ran this code.
 */
public final class PayoutDestination {

    /** The actor identifier bound, as `V006` states it (the {@code audit_record} actor model). */
    public static final int MAX_ACTOR_LENGTH = 200;

    /** The proposal reason's bound: the audit trail's own, which `V006` restates. */
    public static final int MAX_REASON_LENGTH = AuditRecord.MAX_REASON_LENGTH;

    /** A display suffix is the last four characters of the account identifier, never more. */
    public static final String DISPLAY_SUFFIX_REGEX = "[0-9A-Z]{4}";

    private static final Pattern DISPLAY_SUFFIX = Pattern.compile(DISPLAY_SUFFIX_REGEX);

    private final PayoutDestinationId id;
    private final MerchantId merchantId;
    private final PayoutDestinationReference reference;
    private final String displaySuffix;
    private final PayoutDestinationStatus status;
    private final String proposedBy;
    private final Instant proposedAt;
    private final String proposalReason;
    private final String approvedBy;
    private final Instant approvedAt;
    private final Instant coolingOffUntil;
    private final Instant effectiveAt;
    private final Instant supersededAt;
    private final String endedBy;
    private final Instant endedAt;

    private PayoutDestination(
            PayoutDestinationId id,
            MerchantId merchantId,
            PayoutDestinationReference reference,
            String displaySuffix,
            PayoutDestinationStatus status,
            String proposedBy,
            Instant proposedAt,
            String proposalReason,
            String approvedBy,
            Instant approvedAt,
            Instant coolingOffUntil,
            Instant effectiveAt,
            Instant supersededAt,
            String endedBy,
            Instant endedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.displaySuffix = validSuffix(displaySuffix);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.proposedBy = validActor(proposedBy, "proposedBy");
        this.proposedAt = Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        this.proposalReason = validReason(proposalReason);
        this.approvedBy = approvedBy == null ? null : validActor(approvedBy, "approvedBy");
        this.approvedAt = approvedAt;
        this.coolingOffUntil = coolingOffUntil;
        this.effectiveAt = effectiveAt;
        this.supersededAt = supersededAt;
        this.endedBy = endedBy == null ? null : validActor(endedBy, "endedBy");
        this.endedAt = endedAt;
        requireCoherent();
    }

    /** An operator proposes a destination: nothing pays to it until it is approved and due. */
    public static PayoutDestination propose(
            IdGenerator ids,
            Clock clock,
            MerchantId merchantId,
            PayoutDestinationReference reference,
            String displaySuffix,
            String proposedBy,
            String reason) {
        return new PayoutDestination(
                PayoutDestinationId.next(ids),
                merchantId,
                reference,
                displaySuffix,
                PayoutDestinationStatus.PROPOSED,
                proposedBy,
                Instant.now(clock),
                reason,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Reconstitutes from storage. Applies the coherence; a corrupt row is refused at read. */
    public static PayoutDestination rehydrate(
            PayoutDestinationId id,
            MerchantId merchantId,
            PayoutDestinationReference reference,
            String displaySuffix,
            PayoutDestinationStatus status,
            String proposedBy,
            Instant proposedAt,
            String proposalReason,
            Optional<String> approvedBy,
            Optional<Instant> approvedAt,
            Optional<Instant> coolingOffUntil,
            Optional<Instant> effectiveAt,
            Optional<Instant> supersededAt,
            Optional<String> endedBy,
            Optional<Instant> endedAt) {
        return new PayoutDestination(
                id,
                merchantId,
                reference,
                displaySuffix,
                status,
                proposedBy,
                proposedAt,
                proposalReason,
                approvedBy.orElse(null),
                approvedAt.orElse(null),
                coolingOffUntil.orElse(null),
                effectiveAt.orElse(null),
                supersededAt.orElse(null),
                endedBy.orElse(null),
                endedAt.orElse(null));
    }

    /**
     * A second operator approves: the cooling-off starts now and its deadline is pinned on the
     * row, so a later change to the configured period never alters an approved change.
     *
     * @throws PayoutDestinationSelfApprovalException the approver is the proposer
     *     ({@code INV-AUD-04})
     * @throws IllegalPayoutDestinationTransitionException the change is not {@code PROPOSED}
     */
    public PayoutDestination approve(String approver, Clock clock, Duration coolingOff) {
        Objects.requireNonNull(approver, "approver must not be null");
        Objects.requireNonNull(coolingOff, "coolingOff must not be null");
        if (coolingOff.isNegative() || coolingOff.isZero()) {
            throw new IllegalArgumentException("the cooling-off must be positive");
        }
        requireEdge(PayoutDestinationStatus.APPROVED);
        if (approver.equals(proposedBy)) {
            throw new PayoutDestinationSelfApprovalException();
        }
        Instant now = Instant.now(clock);
        return new PayoutDestination(
                id,
                merchantId,
                reference,
                displaySuffix,
                PayoutDestinationStatus.APPROVED,
                proposedBy,
                proposedAt,
                proposalReason,
                approver,
                now,
                now.plus(coolingOff),
                null,
                null,
                null,
                null);
    }

    /** The second pair of eyes says no. Terminal. */
    public PayoutDestination reject(String decider, Clock clock) {
        return ended(PayoutDestinationStatus.REJECTED, decider, clock);
    }

    /**
     * Withdrawn before it took effect — during the proposal, or during the cooling-off, which is
     * the edge that makes the cooling-off a control rather than a delay. Terminal.
     */
    public PayoutDestination withdraw(String actor, Clock clock) {
        return ended(PayoutDestinationStatus.WITHDRAWN, actor, clock);
    }

    /**
     * Whether the cooling-off has elapsed at {@code now}. The effectuation sweep re-judges this
     * on the locked row rather than trusting the list it read the candidate from.
     */
    public boolean isDue(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return status == PayoutDestinationStatus.APPROVED && !now.isBefore(coolingOffUntil);
    }

    /**
     * The platform makes the approved destination effective once its cooling-off has elapsed.
     *
     * @throws IllegalStateException the cooling-off is still running — `V006`'s
     *     {@code effective_at >= cooling_off_until} is the same rule for every other writer
     */
    public PayoutDestination effect(Clock clock) {
        requireEdge(PayoutDestinationStatus.EFFECTIVE);
        Instant now = Instant.now(clock);
        if (now.isBefore(coolingOffUntil)) {
            throw new IllegalStateException(
                    "a payout destination takes effect only once its cooling-off has elapsed");
        }
        return new PayoutDestination(
                id,
                merchantId,
                reference,
                displaySuffix,
                PayoutDestinationStatus.EFFECTIVE,
                proposedBy,
                proposedAt,
                proposalReason,
                approvedBy,
                approvedAt,
                coolingOffUntil,
                now,
                null,
                null,
                null);
    }

    /** A later destination took effect in this one's place, in the same transaction. Terminal. */
    public PayoutDestination supersede(Clock clock) {
        requireEdge(PayoutDestinationStatus.SUPERSEDED);
        Instant now = Instant.now(clock);
        return new PayoutDestination(
                id,
                merchantId,
                reference,
                displaySuffix,
                PayoutDestinationStatus.SUPERSEDED,
                proposedBy,
                proposedAt,
                proposalReason,
                approvedBy,
                approvedAt,
                coolingOffUntil,
                effectiveAt,
                // Never before it took effect, whatever the two instances' clocks said.
                now.isBefore(effectiveAt) ? effectiveAt : now,
                null,
                null);
    }

    private PayoutDestination ended(PayoutDestinationStatus to, String actor, Clock clock) {
        Objects.requireNonNull(actor, "actor must not be null");
        requireEdge(to);
        Instant now = Instant.now(clock);
        return new PayoutDestination(
                id,
                merchantId,
                reference,
                displaySuffix,
                to,
                proposedBy,
                proposedAt,
                proposalReason,
                approvedBy,
                approvedAt,
                coolingOffUntil,
                null,
                null,
                actor,
                now);
    }

    private void requireEdge(PayoutDestinationStatus to) {
        if (!status.canTransitionTo(to)) {
            throw new IllegalPayoutDestinationTransitionException(status, to);
        }
    }

    /** The same rules `V006` states as {@code CHECK}s, so what constructs here always stores. */
    private void requireCoherent() {
        boolean approvalRecorded = approvedBy != null;
        if (approvalRecorded != (approvedAt != null) || approvalRecorded != (coolingOffUntil != null)) {
            throw new IllegalArgumentException(
                    "an approval is its approver, instant and cooling-off deadline together");
        }
        boolean mustBeApproved =
                status == PayoutDestinationStatus.APPROVED
                        || status == PayoutDestinationStatus.EFFECTIVE
                        || status == PayoutDestinationStatus.SUPERSEDED;
        boolean mustNotBeApproved =
                status == PayoutDestinationStatus.PROPOSED
                        || status == PayoutDestinationStatus.REJECTED;
        if ((mustBeApproved && !approvalRecorded) || (mustNotBeApproved && approvalRecorded)) {
            throw new IllegalArgumentException(
                    "a " + status + " destination's approval facts do not match its status");
        }
        if (approvalRecorded && approvedBy.equals(proposedBy)) {
            // A stored self-approval is a corrupt row (V006 refuses writing one): refused at read.
            throw new IllegalArgumentException(
                    "a destination's approver must be distinct from its proposer (INV-AUD-04)");
        }
        boolean hasTakenEffect =
                status == PayoutDestinationStatus.EFFECTIVE
                        || status == PayoutDestinationStatus.SUPERSEDED;
        if (hasTakenEffect != (effectiveAt != null)) {
            throw new IllegalArgumentException(
                    "a destination has an effective instant exactly when it has taken effect");
        }
        if ((status == PayoutDestinationStatus.SUPERSEDED) != (supersededAt != null)) {
            throw new IllegalArgumentException(
                    "a destination has a superseded instant exactly when it is SUPERSEDED");
        }
        boolean hasEnded =
                status == PayoutDestinationStatus.REJECTED
                        || status == PayoutDestinationStatus.WITHDRAWN;
        if (hasEnded != (endedBy != null) || hasEnded != (endedAt != null)) {
            throw new IllegalArgumentException(
                    "a rejection or withdrawal is its actor and instant together");
        }
        if (approvedAt != null && approvedAt.isBefore(proposedAt)) {
            throw new IllegalArgumentException("an approval must not precede its proposal");
        }
        if (coolingOffUntil != null && !coolingOffUntil.isAfter(approvedAt)) {
            throw new IllegalArgumentException("a cooling-off must end after the approval");
        }
        if (effectiveAt != null && effectiveAt.isBefore(coolingOffUntil)) {
            throw new IllegalArgumentException(
                    "a destination must not take effect before its cooling-off has elapsed");
        }
        if (supersededAt != null && supersededAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException(
                    "a destination must not be superseded before it took effect");
        }
        if (endedAt != null && endedAt.isBefore(proposedAt)) {
            throw new IllegalArgumentException("a change must not end before it was proposed");
        }
    }

    private static String validSuffix(String value) {
        Objects.requireNonNull(value, "displaySuffix must not be null");
        if (!DISPLAY_SUFFIX.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "displaySuffix must be four characters of [0-9A-Z]");
        }
        return value;
    }

    private static String validActor(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isEmpty() || value.length() > MAX_ACTOR_LENGTH) {
            throw new IllegalArgumentException(
                    field + " must be 1.." + MAX_ACTOR_LENGTH + " characters");
        }
        return value;
    }

    private static String validReason(String value) {
        Objects.requireNonNull(value, "proposalReason must not be null");
        if (value.isBlank() || value.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "proposalReason must be 1.." + MAX_REASON_LENGTH + " characters");
        }
        return value;
    }

    public PayoutDestinationId id() {
        return id;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public PayoutDestinationReference reference() {
        return reference;
    }

    public String displaySuffix() {
        return displaySuffix;
    }

    public PayoutDestinationStatus status() {
        return status;
    }

    public String proposedBy() {
        return proposedBy;
    }

    public Instant proposedAt() {
        return proposedAt;
    }

    public String proposalReason() {
        return proposalReason;
    }

    public Optional<String> approvedBy() {
        return Optional.ofNullable(approvedBy);
    }

    public Optional<Instant> approvedAt() {
        return Optional.ofNullable(approvedAt);
    }

    public Optional<Instant> coolingOffUntil() {
        return Optional.ofNullable(coolingOffUntil);
    }

    public Optional<Instant> effectiveAt() {
        return Optional.ofNullable(effectiveAt);
    }

    public Optional<Instant> supersededAt() {
        return Optional.ofNullable(supersededAt);
    }

    public Optional<String> endedBy() {
        return Optional.ofNullable(endedBy);
    }

    public Optional<Instant> endedAt() {
        return Optional.ofNullable(endedAt);
    }
}
