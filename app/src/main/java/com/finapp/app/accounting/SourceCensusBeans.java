package com.finapp.app.accounting;

import com.finapp.accounting.JdbcSourceCensusStore;
import com.finapp.accounting.JournalCensusWalk;
import com.finapp.accounting.SourceCensus;
import com.finapp.accounting.SourceCensusStore;
import com.finapp.accounting.SourceJoinCatalogue;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The source-join census, wired (`P15-TSK-035`). Off in test contexts ({@code finapp.accounting.source-census.enabled}); the suites drive
 * it directly. Its connections come from the instance's pool, one at a time: it adds no pool and moves no budget. Tunables with their defaults:
 * {@code .interval} PT1M; {@code .batch} 200; {@code .statement-timeout} PT30S; the gauges' {@code .standing} PT24H, {@code .recent} P30D
 * (the ticket's longer window on findings the page has let go of) and {@code .stale-after} PT15M.
 */
@Configuration(proxyBeanMethods = false)
class SourceCensusBeans {

    @Bean
    SourceCensusStore sourceCensusStore() {
        return new JdbcSourceCensusStore();
    }

    @Bean
    JournalCensusWalk journalCensusWalk() {
        return new LedgerJournalCensusWalk();
    }

    @Bean
    SourceJoinCatalogue sourceJoinCatalogue() {
        return new SourceJoinCatalogue(SourceCensusComposition.sources());
    }

    @Bean
    SourceCensus sourceCensus(SourceCensusStore store, JournalCensusWalk walk, SourceJoinCatalogue catalogue, AccountingTransactions transactions,
            @Value("${finapp.accounting.source-census.batch:200}") int batch,
            @Value("${finapp.accounting.source-census.statement-timeout:PT30S}") Duration statementTimeout) {
        return new SourceCensus(new SourceCensus.Units() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return transactions.inTransaction(work);
            }
        }, store, walk, catalogue, batch, statementTimeout);
    }

    /** A schedule: every instance, one walker at a time by the head row. */
    @Bean
    @ConditionalOnProperty(name = "finapp.accounting.source-census.enabled", havingValue = "true", matchIfMissing = true)
    SourceCensusSchedule sourceCensusSchedule(SourceCensus sourceCensus,
            @Value("${finapp.accounting.source-census.interval:PT1M}") Duration interval) {
        return new SourceCensusSchedule(sourceCensus, interval);
    }

    /** The gauges: the same read-only snapshot and statement timeout as the other accounting gauges. */
    @Bean
    SourceCensusMetrics sourceCensusMetrics(SourceCensusStore store, JournalCensusWalk walk,
            @Value("${finapp.accounting.source-census.standing:PT24H}") Duration standing,
            @Value("${finapp.accounting.source-census.recent:P30D}") Duration recent,
            @Value("${finapp.accounting.source-census.stale-after:PT15M}") Duration staleAfter,
            @Qualifier(AccountingGaugeBeans.SNAPSHOTS) GlSnapshots snapshots, Clock clock, MeterRegistry registry) {
        return new SourceCensusMetrics(store, walk, standing, recent, staleAfter, snapshots, clock, registry);
    }
}
