package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The regulatory report - T-rep and the filing's drive (`P13-TSK-021`; {@code risk V016}; {@code PHASE_13_PLAN.md} sections
 * 5, 7.1 T-rep, 7.2 (R4), 9, 10, 11, 12.9, 14; {@code RISK_LIFECYCLES.md} section 4.8; ADR-0111 decisions 5-7;
 * {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-RSK-08}, {@code INV-AUD-02}, {@code INV-AUD-04}).
 *
 * <h2>T-rep: a person's acts (each its caller's one transaction, no money row)</h2>
 *
 * <ul>
 *   <li><strong>{@link #draft}</strong> - an {@code AML_REPORT_APPROVE} holder, a person placed in a party; <strong>(R4) the
 *       case row {@code FOR UPDATE}</strong> through the case's own protocol ({@link Cases#lockForAct}: the subject's
 *       accountable party placed and not the drafter's, fail closed); the case an {@code AML} case disposed
 *       {@code CONFIRMED_SUSPICIOUS} of a party, with no live or accepted report ({@link ReportNotFileable}); the content
 *       drawn from the case ({@code DESIGN-021} D5): the grounds its attached alerts' rule codes, the transactions the
 *       monitored transactions born from the journal entries its alerts and evidence acts name and touching one of the
 *       party's accounts (the first {@link ReportContent#TRANSACTIONS_BOUND}), the accounts the party's
 *       ({@link MonitoredParties}), the jurisdiction's format and the reporting entity's reference the adapter's
 *       declaration; the drafter's narrative screened. Then the case's {@code REPORT_DRAFTED} investigator action -
 *       its ordinal after the case's last (a disposed case never steps, D2) - and the report born {@code DRAFT} under
 *       that action's id: the report and the case action in one transaction.</li>
 *   <li><strong>{@link #approve}</strong> - <strong>(R4′) the report row {@code FOR UPDATE}</strong>, the subject not the
 *       approver's party, {@code DRAFT}, and a <strong>second person</strong>: the drafter refused
 *       {@link SelfApprovalRefused} (recorded {@code FAILED} by the caller; {@code regulatory_report_four_eyes} holds every
 *       writer).</li>
 *   <li><strong>{@link #file}</strong> - R4′, {@code APPROVED → FILED} with the drive's permit due at once,
 *       {@code risk.RegulatoryReportFiled} (report id and jurisdiction - never the subject) in the same transaction.
 *       Nothing is sent here: <strong>{@code FILED} is committed before the provider is called</strong>.</li>
 * </ul>
 *
 * <h2>The filing's drive (S - the platform; three steps, the provider in none of risk's transactions)</h2>
 *
 * <ol>
 *   <li><strong>{@link #claim}</strong> / {@link #claimNextDue} - the {@code FILED} row {@code FOR UPDATE SKIP LOCKED} on a
 *       permit that has come, one attempt counted, the permit a lease ahead; the caller commits it.</li>
 *   <li><strong>{@link #submit}</strong> on no connection - the first attempt files under the filing reference (the
 *       report's id); every later one inquires by it, and files under the same reference only when the authority says
 *       nothing arrived ({@link FilingInquiry}). A lost answer is asked for, never filed again.</li>
 *   <li><strong>{@link #conclude}</strong> - the answer by a conditional edge on {@code FILED} and the claimed attempt:
 *       {@code ACKNOWLEDGED} / {@code REJECTED} once, audited as the platform; an unknown standing keeps {@code FILED} with
 *       the permit an inquiry interval ahead.</li>
 * </ol>
 *
 * <p>Persons are {@link Cases#person persons} - {@code CUSTOMER}-typed as the session establishes every operator, or
 * {@code EMPLOYEE}. Nothing here logs; the audit summaries carry the case, the jurisdiction, codes and counts - never the
 * subject, a reason's text in a summary, or the narrative, which is served only by the AML desk's doors.
 */
public final class RegulatoryReports {

    /** On {@code FILED}: the report id and the jurisdiction - never the subject ({@code INV-RSK-08}). */
    public static final String FILED_EVENT = "risk.RegulatoryReportFiled";

    public static final String AGGREGATE_TYPE = "regulatory_report";

    /** The audit target type of every act on a report. */
    public static final String TARGET_TYPE = "regulatory_report";

    static final int EVENT_VERSION = 1;

    private final RegulatoryReportStore store;
    private final Cases cases;
    private final RiskCaseStore caseStore;
    private final RiskAlertStore alerts;
    private final MonitoredParties parties;
    private final RiskActingParty actingParties;
    private final RegulatoryFilingProvider provider;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final ReportObserver observer;

    public RegulatoryReports(RegulatoryReportStore store, Cases cases, RiskCaseStore caseStore, RiskAlertStore alerts,
            MonitoredParties parties, RiskActingParty actingParties, RegulatoryFilingProvider provider,
            AuditWriter<Connection> audit, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock,
            ReportObserver observer) {
        this.store = Objects.requireNonNull(store, "store");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    // ------------------------------------------------------------------ requests and outcomes

    /** What the drafter asks: the jurisdiction, the narrative, the attachments' references and the act's reason. */
    public record Draft(String jurisdiction, String narrative, List<UUID> attachments, String reason) {
        public Draft {
            // Copied as given - a null reference is the draft's refusal to make, not this value's.
            attachments = attachments == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(attachments));
        }

        /** Codes and counts only - the narrative and the reason are a person's text ({@code INV-RSK-08}). */
        @Override
        public String toString() {
            return "Draft[" + jurisdiction + ", attachments=" + attachments.size() + "]";
        }
    }

    /** A decision's answer: the report after it; {@code replayed} when the same person's retry converged. */
    public record Decided(RegulatoryReport report, boolean replayed) {
        public Decided {
            Objects.requireNonNull(report, "report");
        }
    }

    // ------------------------------------------------------------------ refusals

    /** No report of the named case answers the request ({@code 404 risk.NotFound}). */
    public static final class ReportNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReportNotFound() {
            super("no risk record answers the request");
        }
    }

    /** The report cannot take the act now ({@code 409 risk.ReportNotFileable}) - nothing was written. */
    public static final class ReportNotFileable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReportNotFileable(String detail) {
            super(detail);
        }
    }

    /** The draft is not admitted as asked ({@code 422 risk.ReportRequestInvalid}) - nothing was drafted. */
    public static final class ReportRequestInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReportRequestInvalid(String detail) {
            super(detail);
        }
    }

    /** The drafter tried to approve their own report ({@code 403 risk.SelfApprovalRefused}; {@code INV-RSK-07}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(RegulatoryReport report) {
            super("a regulatory report is approved by a second AML_REPORT_APPROVE holder, never its drafter");
            this.target = report.id().value().toString();
            this.summary = "case=" + report.caseId().value() + ", jurisdiction=" + report.content().jurisdiction()
                    + ", act=report:approve";
        }

        /** The report the approver drafted - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the case, the jurisdiction and the act - never the subject. */
        public String summary() {
            return summary;
        }
    }

    // ================================================================== T-rep (P)

    /**
     * Drafts a regulatory report from {@code caseId} for {@code actor}. Audited {@code risk.ReportDrafted} with the reason.
     *
     * @throws RiskReasons.ReasonRequired a missing, unbounded or instrument-bearing reason or narrative
     * @throws ReportRequestInvalid a jurisdiction no adapter declares; more than {@link ReportContent#ATTACHMENTS_BOUND}
     *     attachments, or one named twice
     * @throws RiskAssessments.SelfDealingRefused the drafter's own party's case, or a drafter placed in no party
     * @throws Cases.CaseNotFound no such case
     * @throws ReportNotFileable the case is not an AML case disposed {@code CONFIRMED_SUSPICIOUS} of a party, or it has a
     *     live or accepted report
     */
    public RegulatoryReport draft(Connection unitOfWork, RiskCaseId caseId, Draft request, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(request.reason());
        String narrative = ReportContent.narrated(request.narrative());
        RegulatoryFilingProvider.Declaration declared = Optional.ofNullable(request.jurisdiction())
                .flatMap(provider::declared)
                .orElseThrow(() -> new ReportRequestInvalid("a report is drafted for a jurisdiction a filing adapter"
                        + " declares"));
        List<UUID> attachments = request.attachments();
        if (attachments.size() > ReportContent.ATTACHMENTS_BOUND || attachments.contains(null)
                || new LinkedHashSet<>(attachments).size() != attachments.size()) {
            throw new ReportRequestInvalid("a report carries at most " + ReportContent.ATTACHMENTS_BOUND
                    + " attachments' references, each named once");
        }
        RiskCase locked = cases.lockForAct(unitOfWork, caseId, actor, "report-draft");
        judgeFileable(locked);
        if (store.liveOf(unitOfWork, caseId).isPresent()) {
            throw new ReportNotFileable("the case already has a live or accepted report; a new draft follows only a"
                    + " rejected filing");
        }
        UUID party = UUID.fromString(locked.subject().ref());
        List<RiskAlert> attached = alerts.ofCase(unitOfWork, caseId);
        List<CaseAction> acted = caseStore.actions(unitOfWork, caseId);
        List<String> grounds = attached.stream().map(alert -> alert.rule().code()).distinct().sorted()
                .limit(ReportContent.GROUNDS_BOUND).toList();
        Set<UUID> accounts = parties.accountsOf(unitOfWork, party).ids();
        List<UUID> entries = journalEntries(attached, acted);
        RegulatoryReportStore.Referenced referenced = entries.isEmpty() || accounts.isEmpty()
                ? new RegulatoryReportStore.Referenced(List.of(), 0)
                : store.referenced(unitOfWork, entries, accounts, ReportContent.TRANSACTIONS_BOUND);
        ReportContent content = new ReportContent(declared.jurisdiction(), declared.formatVersion(),
                declared.reportingEntityReference(), party, List.copyOf(accounts), referenced.transactions(), grounds,
                narrative, attachments);

        // The case's act first: the report's id is its id, which the report's foreign key names (D2).
        RegulatoryReportId id = RegulatoryReportId.of(ids.next());
        int last = acted.stream().mapToInt(CaseAction::ordinal).max().orElse(0);
        caseStore.appendAction(unitOfWork, new CaseAction(id.value(), caseId, Math.max(last, locked.revision()) + 1,
                CaseActionKind.REPORT_DRAFTED, RiskCase.Person.of(actor), Optional.empty(), AlertEvidence.NONE,
                Optional.empty(), false, Optional.empty(), correlation.value(), Optional.empty()));
        RegulatoryReport born = store.insert(unitOfWork, new RegulatoryReportStore.NewReport(id, caseId, content,
                RiskCase.Person.of(actor), correlation.value())).orElseThrow(() -> new ReportNotFileable(
                        "the case already has a live or accepted report"));
        append(unitOfWork, actor, RiskAuditAction.REPORT_DRAFTED, born, Optional.of(reasoned),
                "case=" + caseId.value() + ", jurisdiction=" + content.jurisdiction() + ", formatVersion="
                        + content.formatVersion() + ", accounts=" + content.accounts().size() + ", transactions="
                        + content.transactions().size() + ", unreferenced="
                        + (referenced.matched() - referenced.transactions().size()) + ", grounds="
                        + content.grounds().size() + ", attachments=" + content.attachments().size(),
                correlation);
        observer.moved(RegulatoryReportStatus.DRAFT);
        return born;
    }

    /**
     * Approves {@code reportId} of {@code caseId} by a second person. Audited {@code risk.ReportApproved} with the reason.
     * The same approver's retry converges.
     *
     * @throws ReportNotFound no such report of the case
     * @throws ReportNotFileable the report is no longer a {@code DRAFT}
     * @throws SelfApprovalRefused the approver drafted it
     */
    public Decided approve(Connection unitOfWork, RiskCaseId caseId, RegulatoryReportId reportId, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RegulatoryReport locked = judged(unitOfWork, caseId, reportId, actor, "report-approve", true);
        if (locked.status() != RegulatoryReportStatus.DRAFT) {
            if (locked.approvedBy(actor)) {
                return new Decided(locked, true);
            }
            throw new ReportNotFileable("the report is no longer awaiting its approval: it is " + locked.status());
        }
        if (locked.draftedBy(actor)) {
            throw new SelfApprovalRefused(locked);
        }
        RegulatoryReport approved = store.approve(unitOfWork, reportId, RiskCase.Person.of(actor), correlation.value())
                .orElseThrow(() -> new IllegalStateException("the locked report moved under another writer: the FOR"
                        + " UPDATE protocol was bypassed"));
        append(unitOfWork, actor, RiskAuditAction.REPORT_APPROVED, approved, Optional.of(reasoned),
                "case=" + caseId.value() + ", jurisdiction=" + approved.content().jurisdiction(), correlation);
        observer.moved(RegulatoryReportStatus.APPROVED);
        return new Decided(approved, false);
    }

    /**
     * Files {@code reportId} of {@code caseId}: {@code FILED} with the drive's permit due at once and
     * {@code risk.RegulatoryReportFiled}, audited {@code risk.ReportFiled}. Nothing is sent here - the caller drives the
     * filing after this transaction commits. The same filer's retry converges.
     *
     * @throws ReportNotFound no such report of the case
     * @throws ReportNotFileable the report is not {@code APPROVED}
     */
    public Decided file(Connection unitOfWork, RiskCaseId caseId, RegulatoryReportId reportId, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RegulatoryReport locked = judged(unitOfWork, caseId, reportId, actor, "report-file", true);
        if (locked.status() != RegulatoryReportStatus.APPROVED) {
            if (locked.filedBy(actor)) {
                return new Decided(locked, true);
            }
            throw new ReportNotFileable("the report is not awaiting its filing: it is " + locked.status());
        }
        RegulatoryReport filed = store.file(unitOfWork, reportId, RiskCase.Person.of(actor), correlation.value())
                .orElseThrow(() -> new IllegalStateException("the locked report moved under another writer: the FOR"
                        + " UPDATE protocol was bypassed"));
        append(unitOfWork, actor, RiskAuditAction.REPORT_FILED, filed, Optional.of(reasoned),
                "case=" + caseId.value() + ", jurisdiction=" + filed.content().jurisdiction() + ", formatVersion="
                        + filed.content().formatVersion(),
                correlation);
        announce(unitOfWork, filed, correlation);
        observer.moved(RegulatoryReportStatus.FILED);
        return new Decided(filed, false);
    }

    // ------------------------------------------------------------------ reads

    /**
     * {@code reportId} of {@code caseId} read for {@code actor} on {@code snapshot} - judged for self-dealing first; the
     * caller records it ({@link #recordRead}) before it is answered.
     *
     * @throws ReportNotFound no such report of the case
     * @throws RiskAssessments.SelfDealingRefused the reader's own party's report, or a reader placed in no party
     */
    public RegulatoryReport read(Connection snapshot, RiskCaseId caseId, RegulatoryReportId reportId, Actor actor) {
        return judged(snapshot, caseId, reportId, actor, "report-read", false);
    }

    /** Records {@code served} read by {@code actor} ({@code risk.RegulatoryReportRead}) on {@code unitOfWork}. */
    public void recordRead(Connection unitOfWork, RegulatoryReport served, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(served, "served");
        append(unitOfWork, actor, RiskAuditAction.REGULATORY_REPORT_READ, served, Optional.empty(),
                "case=" + served.caseId().value() + ", status=" + served.status().name() + ", jurisdiction="
                        + served.content().jurisdiction(),
                correlation);
    }

    /** {@code reportId} as it stands now - the receipt of an act or a drive; no lock, no audit. */
    public Optional<RegulatoryReport> current(Connection unitOfWork, RegulatoryReportId reportId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.find(unitOfWork, Objects.requireNonNull(reportId, "reportId"));
    }

    // ================================================================== the filing's drive (S)

    /**
     * Claims {@code reportId} for its drive - the filing door's, after its filing committed: on {@code unitOfWork} (the
     * caller commits it before calling the provider), one attempt counted and the permit {@code lease} ahead. Empty when
     * another claimer holds it, its permit has not come, or it is concluded.
     */
    public Optional<RegulatoryReport> claim(Connection unitOfWork, RegulatoryReportId reportId, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.claim(unitOfWork, Objects.requireNonNull(reportId, "reportId"), lease(lease));
    }

    /** The oldest due {@code FILED} report claimed, as {@link #claim} - the inquiry leg's. */
    public Optional<RegulatoryReport> claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.claimNextDue(unitOfWork, lease(lease));
    }

    /**
     * {@code claimed} asked of the authority - on <strong>no connection</strong> (the caller holds no transaction): the
     * first attempt files under the filing reference; a later one inquires by it, and files under the same reference only
     * when nothing arrived under it ({@link FilingInquiry}).
     *
     * @throws RuntimeException whatever the provider throws: nothing is recorded, the permit stands
     */
    public FilingOutcome submit(RegulatoryReport claimed) {
        FilingInquiry inquiry = FilingInquiry.of(claimed);
        RegulatoryFilingProvider.Request request = new RegulatoryFilingProvider.Request(claimed.id(),
                inquiry.filingReference(), claimed.content());
        if (inquiry.submits()) {
            return answered(provider.file(request), true);
        }
        RegulatoryFilingProvider.Receipt asked = provider.inquire(inquiry.filingReference());
        observer.answered(asked.outcome());
        if (asked.outcome() != RegulatoryFilingProvider.Outcome.NOT_RECEIVED) {
            return FilingOutcome.of(asked, false);
        }
        // Nothing arrived under the reference: the filing is still owed - the first one, under the same reference.
        return answered(provider.file(request), true);
    }

    /**
     * Records {@code outcome} on {@code claimed}, once: an acknowledgement or a rejection concludes it, audited
     * {@code risk.ReportAcknowledged} / {@code risk.ReportRejected} as the acting principal (the platform); an unknown
     * standing keeps it {@code FILED} with the permit {@code inquiryInterval} ahead. Empty - nothing written - when another
     * drive recorded first.
     */
    public Optional<RegulatoryReport> conclude(Connection unitOfWork, RegulatoryReport claimed, FilingOutcome outcome,
            Duration inquiryInterval, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(correlation, "correlation");
        Actor platform = SecurityContext.require();
        if (platform.type() != ActorType.SYSTEM) {
            // The authority's answer is the platform's record - risk V016's regulatory_report_event_actor_coherent too.
            throw new IllegalStateException("the authority's answer is recorded by the platform, never a person");
        }
        Optional<RegulatoryReport> recorded = store.record(unitOfWork, claimed.id(), claimed.attempts(), outcome,
                lease(inquiryInterval), RiskCase.Person.of(platform), correlation.value());
        recorded.filter(report -> report.status().concluded()).ifPresent(concluded -> {
            boolean acknowledged = concluded.status() == RegulatoryReportStatus.ACKNOWLEDGED;
            append(unitOfWork, platform,
                    acknowledged ? RiskAuditAction.REPORT_ACKNOWLEDGED : RiskAuditAction.REPORT_REJECTED, concluded,
                    Optional.empty(), "jurisdiction=" + concluded.content().jurisdiction()
                            + (acknowledged ? ", providerReference=" + concluded.providerReference().orElseThrow()
                                    : ", rejection=" + concluded.rejectionCode().orElseThrow())
                            + ", attempts=" + concluded.attempts(),
                    correlation);
            observer.moved(concluded.status());
        });
        return recorded;
    }

    // ------------------------------------------------------------------ the refusals, recorded

    /**
     * Records a refused self-approval {@code FAILED} ({@code risk.SelfApprovalRefused}) on {@code unitOfWork} - the
     * caller's own transaction, after the act rolled back, so the attempt is kept ({@code INV-RSK-07}).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(),
                refused.summary(), correlation);
    }

    /**
     * Records a refused act or read on a report {@code FAILED} ({@code risk.SelfDealingRefused}) on {@code unitOfWork} - a
     * transaction of its own, after the act was abandoned. Its target the report; the summary the act and the cause -
     * never the party.
     */
    public void recordRefusal(Connection unitOfWork, RegulatoryReportId target,
            RiskAssessments.SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        refusedDealing(unitOfWork, TARGET_TYPE, Objects.requireNonNull(target, "target").value().toString(), refused,
                actor, correlation);
    }

    /**
     * Records a refused draft {@code FAILED} ({@code risk.SelfDealingRefused}) - its target the case drafted from, no
     * report existing yet.
     */
    public void recordRefusal(Connection unitOfWork, RiskCaseId target, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        refusedDealing(unitOfWork, Cases.TARGET_TYPE, Objects.requireNonNull(target, "target").value().toString(),
                refused, actor, correlation);
    }

    private void refusedDealing(Connection unitOfWork, String targetType, String target,
            RiskAssessments.SelfDealingRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, targetType, target, Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    // ================================================================== plumbing

    /** An AML case disposed {@code CONFIRMED_SUSPICIOUS} of a party - the one case a report is drafted from (D12). */
    private static void judgeFileable(RiskCase locked) {
        boolean confirmedSuspicious = locked.disposition().filter(slot -> !slot.standing())
                .filter(slot -> slot.disposition() == Disposition.CONFIRMED_SUSPICIOUS).isPresent();
        if (locked.kind() != CaseKind.AML || locked.status() != CaseStatus.DISPOSED || !confirmedSuspicious
                || locked.subject().kind() != SubjectKind.PARTY) {
            throw new ReportNotFileable("a regulatory report is drafted from an AML case of a party disposed"
                    + " CONFIRMED_SUSPICIOUS");
        }
    }

    /** The journal entries the case's evidence names - its alerts', then its evidence acts', each once, in order. */
    private static List<UUID> journalEntries(List<RiskAlert> attached, List<CaseAction> acted) {
        Set<UUID> entries = new LinkedHashSet<>();
        attached.forEach(alert -> entries.addAll(alert.evidence().journalEntries()));
        acted.stream().filter(action -> action.kind() == CaseActionKind.EVIDENCE_ATTACHED)
                .forEach(action -> entries.addAll(action.evidence().journalEntries()));
        return List.copyOf(entries);
    }

    private FilingOutcome answered(RegulatoryFilingProvider.Receipt receipt, boolean submitted) {
        observer.answered(receipt.outcome());
        return FilingOutcome.of(receipt, submitted);
    }

    /**
     * A person's act or read, judged: a person placed in a party, then the report of the named case - under its lock
     * (R4′) for an act - and its subject not the actor's party.
     */
    private RegulatoryReport judged(Connection unitOfWork, RiskCaseId caseId, RegulatoryReportId reportId, Actor actor,
            String act, boolean lock) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(actor, "actor");
        if (!Cases.person(actor)) {
            throw new IllegalArgumentException("a report act is a person's - never the platform's, a service's or a"
                    + " merchant's");
        }
        UUID own = actingParties.partyOf(unitOfWork, actor).orElseThrow(() -> new RiskAssessments.SelfDealingRefused(
                RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE, act));
        RegulatoryReport report = (lock ? store.lock(unitOfWork, reportId) : store.find(unitOfWork, reportId))
                .filter(found -> found.caseId().equals(caseId))
                .orElseThrow(ReportNotFound::new);
        if (report.content().subject().equals(own)) {
            throw new RiskAssessments.SelfDealingRefused(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY, act);
        }
        return report;
    }

    private static Duration lease(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("a permit moves a positive interval ahead");
        }
        return lease;
    }

    private void append(Connection unitOfWork, Actor actor, RiskAuditAction action, RegulatoryReport report,
            Optional<String> reason, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                report.id().value().toString(), reason, AuditOutcome.SUCCEEDED, correlation, Optional.of(summary)));
    }

    /** {@code risk.RegulatoryReportFiled} - the report id and the jurisdiction, nothing else ({@code INV-RSK-08}). */
    private void announce(Connection unitOfWork, RegulatoryReport filed, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("reportId", filed.id().value().toString())
                .with("jurisdiction", filed.content().jurisdiction());
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), FILED_EVENT, EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION,
                        filed.id(), AGGREGATE_TYPE, clock.instant(), RiskPolicyAdministration.PRODUCER, correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(), EventPayload.MEDIA_TYPE);
    }
}
