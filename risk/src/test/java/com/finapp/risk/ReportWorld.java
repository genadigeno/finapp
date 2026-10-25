package com.finapp.risk;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.InMemoryRegulatoryReportStore;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The regulatory report over fakes for the hermetic suites (`P13-TSK-021`): the real {@link RegulatoryReports} over an
 * {@link InMemoryRegulatoryReportStore} and {@link CaseWorld}'s real {@link Cases} with its stores; the party's accounts a
 * map; the authority a {@link FakeAuthority} that holds what it received <strong>by filing reference</strong> - as a real
 * FIU does - so a lost answer, a filing that never arrived and an unknown standing are all scriptable; the audit trail,
 * the outbox and the meters recorded. The AML officers are {@code CUSTOMER}-typed, as the session interceptor establishes
 * every operator (the routed defect `-016`/`-018` met).
 */
final class ReportWorld {

    static final Connection UOW = CaseWorld.UOW;
    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration INTERVAL = Duration.ofMinutes(15);

    final CaseWorld world = new CaseWorld();
    final InMemoryRegulatoryReportStore reportStore = new InMemoryRegulatoryReportStore();
    final Map<UUID, Set<UUID>> accountsOf = new HashMap<>();
    final FakeAuthority authority = new FakeAuthority();
    final List<EventEnvelope> events = new ArrayList<>();
    final List<Map<String, String>> payloads = new ArrayList<>();
    final List<RegulatoryReportStatus> moved = new ArrayList<>();
    final List<RegulatoryFilingProvider.Outcome> answered = new ArrayList<>();
    final RegulatoryReports reports;

    ReportWorld() {
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        MonitoredParties parties = new MonitoredParties() {
            @Override
            public Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId) {
                return Optional.empty();
            }

            @Override
            public Accounts accountsOf(Connection unitOfWork, UUID partyId) {
                List<UUID> held = List.copyOf(accountsOf.getOrDefault(partyId, Set.of()));
                return Accounts.of(held, held.stream().map(account -> Instant.parse("2026-01-01T00:00:00Z")).toList());
            }
        };
        ReportObserver observer = new ReportObserver() {
            @Override
            public void moved(RegulatoryReportStatus status) {
                ReportWorld.this.moved.add(status);
            }

            @Override
            public void answered(RegulatoryFilingProvider.Outcome outcome) {
                ReportWorld.this.answered.add(outcome);
            }
        };
        reports = new RegulatoryReports(reportStore, world.cases, world.caseStore, world.alertStore, parties,
                (unitOfWork, actor) -> Optional.ofNullable(world.partyOfActor.get(actor.id())), authority,
                (unitOfWork, record) -> world.audit.add(record), outbox, EvaluationWorld.IDS, EvaluationWorld.CLOCK,
                observer);
    }

    // ------------------------------------------------------------------ people

    /** An AML officer as the session establishes every operator: {@code CUSTOMER}-typed, of a fresh party of their own. */
    Actor officer() {
        Actor actor = new Actor(EvaluationWorld.IDS.next().toString(), ActorType.CUSTOMER);
        world.partyOfActor.put(actor.id(), EvaluationWorld.IDS.next());
        return actor;
    }

    /** An officer whose party is {@code party}. */
    Actor officerOf(UUID party) {
        Actor actor = new Actor(EvaluationWorld.IDS.next().toString(), ActorType.CUSTOMER);
        world.partyOfActor.put(actor.id(), party);
        return actor;
    }

    static CorrelationId correlation() {
        return CorrelationId.of("report-flow-" + EvaluationWorld.IDS.next());
    }

    // ------------------------------------------------------------------ cases

    /** A disposed case and what the report should draw from it. */
    record Confirmed(RiskCase riskCase, UUID party, List<UUID> accounts, List<UUID> transactions,
            List<UUID> foreignTransactions) {
        RiskCaseId id() {
            return riskCase.id();
        }
    }

    /**
     * An AML case of a fresh party, opened by the monitoring's two alerts (structuring over three journal entries, rapid
     * in-out over two of them and a fourth), taken, and disposed {@code CONFIRMED_SUSPICIOUS} by two analysts; the party's
     * two accounts; the four entries' monitored transactions planted - three touching the party's accounts (one as a
     * customer counterparty), one touching only a stranger's.
     */
    Confirmed confirmedSuspicious() {
        return disposed(CaseKind.AML, Disposition.CONFIRMED_SUSPICIOUS);
    }

    /** As {@link #confirmedSuspicious}, of {@code kind} (an AML case is opened by its alerts; another by hand). */
    Confirmed disposed(CaseKind kind, Disposition disposition) {
        UUID party = EvaluationWorld.IDS.next();
        UUID current = EvaluationWorld.IDS.next();
        UUID savings = EvaluationWorld.IDS.next();
        accountsOf.put(party, new LinkedHashSet<>(List.of(current, savings)));
        List<UUID> entries = List.of(EvaluationWorld.IDS.next(), EvaluationWorld.IDS.next(), EvaluationWorld.IDS.next(),
                EvaluationWorld.IDS.next());
        List<UUID> transactions = new ArrayList<>();
        List<UUID> foreign = new ArrayList<>();
        UUID first = EvaluationWorld.IDS.next();
        reportStore.monitored.add(new InMemoryRegulatoryReportStore.Monitored(first, entries.get(0), current,
                Optional.empty()));
        transactions.add(first);
        UUID second = EvaluationWorld.IDS.next();
        reportStore.monitored.add(new InMemoryRegulatoryReportStore.Monitored(second, entries.get(1),
                EvaluationWorld.IDS.next(), Optional.of(savings)));
        transactions.add(second);
        UUID stranger = EvaluationWorld.IDS.next();
        reportStore.monitored.add(new InMemoryRegulatoryReportStore.Monitored(stranger, entries.get(2),
                EvaluationWorld.IDS.next(), Optional.empty()));
        foreign.add(stranger);
        UUID third = EvaluationWorld.IDS.next();
        reportStore.monitored.add(new InMemoryRegulatoryReportStore.Monitored(third, entries.get(3), current,
                Optional.empty()));
        transactions.add(third);

        RiskCase opened;
        if (kind == CaseKind.AML) {
            SignalSubject subject = CaseWorld.party(party);
            Alerts.Raised structuring = CaseWorld.asSystem(() -> world.alerts.raise(UOW,
                    AlertWorld.structuring(subject, AlertWorld.day(), entries.subList(0, 3))));
            CaseWorld.asSystem(() -> world.alerts.raise(UOW, NewAlert.ofRule(AlertKind.AML, AlertSeverity.HIGH, subject,
                    RuleKind.RAPID_IN_OUT, AlertWorld.day(), AlertWorld.policy(),
                    AlertEvidence.journalEntries(List.of(entries.get(1), entries.get(3))), CaseWorld.correlation(),
                    CausationId.of("report-suite"))));
            opened = world.caseStore.find(UOW, structuring.folded().caseId().orElseThrow()).orElseThrow();
        } else {
            opened = world.cases.open(UOW, Cases.OpenRequest.of(kind, CaseWorld.party(party)), "worth a look",
                    world.analyst(), CaseWorld.correlation()).riskCase();
        }
        Actor alice = world.analyst();
        Actor bob = world.analyst();
        RiskCase taken = world.taken(opened, alice);
        Cases.Decided proposed = world.cases.proposeDisposition(UOW, taken.id(), taken.revision(), disposition.name(),
                false, "the pattern is confirmed", alice, CaseWorld.correlation());
        RiskCase disposed = proposed.riskCase();
        if (!disposed.disposition().orElseThrow().approvedBy().isPresent()) {
            disposed = world.cases.approveDisposition(UOW, taken.id(), proposed.dispositionId(), disposed.revision(),
                    "agreed", bob, CaseWorld.correlation()).riskCase();
        }
        return new Confirmed(disposed, party, List.of(current, savings), List.copyOf(transactions),
                List.copyOf(foreign));
    }

    // ------------------------------------------------------------------ the report's acts

    static RegulatoryReports.Draft draft(String narrative) {
        return new RegulatoryReports.Draft("EU-REF", narrative, List.of(), "the case is confirmed suspicious");
    }

    /** {@code confirmed} drafted by {@code drafter} with a plain narrative. */
    RegulatoryReport drafted(Confirmed confirmed, Actor drafter) {
        return reports.draft(UOW, confirmed.id(), draft("Funds were split below the threshold and moved out within a"
                + " day."), drafter, correlation());
    }

    /** {@code confirmed} drafted and approved by two officers. */
    RegulatoryReport approved(Confirmed confirmed) {
        RegulatoryReport drafted = drafted(confirmed, officer());
        return reports.approve(UOW, confirmed.id(), drafted.id(), "the narrative is accurate", officer(),
                correlation()).report();
    }

    /** {@code confirmed} drafted, approved and filed - {@code FILED}, nothing sent. */
    RegulatoryReport filed(Confirmed confirmed) {
        RegulatoryReport approved = approved(confirmed);
        return reports.file(UOW, confirmed.id(), approved.id(), "file it", officer(), correlation()).report();
    }

    /**
     * One drive as the platform: the claim of {@code id} (empty when nothing is due), the call, the record - the claim,
     * the call and the record each the caller's own step, as {@code app}'s driver runs them.
     */
    Optional<RegulatoryReport> drive(RegulatoryReportId id) {
        return CaseWorld.asSystem(() -> reports.claim(UOW, id, LEASE).flatMap(claimed -> {
            FilingOutcome outcome = reports.submit(claimed);
            return reports.conclude(UOW, claimed, outcome, INTERVAL, correlation());
        }));
    }

    /** The store's clock moved {@code by}. */
    void later(Duration by) {
        reportStore.now = reportStore.now.plus(by);
    }

    RegulatoryReport current(RegulatoryReportId id) {
        return reportStore.find(UOW, id).orElseThrow();
    }

    List<Map<String, String>> announced(String type) {
        List<Map<String, String>> found = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).eventType().equals(type)) {
                found.add(payloads.get(i));
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ the authority

    /** How the authority treats the next submission. */
    enum Submission {
        /** Received and acknowledged. */
        ACKNOWLEDGE,
        /** Received and refused. */
        REJECT,
        /** Received, its standing not yet known. */
        UNKNOWN,
        /** Received - and the answer lost on the wire (the call throws). */
        LOSE_ANSWER,
        /** Never reached the authority (the call throws). */
        UNAVAILABLE
    }

    /**
     * An authority that, as a real FIU, keeps what it received <strong>by filing reference</strong> and deduplicates on
     * it; scripted per submission; an inquiry answers what it holds - or {@code NOT_RECEIVED}.
     */
    static final class FakeAuthority implements RegulatoryFilingProvider {

        final Deque<Submission> submissions = new ArrayDeque<>();
        /** What a received filing's later inquiry answers - unless it was refused. */
        Outcome onInquiry = Outcome.ACKNOWLEDGED;
        final Map<String, Outcome> received = new LinkedHashMap<>();
        final List<String> files = new ArrayList<>();
        final List<String> inquiries = new ArrayList<>();
        final List<ReportContent> contents = new ArrayList<>();

        @Override
        public Optional<Declaration> declared(String jurisdiction) {
            return "EU-REF".equals(jurisdiction) ? Optional.of(new Declaration("EU-REF", 1, "FINAPP-TEST-ENTITY"))
                    : Optional.empty();
        }

        @Override
        public Receipt file(Request request) {
            files.add(request.filingReference());
            contents.add(request.content());
            Submission how = submissions.isEmpty() ? Submission.ACKNOWLEDGE : submissions.pop();
            if (how == Submission.UNAVAILABLE) {
                throw new IllegalStateException("the authority is unreachable");
            }
            // Deduplicated on the reference: a second submission under it is the first filing.
            Outcome standing = received.computeIfAbsent(request.filingReference(), reference -> switch (how) {
                case REJECT -> Outcome.REJECTED;
                case UNKNOWN -> Outcome.UNKNOWN;
                default -> Outcome.ACKNOWLEDGED;
            });
            if (how == Submission.LOSE_ANSWER) {
                throw new IllegalStateException("the answer was lost");
            }
            return receipt(request.filingReference(), standing);
        }

        @Override
        public Receipt inquire(String filingReference) {
            inquiries.add(filingReference);
            Outcome standing = received.get(filingReference);
            if (standing == null) {
                return Receipt.notReceived();
            }
            if (standing == Outcome.UNKNOWN) {
                standing = onInquiry;
                received.put(filingReference, standing);
            }
            return receipt(filingReference, standing);
        }

        private static Receipt receipt(String reference, Outcome standing) {
            String authority = "FIU-" + reference.substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            return switch (standing) {
                case ACKNOWLEDGED -> Receipt.acknowledged(authority);
                case REJECTED -> Receipt.rejected(Optional.of(authority), "FORMAT_INVALID");
                case UNKNOWN -> Receipt.unknown(Optional.of(authority));
                case NOT_RECEIVED -> Receipt.notReceived();
            };
        }
    }
}
