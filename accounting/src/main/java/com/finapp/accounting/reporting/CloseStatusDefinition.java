package com.finapp.accounting.reporting;

import com.finapp.accounting.BreakStatus;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseStatusReads;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportKind;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The {@code CLOSE_STATUS} report definition, version 1, <strong>operational and never a run</strong> (`P14-TSK-017`;
 * {@code PHASE_14_PLAN.md} sections 8.1, 13.5, 13.6; {@code INV-ACC-09}): where a period's close stands, for a reader who asks. Attempts
 * conclude and re-run, the period moves, breaks move, a rate set is approved - all under one lock - so a stored answer would be wrong by
 * the next poll and a FINAL one wrong for good ({@link OperationalReportDefinition}).
 *
 * <p><strong>The run's state</strong> ({@link RunState}): the run is born by the check leg, not in T-lock (a recorded deviation), so
 * {@code LOCKED} with no run is ordinary for a poll, and past {@link CloseRunStall#after()} is {@link RunState#STALLED}. A report speaks
 * only when asked, so this is not the signal: {@code finapp.accounting.close.run.stalled} and its alert are, on the same bound.
 *
 * <p><strong>The approved set</strong> (`P14-TSK-015`'s review, M-4): named here, under {@code ACCOUNTING_READ}, so the role that asks
 * for a translated run can find what it must name, which it cannot read at {@code GET .../valuation-rate-sets/{setId}}.
 *
 * <p>Codes, counts and instants only: no person, no reason, no evidence and no figure ({@code CloseRunDesk} serves those). Report code:
 * it reads through {@link CloseStatusReads} on the snapshot it is handed; every age is from {@link ReportInputs#cutAt()}, the database's
 * instant.
 */
@RequiredArgsConstructor
public final class CloseStatusDefinition implements OperationalReportDefinition {

    /** The definition's code - {@code ReportKind.CLOSE_STATUS}. */
    public static final String CODE = ReportKind.CLOSE_STATUS.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 1;

    /** Where a period's close run stands. */
    public enum RunState {
        /** The period is {@code OPEN}: there is no lock for a run to answer. */
        NOT_LOCKED,
        /** Locked, no run under this lock, within the bound: the ordinary gap before the check leg's poll. */
        AWAITING_FIRST_POLL,
        /** Locked, no run under this lock, past the bound: the check leg is not running. */
        STALLED,
        /** The run answers this lock and an attempt has not concluded. */
        RUNNING,
        /** The run answers this lock and its checks have all first concluded. */
        CONCLUDED
    }

    private static final ReportStatement STATEMENT = new ReportStatement(
            "Accounting's own period, close run, close check attempts, breaks and valuation rate sets. No journal line and no"
                    + " other module's table; no projection, cache or gauge.",
            "The period's status, revision, lock and pinned version number; the run's state; each check's latest attempt -"
                    + " status, attempts, failure codes, the versions that judged it, when it concluded; the month's breaks by status;"
                    + " the approved valuation rate set by id, and how many sets per status.",
            "The period's calendar month. Every instant as the database stamped it; the whole as of asOf, the database's instant at"
                    + " the read; the run's state judged from that instant against the lock's.",
            "Holds no amount, in any currency: codes, counts and instants only. The valuation rate set is named by its id, never a"
                    + " rate. Nothing to translate.",
            "OPERATIONAL: never FINAL and never a report run, because attempts, the period's status, breaks and the approved set all"
                    + " move under one lock. Generated at each read, stored nowhere, each read audited. Answered within the door's"
                    + " time bound, 30 seconds unless configured, or refused and nothing served.");

    @NonNull private final CloseStatusReads reads;
    @NonNull private final CloseRunStall stall;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public ReportStatement statement() {
        return STATEMENT;
    }

    @Override
    public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
        CloseStatusReads.Reading reading = reads.read(snapshot, inputs.periodId());
        CanonicalJson.Obj breaks = CanonicalJson.object();
        for (BreakStatus status : BreakStatus.values()) {
            breaks.put(status.name(), reading.breaks().getOrDefault(status, 0L));
        }
        CanonicalJson.Obj sets = CanonicalJson.object();
        reading.setsByStatus().forEach(sets::put);
        CanonicalJson.Obj body = CanonicalJson.object()
                .put("statement", STATEMENT.asValue())
                .put("asOf", inputs.cutAt().toString())
                .put("period", reading.month().toString())
                .put("status", reading.status().name())
                .put("revision", reading.revision())
                .put("runState", state(reading, inputs.cutAt()).name())
                .put("runStalledAfterSeconds", stall.after().toSeconds())
                .put("breaks", breaks)
                .put("valuationRateSets", sets);
        instantOrNull(body, "lockedAt", reading.lockedAt());
        if (reading.pinnedVersion().isPresent()) {
            body.put("pinnedMappingVersion", reading.pinnedVersion().get());
        } else {
            body.putNull("pinnedMappingVersion");
        }
        if (reading.run().isPresent()) {
            body.put("run", run(reading.run().get()));
        } else {
            body.putNull("run");
        }
        if (reading.approvedSet().isPresent()) {
            body.put("approvedValuationRateSet", approved(reading.approvedSet().get()));
        } else {
            body.putNull("approvedValuationRateSet");
        }
        return body;
    }

    /** The run's state, from the reading alone and the instant of the read. */
    RunState state(CloseStatusReads.Reading reading, Instant asOf) {
        if (reading.status() == PeriodStatus.OPEN || reading.lockedAt().isEmpty()) {
            return RunState.NOT_LOCKED;
        }
        Optional<CloseStatusReads.Run> current = reading.run().filter(CloseStatusReads.Run::current);
        if (current.isPresent()) {
            return current.get().checksConcludedAt().isPresent() ? RunState.CONCLUDED : RunState.RUNNING;
        }
        return Duration.between(reading.lockedAt().get(), asOf).compareTo(stall.after()) >= 0
                ? RunState.STALLED
                : RunState.AWAITING_FIRST_POLL;
    }

    private static CanonicalJson.Obj run(CloseStatusReads.Run run) {
        CanonicalJson.Arr checks = CanonicalJson.array();
        for (CloseStatusReads.Check check : run.checks()) {
            CanonicalJson.Arr failures = CanonicalJson.array();
            check.failures().forEach(failures::add);
            CanonicalJson.Obj out = CanonicalJson.object()
                    .put("check", check.check().name())
                    .put("status", check.status().name())
                    .put("attempts", check.attempts())
                    .put("failures", failures);
            if (check.judgedBy().isPresent()) {
                out.put("judgedBy", check.judgedBy().get());
            } else {
                out.putNull("judgedBy");
            }
            instantOrNull(out, "concludedAt", check.concludedAt());
            checks.add(out);
        }
        CanonicalJson.Obj out = CanonicalJson.object()
                .put("lockedAt", run.lockedAt().toString())
                .put("current", run.current())
                .put("complete", run.complete())
                .put("startedAt", run.startedAt().toString())
                .put("checks", checks);
        instantOrNull(out, "checksConcludedAt", run.checksConcludedAt());
        return out;
    }

    private static CanonicalJson.Obj approved(CloseStatusReads.ApprovedSet set) {
        CanonicalJson.Obj out = CanonicalJson.object()
                .put("id", set.id().toString())
                .put("asOf", set.asOf().toString())
                .put("rateCount", set.rateCount());
        instantOrNull(out, "decidedAt", set.decidedAt());
        return out;
    }

    private static void instantOrNull(CanonicalJson.Obj out, String member, Optional<Instant> instant) {
        if (instant.isPresent()) {
            out.put(member, instant.get().toString());
        } else {
            out.putNull(member);
        }
    }
}
