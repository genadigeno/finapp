package com.finapp.app.risk;

import com.finapp.risk.AlertFolding;
import com.finapp.risk.Alerts;
import com.finapp.risk.Cases;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskSpans;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * The case leg of {@code RiskMonitoringSchedule} (`P13-TSK-016`; {@code DESIGN-016} D7) - two bounded steps a tick, in
 * the schedule's system scope, holding no money row:
 *
 * <ol>
 *   <li><strong>The drain.</strong> Every alert born {@code OPEN} before this task wired its folding (or ever left
 *       {@code OPEN} by a raiser) folds once: the queue read with no lock ({@code risk_alert_open_queue}, oldest first,
 *       an alert with a standing suppression proposal left to `-018`'s door), then each alert in a transaction of its
 *       own through {@link Cases#drain} - the case (R4) before the alert (R5), the attach conditional on {@code OPEN}.
 *       Ten instances draining one alert: the first attaches; the others find it attached to the case they hold and
 *       write nothing, or roll back their own opening when it went elsewhere ({@code Alerts.AlertNotOpen}). A failed
 *       fold is counted ({@code claim.failed{kind=monitoring}}) and the leg goes on.</li>
 *   <li><strong>The prohibitions.</strong> {@link CaseProhibitionDriver#applyDue} - an approved prohibiting
 *       disposition whose profile version a door's crash left unwritten.</li>
 * </ol>
 *
 * <p>After the backlog is drained the leg stays as the safety net it is: one indexed probe of an empty partial index a
 * tick.
 */
@Slf4j
public final class CaseLegDriver implements RiskMonitoringSchedule.CaseLeg {

    private final Cases cases;
    private final CaseProhibitionDriver prohibitions;
    private final RiskTransactions transactions;
    private final RiskMonitoringMetrics metrics;
    private final RiskFlowScope flow;
    private final int foldsPerTick;

    /** @param foldsPerTick the most queued alerts one tick folds */
    public CaseLegDriver(Cases cases, CaseProhibitionDriver prohibitions, RiskTransactions transactions,
            RiskMonitoringMetrics metrics, RiskFlowScope flow, int foldsPerTick) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.prohibitions = Objects.requireNonNull(prohibitions, "prohibitions");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.flow = Objects.requireNonNull(flow, "flow");
        if (foldsPerTick < 1) {
            throw new IllegalArgumentException("the folds per tick must be positive: " + foldsPerTick);
        }
        this.foldsPerTick = foldsPerTick;
    }

    @Override
    public int driveDue() {
        int done = 0;
        List<RiskAlert> queued = flow.sweep(RiskSpans.CASE_OPEN,
                () -> transactions.inTransaction(uow -> cases.queued(uow, foldsPerTick)));
        for (RiskAlert alert : queued) {
            try {
                AlertFolding.Folded folded = flow.sweep(RiskSpans.CASE_OPEN,
                        () -> transactions.inTransaction(uow -> cases.drain(uow, alert.id())));
                if (folded.outcome() == AlertFolding.Folding.ATTACHED) {
                    done++;
                }
            } catch (Alerts.AlertNotOpen raced) {
                // Another instance attached it, or an analyst suppressed it, between the read and the fold: this
                // transaction rolled back what it wrote; nothing failed.
                log.debug("Risk case fold of a queued alert met it moved");
            } catch (RuntimeException failure) {
                // That alert's fold rolled back whole; it stays queued for a later tick. The class only - never an
                // alert's subject or kind beyond the code (INV-RSK-08).
                metrics.claimFailed();
                log.warn("Risk case fold of a queued alert failed: {}", failure.getClass().getSimpleName());
            }
        }
        return done + prohibitions.applyDue();
    }
}
