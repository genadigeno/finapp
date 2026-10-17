package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplApplicationStore;
import com.finapp.bnpl.BnplApplications;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplPartyStanding;
import com.finapp.bnpl.BnplProductBounds;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.JdbcBnplApplicationStore;
import com.finapp.credit.CreditDecisions;
import com.finapp.credit.CreditPartyStanding;
import com.finapp.credit.DecisionRequests;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the BNPL application at checkout (`P12-TSK-010`): bnpl's application store, the two ports {@code app}
 * implements over credit - the party's standing ({@link CreditBnplPartyStanding}) and the decision request opened and
 * cancelled from the BNPL origin ({@link CreditBnplDecisions}) - the application service over them and the ports the
 * earlier tasks built (the checkout session, the merchant, the terms pin, credit's bounds), the customer's doors and the
 * {@code finapp.bnpl.application} meter. A configuration of its own beside {@code BnplBeans} and
 * {@code MerchantBnplBeans}, so parallel tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplApplicationBeans {

    @Bean
    BnplApplicationStore bnplApplicationStore() {
        return new JdbcBnplApplicationStore();
    }

    /** The party's standing - credit's one rule, read authoritatively. */
    @Bean
    BnplPartyStanding bnplPartyStanding(CreditPartyStanding<Connection> creditPartyStanding) {
        return new CreditBnplPartyStanding(creditPartyStanding);
    }

    /** Credit decisioning as bnpl reaches it - the port over credit's published API, the BNPL origin always. */
    @Bean
    BnplCreditDecisions bnplCreditDecisions(
            DecisionRequests decisionRequests, CreditDecisions<Connection> creditDecisions) {
        return new CreditBnplDecisions(decisionRequests, creditDecisions);
    }

    @Bean
    BnplApplications bnplApplications(
            BnplApplicationStore bnplApplicationStore,
            BnplTermsAdministration bnplTermsAdministration,
            BnplPartyStanding bnplPartyStanding,
            BnplCheckoutSessions bnplCheckoutSessions,
            BnplMerchants bnplMerchants,
            BnplProductBounds bnplProductBounds,
            BnplCreditDecisions bnplCreditDecisions,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplApplications(bnplApplicationStore, bnplTermsAdministration, bnplPartyStanding,
                bnplCheckoutSessions, bnplMerchants, bnplProductBounds, bnplCreditDecisions, outboxWriter, auditWriter,
                idGenerator, clock);
    }

    /** {@code finapp.bnpl.application{product, outcome}}, every series registered at startup. */
    @Bean
    BnplApplicationMetrics bnplApplicationMetrics(MeterRegistry meterRegistry) {
        return new BnplApplicationMetrics(meterRegistry);
    }

    @Bean
    BnplApplicationDesk bnplApplicationDesk(
            BnplApplications bnplApplications,
            BnplCheckoutSessions bnplCheckoutSessions,
            IdentityStore<Connection> identityStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplApplicationMetrics bnplApplicationMetrics) {
        return new BnplApplicationDesk(bnplApplications, bnplCheckoutSessions, identityStore, mfaEnrolmentStore,
                idempotentExecutor, bnplTransactions, bnplApplicationMetrics);
    }
}
