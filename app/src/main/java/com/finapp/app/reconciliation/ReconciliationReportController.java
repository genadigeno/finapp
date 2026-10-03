package com.finapp.app.reconciliation;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.ReconciliationAuditAction;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reconciliation reports (`P8-TSK-007`, ADR-0072): the positions report — per clearing
 * position and currency, the identity's two sides and their difference — and, since
 * `P8-TSK-024`, the suspense, unmatched, summary and provider-costs reports, composed and
 * audited by {@link ReconciliationReports}. It carries amounts,
 * which is exactly why it is a REPORT and never a metric, and why every serving is on the
 * record ({@code reconciliation.ReportRead}, the {@code payments.ChargebackRatioRead}
 * precedent). Under {@link PermissionName#RECONCILIATION_INVESTIGATE}; read-only but for
 * the audit record. Handler names are deliberately distinctive (the springdoc
 * {@code operationId} rule).
 */
@RestController
@RequestMapping(
        path = "/operator/reports/reconciliation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationReportController {

    /** The rows' bound — the platform's listing bound, stated even though 12 is the ceiling. */
    static final int BOUND = 100;

    @NonNull private final PositionProof proof;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionTemplate reconciliationTransactions;
    @NonNull private final DataSource dataSource;

    /** The four remaining reports (`P8-TSK-024`), composed and audited in their own service. */
    @NonNull private final ReconciliationReports reconciliationReports;

    /**
     * One position-and-currency row: the identity's terms and the difference. Since
     * `P8-TSK-009` the identity carries the items term — {@code difference} is
     * balance − (remainders − items) — and the two new fields are ADDED at the end
     * (ADR-0015: additive).
     */
    public record PositionRow(
            String purpose,
            String currency,
            String ledgerBalance,
            String openRemainders,
            String difference,
            long openExpectations,
            long unattributedLines,
            boolean explained,
            String openItems,
            long openItemCount) {}

    /**
     * One currency's cash row (`P8-TSK-016`, {@code INV-SET-06}): {@code CASH_AT_BANK}'s balance,
     * the head closing of the currency's statement chain, their difference, the latest accepted
     * sequence, and whether the chain is unbroken and explains the balance.
     */
    public record CashRow(
            String currency,
            String ledgerBalance,
            String chainClosing,
            String difference,
            long latestSequence,
            boolean unbroken,
            boolean explained) {}

    /** The positions, and — added at the end (ADR-0015: additive) — the cash rows. */
    public record PositionsReport(
            List<PositionRow> positions, boolean truncated, List<CashRow> cash) {}

    /** The positions report — audited per serving, in the reading's own transaction. */
    @GetMapping("/positions")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public PositionsReport readReconciliationPositionsReport() {
        return inOneTransaction(
                unitOfWork -> {
                    unitOfWork.setTransactionIsolation(
                            Connection.TRANSACTION_REPEATABLE_READ);
                    PositionProof.Report report = proof.sweep(unitOfWork);
                    audit.append(
                            unitOfWork,
                            new AuditRecord(
                                    AuditId.next(ids),
                                    SecurityContext.require(),
                                    Instant.now(clock),
                                    ReconciliationAuditAction.REPORT_READ,
                                    "reconciliation_report",
                                    "positions",
                                    Optional.empty(),
                                    AuditOutcome.SUCCEEDED,
                                    CorrelationContext.current()
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "every request runs in a"
                                                                            + " correlation"
                                                                            + " scope"))
                                            .correlationId(),
                                    // The report and its shape - never its figures.
                                    Optional.of(
                                            "report=positions, rows="
                                                    + report.verdicts().size())));
                    return render(report);
                });
    }

    /**
     * The suspense report (`P8-TSK-024`, ADR-0072 §3): per currency the
     * {@code SUSPENSE_UNMATCHED} balance, the items CREDIT and DEBIT gross and the suspense
     * proof's verdict; every open item, oldest first, with its age and owning break, bounded
     * with {@code truncated}. Audited per serving, in the reading's own transaction.
     */
    @GetMapping("/suspense")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public ReconciliationReports.SuspenseReport readReconciliationSuspenseReport() {
        return reconciliationReports.suspense();
    }

    /**
     * The unmatched report (`P8-TSK-024`, ADR-0072 §3): per source, currency and direction the
     * unexplained items (inside grace, parked) and open expectations (in window, overdue) with
     * count, value and oldest age; the open breaks' value at issue by type and severity; the
     * oldest items, bounded with {@code truncated}. Audited.
     */
    @GetMapping("/unmatched")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public ReconciliationReports.UnmatchedReport readReconciliationUnmatchedReport() {
        return reconciliationReports.unmatched();
    }

    /**
     * One UTC business date's summary per source (`P8-TSK-024`): {@code date} is
     * {@code YYYY-MM-DD} from 2000-01-01 to today, today when absent; any other value is the
     * 422, refused before anything is read. Audited.
     */
    @GetMapping("/summary")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public ReconciliationReports.DailySummaryReport readReconciliationDailySummaryReport(
            @RequestParam(value = "date", required = false) String date) {
        return reconciliationReports.summary(date);
    }

    /**
     * One month's provider costs per source and currency (`P8-TSK-024`, ADR-0072 §6): charged,
     * evidenced (with the agreement verdict), expected, and the month's fee mismatches.
     * {@code month} is {@code YYYY-MM} from 2000-01 to the current UTC month, the current one
     * when absent; any other value is the 422, refused before anything is read. Audited.
     */
    @GetMapping("/provider-costs")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public ReconciliationReports.ProviderCostsReport readReconciliationProviderCostsReport(
            @RequestParam(value = "month", required = false) String month) {
        return reconciliationReports.providerCosts(month);
    }

    // Package-private: the bound's ceiling in production is ~12 rows (four purposes times
    // the supported currencies), so the truncation branch is proven here, hermetically.
    static PositionsReport render(PositionProof.Report report) {
        List<PositionRow> rows =
                report.verdicts().stream()
                        .limit(BOUND)
                        .map(
                                verdict ->
                                        new PositionRow(
                                                verdict.purpose().name(),
                                                verdict.currency().code(),
                                                verdict.ledgerBalance()
                                                        .toBigDecimal()
                                                        .toPlainString(),
                                                verdict.openRemainders()
                                                        .toBigDecimal()
                                                        .toPlainString(),
                                                // The identity's own subtraction, items
                                                // term included (P8-TSK-009).
                                                verdict.ledgerBalance()
                                                        .minus(
                                                                verdict.openRemainders()
                                                                        .minus(
                                                                                verdict
                                                                                    .openItems()))
                                                        .toBigDecimal()
                                                        .toPlainString(),
                                                verdict.openCount(),
                                                report.unattributedByPurpose()
                                                        .getOrDefault(verdict.purpose(), 0L),
                                                verdict.explained(),
                                                verdict.openItems()
                                                        .toBigDecimal()
                                                        .toPlainString(),
                                                verdict.openItemCount()))
                        .toList();
        List<CashRow> cash =
                report.cashVerdicts().stream()
                        .limit(BOUND)
                        .map(
                                verdict ->
                                        new CashRow(
                                                verdict.currency().code(),
                                                verdict.ledgerBalance()
                                                        .toBigDecimal()
                                                        .toPlainString(),
                                                verdict.chainClosing()
                                                        .toBigDecimal()
                                                        .toPlainString(),
                                                verdict.ledgerBalance()
                                                        .minus(verdict.chainClosing())
                                                        .toBigDecimal()
                                                        .toPlainString(),
                                                verdict.latestSequence(),
                                                verdict.unbroken(),
                                                verdict.explained()))
                        .toList();
        return new PositionsReport(rows, report.verdicts().size() > BOUND, cash);
    }

    private <R> R inOneTransaction(SqlFunction<R> work) {
        return reconciliationTransactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } catch (java.sql.SQLException failure) {
                        throw new IllegalStateException(
                                "the positions report could not set its snapshot", failure);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }

    @FunctionalInterface
    private interface SqlFunction<R> {
        R apply(Connection connection) throws java.sql.SQLException;
    }
}
