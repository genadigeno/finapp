package com.finapp.app.crossborder;

import com.finapp.crossborder.Beneficiaries;
import com.finapp.crossborder.BeneficiaryStore;
import com.finapp.crossborder.BeneficiaryVocabulary;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorDirectory;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.CounterpartyScreening;
import com.finapp.crossborder.JdbcBeneficiaryStore;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreeningStatus;
import com.finapp.kyc.CounterpartyScreeningVocabulary;
import com.finapp.kyc.CounterpartyScreenings;
import com.finapp.kyc.CounterpartySubject;
import com.finapp.kyc.ScreeningOutcomeListener;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the cross-border beneficiary (`P9-TSK-017`, ADR-0080 section 3, ADR-0081): the store, the
 * {@link CounterpartyScreening} port over kyc's {@code CounterpartyScreenings}, kyc's
 * {@link ScreeningOutcomeListener} implemented over the beneficiary (T-e), and the door's desk. The two
 * modules never see each other: every word crosses here.
 */
@Configuration(proxyBeanMethods = false)
public class CrossBorderBeneficiaryBeans {

    @Bean
    BeneficiaryStore crossBorderBeneficiaryStore() {
        return new JdbcBeneficiaryStore();
    }

    /** crossborder's screening port over kyc - the request in crossborder's unit of work, then the ask. */
    @Bean
    CounterpartyScreening counterpartyScreening(CounterpartyScreenings counterpartyScreenings) {
        return new CounterpartyScreening() {
            @Override
            public UUID requestWithin(Connection unitOfWork, Request request) {
                return counterpartyScreenings.requestWithin(unitOfWork, new CounterpartyScreenings.Request(
                                request.reference(),
                                new CounterpartySubject(request.name(), request.country(),
                                        CounterpartyScreeningVocabulary.EntityType.valueOf(request.entityType().name())),
                                CounterpartyScreeningVocabulary.PayeeVerdict.valueOf(request.payeeCheck().name()),
                                request.requestedBy()))
                        .id()
                        .value();
            }

            @Override
            public void screenNow(UUID screening, CorrelationId correlation) {
                counterpartyScreenings.retry(CounterpartyScreeningId.of(screening), correlation);
            }

            @Override
            public UUID rescreenWithin(Connection unitOfWork, UUID previous, String reference) {
                return counterpartyScreenings.rescreenWithin(unitOfWork, CounterpartyScreeningId.of(previous), reference).id().value();
            }

            @Override
            public java.util.Optional<Clearance> clearance(Connection unitOfWork, UUID screening) {
                return counterpartyScreenings.clearance(unitOfWork, CounterpartyScreeningId.of(screening))
                        .map(read -> new Clearance(read.clears(), read.decidedAt(),
                                read.status() == CounterpartyScreeningStatus.REQUESTED
                                        || read.status() == CounterpartyScreeningStatus.UNAVAILABLE));
            }
        };
    }

    /**
     * kyc's listener: the beneficiary moves inside kyc's deciding transaction (T-e). Resolved lazily -
     * the beneficiary service asks kyc's screening, and kyc's screening tells this listener.
     */
    @Bean
    ScreeningOutcomeListener screeningOutcomeListener(ObjectProvider<Beneficiaries> crossBorderBeneficiaries) {
        return (unitOfWork, outcome) -> crossBorderBeneficiaries.getObject().screeningDecided(
                unitOfWork, outcome.requestReference(), outcome.screening().value(), outcomeOf(outcome.status()),
                outcome.decidedAt(), outcome.correlation());
    }

    static BeneficiaryVocabulary.ScreeningOutcome outcomeOf(CounterpartyScreeningStatus status) {
        return switch (status) {
            case CLEAR, RELEASED -> BeneficiaryVocabulary.ScreeningOutcome.CLEARED;
            case IN_REVIEW -> BeneficiaryVocabulary.ScreeningOutcome.IN_REVIEW;
            case BLOCKED -> BeneficiaryVocabulary.ScreeningOutcome.BLOCKED;
            case UNAVAILABLE, REQUESTED -> BeneficiaryVocabulary.ScreeningOutcome.UNAVAILABLE;
        };
    }

    @Bean
    Beneficiaries crossBorderBeneficiaries(
            BeneficiaryStore crossBorderBeneficiaryStore,
            CorridorPolicyStore corridorPolicyStore,
            CorridorAvailabilityStore corridorAvailabilityStore,
            CorridorDirectory corridorDirectory,
            CounterpartyScreening counterpartyScreening,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator) {
        return new Beneficiaries(crossBorderBeneficiaryStore, corridorPolicyStore, corridorAvailabilityStore, corridorDirectory,
                counterpartyScreening, auditWriter, outboxWriter, idGenerator);
    }

    @Bean
    CrossBorderBeneficiaryDesk crossBorderBeneficiaryDesk(
            Beneficiaries crossBorderBeneficiaries,
            IdentityStore<Connection> identityStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner crossborderTransactionRunner,
            Clock clock) {
        return new CrossBorderBeneficiaryDesk(crossBorderBeneficiaries, identityStore, mfaEnrolmentStore, idempotentExecutor,
                crossborderTransactionRunner, clock);
    }
}
