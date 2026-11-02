package com.finapp.accounting.reporting;

import com.finapp.accounting.PastTheCutoffPostings;
import com.finapp.accounting.ReconciliationDisclosure;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.ReportKind;
import java.sql.Connection;
import java.util.Objects;

/**
 * The {@code RECONCILIATION_STATUS} report definition, version 1, <strong>operational and never a run</strong> (`P14-TSK-017`;
 * {@code PHASE_14_PLAN.md} section 13.6; {@code INV-ACC-05}; owner decision C11; the owner's decision of 2026-11-02): how
 * reconciliation stands for a month. It reads, and copies nothing ({@link OperationalReportDefinition}).
 *
 * <ul>
 *   <li><strong>The month's runs</strong> ({@link ReconciliationRunCoverage}): per source, kind, status and business date - with whether
 * the month has any batch run at all, which {@code P14-TSK-012}'s check fails on. A late batch joins the month after any lock.
 *   <li><strong>The disclosure</strong> ({@link ReconciliationDisclosure}): the close's own reading, breaks and suspense as of the
 * cutoff and what the grace days added.
 *   <li><strong>Which past-the-cutoff entry is which</strong> ({@link PastTheCutoffPostings}; `P14-TSK-011`'s review, M-2): the
 * disclosure's count per namespace split into the floored and the rule's own dating.
 * </ul>
 *
 * <p>Why it cannot be FINAL: every one of those three moves after the lock, by design. What it reads is on one snapshot, so it agrees
 * with itself; {@code asOf} (the database's instant, {@link ReportInputs#cutAt()}) says when. No rate set; no person; no reference.
 */
public final class ReconciliationStatusDefinition implements OperationalReportDefinition {

    /** The definition's code - {@code ReportKind.RECONCILIATION_STATUS}. */
    public static final String CODE = ReportKind.RECONCILIATION_STATUS.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 1;

    private static final ReportStatement STATEMENT = new ReportStatement(
            "Reconciliation's own runs, breaks, suspense items and releases through its report readings, the ledger's"
                    + " SUSPENSE_UNMATCHED lines and past-the-cutoff entries with their audit marks. Read here, copied nowhere; no"
                    + " accounting break raised; no projection, cache or gauge.",
            "The month's runs per source, kind, status and business date; the close's disclosure, gross per currency and scale; per"
                    + " floored rule's namespace the entries valued by the cutoff and posted after it, split into floored"
                    + " (flooredFrom= on the entry's audit record) and the rule's own dating.",
            "Runs by their business date within the calendar month. Breaks and suspense as of the first instant after the last day,"
                    + " UTC; what the grace days added apart. The whole as of asOf, the database's instant at the read.",
            "Per currency and scale as stored; never summed across currencies, sides or scales; never netted. Minor units in"
                    + " strings. Not translated.",
            "OPERATIONAL: never FINAL and never a report run, because a late batch, a run's status, a resolved break and every"
                    + " later posting move it after any lock. Generated at each read, stored nowhere, each read audited.");

    private final ReconciliationDisclosure disclosure;
    private final ReconciliationRunCoverage coverage;
    private final PastTheCutoffPostings postings;

    public ReconciliationStatusDefinition(ReconciliationDisclosure disclosure, ReconciliationRunCoverage coverage,
            PastTheCutoffPostings postings) {
        this.disclosure = Objects.requireNonNull(disclosure, "disclosure");
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.postings = Objects.requireNonNull(postings, "postings");
    }

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
        CanonicalJson.Obj past = CanonicalJson.object();
        postings.pastTheCutoff(snapshot, inputs.end()).forEach((namespace, split) -> past.put(namespace, split.asValue()));
        return CanonicalJson.object()
                .put("statement", STATEMENT.asValue())
                .put("asOf", inputs.cutAt().toString())
                .put("coverage", coverage.ofMonth(snapshot, inputs.period()).asValue())
                .put("disclosure", disclosure.asOfCutoff(snapshot, inputs.period()).evidence())
                .put("pastTheCutoff", past);
    }
}
