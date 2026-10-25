package com.finapp.risk;

import com.finapp.platform.security.ActorType;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@link RiskCaseStore} over {@code risk V012}'s {@code risk_case}, {@code risk_case_action} and {@code risk_case_event}
 * (`P13-TSK-016`; ADR-0033): the birth one statement, {@code INSERT … ON CONFLICT (subject_ref, kind) WHERE status <>
 * 'DISPOSED' DO NOTHING RETURNING} - the partial unique's inference, so a primary-key collision is never swallowed;
 * every step one conditional {@code UPDATE … WHERE id = ? AND revision = ? RETURNING}, the trigger judging it again and
 * stamping the instants on the database clock; the actions and the history {@code INSERT}-only. Risk's own tables only -
 * no other schema is named ({@code INV-RSK-02}). A failure is named by its operation and SQLSTATE only, never the
 * driver's message (which could quote a subject or a note).
 */
public final class JdbcRiskCaseStore implements RiskCaseStore {

    static final String COLUMNS = "id, kind, subject_kind, subject_ref, status, revision, level, queue, taken_by,"
            + " taken_by_type, sla_hours, sla_deadline, opening_alert_id, opening_assessment_id, predecessor_case_id,"
            + " opened_at, disposition, disposition_id, disposition_prohibits, disposition_proposed_by,"
            + " disposition_proposed_by_type, disposition_proposed_at, disposition_approved_by,"
            + " disposition_approved_by_type, disposed_at, correlation_id";

    static final String ACTION_COLUMNS = "id, case_id, ordinal, kind, actor_id, actor_type, text, evidence_signal_ids,"
            + " evidence_assessment_ids, evidence_journal_entry_ids, disposition, prohibits, queue, recorded_at,"
            + " correlation_id";

    static final String EVENT_COLUMNS = "case_id, revision, kind, status, level, queue, actor_id, actor_type, action_id,"
            + " alert_id, assessment_id, recorded_at, correlation_id";

    /** The birth - one statement under the one-open unique; the stamps are the trigger's whatever is passed. */
    static final String INSERT = "INSERT INTO risk.risk_case (id, kind, subject_kind, subject_ref, status, revision,"
            + " level, queue, sla_hours, sla_deadline, opening_alert_id, opening_assessment_id, predecessor_case_id,"
            + " opened_at, correlation_id)"
            + " VALUES (?, ?, ?, ?, 'OPEN', 0, 'L1', 'FIRST_LINE', ?, clock_timestamp() + interval '1 hour', ?, ?, ?,"
            + " clock_timestamp(), ?)"
            + " ON CONFLICT (subject_ref, kind) WHERE status <> 'DISPOSED' DO NOTHING RETURNING " + COLUMNS;

    /** R4: the subject's open case of the kind - the partial unique's own predicate, re-judged after a wait. */
    static final String LOCK_OPEN = "SELECT " + COLUMNS + " FROM risk.risk_case"
            + " WHERE subject_ref = ? AND kind = ? AND status <> 'DISPOSED' FOR UPDATE";

    static final String LATEST_DISPOSED = "SELECT id FROM risk.risk_case"
            + " WHERE subject_ref = ? AND kind = ? AND status = 'DISPOSED' ORDER BY disposed_at DESC, id DESC LIMIT 1";

    /** The step - conditional on the revision the lock read; the revision the database's + 1. */
    static final String ADVANCE = "UPDATE risk.risk_case SET status = ?, revision = revision + 1, level = ?, queue = ?,"
            + " taken_by = ?, taken_by_type = ?, disposition = ?, disposition_id = ?, disposition_prohibits = ?,"
            + " disposition_proposed_by = ?, disposition_proposed_by_type = ?, disposition_approved_by = ?,"
            + " disposition_approved_by_type = ?"
            + " WHERE id = ? AND revision = ? RETURNING " + COLUMNS;

    static final String APPEND_ACTION = "INSERT INTO risk.risk_case_action (" + ACTION_COLUMNS + ")"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)";

    static final String APPEND_EVENT = "INSERT INTO risk.risk_case_event (" + EVENT_COLUMNS + ")"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)";

    /** The approved prohibitions with no profile version naming them - on the leg's partial index. */
    static final String PROHIBITIONS_DUE = "SELECT c.id, c.subject_ref, c.disposition_id FROM risk.risk_case c"
            + " WHERE c.status = 'DISPOSED' AND c.disposition_prohibits AND NOT EXISTS ("
            + "SELECT 1 FROM risk.customer_risk_profile_version v WHERE v.disposition_id = c.disposition_id)"
            + " ORDER BY c.disposed_at, c.id LIMIT ?";

    /** A disposition waits for a standing action proposal (`P13-TSK-017`) - read under the case's lock. */
    static final String ACTION_PROPOSAL_STANDS = "SELECT EXISTS (SELECT 1 FROM risk.risk_action"
            + " WHERE case_id = ? AND status = 'PROPOSED')";

    /**
     * `P13-TSK-018`'s queue: the reader's own {@code PARTY}-subject cases left out; the status (a named one, or the open
     * three on {@code risk_case_open_queue}), the kind and the cursor appended by {@link #queueStatement} - this class's
     * constant text only, never input.
     */
    static final String QUEUE = "SELECT " + COLUMNS + " FROM risk.risk_case"
            + " WHERE NOT (subject_kind = 'PARTY' AND subject_ref = ?)";

    static final String QUEUE_STATUS = " AND status = ?";

    static final String QUEUE_OPEN = " AND status <> 'DISPOSED'";

    static final String QUEUE_KIND = " AND kind = ?";

    /** After the cursor's key - read from the cursor's own row, so the instant is never round-tripped through Java. */
    static final String QUEUE_AFTER = " AND (opened_at, id) > (SELECT c.opened_at, c.id FROM risk.risk_case c"
            + " WHERE c.id = ?)";

    static final String QUEUE_ORDER = " ORDER BY opened_at, id LIMIT ?";

    /**
     * The backlog gauges' read: the open cases per kind and the oldest's age in whole seconds on the database clock - on
     * {@code risk_case_open_queue}.
     */
    static final String BACKLOG = "SELECT kind, count(*) AS open_cases,"
            + " GREATEST(0, floor(extract(epoch FROM statement_timestamp() - min(opened_at))))::bigint AS oldest_seconds"
            + " FROM risk.risk_case WHERE status <> 'DISPOSED' GROUP BY kind";

    @Override
    public Optional<RiskCase> lockOpen(Connection unitOfWork, SignalSubject subject, CaseKind kind) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        try (PreparedStatement select = unitOfWork.prepareStatement(LOCK_OPEN)) {
            select.setString(1, subject.ref());
            select.setString(2, kind.name());
            return single(select);
        } catch (SQLException failure) {
            throw failed("locking a subject's open risk case", failure);
        }
    }

    @Override
    public Optional<RiskCase> insert(Connection unitOfWork, NewCase newCase) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(newCase, "newCase");
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT)) {
            int i = 0;
            insert.setObject(++i, newCase.id().value());
            insert.setString(++i, newCase.kind().name());
            insert.setString(++i, newCase.subject().kind().name());
            insert.setString(++i, newCase.subject().ref());
            insert.setInt(++i, newCase.slaHours());
            insert.setObject(++i, newCase.openingAlert().map(RiskAlertId::value).orElse(null), Types.OTHER);
            insert.setObject(++i, newCase.openingAssessment().map(RiskAssessmentId::value).orElse(null), Types.OTHER);
            insert.setObject(++i, newCase.predecessor().map(RiskCaseId::value).orElse(null), Types.OTHER);
            insert.setString(++i, newCase.correlationId());
            return single(insert);
        } catch (SQLException failure) {
            throw failed("opening a risk case", failure);
        }
    }

    @Override
    public Optional<RiskCase> lock(Connection unitOfWork, RiskCaseId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.risk_case WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("locking a risk case", failure);
        }
    }

    @Override
    public Optional<RiskCase> find(Connection unitOfWork, RiskCaseId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.risk_case WHERE id = ?")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a risk case", failure);
        }
    }

    @Override
    public Optional<RiskCaseId> latestDisposed(Connection unitOfWork, SignalSubject subject, CaseKind kind) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        try (PreparedStatement select = unitOfWork.prepareStatement(LATEST_DISPOSED)) {
            select.setString(1, subject.ref());
            select.setString(2, kind.name());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(RiskCaseId.of(row.getObject("id", UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a subject's disposed risk case", failure);
        }
    }

    @Override
    public Optional<RiskCase> advance(Connection unitOfWork, RiskCaseId id, int fromRevision, Step step) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(step, "step");
        Optional<RiskCase.DispositionSlot> slot = step.disposition();
        try (PreparedStatement update = unitOfWork.prepareStatement(ADVANCE)) {
            int i = 0;
            update.setString(++i, step.status().name());
            update.setString(++i, step.level().name());
            update.setString(++i, step.queue().name());
            update.setString(++i, step.takenBy().map(RiskCase.Person::id).orElse(null));
            update.setString(++i, step.takenBy().map(person -> person.type().name()).orElse(null));
            update.setString(++i, slot.map(s -> s.disposition().name()).orElse(null));
            update.setObject(++i, slot.map(RiskCase.DispositionSlot::id).orElse(null), Types.OTHER);
            update.setObject(++i, slot.map(RiskCase.DispositionSlot::prohibits).orElse(null), Types.BOOLEAN);
            update.setString(++i, slot.map(s -> s.proposedBy().id()).orElse(null));
            update.setString(++i, slot.map(s -> s.proposedBy().type().name()).orElse(null));
            update.setString(++i, slot.flatMap(RiskCase.DispositionSlot::approvedBy).map(RiskCase.Person::id)
                    .orElse(null));
            update.setString(++i, slot.flatMap(RiskCase.DispositionSlot::approvedBy).map(p -> p.type().name())
                    .orElse(null));
            update.setObject(++i, id.value());
            update.setInt(++i, fromRevision);
            return single(update);
        } catch (SQLException failure) {
            throw failed("stepping a risk case", failure);
        }
    }

    @Override
    public void appendAction(Connection unitOfWork, CaseAction action) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(action, "action");
        try (PreparedStatement insert = unitOfWork.prepareStatement(APPEND_ACTION)) {
            int i = 0;
            insert.setObject(++i, action.id());
            insert.setObject(++i, action.caseId().value());
            insert.setInt(++i, action.ordinal());
            insert.setString(++i, action.kind().name());
            insert.setString(++i, action.actor().id());
            insert.setString(++i, action.actor().type().name());
            insert.setString(++i, action.text().orElse(null));
            insert.setArray(++i, uuids(unitOfWork, action.evidence().signals(), RiskSignalId::value));
            insert.setArray(++i, uuids(unitOfWork, action.evidence().assessments(), RiskAssessmentId::value));
            insert.setArray(++i, uuids(unitOfWork, action.evidence().journalEntries(), Function.identity()));
            insert.setString(++i, action.disposition().map(Enum::name).orElse(null));
            insert.setBoolean(++i, action.prohibits());
            insert.setString(++i, action.queue().map(Enum::name).orElse(null));
            insert.setString(++i, action.correlationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording an investigator action", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, CaseEvent event) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(event, "event");
        try (PreparedStatement insert = unitOfWork.prepareStatement(APPEND_EVENT)) {
            int i = 0;
            insert.setObject(++i, event.caseId().value());
            insert.setInt(++i, event.revision());
            insert.setString(++i, event.kind().name());
            insert.setString(++i, event.status().name());
            insert.setString(++i, event.level().name());
            insert.setString(++i, event.queue().name());
            insert.setString(++i, event.actor().id());
            insert.setString(++i, event.actor().type().name());
            insert.setObject(++i, event.action().orElse(null), Types.OTHER);
            insert.setObject(++i, event.alert().map(RiskAlertId::value).orElse(null), Types.OTHER);
            insert.setObject(++i, event.assessment().map(RiskAssessmentId::value).orElse(null), Types.OTHER);
            insert.setString(++i, event.correlationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a risk case's history", failure);
        }
    }

    @Override
    public List<CaseAction> actions(Connection unitOfWork, RiskCaseId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + ACTION_COLUMNS + " FROM risk.risk_case_action WHERE case_id = ? ORDER BY ordinal")) {
            select.setObject(1, id.value());
            List<CaseAction> actions = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    actions.add(action(row));
                }
            }
            return List.copyOf(actions);
        } catch (SQLException failure) {
            throw failed("reading a risk case's actions", failure);
        }
    }

    @Override
    public List<CaseEvent> events(Connection unitOfWork, RiskCaseId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + EVENT_COLUMNS + " FROM risk.risk_case_event WHERE case_id = ? ORDER BY revision")) {
            select.setObject(1, id.value());
            List<CaseEvent> events = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    events.add(event(row));
                }
            }
            return List.copyOf(events);
        } catch (SQLException failure) {
            throw failed("reading a risk case's history", failure);
        }
    }

    @Override
    public List<PendingProhibition> prohibitionsDue(Connection unitOfWork, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(PROHIBITIONS_DUE)) {
            select.setInt(1, limit);
            List<PendingProhibition> due = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    due.add(new PendingProhibition(RiskCaseId.of(row.getObject("id", UUID.class)),
                            UUID.fromString(row.getString("subject_ref")), row.getObject("disposition_id", UUID.class)));
                }
            }
            return List.copyOf(due);
        } catch (SQLException failure) {
            throw failed("reading the prohibitions due", failure);
        }
    }

    @Override
    public boolean actionProposalStands(Connection unitOfWork, RiskCaseId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(ACTION_PROPOSAL_STANDS)) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a case's standing action proposals", failure);
        }
    }

    @Override
    public List<RiskCase> queue(Connection unitOfWork, Optional<CaseStatus> status, Optional<CaseKind> kind,
            Optional<RiskCaseId> after, UUID excludingParty, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(excludingParty, "excludingParty");
        if (limit < 1) {
            throw new IllegalArgumentException("a queue page reads at least one case: " + limit);
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(queueStatement(status.isPresent(),
                kind.isPresent(), after.isPresent()))) {
            int i = 0;
            select.setString(++i, excludingParty.toString());
            if (status.isPresent()) {
                select.setString(++i, status.get().name());
            }
            if (kind.isPresent()) {
                select.setString(++i, kind.get().name());
            }
            if (after.isPresent()) {
                select.setObject(++i, after.get().value());
            }
            select.setInt(++i, limit);
            List<RiskCase> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    page.add(riskCase(row));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw failed("reading the risk case queue", failure);
        }
    }

    /** The queue's statement - {@link #QUEUE} with the status, kind and cursor predicates as asked, then the order. */
    static String queueStatement(boolean status, boolean kind, boolean after) {
        return QUEUE + (status ? QUEUE_STATUS : QUEUE_OPEN) + (kind ? QUEUE_KIND : "") + (after ? QUEUE_AFTER : "")
                + QUEUE_ORDER;
    }

    @Override
    public List<Backlog> backlog(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(BACKLOG)) {
            List<Backlog> backlog = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    backlog.add(new Backlog(CaseKind.valueOf(row.getString("kind")), row.getLong("open_cases"),
                            row.getLong("oldest_seconds")));
                }
            }
            return List.copyOf(backlog);
        } catch (SQLException failure) {
            throw failed("reading the risk case backlog", failure);
        }
    }

    // -----------------------------------------------------------------

    private static <T> Array uuids(Connection unitOfWork, List<T> references, Function<T, UUID> value)
            throws SQLException {
        return unitOfWork.createArrayOf("uuid", references.stream().map(value).toArray());
    }

    private static Optional<RiskCase> single(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            return row.next() ? Optional.of(riskCase(row)) : Optional.empty();
        }
    }

    private static RiskCase riskCase(ResultSet row) throws SQLException {
        Optional<RiskCase.Person> takenBy = person(row, "taken_by", "taken_by_type");
        Optional<RiskCase.DispositionSlot> slot = Optional.ofNullable(row.getObject("disposition_id", UUID.class))
                .isEmpty()
                ? Optional.empty()
                : Optional.of(new RiskCase.DispositionSlot(
                        row.getObject("disposition_id", UUID.class),
                        Disposition.valueOf(row.getString("disposition")),
                        row.getBoolean("disposition_prohibits"),
                        person(row, "disposition_proposed_by", "disposition_proposed_by_type").orElseThrow(),
                        instant(row, "disposition_proposed_at"),
                        person(row, "disposition_approved_by", "disposition_approved_by_type"),
                        instant(row, "disposed_at")));
        return new RiskCase(
                RiskCaseId.of(row.getObject("id", UUID.class)),
                CaseKind.valueOf(row.getString("kind")),
                new SignalSubject(SubjectKind.valueOf(row.getString("subject_kind")), row.getString("subject_ref")),
                CaseStatus.valueOf(row.getString("status")),
                row.getInt("revision"),
                CaseLevel.valueOf(row.getString("level")),
                CaseQueue.valueOf(row.getString("queue")),
                takenBy,
                row.getInt("sla_hours"),
                row.getTimestamp("sla_deadline").toInstant(),
                Optional.ofNullable(row.getObject("opening_alert_id", UUID.class)).map(RiskAlertId::of),
                Optional.ofNullable(row.getObject("opening_assessment_id", UUID.class)).map(RiskAssessmentId::of),
                Optional.ofNullable(row.getObject("predecessor_case_id", UUID.class)).map(RiskCaseId::of),
                row.getTimestamp("opened_at").toInstant(),
                slot,
                row.getString("correlation_id"));
    }

    private static CaseAction action(ResultSet row) throws SQLException {
        return new CaseAction(
                row.getObject("id", UUID.class),
                RiskCaseId.of(row.getObject("case_id", UUID.class)),
                row.getInt("ordinal"),
                CaseActionKind.valueOf(row.getString("kind")),
                new RiskCase.Person(row.getString("actor_id"), ActorType.valueOf(row.getString("actor_type"))),
                Optional.ofNullable(row.getString("text")),
                new AlertEvidence(
                        references(row, "evidence_signal_ids").stream().map(RiskSignalId::of).toList(),
                        references(row, "evidence_assessment_ids").stream().map(RiskAssessmentId::of).toList(),
                        references(row, "evidence_journal_entry_ids")),
                Optional.ofNullable(row.getString("disposition")).map(Disposition::valueOf),
                row.getBoolean("prohibits"),
                Optional.ofNullable(row.getString("queue")).map(CaseQueue::valueOf),
                row.getString("correlation_id"),
                instant(row, "recorded_at"));
    }

    private static CaseEvent event(ResultSet row) throws SQLException {
        return new CaseEvent(
                RiskCaseId.of(row.getObject("case_id", UUID.class)),
                row.getInt("revision"),
                CaseEventKind.valueOf(row.getString("kind")),
                CaseStatus.valueOf(row.getString("status")),
                CaseLevel.valueOf(row.getString("level")),
                CaseQueue.valueOf(row.getString("queue")),
                new RiskCase.Person(row.getString("actor_id"), ActorType.valueOf(row.getString("actor_type"))),
                Optional.ofNullable(row.getObject("action_id", UUID.class)),
                Optional.ofNullable(row.getObject("alert_id", UUID.class)).map(RiskAlertId::of),
                Optional.ofNullable(row.getObject("assessment_id", UUID.class)).map(RiskAssessmentId::of),
                row.getString("correlation_id"),
                instant(row, "recorded_at"));
    }

    private static Optional<RiskCase.Person> person(ResultSet row, String id, String type) throws SQLException {
        String who = row.getString(id);
        return who == null ? Optional.empty() : Optional.of(new RiskCase.Person(who, ActorType.valueOf(row.getString(type))));
    }

    private static List<UUID> references(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        List<UUID> references = new ArrayList<>();
        for (Object reference : (Object[]) array.getArray()) {
            references.add((UUID) reference);
        }
        return List.copyOf(references);
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
