package com.finapp.accounts;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A proposal to suspend or reinstate a customer agreement, and its one decision (`P17-TSK-028`, ADR-0153 decision 4; the
 * owner's ruling of 2026-11-20, "Analyst proposes, officer approves").
 *
 * <p>Born {@code PROPOSED} by a holder of {@code ACCOUNT_RESTRICT}, on an agreement whose status the kind starts from; decided
 * once, {@code APPROVED} or {@code REJECTED}, by a holder of {@code ACCOUNT_RESTRICT_APPROVE} who is not the proposer
 * ({@code INV-AUD-04}: refused here and by V003's {@code CHECK} for every writer). A reason code of the kind's vocabulary and
 * a person's reason on each act, screened for card-number and bank-account shapes. The approval's move of the agreement is
 * {@link AccountRestrictions}'s, under the agreement's lock; this record only states what may be decided.
 */
public record AccountRestriction(
        AccountRestrictionId id,
        CustomerAccountId accountId,
        UUID customerId,
        RestrictionKind kind,
        RestrictionReason reasonCode,
        String proposalReason,
        String proposedBy,
        String proposedByType,
        Instant proposedAt,
        String proposalCorrelationId,
        RestrictionStatus status,
        Optional<Decision> decision) {

    /** The longest reason a person may write - the audit record's own bound. */
    public static final int REASON_BOUND = 1000;

    /** Who decided, when, why. */
    public record Decision(String decidedBy, String decidedByType, Instant decidedAt, String reason, String correlationId) {
        public Decision {
            Objects.requireNonNull(decidedBy, "decidedBy");
            Objects.requireNonNull(decidedByType, "decidedByType");
            Objects.requireNonNull(decidedAt, "decidedAt");
            reason = reasoned(reason);
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public AccountRestriction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.kind() != kind) {
            throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.REASON_INVALID,
                    reasonCode + " explains a " + reasonCode.kind() + ", not a " + kind);
        }
        proposalReason = reasoned(proposalReason);
        Objects.requireNonNull(proposedBy, "proposedBy");
        Objects.requireNonNull(proposedByType, "proposedByType");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(proposalCorrelationId, "proposalCorrelationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decision, "decision");
        if ((status == RestrictionStatus.PROPOSED) != decision.isEmpty()) {
            throw new IllegalArgumentException("a restriction carries a decision exactly when it is decided: " + status);
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(proposedBy)) {
            throw new IllegalArgumentException("a restriction is never decided by its proposer (INV-AUD-04)");
        }
    }

    /**
     * A proposal of {@code kind} on {@code account}, which must hold the status the kind starts from - a suspension of an
     * {@code ACTIVE} agreement, a reinstatement of a {@code SUSPENDED} one.
     */
    public static AccountRestriction propose(IdGenerator ids, Clock clock, CustomerAccount account, RestrictionKind kind,
            RestrictionReason reasonCode, String reason, Actor proposer, String correlationId) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(kind, "kind");
        requireApplicable(account, kind);
        return new AccountRestriction(AccountRestrictionId.next(ids), account.id(), account.customerId(), kind, reasonCode,
                reason, proposer.id(), proposer.type().name(), Instant.now(clock), correlationId, RestrictionStatus.PROPOSED,
                Optional.empty());
    }

    /**
     * This proposal decided {@code outcome} by {@code decider}: refused when it is decided already, or when the decider
     * proposed it ({@code INV-AUD-04}).
     */
    public AccountRestriction decide(RestrictionStatus outcome, Actor decider, String reason, Clock clock,
            String correlationId) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(decider, "decider");
        if (!status.canTransitionTo(outcome)) {
            throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.ALREADY_DECIDED,
                    "the restriction is " + status + "; it is decided once");
        }
        if (decider.id().equals(proposedBy)) {
            throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.SELF_DECISION,
                    "a restriction is decided by a person who did not propose it");
        }
        // Never before the proposal: the proposer's instance stamped proposedAt by its own clock, and this decider's instance
        // may run behind it - N instances, N clocks. The decision is later in fact, so it is never dated earlier (V003's
        // account_restriction_decided_after_proposed would refuse the approval otherwise).
        Instant now = Instant.now(clock);
        Instant decidedAt = now.isBefore(proposedAt) ? proposedAt : now;
        return new AccountRestriction(id, accountId, customerId, kind, reasonCode, proposalReason, proposedBy, proposedByType,
                proposedAt, proposalCorrelationId, outcome, Optional.of(new Decision(decider.id(), decider.type().name(),
                        decidedAt, reason, correlationId)));
    }

    /** Refuses {@code kind} on an agreement not in the status it starts from. */
    public static void requireApplicable(CustomerAccount account, RestrictionKind kind) {
        if (account.status() != kind.from()) {
            throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.NOT_APPLICABLE,
                    "a " + kind + " applies to a " + kind.from() + " agreement; this one is " + account.status());
        }
    }

    /** A person's reason: 1 to {@value #REASON_BOUND} characters, no card-number or bank-account shape. */
    static String reasoned(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > REASON_BOUND) {
            throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.REASON_INVALID,
                    "a reason of 1 to " + REASON_BOUND + " characters is required");
        }
        if (InstrumentShapes.holdsAny(reason)) {
            throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.REASON_INVALID,
                    "a reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }
}
