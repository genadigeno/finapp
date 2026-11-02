package com.finapp.accounting.reporting;

import com.finapp.accounting.ReportKind;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.accounting.TrialBalanceReads;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * The {@code SUSPENSE} report definition, version 1 (`P14-TSK-017`; {@code PHASE_14_PLAN.md} section 13.6; {@code INV-ACC-04},
 * {@code INV-ACC-05}, {@code INV-REC-05}): the ledger's {@code SUSPENSE_UNMATCHED} balance by currency - the financial truth - and
 * beside it reconciliation's register as of the cutoff: items and remainders per side, gross, and their ageing. The two are shown
 * apart and never netted or reconciled to each other here: an item adopted after the instant is in the ledger's figure and not yet
 * in the register's, and saying so is the point.
 *
 * <p><strong>FINAL, because its register read is frozen</strong> ({@link SuspenseAsOfCutoff}): an adoption, a release or a resolution
 * after the lock changes no byte. Not {@code ReconciliationDisclosure.asOfCutoff}, whose read does move.
 *
 * <p>Report code: it reads through its ports on the snapshot it is handed and writes nothing. No rate set.
 */
public final class SuspenseDefinition implements ReportDefinition {

    /** The definition's code - {@code ReportKind.SUSPENSE}. */
    public static final String CODE = ReportKind.SUSPENSE.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 1;

    private static final ReportStatement STATEMENT = new ReportStatement(
            "The ledger's SUSPENSE_UNMATCHED journal lines by posting date, and reconciliation's suspense items, their append-only"
                    + " releases and their owning breaks' frozen raise stamps; no projection, cache or gauge.",
            "Per currency the ledger's opening, debits, credits and closing, debit-positive. Per currency and scale the register's"
                    + " items with a remainder at the cutoff's instant - opened through the last day, the owning break raised"
                    + " before the instant, less releases before it - CREDIT and DEBIT gross, never netted, aged from the day"
                    + " opened to the last day in the operator reports' bands.",
            "The ledger's lines by posting date through the period's last day. The register as of the first instant after that"
                    + " day, UTC - at least the lock's grace in the past, so no later act can move it.",
            "Per currency and scale, as stored; the ledger's rows at the largest scale their own amounts carry. Never summed across"
                    + " currencies, sides or scales. Minor units in strings. Not translated: no run takes a valuation rate set.",
            "FINAL for a period no longer OPEN: byte-identical on regeneration while its lock stands, superseded by an unlock or a"
                    + " reopen. PROVISIONAL for an OPEN period: the register as things stand, labelled, never issued.");

    private final PurposeReportDefinition ledger;
    private final SuspenseAsOfCutoff register;

    public SuspenseDefinition(TrialBalanceReads reads, SuspenseAsOfCutoff register) {
        this.ledger = new PurposeReportDefinition(CODE, PurposeReportDefinition.Window.BALANCES,
                List.of(new PurposeReportDefinition.Section("LEDGER", List.of("SUSPENSE_UNMATCHED/SUSPENSE"))), STATEMENT,
                reads);
        this.register = Objects.requireNonNull(register, "register");
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
        return ledger.generate(snapshot, inputs).put("register", register.ofCutoff(snapshot, inputs.period()).asValue());
    }
}
