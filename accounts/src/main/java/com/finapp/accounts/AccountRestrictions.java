package com.finapp.accounts;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer agreement's restriction (`P17-TSK-028`, ADR-0153): suspension and reinstatement by four eyes, and risk's two
 * actions through the same machine.
 *
 * <p><strong>Serialization.</strong> Every act takes the agreement {@code FOR UPDATE} - the row each new customer-initiated
 * outflow reads {@code FOR SHARE} in its own transaction (ADR-0153 decision 3) - so a movement racing a suspension commits
 * wholly before it or is refused after it, never half. The approval takes the restriction first, then the agreement; the
 * proposal and risk's path take only the agreement; an outflow only shares it. No act takes them in the other order, so no
 * cycle.
 *
 * <p><strong>What it never touches</strong>: a balance, a hold, a ledger account's status (ADR-0153 decisions 2 and 6). The
 * agreement's ledger accounts stay {@code ACTIVE}; credits and the completion of movements already in flight post as before.
 */
@RequiredArgsConstructor
public final class AccountRestrictions {

    static final String TARGET_TYPE = "customer_account";

    /** An approval or rejection: the restriction as decided, and the agreement's status after it. */
    public record Decided(AccountRestriction restriction, CustomerAccountStatus accountStatus) {}

    /** A restriction as its approver reads it (`P17-TSK-124`): the restriction, and the agreement's status now. */
    public record Read(AccountRestriction restriction, CustomerAccountStatus accountStatus) {}

    /** Risk's action, answered as risk's dispatch reads it (`ActionOutcome`'s three). */
    public enum RiskOutcome {
        /** The agreement moved. */
        DONE,
        /** It already held the status the action asks for. */
        ALREADY,
        /** Unknown, or in a status the action does not start from (closed, pending). */
        REFUSED
    }

    @NonNull private final CustomerAccountStore<Connection> accounts;
    @NonNull private final AccountRestrictionStore<Connection> restrictions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The restriction and its agreement's status, unlocked - what its approver reads before deciding (`P17-TSK-124`). Empty
     * for an unknown restriction. Nothing is written here: the caller records the serving in this unit of work.
     */
    public Optional<Read> read(Connection unitOfWork, AccountRestrictionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return restrictions.find(unitOfWork, id).map(restriction -> new Read(restriction, accounts.findById(unitOfWork,
                restriction.accountId()).orElseThrow(() -> new AccountsStorageException(
                        "a restriction names an agreement that does not exist")).status()));
    }

    /**
     * Proposes {@code kind} on the agreement, by the acting person - empty for an unknown agreement. Refused when the
     * agreement is not in the status the kind starts from, when it already has an open proposal, or for a reason code of the
     * other kind; nothing is written then.
     */
    public Optional<AccountRestriction> propose(Connection unitOfWork, CustomerAccountId accountId, RestrictionKind kind,
            RestrictionReason reasonCode, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<CustomerAccount> locked = accounts.lockById(unitOfWork, accountId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        if (restrictions.findOpenFor(unitOfWork, accountId).isPresent()) {
            throw new AccountRestrictionRefusedException(AccountRestrictionRefusedException.Reason.ALREADY_PROPOSED,
                    "the agreement already has an open restriction; decide it first");
        }
        AccountRestriction proposed = AccountRestriction.propose(ids, clock, locked.get(), kind, reasonCode, reason, actor,
                correlation.correlationId().value());
        restrictions.insert(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                AccountsAuditAction.RESTRICTION_PROPOSED, TARGET_TYPE, accountId.value().toString(),
                Optional.of(proposed.proposalReason()), AuditOutcome.SUCCEEDED, correlation.correlationId(),
                Optional.of("restriction=" + proposed.id().value() + ", kind=" + kind + ", reasonCode=" + reasonCode + ", customer="
                        + proposed.customerId())));
        return Optional.of(proposed);
    }

    /**
     * Approves the restriction: the agreement moves in this transaction. Empty for an unknown restriction. Refused when it is
     * decided already, when the approver proposed it, or when the agreement no longer holds the status the kind starts from
     * (a risk action or a close moved it meanwhile) - the proposal then stays open, for a rejection.
     */
    public Optional<Decided> approve(Connection unitOfWork, AccountRestrictionId id, String reason) {
        return decide(unitOfWork, id, RestrictionStatus.APPROVED, reason);
    }

    /** Rejects the restriction: nothing moves. Empty for an unknown restriction; refused as {@link #approve} but never for the agreement's status. */
    public Optional<Decided> reject(Connection unitOfWork, AccountRestrictionId id, String reason) {
        return decide(unitOfWork, id, RestrictionStatus.REJECTED, reason);
    }

    private Optional<Decided> decide(Connection unitOfWork, AccountRestrictionId id, RestrictionStatus outcome,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<AccountRestriction> locked = restrictions.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        AccountRestriction decided = locked.get().decide(outcome, actor, reason, clock, correlation.correlationId().value());
        AccountRestriction.Decision decision = decided.decision().orElseThrow();
        CustomerAccount account = accounts.lockById(unitOfWork, decided.accountId()).orElseThrow(
                () -> new AccountsStorageException("a restriction names an agreement that does not exist"));
        CustomerAccountStatus after = account.status();
        if (outcome == RestrictionStatus.APPROVED) {
            AccountRestriction.requireApplicable(account, decided.kind());
            move(unitOfWork, account, decided.kind(), decision.decidedAt());
            after = decided.kind().to();
        }
        if (!restrictions.decide(unitOfWork, decided)) {
            throw new AccountsStorageException("the restriction moved under its own FOR UPDATE - an invariant is already broken");
        }
        AccountsAuditAction action = outcome == RestrictionStatus.REJECTED
                ? AccountsAuditAction.RESTRICTION_REJECTED
                : decided.kind() == RestrictionKind.SUSPENSION
                        ? AccountsAuditAction.ACCOUNT_SUSPENDED
                        : AccountsAuditAction.ACCOUNT_REINSTATED;
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decision.decidedAt(), action, TARGET_TYPE,
                decided.accountId().value().toString(), Optional.of(decision.reason()), AuditOutcome.SUCCEEDED,
                correlation.correlationId(), Optional.of("restriction=" + decided.id().value() + ", kind=" + decided.kind()
                        + ", reasonCode=" + decided.reasonCode() + ", proposedBy=" + decided.proposedBy() + ", outcome="
                        + outcome)));
        return Optional.of(new Decided(decided, after));
    }

    /**
     * Risk's {@code SUSPEND_ACCOUNT} or {@code REINSTATE_ACCOUNT} (ADR-0153 decision 5): risk's case approval is already four
     * eyes, so the agreement moves directly, by the acting principal - the platform under the action driver's system scope -
     * with the action's reason code. Judged under the agreement's lock: the move ({@code DONE}), the status it asks for
     * already ({@code ALREADY}), or anything else ({@code REFUSED}). An open operator proposal is left as it is; its approval
     * is then refused as no longer applicable, for a rejection.
     */
    public RiskOutcome applyForRisk(Connection unitOfWork, CustomerAccountId accountId, RestrictionKind kind,
            String actionReason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(actionReason, "actionReason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<CustomerAccount> locked = accounts.lockById(unitOfWork, accountId);
        if (locked.isEmpty()) {
            return RiskOutcome.REFUSED;
        }
        if (locked.get().status() == kind.to()) {
            return RiskOutcome.ALREADY;
        }
        if (locked.get().status() != kind.from()) {
            return RiskOutcome.REFUSED;
        }
        Instant now = Instant.now(clock);
        move(unitOfWork, locked.get(), kind, now);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, now,
                kind == RestrictionKind.SUSPENSION ? AccountsAuditAction.ACCOUNT_SUSPENDED : AccountsAuditAction.ACCOUNT_REINSTATED,
                TARGET_TYPE, accountId.value().toString(), Optional.of(actionReason), AuditOutcome.SUCCEEDED,
                correlation.correlationId(), Optional.of("origin=risk-action, kind=" + kind + ", customer="
                        + locked.get().customerId())));
        return RiskOutcome.DONE;
    }

    private void move(Connection unitOfWork, CustomerAccount account, RestrictionKind kind, Instant at) {
        account.moveTo(kind.to(), clock);
        if (!accounts.moveStatus(unitOfWork, account.id(), kind.from(), kind.to(), at)) {
            throw new AccountsStorageException("the agreement moved under its own FOR UPDATE - an invariant is already broken");
        }
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current().orElseThrow(() -> new IllegalStateException(
                "a restriction act runs inside a correlation scope (the request's or the action driver's)"));
    }
}
