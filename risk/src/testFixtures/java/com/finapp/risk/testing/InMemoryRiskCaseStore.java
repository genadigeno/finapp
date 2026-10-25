package com.finapp.risk.testing;

import com.finapp.risk.CaseAction;
import com.finapp.risk.CaseEvent;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CaseLevel;
import com.finapp.risk.CaseQueue;
import com.finapp.risk.CaseStatus;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskCaseStore;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A {@link RiskCaseStore} in memory for the hermetic suites (`P13-TSK-016`) - {@code risk V012}'s arbiters as the
 * statements apply them: the birth under the one-open unique (a second open case of a subject and kind is no row; a
 * second id throws, as a primary-key collision does), every step conditional on the revision it moves from and moving
 * it by one, nothing out of {@code DISPOSED}, one action per ordinal and one history row per revision, the stamps on the
 * database clock {@link #now}, which the suite moves. <strong>Not modelled, deliberately</strong>: the trigger's edges and
 * frozen facts and the four-eyes {@code CHECK} beyond what the {@link RiskCase} value refuses - the domain's own
 * refusals are what the hermetic suites prove; the database's are {@code CaseDatabaseTest}'s (written and owed, C5).
 * {@link #log} records each operation in order (a suite holds the lock order with it). Single-threaded.
 */
public final class InMemoryRiskCaseStore implements RiskCaseStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-25T12:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    /** Whether a disposition already has its profile version - the prohibitions' census; the suite wires it. */
    public Predicate<UUID> applied = disposition -> false;

    /** Whether an action proposed on a case stands {@code PROPOSED} - `P13-TSK-017`'s; the suite wires its action store. */
    public Predicate<RiskCaseId> actionStands = caseId -> false;

    private final Map<RiskCaseId, RiskCase> rows = new LinkedHashMap<>();
    private final List<CaseAction> actions = new ArrayList<>();
    private final List<CaseEvent> events = new ArrayList<>();

    /** Every case stored, in insert order. */
    public List<RiskCase> all() {
        return List.copyOf(rows.values());
    }

    /** Every action stored, in insert order. */
    public List<CaseAction> allActions() {
        return List.copyOf(actions);
    }

    /** Every history row stored, in insert order. */
    public List<CaseEvent> allEvents() {
        return List.copyOf(events);
    }

    @Override
    public Optional<RiskCase> lockOpen(Connection unitOfWork, SignalSubject subject, CaseKind kind) {
        log.add("lockOpen " + kind);
        return openOf(subject, kind);
    }

    @Override
    public Optional<RiskCase> insert(Connection unitOfWork, NewCase newCase) {
        log.add("insert " + newCase.kind());
        if (rows.containsKey(newCase.id())) {
            throw new IllegalStateException("risk_case_pk: a primary-key collision is never a conflict");
        }
        if (openOf(newCase.subject(), newCase.kind()).isPresent()) {
            return Optional.empty();
        }
        RiskCase born = new RiskCase(newCase.id(), newCase.kind(), newCase.subject(), CaseStatus.OPEN, 0, CaseLevel.L1,
                CaseQueue.FIRST_LINE, Optional.empty(), newCase.slaHours(), now.plusSeconds(3600L * newCase.slaHours()),
                newCase.openingAlert(), newCase.openingAssessment(), newCase.predecessor(), now, Optional.empty(),
                newCase.correlationId());
        rows.put(born.id(), born);
        return Optional.of(born);
    }

    @Override
    public Optional<RiskCase> lock(Connection unitOfWork, RiskCaseId id) {
        log.add("lock case");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RiskCase> find(Connection unitOfWork, RiskCaseId id) {
        log.add("find case");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RiskCaseId> latestDisposed(Connection unitOfWork, SignalSubject subject, CaseKind kind) {
        log.add("latestDisposed");
        return rows.values().stream()
                .filter(row -> row.subject().ref().equals(subject.ref()) && row.kind() == kind
                        && row.status() == CaseStatus.DISPOSED)
                .max(Comparator.comparing(row -> row.disposition().flatMap(RiskCase.DispositionSlot::disposedAt)
                        .orElse(Instant.MIN)))
                .map(RiskCase::id);
    }

    @Override
    public Optional<RiskCase> advance(Connection unitOfWork, RiskCaseId id, int fromRevision, Step step) {
        log.add("advance " + step.status());
        RiskCase row = rows.get(id);
        if (row == null || row.revision() != fromRevision || row.status() == CaseStatus.DISPOSED) {
            return Optional.empty();
        }
        Optional<RiskCase.DispositionSlot> slot = step.disposition().map(asked -> stamped(row, asked));
        RiskCase moved = new RiskCase(row.id(), row.kind(), row.subject(), step.status(), fromRevision + 1, step.level(),
                step.queue(), step.takenBy(), row.slaHours(), row.slaDeadline(), row.openingAlert(),
                row.openingAssessment(), row.predecessor(), row.openedAt(), slot, row.correlationId());
        rows.put(id, moved);
        return Optional.of(moved);
    }

    @Override
    public void appendAction(Connection unitOfWork, CaseAction action) {
        log.add("action " + action.kind());
        Objects.requireNonNull(action, "action");
        if (!rows.containsKey(action.caseId())) {
            throw new IllegalStateException("risk_case_action_case_fk");
        }
        if (actions.stream().anyMatch(stored -> stored.id().equals(action.id()))) {
            throw new IllegalStateException("risk_case_action_pk");
        }
        if (actions.stream().anyMatch(stored -> stored.caseId().equals(action.caseId())
                && stored.ordinal() == action.ordinal())) {
            throw new IllegalStateException("risk_case_action_one_per_step");
        }
        actions.add(new CaseAction(action.id(), action.caseId(), action.ordinal(), action.kind(), action.actor(),
                action.text(), action.evidence(), action.disposition(), action.prohibits(), action.queue(),
                action.correlationId(), Optional.of(now)));
    }

    @Override
    public void appendEvent(Connection unitOfWork, CaseEvent event) {
        log.add("event " + event.kind());
        Objects.requireNonNull(event, "event");
        if (events.stream().anyMatch(stored -> stored.caseId().equals(event.caseId())
                && stored.revision() == event.revision())) {
            throw new IllegalStateException("risk_case_event_pk");
        }
        RiskCase row = rows.get(event.caseId());
        if (row == null || row.revision() != event.revision() || row.status() != event.status()) {
            // risk_case_has_its_history: the row is the state the case reached at that revision.
            throw new IllegalStateException("risk_case_has_its_history");
        }
        events.add(new CaseEvent(event.caseId(), event.revision(), event.kind(), event.status(), event.level(),
                event.queue(), event.actor(), event.action(), event.alert(), event.assessment(), event.correlationId(),
                Optional.of(now)));
    }

    @Override
    public List<CaseAction> actions(Connection unitOfWork, RiskCaseId id) {
        return actions.stream().filter(action -> action.caseId().equals(id))
                .sorted(Comparator.comparingInt(CaseAction::ordinal)).toList();
    }

    @Override
    public List<CaseEvent> events(Connection unitOfWork, RiskCaseId id) {
        return events.stream().filter(event -> event.caseId().equals(id))
                .sorted(Comparator.comparingInt(CaseEvent::revision)).toList();
    }

    @Override
    public List<PendingProhibition> prohibitionsDue(Connection unitOfWork, int limit) {
        log.add("prohibitionsDue");
        return rows.values().stream()
                .filter(row -> row.status() == CaseStatus.DISPOSED)
                .flatMap(row -> row.disposition().filter(RiskCase.DispositionSlot::prohibits)
                        .filter(slot -> !applied.test(slot.id()))
                        .map(slot -> new PendingProhibition(row.id(), UUID.fromString(row.subject().ref()), slot.id()))
                        .stream())
                .limit(limit)
                .toList();
    }

    @Override
    public boolean actionProposalStands(Connection unitOfWork, RiskCaseId id) {
        log.add("actionProposalStands");
        return actionStands.test(id);
    }

    @Override
    public List<RiskCase> queue(Connection unitOfWork, Optional<CaseStatus> status, Optional<CaseKind> kind,
            Optional<RiskCaseId> after, UUID excludingParty, int limit) {
        log.add("queue " + status.map(Enum::name).orElse("OPEN_ANY"));
        if (limit < 1) {
            throw new IllegalArgumentException("a queue page reads at least one case: " + limit);
        }
        Comparator<RiskCase> oldestFirst = Comparator.comparing(RiskCase::openedAt)
                .thenComparing(row -> row.id().value());
        Optional<RiskCase> cursor = after.map(rows::get);
        return rows.values().stream()
                .filter(row -> status.isPresent() ? row.status() == status.get() : row.open())
                .filter(row -> kind.isEmpty() || row.kind() == kind.get())
                .filter(row -> !(row.subject().kind() == SubjectKind.PARTY
                        && row.subject().ref().equals(excludingParty.toString())))
                .filter(row -> cursor.isEmpty() || oldestFirst.compare(row, cursor.get()) > 0)
                .sorted(oldestFirst)
                .limit(limit)
                .toList();
    }

    @Override
    public List<Backlog> backlog(Connection unitOfWork) {
        log.add("backlog");
        Map<CaseKind, List<RiskCase>> open = new java.util.EnumMap<>(CaseKind.class);
        rows.values().stream().filter(RiskCase::open)
                .forEach(row -> open.computeIfAbsent(row.kind(), k -> new ArrayList<>()).add(row));
        return open.entrySet().stream()
                .map(entry -> new Backlog(entry.getKey(), entry.getValue().size(), Math.max(0, java.time.Duration
                        .between(entry.getValue().stream().map(RiskCase::openedAt).min(Comparator.naturalOrder())
                                .orElseThrow(), now).toSeconds())))
                .toList();
    }

    private Optional<RiskCase> openOf(SignalSubject subject, CaseKind kind) {
        return rows.values().stream()
                .filter(row -> row.subject().ref().equals(subject.ref()) && row.kind() == kind && row.open())
                .findFirst();
    }

    /** The slot as the trigger stamps it: the proposal's instant kept, the decision's stamped now. */
    private RiskCase.DispositionSlot stamped(RiskCase row, RiskCase.DispositionSlot asked) {
        Optional<Instant> proposedAt = row.disposition().filter(standing -> standing.id().equals(asked.id()))
                .flatMap(RiskCase.DispositionSlot::proposedAt).or(() -> Optional.of(now));
        Optional<Instant> disposedAt = asked.approvedBy().isPresent() ? Optional.of(now) : Optional.empty();
        return new RiskCase.DispositionSlot(asked.id(), asked.disposition(), asked.prohibits(), asked.proposedBy(),
                proposedAt, asked.approvedBy(), disposedAt);
    }
}
