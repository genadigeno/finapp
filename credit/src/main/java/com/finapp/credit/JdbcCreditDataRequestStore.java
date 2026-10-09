package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CreditDataRequestStore} over credit {@code V004} (`P10-TSK-006`).
 *
 * <p>Stateless. Every permit is assigned as {@code statement_timestamp() + retry_cadence} - the cadence stamped on
 * the row at birth - and nothing is bound into it; requested-at, the deadline and the first permit are the trigger's.
 * Failures carry no driver exception: {@code DatabaseFailure.describe} keeps the SQLState and drops the row.
 */
public final class JdbcCreditDataRequestStore implements CreditDataRequestStore {

    private static final String TABLE = "credit.data_request";
    private static final String COLUMNS = "id, decision_request_id, party_id, product, source_kind, provider_code,"
            + " request_reference, status, attempts, next_attempt_at, requested_at, deadline_at, unavailable_reported";

    @Override
    public void insertRequested(Connection unitOfWork, NewRequest request) {
        Objects.requireNonNull(request, "request");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + TABLE + " (id, decision_request_id, party_id, product, source_kind, provider_code,"
                        + " request_reference, status, attempts, retry_cadence, collection_window, unavailable_reported)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 0, ? * interval '1 millisecond',"
                        + " ? * interval '1 millisecond', false)")) {
            insert.setObject(1, request.id().value());
            insert.setObject(2, request.decisionRequestId());
            insert.setObject(3, request.partyId());
            insert.setString(4, request.product().name());
            insert.setString(5, request.kind().name());
            insert.setString(6, request.providerCode());
            insert.setString(7, request.reference());
            insert.setLong(8, request.retryCadence().toMillis());
            insert.setLong(9, request.collectionWindow().toMillis());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("opening a credit data request", failure));
        }
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, CreditDataRequestId id) {
        return byId(unitOfWork, id, "");
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, CreditDataRequestId id) {
        return byId(unitOfWork, id, " FOR UPDATE");
    }

    @Override
    public boolean receive(Connection unitOfWork, CreditDataRequestId id, int attempts) {
        return transition(unitOfWork, "UPDATE " + TABLE + " SET status = 'RECEIVED', attempts = ?"
                + " WHERE id = ? AND status = 'REQUESTED'", attempts, id, "recording a credit data request received");
    }

    @Override
    public boolean markUnavailable(Connection unitOfWork, CreditDataRequestId id, int attempts) {
        return transition(unitOfWork, "UPDATE " + TABLE + " SET status = 'UNAVAILABLE', attempts = ?,"
                + " next_attempt_at = statement_timestamp() + retry_cadence WHERE id = ? AND status = 'REQUESTED'",
                attempts, id, "recording a credit data request unavailable");
    }

    @Override
    public boolean withdraw(Connection unitOfWork, CreditDataRequestId id, CreditDataRequestStatus from, int attempts) {
        if (from != CreditDataRequestStatus.REQUESTED && from != CreditDataRequestStatus.UNAVAILABLE) {
            throw new IllegalArgumentException("only an unanswered data request can find consent withdrawn");
        }
        return transition(unitOfWork, "UPDATE " + TABLE + " SET status = 'CONSENT_WITHDRAWN', attempts = ?"
                + " WHERE id = ? AND status = '" + from.name() + "'", attempts, id,
                "recording a credit data request's consent withdrawn");
    }

    @Override
    public void insertAttempt(Connection unitOfWork, CreditDataRequestId id, int attempt, String outcome) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO credit.data_request_attempt (data_request_id, attempt, outcome, answered_at)"
                        + " VALUES (?, ?, ?, statement_timestamp())")) {
            insert.setObject(1, id.value());
            insert.setInt(2, attempt);
            insert.setString(3, outcome);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording a credit data attempt", failure));
        }
    }

    @Override
    public void insertRecord(
            Connection unitOfWork,
            CreditRecordId id,
            Row request,
            String providerCode,
            int normaliserVersion,
            boolean complete,
            java.time.Instant retrievedAt,
            List<CreditAttribute> attributes) {
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.credit_record (id, data_request_id, party_id, source_kind, provider_code,"
                            + " normaliser_version, complete, retrieved_at, recorded_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp())")) {
                insert.setObject(1, id.value());
                insert.setObject(2, request.id().value());
                insert.setObject(3, request.partyId());
                insert.setString(4, request.kind().name());
                insert.setString(5, providerCode);
                insert.setInt(6, normaliserVersion);
                insert.setBoolean(7, complete);
                insert.setTimestamp(8, java.sql.Timestamp.from(retrievedAt));
                insert.executeUpdate();
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.credit_record_attribute (record_id, code, value_type, integer_value,"
                            + " money_minor, money_currency, money_scale, boolean_value, code_value, absent)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (CreditAttribute attribute : attributes) {
                    insert.setObject(1, id.value());
                    insert.setString(2, attribute.code().name());
                    insert.setString(3, attribute.code().valueType().name());
                    insert.setNull(4, Types.BIGINT);
                    insert.setNull(5, Types.BIGINT);
                    insert.setNull(6, Types.VARCHAR);
                    insert.setNull(7, Types.INTEGER);
                    insert.setNull(8, Types.BOOLEAN);
                    insert.setNull(9, Types.VARCHAR);
                    insert.setBoolean(10, attribute.absent());
                    switch (attribute.value()) {
                        case AttributeValue.IntegerValue integer -> insert.setLong(4, integer.value());
                        case AttributeValue.MoneyValue money -> {
                            insert.setLong(5, money.value().minorUnits());
                            insert.setString(6, money.value().currency().code());
                            insert.setInt(7, money.value().scale());
                        }
                        case AttributeValue.BooleanValue bool -> insert.setBoolean(8, bool.value());
                        case AttributeValue.CodeValue code -> insert.setString(9, code.value());
                        case AttributeValue.Absent absent -> { }
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording a credit record", failure));
        }
    }

    @Override
    public void insertEvidence(Connection unitOfWork, NewEvidence evidence) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO credit.credit_evidence (id, data_request_id, attempt, duplicate, consent_withdrawn,"
                        + " content_ciphertext, content_nonce, key_version, checksum_sha256, content_length,"
                        + " retention_months) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, evidence.id().value());
            insert.setObject(2, evidence.dataRequest().value());
            insert.setInt(3, evidence.attempt());
            insert.setBoolean(4, evidence.duplicate());
            insert.setBoolean(5, evidence.consentWithdrawn());
            if (evidence.sealed().isPresent()) {
                CreditEvidenceCipher.Encrypted sealed = evidence.sealed().get();
                insert.setBytes(6, sealed.ciphertext());
                insert.setBytes(7, sealed.nonce());
                insert.setInt(8, sealed.keyVersion());
                insert.setBytes(9, evidence.checksum().get());
                insert.setInt(10, evidence.contentLength());
            } else {
                insert.setNull(6, Types.BINARY);
                insert.setNull(7, Types.BINARY);
                insert.setNull(8, Types.INTEGER);
                insert.setNull(9, Types.BINARY);
                insert.setNull(10, Types.INTEGER);
            }
            insert.setInt(11, evidence.retentionMonths());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording credit evidence", failure));
        }
    }

    @Override
    public List<CreditDataRequestId> claimDue(Connection unitOfWork, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one data request");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET status = 'REQUESTED', next_attempt_at = statement_timestamp() + retry_cadence"
                        + " WHERE id IN (SELECT id FROM " + TABLE
                        // NOT unavailable_reported: a claim whose statement began just before a deadline re-checks a
                        // row another sweeper reported meanwhile against its OWN statement_timestamp() - READ
                        // COMMITTED's re-evaluation - so the deadline alone would still admit it, and the CHECK
                        // would refuse the whole batch. The flag is the committed fact the re-check can see.
                        + " WHERE (status = 'REQUESTED' OR (status = 'UNAVAILABLE' AND deadline_at > statement_timestamp()"
                        + " AND NOT unavailable_reported))"
                        + " AND next_attempt_at <= statement_timestamp()"
                        // The retry stops asking for a closed request (CREDIT_DECISIONING_LIFECYCLES.md section 3.1;
                        // P10-DOC-001): a cancelled, expired, abandoned or decided request needs no more data, and
                        // asking again would be a paid pull of the applicant's data for nothing. A plain read - the
                        // decision request's lock (L2) is never taken after a data request's (L4).
                        + " AND EXISTS (SELECT 1 FROM credit.decision_request r WHERE r.id = " + TABLE
                        + ".decision_request_id"
                        + " AND r.status IN ('SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW'))"
                        + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id")) {
            claim.setInt(1, limit);
            List<CreditDataRequestId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(CreditDataRequestId.of(rows.getObject("id", UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("claiming due credit data requests", failure));
        }
    }

    @Override
    public List<Overdue> claimOverdue(Connection unitOfWork, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one data request");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET unavailable_reported = true"
                        + " WHERE id IN (SELECT id FROM " + TABLE
                        + " WHERE status = 'UNAVAILABLE' AND NOT unavailable_reported"
                        + " AND deadline_at <= statement_timestamp()"
                        + " ORDER BY deadline_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND NOT unavailable_reported RETURNING id, source_kind, provider_code, attempts")) {
            claim.setInt(1, limit);
            List<Overdue> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(new Overdue(
                            CreditDataRequestId.of(rows.getObject("id", UUID.class)),
                            CreditSourceKind.valueOf(rows.getString("source_kind")),
                            rows.getString("provider_code"),
                            rows.getInt("attempts")));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("claiming overdue credit data requests", failure));
        }
    }

    // -----------------------------------------------------------------

    private Optional<Row> byId(Connection unitOfWork, CreditDataRequestId id, String lock) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?" + lock)) {
            select.setObject(1, id.value());
            return one(select);
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a credit data request", failure));
        }
    }

    private static boolean transition(
            Connection unitOfWork, String sql, int attempts, CreditDataRequestId id, String operation) {
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setInt(1, attempts);
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe(operation, failure));
        }
    }

    private static Optional<Row> one(PreparedStatement select) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            return Optional.of(new Row(
                    CreditDataRequestId.of(row.getObject("id", UUID.class)),
                    row.getObject("decision_request_id", UUID.class),
                    row.getObject("party_id", UUID.class),
                    CreditProduct.valueOf(row.getString("product")),
                    CreditSourceKind.valueOf(row.getString("source_kind")),
                    row.getString("provider_code"),
                    row.getString("request_reference"),
                    CreditDataRequestStatus.valueOf(row.getString("status")),
                    row.getInt("attempts"),
                    row.getTimestamp("next_attempt_at").toInstant(),
                    row.getTimestamp("requested_at").toInstant(),
                    row.getTimestamp("deadline_at").toInstant(),
                    row.getBoolean("unavailable_reported")));
        }
    }
}
