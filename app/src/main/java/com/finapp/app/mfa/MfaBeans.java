package com.finapp.app.mfa;

import com.finapp.app.security.DatabaseEndpoint;
import com.finapp.identity.JdbcMfaEnrolmentStore;
import com.finapp.identity.MfaEnrolmentService;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.SecretCipher;
import com.finapp.identity.TotpVerifier;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring for MFA enrolment (`P1-TSK-017`). */
@Configuration
class MfaBeans {

    @Bean
    MfaEnrolmentStore<Connection> mfaEnrolmentStore() {
        return new JdbcMfaEnrolmentStore();
    }

    /**
     * Randomness for secrets and nonces.
     *
     * <p>Declared here rather than promoted to a shared platform bean: {@code PlatformBeans}
     * constructs its own for {@code IdGenerator}, and rewiring proven code to share one would be a
     * refactor with no correctness benefit ({@code EXECUTION_PROTOCOL.md} rule 4). Two
     * {@code SecureRandom} instances are independent and each seeds from the operating system —
     * there is no shared state to get wrong.
     */
    @Bean
    SecureRandom mfaRandomness() {
        return new SecureRandom();
    }

    @Bean
    TotpVerifier totpVerifier(Clock clock) {
        return new TotpVerifier(clock);
    }

    /**
     * The cipher, and the one place the key is read.
     *
     * <p><strong>A bad key stops the application here rather than at the first challenge.</strong>
     * An unusable key otherwise produces a platform that starts, serves traffic and fails for every
     * customer at the moment they try to log in — with an error that reads as a cryptography bug
     * rather than a configuration one.
     *
     * <p>The marked local default is confined to loopback, which is
     * {@code DatabaseCredentialGuard}'s rule applied to the platform's second credential. ADR-0020's
     * debt row named that as the trigger for generalising it, so this reuses {@link
     * DatabaseEndpoint} rather than inventing a second definition of "is this database on this
     * machine?".
     */
    @Bean
    SecretCipher mfaSecretCipher(
            @Value("${finapp.mfa.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}") String configuredKey,
            @Value("${finapp.mfa.key-version:1}") int keyVersion,
            org.springframework.core.env.Environment environment,
            SecureRandom mfaRandomness) {
        // Read through DatabaseEndpoint rather than a @Value of my own: P0-TSK-031's review found
        // the credential guard reading `spring.datasource.url` while Hikari's `jdbc-url` actually
        // wins - a proven bypass. One definition of "what does the pool connect with", not two.
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new SecretCipher(MfaKey.decode(configuredKey, loopback), keyVersion, mfaRandomness);
    }

    /**
     * Its own transaction template.
     *
     * <p>{@code REQUIRES_NEW} and {@code ISOLATION_DEFAULT}: every write here is a conditional
     * {@code UPDATE … WHERE} whose row count is the outcome, so there is no read-then-write for a
     * higher isolation level to protect. Three templates now exist, so an unqualified
     * {@code TransactionTemplate} parameter would be ambiguous — the context refusing to start is
     * the framework being right, as it was in {@code P1-TSK-016}.
     */
    @Bean
    TransactionTemplate mfaTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    MfaEnrolmentService mfaEnrolmentService(
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            SecretCipher mfaSecretCipher,
            TotpVerifier totpVerifier,
            SecureRandom mfaRandomness,
            IdGenerator idGenerator,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new MfaEnrolmentService(
                mfaEnrolmentStore,
                mfaSecretCipher,
                totpVerifier,
                mfaRandomness,
                idGenerator,
                clock,
                auditWriter,
                outboxWriter);
    }
}
