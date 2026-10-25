package com.finapp.risk.testing;

import com.finapp.risk.FilingOutcome;
import com.finapp.risk.RegulatoryReport;
import com.finapp.risk.RegulatoryReportId;
import com.finapp.risk.RegulatoryReportStatus;
import com.finapp.risk.RegulatoryReportStore;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskCaseId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link RegulatoryReportStore} in memory for the hermetic suites (`P13-TSK-021`) - {@code risk V016}'s arbiters as the
 * statements apply them: the birth under the one-live-or-accepted unique (a second live report of a case is no row; a
 * second id throws, as a primary-key collision does), every edge conditional on the state it leaves, the drive's claim only
 * of a {@code FILED} permit that has come on the database clock {@link #now} (which the suite moves), one attempt counted
 * and the permit a lease ahead, the record conditional on {@code FILED} and the claimed attempt, the provider's reference
 * kept once given, one history row per status reached. The monitored transactions a draft may reference are planted
 * ({@link #monitored}). <strong>Not modelled, deliberately</strong>: the trigger's cross-table preconditions, the frozen
 * content (no edge here can write it) and the four-eyes {@code CHECK} - the domain's own refusals are what the hermetic
 * suites prove; the database's are {@code RegulatoryReportDatabaseTest}'s (written and owed, C5). {@link #log} records
 * each operation in order. Single-threaded; {@link #snapshot}/{@link #restore} roll a fake transaction back.
 */
public final class InMemoryRegulatoryReportStore implements RegulatoryReportStore {

    /** A monitored transaction a draft may reference: its id, the journal entry it was born from, the accounts it touches. */
    public record Monitored(UUID id, UUID journalEntry, UUID subjectAccount, Optional<UUID> customerCounterparty) {
        public Monitored {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(journalEntry, "journalEntry");
            Objects.requireNonNull(subjectAccount, "subjectAccount");
            Objects.requireNonNull(customerCounterparty, "customerCounterparty");
        }
    }

    /** One history row. */
    public record Event(RegulatoryReportId report, RegulatoryReportStatus status, RiskCase.Person actor,
            String correlationId) {}

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-25T12:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    /** The monitored transactions, in the journal's order - the suite plants them. */
    public final List<Monitored> monitored = new ArrayList<>();

    private Map<RegulatoryReportId, RegulatoryReport> rows = new LinkedHashMap<>();
    private List<Event> events = new ArrayList<>();

    /** Every report stored, in insert order. */
    public List<RegulatoryReport> all() {
        return List.copyOf(rows.values());
    }

    /** Every history row, in insert order. */
    public List<Event> events() {
        return List.copyOf(events);
    }

    /** A copy of the rows and the history - a fake transaction's begin. */
    public Object snapshot() {
        return new Object[] {new LinkedHashMap<>(rows), new ArrayList<>(events)};
    }

    /** The rows and the history put back as {@code snapshot} held them - a fake transaction's rollback. */
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        Object[] held = (Object[]) snapshot;
        rows = new LinkedHashMap<>((Map<RegulatoryReportId, RegulatoryReport>) held[0]);
        events = new ArrayList<>((List<Event>) held[1]);
    }

    @Override
    public Optional<RegulatoryReport> liveOf(Connection unitOfWork, RiskCaseId caseId) {
        log.add("liveOf");
        return rows.values().stream().filter(row -> row.caseId().equals(caseId) && row.status().occupiesItsCase())
                .findFirst();
    }

    @Override
    public Optional<RegulatoryReport> insert(Connection unitOfWork, NewReport report) {
        log.add("insert");
        if (rows.containsKey(report.id())) {
            throw new IllegalStateException("regulatory_report_pk: a primary-key collision is never a conflict");
        }
        if (liveOf(unitOfWork, report.caseId()).isPresent()) {
            return Optional.empty();
        }
        RegulatoryReport born = new RegulatoryReport(report.id(), report.caseId(), report.content(),
                RegulatoryReportStatus.DRAFT, report.drafter(), Optional.of(now), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0, report.correlationId());
        rows.put(born.id(), born);
        events.add(new Event(born.id(), RegulatoryReportStatus.DRAFT, report.drafter(), report.correlationId()));
        return Optional.of(born);
    }

    @Override
    public Optional<RegulatoryReport> lock(Connection unitOfWork, RegulatoryReportId id) {
        log.add("lock report");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RegulatoryReport> find(Connection unitOfWork, RegulatoryReportId id) {
        log.add("find report");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RegulatoryReport> approve(Connection unitOfWork, RegulatoryReportId id, RiskCase.Person approver,
            String correlationId) {
        log.add("approve");
        RegulatoryReport row = rows.get(id);
        if (row == null || row.status() != RegulatoryReportStatus.DRAFT) {
            return Optional.empty();
        }
        RegulatoryReport approved = with(row, RegulatoryReportStatus.APPROVED, Optional.of(approver), Optional.of(now),
                Optional.empty(), Optional.empty(), row.providerReference(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0);
        return moved(approved, approver, correlationId);
    }

    @Override
    public Optional<RegulatoryReport> file(Connection unitOfWork, RegulatoryReportId id, RiskCase.Person filer,
            String correlationId) {
        log.add("file");
        RegulatoryReport row = rows.get(id);
        if (row == null || row.status() != RegulatoryReportStatus.APPROVED) {
            return Optional.empty();
        }
        RegulatoryReport filed = with(row, RegulatoryReportStatus.FILED, row.approvedBy(), row.approvedAt(),
                Optional.of(filer), Optional.of(now), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(now), 0);
        return moved(filed, filer, correlationId);
    }

    @Override
    public Optional<RegulatoryReport> claim(Connection unitOfWork, RegulatoryReportId id, Duration lease) {
        log.add("claim");
        RegulatoryReport row = rows.get(id);
        if (row == null || !due(row)) {
            return Optional.empty();
        }
        return Optional.of(claimed(row, lease));
    }

    @Override
    public Optional<RegulatoryReport> claimNextDue(Connection unitOfWork, Duration lease) {
        log.add("claimNextDue");
        return rows.values().stream().filter(this::due)
                .min(Comparator.comparing((RegulatoryReport row) -> row.nextInquiryAt().orElseThrow())
                        .thenComparing(row -> row.id().value()))
                .map(row -> claimed(row, lease));
    }

    @Override
    public Optional<RegulatoryReport> record(Connection unitOfWork, RegulatoryReportId id, int attempt,
            FilingOutcome outcome, Duration inquiryInterval, RiskCase.Person actor, String correlationId) {
        log.add("record " + outcome.kind());
        RegulatoryReport row = rows.get(id);
        if (row == null || row.status() != RegulatoryReportStatus.FILED || row.attempts() != attempt) {
            return Optional.empty();
        }
        Optional<String> reference = row.providerReference().or(outcome::providerReference);
        RegulatoryReport recorded = switch (outcome.kind()) {
            case ACKNOWLEDGED -> with(row, RegulatoryReportStatus.ACKNOWLEDGED, row.approvedBy(), row.approvedAt(),
                    row.filedBy(), row.filedAt(), reference, Optional.empty(), Optional.of(now), Optional.empty(),
                    row.attempts());
            case REJECTED -> with(row, RegulatoryReportStatus.REJECTED, row.approvedBy(), row.approvedAt(),
                    row.filedBy(), row.filedAt(), reference, outcome.rejectionCode(), Optional.of(now),
                    Optional.empty(), row.attempts());
            case UNKNOWN -> with(row, RegulatoryReportStatus.FILED, row.approvedBy(), row.approvedAt(), row.filedBy(),
                    row.filedAt(), reference, Optional.empty(), Optional.empty(),
                    Optional.of(now.plus(inquiryInterval)), row.attempts());
        };
        if (!recorded.status().concluded()) {
            rows.put(id, recorded);
            return Optional.of(recorded);
        }
        return moved(recorded, actor, correlationId);
    }

    @Override
    public Referenced referenced(Connection unitOfWork, List<UUID> journalEntries, Set<UUID> accounts, int limit) {
        log.add("referenced");
        List<UUID> matched = monitored.stream()
                .filter(transaction -> journalEntries.contains(transaction.journalEntry()))
                .filter(transaction -> accounts.contains(transaction.subjectAccount())
                        || transaction.customerCounterparty().filter(accounts::contains).isPresent())
                .map(Monitored::id)
                .toList();
        return new Referenced(matched.stream().limit(limit).toList(), matched.size());
    }

    // -----------------------------------------------------------------

    private boolean due(RegulatoryReport row) {
        return row.status() == RegulatoryReportStatus.FILED && !row.nextInquiryAt().orElseThrow().isAfter(now);
    }

    private RegulatoryReport claimed(RegulatoryReport row, Duration lease) {
        RegulatoryReport claimed = with(row, RegulatoryReportStatus.FILED, row.approvedBy(), row.approvedAt(),
                row.filedBy(), row.filedAt(), row.providerReference(), Optional.empty(), Optional.empty(),
                Optional.of(now.plus(lease)), row.attempts() + 1);
        rows.put(row.id(), claimed);
        return claimed;
    }

    private Optional<RegulatoryReport> moved(RegulatoryReport moved, RiskCase.Person actor, String correlationId) {
        if (events.stream().anyMatch(event -> event.report().equals(moved.id()) && event.status() == moved.status())) {
            throw new IllegalStateException("regulatory_report_event_pk: each status is reached once");
        }
        rows.put(moved.id(), moved);
        events.add(new Event(moved.id(), moved.status(), actor, correlationId));
        return Optional.of(moved);
    }

    private static RegulatoryReport with(RegulatoryReport row, RegulatoryReportStatus status,
            Optional<RiskCase.Person> approvedBy, Optional<Instant> approvedAt, Optional<RiskCase.Person> filedBy,
            Optional<Instant> filedAt, Optional<String> providerReference, Optional<String> rejectionCode,
            Optional<Instant> concludedAt, Optional<Instant> nextInquiryAt, int attempts) {
        return new RegulatoryReport(row.id(), row.caseId(), row.content(), status, row.draftedBy(), row.draftedAt(),
                approvedBy, approvedAt, filedBy, filedAt, providerReference, rejectionCode, concludedAt, nextInquiryAt,
                attempts, row.correlationId());
    }
}
