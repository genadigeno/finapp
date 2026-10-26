package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RescreeningPermitStore} over {@code risk.rescreening_permit} ({@code risk V017}; `P13-TSK-022`): explicit SQL on
 * the caller's connection (ADR-0033); the enqueue {@code ON CONFLICT DO NOTHING} on the permit's unique; the claim one
 * statement, {@code FOR UPDATE SKIP LOCKED} on a permit that has come, the permit a lease ahead and the attempt counted in
 * the same statement; every conclusion conditional on {@code PENDING} and the claimed attempt. Each read joins the
 * permit's version for its list and activation instant. Risk's own tables only ({@code INV-RSK-02}); a failure is named
 * by its operation and SQLSTATE only.
 */
public final class JdbcRescreeningPermitStore implements RescreeningPermitStore {

    static final String COLUMNS = "p.id, p.party_id, p.list_version_id, v.list, v.activated_at, p.status, p.outcome,"
            + " p.cause, p.signal_id, p.next_attempt_at, p.attempts, p.correlation_id";

    /** Born PENDING, due at once (the trigger stamps both); the party's one permit per version the arbiter. */
    static final String ENQUEUE = "INSERT INTO risk.rescreening_permit (id, party_id, list_version_id, status, attempts,"
            + " next_attempt_at, enqueued_at, correlation_id)"
            + " VALUES (?, ?, ?, 'PENDING', 0, clock_timestamp(), clock_timestamp(), ?)"
            + " ON CONFLICT (party_id, list_version_id) DO NOTHING";

    /** The schedule's claim: the oldest due permit, SKIP LOCKED (R9), a lease ahead, one attempt counted. */
    static final String CLAIM_NEXT_DUE = "WITH claimed AS (UPDATE risk.rescreening_permit"
            + " SET attempts = attempts + 1, next_attempt_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = (SELECT id FROM risk.rescreening_permit WHERE status = 'PENDING'"
            + " AND next_attempt_at <= clock_timestamp() ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)"
            + " RETURNING *)"
            + " SELECT " + COLUMNS + " FROM claimed p JOIN risk.screening_list_version v ON v.id = p.list_version_id";

    static final String FIND = "SELECT " + COLUMNS + " FROM risk.rescreening_permit p"
            + " JOIN risk.screening_list_version v ON v.id = p.list_version_id WHERE p.id = ?";

    /** (R9) the permit row alone FOR UPDATE - its version is read, never locked. */
    static final String LOCK = FIND + " FOR UPDATE OF p";

    /** PENDING -> DONE, once, at the claimed attempt; concluded_at the trigger's. */
    static final String CONCLUDE = "UPDATE risk.rescreening_permit SET status = 'DONE', outcome = ?, signal_id = ?,"
            + " cause = NULL, next_attempt_at = NULL WHERE id = ? AND status = 'PENDING' AND attempts = ?";

    /** PENDING -> FAILED, once, at the claimed attempt. */
    static final String FAIL = "UPDATE risk.rescreening_permit SET status = 'FAILED', cause = ?, next_attempt_at = NULL"
            + " WHERE id = ? AND status = 'PENDING' AND attempts = ?";

    /** PENDING -> PENDING, re-permitted backoff ahead, at the claimed attempt. */
    static final String DEFER = "UPDATE risk.rescreening_permit SET cause = ?,"
            + " next_attempt_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = ? AND status = 'PENDING' AND attempts = ?";

    /** The backlog, saturating at the cap - the gauge's read, on the due index's predicate. */
    static final String PENDING = "SELECT count(*) FROM (SELECT 1 FROM risk.rescreening_permit WHERE status = 'PENDING'"
            + " LIMIT ?) AS backlog";

    @Override
    public boolean enqueue(Connection unitOfWork, NewPermit permit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(permit, "permit");
        try (PreparedStatement enqueue = unitOfWork.prepareStatement(ENQUEUE)) {
            enqueue.setObject(1, permit.id().value());
            enqueue.setObject(2, permit.partyId());
            enqueue.setObject(3, permit.listVersionId().value());
            enqueue.setString(4, permit.correlationId());
            return enqueue.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("enqueuing a rescreening permit", failure);
        }
    }

    @Override
    public Optional<RescreeningPermit> claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(lease, "lease");
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM_NEXT_DUE)) {
            claim.setLong(1, lease.toMillis());
            return single(claim);
        } catch (SQLException failure) {
            throw failed("claiming a due rescreening permit", failure);
        }
    }

    @Override
    public Optional<RescreeningPermit> lock(Connection unitOfWork, RescreeningPermitId id) {
        return byId(unitOfWork, id, LOCK, "locking a rescreening permit");
    }

    @Override
    public Optional<RescreeningPermit> find(Connection unitOfWork, RescreeningPermitId id) {
        return byId(unitOfWork, id, FIND, "reading a rescreening permit");
    }

    @Override
    public boolean conclude(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Outcome outcome,
            Optional<RiskSignalId> signal) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(signal, "signal");
        try (PreparedStatement conclude = unitOfWork.prepareStatement(CONCLUDE)) {
            conclude.setString(1, outcome.name());
            if (signal.isPresent()) {
                conclude.setObject(2, signal.get().value());
            } else {
                conclude.setNull(2, Types.OTHER);
            }
            conclude.setObject(3, id.value());
            conclude.setInt(4, attempt);
            return conclude.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("concluding a rescreening permit", failure);
        }
    }

    @Override
    public boolean fail(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Cause cause) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        try (PreparedStatement fail = unitOfWork.prepareStatement(FAIL)) {
            fail.setString(1, cause.name());
            fail.setObject(2, id.value());
            fail.setInt(3, attempt);
            return fail.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("failing a rescreening permit", failure);
        }
    }

    @Override
    public boolean defer(Connection unitOfWork, RescreeningPermitId id, int attempt, RescreeningPermit.Cause cause,
            Duration backoff) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(backoff, "backoff");
        try (PreparedStatement defer = unitOfWork.prepareStatement(DEFER)) {
            defer.setString(1, cause.name());
            defer.setLong(2, backoff.toMillis());
            defer.setObject(3, id.value());
            defer.setInt(4, attempt);
            return defer.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deferring a rescreening permit", failure);
        }
    }

    @Override
    public long pending(Connection unitOfWork, long cap) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        if (cap < 1) {
            throw new IllegalArgumentException("the backlog's cap is positive");
        }
        try (PreparedStatement count = unitOfWork.prepareStatement(PENDING)) {
            count.setLong(1, cap);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw failed("counting the pending rescreening permits", failure);
        }
    }

    // -----------------------------------------------------------------

    private static Optional<RescreeningPermit> byId(Connection unitOfWork, RescreeningPermitId id, String sql,
            String operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id.value());
            return single(read);
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<RescreeningPermit> single(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            return row.next() ? Optional.of(read(row)) : Optional.empty();
        }
    }

    static RescreeningPermit read(ResultSet row) throws SQLException {
        Timestamp next = row.getTimestamp("next_attempt_at");
        String outcome = row.getString("outcome");
        String cause = row.getString("cause");
        UUID signal = row.getObject("signal_id", UUID.class);
        return new RescreeningPermit(
                RescreeningPermitId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                ScreeningListVersionId.of(row.getObject("list_version_id", UUID.class)),
                ScreeningList.valueOf(row.getString("list")),
                row.getTimestamp("activated_at").toInstant(),
                RescreeningPermit.Status.valueOf(row.getString("status")),
                Optional.ofNullable(outcome).map(RescreeningPermit.Outcome::valueOf),
                Optional.ofNullable(cause).map(RescreeningPermit.Cause::valueOf),
                Optional.ofNullable(signal).map(RiskSignalId::of),
                next == null ? Optional.empty() : Optional.of(next.toInstant()),
                row.getInt("attempts"),
                row.getString("correlation_id"));
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
