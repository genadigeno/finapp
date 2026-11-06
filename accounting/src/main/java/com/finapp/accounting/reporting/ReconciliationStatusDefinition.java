package com.finapp.accounting.reporting;

import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.ReportKind;
import java.sql.Connection;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The {@code RECONCILIATION_STATUS} report definition, version 3, <strong>operational and never a run</strong> (`P14-TSK-017`;
 * {@code PHASE_14_PLAN.md} section 13.6; {@code INV-ACC-05}; owner decision C11; the owner's decision of 2026-11-02): how
 * reconciliation stands for a month. It reads, and copies nothing ({@link OperationalReportDefinition}).
 *
 * <ul>
 *   <li><strong>The month's runs</strong> ({@link ReconciliationRunCoverage}): per source, kind, status, business date and repudiation, with the source register, and no verdict: the
 * check judges them (`P14-TSK-012`, the owner's rule). A late batch joins the month after any lock. Each source with its admission and retirement days, the day its rule set
 * first stood active, and whether the month requires it - the check's own rule, so a reader of {@code finapp.accounting.close.month.unrun} sees why (`P14-TSK-025`).
 *   <li><strong>The disclosure</strong> ({@link ReconciliationDisclosure}): the close's own reading, breaks and suspense as of the
 * cutoff and what the grace days added.
 *   <li><strong>Which past-the-cutoff entry is which</strong> ({@code Disclosure.pastTheCutoff}): the same read the check judges, so the two cannot disagree (the owner's decision (b) of
 * 2026-11-03): per namespace the floored, the unrecorded and the rule's own dating.
 * </ul>
 *
 * <p><em>Version 1 (`P14-TSK-017`) read the past-the-cutoff split apart from the disclosure and told no missing record; version 2 (`P14-TSK-012`) gave each source its
 * identity and state alone. Never stored, so no reader holds either.</em>
 *
 * <p>Why it cannot be FINAL: every one of those three moves after the lock, by design. What it reads is on one snapshot, so it agrees
 * with itself; {@code asOf} (the database's instant, {@link ReportInputs#cutAt()}) says when. No rate set; no person; no reference.
 */
@RequiredArgsConstructor
public final class ReconciliationStatusDefinition implements OperationalReportDefinition {

    /** The definition's code - {@code ReportKind.RECONCILIATION_STATUS}. */
    public static final String CODE = ReportKind.RECONCILIATION_STATUS.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 3;

    private static final ReportStatement STATEMENT = new ReportStatement(
            "Reconciliation's own runs, breaks, suspense items and releases through its report readings, the ledger's"
                    + " SUSPENSE_UNMATCHED lines and past-the-cutoff entries with their audit marks. Read here, copied nowhere; no"
                    + " accounting break raised; no projection, cache or gauge.",
            "The month's runs per source, kind, status, business date and repudiation, of any status and with no verdict on the month, and the"
                    + " settlement sources, each with its admission and retirement days, the day its rule set first stood active and whether the month"
                    + " requires its run, by the close check's own rule; the close's disclosure, gross per currency and scale; per key namespace of the floored rules other than LND-2 (whose"
                    + " entries are ACCRUALS_COMPLETE's) the entries valued by the cutoff and posted after it, split into floored"
                    + " (flooredFrom= on the posting's audit record), unrecorded (no such record) and ownValueDate, the rest: a record present with no mark.",
            "Runs by their business date within the calendar month. Breaks and suspense as of the first instant after the last day,"
                    + " UTC; what the grace days added apart. The whole as of asOf, the database's instant at the read.",
            "Per currency and scale as stored; never summed across currencies, sides or scales; never netted. Minor units in"
                    + " strings. Not translated.",
            "OPERATIONAL: never FINAL and never a report run, because a late batch, a run's status, a resolved break and every"
                    + " later posting move it after any lock. Generated at each read, stored nowhere, each read audited. Answered"
                    + " within the door's time bound, 30 seconds unless configured, or refused and nothing served: a long-past month"
                    + " reads every entry posted since, and may no longer answer inside it.");

    @NonNull private final ReconciliationDisclosure disclosure;
    @NonNull private final ReconciliationRunCoverage coverage;

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
        return CanonicalJson.object()
                .put("statement", STATEMENT.asValue())
                .put("asOf", inputs.cutAt().toString())
                .put("coverage", coverage.ofMonth(snapshot, inputs.period()).asValue())
                .put("disclosure", disclosure.asOfCutoff(snapshot, inputs.period()).evidence());
    }
}
