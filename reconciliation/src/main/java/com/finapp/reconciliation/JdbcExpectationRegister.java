package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link ExpectationRegister} over JDBC (ADR-0033: explicit SQL, no mapper).
 *
 * <p>Every insert is {@code ON CONFLICT DO NOTHING} (ADR-0067 §6): the two expectation
 * uniques converge on the standing row — the every-writer backstop that holds even with an
 * applier's acting guard removed — and a colliding key or alias is skipped and recorded as
 * an {@code expectation_event(KEY_COLLISION)}, never a failed payment. The opener takes no
 * lock on any reconciliation row and waits only on a concurrent, uncommitted inserter of the
 * same unique value (ADR-0067 §7).
 */
@RequiredArgsConstructor
public final class JdbcExpectationRegister implements ExpectationRegister {

    @NonNull private final IdGenerator ids;

    @Override
    public OpenResult open(Connection unitOfWork, NewExpectation expectation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(expectation, "expectation must not be null");
        UUID expectationId = ids.next();
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO reconciliation.expectation (id, kind, operation_ref,"
                                    + " posting_key, source_id, position_purpose,"
                                    + " ledger_account_id, direction, amount_minor, currency,"
                                    + " scale, journal_entry_id, posting_date,"
                                    + " settlement_cycle, expected_by, rule_set_id, status,"
                                    + " allocated_minor, resolved_minor, opened_at,"
                                    + " status_changed_at, correlation_id)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                    + " ?, 'OPEN', 0, 0, ?, ?, ?)"
                                    // Both identity uniques converge without a target list:
                                    // an earlier opener's row or the backfill's stands, for
                                    // any writer (ADR-0067 §6).
                                    + " ON CONFLICT DO NOTHING")) {
                insert.setObject(1, expectationId);
                insert.setString(2, expectation.kind().name());
                insert.setString(3, expectation.operationRef());
                insert.setString(4, expectation.postingKey());
                insert.setObject(5, expectation.sourceId());
                insert.setString(6, expectation.positionPurpose().name());
                insert.setObject(7, expectation.ledgerAccountId());
                insert.setString(8, expectation.direction().name());
                insert.setLong(9, expectation.amount().minorUnits());
                insert.setString(10, expectation.amount().currency().code());
                insert.setInt(11, expectation.amount().scale());
                insert.setObject(12, expectation.journalEntryId().orElse(null));
                insert.setObject(13, expectation.postingDate());
                insert.setString(14, expectation.settlementCycle().orElse(null));
                insert.setObject(15, expectation.expectedBy());
                insert.setObject(16, expectation.ruleSetId());
                insert.setTimestamp(17, Timestamp.from(expectation.openedAt()));
                insert.setTimestamp(18, Timestamp.from(expectation.openedAt()));
                insert.setString(19, expectation.correlation().value());
                if (insert.executeUpdate() == 0) {
                    // The winner registered the keys; writing them again would only record
                    // false collisions against our own standing row.
                    return OpenResult.CONVERGED;
                }
            }
            appendEvent(
                    unitOfWork,
                    expectationId,
                    "OPENED",
                    expectation.kind().name() + ":" + expectation.operationRef(),
                    expectation.openedBy(),
                    expectation.openedAt(),
                    expectation.correlation());
            for (NewExpectation.ExpectationKey key : expectation.keys()) {
                if (!insertKey(unitOfWork, expectation.sourceId(), key, expectationId)) {
                    // The key belongs to another expectation of this source: skipped, the
                    // payment completes, and the record is what P8-TSK-010's sweep raises
                    // DUPLICATE_INTERNAL from (ADR-0067 §6).
                    appendEvent(
                            unitOfWork,
                            expectationId,
                            "KEY_COLLISION",
                            key.kind().name() + ":" + key.value(),
                            expectation.openedBy(),
                            expectation.openedAt(),
                            expectation.correlation());
                }
            }
            return OpenResult.OPENED;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not open a settlement expectation", failure);
        }
    }

    private boolean insertKey(
            Connection unitOfWork,
            UUID sourceId,
            NewExpectation.ExpectationKey key,
            UUID expectationId)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.expectation_key (source_id, key_kind,"
                                + " key_value, expectation_id) VALUES (?, ?, ?, ?)"
                                + " ON CONFLICT DO NOTHING")) {
            insert.setObject(1, sourceId);
            insert.setString(2, key.kind().name());
            insert.setString(3, key.value());
            insert.setObject(4, expectationId);
            return insert.executeUpdate() == 1;
        }
    }

    @Override
    public void registerAlias(Connection unitOfWork, NewAlias alias) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(alias, "alias must not be null");
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO reconciliation.reference_alias (source_id, key_kind,"
                                    + " key_value, anchor_kind, anchor_value, registered_at,"
                                    + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?)"
                                    + " ON CONFLICT DO NOTHING")) {
                insert.setObject(1, alias.sourceId());
                insert.setString(2, alias.kind().name());
                insert.setString(3, alias.value());
                insert.setString(4, alias.anchorKind().name());
                insert.setString(5, alias.anchorValue());
                insert.setTimestamp(6, Timestamp.from(alias.registeredAt()));
                insert.setString(7, alias.correlation().value());
                if (insert.executeUpdate() == 1) {
                    return;
                }
            }
            // The first writer stands (the ARN in either order with its capture); the
            // collision is recorded with no owning expectation - the alias never got one.
            appendEvent(
                    unitOfWork,
                    null,
                    "KEY_COLLISION",
                    alias.kind().name() + ":" + alias.value(),
                    alias.registeredBy(),
                    alias.registeredAt(),
                    alias.correlation());
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not register a reference alias", failure);
        }
    }

    private void appendEvent(
            Connection unitOfWork,
            UUID expectationId,
            String eventType,
            String detail,
            Actor actor,
            Instant occurredAt,
            CorrelationId correlation)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.expectation_event (expectation_id,"
                                + " event_type, detail, actor, actor_type, occurred_at,"
                                + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, expectationId);
            insert.setString(2, eventType);
            insert.setString(3, detail);
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.setString(7, correlation.value());
            insert.executeUpdate();
        }
    }
}
