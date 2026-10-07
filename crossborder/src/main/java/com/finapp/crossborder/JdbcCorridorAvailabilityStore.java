package com.finapp.crossborder;

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

/** Plain-JDBC corridor availability storage (`P9-TSK-015`, ADR-0033). */
@RequiredArgsConstructor
public final class JdbcCorridorAvailabilityStore implements CorridorAvailabilityStore {

    /** Advisory namespace 8 - reserved in DISTRIBUTED_EXECUTION.md for corridor availability writers. */
    static final int NAMESPACE = 8;

    private static final String UNIQUE_VIOLATION = "23505";

    @NonNull private final IdGenerator ids;

    @Override
    public void lockCorridor(Connection unitOfWork, CorridorKey corridor) {
        try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, NAMESPACE);
            lock.setString(2, corridor.code());
            lock.execute();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("locking a corridor's availability", failure));
        }
    }

    @Override
    public boolean isAvailable(Connection unitOfWork, CorridorKey corridor) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT available FROM crossborder.corridor_availability WHERE corridor = ? ORDER BY seq DESC LIMIT 1")) {
            select.setString(1, corridor.code());
            try (ResultSet row = select.executeQuery()) {
                return !row.next() || row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("reading corridor availability", failure));
        }
    }

    @Override
    public void insertFact(
            Connection unitOfWork,
            CorridorKey corridor,
            boolean available,
            String actorId,
            String reason,
            Instant at,
            Optional<UUID> enableRequestId) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO crossborder.corridor_availability (id, corridor, available, actor_id, reason,"
                                + " recorded_at, enable_request_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, ids.next());
            insert.setString(2, corridor.code());
            insert.setBoolean(3, available);
            insert.setString(4, actorId);
            insert.setString(5, reason);
            insert.setTimestamp(6, Timestamp.from(at));
            insert.setObject(7, enableRequestId.orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording corridor availability", failure));
        }
    }

    @Override
    public void insertRequest(Connection unitOfWork, UUID id, CorridorKey corridor, String proposedBy, String reason, Instant at) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO crossborder.corridor_enable_request (id, corridor, status, proposed_by, proposed_at,"
                                + " proposal_reason) VALUES (?, ?, 'PROPOSED', ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, corridor.code());
            insert.setString(3, proposedBy);
            insert.setTimestamp(4, Timestamp.from(at));
            insert.setString(5, reason);
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new CorridorAvailability.EnablePending(failure);
            }
            throw new CrossborderStorageException(DatabaseFailure.describe("proposing a corridor enabling", failure));
        }
    }

    @Override
    public Optional<RequestRow> lockRequest(Connection unitOfWork, UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, corridor, status, proposed_by, decided_by FROM crossborder.corridor_enable_request"
                                + " WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RequestRow(
                        row.getObject("id", UUID.class),
                        CorridorKey.parse(row.getString("corridor")),
                        EnableRequestStatus.valueOf(row.getString("status")),
                        row.getString("proposed_by"),
                        Optional.ofNullable(row.getString("decided_by"))));
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("locking a corridor enable request", failure));
        }
    }

    @Override
    public boolean decideRequest(
            Connection unitOfWork, UUID id, EnableRequestStatus to, String decidedBy, String reason, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE crossborder.corridor_enable_request SET status = ?, decided_by = ?, decided_at = ?,"
                                + " decision_reason = ? WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setTimestamp(3, Timestamp.from(at));
            update.setString(4, reason);
            update.setObject(5, id);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("deciding a corridor enable request", failure));
        }
    }
}
