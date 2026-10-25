package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.CustomerRiskProfileStore;
import com.finapp.risk.CustomerRiskProfiles;
import com.finapp.risk.JdbcCustomerRiskProfileStore;
import com.finapp.risk.MonitoredMovementStore;
import com.finapp.risk.MonitoredMovements;
import com.finapp.risk.MonitoredParties;
import com.finapp.risk.MonitoringObserver;
import com.finapp.risk.ProfileHints;
import com.finapp.risk.ProfileInputs;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPartyIdentities;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.SignalReadings;
import com.finapp.risk.VelocityWindows;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the customer risk profile (`P13-TSK-007`; {@code PHASE_13_PLAN.md} sections 7.1, 12.5): the store, the
 * party join over identity, T-sig's permit pull (given to {@code RiskBeans}' {@code RiskSignals}), the recompute
 * (the leg {@code RiskMonitoringBeans}' schedule drives; {@code finapp.risk.profile.cadence} the re-assessment
 * interval), the reads and the analyst's door, and credit's seam at version 2 - {@link RiskCreditRiskSignal}, the
 * {@code CreditRiskSignal} bean {@code CreditBeans}' freezer is given in place of Phase 10's
 * {@code NotAssessedUntilPhase13}. Whose party an analyst is, is read through credit's {@code IdentityActingParty} -
 * one read for credit, lending, bnpl and risk.
 */
@Configuration(proxyBeanMethods = false)
public class RiskProfileBeans {

    @Bean
    CustomerRiskProfileStore customerRiskProfileStore() {
        return new JdbcCustomerRiskProfileStore();
    }

    /**
     * The party behind a signal's subject - an identity's party; the other kinds placed by later tasks - and the reverse,
     * the identities a party holds ({@link RiskPartyIdentities}, `P13-TSK-013`): one bean, both joins - and the case's
     * accountable party, a merchant's organisation over merchant's store (`P13-TSK-016`, {@code DESIGN-016} D6).
     */
    @Bean
    IdentitySubjectParties riskSubjectParties(IdentityStore<Connection> identityStore) {
        return new IdentitySubjectParties(identityStore, new com.finapp.merchant.JdbcMerchantStore());
    }

    /** T-sig's permit pull: a signal born about a placeable party pulls its profile permit to now. */
    @Bean
    ProfileHints profileHints(RiskSubjectParties riskSubjectParties, CustomerRiskProfileStore customerRiskProfileStore) {
        return ProfileHints.over(riskSubjectParties, customerRiskProfileStore);
    }

    /**
     * The profile's inputs - since `P13-TSK-020` with the journal's readings over the party's movements
     * ({@link MonitoredMovements}: the monitored transactions by the party's accounts and `-006`'s committed windows).
     */
    @Bean
    ProfileInputs profileInputs(ScreeningClearances screeningClearances, SignalReadings signalReadings,
            RiskPartyIdentities riskPartyIdentities, VelocityWindows velocityWindows,
            MonitoredMovementStore monitoredMovementStore, MonitoredParties monitoredParties) {
        return new ProfileInputs(screeningClearances, signalReadings, riskPartyIdentities, velocityWindows,
                new MonitoredMovements(monitoredMovementStore, monitoredParties, velocityWindows));
    }

    /**
     * T-mon's profile leg: the pin, the head (R8), the engine over the MONITORING version, a version per change - and
     * since `P13-TSK-020` the rule leg's per-party step ({@code ProfileRecompute.monitor}), its rule hits metered.
     */
    @Bean
    ProfileRecompute profileRecompute(
            RiskPolicies riskPolicies,
            CustomerRiskProfileStore customerRiskProfileStore,
            ProfileInputs profileInputs,
            AlertRaiser alertRaiser,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            MonitoringObserver monitoringObserver,
            @Value("${finapp.risk.profile.cadence:P1D}") Duration cadence) {
        return new ProfileRecompute(riskPolicies, customerRiskProfileStore, profileInputs, alertRaiser, outboxWriter,
                idGenerator, clock, cadence, monitoringObserver);
    }

    @Bean
    CustomerRiskProfiles customerRiskProfiles(
            CustomerRiskProfileStore customerRiskProfileStore,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new CustomerRiskProfiles(customerRiskProfileStore,
                new com.finapp.app.credit.IdentityActingParty(identityStore), auditWriter, idGenerator, clock);
    }

    /** Credit's risk seam, answered by risk at seam version 2 (`INV-RSK-10`, `INV-CRD-01`). */
    @Bean
    com.finapp.credit.CreditRiskSignal<Connection> creditRiskSignal(CustomerRiskProfiles customerRiskProfiles) {
        return new RiskCreditRiskSignal(customerRiskProfiles);
    }

    @Bean
    RiskProfileDesk riskProfileDesk(
            CustomerRiskProfiles customerRiskProfiles, RiskTransactions riskTransactions, RiskFlowScope riskFlowScope) {
        return new RiskProfileDesk(customerRiskProfiles, riskTransactions, riskFlowScope);
    }
}
