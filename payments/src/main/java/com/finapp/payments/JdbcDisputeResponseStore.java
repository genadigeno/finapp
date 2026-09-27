package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DisputeResponseStore} over explicit SQL (ADR-0033), on the caller's connection — the
 * {@code JdbcRefundStore} protocols at `V022`'s {@code payments.dispute_response}: the dispatch
 * key's convergence, the permit's conditional forward-only renewal, and the conditional
 * transition with its trail row.
 */
public final class JdbcDisputeResponseStore implements DisputeResponseStore<Connection> {

    private static final String TABLE = "payments.dispute_response";

    private static final String COLUMNS =
            "r.id, r.dispute_id, r.kind, r.status, r.failure_reason,"
                    + " r.provider_idempotency_reference, r.provider_reference, r.evidence_ids,"
                    + " r.requested_by_id, r.requested_by_type, r.reason, r.created_at";

    @Override
    public void insert(
            Connection unitOfWork,
            DisputeResponse response,
            String dispatchScope,
            String dispatchKey) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(dispatchScope, "dispatchScope must not be null");
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, dispute_id, kind, status, failure_reason,"
                                + " provider_idempotency_reference, provider_reference,"
                                + " evidence_ids, requested_by_id, requested_by_type, reason,"
                                + " dispatch_scope, dispatch_key, send_permit, created_at)"
                                + " VALUES (?, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, response.id().value());
            insert.setObject(2, response.dispute().value());
            insert.setString(3, response.kind().name());
            insert.setString(4, response.status().name());
            insert.setString(5, response.reference().value());
            insert.setArray(6, evidenceArray(unitOfWork, response.evidence()));
            insert.setString(7, response.requestedById());
            insert.setString(8, response.requestedByType());
            insert.setString(9, response.reason().orElse(null));
            insert.setString(10, dispatchScope);
            insert.setString(11, dispatchKey);
            // The birth permit is the birth: the first send is the dispatch's own.
            insert.setTimestamp(12, Timestamp.from(response.createdAt()));
            insert.setTimestamp(13, Timestamp.from(response.createdAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("dispatching a dispute response", failure));
        }
    }

    @Override
    public Optional<DisputeResponse> findByDispatchKey(
            Connection unitOfWork, String dispatchScope, String dispatchKey) {
        Objects.requireNonNull(dispatchScope, "dispatchScope must not be null");
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " r"
                                + " WHERE r.dispatch_scope = ? AND r.dispatch_key = ?")) {
            select.setString(1, dispatchScope);
            select.setString(2, dispatchKey);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("finding a dispute response by its key", failure));
        }
    }

    @Override
    public Optional<Locked> lockForOutcome(Connection unitOfWork, DisputeResponseId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + ", r.send_permit FROM " + TABLE + " r"
                                + " WHERE r.id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next()
                        ? Optional.of(
                                new Locked(map(row), row.getTimestamp("send_permit").toInstant()))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("locking dispute response " + id, failure));
        }
    }

    @Override
    public Optional<Instant> renewSendPermit(
            Connection unitOfWork, DisputeResponseId id, Instant at) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(at, "at must not be null");
        // The conditional IS the permit (the refund's renewal, V009): a response another resolver
        // moved to a terminal status leaves this matching no row, and then nothing may be sent.
        // GREATEST keeps the permit forward-only against an instance whose clock trails the one
        // that wrote the previous permit - V022's trigger refuses a step back.
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET send_permit = GREATEST(send_permit, CAST(? AS timestamptz))"
                                + " WHERE id = ? AND status IN ('DISPATCHED', 'UNKNOWN')"
                                + " RETURNING send_permit")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, id.value());
            try (ResultSet row = update.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getTimestamp(1).toInstant())
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "renewing dispute response " + id + "'s send permit", failure));
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork, DisputeResponse before, DisputeResponse after, Instant at) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET status = ?, failure_reason = ?, provider_reference = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, after.status().name());
            update.setString(2, after.failure().map(Enum::name).orElse(null));
            update.setString(3, after.providerReference().map(ProviderReference::value).orElse(null));
            update.setObject(4, after.id().value());
            update.setString(5, before.status().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "moving a dispute response " + before.status() + " -> "
                                    + after.status(),
                            failure));
        }
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.dispute_response_event"
                                + " (response_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, after.id().value());
            insert.setString(2, before.status().name());
            insert.setString(3, after.status().name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.executeUpdate();
            return true;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("appending a dispute response trail row", failure));
        }
    }

    @Override
    public Optional<DisputeResponse> findLive(Connection unitOfWork, DisputeId dispute) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " r"
                                + " WHERE r.dispute_id = ? AND r.status <> 'FAILED'")) {
            select.setObject(1, dispute.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a dispute's live response", failure));
        }
    }

    @Override
    public List<DisputeResponse> listFor(Connection unitOfWork, DisputeId dispute) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " r"
                                + " WHERE r.dispute_id = ? ORDER BY r.created_at, r.id")) {
            select.setObject(1, dispute.value());
            return all(select);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("listing a dispute's responses", failure));
        }
    }

    @Override
    public List<DisputeResponse> findSweepable(
            Connection unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit) {
        Objects.requireNonNull(dispatchedBefore, "dispatchedBefore must not be null");
        Objects.requireNonNull(unknownBefore, "unknownBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        // The refund sweep's clocks: a DISPATCHED response has waited since its latest send
        // permit (a takeover re-drive is a new send); an UNKNOWN one since the move that made it
        // UNKNOWN - the latest transition.
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " r"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.dispute_response_event e"
                                + "   WHERE e.response_id = r.id) h ON true"
                                + " WHERE (r.status = 'DISPATCHED' AND r.send_permit <= ?)"
                                + "    OR (r.status = 'UNKNOWN'"
                                + "        AND COALESCE(h.entered, r.created_at) <= ?)"
                                + " ORDER BY r.created_at, r.id"
                                + " LIMIT ?")) {
            select.setTimestamp(1, Timestamp.from(dispatchedBefore));
            select.setTimestamp(2, Timestamp.from(unknownBefore));
            select.setInt(3, limit);
            return all(select);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading sweepable dispute responses", failure));
        }
    }

    @Override
    public PaymentAttemptStore.UnknownReading unknownReading(
            Connection unitOfWork, java.time.Duration dispatchedBound) {
        Objects.requireNonNull(dispatchedBound, "dispatchedBound must not be null");
        // findSweepable's own clocks with the UNKNOWN bound at zero (the payout's reading, the
        // withdrawal's): an UNKNOWN answer has waited since the move that made it so, a
        // DISPATCHED one since its latest send permit, counted only once past the bound. The
        // SERVER's clock decides the age (ADR-0014), whole seconds, floored at zero.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*),"
                                + " GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM now() - min("
                                + "   CASE WHEN r.status = 'UNKNOWN'"
                                + "        THEN COALESCE(h.entered, r.created_at)"
                                + "        ELSE r.send_permit END)))::bigint, 0))"
                                + " FROM " + TABLE + " r"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.dispute_response_event e"
                                + "   WHERE e.response_id = r.id) h ON true"
                                + " WHERE r.status = 'UNKNOWN'"
                                + "    OR (r.status = 'DISPATCHED'"
                                + "        AND r.send_permit <= now() - make_interval(secs => ?))")) {
            read.setLong(1, dispatchedBound.toSeconds());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new PaymentAttemptStore.UnknownReading(row.getLong(1), row.getLong(2));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the stuck-dispute-response gauge", failure));
        }
    }

    // -----------------------------------------------------------------

    private static Array evidenceArray(Connection unitOfWork, List<DisputeEvidenceId> evidence)
            throws SQLException {
        return unitOfWork.createArrayOf(
                "uuid", evidence.stream().map(DisputeEvidenceId::value).toArray());
    }

    private static List<DisputeResponse> all(PreparedStatement select) throws SQLException {
        List<DisputeResponse> found = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                found.add(map(row));
            }
        }
        return List.copyOf(found);
    }

    private static DisputeResponse map(ResultSet row) throws SQLException {
        Object[] evidence = (Object[]) row.getArray("evidence_ids").getArray();
        String failure = row.getString("failure_reason");
        String providerReference = row.getString("provider_reference");
        return DisputeResponse.rehydrate(
                DisputeResponseId.of(row.getObject("id", UUID.class)),
                DisputeId.of(row.getObject("dispute_id", UUID.class)),
                DisputeResponseKind.valueOf(row.getString("kind")),
                DisputeResponseStatus.valueOf(row.getString("status")),
                Optional.ofNullable(failure).map(DisputeResponseFailure::valueOf),
                new ProviderIdempotencyReference(row.getString("provider_idempotency_reference")),
                Optional.ofNullable(providerReference).map(ProviderReference::new),
                Arrays.stream(evidence)
                        .map(value -> DisputeEvidenceId.of((UUID) value))
                        .toList(),
                row.getString("requested_by_id"),
                row.getString("requested_by_type"),
                Optional.ofNullable(row.getString("reason")),
                row.getTimestamp("created_at").toInstant());
    }
}
