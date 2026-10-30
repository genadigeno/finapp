package com.finapp.accounting;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * GL mapping versions change only forward, and only under four eyes (`P14-TSK-003`; ADR-0113 decision 3;
 * {@code PHASE_14_PLAN.md} sections 5, 6, 8.1 T-map; {@code INV-ACC-06}, {@code INV-ACC-07}, {@code INV-AUD-04}): an
 * accountant proposes a whole version - its {@link GlMappingVersion} already judged coherent; a DIFFERENT person holding
 * {@code ACCOUNTING_MAPPING_APPROVE} activates it, the activation judging it <strong>total and
 * classification-compatible against the ledger's chart as its own transaction reads it</strong> ({@link LedgerChart},
 * {@link MappingJudgement}) and retiring the version in force in the SAME transaction, so at most one version is
 * {@code ACTIVE} at every instant and the versions' month ranges meet; a holder of the approve permission may reject it.
 * risk's {@code RiskPolicyAdministration}, applied.
 *
 * <p><strong>The lock order</strong> (T-map): namespace {@code 14} key {@code mapping} exclusively first (A2), then the
 * version row {@code FOR UPDATE}, then the {@code ACTIVE} row {@code FOR UPDATE}. <strong>Ten proposers, ten
 * versions</strong> numbered without a gap ({@code max + 1} under A2). <strong>Ten activators, one activation</strong>
 * ({@link MappingStale} for the others; the partial unique {@code gl_mapping_one_active} the backstop). Activation is
 * monotonic in version number and <strong>never retroactive</strong>: a version takes effect from a month no earlier
 * than any version already activated ({@link MappingRetroactive}; {@code accounting V002}'s edge trigger for every
 * writer) - `P14-TSK-005` adds that the month is an {@code OPEN} period, re-read under the period row. A replayed
 * decision by the same person converges.
 *
 * <p><strong>Persons only</strong>: every act is an authenticated human's ({@code CUSTOMER}-typed, as every operator
 * session is, or {@code EMPLOYEE}) - never the platform's, a service's or a merchant's; {@code V002}'s CHECK holds it
 * for every writer. <strong>Never activated by a migration</strong>: production starts with none (C2).
 */
@RequiredArgsConstructor
public final class GlMappingAdministration {

    /** The event every activation publishes - codes and numbers only. */
    public static final String ACTIVATED_EVENT = "accounting.GlMappingVersionActivated";

    static final String PRODUCER = "accounting";
    static final int EVENT_VERSION = 1;

    /** The audit record's and the event's target type. */
    public static final String TARGET_TYPE = "gl_mapping_version";

    @NonNull private final GlMappingStore store;
    @NonNull private final LedgerChart chart;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(GlMappingVersionId id, int version, YearMonth effectiveFrom) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for the first, a
     * rejection and a converged replay), {@code replayed} when the same person's repeat converged.
     */
    public record Decided(
            GlMappingVersionId id,
            int version,
            YearMonth effectiveFrom,
            GlMappingVersionStatus status,
            Optional<Integer> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No mapping version has this id. */
    public static final class MappingNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MappingNotFound() {
            super("no GL mapping version answers this request");
        }
    }

    /** The version is no longer {@code PROPOSED} - decided by somebody else, or differently - or is superseded. */
    public static final class MappingStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MappingStale(String detail) {
            super(detail);
        }
    }

    /** The version would take effect before a version already in force ({@code INV-ACC-07}). */
    public static final class MappingRetroactive extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MappingRetroactive(String detail) {
            super(detail);
        }
    }

    /** A purpose of the ledger's chart is unmapped ({@code INV-ACC-06}). Names the purposes - codes, never a figure. */
    public static final class MappingNotTotal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MappingNotTotal(List<String> unmapped) {
            super("the version does not map " + unmapped.size() + " purpose(s) of the ledger's chart: "
                    + String.join(", ", unmapped));
        }
    }

    /** A rule is not classification-compatible, or names a purpose the chart lacks ({@code INV-ACC-06}). */
    public static final class MappingIncompatible extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MappingIncompatible(MappingJudgement judgement) {
            super("the version is not compatible with the ledger's chart:"
                    + (judgement.unknown().isEmpty() ? "" : " no such purpose " + String.join(", ", judgement.unknown())
                            + ";")
                    + (judgement.incompatible().isEmpty() ? "" : " " + String.join("; ", judgement.incompatible())));
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}, {@code INV-ACC-07}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(GlMappingVersionId id, int version) {
            super("a GL mapping version is activated by someone other than its proposer; the proposer may reject it");
            this.target = id.value().toString();
            this.summary = "version=v" + version + ", act=mapping:activate";
        }

        /** The proposal the activator proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the proposal and the act. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: {@code content} already judged coherent before anything is locked or written; then
     * A2, numbered {@code max + 1}, written {@code PROPOSED} with its content, its history row and the reasoned audit
     * record - one transaction.
     *
     * @throws AccountingReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Proposed propose(
            Connection unitOfWork, GlMappingVersion content, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        store.lockMapping(unitOfWork);
        int version = store.maxVersion(unitOfWork) + 1;
        GlMappingVersionId id = GlMappingVersionId.next(ids);
        store.insertProposal(unitOfWork, new GlMappingStore.NewProposal(id, version, content, actor, reasoned,
                correlation, ids.next()));
        audit(unitOfWork, actor, AccountingAuditAction.MAPPING_PROPOSED, id, Optional.of(reasoned),
                "version=v" + version + ", effectiveFrom=" + content.effectiveFrom() + ", accounts="
                        + content.accounts().size() + ", rules=" + content.rules().size(),
                correlation);
        return new Proposed(id, version, content.effectiveFrom());
    }

    // ------------------------------------------------------------------ activate

    /**
     * Activates a pending version - a different person's act. Under A2, the version's row lock and the {@code ACTIVE}
     * row's: the version judged against the ledger's chart read on this transaction (total, compatible), never before a
     * month already in force; the predecessor (if any) retired FIRST, ending where this begins; the edge
     * {@code PROPOSED -> ACTIVE}; both history rows, the reasoned audit record and
     * {@code accounting.GlMappingVersionActivated} - one transaction.
     *
     * @throws MappingNotFound when no version has this id
     * @throws MappingStale when the version is no longer {@code PROPOSED}, or is numbered below the version in force
     * @throws SelfApprovalRefused when the activator proposed it ({@code INV-AUD-04})
     * @throws MappingRetroactive when it takes effect before a version already activated ({@code INV-ACC-07})
     * @throws MappingNotTotal when a purpose of the chart is unmapped ({@code INV-ACC-06})
     * @throws MappingIncompatible when a rule's classification is not its purpose's type, or names no purpose
     */
    public Decided activate(
            Connection unitOfWork, GlMappingVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        GlMappingStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == GlMappingVersionStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.effectiveFrom(), row.status(), Optional.empty(), true);
        }
        if (row.status() != GlMappingVersionStatus.PROPOSED) {
            throw new MappingStale("the GL mapping version is no longer awaiting a decision: it is " + row.status().name());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row.id(), row.version());
        }
        Optional<GlMappingStore.VersionRow> predecessor = store.lockActive(unitOfWork);
        if (predecessor.isPresent() && predecessor.get().version() > row.version()) {
            throw new MappingStale("the GL mapping version is superseded: v" + predecessor.get().version()
                    + " is in force; propose a later version");
        }
        Optional<YearMonth> floor = store.latestEffectiveFrom(unitOfWork);
        if (floor.isPresent() && row.effectiveFrom().isBefore(floor.get())) {
            throw new MappingRetroactive("the version takes effect from " + row.effectiveFrom()
                    + ", before " + floor.get() + ", the month a version already in force takes effect from");
        }
        MappingJudgement judgement = MappingJudgement.judge(store.content(unitOfWork, row.id()), chart.read(unitOfWork));
        if (!judgement.total()) {
            throw new MappingNotTotal(judgement.unmapped());
        }
        if (!judgement.compatible()) {
            throw new MappingIncompatible(judgement);
        }
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id(), row.effectiveFrom())) {
            throw new IllegalStateException(
                    "the locked ACTIVE version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        if (!store.decide(unitOfWork, row.id(), GlMappingVersionStatus.ACTIVE, actor, reasoned)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        predecessor.ifPresent(retired -> store.appendEvent(unitOfWork, new GlMappingStore.HistoryRow(ids.next(),
                retired.id(), Optional.of(GlMappingVersionStatus.ACTIVE), GlMappingVersionStatus.RETIRED, actor,
                AccountingReasons.bounded("retired by version " + row.version() + "'s activation: " + reasoned),
                correlation)));
        store.appendEvent(unitOfWork, new GlMappingStore.HistoryRow(ids.next(), row.id(),
                Optional.of(GlMappingVersionStatus.PROPOSED), GlMappingVersionStatus.ACTIVE, actor, reasoned, correlation));
        Optional<Integer> retired = predecessor.map(GlMappingStore.VersionRow::version);
        // Codes and numbers only - never a figure, a person or a reason; the retired version only when there was one.
        EventPayload payload = EventPayload.of()
                .with("version", Integer.toString(row.version()))
                .with("effectiveFromPeriod", row.effectiveFrom().toString());
        if (retired.isPresent()) {
            payload = payload.with("retiredVersion", Integer.toString(retired.get()));
        }
        audit(unitOfWork, actor, AccountingAuditAction.MAPPING_ACTIVATED, row.id(), Optional.of(reasoned),
                "version=v" + row.version() + ", effectiveFrom=" + row.effectiveFrom()
                        + retired.map(v -> ", retired=v" + v).orElse(", retired=none"),
                correlation);
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ACTIVATED_EVENT,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        row.id(),
                        TARGET_TYPE,
                        clock.instant(),
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.version(), row.effectiveFrom(), GlMappingVersionStatus.ACTIVE, retired, false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act; terminal; a correction is a new proposal. The same person's repeat
     * converges.
     *
     * @throws MappingNotFound when no version has this id
     * @throws MappingStale when the version is no longer {@code PROPOSED}
     */
    public Decided reject(
            Connection unitOfWork, GlMappingVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        GlMappingStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == GlMappingVersionStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.effectiveFrom(), row.status(), Optional.empty(), true);
        }
        if (row.status() != GlMappingVersionStatus.PROPOSED) {
            throw new MappingStale("the GL mapping version is no longer awaiting a decision: it is " + row.status().name());
        }
        if (!store.decide(unitOfWork, row.id(), GlMappingVersionStatus.REJECTED, actor, reasoned)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new GlMappingStore.HistoryRow(ids.next(), row.id(),
                Optional.of(GlMappingVersionStatus.PROPOSED), GlMappingVersionStatus.REJECTED, actor, reasoned,
                correlation));
        audit(unitOfWork, actor, AccountingAuditAction.MAPPING_REJECTED, row.id(), Optional.of(reasoned),
                "version=v" + row.version() + ", byProposer=" + row.proposedBy().equals(actor.id()), correlation);
        return new Decided(row.id(), row.version(), row.effectiveFrom(), GlMappingVersionStatus.REJECTED,
                Optional.empty(), false);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link AccountingAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                AccountingAuditAction.SELF_APPROVAL_REFUSED, TARGET_TYPE, refused.target(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of(refused.summary())));
    }

    // ------------------------------------------------------------------ plumbing

    /** A2 first, then the version row locked - the state the decision acts on is the one every committed act left. */
    private GlMappingStore.VersionRow locked(Connection unitOfWork, GlMappingVersionId id) {
        store.lockMapping(unitOfWork);
        return store.lock(unitOfWork, id).orElseThrow(MappingNotFound::new);
    }

    /**
     * An authenticated human - {@code CUSTOMER}-typed, as the platform's session interceptor establishes every identity
     * (the operator's permission is the door's), or {@code EMPLOYEE}. Fail closed before anything is read.
     */
    static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a GL mapping act is a person's - never the platform's, a service's or a"
                    + " merchant's");
        }
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            AccountingAuditAction action,
            GlMappingVersionId id,
            Optional<String> reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
