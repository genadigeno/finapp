package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Plain-JDBC availability storage (`P9-TSK-007`, ADR-0033). */
@RequiredArgsConstructor
public final class JdbcAvailabilityStore implements AvailabilityStore {

    /** Advisory namespace 7 - reserved in DISTRIBUTED_EXECUTION.md for availability writers. */
    static final int NAMESPACE = 7;

    private static final String UNIQUE_VIOLATION = "23505";

    @NonNull private final IdGenerator ids;

    @Override
    public void lockSubject(Connection unitOfWork, AvailabilitySubject subject) {
        try (PreparedStatement lock =
                unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, NAMESPACE);
            lock.setString(2, subject.kind().name() + ":" + subject.subject());
            lock.execute();
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("locking an availability subject", failure), failure);
        }
    }

    @Override
    public boolean isAvailable(Connection unitOfWork, AvailabilitySubject subject) {
        String sql =
                subject.kind() == AvailabilitySubject.Kind.PAIR
                        ? "SELECT available FROM fx.pair_availability WHERE source_currency = ?"
                                + " AND destination_currency = ? ORDER BY seq DESC LIMIT 1"
                        : "SELECT available FROM fx.provider_availability WHERE provider_code = ?"
                                + " ORDER BY seq DESC LIMIT 1";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            bindSubject(select, subject);
            try (ResultSet row = select.executeQuery()) {
                return !row.next() || row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("reading availability", failure), failure);
        }
    }

    @Override
    public void insertFact(
            Connection unitOfWork,
            AvailabilitySubject subject,
            boolean available,
            String actorId,
            String reason,
            Instant at,
            Optional<UUID> enableRequestId) {
        String sql =
                subject.kind() == AvailabilitySubject.Kind.PAIR
                        ? "INSERT INTO fx.pair_availability (source_currency, destination_currency, id,"
                                + " available, actor_id, reason, recorded_at, enable_request_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        : "INSERT INTO fx.provider_availability (provider_code, id, available, actor_id,"
                                + " reason, recorded_at, enable_request_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            int next = bindSubject(insert, subject);
            insert.setObject(next, ids.next());
            insert.setBoolean(next + 1, available);
            insert.setString(next + 2, actorId);
            insert.setString(next + 3, reason);
            insert.setTimestamp(next + 4, Timestamp.from(at));
            insert.setObject(next + 5, enableRequestId.orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("recording availability", failure), failure);
        }
    }

    @Override
    public void insertRequest(
            Connection unitOfWork,
            UUID id,
            AvailabilitySubject subject,
            String proposedBy,
            String reason,
            Instant at) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.availability_enable_request (id, subject_kind, subject, status,"
                                + " proposed_by, proposed_at, proposal_reason)"
                                + " VALUES (?, ?, ?, 'PROPOSED', ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, subject.kind().name());
            insert.setString(3, subject.subject());
            insert.setString(4, proposedBy);
            insert.setTimestamp(5, Timestamp.from(at));
            insert.setString(6, reason);
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new FxAvailability.EnablePending(failure);
            }
            throw new FxStorageException(DatabaseFailure.describe("proposing an enabling", failure), failure);
        }
    }

    @Override
    public Optional<RequestRow> lockRequest(Connection unitOfWork, UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, subject_kind, subject, status, proposed_by, decided_by"
                                + " FROM fx.availability_enable_request WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new RequestRow(
                                row.getObject("id", UUID.class),
                                new AvailabilitySubject(
                                        AvailabilitySubject.Kind.valueOf(row.getString("subject_kind")),
                                        row.getString("subject")),
                                EnableRequestStatus.valueOf(row.getString("status")),
                                row.getString("proposed_by"),
                                Optional.ofNullable(row.getString("decided_by"))));
            }
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("locking an enable request", failure), failure);
        }
    }

    @Override
    public boolean decideRequest(
            Connection unitOfWork,
            UUID id,
            EnableRequestStatus to,
            String decidedBy,
            String reason,
            Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE fx.availability_enable_request SET status = ?, decided_by = ?,"
                                + " decided_at = ?, decision_reason = ? WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setTimestamp(3, Timestamp.from(at));
            update.setString(4, reason);
            update.setObject(5, id);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("deciding an enable request", failure), failure);
        }
    }

    /** Binds the subject's columns from parameter 1; returns the next parameter index. */
    private static int bindSubject(PreparedStatement statement, AvailabilitySubject subject) throws SQLException {
        if (subject.kind() == AvailabilitySubject.Kind.PAIR) {
            statement.setString(1, subject.source().code());
            statement.setString(2, subject.destination().code());
            return 3;
        }
        statement.setString(1, subject.subject());
        return 2;
    }
}
