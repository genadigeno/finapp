package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/** {@link ReconciliationRuns} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcReconciliationRuns implements ReconciliationRuns {

    @Override
    public void birth(Connection unitOfWork, NewRun run) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.reconciliation_batch (id, source_id,"
                                + " batch_id, kind, rule_set_id, business_date,"
                                + " source_sequence, status, item_count, created_at,"
                                + " status_changed_at, correlation_id, requested_by, reason)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, run.id());
            insert.setObject(2, run.sourceId());
            insert.setObject(3, run.batchId().orElse(null));
            insert.setString(4, run.kind().name());
            insert.setObject(5, run.ruleSetId());
            insert.setObject(6, run.businessDate());
            insert.setObject(7, run.sourceSequence().orElse(null));
            insert.setInt(8, run.itemCount());
            insert.setTimestamp(9, Timestamp.from(run.at()));
            insert.setTimestamp(10, Timestamp.from(run.at()));
            insert.setString(11, run.correlation().value());
            insert.setString(12, run.requestedBy().orElse(null));
            insert.setString(13, run.reason().orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not birth a reconciliation run", failure);
        }
        try (PreparedStatement event =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.reconciliation_batch_event (run_id,"
                                + " from_status, to_status, actor, actor_type, occurred_at,"
                                + " correlation_id)"
                                + " VALUES (?, NULL, 'OPEN', ?, ?, ?, ?)")) {
            event.setObject(1, run.id());
            event.setString(2, run.actor().id());
            event.setString(3, run.actor().type().name());
            event.setTimestamp(4, Timestamp.from(run.at()));
            event.setString(5, run.correlation().value());
            event.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append a run's birth event", failure);
        }
    }
}
