package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The alert queue - a read model over {@code risk_alert}, no projection (`P13-TSK-018`; {@code risk V014}'s
 * {@code risk_alert_queue}, {@code V010}'s {@code risk_alert_open_queue}; {@code PHASE_13_PLAN.md} sections 9, 11;
 * {@code INV-RSK-08}; {@code DESIGN-018} D3-D7).
 *
 * <p>{@link #page} reads one page on the caller's read-only snapshot: oldest first by the immutable key
 * {@code (raised_at, id)}, of one {@link AlertStatus} ({@code OPEN} unless named) and optionally one {@link AlertKind},
 * after the alert the cursor names (its key read from its own row, so the cursor stays valid however the queue moves),
 * every alert of the reader's own party - or whose party cannot be placed - withheld ({@link Queues}' self-dealing scan).
 * {@link #record} then appends {@code risk.AlertRead} on a transaction of the caller's own, which the caller commits
 * <strong>before</strong> it answers the page: a page nobody recorded was never served. A read decides nothing and writes
 * nothing else; persons are never part of a page's rows ({@link RiskAlert}'s proposer and decider are the desk's to
 * withhold).
 */
public final class AlertQueue {

    /** The audit target type of a page - the queue, its id the status read. */
    public static final String TARGET_TYPE = "risk_alert_queue";

    private static final String ACT = "alert-queue-read";

    private final RiskAlertStore store;
    private final RiskActingParty actingParties;
    private final RiskSubjectParties subjectParties;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public AlertQueue(RiskAlertStore store, RiskActingParty actingParties, RiskSubjectParties subjectParties,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.subjectParties = Objects.requireNonNull(subjectParties, "subjectParties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What a page reads: one status ({@code OPEN} unless named) and, when named, one kind. */
    public record Filter(AlertStatus status, Optional<AlertKind> kind) {
        public Filter {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(kind, "kind");
        }

        /**
         * The filter {@code status} and {@code kind} name, exactly as the vocabularies spell them.
         *
         * @throws Queues.QueueRequestInvalid a name outside {@link AlertStatus} or {@link AlertKind}
         */
        public static Filter named(Optional<String> status, Optional<String> kind) {
            return new Filter(Queues.named(status, AlertStatus.class, "status").orElse(AlertStatus.OPEN),
                    Queues.named(kind, AlertKind.class, "kind"));
        }
    }

    /**
     * One page: the alerts served, oldest first; the cursor to the next page (the last alert served - present only when
     * more remain); how many alerts were withheld from the reader; and whether the scan's bound ended the page.
     */
    public record Page(Filter filter, Optional<RiskAlertId> after, List<RiskAlert> alerts, Optional<RiskAlertId> next,
            int withheld, boolean bounded) {
        public Page {
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(after, "after");
            alerts = List.copyOf(alerts);
            Objects.requireNonNull(next, "next");
        }
    }

    /**
     * One page of the queue for {@code actor}, on the caller's read-only snapshot - nothing recorded here.
     *
     * @throws RiskAssessments.SelfDealingRefused the reader is placed in no party (fail closed)
     * @throws Queues.QueueRequestInvalid the cursor names no alert
     * @throws IllegalArgumentException the reader is not a person
     */
    public Page page(Connection snapshot, Filter filter, Optional<RiskAlertId> after, int limit, Actor actor) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(after, "after");
        UUID own = Queues.reader(snapshot, actingParties, actor, ACT);
        if (after.isPresent() && store.find(snapshot, after.get()).isEmpty()) {
            throw new Queues.QueueRequestInvalid("the cursor names no alert; page again from the start");
        }
        Queues.Scanned<RiskAlert, RiskAlertId> scanned = Queues.scan(limit, after,
                cursor -> store.queue(snapshot, filter.status(), filter.kind(), cursor, own, limit + 1),
                RiskAlert::id,
                alert -> Queues.visibleTo(snapshot, subjectParties, alert.subject(), own));
        return new Page(filter, after, scanned.served(), scanned.next(), scanned.withheld(), scanned.bounded());
    }

    /**
     * Records {@code page} served to {@code actor} - {@code risk.AlertRead} on {@code unitOfWork}, which the caller
     * commits before it answers. The summary the count, the filter, the cursor, the ids served, the withheld count and
     * whether more remain - never a subject, an evidence reference or a reason.
     */
    public void record(Connection unitOfWork, Page page, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.ALERT_READ,
                TARGET_TYPE, page.filter().status().name(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("page: " + page.alerts().size() + " alert(s), status=" + page.filter().status().name()
                        + ", kind=" + page.filter().kind().map(Enum::name).orElse("ANY")
                        + ", after=" + page.after().map(id -> id.value().toString()).orElse("none")
                        + ", ids=" + page.alerts().stream().map(alert -> alert.id().value().toString())
                                .collect(Collectors.joining("|", "[", "]"))
                        + ", withheld=" + page.withheld() + ", more=" + page.next().isPresent()
                        + (page.bounded() ? ", bounded=true" : ""))));
    }

    /**
     * Records a refused page {@code FAILED} ({@code risk.SelfDealingRefused}) on {@code unitOfWork} - a transaction of its
     * own; its target the queue, the summary the act and the cause - never the party.
     */
    public void recordRefusal(Connection unitOfWork, Filter filter, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(refused, "refused");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, filter.status().name(), Optional.empty(),
                AuditOutcome.FAILED, correlation,
                Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }
}
