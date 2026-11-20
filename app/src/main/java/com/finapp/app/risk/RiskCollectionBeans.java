package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.risk.AssessmentSearch;
import com.finapp.risk.JdbcRiskCollections;
import com.finapp.risk.RiskCollections;
import com.finapp.risk.ScreeningListStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the risk desks' §7 collections (`P17-TSK-020`): the read model over risk's tables, the assessment search - its
 * readers placed as {@code RiskAssessments}' are, through credit's {@code IdentityActingParty} - and the desk the assessment,
 * policy and screening list controllers serve them through.
 */
@Configuration(proxyBeanMethods = false)
public class RiskCollectionBeans {

    @Bean
    RiskCollections riskCollections() {
        return new JdbcRiskCollections();
    }

    @Bean
    AssessmentSearch assessmentSearch(
            RiskCollections riskCollections,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new AssessmentSearch(riskCollections, new com.finapp.app.credit.IdentityActingParty(identityStore),
                auditWriter, idGenerator, clock);
    }

    @Bean
    RiskCollectionDesk riskCollectionDesk(
            AssessmentSearch assessmentSearch,
            RiskCollections riskCollections,
            ScreeningListStore screeningListStore,
            RiskTransactions riskTransactions,
            RiskFlowScope riskFlowScope) {
        return new RiskCollectionDesk(assessmentSearch, riskCollections, screeningListStore, riskTransactions, riskFlowScope);
    }

    /** Risk's queues of proposals awaiting a decision (`P17-TSK-040`), the investigative ones recorded. */
    @Bean
    RiskPendingDecisionDesk riskPendingDecisionDesk(
            RiskCollections riskCollections,
            RiskTransactions riskTransactions,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            RiskFlowScope riskFlowScope) {
        return new RiskPendingDecisionDesk(riskCollections, riskTransactions, auditWriter, idGenerator, clock, riskFlowScope);
    }
}
