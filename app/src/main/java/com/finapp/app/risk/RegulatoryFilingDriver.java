package com.finapp.app.risk;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.FilingOutcome;
import com.finapp.risk.RegulatoryReport;
import com.finapp.risk.RegulatoryReportId;
import com.finapp.risk.RegulatoryReports;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The filing's drive (`P13-TSK-021`; {@code PHASE_13_PLAN.md} sections 7.1 T-rep, 7.5, 14; ADR-0111 decision 6;
 * {@code DESIGN-021} D7, D15; the payout precedent) - a {@code FILED} regulatory report taken to the authority and its
 * answer recorded, in three steps with the provider in none of risk's transactions:
 *
 * <ol>
 *   <li><strong>the claim</strong> - {@link RegulatoryReports#claim} for the filing door's own report after its filing
 *       committed (and on every replay of its key), or {@link RegulatoryReports#claimNextDue} for the inquiry leg of
 *       {@link RiskActionSchedule}: the row {@code FOR UPDATE SKIP LOCKED} on a permit that has come, one attempt counted,
 *       the permit a lease ahead, <strong>committed</strong> - so ten instances claim a report once per lease;</li>
 *   <li><strong>the call</strong> on <strong>no transaction</strong> ({@link RiskTransactions} is Spring's default
 *       propagation: a provider call inside it would hold a connection across the authority's latency) - the first
 *       attempt submits under the filing reference; every later one inquires by it, and submits under the same
 *       reference only when nothing arrived;</li>
 *   <li><strong>the record</strong> - the answer by a conditional edge on {@code FILED} and the claimed attempt, once.</li>
 * </ol>
 *
 * <p><strong>The platform executes; persons decided</strong> - every drive runs under {@link SecurityContext#enterSystem()}
 * ({@link #asThePlatform}, the one enumerated site): two persons drafted and approved the report and a third filed it,
 * each recorded under their own actor at the door ({@code risk.ReportDrafted}, {@code ReportApproved},
 * {@code ReportFiled}); the authority's answer is recorded as the platform ({@code risk.ReportAcknowledged} /
 * {@code ReportRejected}, the history's {@code SYSTEM} rows).
 *
 * <p>A call that throws records nothing: counted {@code finapp.risk.report.filing{outcome=failed}}, one warn line with the
 * exception's class only, the permit left for the next claim - which inquires, never files again. A record that fails to
 * commit is the same: the next claim asks the authority again and records its answer.
 */
@Slf4j
public final class RegulatoryFilingDriver {

    private final RegulatoryReports reports;
    private final RiskTransactions transactions;
    private final RegulatoryReportMetrics metrics;
    private final RiskFlowScope flow;
    private final Duration lease;
    private final Duration inquiryInterval;
    private final int perTick;

    /**
     * @param lease how long a claimed report's permit is held before another claimer may take it - longer than the
     *     provider's timeout, so a live call is not raced
     * @param inquiryInterval how long an unknown standing waits before it is asked again
     * @param perTick the most due reports one schedule tick drives
     */
    public RegulatoryFilingDriver(RegulatoryReports reports, RiskTransactions transactions,
            RegulatoryReportMetrics metrics, RiskFlowScope flow, Duration lease, Duration inquiryInterval, int perTick) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.lease = positive(lease, "lease");
        this.inquiryInterval = positive(inquiryInterval, "inquiryInterval");
        if (perTick < 1) {
            throw new IllegalArgumentException("perTick must be positive: " + perTick);
        }
        this.perTick = perTick;
    }

    /**
     * {@code id} driven when its permit has come - the filing door's drive, after its filing's transaction has
     * committed, under the door's correlation. A failed claim is counted and left to the leg: the filing stands.
     *
     * @return the report as this drive recorded it; empty when nothing was due, another claimer holds it, or the call threw
     */
    public Optional<RegulatoryReport> submitIfDue(RegulatoryReportId id) {
        Objects.requireNonNull(id, "id");
        return asThePlatform(() -> {
            Optional<RegulatoryReport> claimed;
            try {
                claimed = flow.sweep(RiskSpans.REPORT_INQUIRE,
                        () -> transactions.inTransaction(uow -> reports.claim(uow, id, lease)));
            } catch (RuntimeException failure) {
                metrics.claimFailed();
                log.warn("Regulatory filing claim failed: {}", failure.getClass().getSimpleName());
                return Optional.empty();
            }
            return claimed.flatMap(this::drive);
        });
    }

    /**
     * The inquiry leg's tick: up to the bound, the oldest due {@code FILED} report claimed in a transaction of its own,
     * then asked and recorded - each under its own report's correlation. Never throws: a failed claim is counted and ends
     * the leg for the tick; a failed call is counted and the leg goes on.
     *
     * @return how many answers this tick recorded
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int inquireDue() {
        return asThePlatform(() -> {
            int recorded = 0;
            for (int taken = 0; taken < perTick; taken++) {
                Optional<RegulatoryReport> claimed;
                try {
                    claimed = flow.sweep(RiskSpans.REPORT_INQUIRE,
                            () -> transactions.inTransaction(uow -> reports.claimNextDue(uow, lease)));
                } catch (RuntimeException failure) {
                    metrics.claimFailed();
                    log.warn("Regulatory filing claim failed: {}", failure.getClass().getSimpleName());
                    return recorded;
                }
                if (claimed.isEmpty()) {
                    return recorded;
                }
                CorrelationId correlation = CorrelationId.of(claimed.get().correlationId());
                try (CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(correlation))) {
                    if (drive(claimed.get()).isPresent()) {
                        recorded++;
                    }
                }
            }
            return recorded;
        });
    }

    /** The lease a claim moves the permit by. */
    public Duration lease() {
        return lease;
    }

    private Optional<RegulatoryReport> drive(RegulatoryReport claimed) {
        FilingOutcome outcome;
        try {
            outcome = flow.sweep(RiskSpans.REPORT_INQUIRE, () -> reports.submit(claimed));
        } catch (RuntimeException failure) {
            // Nothing recorded: the permit stands and the next claim inquires by the reference. The class only - never a
            // reference, a jurisdiction's answer or the report's content.
            metrics.failed();
            log.warn("Regulatory filing call failed: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
        CorrelationId correlation = CorrelationContext.current().map(Correlation::correlationId)
                .orElseGet(() -> CorrelationId.of(claimed.correlationId()));
        try {
            return flow.sweep(RiskSpans.REPORT_INQUIRE, () -> transactions.inTransaction(uow -> reports.conclude(uow,
                    claimed, outcome, inquiryInterval, correlation)));
        } catch (RuntimeException failure) {
            // The authority answered but the record did not commit: the next claim asks again and records the same answer.
            metrics.failed();
            log.warn("Regulatory filing record failed: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * {@code work} as the platform - the one place the filing's drive claims to be the acting party: persons drafted,
     * approved and filed the report (risk's records name them); the platform takes it to the authority and records the
     * answer.
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static <T> T asThePlatform(Supplier<T> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return work.get();
        }
    }

    private static Duration positive(Duration duration, String what) {
        Objects.requireNonNull(duration, what);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(what + " is positive");
        }
        return duration;
    }
}
