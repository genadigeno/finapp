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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the {@code kyc} module (`P2-TSK-007`, `P2-TSK-008`, `P2-TSK-010`).
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
    com.finapp.kyc.BeneficialOwnerStore<Connection> beneficialOwnerStore() {
        return new com.finapp.kyc.JdbcBeneficialOwnerStore();
    }

    /**
     * The case-kind resolution, implemented over {@code party} because the answer is that
     * module's fact and {@code kyc} cannot see it — the {@code DecisionOutcome → CustomerStatus}
     * mapping's reasoning (ADR-0035), pointing the other way (`P2-TSK-015`): an
     * {@code ORGANISATION} customer's verification opens as a {@code KYB} case.
     */
    @Bean
    com.finapp.kyc.CaseKindResolver<Connection> caseKindResolver(
            PartyStore<Connection> partyStore) {
        return (unitOfWork, customerId) ->
                partyStore
                        .kindOfCustomer(unitOfWork, com.finapp.party.CustomerId.of(customerId))
                        .map(
                                kind ->
                                        kind == com.finapp.party.PartyKind.ORGANISATION
                                                ? com.finapp.kyc.KycCaseKind.KYB
                                                : com.finapp.kyc.KycCaseKind.KYC)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "no party behind customer " + customerId
                                                        + ": the consumer runs after the"
                                                        + " registration that created the"
                                                        + " customer committed, so this is a"
                                                        + " defect - and a defaulted kind would"
                                                        + " silently disarm the ownership gate"));
    }

    /** The owner-declaration orchestration (`P2-TSK-015`); its endpoint is `P2-TSK-016`'s. */
    @Bean
    OwnerDeclaration ownerDeclaration(
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.BeneficialOwnerStore<Connection> beneficialOwnerStore,
            PartyStore<Connection> partyStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new OwnerDeclaration(
                kycCaseStore,
                beneficialOwnerStore,
                partyStore,
                auditWriter,
                idGenerator,
                clock,
                kycTransactions,
                dataSource);
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

    @Bean
    com.finapp.kyc.ReviewTaskStore<Connection> reviewTaskStore() {
        return new com.finapp.kyc.JdbcReviewTaskStore();
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

    /**
     * The three screening questions (`P2-TSK-010`) — one adapter class, three type-and-path
     * bindings, joining the same providers list under the same property as the verification
     * adapters, so a configured endpoint answers all five questions and an unconfigured
     * deployment carries none of them.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    com.finapp.kyc.ScreeningAdapter sanctionsScreeningAdapter(
            @Value("${finapp.kyc.provider.url}") java.net.URI providerUrl,
            @Value("${finapp.kyc.provider.timeout:PT2S}") java.time.Duration timeout) {
        return com.finapp.kyc.ScreeningAdapter.sanctions(providerUrl, timeout);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    com.finapp.kyc.ScreeningAdapter pepScreeningAdapter(
            @Value("${finapp.kyc.provider.url}") java.net.URI providerUrl,
            @Value("${finapp.kyc.provider.timeout:PT2S}") java.time.Duration timeout) {
        return com.finapp.kyc.ScreeningAdapter.pep(providerUrl, timeout);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    com.finapp.kyc.ScreeningAdapter adverseMediaScreeningAdapter(
            @Value("${finapp.kyc.provider.url}") java.net.URI providerUrl,
            @Value("${finapp.kyc.provider.timeout:PT2S}") java.time.Duration timeout) {
        return com.finapp.kyc.ScreeningAdapter.adverseMedia(providerUrl, timeout);
    }

    @Bean
    com.finapp.kyc.KycDecisionStore<Connection> kycDecisionStore() {
        return new com.finapp.kyc.JdbcKycDecisionStore();
    }

    /**
     * The one decision-recording routine, both doors (`P2-TSK-013`). Unconditional, like
     * {@code reviewService}: a reviewer decides a reviewed case wherever it came from, and only
     * the automatic door rides the provider-conditional {@code CaseAssessment}.
     */
    @Bean
    DecisionRecording decisionRecording(
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.CheckStore<Connection> checkStore,
            com.finapp.kyc.KycDecisionStore<Connection> kycDecisionStore,
            PartyStore<Connection> partyStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource,
            ObjectProvider<CaseAssessment> caseAssessment) {
        return new DecisionRecording(
                kycCaseStore,
                checkStore,
                kycDecisionStore,
                partyStore,
                auditWriter,
                idGenerator,
                clock,
                kycTransactions,
                dataSource,
                caseAssessment);
    }

    /** The assessment and its routing — shared by the run and the callback door (P2-TSK-011). */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    CaseAssessment caseAssessment(
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.CheckStore<Connection> checkStore,
            com.finapp.kyc.ReviewTaskStore<Connection> reviewTaskStore,
            com.finapp.kyc.BeneficialOwnerStore<Connection> beneficialOwnerStore,
            DecisionRecording decisionRecording,
            java.util.List<com.finapp.kyc.VerificationProvider> providers,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new CaseAssessment(
                kycCaseStore,
                checkStore,
                reviewTaskStore,
                beneficialOwnerStore,
                decisionRecording,
                providers,
                idGenerator,
                clock,
                kycTransactions,
                dataSource);
    }

    /** The one audit-and-counter trail for check outcomes, whichever door they arrive through. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    CheckOutcomeTrail checkOutcomeTrail(
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new CheckOutcomeTrail(auditWriter, idGenerator, clock, meterRegistry);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    VerificationRunService verificationRunService(
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.CheckStore<Connection> checkStore,
            CaseAssessment caseAssessment,
            CheckOutcomeTrail checkOutcomeTrail,
            java.util.List<com.finapp.kyc.VerificationProvider> providers,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new VerificationRunService(
                kycCaseStore,
                checkStore,
                caseAssessment,
                checkOutcomeTrail,
                providers,
                idGenerator,
                clock,
                kycTransactions,
                dataSource,
                meterRegistry);
    }

    /**
     * The callback signing key, and the one place {@code FINAPP_KYC_CALLBACK_KEY} is read.
     *
     * <p>The {@code DocumentCipher} shape: a bad key stops the application here rather than at
     * the first callback, and the marked local default is confined to loopback — the
     * <strong>fourth</strong> per-credential confinement, the debt row's own trigger
     * ({@code CallbackKey}'s javadoc carries the reckoning).
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    com.finapp.kyc.CallbackSignature callbackSignature(
            @Value("${finapp.kyc.callback.key:" + MfaKey.MARKED_LOCAL_DEFAULT + "}")
                    String configuredKey,
            Environment environment) {
        boolean loopback = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        return new com.finapp.kyc.CallbackSignature(CallbackKey.decode(configuredKey, loopback));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            "finapp.kyc.provider.url")
    ProviderCallbackService providerCallbackService(
            com.finapp.kyc.CheckStore<Connection> checkStore,
            com.finapp.kyc.CallbackSignature callbackSignature,
            com.finapp.platform.inbox.InboxConsumer<Connection> inboxConsumer,
            CheckOutcomeTrail checkOutcomeTrail,
            CaseAssessment caseAssessment,
            tools.jackson.databind.ObjectMapper objectMapper,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new ProviderCallbackService(
                checkStore,
                callbackSignature,
                inboxConsumer,
                checkOutcomeTrail,
                caseAssessment,
                objectMapper,
                idGenerator,
                clock,
                kycTransactions,
                dataSource);
    }

    /**
     * The reviewer surface (`P2-TSK-012`). Unconditional, unlike the provider beans: reviewing
     * needs no provider endpoint — a case with tasks is reviewable wherever it came from.
     */
    @Bean
    ReviewService reviewService(
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.CheckStore<Connection> checkStore,
            com.finapp.kyc.ReviewTaskStore<Connection> reviewTaskStore,
            com.finapp.kyc.BeneficialOwnerStore<Connection> beneficialOwnerStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new ReviewService(
                kycCaseStore,
                checkStore,
                reviewTaskStore,
                beneficialOwnerStore,
                auditWriter,
                idGenerator,
                clock,
                kycTransactions,
                dataSource);
    }

    /** The acting person's KYB surface (`P2-TSK-016`): registration, declaration, the view. */
    @Bean
    KybService kybService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            com.finapp.party.OrganisationRegistration organisationRegistration,
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.BeneficialOwnerStore<Connection> beneficialOwnerStore,
            OwnerDeclaration ownerDeclaration,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new KybService(
                identityStore,
                partyStore,
                organisationRegistration,
                kycCaseStore,
                beneficialOwnerStore,
                ownerDeclaration,
                idGenerator,
                clock,
                kycTransactions,
                dataSource);
    }

    /**
     * The person's own case surface (`P2-TSK-006`): ensure-exists and the shaped view. Wired
     * over the same resolver and gate as the consumer door, so the two doors of the gated
     * capability share every definition — the kind, the basis question, and (through
     * {@code CaseOpeningTrail}) the record and the announcement.
     */
    @Bean
    KycCaseService kycCaseService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.CaseKindResolver<Connection> caseKindResolver,
            com.finapp.consent.ConsentGate<Connection> consentGate,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new KycCaseService(
                identityStore,
                partyStore,
                kycCaseStore,
                caseKindResolver,
                consentGate,
                auditWriter,
                outboxWriter,
                idGenerator,
                clock,
                kycTransactions,
                dataSource);
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

    /**
     * The case-opening door's consent question (`P2-TSK-019`), carried across two boundaries
     * this module may not cross itself: the basis is the <strong>party's</strong> fact, so the
     * adapter resolves customer → party through {@code party} and asks the gate for
     * {@code KYC_PROCESSING} — on the caller's unit of work, so the decision and the open it
     * authorises are one snapshot ({@code INV-CNS-03}).
     *
     * <p>Throws on an unresolvable customer rather than answering {@code false} — the
     * {@code CaseKindResolver} reasoning: the event commits in the customer's own transaction,
     * so absence is a broken invariant, and a quiet {@code false} would file a defect under
     * "person has not consented yet", where nobody would ever look.
     */
    @Bean
    com.finapp.kyc.CaseOpeningConsent<Connection> caseOpeningConsent(
            PartyStore<Connection> partyStore,
            com.finapp.consent.ConsentGate<Connection> consentGate) {
        return (unitOfWork, customerId) -> {
            com.finapp.party.PartyId party =
                    partyStore
                            .partyOfCustomer(
                                    unitOfWork, com.finapp.party.CustomerId.of(customerId))
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "party.CustomerOpened names customer "
                                                            + customerId
                                                            + " but no customer row exists; the"
                                                            + " event commits with the row, so"
                                                            + " this is a broken invariant"));
            return consentGate.permits(
                    unitOfWork,
                    party.value(),
                    com.finapp.consent.ConsentPurpose.KYC_PROCESSING);
        };
    }

    @Bean
    CustomerOpenedOpensCase customerOpenedOpensCase(
            KycCaseStore<Connection> kycCaseStore,
            com.finapp.kyc.CaseKindResolver<Connection> caseKindResolver,
            com.finapp.kyc.CaseOpeningConsent<Connection> caseOpeningConsent,
            IdGenerator idGenerator,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new CustomerOpenedOpensCase(
                kycCaseStore,
                caseKindResolver,
                caseOpeningConsent,
                idGenerator,
                clock,
                auditWriter,
                outboxWriter);
    }
}
