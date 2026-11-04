package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RegulatoryObligationStore} over plain JDBC (`P14-TSK-018`, ADR-0033; {@code JdbcPriorPeriodAdjustmentStore}'s shape): the arbiters are these statements and {@code accounting V010} beneath them, pinned by
 * {@code RegulatorySubmissionStatementsTest} and proven against PostgreSQL by {@code RegulatorySubmissionDatabaseTest} (owed, C5). Every stamp the database's clock; every failure its operation and SQLSTATE only.
 */
public final class JdbcRegulatoryObligationStore implements RegulatoryObligationStore {

    static final String INSERT_SQL = "INSERT INTO accounting.regulatory_obligation (id, jurisdiction_code, form_code, form_version, period_type, adapter_name, standing, configuration, configuration_sha256,"
            + " status, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id)"
            + " VALUES (?, ?, ?, ?, 'MONTH', ?, 'REFERENCE', ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)"
            + " ON CONFLICT ON CONSTRAINT regulatory_obligation_one_version DO NOTHING";

    private static final String ROW = "SELECT id, jurisdiction_code, form_code, form_version, period_type, adapter_name, standing, configuration, configuration_sha256, status, proposed_by, proposed_at,"
            + " proposal_reason, decided_by, decided_at, retired_at, correlation_id FROM accounting.regulatory_obligation";

    static final String FIND_SQL = ROW + " WHERE id = ?";
    static final String LOCK_SHARED_SQL = ROW + " WHERE id = ? FOR SHARE";
    static final String LOCK_FORM_SQL = ROW + " WHERE jurisdiction_code = ? AND form_code = ? ORDER BY id FOR UPDATE";
    static final String RECENT_SQL = ROW + " ORDER BY proposed_at DESC, id DESC LIMIT ?";

    /** Selected in the statement: the filter is the database's, before the {@code LIMIT}. */
    static final String AWAITING_SQL = ROW + " WHERE status = 'PROPOSED' AND proposed_by <> ? ORDER BY proposed_at, id LIMIT ?";

    static final String ACTIVATE_SQL = "UPDATE accounting.regulatory_obligation SET status = 'ACTIVE', decided_by = ?, decided_by_type = ?, decided_at = clock_timestamp(), decision_reason = ?"
            + " WHERE id = ? AND status = 'PROPOSED'";
    static final String REJECT_SQL = "UPDATE accounting.regulatory_obligation SET status = 'REJECTED', decided_by = ?, decided_by_type = ?, decided_at = clock_timestamp(), decision_reason = ?"
            + " WHERE id = ? AND status = 'PROPOSED'";
    static final String RETIRE_SQL = "UPDATE accounting.regulatory_obligation SET status = 'RETIRED', retired_at = clock_timestamp() WHERE id = ? AND status = 'ACTIVE'";

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewObligation obligation) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_SQL)) {
            insert.setObject(1, obligation.id().value());
            insert.setString(2, obligation.jurisdiction());
            insert.setString(3, obligation.form());
            insert.setInt(4, obligation.formVersion());
            insert.setString(5, obligation.adapter());
            insert.setString(6, obligation.configuration());
            insert.setString(7, obligation.configurationSha256());
            insert.setString(8, obligation.proposedBy().id());
            insert.setString(9, obligation.proposedBy().type().name());
            insert.setString(10, obligation.reason());
            insert.setString(11, obligation.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("proposing a regulatory obligation", failure);
        }
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, RegulatoryObligationId id) {
        return one(unitOfWork, FIND_SQL, id, "reading a regulatory obligation");
    }

    @Override
    public Optional<Row> lockShared(Connection unitOfWork, RegulatoryObligationId id) {
        return one(unitOfWork, LOCK_SHARED_SQL, id, "holding a regulatory obligation");
    }

    @Override
    public List<Row> lockForm(Connection unitOfWork, String jurisdiction, String form) {
        try (PreparedStatement read = unitOfWork.prepareStatement(LOCK_FORM_SQL)) {
            read.setString(1, jurisdiction);
            read.setString(2, form);
            return rows(read);
        } catch (SQLException failure) {
            throw failed("locking a form's regulatory obligations", failure);
        }
    }

    @Override
    public List<Row> recent(Connection unitOfWork, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(RECENT_SQL)) {
            read.setInt(1, limit);
            return rows(read);
        } catch (SQLException failure) {
            throw failed("reading the regulatory obligations", failure);
        }
    }

    @Override
    public List<Row> awaitingActivation(Connection unitOfWork, String reader, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(AWAITING_SQL)) {
            read.setString(1, reader);
            read.setInt(2, limit);
            return rows(read);
        } catch (SQLException failure) {
            throw failed("reading the regulatory obligations awaiting activation", failure);
        }
    }

    @Override
    public boolean markActive(Connection unitOfWork, RegulatoryObligationId id, Actor actor, String reason) {
        return decided(unitOfWork, ACTIVATE_SQL, id, actor, reason, "activating a regulatory obligation");
    }

    @Override
    public boolean markRejected(Connection unitOfWork, RegulatoryObligationId id, Actor actor, String reason) {
        return decided(unitOfWork, REJECT_SQL, id, actor, reason, "rejecting a regulatory obligation");
    }

    @Override
    public boolean markRetired(Connection unitOfWork, RegulatoryObligationId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(RETIRE_SQL)) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("retiring a regulatory obligation", failure);
        }
    }

    private static boolean decided(Connection unitOfWork, String statement, RegulatoryObligationId id, Actor actor, String reason, String operation) {
        try (PreparedStatement update = unitOfWork.prepareStatement(statement)) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<Row> one(Connection unitOfWork, String statement, RegulatoryObligationId id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(statement)) {
            read.setObject(1, id.value());
            List<Row> rows = rows(read);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static List<Row> rows(PreparedStatement read) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (ResultSet row = read.executeQuery()) {
            while (row.next()) {
                rows.add(new Row(
                        RegulatoryObligationId.of(row.getObject("id", UUID.class)),
                        row.getString("jurisdiction_code"),
                        row.getString("form_code"),
                        row.getInt("form_version"),
                        row.getString("period_type"),
                        row.getString("adapter_name"),
                        row.getString("standing"),
                        row.getString("configuration"),
                        row.getString("configuration_sha256"),
                        RegulatoryObligationStatus.valueOf(row.getString("status")),
                        row.getString("proposed_by"),
                        instant(row, "proposed_at").orElseThrow(),
                        row.getString("proposal_reason"),
                        Optional.ofNullable(row.getString("decided_by")),
                        instant(row, "decided_at"),
                        instant(row, "retired_at"),
                        CorrelationId.of(row.getString("correlation_id"))));
            }
        }
        return rows;
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
