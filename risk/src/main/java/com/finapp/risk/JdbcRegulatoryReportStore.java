package com.finapp.risk;

import com.finapp.platform.security.ActorType;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link RegulatoryReportStore} over {@code risk V016}'s {@code regulatory_report} and {@code regulatory_report_event}
 * (`P13-TSK-021`; ADR-0033): the birth one statement, {@code INSERT … ON CONFLICT (case_id) WHERE status <> 'REJECTED' DO
 * NOTHING RETURNING} - the partial unique's inference, so a primary-key collision is never swallowed; every edge one
 * conditional {@code UPDATE … RETURNING} with its history row after it, the trigger judging the edge again and stamping the
 * instants on the database clock; the drive's claim one statement, {@code FOR UPDATE SKIP LOCKED} on a permit that has
 * come, the attempt counted and the permit moved in the same statement; the record conditional on {@code FILED} and the
 * claimed attempt. No statement names a content column in a {@code SET} (none is granted). Risk's own tables only. A
 * failure is named by its operation and SQLSTATE only, never the driver's message (which could quote the narrative).
 */
public final class JdbcRegulatoryReportStore implements RegulatoryReportStore {

    static final String COLUMNS = "id, case_id, jurisdiction, format_version, reporting_entity_ref, subject_ref,"
            + " account_refs, transaction_ids, ground_codes, narrative, attachment_refs, status, drafted_by,"
            + " drafted_by_type, drafted_at, approved_by, approved_by_type, approved_at, filed_by, filed_by_type, filed_at,"
            + " provider_reference, rejection_code, concluded_at, next_inquiry_at, attempts, correlation_id";

    /** The birth under the one-live unique; the stamp is the trigger's whatever is passed. */
    static final String INSERT = "INSERT INTO risk.regulatory_report (id, case_id, jurisdiction, format_version,"
            + " reporting_entity_ref, subject_kind, subject_ref, account_refs, transaction_ids, ground_codes, narrative,"
            + " attachment_refs, status, drafted_by, drafted_by_type, drafted_at, attempts, correlation_id)"
            + " VALUES (?, ?, ?, ?, ?, 'PARTY', ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, clock_timestamp(), 0, ?)"
            + " ON CONFLICT (case_id) WHERE status <> 'REJECTED' DO NOTHING RETURNING " + COLUMNS;

    static final String EVENT = "INSERT INTO risk.regulatory_report_event (report_id, status, actor_id, actor_type,"
            + " recorded_at, correlation_id) VALUES (?, ?, ?, ?, clock_timestamp(), ?)";

    /** A case's report that occupies it - on the one-live unique's own predicate. */
    static final String LIVE_OF = "SELECT " + COLUMNS + " FROM risk.regulatory_report"
            + " WHERE case_id = ? AND status <> 'REJECTED'";

    static final String APPROVE = "UPDATE risk.regulatory_report SET status = 'APPROVED', approved_by = ?,"
            + " approved_by_type = ? WHERE id = ? AND status = 'DRAFT' RETURNING " + COLUMNS;

    /** The filing: the trigger stamps it and sets the drive's permit due at once. */
    static final String FILE = "UPDATE risk.regulatory_report SET status = 'FILED', filed_by = ?, filed_by_type = ?"
            + " WHERE id = ? AND status = 'APPROVED' RETURNING " + COLUMNS;

    /** The claim of one report: SKIP LOCKED on its due permit, moved a lease ahead, one attempt counted. */
    static final String CLAIM = "UPDATE risk.regulatory_report SET attempts = attempts + 1,"
            + " next_inquiry_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = (SELECT id FROM risk.regulatory_report WHERE id = ? AND status = 'FILED'"
            + " AND next_inquiry_at <= clock_timestamp() FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;

    /** The leg's claim: the oldest due permit, on regulatory_report_due. */
    static final String CLAIM_NEXT_DUE = "UPDATE risk.regulatory_report SET attempts = attempts + 1,"
            + " next_inquiry_at = clock_timestamp() + ? * interval '1 millisecond'"
            + " WHERE id = (SELECT id FROM risk.regulatory_report WHERE next_inquiry_at <= clock_timestamp()"
            + " AND status = 'FILED' ORDER BY next_inquiry_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;

    /** The authority's acknowledgement: conditional on FILED and the claimed attempt; its reference kept once given. */
    static final String ACKNOWLEDGE = "UPDATE risk.regulatory_report SET status = 'ACKNOWLEDGED',"
            + " provider_reference = COALESCE(provider_reference, ?)"
            + " WHERE id = ? AND status = 'FILED' AND attempts = ? RETURNING " + COLUMNS;

    static final String REJECT = "UPDATE risk.regulatory_report SET status = 'REJECTED',"
            + " provider_reference = COALESCE(provider_reference, ?), rejection_code = ?"
            + " WHERE id = ? AND status = 'FILED' AND attempts = ? RETURNING " + COLUMNS;

    /** An unknown standing: FILED kept, the permit an inquiry interval ahead. */
    static final String AWAIT = "UPDATE risk.regulatory_report SET"
            + " next_inquiry_at = clock_timestamp() + ? * interval '1 millisecond',"
            + " provider_reference = COALESCE(provider_reference, ?)"
            + " WHERE id = ? AND status = 'FILED' AND attempts = ? RETURNING " + COLUMNS;

    /**
     * The monitored transactions born from the case's evidence's journal entries that touch the subject's accounts - the
     * first {@code limit} in the journal's order, and the count of all of them (computed before the limit).
     */
    static final String REFERENCED = "SELECT id, count(*) OVER () AS matched FROM risk.monitored_transaction"
            + " WHERE journal_entry_id = ANY (?)"
            + " AND ((subject_kind = 'ACCOUNT' AND subject_ref = ANY (?))"
            + " OR (counterparty_kind = 'CUSTOMER' AND counterparty_ref = ANY (?)))"
            + " ORDER BY journal_sequence, id LIMIT ?";

    @Override
    public Optional<RegulatoryReport> liveOf(Connection unitOfWork, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(caseId, "caseId");
        try (PreparedStatement select = unitOfWork.prepareStatement(LIVE_OF)) {
            select.setObject(1, caseId.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a case's live regulatory report", failure);
        }
    }

    @Override
    public Optional<RegulatoryReport> insert(Connection unitOfWork, NewReport report) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(report, "report");
        ReportContent content = report.content();
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT)) {
            int i = 0;
            insert.setObject(++i, report.id().value());
            insert.setObject(++i, report.caseId().value());
            insert.setString(++i, content.jurisdiction());
            insert.setInt(++i, content.formatVersion());
            insert.setString(++i, content.reportingEntityReference());
            insert.setObject(++i, content.subject());
            insert.setArray(++i, unitOfWork.createArrayOf("uuid", content.accounts().toArray()));
            insert.setArray(++i, unitOfWork.createArrayOf("uuid", content.transactions().toArray()));
            insert.setArray(++i, unitOfWork.createArrayOf("text", content.grounds().toArray()));
            insert.setString(++i, content.narrative());
            insert.setArray(++i, unitOfWork.createArrayOf("uuid", content.attachments().toArray()));
            insert.setString(++i, report.drafter().id());
            insert.setString(++i, report.drafter().type().name());
            insert.setString(++i, report.correlationId());
            Optional<RegulatoryReport> born = single(insert);
            if (born.isPresent()) {
                event(unitOfWork, born.get().id(), RegulatoryReportStatus.DRAFT, report.drafter(), report.correlationId());
            }
            return born;
        } catch (SQLException failure) {
            throw failed("drafting a regulatory report", failure);
        }
    }

    @Override
    public Optional<RegulatoryReport> lock(Connection unitOfWork, RegulatoryReportId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.regulatory_report WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("locking a regulatory report", failure);
        }
    }

    @Override
    public Optional<RegulatoryReport> find(Connection unitOfWork, RegulatoryReportId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.regulatory_report WHERE id = ?")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a regulatory report", failure);
        }
    }

    @Override
    public Optional<RegulatoryReport> approve(Connection unitOfWork, RegulatoryReportId id, RiskCase.Person approver,
            String correlationId) {
        return decided(unitOfWork, APPROVE, id, approver, RegulatoryReportStatus.APPROVED, correlationId,
                "approving a regulatory report");
    }

    @Override
    public Optional<RegulatoryReport> file(Connection unitOfWork, RegulatoryReportId id, RiskCase.Person filer,
            String correlationId) {
        return decided(unitOfWork, FILE, id, filer, RegulatoryReportStatus.FILED, correlationId,
                "filing a regulatory report");
    }

    @Override
    public Optional<RegulatoryReport> claim(Connection unitOfWork, RegulatoryReportId id, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(CLAIM)) {
            update.setLong(1, Objects.requireNonNull(lease, "lease").toMillis());
            update.setObject(2, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("claiming a regulatory report's drive", failure);
        }
    }

    @Override
    public Optional<RegulatoryReport> claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement update = unitOfWork.prepareStatement(CLAIM_NEXT_DUE)) {
            update.setLong(1, Objects.requireNonNull(lease, "lease").toMillis());
            return single(update);
        } catch (SQLException failure) {
            throw failed("claiming the next regulatory report due an inquiry", failure);
        }
    }

    @Override
    public Optional<RegulatoryReport> record(Connection unitOfWork, RegulatoryReportId id, int attempt,
            FilingOutcome outcome, Duration inquiryInterval, RiskCase.Person actor, String correlationId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(inquiryInterval, "inquiryInterval");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
        String reference = outcome.providerReference().orElse(null);
        try {
            switch (outcome.kind()) {
                case ACKNOWLEDGED -> {
                    try (PreparedStatement update = unitOfWork.prepareStatement(ACKNOWLEDGE)) {
                        update.setString(1, reference);
                        update.setObject(2, id.value());
                        update.setInt(3, attempt);
                        return concluded(unitOfWork, update, RegulatoryReportStatus.ACKNOWLEDGED, actor, correlationId);
                    }
                }
                case REJECTED -> {
                    try (PreparedStatement update = unitOfWork.prepareStatement(REJECT)) {
                        update.setString(1, reference);
                        update.setString(2, outcome.rejectionCode().orElseThrow());
                        update.setObject(3, id.value());
                        update.setInt(4, attempt);
                        return concluded(unitOfWork, update, RegulatoryReportStatus.REJECTED, actor, correlationId);
                    }
                }
                default -> {
                    try (PreparedStatement update = unitOfWork.prepareStatement(AWAIT)) {
                        update.setLong(1, inquiryInterval.toMillis());
                        update.setString(2, reference);
                        update.setObject(3, id.value());
                        update.setInt(4, attempt);
                        return single(update);
                    }
                }
            }
        } catch (SQLException failure) {
            throw failed("recording a regulatory report's answer", failure);
        }
    }

    @Override
    public Referenced referenced(Connection unitOfWork, List<UUID> journalEntries, Set<UUID> accounts, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(journalEntries, "journalEntries");
        Objects.requireNonNull(accounts, "accounts");
        if (limit < 1) {
            throw new IllegalArgumentException("a report references at least one transaction when it references any");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(REFERENCED)) {
            Array accountRefs = unitOfWork.createArrayOf("uuid", accounts.toArray());
            select.setArray(1, unitOfWork.createArrayOf("uuid", journalEntries.toArray()));
            select.setArray(2, accountRefs);
            select.setArray(3, accountRefs);
            select.setInt(4, limit);
            List<UUID> transactions = new ArrayList<>();
            long matched = 0;
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    transactions.add(row.getObject("id", UUID.class));
                    matched = row.getLong("matched");
                }
            }
            return new Referenced(transactions, matched);
        } catch (SQLException failure) {
            throw failed("reading the transactions a report references", failure);
        }
    }

    // -----------------------------------------------------------------

    private Optional<RegulatoryReport> decided(Connection unitOfWork, String statement, RegulatoryReportId id,
            RiskCase.Person person, RegulatoryReportStatus reached, String correlationId, String operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(person, "person");
        Objects.requireNonNull(correlationId, "correlationId");
        try (PreparedStatement update = unitOfWork.prepareStatement(statement)) {
            update.setString(1, person.id());
            update.setString(2, person.type().name());
            update.setObject(3, id.value());
            Optional<RegulatoryReport> moved = single(update);
            if (moved.isPresent()) {
                event(unitOfWork, id, reached, person, correlationId);
            }
            return moved;
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<RegulatoryReport> concluded(Connection unitOfWork, PreparedStatement update,
            RegulatoryReportStatus reached, RiskCase.Person actor, String correlationId) throws SQLException {
        Optional<RegulatoryReport> answered = single(update);
        if (answered.isPresent()) {
            event(unitOfWork, answered.get().id(), reached, actor, correlationId);
        }
        return answered;
    }

    private static void event(Connection unitOfWork, RegulatoryReportId id, RegulatoryReportStatus status,
            RiskCase.Person actor, String correlationId) throws SQLException {
        try (PreparedStatement insert = unitOfWork.prepareStatement(EVENT)) {
            insert.setObject(1, id.value());
            insert.setString(2, status.name());
            insert.setString(3, actor.id());
            insert.setString(4, actor.type().name());
            insert.setString(5, correlationId);
            insert.executeUpdate();
        }
    }

    private static Optional<RegulatoryReport> single(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            return row.next() ? Optional.of(report(row)) : Optional.empty();
        }
    }

    private static RegulatoryReport report(ResultSet row) throws SQLException {
        ReportContent content = new ReportContent(
                row.getString("jurisdiction"),
                row.getInt("format_version"),
                row.getString("reporting_entity_ref"),
                row.getObject("subject_ref", UUID.class),
                uuids(row, "account_refs"),
                uuids(row, "transaction_ids"),
                texts(row, "ground_codes"),
                row.getString("narrative"),
                uuids(row, "attachment_refs"));
        return new RegulatoryReport(
                RegulatoryReportId.of(row.getObject("id", UUID.class)),
                RiskCaseId.of(row.getObject("case_id", UUID.class)),
                content,
                RegulatoryReportStatus.valueOf(row.getString("status")),
                person(row, "drafted_by", "drafted_by_type").orElseThrow(),
                instant(row, "drafted_at"),
                person(row, "approved_by", "approved_by_type"),
                instant(row, "approved_at"),
                person(row, "filed_by", "filed_by_type"),
                instant(row, "filed_at"),
                Optional.ofNullable(row.getString("provider_reference")),
                Optional.ofNullable(row.getString("rejection_code")),
                instant(row, "concluded_at"),
                instant(row, "next_inquiry_at"),
                row.getInt("attempts"),
                row.getString("correlation_id"));
    }

    private static Optional<RiskCase.Person> person(ResultSet row, String id, String type) throws SQLException {
        String who = row.getString(id);
        return who == null ? Optional.empty()
                : Optional.of(new RiskCase.Person(who, ActorType.valueOf(row.getString(type))));
    }

    private static List<UUID> uuids(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        List<UUID> references = new ArrayList<>();
        for (Object reference : (Object[]) array.getArray()) {
            references.add((UUID) reference);
        }
        return List.copyOf(references);
    }

    private static List<String> texts(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        List<String> codes = new ArrayList<>();
        for (Object code : (Object[]) array.getArray()) {
            codes.add((String) code);
        }
        return List.copyOf(codes);
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
