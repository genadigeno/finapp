package com.finapp.platform.inbox;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.PlatformAuditAction;
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

/**
 * {@link InboxParking} over {@code platform.inbox_parked_record} (`P15-TSK-028`).
 *
 * <p><strong>The upsert decides, and it decides once.</strong> The count, the elapsed floor and the transition are one statement on
 * the row, on the server's clock, so instances racing a redelivery can only add real failures to one count. {@code WHERE state =
 * 'FAILING'} on the conflict branch means a row another instance parked, handled or discarded is left alone, a terminal state never
 * regresses, and the {@code RETURNING} row, present only when this statement moved it, is the exactly-once signal for the record of it.
 */
public final class JdbcInboxParking implements InboxParking {

    private static final String TABLE = "platform.inbox_parked_record";

    static final String STANDING_SQL = "SELECT state FROM " + TABLE + " WHERE consumer = ? AND dedupe_key = ?";

    static final String HANDLED_SQL =
            "UPDATE " + TABLE + " SET state = 'HANDLED', settled_at = now() "
                    + "WHERE consumer = ? AND dedupe_key = ? AND state IN ('FAILING', 'PARKED')";

    /** {@code parked.attempts + 1}: this failure included. {@code now()} is the database's, the only clock N instances share. */
    private static final String BUDGET_SPENT =
            "parked.attempts + 1 >= ? AND now() - parked.first_failed_at >= (? * INTERVAL '1 millisecond')";

    static final String FAILURE_SQL =
            "INSERT INTO " + TABLE + " AS parked (park_id, consumer, dedupe_key, consumer_group, message_type, correlation_id, "
                    + "topic, partition_number, record_offset, state, attempts, last_failure, first_failed_at, last_failed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FAILING', 1, ?, now(), now()) "
                    + "ON CONFLICT (consumer, dedupe_key) DO UPDATE SET "
                    + "attempts = parked.attempts + 1, last_failure = EXCLUDED.last_failure, last_failed_at = now(), "
                    + "state = CASE WHEN " + BUDGET_SPENT + " THEN 'PARKED' ELSE 'FAILING' END, "
                    + "parked_at = CASE WHEN " + BUDGET_SPENT + " THEN now() ELSE NULL END "
                    + "WHERE parked.state = 'FAILING' "
                    + "RETURNING state, attempts";

    private final ParkingPolicy policy;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public JdbcInboxParking(ParkingPolicy policy, AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Standing standing(Connection unitOfWork, InboxKey key) {
        try (PreparedStatement select = unitOfWork.prepareStatement(STANDING_SQL)) {
            select.setString(1, key.consumer());
            select.setString(2, key.dedupeKey());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Standing.valueOf(row.getString("state")) : Standing.NONE;
            }
        } catch (SQLException e) {
            throw new InboxStorageException("Could not read a record's standing " + key, e);
        }
    }

    @Override
    public void handled(Connection unitOfWork, InboxKey key) {
        try (PreparedStatement update = unitOfWork.prepareStatement(HANDLED_SQL)) {
            update.setString(1, key.consumer());
            update.setString(2, key.dedupeKey());
            update.executeUpdate();
        } catch (SQLException e) {
            throw new InboxStorageException("Could not settle " + key, e);
        }
    }

    @Override
    public Verdict recordFailure(Connection unitOfWork, Failure failure, String failureClass) {
        Objects.requireNonNull(failure, "failure must not be null");
        CorrelationId correlation = failure.correlation().orElseGet(() -> CorrelationId.generate(ids));
        String bounded = failureClass.length() <= 200 ? failureClass : failureClass.substring(0, 200);
        try (PreparedStatement upsert = unitOfWork.prepareStatement(FAILURE_SQL)) {
            int at = 1;
            upsert.setObject(at++, ids.next());
            upsert.setString(at++, failure.key().consumer());
            upsert.setString(at++, failure.key().dedupeKey());
            upsert.setString(at++, failure.group());
            upsert.setString(at++, failure.messageType());
            upsert.setString(at++, correlation.value());
            upsert.setString(at++, failure.topic());
            upsert.setInt(at++, failure.partition());
            upsert.setLong(at++, failure.offset());
            upsert.setString(at++, bounded);
            upsert.setInt(at++, policy.attempts());
            upsert.setLong(at++, policy.failingFor().toMillis());
            upsert.setInt(at++, policy.attempts());
            upsert.setLong(at, policy.failingFor().toMillis());
            try (ResultSet moved = upsert.executeQuery()) {
                if (!moved.next()) {
                    return Verdict.ALREADY_SETTLED;
                }
                if (!"PARKED".equals(moved.getString("state"))) {
                    return Verdict.RETRY;
                }
                recordTheParking(unitOfWork, failure, correlation, bounded, moved.getInt("attempts"));
                return Verdict.PARKED;
            }
        } catch (SQLException e) {
            throw new InboxStorageException("Could not count a failure of " + failure.key(), e);
        }
    }

    /** In the transaction that parked it: no parked record without its audit record ({@code INV-AUD-01}). */
    @SuppressWarnings("try") // The system scope is used for its close side effect.
    private void recordTheParking(Connection unitOfWork, Failure failure, CorrelationId correlation, String failureClass, int attempts) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            audit.append(unitOfWork, new AuditRecord(
                    AuditId.next(ids),
                    SecurityContext.require(),
                    clock.instant(),
                    PlatformAuditAction.INBOX_RECORD_PARKED,
                    "inbox.record",
                    failure.key().consumer() + "/" + failure.key().dedupeKey(),
                    Optional.empty(),
                    AuditOutcome.SUCCEEDED,
                    correlation,
                    Optional.of("group=" + failure.group() + ", type=" + failure.messageType() + ", attempts=" + attempts
                            + ", failure=" + failureClass)));
        }
    }
}
