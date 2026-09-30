package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BreakCaseStore} over JDBC (ADR-0033: explicit SQL, no mapper). Every statement is
 * reconciliation's own schema; the moving columns move under `V004`'s edge trigger for every
 * writer (a resolved break takes no write, severity and {@code residual_version} only rise,
 * the subject, cause and value at issue are frozen).
 */
public final class JdbcBreakCaseStore implements BreakCaseStore {

    static final String BREAK_COLUMNS =
            "id, type, cause, status, severity, source_id, rule_set_id, expectation_id,"
                    + " external_item_id, suspense_item_id, run_id, decision_id,"
                    + " value_at_issue_minor, currency, scale, internal_classification,"
                    + " internal_operation_ref, internal_state, assignee, residual_version,"
                    + " follows_break_id, raised_at, resolved_at, status_changed_at";

    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public Optional<UUID> sourceOf(Connection unitOfWork, UUID breakId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT source_id FROM reconciliation.break WHERE id = ?")) {
            read.setObject(1, breakId);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getObject("source_id", UUID.class))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the break's source", failure);
        }
    }

    @Override
    public void lockSource(Connection unitOfWork, UUID sourceId) {
        try (PreparedStatement lock =
                unitOfWork.prepareStatement(
                        "SELECT pg_advisory_xact_lock(4, hashtext(?::text))")) {
            lock.setObject(1, sourceId);
            lock.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not take the source's advisory lock", failure);
        }
    }

    @Override
    public Optional<BreakRow> lockForUpdate(Connection unitOfWork, UUID breakId) {
        return lockedRow(unitOfWork, breakId, "FOR UPDATE");
    }

    @Override
    public Optional<BreakRow> lockForShare(Connection unitOfWork, UUID breakId) {
        return lockedRow(unitOfWork, breakId, "FOR SHARE");
    }

    private Optional<BreakRow> lockedRow(Connection unitOfWork, UUID breakId, String lock) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + BREAK_COLUMNS + " FROM reconciliation.break WHERE id = ? "
                                + lock)) {
            read.setObject(1, breakId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(breakRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not lock the break", failure);
        }
    }

    static BreakRow breakRow(ResultSet row) throws SQLException {
        return new BreakRow(
                row.getObject("id", UUID.class),
                BreakType.valueOf(row.getString("type")),
                BreakCause.valueOf(row.getString("cause")),
                BreakStatus.valueOf(row.getString("status")),
                Severity.valueOf(row.getString("severity")),
                row.getObject("source_id", UUID.class),
                row.getObject("rule_set_id", UUID.class),
                Optional.ofNullable(row.getObject("expectation_id", UUID.class)),
                Optional.ofNullable(row.getObject("external_item_id", UUID.class)),
                Optional.ofNullable(row.getObject("suspense_item_id", UUID.class)),
                Optional.ofNullable(row.getObject("run_id", UUID.class)),
                Optional.ofNullable(row.getObject("decision_id", UUID.class)),
                row.getLong("value_at_issue_minor"),
                row.getString("currency").trim(),
                row.getInt("scale"),
                Optional.ofNullable(row.getString("internal_classification")),
                Optional.ofNullable(row.getString("internal_operation_ref")),
                Optional.ofNullable(row.getString("internal_state")),
                Optional.ofNullable(row.getString("assignee")),
                row.getLong("residual_version"),
                Optional.ofNullable(row.getObject("follows_break_id", UUID.class)),
                row.getObject("raised_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(row.getObject("resolved_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant),
                row.getObject("status_changed_at", OffsetDateTime.class).toInstant());
    }

    @Override
    public boolean assign(
            Connection unitOfWork,
            UUID breakId,
            BreakStatus from,
            BreakStatus to,
            String assignee,
            Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET assignee = ?, status = ?,"
                                + " status_changed_at = CASE WHEN status = ? THEN"
                                + " status_changed_at ELSE ? END"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, assignee);
            update.setString(2, to.name());
            update.setString(3, to.name());
            update.setTimestamp(4, Timestamp.from(at));
            update.setObject(5, breakId);
            update.setString(6, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not assign the break", failure);
        }
    }

    @Override
    public boolean reclassify(
            Connection unitOfWork,
            UUID breakId,
            BreakType from,
            BreakType to,
            Severity severity,
            Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET type = ?, severity = ?,"
                                + " residual_version = residual_version + 1"
                                + " WHERE id = ? AND type = ? AND status IN"
                                + " ('OPEN', 'INVESTIGATING')")) {
            update.setString(1, to.name());
            update.setString(2, severity.name());
            update.setObject(3, breakId);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new OneOpenSeatTaken(failure);
            }
            throw new ReconciliationStorageException("could not reclassify the break", failure);
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            UUID breakId,
            BreakEventType eventType,
            Actor actor,
            Optional<String> reason,
            String detail,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break_event (break_id, event_type, actor,"
                                + " actor_type, reason, detail, occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, breakId);
            insert.setString(2, eventType.name());
            insert.setString(3, actor.id());
            insert.setString(4, actor.type().name());
            insert.setString(5, reason.orElse(null));
            insert.setString(6, detail);
            insert.setTimestamp(7, Timestamp.from(at));
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the break's history", failure);
        }
    }

    @Override
    public void insertNote(
            Connection unitOfWork,
            UUID noteId,
            UUID breakId,
            String body,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break_note (id, break_id, body, author,"
                                + " author_type, added_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, noteId);
            insert.setObject(2, breakId);
            insert.setString(3, body);
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.setString(7, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            // Never the driver's message: a CHECK violation may echo the body.
            throw new ReconciliationStorageException(
                    "could not add the note (SQLState " + failure.getSQLState() + ")");
        }
    }

    @Override
    public void insertLink(
            Connection unitOfWork,
            UUID linkId,
            UUID breakId,
            EvidenceTargetKind kind,
            String targetRef,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break_evidence_link (id, break_id,"
                                + " target_kind, target_ref, added_by, added_by_type, added_at,"
                                + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, linkId);
            insert.setObject(2, breakId);
            insert.setString(3, kind.name());
            insert.setString(4, targetRef);
            insert.setString(5, actor.id());
            insert.setString(6, actor.type().name());
            insert.setTimestamp(7, Timestamp.from(at));
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not link the evidence (SQLState " + failure.getSQLState() + ")");
        }
    }

    @Override
    public boolean holdsParkedValue(Connection unitOfWork, UUID breakId) {
        return exists(
                unitOfWork,
                "SELECT EXISTS (SELECT 1 FROM reconciliation.suspense_item WHERE break_id = ?"
                        + " AND status IN ('OPEN', 'PARTIALLY_RELEASED')"
                        + " AND released_minor < amount_minor)",
                breakId);
    }

    @Override
    public boolean openBreakOfTypeStands(
            Connection unitOfWork,
            BreakType type,
            BreakSubjectKind subjectKind,
            UUID subjectId,
            UUID exceptBreakId) {
        String column =
                switch (subjectKind) {
                    case EXPECTATION -> "expectation_id";
                    case EXTERNAL_ITEM -> "external_item_id";
                    case SUSPENSE_ITEM -> "suspense_item_id";
                    case RUN -> "run_id";
                    case DECISION -> "decision_id";
                };
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM reconciliation.break WHERE type = ?"
                                + " AND status <> 'RESOLVED' AND " + column + " = ?"
                                + " AND id <> ?)")) {
            read.setString(1, type.name());
            read.setObject(2, subjectId);
            read.setObject(3, exceptBreakId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the subject's open breaks", failure);
        }
    }

    @Override
    public Optional<ExpectationDirection> subjectDirection(Connection unitOfWork, BreakRow row) {
        String sql =
                switch (row.subjectKind()) {
                    case EXPECTATION ->
                            "SELECT direction FROM reconciliation.expectation WHERE id = ?";
                    case EXTERNAL_ITEM ->
                            "SELECT direction FROM reconciliation.external_item WHERE id = ?";
                    case SUSPENSE_ITEM, RUN, DECISION -> null;
                };
        if (sql == null) {
            return Optional.empty();
        }
        return text(unitOfWork, sql, row.subjectId()).map(ExpectationDirection::valueOf);
    }

    @Override
    public Optional<ExpectationKind> subjectExpectationKind(Connection unitOfWork, BreakRow row) {
        if (row.subjectKind() != BreakSubjectKind.EXPECTATION) {
            return Optional.empty();
        }
        return text(
                        unitOfWork,
                        "SELECT kind FROM reconciliation.expectation WHERE id = ?",
                        row.subjectId())
                .map(ExpectationKind::valueOf);
    }

    @Override
    public Optional<Long> highValueMinor(Connection unitOfWork, UUID ruleSetId, String currency) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT high_value_minor FROM reconciliation.severity_threshold"
                                + " WHERE rule_set_id = ? AND currency = ?")) {
            read.setObject(1, ruleSetId);
            read.setString(2, currency);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getLong("high_value_minor"))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the severity threshold", failure);
        }
    }

    @Override
    public boolean runExists(Connection unitOfWork, UUID runId) {
        return exists(
                unitOfWork,
                "SELECT EXISTS (SELECT 1 FROM reconciliation.reconciliation_batch WHERE id = ?)",
                runId);
    }

    @Override
    public boolean decisionExists(Connection unitOfWork, UUID decisionId) {
        return exists(
                unitOfWork,
                "SELECT EXISTS (SELECT 1 FROM reconciliation.match_decision WHERE id = ?)",
                decisionId);
    }

    @Override
    public boolean expectationTracks(
            Connection unitOfWork, ExpectationKind kind, String operationRef) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM reconciliation.expectation"
                                + " WHERE kind = ? AND operation_ref = ?)")) {
            read.setString(1, kind.name());
            read.setString(2, operationRef);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the expectation register", failure);
        }
    }

    private static boolean exists(Connection unitOfWork, String sql, UUID id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read a case-file target", failure);
        }
    }

    private static Optional<String> text(Connection unitOfWork, String sql, UUID id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.ofNullable(row.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the break's subject", failure);
        }
    }
}
