package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The key-collision leg (`P8-TSK-010`, ADR-0069 §3's sweep row): a {@code DUPLICATE_INTERNAL}
 * break — subject the colliding expectation, value its own amount, never parked — for each
 * recorded {@code KEY_COLLISION} event without one. Callable here and idempotent through the
 * raise's partial unique, so ten legs racing over one record write one break; scheduled by
 * `P8-TSK-013`'s {@code ReconciliationSweepSchedule}.
 *
 * <p>An alias collision is recorded with no owning expectation (the first writer stands)
 * and raises nothing here — a break needs a subject that holds value. A collision recorded
 * after the expectation's earlier break was RESOLVED is suppressed by the any-status guard
 * below, not re-raised: the recurrence-after-resolution refinement is the sweep's
 * (`P8-TSK-013`), recorded at design.
 */
@RequiredArgsConstructor
public final class KeyCollisionBreaks {

    @NonNull private final BreakRegister register;
    @NonNull private final RuleSets ruleSets;
    @NonNull private final IdGenerator ids;

    /** Raises up to {@code limit} missing collision breaks; returns how many were created. */
    public int raiseFromRecordedCollisions(
            Connection unitOfWork, int limit, Actor actor, Instant at,
            CorrelationId correlation) {
        List<Colliding> candidates = candidates(unitOfWork, limit);
        int created = 0;
        for (Colliding candidate : candidates) {
            RuleSets.ActiveRuleSet ruleSet =
                    ruleSets.activeFor(unitOfWork, candidate.sourceId());
            BreakRegister.Raised raised =
                    register.raise(
                            unitOfWork,
                            new BreakRegister.NewBreak(
                                    ids.next(),
                                    BreakType.DUPLICATE_INTERNAL,
                                    BreakCause.KEY_COLLISION,
                                    BreakRegister.Subject.expectation(
                                            candidate.expectationId()),
                                    candidate.sourceId(),
                                    ruleSet.id(),
                                    candidate.amount(),
                                    Optional.of(candidate.direction()),
                                    Optional.of(candidate.kind()),
                                    // A question about our own records: there is nothing
                                    // external to look up (ADR-0069 section 2).
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    actor,
                                    at,
                                    correlation));
            if (raised.created()) {
                created++;
            }
        }
        return created;
    }

    private record Colliding(
            UUID expectationId,
            UUID sourceId,
            ExpectationKind kind,
            ExpectationDirection direction,
            Money amount) {}

    private List<Colliding> candidates(Connection unitOfWork, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT x.id, x.source_id, x.kind, x.direction,"
                                + " x.amount_minor, x.currency, x.scale"
                                + " FROM reconciliation.expectation_event ev"
                                + " JOIN reconciliation.expectation x ON x.id ="
                                + " ev.expectation_id"
                                + " WHERE ev.event_type = 'KEY_COLLISION'"
                                + " AND NOT EXISTS (SELECT 1 FROM reconciliation.break b"
                                + "     WHERE b.type = 'DUPLICATE_INTERNAL'"
                                + "     AND b.expectation_id = x.id)"
                                + " ORDER BY x.id"
                                + " LIMIT ?")) {
            read.setInt(1, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<Colliding> candidates = new ArrayList<>();
                while (rows.next()) {
                    candidates.add(
                            new Colliding(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("source_id", UUID.class),
                                    ExpectationKind.valueOf(rows.getString("kind")),
                                    ExpectationDirection.valueOf(
                                            rows.getString("direction")),
                                    Money.ofPersisted(
                                            rows.getLong("amount_minor"),
                                            CurrencyCode.of(
                                                    rows.getString("currency").trim()),
                                            rows.getInt("scale"))));
                }
                return List.copyOf(candidates);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the recorded collisions", failure);
        }
    }
}
