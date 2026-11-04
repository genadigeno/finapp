package com.finapp.app.accounting;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlActivity;
import com.finapp.accounting.reporting.PinnedMapping;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A report run's figure drilled to its journal lines (`P14-TSK-016`; {@code INV-ACC-02}; ADR-0115 decision 4): one GL account's lines in one calendar month and currency, under <strong>the run's pinned version</strong>, by keyset page.
 *
 * <p><strong>Why not only the GL's own door.</strong> {@code GET .../gl/accounts/{code}/activity} reads a month under that <em>month's</em> version, which answers a period's movement exactly. An opening is from inception and under this run's version: a purpose whose account moved in an earlier version is under another code there, and a
 * month before any version took effect is {@code accounting.NoActiveMapping}, so no door could list its lines at all. A figure with no route is a defect. So the version here is the run's, and the months walk back from its period until the row's {@code openingLines} are counted: a page holds at most {@code
 * JournalReads.LineQuery.MAX_LIMIT} lines of one month.
 *
 * <p>The run, the version, the month's own status ({@code NONE} when no period row holds it) and the lines in one {@code REPEATABLE READ READ ONLY} snapshot ({@link GlSnapshots}), the read recorded {@code accounting.GlRead} in a transaction of its own <strong>before</strong> it is answered: the {@code GeneralLedgerDesk} order, and its refusals. A month after the run's period is {@code api.ValidationFailed}: no figure of the
 * run reads it. {@code exact} is whether the pages sum to the figure: true of a {@code FINAL} run, whose months are immutable; a {@code PROVISIONAL} run was cut at its claim and a {@code SUPERSEDED} one's period moved, and the lines are the journal's as it stands.
 *
 * <p>What leaves is {@code GeneralLedgerDesk.GlActivityView}: figures and lines with owner references and counterparty codes, never a name, the entry's reference, a reason or an actor. The summary carries the door, the run, the month, the account, the currency, the version and a count, never an amount ({@code INV-AUD-02}).
 */
@RequiredArgsConstructor
public final class ReportRunLinesDesk {

    static final String SPAN = "accounting.report.lines";

    /** The audit record's target type. */
    static final String TARGET = "report_run";

    @NonNull private final ReportRuns runs;
    @NonNull private final GlMappingStore mappings;
    @NonNull private final GlPins.PeriodPins periods;
    @NonNull private final GeneralLedger ledger;
    @NonNull private final GlSnapshots snapshots;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final AccountingFlowScope flow;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * A run's figure's lines in one month.
     *
     * @param run the run
     * @param status its status - {@code exact} follows from it
     * @param exact whether the pages sum to the figure
     * @param activity the month's figure under the run's version, and one page of its lines
     */
    public record ReportRunLinesView(String run, String status, boolean exact, GeneralLedgerDesk.GlActivityView activity) {}

    /** One page of what composes {@code rawCode}'s figure in {@code rawMonth}, under the run's version. */
    public ReportRunLinesView lines(String rawRunId, String rawCode, String rawMonth, String rawCurrency, String rawAfter, Integer rawLimit) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = GeneralLedgerDesk.correlation();
        ReportRunId runId = runId(rawRunId);
        String code = GeneralLedgerDesk.code(rawCode);
        YearMonth month = GeneralLedgerDesk.period(rawMonth);
        if (rawCurrency == null) {
            throw GeneralLedgerDesk.invalid("a figure's lines are read in one currency");
        }
        String currency = GeneralLedgerDesk.currency(rawCurrency);
        Optional<JournalReads.LineCursor> after = GeneralLedgerDesk.after(rawAfter, month);
        int limit = GeneralLedgerDesk.limit(rawLimit);
        Served served = GeneralLedgerDesk.guarded(() -> flow.door(SPAN, () -> snapshots.read(snapshot -> {
            ReportRunStore.RunRow run = runs.find(snapshot, runId).orElseThrow(GeneralLedgerDesk::notFound);
            if (month.isAfter(run.period())) {
                throw GeneralLedgerDesk.invalid("no figure of this run reads a month after its period");
            }
            GlMappingVersionId version = run.mappingVersionId().orElseThrow(() -> new GlPins.NoActiveMapping("this report run pinned no GL mapping version"));
            int number = run.mappingVersion().orElseThrow(() -> new IllegalStateException("a pinned mapping version names its id and its number together"));
            return new Served(run, ledger.activity(snapshot, new PinnedMapping(month, periods.of(snapshot, month).map(GlPins.PeriodPin::status), version, number, mappings.content(snapshot, version)), code, currency, after, limit));
        })));
        transactions.inTransaction(unitOfWork -> {
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), AccountingAuditAction.GL_READ, TARGET, runId.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED,
                    correlation, Optional.of("door=report-run-lines, month=" + month + ", account=" + code + ", currency=" + currency + ", version=" + served.activity().mapping().version() + ", lines="
                            + served.activity().lines().size() + ", page=" + (after.isPresent() ? "next" : "first"))));
            return null;
        });
        return new ReportRunLinesView(runId.value().toString(), served.run().status().name(), served.run().status() == RunStatus.FINAL, GeneralLedgerDesk.GlActivityView.of(served.activity()));
    }

    /** What one snapshot read. */
    private record Served(ReportRunStore.RunRow run, GlActivity activity) {}

    private static ReportRunId runId(String raw) {
        try {
            return ReportRunId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw GeneralLedgerDesk.notFound();
        }
    }
}
