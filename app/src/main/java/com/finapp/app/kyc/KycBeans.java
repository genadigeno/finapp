package com.finapp.app.kyc;

import com.finapp.app.mfa.MfaKey;
import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.identity.IdentityStore;
import com.finapp.kyc.CustomerOpenedOpensCase;
import com.finapp.kyc.DocumentAccess;
import com.finapp.kyc.DocumentCipher;
import com.finapp.kyc.DocumentStore;
import com.finapp.kyc.JdbcDocumentStore;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the {@code kyc} module (`P2-TSK-007`, `P2-TSK-008`).
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

    /**
     * Randomness for document-encryption nonces.
     *
     * <p>Declared here rather than shared, on {@code MfaBeans.mfaRandomness}'s recorded
     * reasoning: instances are independent, each seeds from the operating system, and rewiring
     * proven configuration to share one is a refactor with no correctness benefit.
     */
    @Bean
    SecureRandom kycRandomness() {
        return new SecureRandom();
    }

    /**
     * The document cipher, and the one place {@code FINAPP_DOC_KEY} is read.
     *
     * <p>A bad key stops the application here rather than at the first upload — the {@code MfaKey}
     * reasoning: a platform that starts and fails at the moment a customer submits their passport
     * fails for everyone, unwatched, with an error that reads as a cryptography bug. The marked
     * local default is confined to loopback via {@link DatabaseEndpoint}, the third
     * per-credential confinement.
     */
    @Bean
    DocumentCipher documentCipher(
            @Value("${finapp.doc.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}") String configuredKey,
            @Value("${finapp.doc.key-version:1}") int keyVersion,
            Environment environment,
            SecureRandom kycRandomness) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new DocumentCipher(
                DocumentKey.decode(configuredKey, loopback), keyVersion, kycRandomness);
    }

    @Bean
    DocumentStore<Connection> documentStore(DocumentCipher documentCipher) {
        return new JdbcDocumentStore(documentCipher);
    }

    /** The one audited read path ({@code INV-KYC-06}); its HTTP caller arrives with P2-TSK-012. */
    @Bean
    DocumentAccess documentAccess(
            DocumentStore<Connection> documentStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new DocumentAccess(documentStore, auditWriter, idGenerator, clock);
    }

    /**
     * Its own transaction template, {@code REQUIRES_NEW} and default isolation: the upload is a
     * derived-chain read plus a claim-by-insert behind a savepoint — no read-then-write for a
     * higher level to protect ({@code MfaBeans.mfaTransactions}' reasoning).
     */
    @Bean
    TransactionTemplate kycTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    com.finapp.kyc.CheckStore<Connection> checkStore(DocumentCipher documentCipher) {
        // The same cipher as documents, deliberately: provider evidence and document content are
        // one at-rest concern (ADR-0036 groups them), and key_version per row keeps a later
        // split one rotation away.
        return new com.finapp.kyc.JdbcCheckStore(documentCipher);
    }

    /**
     * The verification providers and their runner — present only where a provider endpoint is
     * configured.
     *
     * <p>{@code finapp.kyc.provider.url} has <strong>no default</strong>: ADR-0008 simulates
     * providers, so the only endpoint that exists is whatever a test (or a demo compose file)
     * stands up, and a deployed instance without one simply lacks these beans rather than
     * carrying adapters aimed at nothing. The kafka-relay property-gate precedent, by absence
     * of a bean rather than a flag.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    com.finapp.kyc.IdentityVerificationAdapter identityVerificationAdapter(
            @Value("${finapp.kyc.provider.url}") java.net.URI providerUrl,
            @Value("${finapp.kyc.provider.timeout:PT2S}") java.time.Duration timeout) {
        return new com.finapp.kyc.IdentityVerificationAdapter(providerUrl, timeout);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    com.finapp.kyc.DocumentVerificationAdapter documentVerificationAdapter(
            @Value("${finapp.kyc.provider.url}") java.net.URI providerUrl,
            @Value("${finapp.kyc.provider.timeout:PT2S}") java.time.Duration timeout) {
        return new com.finapp.kyc.DocumentVerificationAdapter(providerUrl, timeout);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    VerificationRunService verificationRunService(
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.CheckStore<Connection> checkStore,
            java.util.List<com.finapp.kyc.VerificationProvider> providers,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new VerificationRunService(
                kycCaseStore,
                checkStore,
                providers,
                auditWriter,
                idGenerator,
                clock,
                kycTransactions,
                dataSource,
                meterRegistry);
    }

    @Bean
    DocumentUploadService documentUploadService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            KycCaseStore<Connection> kycCaseStore,
            DocumentStore<Connection> documentStore,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new DocumentUploadService(
                identityStore,
                partyStore,
                kycCaseStore,
                documentStore,
                idGenerator,
                clock,
                kycTransactions,
                dataSource);
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
