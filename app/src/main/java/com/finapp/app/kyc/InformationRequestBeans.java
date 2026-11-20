package com.finapp.app.kyc;

import com.finapp.identity.IdentityStore;
import com.finapp.kyc.InformationRequestStore;
import com.finapp.kyc.InformationRequests;
import com.finapp.kyc.JdbcInformationRequestStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.TransactionRunner;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A KYC case awaiting its customer (`P17-TSK-126`, ADR-0155): kyc's request store and its three acts, the doors' desk, and the
 * leaderless expiry schedule - every instance, no lease, off in test contexts.
 */
@Configuration(proxyBeanMethods = false)
public class InformationRequestBeans {

    @Bean
    InformationRequestStore<Connection> informationRequestStore() {
        return new JdbcInformationRequestStore();
    }

    @Bean
    InformationRequests informationRequests(
            KycCaseStore<Connection> kycCaseStore,
            InformationRequestStore<Connection> informationRequestStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new InformationRequests(kycCaseStore, informationRequestStore, auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    InformationRequestDesk informationRequestDesk(
            InformationRequests informationRequests,
            KycCaseStore<Connection> kycCaseStore,
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new InformationRequestDesk(
                informationRequests, kycCaseStore, identityStore, partyStore, clock, kycTransactions, dataSource);
    }

    /** The expiry's schedule - every instance, no lease, off in test contexts. */
    @Bean
    @ConditionalOnProperty(
            name = "finapp.kyc.information-request.expiry.sweeper.enabled",
            havingValue = "true",
            matchIfMissing = true)
    InformationRequestExpirySchedule informationRequestExpirySchedule(
            InformationRequests informationRequests,
            InformationRequestDesk informationRequestDesk,
            TransactionRunner kycTransactionRunner,
            @Value("${finapp.kyc.information-request.expiry.poll:PT1M}") Duration pollInterval,
            @Value("${finapp.kyc.information-request.expiry.batch:200}") int pageSize) {
        return new InformationRequestExpirySchedule(
                informationRequests, informationRequestDesk, kycTransactionRunner, pollInterval, pageSize);
    }

    /** Whether this instance runs the expiry sweep - eager either way (`P1-TSK-029`'s rule). */
    @Bean
    Gauge kycInformationRequestExpirySweeperEnabled(
            @Value("${finapp.kyc.information-request.expiry.sweeper.enabled:true}") boolean enabled,
            MeterRegistry meterRegistry) {
        return Gauge.builder("finapp.kyc.information.request.expiry.sweeper.enabled", () -> enabled ? 1 : 0)
                .description("Whether this instance runs the KYC information request expiry sweep")
                .register(meterRegistry);
    }
}
