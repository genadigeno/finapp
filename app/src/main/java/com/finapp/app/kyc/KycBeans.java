package com.finapp.app.kyc;

import com.finapp.kyc.CustomerOpenedOpensCase;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the {@code kyc} module (`P2-TSK-007`).
 *
 * <p>The handler bean is what makes the consumer real: {@code InboxConsumers} discovers every
 * {@code InboxEventHandler} in the context, derives the {@code finapp.kyc} group from its
 * consumer name, and a deployed instance starts reacting to registrations with no configuration
 * — which is the moment the broker path carries its first business flow.
 */
@Configuration(proxyBeanMethods = false)
public class KycBeans {

    @Bean
    KycCaseStore<Connection> kycCaseStore() {
        return new JdbcKycCaseStore();
    }

    @Bean
    CustomerOpenedOpensCase customerOpenedOpensCase(
            KycCaseStore<Connection> kycCaseStore,
            IdGenerator idGenerator,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new CustomerOpenedOpensCase(
                kycCaseStore, idGenerator, clock, auditWriter, outboxWriter);
    }
}
