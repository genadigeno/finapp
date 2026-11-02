package com.finapp.accounting;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Valuation rate sets - capture, approve, reject (`P14-TSK-014`; ADR-0116 decision 2; {@code PHASE_14_PLAN.md} sections
 * 5, 6, 13.4; owner decisions C1, C7; {@code INV-ACC-11}, {@code INV-FX-05}, {@code INV-AUD-04}). A set is the period's
 * frozen valuation input: per valued currency the reference's last {@code EUR}-based observation before the period's end,
 * within the staleness bound, with its provenance ({@link ValuationRateSet#judge}); never a quote, an executed or a cover
 * rate. Nothing is posted and no journal line is touched (C7 A).
 *
 * <p><strong>Which period</strong>: a set is captured and approved only while its period is {@code LOCKED} - the period's
 * end has passed and the ledger's cutoff stands (the plan's "captured at lock"; the close's {@code VALUATION_SET_APPROVED}
 * check reads the locked period). An {@code OPEN}, closing or closed period refuses ({@link AccountingPeriods.PeriodStale}).
 * A rejection is allowed in any period state. An unlock does not void an approved set: its rates are the period end's,
 * whatever the journal holds.
 *
 * <p><strong>Capture</strong> ({@link #capture}, a person's): the period {@code FOR SHARE} (A1 - an unlock's approval
 * waits), {@code LOCKED} and without an approved set, then the reference read through {@link ValuationRates} and judged -
 * one stale or missing pair refuses all ({@link ValuationRateSet.ValuationRateStale}) - then the set and every rate
 * inserted {@code PROPOSED} and the audit record: one transaction. Two captures for one period are two proposals.
 *
 * <p><strong>Approve</strong> (four eyes): the period {@code FOR UPDATE} (A1 - every approval of the period serialised),
 * then the set {@code FOR UPDATE} (A4); refused to its capturer ({@link SelfApprovalRefused}; {@code V005}'s
 * {@code CHECK} for every writer), unless {@code PROPOSED} or when the period already has an approved set
 * ({@link ValuationSetStale}; the partial unique {@code valuation_rate_set_one_approved_per_period} the backstop), and
 * unless the period is {@code LOCKED}; then the conditional edge and the audit record. <strong>Ten approvers, one
 * approved set</strong> per period. <strong>Reject</strong>: the set {@code FOR UPDATE}, terminal; its capturer may reject
 * it. The same person's repeated decision converges, writing nothing.
 */
@RequiredArgsConstructor
public final class ValuationRateSets {

    /** The audit record's target type. */
    public static final String TARGET_TYPE = "valuation_rate_set";

    @NonNull private final ValuationRateSetStore store;
    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final ValuationRates rates;
    @NonNull private final ValuationStaleness staleness;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes

    /**
     * A set as an act left it, or as a read found it; {@code replayed} when the act found its decision already made by
     * the same person and wrote nothing.
     */
    public record Valued(
            ValuationRateSetId id,
            AccountingPeriodId period,
            YearMonth month,
            ValuationRateSetStatus status,
            Instant asOf,
            int maxAgeHours,
            List<ValuationRate> rates,
            boolean replayed) {

        public Valued {
            rates = List.copyOf(rates);
        }
    }

    /** A set as the reviewer reads it: the set and its two instants - no person, no reason. */
    public record Read(Valued set, Instant capturedAt, Optional<Instant> decidedAt) {}

    // ------------------------------------------------------------------ refusals

    /** No valuation rate set has this id. */
    public static final class ValuationSetNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ValuationSetNotFound() {
            super("no valuation rate set answers this request");
        }
    }

    /** The set is no longer {@code PROPOSED}, or its period already has an approved set. */
    public static final class ValuationSetStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ValuationSetStale(String detail) {
            super(detail);
        }
    }

    /** The capturer tried to approve their own set ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(ValuationRateSetId id, YearMonth month) {
            super("a valuation rate set is approved by someone other than its capturer; the capturer may reject it");
            this.target = id.value().toString();
            this.summary = "period=" + month + ", act=valuation:approve";
        }

        /** The set - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the period and the act. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ capture

    /**
     * Captures a set for a {@code LOCKED} period from the reference's observations before its end.
     *
     * @param reason optional; screened when given
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     * @throws AccountingPeriods.PeriodStale when the period is not {@code LOCKED}
     * @throws ValuationSetStale when the period already has an approved set
     * @throws ValuationRateSet.ValuationRateStale when a valued pair is stale or missing - nothing is written
     */
    public Valued capture(
            Connection unitOfWork, AccountingPeriodId periodId, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(periodId, "periodId");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        Optional<String> reasoned = reason == null || reason.isBlank()
                ? Optional.empty() : Optional.of(AccountingReasons.reasoned(reason));
        // The month is frozen from birth, so the unlocked read only names the row; A1 FOR SHARE: an unlock waits.
        YearMonth month = periods.find(unitOfWork, periodId).orElseThrow(AccountingPeriods.PeriodNotFound::new).month();
        AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, month)
                .orElseThrow(AccountingPeriods.PeriodNotFound::new);
        if (period.status() != PeriodStatus.LOCKED) {
            throw new AccountingPeriods.PeriodStale("the period is " + period.status().name()
                    + "; a valuation rate set is captured for a LOCKED period");
        }
        if (store.approvedFor(unitOfWork, periodId).isPresent()) {
            throw new ValuationSetStale("the period " + month + " already has an approved valuation rate set");
        }
        ValuationRateSet content = ValuationRateSet.judge(periodId, month, staleness, rates.valued(),
                rates.lastObservedBefore(unitOfWork, ValuationRateSet.asOf(month)));
        ValuationRateSetId id = ValuationRateSetId.next(ids);
        store.insert(unitOfWork, new ValuationRateSetStore.NewSet(id, content, actor, reasoned, correlation));
        audit(unitOfWork, actor, AccountingAuditAction.VALUATION_SET_CAPTURED, id, reasoned,
                "period=" + month + ", asOf=" + content.asOf() + ", pairs=" + content.rates().size() + ", maxAgeHours="
                        + staleness.hours(),
                correlation);
        return new Valued(id, periodId, month, ValuationRateSetStatus.PROPOSED, content.asOf(), staleness.hours(),
                content.rates(), false);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves a {@code PROPOSED} set - a different person's act, while its period is {@code LOCKED} and has no approved
     * set. The same approver's repeat converges.
     *
     * @throws ValuationSetNotFound when no set has this id
     * @throws ValuationSetStale when the set is no longer {@code PROPOSED}, or the period already has an approved set
     * @throws SelfApprovalRefused when the approver captured it ({@code INV-AUD-04})
     * @throws AccountingPeriods.PeriodStale when the period is not {@code LOCKED}
     */
    public Valued approve(
            Connection unitOfWork, ValuationRateSetId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        // The set's period is frozen from birth: the unlocked read only names the period row to lock first (A1).
        AccountingPeriodId periodId = store.find(unitOfWork, id).orElseThrow(ValuationSetNotFound::new).period();
        AccountingPeriodStore.PeriodRow period = periods.lock(unitOfWork, periodId)
                .orElseThrow(AccountingPeriods.PeriodNotFound::new);
        ValuationRateSetStore.SetRow row = store.lock(unitOfWork, id).orElseThrow(ValuationSetNotFound::new);
        if (row.status() == ValuationRateSetStatus.APPROVED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return valued(unitOfWork, row, true);
        }
        if (row.status() != ValuationRateSetStatus.PROPOSED) {
            throw new ValuationSetStale("the valuation rate set is no longer awaiting a decision: it is "
                    + row.status().name());
        }
        if (row.capturedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row.id(), row.month());
        }
        if (period.status() != PeriodStatus.LOCKED) {
            throw new AccountingPeriods.PeriodStale("the period is " + period.status().name()
                    + "; a valuation rate set is approved while its period is LOCKED");
        }
        if (store.approvedFor(unitOfWork, periodId).isPresent()) {
            throw new ValuationSetStale("the period " + row.month() + " already has an approved valuation rate set");
        }
        if (!store.decide(unitOfWork, row.id(), ValuationRateSetStatus.APPROVED, actor, reasoned)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        audit(unitOfWork, actor, AccountingAuditAction.VALUATION_SET_APPROVED, row.id(), Optional.of(reasoned),
                "period=" + row.month() + ", pairs=" + row.rateCount(), correlation);
        return valued(unitOfWork, row, ValuationRateSetStatus.APPROVED);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a {@code PROPOSED} set - reasoned, terminal; a correction is a new capture. The same rejecter's repeat
     * converges.
     *
     * @throws ValuationSetNotFound when no set has this id
     * @throws ValuationSetStale when the set is no longer {@code PROPOSED}
     */
    public Valued reject(
            Connection unitOfWork, ValuationRateSetId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        ValuationRateSetStore.SetRow row = store.lock(unitOfWork, id).orElseThrow(ValuationSetNotFound::new);
        if (row.status() == ValuationRateSetStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return valued(unitOfWork, row, true);
        }
        if (row.status() != ValuationRateSetStatus.PROPOSED) {
            throw new ValuationSetStale("the valuation rate set is no longer awaiting a decision: it is "
                    + row.status().name());
        }
        if (!store.decide(unitOfWork, row.id(), ValuationRateSetStatus.REJECTED, actor, reasoned)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        audit(unitOfWork, actor, AccountingAuditAction.VALUATION_SET_REJECTED, row.id(), Optional.of(reasoned),
                "period=" + row.month() + ", byCapturer=" + row.capturedBy().equals(actor.id()), correlation);
        return valued(unitOfWork, row, ValuationRateSetStatus.REJECTED);
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

    // ------------------------------------------------------------------ reads

    /**
     * One set by id, with its rates and instants - what the approver reviews. Unlocked.
     *
     * @throws ValuationSetNotFound when no set has this id
     */
    public Read read(Connection unitOfWork, ValuationRateSetId id) {
        ValuationRateSetStore.SetRow row = store.find(unitOfWork, id).orElseThrow(ValuationSetNotFound::new);
        return new Read(valued(unitOfWork, row, false), row.capturedAt(), row.decidedAt());
    }

    /**
     * The period's approved set, when it has one - with its id, what an operator or the close names to ask for a translated
     * run. Read by the close (`P14-TSK-011`'s {@code VALUATION_SET_APPROVED} check), outside {@code accounting.reporting}.
     * Report code never reaches this class: it holds the audit writer, so {@code ReportingNeverWritesTest} refuses any
     * reporting class on a path to it - a translated report reads its pinned set through {@link ValuationRateReads}
     * (`P14-TSK-015`), and the claim's own check is the store's read under the period row. Unlocked; a writer that depends
     * on it re-reads it under its own lock.
     */
    public Optional<Valued> approvedFor(Connection unitOfWork, AccountingPeriodId period) {
        return store.approvedFor(unitOfWork, period).map(row -> valued(unitOfWork, row, false));
    }

    // ------------------------------------------------------------------ plumbing

    private Valued valued(Connection unitOfWork, ValuationRateSetStore.SetRow row, boolean replayed) {
        return new Valued(row.id(), row.period(), row.month(), row.status(), row.asOf(), row.maxAgeHours(),
                store.rates(unitOfWork, row.id()), replayed);
    }

    private Valued valued(Connection unitOfWork, ValuationRateSetStore.SetRow row, ValuationRateSetStatus decided) {
        return new Valued(row.id(), row.period(), row.month(), decided, row.asOf(), row.maxAgeHours(),
                store.rates(unitOfWork, row.id()), false);
    }

    /** An authenticated human - {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a valuation rate set's capture or decision is a person's - never the"
                    + " platform's, a service's or a merchant's");
        }
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            AccountingAuditAction action,
            ValuationRateSetId id,
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
