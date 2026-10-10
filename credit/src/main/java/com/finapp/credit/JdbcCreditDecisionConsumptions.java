package com.finapp.credit;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link CreditDecisionConsumptions} over {@code credit_decision_consumption} through {@code credit.consume_decision}
 * (`P11-TSK-001`, credit {@code V021}; ADR-0091). Stateless; on the caller's unit of work.
 *
 * <ol>
 *   <li>the decision's party, read plain - absent, {@link Consumption.NotFound}, nothing locked;
 *   <li><strong>the party's profile {@code FOR UPDATE}</strong> through {@link CreditProfiles#lockForDecision} - lock-order
 *       element (1), first, exactly as a deciding transaction takes it;
 *   <li>the decision re-read under the lock: not an approval, {@link Consumption.NotApproved} - the domain's own
 *       judgement, from a fact that never changes ({@code INV-CRD-02});
 *   <li>{@code credit.consume_decision} - which re-takes the lock (re-entrant), re-judges the outcome, finds an existing
 *       consumption (the same consumer's: a replay; another's: {@link Consumption.AlreadyConsumed}), judges the lapse on
 *       the database clock read under the lock, and only then inserts. <strong>The lapse is the database's judgement
 *       alone</strong>: no instance clock - each may be seconds off - decides whether an approval still reserves;
 *   <li>the winner alone audits {@code credit.DecisionConsumed}; every answer is reported to the observer, which counts
 *       after the commit.
 * </ol>
 *
 * <p>The actor and the correlation are resolved first, so a missing scope fails before any lock or write.
 */
@RequiredArgsConstructor
public final class JdbcCreditDecisionConsumptions implements CreditDecisionConsumptions<Connection> {

    static final String TARGET_TYPE = "credit_decision";

    @NonNull private final CreditProfiles<Connection> profiles;
    @NonNull private final CreditDecisions<Connection> decisions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final ConsumptionObserver observer;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    @Override
    public Consumption consume(Connection unitOfWork, CreditDecisionId decision, ConsumerRef consumer) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(consumer, "consumer");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a consumption runs inside a correlation scope: its audit"
                        + " record carries the flow's identifier"))
                .correlationId();
        Consumption answer = consumeWithin(unitOfWork, decision, consumer, actor, correlation);
        observer.answered(ConsumptionObserver.Outcome.of(answer));
        return answer;
    }

    private Consumption consumeWithin(
            Connection uow, CreditDecisionId decision, ConsumerRef consumer, Actor actor, CorrelationId correlation) {
        Optional<UUID> party = partyOf(uow, decision);
        if (party.isEmpty()) {
            return new Consumption.NotFound(decision);
        }
        if (profiles.lockForDecision(uow, party.get()).isEmpty()) {
            throw new IllegalStateException("a decision's party has a credit profile (credit_decision_profile_fk)");
        }
        CreditDecision recorded = decisions.byId(uow, decision)
                .orElseThrow(() -> new IllegalStateException("a decision read once is never deleted (INV-CRD-02)"));
        if (recorded.outcome() != DecisionOutcome.APPROVED) {
            return new Consumption.NotApproved(decision);
        }
        CreditDecisionConsumptionId id = CreditDecisionConsumptionId.next(ids);
        try (PreparedStatement consume = uow.prepareStatement("SELECT answer, consumption_id, decision_valid_until,"
                + " held_by_kind, held_by_ref FROM credit.consume_decision(?, ?, ?, ?)")) {
            consume.setObject(1, decision.value());
            consume.setObject(2, id.value());
            consume.setString(3, consumer.kind().name());
            consume.setObject(4, consumer.ref());
            try (ResultSet row = consume.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("credit.consume_decision answers exactly one row");
                }
                String answer = row.getString("answer");
                return switch (answer) {
                    case "CONSUMED" -> {
                        audited(uow, recorded, consumer, actor, correlation);
                        yield new Consumption.Consumed(id, recorded, false);
                    }
                    case "REPLAYED" -> new Consumption.Consumed(
                            CreditDecisionConsumptionId.of(row.getObject("consumption_id", UUID.class)), recorded, true);
                    case "ALREADY_CONSUMED" -> new Consumption.AlreadyConsumed(decision, new ConsumerRef(
                            ConsumerKind.valueOf(row.getString("held_by_kind")), row.getObject("held_by_ref", UUID.class)));
                    case "LAPSED" -> new Consumption.Lapsed(decision, row.getTimestamp("decision_valid_until").toInstant());
                    case "NOT_APPROVED" -> throw new IllegalStateException(
                            "the database found the approval read under the same lock not approved");
                    case "NOT_FOUND" -> throw new IllegalStateException(
                            "the database found no decision where one was read under the same lock");
                    default -> throw new IllegalStateException("credit.consume_decision answered " + answer);
                };
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("consuming a credit decision", failure));
        }
    }

    private static Optional<UUID> partyOf(Connection uow, CreditDecisionId decision) {
        try (PreparedStatement select = uow.prepareStatement("SELECT party_id FROM credit.credit_decision WHERE id = ?")) {
            select.setObject(1, decision.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a credit decision's party", failure));
        }
    }

    /** {@code credit.DecisionConsumed} - the decision and the consumer, never an amount. */
    private void audited(Connection uow, CreditDecision decision, ConsumerRef consumer, Actor actor,
            CorrelationId correlation) {
        audit.append(uow, new AuditRecord(
                AuditId.next(ids),
                actor,
                clock.instant(),
                CreditAuditAction.DECISION_CONSUMED,
                TARGET_TYPE,
                decision.id().value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("decision " + decision.id().value() + " consumed by " + consumer.kind().name() + " "
                        + consumer.ref())));
    }
}
