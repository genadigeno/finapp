package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.identity.SessionStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.AssessmentVelocityEvidence;
import com.finapp.risk.CaseOpener;
import com.finapp.risk.CustomerRiskProfiles;
import com.finapp.risk.EvaluationBudget;
import com.finapp.risk.JdbcEvaluationBudget;
import com.finapp.risk.JdbcRiskAssessmentStore;
import com.finapp.risk.JdbcVelocityWindowStore;
import com.finapp.risk.RiskAssessmentStore;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskInputs;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.SessionAssurance;
import com.finapp.risk.SignalReadings;
import com.finapp.risk.VelocityEvidence;
import com.finapp.risk.VelocityWindowStore;
import com.finapp.risk.VelocityWindows;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for T-eval, T-advise and the analyst's assessment doors (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 7.1,
 * 7.6, 9, 15): the velocity windows over their store, the sealed assessments' store, the savepoint and statement-timeout
 * budget, {@link PlatformRiskInputs} over the session's, kyc's, party's and merchant's reads, the meters, and the
 * evaluation itself - channel-neutral, consumed since `P13-TSK-008` by transfers' two seams and the transfer door's
 * advice ({@code RiskTransferBeans}); the withdrawal, checkout and cross-border seams stay on their permit-all defaults
 * until `-009`…`-011` wire their adapters over {@link RiskEvaluation}.
 *
 * <p><strong>The session's assurance</strong> is identity's, read live ({@link IdentitySessionAssurance}, `-008`), so a
 * {@code CHALLENGE} the acting session satisfies permits. <strong>The case opener</strong> (`P13-TSK-016`):
 * {@code RiskCaseBeans}' {@link CaseOpener} - a {@code REVIEW} or {@code HOLD} opens or joins the party's case of the kind
 * the sealed hits call for, in the money transaction, the assessment its evidence. <strong>The alerts</strong> (`P13-TSK-013`): the sealed
 * hits' account-takeover alerts raised through {@code RiskAlertBeans}' {@link AlertRaiser} - {@code Alerts.raiseAll},
 * behind T-eval's own savepoint. The velocity proof's evidence of record is
 * {@link AssessmentVelocityEvidence} - the counted assessments, every dimension provable (`-006`'s finding closed);
 * `-023` runs the proof on its schedule.
 */
@Configuration(proxyBeanMethods = false)
public class RiskEvaluationBeans {

    @Bean
    VelocityWindowStore velocityWindowStore() {
        return new JdbcVelocityWindowStore();
    }

    @Bean
    VelocityWindows velocityWindows(VelocityWindowStore velocityWindowStore) {
        return new VelocityWindows(velocityWindowStore);
    }

    @Bean
    RiskAssessmentStore riskAssessmentStore() {
        return new JdbcRiskAssessmentStore();
    }

    /** The savepoint and the 250 ms statement timeout T-eval's reads run behind. */
    @Bean
    EvaluationBudget evaluationBudget() {
        return new JdbcEvaluationBudget();
    }

    /**
     * The acting session's assurance (`P13-TSK-008`): the session the request presented, re-read live through the
     * caller's unit of work - the port's first implementation.
     */
    @Bean
    SessionAssurance sessionAssurance(SessionStore<Connection> sessionStore) {
        return new IdentitySessionAssurance(sessionStore);
    }

    /** The readings only other modules answer - the session's, kyc's, party's and merchant's. */
    @Bean
    RiskInputs riskInputs(SessionAssurance sessionAssurance, ScreeningClearances screeningClearances,
            PartyStore<Connection> partyStore, KycCaseStore<Connection> kycCaseStore) {
        return new PlatformRiskInputs(Optional.of(sessionAssurance), screeningClearances, partyStore, kycCaseStore,
                new JdbcMerchantStore());
    }

    /** {@code finapp.risk.evaluation*}, {@code .rule.hit}, {@code .challenge} - registered eagerly. */
    @Bean
    RiskEvaluationMetrics riskEvaluationMetrics(MeterRegistry meterRegistry) {
        return new RiskEvaluationMetrics(meterRegistry);
    }

    @Bean
    RiskEvaluation riskEvaluation(
            RiskPolicies riskPolicies,
            VelocityWindows velocityWindows,
            CustomerRiskProfiles customerRiskProfiles,
            SignalReadings signalReadings,
            RiskInputs riskInputs,
            RiskAssessmentStore riskAssessmentStore,
            EvaluationBudget evaluationBudget,
            CaseOpener caseOpener,
            AlertRaiser alertRaiser,
            OutboxWriter<Connection> outboxWriter,
            RiskEvaluationMetrics riskEvaluationMetrics,
            Spans domainSpans,
            IdGenerator idGenerator,
            Clock clock) {
        return new RiskEvaluation(riskPolicies, velocityWindows, customerRiskProfiles, signalReadings, riskInputs,
                riskAssessmentStore, evaluationBudget, caseOpener, alertRaiser, outboxWriter, riskEvaluationMetrics,
                domainSpans, idGenerator, clock);
    }

    /** The velocity proof's evidence of record: the counted assessments (`-023` runs the proof). */
    @Bean
    VelocityEvidence velocityEvidence(RiskAssessmentStore riskAssessmentStore, VelocityWindowStore velocityWindowStore) {
        return new AssessmentVelocityEvidence(riskAssessmentStore, velocityWindowStore);
    }

    /** The analyst's reads; whose party an analyst is, through credit's {@code IdentityActingParty}. */
    @Bean
    RiskAssessments riskAssessments(
            RiskAssessmentStore riskAssessmentStore,
            RiskPolicies riskPolicies,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new RiskAssessments(riskAssessmentStore, riskPolicies,
                new com.finapp.app.credit.IdentityActingParty(identityStore), auditWriter, idGenerator, clock);
    }

    @Bean
    RiskAssessmentDesk riskAssessmentDesk(
            RiskAssessments riskAssessments, RiskTransactions riskTransactions, RiskFlowScope riskFlowScope) {
        return new RiskAssessmentDesk(riskAssessments, riskTransactions, riskFlowScope);
    }
}
