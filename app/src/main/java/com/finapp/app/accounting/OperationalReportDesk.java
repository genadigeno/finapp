package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.OperationalReports;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the operational report door (`P14-TSK-017`; ADR-0115 As built; the {@code ReportRunDesk} and
 * {@code CloseRunDesk} shape): generate on a read-only snapshot, record the read in a transaction of its own, then serve - a failed
 * record fails the read, so nothing is served unrecorded. No key: a read changes nothing, and a repeat is a new answer as of its own
 * instant. No connection is held across anything: the snapshot is rolled back before the record is written.
 *
 * <p>What leaves: the issued canonical JSON text, exactly the bytes {@code contentSha256} hashes - {@code CONFIDENTIAL} aggregates
 * and codes, never a person, a reason or a reference.
 */
@RequiredArgsConstructor
public final class OperationalReportDesk {

    static final String READ_SPAN = "accounting.operationalreport.read";

    @NonNull private final OperationalReports reports;
    @NonNull private final ReportSnapshots snapshots;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    /**
     * An operational report: what was asked, the database's instant it is as of, and its content. {@code basis} is always
     * {@code OPERATIONAL}: there is no run, no revision and no regeneration.
     */
    public record OperationalReportView(
            String definition,
            int definitionVersion,
            String periodId,
            String period,
            String basis,
            String asOf,
            String contentSha256,
            String content) {}

    /** {@code definition}'s answer for the period, recorded {@code accounting.OperationalReportRead} before it is served. */
    public OperationalReportView read(String rawPeriodId, String definition) {
        AccountingPeriodId id = periodId(rawPeriodId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an operational report is read inside a correlation scope"))
                .correlationId();
        return flow.door(READ_SPAN, () -> {
            OperationalReports.Answer answer;
            try {
                answer = snapshots.read(snapshot -> reports.answer(snapshot, definition, id));
            } catch (ReportRuns.DefinitionUnknown unknown) {
                throw refused(AccountingErrorCode.REPORT_DEFINITION_UNKNOWN, unknown);
            } catch (OperationalReports.NotOperational misdirected) {
                throw refused(AccountingErrorCode.REPORT_DOOR_MISMATCH, misdirected);
            } catch (AccountingPeriods.PeriodNotFound unknown) {
                throw notFound();
            } catch (ReportGeneration.GenerationTimedOut timedOut) {
                throw new ApiException(AccountingErrorCode.READ_TIMED_OUT, "An operational report was refused",
                        "the report did not finish within its time bound; nothing was served - retry.");
            }
            transactions.inTransaction(unitOfWork -> {
                reports.recordRead(unitOfWork, answer, actor, correlation);
                return null;
            });
            return new OperationalReportView(answer.definition(), answer.version(), answer.periodId().value().toString(),
                    answer.month().toString(), ReportGeneration.OPERATIONAL, answer.asOf().toString(),
                    answer.generated().sha256(), answer.generated().content());
        });
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "An operational report was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No accounting period answers the request", "no such record.");
    }

    private static AccountingPeriodId periodId(String raw) {
        try {
            return AccountingPeriodId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }
}
