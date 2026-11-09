package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RegulatorySubmissionStore} over plain JDBC (`P14-TSK-018`, ADR-0033; {@code JdbcRegulatoryReportStore}'s shape): the birth under {@code regulatory_submission_one_live}'s inference; every edge one conditional
 * {@code UPDATE}, its stamp the database's and judged again by {@code accounting V010}; the claim one statement. Each statement is pinned by {@code RegulatorySubmissionStatementsTest} and proven against PostgreSQL by
 * {@code RegulatorySubmissionDatabaseTest} (owed, C5). A failure is its operation and SQLSTATE only, never the driver's message, which could quote a figure or a reason. <strong>{@code 23505} on the birth is a refusal</strong>:
 * the one-correction unique, and the trigger's own "a return was sent" under that SQLSTATE, so the domain answers one way; the transaction is then rolled back by that refusal.
 */
public final class JdbcRegulatorySubmissionStore implements RegulatorySubmissionStore {

    private static final String UNIQUE_VIOLATION = "23505";

    static final String INSERT_SQL = "INSERT INTO accounting.regulatory_submission (id, obligation_id, jurisdiction_code, form_code, form_version, period_id, period_revision, corrects_submission_id, content,"
            + " content_sha256, status, prepared_by, prepared_by_type, prepared_at, preparation_reason, attempts, resends, correlation_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, clock_timestamp(), ?, 0, 0, ?)"
            + " ON CONFLICT (jurisdiction_code, form_code, period_id) WHERE status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'SUBMITTED') DO NOTHING";

    static final String INSERT_SOURCE_SQL = "INSERT INTO accounting.regulatory_submission_source (submission_id, run_id, run_sha256, definition, definition_version) VALUES (?, ?, ?, ?, ?)";

    static final String INSERT_LINE_SQL = "INSERT INTO accounting.regulatory_submission_line (submission_id, ordinal, line_code, label, currency, minor_units, scale, run_id, run_sha256, source_definition, figure_path)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String STANDING_CORRECTION = "SELECT c.id FROM accounting.regulatory_submission c WHERE c.corrects_submission_id = s.id AND c.status NOT IN ('REFUSED', 'WITHDRAWN')";

    /**
     * Sent or acknowledged, the month's revision moved, and no acknowledged return of the form standing at the month's present revision. It reads {@code s} and {@code p}. A chain of corrections reads rightly: only what the provider acknowledged at this revision clears it.
     */
    static final String OVERTAKEN = "s.status IN ('SUBMITTED', 'ACKNOWLEDGED') AND p.revision <> s.period_revision AND NOT EXISTS (SELECT 1 FROM accounting.regulatory_submission n WHERE n.jurisdiction_code = s.jurisdiction_code"
            + " AND n.form_code = s.form_code AND n.period_id = s.period_id AND n.status = 'ACKNOWLEDGED' AND n.period_revision = p.revision)";

    private static final String ROW = "SELECT s.id, s.obligation_id, s.jurisdiction_code, s.form_code, s.form_version, o.adapter_name, s.period_id, p.period_start, s.period_revision, p.revision AS period_revision_now,"
            + " p.status AS period_status_now, s.corrects_submission_id, (" + STANDING_CORRECTION + ") AS corrected_by, s.content_sha256, s.status, s.validation_outcome, s.validation_passed, s.validated_by, s.validated_at,"
            + " s.prepared_by, s.prepared_at, s.preparation_reason, s.approved_by, s.approved_at, s.submitted_by, s.submitted_at, s.closed_by, s.closed_at, s.attempts, s.resends, s.next_inquiry_at, s.answer_horizon_at,"
            + " (s.answer_horizon_at IS NOT NULL AND s.answer_horizon_at <= clock_timestamp()) AS past_horizon, s.last_answer, s.provider_reference, s.rejection_code, s.concluded_at,"
            + " (SELECT count(*) FROM accounting.regulatory_provider_answer a WHERE a.submission_id = s.id AND a.flag = 'CONTRARY') AS contrary_answers, (" + OVERTAKEN + ") AS overtaken, s.correlation_id"
            + " FROM accounting.regulatory_submission s JOIN accounting.accounting_period p ON p.id = s.period_id JOIN accounting.regulatory_obligation o ON o.id = s.obligation_id";

    static final String FIND_SQL = ROW + " WHERE s.id = ?";

    /** The row alone: the period and the obligation are the caller's, {@code FOR SHARE} and first. */
    static final String LOCK_SQL = ROW + " WHERE s.id = ? FOR UPDATE OF s";

    static final String CONTENT_SQL = "SELECT content FROM accounting.regulatory_submission WHERE id = ?";

    static final String ANY_SENT_SQL = "SELECT EXISTS (SELECT 1 FROM accounting.regulatory_submission WHERE jurisdiction_code = ? AND form_code = ? AND period_id = ?"
            + " AND status IN ('SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED'))";

    static final String CORRECTION_OF_SQL = "SELECT id FROM accounting.regulatory_submission WHERE corrects_submission_id = ? AND status NOT IN ('REFUSED', 'WITHDRAWN')";

    static final String SOURCES_SQL = "SELECT run_id, run_sha256, definition, definition_version FROM accounting.regulatory_submission_source WHERE submission_id = ? ORDER BY definition";

    static final String LINES_SQL = "SELECT ordinal, line_code, label, currency, minor_units, scale, run_id, run_sha256, source_definition, figure_path FROM accounting.regulatory_submission_line"
            + " WHERE submission_id = ? ORDER BY ordinal";

    /**
     * The newest only, then in order: an untrusted provider chooses how many there are and how large, so a read's load is the statement's. "Newest" is the insertion sequence ({@code accounting V014}), never {@code received_at}, which a backward step of the database's clock
     * can put before an earlier answer (`P14-REM-009`). Both writers take the submission's row {@code FOR UPDATE} before they insert, so for one submission the sequence is also the order its answers committed in.
     */
    static final String ANSWERS_SQL = "SELECT id, attempt, call_kind, stated_outcome, read_outcome, provider_reference, rejection_code, anomaly, flag, raw, raw_sha256, received_bytes, truncated, received_at FROM ("
            + "SELECT id, attempt, call_kind, stated_outcome, read_outcome, provider_reference, rejection_code, anomaly, flag, raw, raw_sha256, received_bytes, truncated, received_at, recorded_order"
            + " FROM accounting.regulatory_provider_answer WHERE submission_id = ? ORDER BY recorded_order DESC, id DESC LIMIT ?) newest ORDER BY recorded_order, id";

    static final String ANSWER_COUNT_SQL = "SELECT count(*) FROM accounting.regulatory_provider_answer WHERE submission_id = ?";

    static final String VALIDATE_SQL = "UPDATE accounting.regulatory_submission SET status = ?, validation_outcome = ?, validation_passed = ?, validated_by = ?, validated_by_type = ?, validated_at = clock_timestamp()"
            + " WHERE id = ? AND status = 'DRAFT' AND validation_outcome IS NULL";

    static final String APPROVE_SQL = "UPDATE accounting.regulatory_submission SET status = 'APPROVED', approved_by = ?, approved_by_type = ?, approved_at = clock_timestamp(), approval_reason = ?,"
            + " approved_content_sha256 = ?, approved_period_revision = ? WHERE id = ? AND status = 'VALIDATED'";

    static final String REFUSE_SQL = "UPDATE accounting.regulatory_submission SET status = 'REFUSED', closed_by = ?, closed_by_type = ?, closed_at = clock_timestamp(), closing_reason = ?"
            + " WHERE id = ? AND status IN ('VALIDATED', 'APPROVED')";

    static final String WITHDRAW_SQL = "UPDATE accounting.regulatory_submission SET status = 'WITHDRAWN', closed_by = ?, closed_by_type = ?, closed_at = clock_timestamp(), closing_reason = ?"
            + " WHERE id = ? AND status IN ('DRAFT', 'VALIDATED', 'APPROVED')";

    static final String SUBMIT_SQL = "UPDATE accounting.regulatory_submission SET status = 'SUBMITTED', submitted_by = ?, submitted_by_type = ?, submitted_at = clock_timestamp(), submission_reason = ?,"
            + " next_inquiry_at = clock_timestamp(), answer_horizon_at = clock_timestamp() + ? * interval '1 millisecond' WHERE id = ? AND status = 'APPROVED'";

    static final String ACKNOWLEDGE_SQL = "UPDATE accounting.regulatory_submission SET status = 'ACKNOWLEDGED', last_answer = 'ACKNOWLEDGED', provider_reference = COALESCE(provider_reference, ?),"
            + " concluded_at = clock_timestamp() WHERE id = ? AND status = 'SUBMITTED'";

    static final String REJECT_SQL = "UPDATE accounting.regulatory_submission SET status = 'REJECTED', last_answer = 'REJECTED', provider_reference = COALESCE(provider_reference, ?), rejection_code = ?,"
            + " concluded_at = clock_timestamp() WHERE id = ? AND status = 'SUBMITTED'";

    static final String UNDELIVER_SQL = "UPDATE accounting.regulatory_submission s SET status = 'UNDELIVERED', closed_by = ?, closed_by_type = ?, closed_at = clock_timestamp(), closing_reason = ?"
            + " WHERE s.id = ? AND s.status = 'SUBMITTED' AND s.last_answer = 'NOT_RECEIVED' AND (s.resends >= ? OR s.answer_horizon_at <= clock_timestamp()"
            + " OR s.period_revision <> (SELECT p.revision FROM accounting.accounting_period p WHERE p.id = s.period_id))";

    /** The door's claim: {@code SKIP LOCKED} on a permit that has come, one attempt counted, the permit a lease ahead, in one statement. */
    static final String CLAIM_SQL = "UPDATE accounting.regulatory_submission SET attempts = attempts + 1, next_inquiry_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = (SELECT id FROM accounting.regulatory_submission WHERE id = ? AND status = 'SUBMITTED' AND next_inquiry_at <= clock_timestamp() FOR UPDATE SKIP LOCKED) RETURNING id";

    /** The leg's claim: the oldest due permit, on {@code regulatory_submission_due}. */
    static final String CLAIM_NEXT_SQL = "UPDATE accounting.regulatory_submission SET attempts = attempts + 1, next_inquiry_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = (SELECT id FROM accounting.regulatory_submission WHERE status = 'SUBMITTED' AND next_inquiry_at <= clock_timestamp() ORDER BY next_inquiry_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING id";

    /** A figure the books no longer say is not sent again: the revision read, in the statement, for a caller past the domain's judgement. */
    static final String PERMIT_RESEND_SQL = "UPDATE accounting.regulatory_submission s SET resends = s.resends + 1, last_answer = 'RESENDING'"
            + " WHERE s.id = ? AND s.status = 'SUBMITTED' AND s.attempts = ? AND s.last_answer = 'NOT_RECEIVED' AND s.resends < ?"
            + " AND s.period_revision = (SELECT p.revision FROM accounting.accounting_period p WHERE p.id = s.period_id)";

    /** The back-off is the statement's, on the database's clock: the base interval up to the horizon, the time since it after, the ceiling above all. */
    static final String AWAIT_SQL = "UPDATE accounting.regulatory_submission SET next_inquiry_at = clock_timestamp() + LEAST(? * interval '1 millisecond', GREATEST(? * interval '1 millisecond', clock_timestamp() - answer_horizon_at)),"
            + " last_answer = ?, provider_reference = COALESCE(provider_reference, ?) WHERE id = ? AND status = 'SUBMITTED' AND attempts = ?";

    static final String INSERT_ANSWER_SQL = "INSERT INTO accounting.regulatory_provider_answer (id, submission_id, attempt, call_kind, stated_outcome, read_outcome, provider_reference, rejection_code, anomaly, flag, raw,"
            + " raw_sha256, received_bytes, truncated, received_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)";

    static final String INSERT_EVENT_SQL = "INSERT INTO accounting.regulatory_submission_event (id, submission_id, act, from_status, to_status, actor, actor_type, prepared_by, reason, occurred_at, correlation_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)";

    /** What stands: live, rejected or undelivered with no correction, overtaken, or holding a contrary answer so that a person can find which return. */
    private static final String STANDS = "(s.status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'SUBMITTED')"
            + " OR (s.status IN ('REJECTED', 'UNDELIVERED') AND NOT EXISTS (" + STANDING_CORRECTION + "))"
            + " OR (" + OVERTAKEN + ")"
            + " OR EXISTS (SELECT 1 FROM accounting.regulatory_provider_answer f WHERE f.submission_id = s.id AND f.flag = 'CONTRARY'))";

    /** Selected in the statement: the filter is the database's, before the {@code LIMIT}. Oldest first by the insertion sequence ({@code accounting V014}), which no step of a clock can reorder (`P14-REM-009`). */
    static final String STANDING_SQL = ROW + " WHERE " + STANDS + " ORDER BY s.recorded_order, s.id LIMIT ?";

    /**
     * The next page: a keyset on what is frozen from birth - by grant: {@code GENERATED ALWAYS} still admits {@code SET recorded_order = DEFAULT}, and {@code finapp_app} holds no {@code UPDATE} of the column - by the id the last page ended on, so the cursor is a row's id and means the same on either side of {@code V014}, whose backfill numbered every earlier row in the order this statement served them.
     * <strong>A number is drawn at the insert and is not commit order</strong>: a birth still uncommitted when a later number is served is missed by that walk. That is accepted, as the old stamp's order had it too: {@link #standing} is a person's worklist, the next walk from
     * the first page serves the row in its place, and every act judges again under its own locks.
     */
    static final String STANDING_AFTER_SQL = ROW + " WHERE " + STANDS + " AND (s.recorded_order, s.id) > (SELECT l.recorded_order, l.id FROM accounting.regulatory_submission l WHERE l.id = ?) ORDER BY s.recorded_order, s.id LIMIT ?";

    /** Selected in the statement: the filter is the database's, before the {@code LIMIT} - what its reader cannot decide never counts toward it. */
    static final String AWAITING_SQL = ROW + " WHERE s.status = 'VALIDATED' AND s.prepared_by <> ? ORDER BY s.validated_at, s.id LIMIT ?";

    static final String ATTENTION_SQL = "SELECT"
            + " count(*) FILTER (WHERE s.status = 'SUBMITTED' AND s.answer_horizon_at <= clock_timestamp()) AS overdue,"
            + " count(*) FILTER (WHERE s.status = 'SUBMITTED' AND s.last_answer = 'NOT_RECEIVED' AND s.resends >= ?) AS resends_exhausted,"
            + " count(*) FILTER (WHERE " + OVERTAKEN + ") AS overtaken,"
            + " count(*) FILTER (WHERE s.status IN ('DRAFT', 'VALIDATED', 'APPROVED') AND p.revision <> s.period_revision) AS stale_unsent"
            + " FROM accounting.regulatory_submission s JOIN accounting.accounting_period p ON p.id = s.period_id";

    @Override
    public boolean insert(Connection unitOfWork, NewSubmission submission) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_SQL)) {
            int i = 0;
            insert.setObject(++i, submission.id().value());
            insert.setObject(++i, submission.obligation().value());
            insert.setString(++i, submission.jurisdiction());
            insert.setString(++i, submission.form());
            insert.setInt(++i, submission.formVersion());
            insert.setObject(++i, submission.period().value());
            insert.setInt(++i, submission.periodRevision());
            if (submission.corrects().isPresent()) {
                insert.setObject(++i, submission.corrects().get().value());
            } else {
                insert.setNull(++i, Types.OTHER);
            }
            insert.setString(++i, submission.content());
            insert.setString(++i, submission.contentSha256());
            insert.setString(++i, submission.preparedBy().id());
            insert.setString(++i, submission.preparedBy().type().name());
            insert.setString(++i, submission.reason());
            insert.setString(++i, submission.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                return false;
            }
            throw failed("drafting a regulatory submission", failure);
        }
    }

    @Override
    public void insertSource(Connection unitOfWork, RegulatorySubmissionId id, Source source) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_SOURCE_SQL)) {
            insert.setObject(1, id.value());
            insert.setObject(2, source.run().value());
            insert.setString(3, source.runSha256());
            insert.setString(4, source.definition());
            insert.setInt(5, source.definitionVersion());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a regulatory submission's source", failure);
        }
    }

    @Override
    public void insertLines(Connection unitOfWork, RegulatorySubmissionId id, List<RegulatoryFormLine> lines) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_LINE_SQL)) {
            for (RegulatoryFormLine line : lines) {
                int i = 0;
                insert.setObject(++i, id.value());
                insert.setInt(++i, line.ordinal());
                insert.setString(++i, line.code());
                insert.setString(++i, line.label());
                insert.setString(++i, line.currency());
                insert.setString(++i, line.minor().toString());
                insert.setInt(++i, line.scale());
                insert.setObject(++i, line.run().value());
                insert.setString(++i, line.runSha256());
                insert.setString(++i, line.source());
                insert.setString(++i, line.figure());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("recording a regulatory submission's lines", failure);
        }
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, RegulatorySubmissionId id) {
        return one(unitOfWork, FIND_SQL, id, "reading a regulatory submission");
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, RegulatorySubmissionId id) {
        return one(unitOfWork, LOCK_SQL, id, "locking a regulatory submission");
    }

    @Override
    public Optional<String> content(Connection unitOfWork, RegulatorySubmissionId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(CONTENT_SQL)) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a regulatory submission's content", failure);
        }
    }

    @Override
    public boolean anySent(Connection unitOfWork, String jurisdiction, String form, AccountingPeriodId period) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ANY_SENT_SQL)) {
            read.setString(1, jurisdiction);
            read.setString(2, form);
            read.setObject(3, period.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading whether a return was sent", failure);
        }
    }

    @Override
    public Optional<RegulatorySubmissionId> correctionOf(Connection unitOfWork, RegulatorySubmissionId original) {
        try (PreparedStatement read = unitOfWork.prepareStatement(CORRECTION_OF_SQL)) {
            read.setObject(1, original.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(RegulatorySubmissionId.of(row.getObject(1, UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a return's correction", failure);
        }
    }

    @Override
    public List<Source> sources(Connection unitOfWork, RegulatorySubmissionId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(SOURCES_SQL)) {
            read.setObject(1, id.value());
            List<Source> sources = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    sources.add(new Source(ReportRunId.of(row.getObject("run_id", UUID.class)), row.getString("run_sha256"), row.getString("definition"), row.getInt("definition_version")));
                }
            }
            return sources;
        } catch (SQLException failure) {
            throw failed("reading a regulatory submission's sources", failure);
        }
    }

    @Override
    public List<RegulatoryFormLine> lines(Connection unitOfWork, RegulatorySubmissionId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(LINES_SQL)) {
            read.setObject(1, id.value());
            List<RegulatoryFormLine> lines = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    lines.add(new RegulatoryFormLine(row.getInt("ordinal"), row.getString("line_code"), row.getString("label"), row.getString("currency"), new BigInteger(row.getString("minor_units")),
                            row.getInt("scale"), ReportRunId.of(row.getObject("run_id", UUID.class)), row.getString("run_sha256"), row.getString("source_definition"), row.getString("figure_path")));
                }
            }
            return lines;
        } catch (SQLException failure) {
            throw failed("reading a regulatory submission's lines", failure);
        }
    }

    @Override
    public long answerCount(Connection unitOfWork, RegulatorySubmissionId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ANSWER_COUNT_SQL)) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw failed("counting a regulatory submission's answers", failure);
        }
    }

    @Override
    public List<AnswerRow> answers(Connection unitOfWork, RegulatorySubmissionId id, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ANSWERS_SQL)) {
            read.setObject(1, id.value());
            read.setInt(2, limit);
            List<AnswerRow> answers = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    answers.add(new AnswerRow(row.getObject("id", UUID.class), row.getInt("attempt"), RegulatoryReportingProvider.CallKind.valueOf(row.getString("call_kind")),
                            RegulatoryReportingProvider.Outcome.valueOf(row.getString("stated_outcome")), RegulatoryReportingProvider.Outcome.valueOf(row.getString("read_outcome")),
                            Optional.ofNullable(row.getString("provider_reference")), Optional.ofNullable(row.getString("rejection_code")),
                            Optional.ofNullable(row.getString("anomaly")).map(RegulatoryAnswerReading.Anomaly::valueOf), Optional.ofNullable(row.getString("flag")).map(Flag::valueOf),
                            row.getBytes("raw"), row.getString("raw_sha256"), row.getLong("received_bytes"), row.getBoolean("truncated"), instant(row, "received_at").orElseThrow()));
                }
            }
            return answers;
        } catch (SQLException failure) {
            throw failed("reading a regulatory submission's answers", failure);
        }
    }

    @Override
    public boolean markValidated(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String outcome, boolean passed) {
        try (PreparedStatement update = unitOfWork.prepareStatement(VALIDATE_SQL)) {
            update.setString(1, passed ? SubmissionStatus.VALIDATED.name() : SubmissionStatus.DRAFT.name());
            update.setString(2, outcome);
            update.setBoolean(3, passed);
            update.setString(4, actor.id());
            update.setString(5, actor.type().name());
            update.setObject(6, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("validating a regulatory submission", failure);
        }
    }

    @Override
    public boolean markApproved(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, String contentSha256, int periodRevision) {
        try (PreparedStatement update = unitOfWork.prepareStatement(APPROVE_SQL)) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setString(4, contentSha256);
            update.setInt(5, periodRevision);
            update.setObject(6, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("approving a regulatory submission", failure);
        }
    }

    @Override
    public boolean markRefused(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason) {
        return closed(unitOfWork, REFUSE_SQL, id, actor, reason, "refusing a regulatory submission");
    }

    @Override
    public boolean markWithdrawn(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason) {
        return closed(unitOfWork, WITHDRAW_SQL, id, actor, reason, "withdrawing a regulatory submission");
    }

    @Override
    public boolean markSubmitted(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, Duration horizon) {
        try (PreparedStatement update = unitOfWork.prepareStatement(SUBMIT_SQL)) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setLong(4, horizon.toMillis());
            update.setObject(5, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("submitting a regulatory submission", failure);
        }
    }

    @Override
    public boolean markAcknowledged(Connection unitOfWork, RegulatorySubmissionId id, Optional<String> reference) {
        try (PreparedStatement update = unitOfWork.prepareStatement(ACKNOWLEDGE_SQL)) {
            update.setString(1, reference.orElse(null));
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("recording a regulatory submission's acknowledgement", failure);
        }
    }

    @Override
    public boolean markRejected(Connection unitOfWork, RegulatorySubmissionId id, Optional<String> reference, String code) {
        try (PreparedStatement update = unitOfWork.prepareStatement(REJECT_SQL)) {
            update.setString(1, reference.orElse(null));
            update.setString(2, code);
            update.setObject(3, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("recording a regulatory submission's rejection", failure);
        }
    }

    @Override
    public boolean markUndelivered(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, int maxResends) {
        try (PreparedStatement update = unitOfWork.prepareStatement(UNDELIVER_SQL)) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            update.setInt(5, maxResends);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("concluding a regulatory submission undelivered", failure);
        }
    }

    @Override
    public Optional<Row> claim(Connection unitOfWork, RegulatorySubmissionId id, Duration lease) {
        try (PreparedStatement update = unitOfWork.prepareStatement(CLAIM_SQL)) {
            update.setLong(1, lease.toMillis());
            update.setObject(2, id.value());
            return claimed(unitOfWork, update);
        } catch (SQLException failure) {
            throw failed("claiming a regulatory submission's drive", failure);
        }
    }

    @Override
    public Optional<Row> claimNextDue(Connection unitOfWork, Duration lease) {
        try (PreparedStatement update = unitOfWork.prepareStatement(CLAIM_NEXT_SQL)) {
            update.setLong(1, lease.toMillis());
            return claimed(unitOfWork, update);
        } catch (SQLException failure) {
            throw failed("claiming the next regulatory submission due an inquiry", failure);
        }
    }

    private Optional<Row> claimed(Connection unitOfWork, PreparedStatement update) throws SQLException {
        UUID id;
        try (ResultSet row = update.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            id = row.getObject(1, UUID.class);
        }
        return find(unitOfWork, RegulatorySubmissionId.of(id));
    }

    @Override
    public boolean permitResend(Connection unitOfWork, RegulatorySubmissionId id, int attempt, int maxResends) {
        try (PreparedStatement update = unitOfWork.prepareStatement(PERMIT_RESEND_SQL)) {
            update.setObject(1, id.value());
            update.setInt(2, attempt);
            update.setInt(3, maxResends);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("counting a regulatory submission's resend", failure);
        }
    }

    @Override
    public boolean await(Connection unitOfWork, RegulatorySubmissionId id, int attempt, Duration interval, Duration ceiling, String lastAnswer, Optional<String> reference) {
        try (PreparedStatement update = unitOfWork.prepareStatement(AWAIT_SQL)) {
            update.setLong(1, ceiling.toMillis());
            update.setLong(2, interval.toMillis());
            update.setString(3, lastAnswer);
            update.setString(4, reference.orElse(null));
            update.setObject(5, id.value());
            update.setInt(6, attempt);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("recording a regulatory submission's unconcluded answer", failure);
        }
    }

    @Override
    public void appendAnswer(Connection unitOfWork, NewAnswer answer) {
        RegulatoryAnswerReading reading = answer.reading();
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_ANSWER_SQL)) {
            int i = 0;
            insert.setObject(++i, answer.id());
            insert.setObject(++i, answer.submission().value());
            insert.setInt(++i, answer.attempt());
            insert.setString(++i, reading.call().name());
            insert.setString(++i, reading.stated().name());
            insert.setString(++i, reading.read().name());
            insert.setString(++i, reading.providerReference().orElse(null));
            insert.setString(++i, reading.rejectionCode().orElse(null));
            insert.setString(++i, reading.anomaly().map(Enum::name).orElse(null));
            insert.setString(++i, answer.flag().map(Enum::name).orElse(null));
            insert.setBytes(++i, reading.retained());
            insert.setString(++i, reading.rawSha256());
            insert.setLong(++i, reading.receivedBytes());
            insert.setBoolean(++i, reading.truncated());
            insert.setString(++i, answer.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("keeping a provider's answer", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, EventRow event) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_EVENT_SQL)) {
            int i = 0;
            insert.setObject(++i, event.id());
            insert.setObject(++i, event.submission().value());
            insert.setString(++i, event.act().name());
            insert.setString(++i, event.from().map(Enum::name).orElse(null));
            insert.setString(++i, event.to().name());
            insert.setString(++i, event.actor().id());
            insert.setString(++i, event.actor().type().name());
            insert.setString(++i, event.preparedBy().orElse(null));
            insert.setString(++i, event.reason().orElse(null));
            insert.setString(++i, event.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a regulatory submission's history", failure);
        }
    }

    @Override
    public List<Row> standing(Connection unitOfWork, Optional<RegulatorySubmissionId> after, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(after.isPresent() ? STANDING_AFTER_SQL : STANDING_SQL)) {
            int i = 0;
            if (after.isPresent()) {
                read.setObject(++i, after.get().value());
            }
            read.setInt(++i, limit);
            return rows(read);
        } catch (SQLException failure) {
            throw failed("reading the regulatory submissions standing", failure);
        }
    }

    @Override
    public List<Row> awaitingApproval(Connection unitOfWork, String reader, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(AWAITING_SQL)) {
            read.setString(1, reader);
            read.setInt(2, limit);
            return rows(read);
        } catch (SQLException failure) {
            throw failed("reading the regulatory submissions awaiting approval", failure);
        }
    }

    @Override
    public Attention attention(Connection connection, int maxResends) {
        try (PreparedStatement read = connection.prepareStatement(ATTENTION_SQL)) {
            read.setInt(1, maxResends);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new Attention(row.getLong("overdue"), row.getLong("resends_exhausted"), row.getLong("overtaken"), row.getLong("stale_unsent"));
            }
        } catch (SQLException failure) {
            throw failed("reading what stands on the regulatory submissions", failure);
        }
    }

    // ------------------------------------------------------------------

    private static boolean closed(Connection unitOfWork, String statement, RegulatorySubmissionId id, Actor actor, String reason, String operation) {
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

    private static Optional<Row> one(Connection unitOfWork, String statement, RegulatorySubmissionId id, String operation) {
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
                rows.add(row(row));
            }
        }
        return rows;
    }

    private static Row row(ResultSet row) throws SQLException {
        return new Row(
                RegulatorySubmissionId.of(row.getObject("id", UUID.class)),
                RegulatoryObligationId.of(row.getObject("obligation_id", UUID.class)),
                row.getString("jurisdiction_code"),
                row.getString("form_code"),
                row.getInt("form_version"),
                row.getString("adapter_name"),
                AccountingPeriodId.of(row.getObject("period_id", UUID.class)),
                YearMonth.from(row.getDate("period_start").toLocalDate()),
                row.getInt("period_revision"),
                row.getInt("period_revision_now"),
                PeriodStatus.valueOf(row.getString("period_status_now")),
                submission(row, "corrects_submission_id"),
                submission(row, "corrected_by"),
                row.getString("content_sha256"),
                SubmissionStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getString("validation_outcome")),
                flag(row, "validation_passed"),
                Optional.ofNullable(row.getString("validated_by")),
                instant(row, "validated_at"),
                row.getString("prepared_by"),
                instant(row, "prepared_at").orElseThrow(),
                row.getString("preparation_reason"),
                Optional.ofNullable(row.getString("approved_by")),
                instant(row, "approved_at"),
                Optional.ofNullable(row.getString("submitted_by")),
                instant(row, "submitted_at"),
                Optional.ofNullable(row.getString("closed_by")),
                instant(row, "closed_at"),
                row.getInt("attempts"),
                row.getInt("resends"),
                instant(row, "next_inquiry_at"),
                instant(row, "answer_horizon_at"),
                row.getBoolean("past_horizon"),
                Optional.ofNullable(row.getString("last_answer")),
                Optional.ofNullable(row.getString("provider_reference")),
                Optional.ofNullable(row.getString("rejection_code")),
                instant(row, "concluded_at"),
                row.getLong("contrary_answers"),
                row.getBoolean("overtaken"),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static Optional<RegulatorySubmissionId> submission(ResultSet row, String column) throws SQLException {
        UUID id = row.getObject(column, UUID.class);
        return id == null ? Optional.empty() : Optional.of(RegulatorySubmissionId.of(id));
    }

    private static Optional<Boolean> flag(ResultSet row, String column) throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
