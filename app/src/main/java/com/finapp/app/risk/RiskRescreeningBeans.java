package com.finapp.app.risk;

import com.finapp.kyc.CheckStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.risk.ActiveParties;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.JdbcRescreeningPermitStore;
import com.finapp.risk.JdbcScreeningListStore;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.RescreeningPermitStore;
import com.finapp.risk.RescreeningRun;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.ScreeningListStore;
import com.finapp.risk.ScreeningListVersions;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the rescreening on screening-list updates (`P13-TSK-022`; {@code PHASE_13_PLAN.md} section 12.10): the two
 * stores, T-list ({@link ScreeningListVersions}) and its doors, the ports {@code app} implements - {@link ActiveParties}
 * over party's store and {@link PartyRescreening} through kyc's re-screening door ({@code VerificationRunService::rescreen},
 * the owner's decision O1) - the leg's domain ({@link RescreeningRun}) and its driver, which {@code RiskActionBeans}
 * hands {@code RiskActionSchedule} as its third leg, and the meters - the leg's gauge eager either way.
 */
@Configuration(proxyBeanMethods = false)
public class RiskRescreeningBeans {

    @Bean
    ScreeningListStore screeningListStore() {
        return new JdbcScreeningListStore();
    }

    @Bean
    RescreeningPermitStore rescreeningPermitStore() {
        return new JdbcRescreeningPermitStore();
    }

    @Bean
    ScreeningListVersions screeningListVersions(ScreeningListStore screeningListStore,
            AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        return new ScreeningListVersions(screeningListStore, auditWriter, idGenerator, clock);
    }

    @Bean
    RiskScreeningListDesk riskScreeningListDesk(ScreeningListVersions screeningListVersions,
            IdempotentExecutor idempotentExecutor, RiskTransactions riskTransactions, RiskFlowScope riskFlowScope) {
        return new RiskScreeningListDesk(screeningListVersions, idempotentExecutor, riskTransactions, riskFlowScope);
    }

    /** The census's parties: every {@code ACTIVE} or {@code PENDING} customer relationship's, a keyset page of party's store. */
    @Bean
    ActiveParties activeParties(PartyStore<Connection> partyStore) {
        return new PartyActiveParties(partyStore);
    }

    /**
     * risk asks, kyc screens: kyc's re-screening door ({@code VerificationRunService::rescreen}) where a verification
     * provider is configured; where none is, {@link KycPartyRescreening.Door#UNAVAILABLE} - every ask unavailable, the
     * permits backed off and failed at their bound, counted.
     */
    @Bean
    PartyRescreening partyRescreening(PartyStore<Connection> partyStore, KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore,
            org.springframework.beans.factory.ObjectProvider<com.finapp.app.kyc.VerificationRunService> verificationRuns,
            RiskTransactions riskTransactions) {
        com.finapp.app.kyc.VerificationRunService runs = verificationRuns.getIfAvailable();
        KycPartyRescreening.Door door = runs == null ? KycPartyRescreening.Door.UNAVAILABLE : runs::rescreen;
        return new KycPartyRescreening(partyStore, kycCaseStore, checkStore, door, riskTransactions);
    }

    /** {@code finapp.risk.rescreening{outcome}}, {@code .rescreening.backlog}, {@code claim.failed{kind=rescreening}}. */
    @Bean
    RescreeningMetrics rescreeningMetrics(RescreeningPermitStore rescreeningPermitStore, DataSource dataSource,
            Clock clock, MeterRegistry meterRegistry) {
        return new RescreeningMetrics(rescreeningPermitStore, dataSource::getConnection, clock, meterRegistry);
    }

    /**
     * The leg's domain: the bounds ({@code finapp.risk.rescreening.max-attempts} 12, {@code .first-backoff} 5 min doubling
     * to {@code .max-backoff} 6 h) before a permit with no current screening fails.
     */
    @Bean
    RescreeningRun rescreeningRun(
            ScreeningListStore screeningListStore,
            RescreeningPermitStore rescreeningPermitStore,
            ActiveParties activeParties,
            PartyRescreening partyRescreening,
            RiskSignals riskSignals,
            AlertRaiser alertRaiser,
            IdGenerator idGenerator,
            RescreeningMetrics rescreeningMetrics,
            @Value("${finapp.risk.rescreening.max-attempts:12}") int maxAttempts,
            @Value("${finapp.risk.rescreening.first-backoff:PT5M}") Duration firstBackoff,
            @Value("${finapp.risk.rescreening.max-backoff:PT6H}") Duration maxBackoff) {
        return new RescreeningRun(screeningListStore, rescreeningPermitStore, activeParties, partyRescreening,
                riskSignals, alertRaiser, idGenerator, rescreeningMetrics,
                new RescreeningRun.Bounds(maxAttempts, firstBackoff, maxBackoff));
    }

    /**
     * The rescreening leg's driver - {@code RiskActionSchedule}'s third leg: {@code finapp.risk.rescreening.lease}
     * (2 min - longer than kyc's bounded provider call and its outcome's commit), {@code .enqueue-batch} (500 parties a
     * transaction), {@code .enqueue-batches-per-tick} (4), {@code .permits-per-tick} (20 - each one kyc provider call at
     * most, so a tick's provider time is bounded by twenty bounded calls).
     */
    @Bean
    RescreeningDriver rescreeningDriver(
            RescreeningRun rescreeningRun,
            RiskTransactions riskTransactions,
            RescreeningMetrics rescreeningMetrics,
            RiskFlowScope riskFlowScope,
            @Value("${finapp.risk.rescreening.lease:PT2M}") Duration lease,
            @Value("${finapp.risk.rescreening.enqueue-batch:500}") int enqueueBatch,
            @Value("${finapp.risk.rescreening.enqueue-batches-per-tick:4}") int enqueueBatchesPerTick,
            @Value("${finapp.risk.rescreening.permits-per-tick:20}") int permitsPerTick) {
        return new RescreeningDriver(rescreeningRun, riskTransactions, rescreeningMetrics, riskFlowScope, lease,
                enqueueBatch, enqueueBatchesPerTick, permitsPerTick);
    }

    /**
     * Whether this instance runs the rescreening leg - a leg of the action schedule, so its property decides it; eager
     * either way (`P1-TSK-029`'s rule).
     */
    @Bean
    Gauge riskRescreeningSweeperEnabled(
            @Value("${finapp.risk.action.sweeper.enabled:true}") boolean enabled, MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.risk.sweeper.enabled", () -> enabled ? 1 : 0)
                .tag("kind", RescreeningMetrics.KIND)
                .description("Whether this instance runs the risk rescreening leg (RiskActionSchedule's)")
                .register(meterRegistry);
    }
}
