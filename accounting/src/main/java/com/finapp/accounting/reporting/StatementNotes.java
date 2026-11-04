package com.finapp.accounting.reporting;

import com.finapp.accounting.CloseRecordReads;
import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.accounting.ReconciliationAsOfCutoff;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.SuspenseAsOfCutoff;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A financial statement's notes (`P14-TSK-016`; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-05}; `P14-TSK-013`'s note: {@code report_run} holds none, so they are <strong>part of the content, under the definition's version</strong>).
 * Both statements write the same set, so a reader of either has the disclosures. Each is read through a port on the run's snapshot, and each states the route from what it counts to what it counts.
 *
 * <p><strong>Frozen, every one, for a FINAL run</strong>: {@code suspense} and {@code reconciliationBreaks} are as of the first instant after the period's last day, by {@link SuspenseAsOfCutoff} and {@link ReconciliationAsOfCutoff}, and so a release, a resolution, an adoption or a day's
 * ageing after it changes no byte - never {@code ReconciliationDisclosure.asOfCutoff}, which moves. {@code closeRecord} is the approved {@code period_close} at the run's revision, decided once, with its insert-only checks: a FINAL statement exists only under {@code CLOSED}, whose one edge is a
 * reopen, which supersedes it. {@code priorPeriodCorrections} is by posting date, and nothing can be dated into a locked month. {@code deferredTreatments} and {@code referenceChart} are this class's words: a change to them is a new version.
 *
 * <p><strong>Across revisions.</strong> A re-closed month's statement is a new run under a new lock. {@code closeRecord} is its own revision's, {@code earlierCloses} names each first close, which stands beside it. {@code suspense} and {@code reconciliationBreaks} read the same instant, so they read the same: what a
 * reopen posted shows in the ledger's row, not the register. {@code priorPeriodCorrections} is of the month's own dates: an adjustment dated later that corrects this month is disclosed where it is dated, naming this month and the revision it corrected, and is never added here.
 *
 * <p><strong>Classification</strong>: a statement is read under {@code ACCOUNTING_READ}, content {@code CONFIDENTIAL}. A note holds codes, counts, ids, frozen stamps and the register's gross figures: no person, no reason and no evidence. A {@code CONFIDENTIAL} reason on a {@code DISCLOSED} attempt is the approver's to read
 * ({@code ACCOUNTING_CLOSE_APPROVE}) and is neither read nor quoted ({@link CloseRecordReads}). No {@code toString} here or on what it reads holds free text, because none of it does.
 *
 * <p>Report code: it reads through its ports on the snapshot it is handed and writes nothing.
 */
@RequiredArgsConstructor
public final class StatementNotes {

    /** C6 (the owner's decision, 2026-11-04): true whichever of this task and `P14-TSK-021` merges first. It claims neither that a write-off has occurred nor that none can. */
    static final String WRITE_OFFS_AND_DEFERRED_TREATMENTS = "Write-offs are posted only by a four-eyes command, under LOAN_WRITE_OFF_EXPENSE and BNPL_WRITE_OFF_EXPENSE, with no automatic charge-off."
            + " Provisioning and effective-interest amortisation are not applied: each is a reporting difference deferred for qualified review, so a receivable here is its ledger balance, with no allowance against it.";

    /** C7, and `P14-TSK-015`'s review, M-7: the note says so, on a plain run and a translated one alike. */
    static final String UNREALISED_FX = "Translation is a reporting act at the run's one pinned valuation rate set: nothing is posted and no journal line or executed rate changes. Realised FX results are reported from their posted purposes; unrealised revaluation is"
            + " not posted. Each translated column is translated on its own and foots through its difference line, so a translated row need not cross-foot. A translated closing is at this period's set and last period's at its own, so when rates moved the two are not"
            + " comparable: that gap is the unrealised effect, which no figure here states and which a reader finds by comparing two runs.";

    /** {@code PHASE_14_PLAN.md} section 1.1. */
    static final String REFERENCE_CHART = "The GL chart, these statement sections and this layout are reference artefacts, not a statutory chart or a filed statement. Codes are illustrative, and nothing here claims compliance with any accounting framework or regulator;"
            + " a real framework needs qualified review.";

    static final String REVERSALS_FINAL = "Each original is dated in a closed month, or before the first accounting period: a lock needs its previous period closed, so with this month's lock standing every month before it is closed.";

    static final String REVERSALS_PROVISIONAL = "Each original is dated before the month, which may still be open: this month is not locked, so nothing yet says those before it are closed.";

    private static final String CORRECTIONS_RULE = "A correction is disclosed in the month it is dated, naming the month it corrects: nothing is added to a closed month's FINAL bytes. A month reopened and closed again is a new statement beside this one; an adjustment names the revision it corrected, as posted.";

    private static final String REGISTER_APART = "The register and the balance sheet's SUSPENSE_UNMATCHED row stand apart, never netted or reconciled to each other here: an item adopted after the instant is in the ledger's figure and not yet in the register's. A CREDIT item never nets a DEBIT item.";

    private static final String ACCOUNTING_BREAKS_RULE = "As the close recorded them at its proposal, frozen: a later resolution changes neither. A close is approved only with no break of its month OPEN, so none stood open at the approval.";

    private static final String REASONS_NOT_QUOTED = "A person's reason for a DISCLOSED attempt is CONFIDENTIAL, read by the approver at GET /v1/operator/accounting/periods/{periodId}/close-proposal under ACCOUNTING_CLOSE_APPROVE, and not quoted here. What an attempt disclosed is read by attempt at GET /v1/operator/accounting/periods/{periodId}/close-run (ACCOUNTING_READ); suspense and open breaks as of the cutoff are the notes beside this one.";

    private static final String SUSPENSE_ROUTE = "Each item's owning break: GET /v1/operator/reconciliation/breaks/{id} and .../trace (RECONCILIATION_INVESTIGATE, a reconciliation operator's and not the accountant's) to its journal entries, then GET /v1/operator/accounting/gl/entries/{entryId} (ACCOUNTING_READ)"
            + " to the lines. The ledger's figure is its row's route.";

    private static final String BREAKS_ROUTE = "Each break by id: GET /v1/operator/reconciliation/breaks/{id} and .../trace (RECONCILIATION_INVESTIGATE, a reconciliation operator's and not the accountant's), then GET /v1/operator/accounting/gl/entries/{entryId} (ACCOUNTING_READ) to the lines.";

    private static final String CLOSE_ROUTE = "Counts: GET /v1/operator/accounting/breaks?period={month}&status={status} (ACCOUNTING_READ), each break's acts stamped, to read as of proposedAt. Checks: GET /v1/operator/accounting/periods/{periodId}/close-run (ACCOUNTING_READ), by attempt.";

    private static final String CORRECTIONS_ROUTE = "An adjustment: GET /v1/operator/accounting/gl/entries/{entry} (ACCOUNTING_READ). Reversals: the month's GET /v1/operator/accounting/gl/accounts/{code}/activity lines of entryType REVERSAL, each entry's view naming what it reverses and that one's date. Untraced: the month's ADJUSTMENT lines,"
            + " less GET /v1/operator/accounting/periods/{periodId}/prior-period-adjustments (ACCOUNTING_READ).";

    @NonNull private final SuspenseAsOfCutoff suspense;
    @NonNull private final ReconciliationAsOfCutoff reconciliation;
    @NonNull private final PriorPeriodCorrections corrections;
    @NonNull private final CloseRecordReads closes;

    /**
     * The content's {@code notes} member for {@code inputs}.
     *
     * @throws ReportRefused when a FINAL run's period has no approved close at its revision and lock
     */
    CanonicalJson.Obj read(Connection snapshot, ReportInputs inputs) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(inputs, "inputs");
        ReconciliationAsOfCutoff.Standing standing = reconciliation.ofCutoff(snapshot, inputs.period());
        return CanonicalJson.object()
                .put("suspense", suspense(snapshot, inputs, standing))
                .put("reconciliationBreaks", reconciliationBreaks(standing))
                .put("closeRecord", closeRecord(snapshot, inputs))
                .put("priorPeriodCorrections", priorPeriodCorrections(snapshot, inputs))
                .put("deferredTreatments", CanonicalJson.object()
                        .put("writeOffsProvisioningAndEffectiveInterest", WRITE_OFFS_AND_DEFERRED_TREATMENTS)
                        .put("unrealisedFx", UNREALISED_FX))
                .put("referenceChart", REFERENCE_CHART);
    }

    private CanonicalJson.Obj suspense(Connection snapshot, ReportInputs inputs, ReconciliationAsOfCutoff.Standing standing) {
        CanonicalJson.Arr items = CanonicalJson.array();
        standing.items().forEach(item -> items.add(CanonicalJson.object()
                .put("item", item.item().toString())
                .put("owningBreak", item.owningBreak().toString())));
        return suspense.ofCutoff(snapshot, inputs.period()).asValue()
                .put("items", items)
                .put("apart", REGISTER_APART)
                .put("route", SUSPENSE_ROUTE);
    }

    private static CanonicalJson.Obj reconciliationBreaks(ReconciliationAsOfCutoff.Standing standing) {
        CanonicalJson.Arr groups = CanonicalJson.array();
        for (ReconciliationAsOfCutoff.OpenBreaks group : standing.breaks()) {
            CanonicalJson.Obj ageing = CanonicalJson.object();
            group.ageing().forEach(ageing::put);
            CanonicalJson.Arr ids = CanonicalJson.array();
            group.breakIds().forEach(id -> ids.add(id.toString()));
            groups.add(CanonicalJson.object()
                    .put("currency", group.currency())
                    .put("scale", group.scale())
                    .put("breaks", group.breaks())
                    .put("valueAtIssueMinor", group.valueAtIssueMinor().toString())
                    .put("ageing", ageing)
                    .put("oldestRaisedOn", group.oldestRaisedOn().toString())
                    .put("breakIds", ids));
        }
        return CanonicalJson.object()
                .put("cutoff", standing.cutoff().toString())
                .put("asOf", standing.asOf().toString())
                .put("groups", groups)
                .put("route", BREAKS_ROUTE);
    }

    /** {@code null} on a PROVISIONAL run: an open month has no close at its revision. */
    private CanonicalJson.Value closeRecord(Connection snapshot, ReportInputs inputs) {
        if (inputs.basis() != RunBasis.FINAL) {
            return CanonicalJson.Null.INSTANCE;
        }
        CloseRecordReads.CloseRecord record = closes.ofRevision(snapshot, inputs.periodId(), inputs.periodRevision())
                .filter(found -> inputs.periodLockedAt().filter(found.lockedAt()::equals).isPresent())
                .orElseThrow(() -> new ReportRefused(ReportRefused.Reason.CLOSE_RECORD_MISSING, List.of()));
        CanonicalJson.Arr checks = CanonicalJson.array();
        long disclosed = 0;
        for (CloseRecordReads.Check check : record.checks()) {
            checks.add(CanonicalJson.object()
                    .put("check", check.check())
                    .put("status", check.status())
                    .put("attempt", check.attempt())
                    .put("attemptId", check.attemptId().toString())
                    .put("judgedBy", check.judgedBy()));
            if ("DISCLOSED".equals(check.status())) {
                disclosed++;
            }
        }
        CanonicalJson.Arr earlier = CanonicalJson.array();
        record.earlier().forEach(close -> earlier.add(CanonicalJson.object()
                .put("revision", close.revision())
                .put("close", close.close().toString())
                .put("decidedAt", close.decidedAt().toString())));
        return CanonicalJson.object()
                .put("close", record.close().toString())
                .put("revision", record.revision())
                .put("periodLockedAt", record.lockedAt().toString())
                .put("closeRun", record.closeRun().toString())
                .put("pinnedMappingVersion", record.pinnedMappingVersion().toString())
                .put("valuationRateSet", record.valuationRateSet().toString())
                .put("proposedAt", record.proposedAt().toString())
                .put("decidedAt", record.decidedAt().toString())
                .put("accountingBreaks", CanonicalJson.object()
                        .put("acknowledgedAtProposal", record.acknowledged())
                        .put("resolvedAtProposal", record.resolved())
                        .put("rule", ACCOUNTING_BREAKS_RULE))
                .put("checks", checks)
                .put("disclosedChecks", disclosed)
                .put("reasons", REASONS_NOT_QUOTED)
                .put("earlierCloses", earlier)
                .put("route", CLOSE_ROUTE);
    }

    private CanonicalJson.Obj priorPeriodCorrections(Connection snapshot, ReportInputs inputs) {
        PriorPeriodCorrections.Corrections read = corrections.ofMonth(snapshot, inputs.period());
        CanonicalJson.Arr adjustments = CanonicalJson.array();
        for (PriorPeriodCorrections.Adjustment adjustment : read.adjustments()) {
            adjustments.add(CanonicalJson.object()
                    .put("id", adjustment.id().toString())
                    .put("correctedMonth", adjustment.correctedMonth().toString())
                    .put("correctedRevision", adjustment.correctedRevision())
                    .put("entry", adjustment.entry().toString())
                    .put("postingDate", adjustment.postingDate().toString()));
        }
        CanonicalJson.Arr reversals = CanonicalJson.array();
        for (PriorPeriodCorrections.DomainReversals reversal : read.reversals()) {
            reversals.add(CanonicalJson.object()
                    .put("namespace", reversal.namespace())
                    .put("originalMonth", reversal.originalMonth().toString())
                    .put("entries", reversal.entries()));
        }
        return CanonicalJson.object()
                .put("adjustments", adjustments)
                .put("domainReversals", reversals)
                .put("reversalsMean", inputs.basis() == RunBasis.FINAL ? REVERSALS_FINAL : REVERSALS_PROVISIONAL)
                .put("untraced", read.untraced())
                .put("rule", CORRECTIONS_RULE)
                .put("route", CORRECTIONS_ROUTE);
    }
}
