package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.ClosedMonthRuns;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.PriorPeriodAdjustmentStore;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.app.telemetry.AccountingObjectives;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the accounting gauges read through (`P14-TSK-020`; `P14-TSK-017`'s review, M-9), and the gauges this task adds: their own {@link GlSnapshots}, whose statement timeout - {@code finapp.accounting.gauges.statement-timeout}, 2 seconds - is a scrape's bound and not a GL read's 10.
 * Its own configuration, so the module's parallel tasks each add theirs without editing one file. {@code gl.unmapped} keeps the GL's bean: its read counts journal lines.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingGaugeBeans {

    /** The bean's name, asked for by name: not a default candidate, so every other reader of a {@link GlSnapshots} still finds the GL's one. */
    static final String SNAPSHOTS = "accountingGaugeSnapshots";

    @Bean(name = SNAPSHOTS, defaultCandidate = false)
    GlSnapshots accountingGaugeSnapshots(DataSource dataSource,
            @Value("${finapp.accounting.gauges.statement-timeout:" + AccountingObjectives.GAUGE_STATEMENT_TIMEOUT + "}") Duration statementTimeout) {
        return new GlSnapshots(dataSource::getConnection, statementTimeout);
    }

    /** {@code finapp.accounting.close.check.stalled}: the same bound as the run's birth. */
    @Bean
    CloseCheckStallMetrics closeCheckStallMetrics(CloseRunStore closeRunStore, CloseRunStall closeRunStall, @Qualifier(SNAPSHOTS) GlSnapshots snapshots, Clock clock, MeterRegistry meterRegistry) {
        return new CloseCheckStallMetrics(closeRunStore, closeRunStall, snapshots, clock, meterRegistry);
    }

    /** {@code finapp.accounting.break.aged{type}}. */
    @Bean
    AccountingBreakAgeMetrics accountingBreakAgeMetrics(AccountingBreakStore accountingBreakStore,
            @Value("${finapp.accounting.break.aged-after:" + AccountingObjectives.BREAK_AGED_AFTER + "}") Duration agedAfter,
            @Qualifier(SNAPSHOTS) GlSnapshots snapshots, Clock clock, MeterRegistry meterRegistry) {
        return new AccountingBreakAgeMetrics(accountingBreakStore, agedAfter, snapshots, clock, meterRegistry);
    }

    /** {@code finapp.accounting.adjustment.standing}. */
    @Bean
    PriorPeriodAdjustmentMetrics priorPeriodAdjustmentMetrics(PriorPeriodAdjustmentStore priorPeriodAdjustmentStore,
            @Value("${finapp.accounting.adjustment.standing-after:" + AccountingObjectives.ADJUSTMENT_STANDING_AFTER + "}") Duration standingAfter,
            @Qualifier(SNAPSHOTS) GlSnapshots snapshots, Clock clock, MeterRegistry meterRegistry) {
        return new PriorPeriodAdjustmentMetrics(priorPeriodAdjustmentStore, standingAfter, snapshots, clock, meterRegistry);
    }

    /** {@code finapp.accounting.close.month.unrun}. */
    @Bean
    ClosedMonthRunMetrics closedMonthRunMetrics(AccountingPeriodStore accountingPeriodStore, ReconciliationRunCoverage reconciliationRunCoverage,
            @Qualifier(SNAPSHOTS) GlSnapshots snapshots, Clock clock, MeterRegistry meterRegistry) {
        return new ClosedMonthRunMetrics(new ClosedMonthRuns(accountingPeriodStore, reconciliationRunCoverage), snapshots, clock, meterRegistry);
    }
}
