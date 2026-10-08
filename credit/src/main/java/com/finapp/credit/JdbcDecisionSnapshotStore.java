package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link DecisionSnapshotStore} over credit {@code V004} and {@code V005} (`P10-TSK-008`). Stateless. */
public final class JdbcDecisionSnapshotStore implements DecisionSnapshotStore {

    @Override
    public List<DataRequestState> lockDataRequestsOf(Connection unitOfWork, UUID decisionRequestId) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, source_kind, status, requested_at, deadline_at <= transaction_timestamp() AS past_deadline"
                        + " FROM credit.data_request WHERE decision_request_id = ? ORDER BY id FOR UPDATE")) {
            select.setObject(1, decisionRequestId);
            List<DataRequestState> states = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    states.add(new DataRequestState(
                            CreditDataRequestId.of(rows.getObject("id", UUID.class)),
                            CreditSourceKind.valueOf(rows.getString("source_kind")),
                            CreditDataRequestStatus.valueOf(rows.getString("status")),
                            rows.getTimestamp("requested_at").toInstant(),
                            rows.getBoolean("past_deadline")));
                }
            }
            return states;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("locking a decision's data requests", failure));
        }
    }

    @Override
    public Optional<StoredRecord> recordOf(Connection unitOfWork, CreditDataRequestId dataRequest, Duration maxAge) {
        try {
            CreditRecordId id;
            CreditSourceKind kind;
            String provider;
            int normaliser;
            boolean fresh;
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT id, source_kind, provider_code, normaliser_version,"
                            + " retrieved_at >= transaction_timestamp() - ? * interval '1 millisecond' AS fresh"
                            + " FROM credit.credit_record WHERE data_request_id = ?")) {
                select.setLong(1, maxAge.toMillis());
                select.setObject(2, dataRequest.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    id = CreditRecordId.of(row.getObject("id", UUID.class));
                    kind = CreditSourceKind.valueOf(row.getString("source_kind"));
                    provider = row.getString("provider_code");
                    normaliser = row.getInt("normaliser_version");
                    fresh = row.getBoolean("fresh");
                }
            }
            List<StoredAttribute> attributes = new ArrayList<>();
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT code, value_type, integer_value, money_minor, money_currency, money_scale, boolean_value,"
                            + " code_value, absent FROM credit.credit_record_attribute WHERE record_id = ? ORDER BY code")) {
                select.setObject(1, id.value());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        attributes.add(new StoredAttribute(CreditAttributeCode.valueOf(rows.getString("code")), value(rows)));
                    }
                }
            }
            return Optional.of(new StoredRecord(id, kind, provider, normaliser, fresh, attributes));
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a credit record", failure));
        }
    }

    @Override
    public boolean insertSnapshot(
            Connection unitOfWork,
            DecisionSnapshotId id,
            UUID decisionRequestId,
            int sequence,
            int format,
            String canonical,
            byte[] sha256,
            PinnedVersions versions) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO credit.decision_snapshot (id, decision_request_id, sequence, snapshot_format, canonical,"
                        + " content_sha256, policy_version_id, model_version_id, engine_version, frozen_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp())"
                        + " ON CONFLICT (decision_request_id, sequence) DO NOTHING")) {
            insert.setObject(1, id.value());
            insert.setObject(2, decisionRequestId);
            insert.setInt(3, sequence);
            insert.setInt(4, format);
            insert.setString(5, canonical);
            insert.setBytes(6, sha256);
            insert.setObject(7, versions.policyVersion());
            insert.setObject(8, versions.modelVersion());
            insert.setInt(9, versions.engineVersion());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("freezing a decision snapshot", failure));
        }
    }

    @Override
    public Optional<StoredSnapshot> latestSnapshotOf(Connection unitOfWork, UUID decisionRequestId) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT sequence FROM credit.decision_snapshot WHERE decision_request_id = ? ORDER BY sequence DESC LIMIT 1")) {
            select.setObject(1, decisionRequestId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? snapshotOf(unitOfWork, decisionRequestId, row.getInt(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the latest decision snapshot", failure));
        }
    }

    @Override
    public Optional<StoredSnapshot> snapshotOf(Connection unitOfWork, UUID decisionRequestId, int sequence) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, sequence, snapshot_format, canonical, content_sha256, frozen_at FROM credit.decision_snapshot"
                        + " WHERE decision_request_id = ? AND sequence = ?")) {
            select.setObject(1, decisionRequestId);
            select.setInt(2, sequence);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredSnapshot(
                        DecisionSnapshotId.of(row.getObject("id", UUID.class)),
                        row.getInt("sequence"),
                        row.getInt("snapshot_format"),
                        row.getString("canonical"),
                        row.getBytes("content_sha256"),
                        row.getTimestamp("frozen_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a decision snapshot", failure));
        }
    }

    private static AttributeValue value(ResultSet row) throws SQLException {
        if (row.getBoolean("absent")) {
            return new AttributeValue.Absent();
        }
        return switch (AttributeValueType.valueOf(row.getString("value_type"))) {
            case INTEGER -> new AttributeValue.IntegerValue(row.getLong("integer_value"));
            case MONEY -> new AttributeValue.MoneyValue(Money.ofPersisted(row.getLong("money_minor"),
                    CurrencyCode.of(row.getString("money_currency")), row.getInt("money_scale")));
            case BOOLEAN -> new AttributeValue.BooleanValue(row.getBoolean("boolean_value"));
            case CODE -> new AttributeValue.CodeValue(row.getString("code_value"));
        };
    }
}
