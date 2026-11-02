package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.OperationalDoorBound;
import com.finapp.accounting.OperationalReports;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the operational report door (`P14-TSK-017`; ADR-0115 As built; the {@code ReportRunDesk} and
 * {@code CloseRunDesk} shape): generate on a read-only snapshot, record the read in a transaction of its own, then serve - a failed
 * record fails the read, so nothing is served unrecorded. No key: a read changes nothing, and a repeat is a new answer as of its own
 * instant. No connection is held across anything: the snapshot is rolled back before the record is written.
 *
 * <p><strong>The bound</strong> ({@link OperationalDoorBound}; `P14-TSK-017`'s review, I-3): the {@code snapshots} it is handed carry the
 * door's own time bound, and at most {@code permits} reads run at once on this instance, the next refused {@code 503} with nothing
 * borrowed from the pool: never queued, because a queue of readers holding request threads is the outage in another shape. The count is
 * this instance's and protects this instance's pool; it coordinates nothing between instances and no correctness rests on it, which is why
 * it is an instance field and not a {@code Semaphore} ({@code NoSingleInstanceAssumptionRulesTest}'s lock rule refuses that type outright,
 * because it reads like coordination; {@code DISTRIBUTED_EXECUTION.md} section 3 holds this component's row). The permit is held for the
 * snapshot alone: the record's transaction is short and is the postings' own kind of work.
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
    @NonNull private final OperationalDoorBound bound;
    private final AtomicInteger reading = new AtomicInteger();

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
            OperationalReports.Answer answer = answer(definition, id);
            transactions.inTransaction(unitOfWork -> {
                reports.recordRead(unitOfWork, answer, actor, correlation);
                return null;
            });
            return new OperationalReportView(answer.definition(), answer.version(), answer.periodId().value().toString(),
                    answer.month().toString(), ReportGeneration.OPERATIONAL, answer.asOf().toString(),
                    answer.generated().sha256(), answer.generated().content());
        });
    }

    /** The answer, inside this instance's permits: refused before a connection is borrowed when they are all held. */
    private OperationalReports.Answer answer(String definition, AccountingPeriodId id) {
        if (reading.incrementAndGet() > bound.permits()) {
            reading.decrementAndGet();
            throw new ApiException(AccountingErrorCode.READ_TIMED_OUT, "An operational report was refused",
                    "this instance is answering as many operational reports as it may at once; nothing was served - retry.");
        }
        try {
            return snapshots.read(snapshot -> reports.answer(snapshot, definition, id));
        } catch (ReportRuns.DefinitionUnknown unknown) {
            throw refused(AccountingErrorCode.REPORT_DEFINITION_UNKNOWN, unknown);
        } catch (OperationalReports.NotOperational misdirected) {
            throw refused(AccountingErrorCode.REPORT_DOOR_MISMATCH, misdirected);
        } catch (AccountingPeriods.PeriodNotFound unknown) {
            throw notFound();
        } catch (ReportGeneration.GenerationTimedOut timedOut) {
            throw new ApiException(AccountingErrorCode.READ_TIMED_OUT, "An operational report was refused",
                    "the report did not finish within its time bound; nothing was served - retry.");
        } finally {
            reading.decrementAndGet();
        }
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
