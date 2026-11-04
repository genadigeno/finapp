package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RegulatoryFormLine;
import com.finapp.accounting.RegulatoryObligationStore;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * {@link RegulatorySubmissionStore} in memory (`P14-TSK-018`), holding the ranks of {@code accounting V010} the machine relies on: one live return per form and month, one standing correction per original, four eyes, the approver not sending, the edges, a resend counted against
 * its bound, a concluded row answered by no edge. A defect the database would refuse is refused here too, as an {@link IllegalStateException} naming the rank. Safe for the suites' racing threads the way the database is for its connections: an arbiter is one atomic
 * {@code putIfAbsent}, every move one atomic {@code compute} of the row - no monitor and no lock (ADR-0014's rule binds test fixtures too). The real ranks are V010's, proven by {@code RegulatorySubmissionDatabaseTest} (owed, C5).
 */
public final class InMemoryRegulatorySubmissionStore implements RegulatorySubmissionStore {

    private final Clock clock;
    private final AccountingPeriodStore periods;
    private final RegulatoryObligationStore obligations;
    private final ConcurrentHashMap<RegulatorySubmissionId, Stored> rows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RegulatorySubmissionId> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegulatorySubmissionId, RegulatorySubmissionId> corrections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegulatorySubmissionId, List<Source>> sources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegulatorySubmissionId, List<RegulatoryFormLine>> lines = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<NewAnswer> answers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EventRow> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Instant> answerTimes = new CopyOnWriteArrayList<>();

    /** What the row holds beyond the row: its text, and its permit's instant kept apart from what a {@link Row} shows. */
    private record Stored(Row row, String content, String approvalReason, Optional<Instant> horizon) {

        Stored with(Row next) {
            return new Stored(next, content, approvalReason, horizon);
        }
    }

    public InMemoryRegulatorySubmissionStore(Clock clock, AccountingPeriodStore periods, RegulatoryObligationStore obligations) {
        this.clock = clock;
        this.periods = periods;
        this.obligations = obligations;
    }

    /** The history, in order. */
    public List<EventRow> history() {
        return List.copyOf(events);
    }

    /** A line as a defect or a raw writer might leave it: what a reader's comparison exists to catch. */
    public void forgeLineForTest(RegulatorySubmissionId id, int ordinal, java.math.BigInteger minor) {
        lines.compute(id, (ignored, held) -> held.stream().map(line -> line.ordinal() != ordinal ? line
                : new RegulatoryFormLine(line.ordinal(), line.code(), line.label(), line.currency(), minor, line.scale(), line.run(), line.runSha256(), line.source(), line.figure())).toList());
    }

    /** The kept answers, in order. */
    public List<NewAnswer> keptAnswers() {
        return List.copyOf(answers);
    }

    /** The store as it stands, restored by the runnable - a rolled-back transaction. */
    public Runnable savepoint() {
        Map<RegulatorySubmissionId, Stored> heldRows = Map.copyOf(rows);
        Map<String, RegulatorySubmissionId> heldLive = Map.copyOf(live);
        Map<RegulatorySubmissionId, RegulatorySubmissionId> heldCorrections = Map.copyOf(corrections);
        Map<RegulatorySubmissionId, List<Source>> heldSources = Map.copyOf(sources);
        Map<RegulatorySubmissionId, List<RegulatoryFormLine>> heldLines = Map.copyOf(lines);
        List<NewAnswer> heldAnswers = List.copyOf(answers);
        List<EventRow> heldEvents = List.copyOf(events);
        List<Instant> heldTimes = List.copyOf(answerTimes);
        return () -> {
            rows.clear();
            rows.putAll(heldRows);
            live.clear();
            live.putAll(heldLive);
            corrections.clear();
            corrections.putAll(heldCorrections);
            sources.clear();
            sources.putAll(heldSources);
            lines.clear();
            lines.putAll(heldLines);
            answers.clear();
            answers.addAll(heldAnswers);
            events.clear();
            events.addAll(heldEvents);
            answerTimes.clear();
            answerTimes.addAll(heldTimes);
        };
    }

    /** A suite's way past the horizon: the row's horizon set in the past. */
    public void horizonPassedForTest(RegulatorySubmissionId id) {
        rows.compute(id, (key, stored) -> new Stored(stored.row(), stored.content(), stored.approvalReason(), Optional.of(Instant.EPOCH)));
    }

    /** A suite's way to a permit that has come. */
    public void permitDueForTest(RegulatorySubmissionId id) {
        rows.compute(id, (key, stored) -> stored.with(copy(stored.row(), Map.of("nextInquiryAt", Optional.of(Instant.EPOCH)))));
    }

    @Override
    public boolean insert(Connection unitOfWork, NewSubmission submission) {
        String key = liveKey(submission.jurisdiction(), submission.form(), submission.period());
        if (submission.corrects().isEmpty() && anySent(unitOfWork, submission.jurisdiction(), submission.form(), submission.period())) {
            return false;
        }
        if (live.putIfAbsent(key, submission.id()) != null) {
            return false;
        }
        if (submission.corrects().isPresent() && corrections.putIfAbsent(submission.corrects().get(), submission.id()) != null) {
            live.remove(key, submission.id());
            return false;
        }
        AccountingPeriodStore.PeriodRow period = periods.find(unitOfWork, submission.period()).orElseThrow();
        if (period.status() != PeriodStatus.CLOSED || period.revision() != submission.periodRevision()) {
            throw new IllegalStateException("regulatory_submission_permits_only_machine_edges: drawn only from a CLOSED period at its present revision");
        }
        RegulatoryObligationStore.Row obligation = obligations.find(unitOfWork, submission.obligation()).orElseThrow();
        rows.put(submission.id(), new Stored(new Row(submission.id(), submission.obligation(), submission.jurisdiction(), submission.form(), submission.formVersion(), obligation.adapter(), submission.period(), period.month(),
                submission.periodRevision(), period.revision(), period.status(), submission.corrects(), Optional.empty(), submission.contentSha256(), SubmissionStatus.DRAFT, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                submission.preparedBy().id(), clock.instant(), submission.reason(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0, 0, Optional.empty(), Optional.empty(), false,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0L, submission.correlation()), submission.content(), null, Optional.empty()));
        return true;
    }

    @Override
    public void insertSource(Connection unitOfWork, RegulatorySubmissionId id, Source source) {
        sources.compute(id, (key, held) -> {
            List<Source> next = new ArrayList<>(held == null ? List.of() : held);
            next.add(source);
            return List.copyOf(next);
        });
    }

    @Override
    public void insertLines(Connection unitOfWork, RegulatorySubmissionId id, List<RegulatoryFormLine> given) {
        lines.put(id, List.copyOf(given));
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, RegulatorySubmissionId id) {
        return Optional.ofNullable(rows.get(id)).map(this::read);
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, RegulatorySubmissionId id) {
        return find(unitOfWork, id);
    }

    @Override
    public Optional<String> content(Connection unitOfWork, RegulatorySubmissionId id) {
        return Optional.ofNullable(rows.get(id)).map(Stored::content);
    }

    @Override
    public boolean anySent(Connection unitOfWork, String jurisdiction, String form, AccountingPeriodId period) {
        return rows.values().stream().map(Stored::row)
                .anyMatch(row -> row.jurisdiction().equals(jurisdiction) && row.form().equals(form) && row.period().equals(period) && row.status().immutable());
    }

    @Override
    public Optional<RegulatorySubmissionId> correctionOf(Connection unitOfWork, RegulatorySubmissionId original) {
        return Optional.ofNullable(corrections.get(original));
    }

    @Override
    public List<Source> sources(Connection unitOfWork, RegulatorySubmissionId id) {
        return sources.getOrDefault(id, List.of());
    }

    @Override
    public List<RegulatoryFormLine> lines(Connection unitOfWork, RegulatorySubmissionId id) {
        return lines.getOrDefault(id, List.of());
    }

    @Override
    public List<AnswerRow> answers(Connection unitOfWork, RegulatorySubmissionId id) {
        List<AnswerRow> kept = new ArrayList<>();
        for (NewAnswer answer : answers) {
            if (answer.submission().equals(id)) {
                kept.add(new AnswerRow(answer.id(), answer.attempt(), answer.reading().call(), answer.reading().stated(), answer.reading().read(), answer.reading().providerReference(), answer.reading().rejectionCode(), answer.reading().anomaly(),
                        answer.flag(), answer.reading().retained(), answer.reading().rawSha256(), answer.reading().receivedBytes(), answer.reading().truncated(), clock.instant()));
            }
        }
        return kept;
    }

    @Override
    public boolean markValidated(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String outcome, boolean passed) {
        return moved(id, row -> row.status() == SubmissionStatus.DRAFT && row.validationOutcome().isEmpty(), row -> copy(row, Map.of(
                "status", passed ? SubmissionStatus.VALIDATED : SubmissionStatus.DRAFT,
                "validationOutcome", Optional.of(outcome),
                "validationPassed", Optional.of(passed),
                "validatedBy", Optional.of(actor.id()),
                "validatedAt", Optional.of(clock.instant()))));
    }

    @Override
    public boolean markApproved(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, String contentSha256, int periodRevision) {
        Row before = rows.get(id).row();
        if (before.preparedBy().equals(actor.id())) {
            throw new IllegalStateException("regulatory_submission_four_eyes");
        }
        if (!before.contentSha256().equals(contentSha256) || before.periodRevision() != periodRevision) {
            throw new IllegalStateException("regulatory_submission_approval_coherent");
        }
        return moved(id, row -> row.status() == SubmissionStatus.VALIDATED, row -> copy(row, Map.of("status", SubmissionStatus.APPROVED, "approvedBy", Optional.of(actor.id()), "approvedAt", Optional.of(clock.instant()))));
    }

    @Override
    public boolean markRefused(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason) {
        return closed(id, actor, SubmissionStatus.REFUSED, row -> row.status() == SubmissionStatus.VALIDATED || row.status() == SubmissionStatus.APPROVED);
    }

    @Override
    public boolean markWithdrawn(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason) {
        return closed(id, actor, SubmissionStatus.WITHDRAWN, row -> row.status() == SubmissionStatus.DRAFT || row.status() == SubmissionStatus.VALIDATED || row.status() == SubmissionStatus.APPROVED);
    }

    private boolean closed(RegulatorySubmissionId id, Actor actor, SubmissionStatus status, java.util.function.Predicate<Row> from) {
        boolean moved = moved(id, from, row -> copy(row, Map.of("status", status, "closedBy", Optional.of(actor.id()), "closedAt", Optional.of(clock.instant()))));
        if (moved) {
            freed(id);
            Row row = rows.get(id).row();
            row.corrects().ifPresent(original -> corrections.remove(original, id));
        }
        return moved;
    }

    private void freed(RegulatorySubmissionId id) {
        Row row = rows.get(id).row();
        live.remove(liveKey(row.jurisdiction(), row.form(), row.period()), id);
    }

    @Override
    public boolean markSubmitted(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, Duration horizon) {
        Row before = rows.get(id).row();
        if (before.approvedBy().filter(actor.id()::equals).isPresent()) {
            throw new IllegalStateException("regulatory_submission_approver_does_not_send");
        }
        Instant now = clock.instant();
        boolean moved = moved(id, row -> row.status() == SubmissionStatus.APPROVED, row -> copy(row, Map.of("status", SubmissionStatus.SUBMITTED, "submittedBy", Optional.of(actor.id()), "submittedAt", Optional.of(now), "nextInquiryAt", Optional.of(now),
                "answerHorizonAt", Optional.of(now.plus(horizon)))));
        if (moved) {
            rows.compute(id, (key, stored) -> new Stored(stored.row(), stored.content(), stored.approvalReason(), Optional.of(now.plus(horizon))));
        }
        return moved;
    }

    @Override
    public boolean markAcknowledged(Connection unitOfWork, RegulatorySubmissionId id, Optional<String> reference) {
        if (reference.isEmpty() && rows.get(id).row().providerReference().isEmpty()) {
            throw new IllegalStateException("regulatory_submission_answer_coherent");
        }
        boolean moved = moved(id, row -> row.status() == SubmissionStatus.SUBMITTED, row -> copy(row, Map.of("status", SubmissionStatus.ACKNOWLEDGED, "lastAnswer", Optional.of("ACKNOWLEDGED"), "providerReference",
                row.providerReference().isPresent() ? row.providerReference() : reference, "concludedAt", Optional.of(clock.instant()))));
        if (moved) {
            freed(id);
        }
        return moved;
    }

    @Override
    public boolean markRejected(Connection unitOfWork, RegulatorySubmissionId id, Optional<String> reference, String code) {
        boolean moved = moved(id, row -> row.status() == SubmissionStatus.SUBMITTED, row -> copy(row, Map.of("status", SubmissionStatus.REJECTED, "lastAnswer", Optional.of("REJECTED"), "providerReference",
                row.providerReference().isPresent() ? row.providerReference() : reference, "rejectionCode", Optional.of(code), "concludedAt", Optional.of(clock.instant()))));
        if (moved) {
            freed(id);
        }
        return moved;
    }

    @Override
    public boolean markUndelivered(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, int maxResends) {
        Stored stored = rows.get(id);
        boolean spentOrPast = stored.row().resends() >= maxResends || stored.horizon().filter(horizon -> !horizon.isAfter(clock.instant())).isPresent();
        boolean moved = moved(id, row -> row.status() == SubmissionStatus.SUBMITTED && row.lastAnswer().filter("NOT_RECEIVED"::equals).isPresent() && spentOrPast,
                row -> copy(row, Map.of("status", SubmissionStatus.UNDELIVERED, "closedBy", Optional.of(actor.id()), "closedAt", Optional.of(clock.instant()))));
        if (moved) {
            freed(id);
        }
        return moved;
    }

    @Override
    public Optional<Row> claim(Connection unitOfWork, RegulatorySubmissionId id, Duration lease) {
        return claimedRow(id, lease) ? find(unitOfWork, id) : Optional.empty();
    }

    @Override
    public Optional<Row> claimNextDue(Connection unitOfWork, Duration lease) {
        List<RegulatorySubmissionId> due = rows.values().stream().map(Stored::row).filter(this::due).sorted(Comparator.comparing((Row row) -> row.nextInquiryAt().orElseThrow()).thenComparing(row -> row.id().value())).map(Row::id).toList();
        for (RegulatorySubmissionId id : due) {
            if (claimedRow(id, lease)) {
                return find(unitOfWork, id);
            }
        }
        return Optional.empty();
    }

    private boolean claimedRow(RegulatorySubmissionId id, Duration lease) {
        boolean[] taken = {false};
        rows.computeIfPresent(id, (key, stored) -> {
            if (!due(stored.row())) {
                return stored;
            }
            taken[0] = true;
            return stored.with(copy(stored.row(), Map.of("attempts", stored.row().attempts() + 1, "nextInquiryAt", Optional.of(clock.instant().plus(lease)))));
        });
        return taken[0];
    }

    private boolean due(Row row) {
        return row.status() == SubmissionStatus.SUBMITTED && row.nextInquiryAt().filter(at -> !at.isAfter(clock.instant())).isPresent();
    }

    @Override
    public boolean permitResend(Connection unitOfWork, RegulatorySubmissionId id, int attempt, int maxResends) {
        return moved(id, row -> row.status() == SubmissionStatus.SUBMITTED && row.attempts() == attempt && row.lastAnswer().filter("NOT_RECEIVED"::equals).isPresent() && row.resends() < maxResends,
                row -> copy(row, Map.of("resends", row.resends() + 1, "lastAnswer", Optional.of("RESENDING"))));
    }

    @Override
    public boolean await(Connection unitOfWork, RegulatorySubmissionId id, int attempt, Duration interval, String lastAnswer, Optional<String> reference) {
        return moved(id, row -> row.status() == SubmissionStatus.SUBMITTED && row.attempts() == attempt, row -> copy(row, Map.of("nextInquiryAt", Optional.of(clock.instant().plus(interval)), "lastAnswer", Optional.of(lastAnswer), "providerReference",
                row.providerReference().isPresent() ? row.providerReference() : reference)));
    }

    @Override
    public void appendAnswer(Connection unitOfWork, NewAnswer answer) {
        answers.add(answer);
        answerTimes.add(clock.instant());
    }

    @Override
    public void appendEvent(Connection unitOfWork, EventRow event) {
        if (event.act() == Act.APPROVED && (event.preparedBy().isEmpty() || event.preparedBy().get().equals(event.actor().id()))) {
            throw new IllegalStateException("regulatory_submission_event_four_eyes");
        }
        events.add(event);
    }

    @Override
    public List<Row> standing(Connection unitOfWork, int limit) {
        return rows.values().stream().map(this::read).filter(row -> row.status().live()
                || ((row.status() == SubmissionStatus.REJECTED || row.status() == SubmissionStatus.UNDELIVERED) && !corrections.containsKey(row.id()))
                || (row.status() == SubmissionStatus.ACKNOWLEDGED && row.monthMoved() && !corrections.containsKey(row.id())))
                .sorted(Comparator.comparing(Row::preparedAt).thenComparing(row -> row.id().value())).limit(limit).toList();
    }

    @Override
    public List<Row> awaitingApproval(Connection unitOfWork, String reader, int limit) {
        return rows.values().stream().map(this::read).filter(row -> row.status() == SubmissionStatus.VALIDATED && !row.preparedBy().equals(reader))
                .sorted(Comparator.comparing((Row row) -> row.validatedAt().orElseThrow()).thenComparing(row -> row.id().value())).limit(limit).toList();
    }

    @Override
    public Attention attention(Connection connection, int maxResends) {
        List<Row> all = rows.values().stream().map(this::read).toList();
        long overdue = all.stream().filter(row -> row.status() == SubmissionStatus.SUBMITTED && row.pastHorizon()).count();
        long exhausted = all.stream().filter(row -> row.status() == SubmissionStatus.SUBMITTED && row.lastAnswer().filter("NOT_RECEIVED"::equals).isPresent() && row.resends() >= maxResends).count();
        long overtaken = all.stream().filter(row -> (row.status() == SubmissionStatus.SUBMITTED || row.status() == SubmissionStatus.ACKNOWLEDGED) && row.monthMoved() && !corrections.containsKey(row.id())).count();
        long stale = all.stream().filter(row -> (row.status() == SubmissionStatus.DRAFT || row.status() == SubmissionStatus.VALIDATED || row.status() == SubmissionStatus.APPROVED) && row.monthMoved()).count();
        long contrary = answers.stream().filter(answer -> answer.flag().filter(Flag.CONTRARY::equals).isPresent()).count();
        return new Attention(overdue, exhausted, overtaken, stale, contrary);
    }

    // ------------------------------------------------------------------

    private boolean moved(RegulatorySubmissionId id, java.util.function.Predicate<Row> from, UnaryOperator<Row> to) {
        boolean[] done = {false};
        rows.computeIfPresent(id, (key, stored) -> {
            if (!from.test(stored.row())) {
                return stored;
            }
            done[0] = true;
            return stored.with(to.apply(stored.row()));
        });
        return done[0];
    }

    /** The row as a reader sees it: the month's revision and status as they stand, its correction, its contrary answers. */
    private Row read(Stored stored) {
        Row row = stored.row();
        AccountingPeriodStore.PeriodRow period = periods.find(null, row.period()).orElseThrow();
        boolean past = stored.horizon().filter(horizon -> !horizon.isAfter(clock.instant())).isPresent();
        long contrary = answers.stream().filter(answer -> answer.submission().equals(row.id()) && answer.flag().filter(Flag.CONTRARY::equals).isPresent()).count();
        return new Row(row.id(), row.obligation(), row.jurisdiction(), row.form(), row.formVersion(), row.adapter(), row.period(), row.month(), row.periodRevision(), period.revision(), period.status(), row.corrects(),
                Optional.ofNullable(corrections.get(row.id())), row.contentSha256(), row.status(), row.validationOutcome(), row.validationPassed(), row.validatedBy(), row.validatedAt(), row.preparedBy(), row.preparedAt(), row.preparationReason(),
                row.approvedBy(), row.approvedAt(), row.submittedBy(), row.submittedAt(), row.closedBy(), row.closedAt(), row.attempts(), row.resends(), row.nextInquiryAt(), row.answerHorizonAt(), past, row.lastAnswer(), row.providerReference(),
                row.rejectionCode(), row.concludedAt(), contrary, row.correlation());
    }

    private static String liveKey(String jurisdiction, String form, AccountingPeriodId period) {
        return jurisdiction + "|" + form + "|" + period.value();
    }

    @SuppressWarnings("unchecked")
    private static Row copy(Row row, Map<String, Object> changed) {
        Function<String, Object> at = changed::get;
        return new Row(row.id(), row.obligation(), row.jurisdiction(), row.form(), row.formVersion(), row.adapter(), row.period(), row.month(), row.periodRevision(), row.periodRevisionNow(), row.periodStatusNow(), row.corrects(), row.correctedBy(),
                row.contentSha256(),
                changed.containsKey("status") ? (SubmissionStatus) at.apply("status") : row.status(),
                changed.containsKey("validationOutcome") ? (Optional<String>) at.apply("validationOutcome") : row.validationOutcome(),
                changed.containsKey("validationPassed") ? (Optional<Boolean>) at.apply("validationPassed") : row.validationPassed(),
                changed.containsKey("validatedBy") ? (Optional<String>) at.apply("validatedBy") : row.validatedBy(),
                changed.containsKey("validatedAt") ? (Optional<Instant>) at.apply("validatedAt") : row.validatedAt(),
                row.preparedBy(), row.preparedAt(), row.preparationReason(),
                changed.containsKey("approvedBy") ? (Optional<String>) at.apply("approvedBy") : row.approvedBy(),
                changed.containsKey("approvedAt") ? (Optional<Instant>) at.apply("approvedAt") : row.approvedAt(),
                changed.containsKey("submittedBy") ? (Optional<String>) at.apply("submittedBy") : row.submittedBy(),
                changed.containsKey("submittedAt") ? (Optional<Instant>) at.apply("submittedAt") : row.submittedAt(),
                changed.containsKey("closedBy") ? (Optional<String>) at.apply("closedBy") : row.closedBy(),
                changed.containsKey("closedAt") ? (Optional<Instant>) at.apply("closedAt") : row.closedAt(),
                changed.containsKey("attempts") ? (Integer) at.apply("attempts") : row.attempts(),
                changed.containsKey("resends") ? (Integer) at.apply("resends") : row.resends(),
                changed.containsKey("nextInquiryAt") ? (Optional<Instant>) at.apply("nextInquiryAt") : row.nextInquiryAt(),
                changed.containsKey("answerHorizonAt") ? (Optional<Instant>) at.apply("answerHorizonAt") : row.answerHorizonAt(),
                row.pastHorizon(),
                changed.containsKey("lastAnswer") ? (Optional<String>) at.apply("lastAnswer") : row.lastAnswer(),
                changed.containsKey("providerReference") ? (Optional<String>) at.apply("providerReference") : row.providerReference(),
                changed.containsKey("rejectionCode") ? (Optional<String>) at.apply("rejectionCode") : row.rejectionCode(),
                changed.containsKey("concludedAt") ? (Optional<Instant>) at.apply("concludedAt") : row.concludedAt(),
                row.contraryAnswers(), row.correlation());
    }
}
