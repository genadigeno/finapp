package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplClosure;
import com.finapp.bnpl.BnplClosureReason;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplRefund;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The refund doors' boundary, hermetic (`P12-TSK-021`): the receipts a retried key replays - the merchant's purchase-level
 * figures and the operator's split, never the key or the reason ({@code INV-IDEM-01}, {@code INV-AUD-02}); the merchant's
 * answer closed against the customer's facts (the `-014` closed view); the body's amount exact at the currency's minor
 * units; the refused conservation recognised; and the refund meter's closed, eager series. The doors over a real database
 * are {@code BnplRefundApiTest}'s.
 */
@DisplayName("the BNPL refund doors' boundary (P12-TSK-021)")
class BnplRefundDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("the merchant's receipt replays its purchase-level figures and never the key, the reason, an instalment or"
            + " the customer's excess; the operator's replays the split and the closure")
    void theReceiptsReplay() {
        BnplRefunds.Refunded refunded = refunded(BnplRefunds.Kind.FULL);
        BnplRefundDesk.BnplMerchantRefundView merchant = BnplRefundDesk.merchantView(refunded);
        byte[] stored = BnplRefundDesk.stored(merchant).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("the-merchants-secret-key")
                .doesNotContain("goods came back");
        assertThat(BnplRefundDesk.replayedMerchant(stored)).isEqualTo(merchant);
        assertThat(merchant.amount()).isEqualTo("600.00");
        assertThat(merchant.payableDebit()).isEqualTo("576.00");
        assertThat(merchant.purchaseStatus()).isEqualTo("FULLY_REFUNDED");

        BnplRefundDesk.BnplOperatorRefundView operator = BnplRefundDesk.operatorView(refunded);
        assertThat(BnplRefundDesk.replayedOperator(BnplRefundDesk.stored(operator).body())).isEqualTo(operator);
        assertThat(operator.excess()).isEqualTo("150.00");
        assertThat(operator.excessTo()).isEqualTo("WALLET");
        assertThat(operator.closure()).isEqualTo("REFUNDED");
        BnplRefundDesk.BnplOperatorRefundView open = BnplRefundDesk.operatorView(new BnplRefunds.Refunded(
                refunded.refund(), Optional.empty(), false, BnplRefunds.Kind.PARTIAL));
        assertThat(BnplRefundDesk.replayedOperator(BnplRefundDesk.stored(open).body())).isEqualTo(open);
        assertThat(open.purchaseStatus()).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    @DisplayName("the merchant's answer is closed: no member names an instalment, the excess, the customer, the wallet or"
            + " the agreement")
    void theMerchantsAnswerIsClosed() {
        assertThat(Arrays.stream(BnplRefundDesk.BnplMerchantRefundView.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("refundId", "purchaseId", "amount", "currency", "feeReturned", "payableDebit",
                        "purchaseStatus", "valueDate");
    }

    @Test
    @DisplayName("the body's amount is exact at the currency's minor units; an exponent, an unknown currency or a sub-cent"
            + " amount is 422 before anything")
    void theAmountIsExact() {
        assertThat(BnplRefundDesk.amount(new BnplRefundController.BnplRefundBody("40.00", "EUR", "r")))
                .isEqualTo(Money.ofMinorUnits(40_00, EUR));
        for (BnplRefundController.BnplRefundBody invalid : List.of(
                new BnplRefundController.BnplRefundBody("1e3", "EUR", "r"),
                new BnplRefundController.BnplRefundBody("1.001", "EUR", "r"),
                new BnplRefundController.BnplRefundBody("1.00", "XXQ", "r"))) {
            assertThatThrownBy(() -> BnplRefundDesk.amount(invalid)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("a scope refusal's audit target is the path's purchase identifier, canonical, or 'malformed' - never the"
            + " caller's free text")
    void aRefusalsTargetIsNeverFreeText() {
        UUID purchase = IDS.next();
        assertThat(BnplRefundDesk.parsedOrMalformed(purchase.toString().toUpperCase(java.util.Locale.ROOT)))
                .isEqualTo(purchase.toString());
        assertThat(BnplRefundDesk.parsedOrMalformed("'; DROP TABLE x; --")).isEqualTo("malformed");
        assertThat(BnplRefundDesk.parsedOrMalformed(null)).isEqualTo("malformed");
    }

    @Test
    @DisplayName("a conservation refused at commit is recognised by its invariant - INV-BNPL-06 or -07 - and only it")
    void aRefusedConservationIsRecognised() {
        assertThat(BnplRefundDesk.refusedConservation(new IllegalStateException("wrapped", new IllegalStateException(
                "a BNPL refund's adjustments sum to its cancellations (P12-TSK-021, INV-BNPL-06)")))).isTrue();
        assertThat(BnplRefundDesk.refusedConservation(new IllegalStateException("a deadlock"))).isFalse();
    }

    @Test
    @DisplayName("the refund meter registers every kind and outcome eagerly, and the shared anomaly counter")
    void theMeterIsEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplRefundMetrics metrics = new BnplRefundMetrics(registry);
        assertThat(registry.find(BnplRefundMetrics.REFUND).counters())
                .hasSize(BnplRefunds.Kind.values().length * BnplRefundMetrics.Outcome.values().length);
        metrics.record(BnplRefunds.Kind.FULL, BnplRefundMetrics.Outcome.CLOSED);
        assertThat(registry.get(BnplRefundMetrics.REFUND).tags("kind", "full", "outcome", "closed").counter().count())
                .isEqualTo(1.0);
        metrics.anomaly();
        assertThat(registry.get(BnplRepaymentMetrics.ANOMALY).counter().count()).isEqualTo(1.0);
        new BnplRepaymentMetrics(registry).anomaly();
        assertThat(registry.get(BnplRepaymentMetrics.ANOMALY).counter().count()).as("one counter, two meters")
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("P14-TSK-021: a merchant refunding a written-off customer's purchase learns nothing of it: the neutral 409, no word of a write-off,"
            + " a default, a debt or a retry, nothing moved, the refusal counted; the operator's door keeps the full code")
    @SuppressWarnings("try") // The Scopes are used for their close side effect (the idiom).
    void aMerchantLearnsNothingOfAWriteOff() {
        UUID merchantId = IDS.next();
        UUID customer = UUID.randomUUID();
        BnplObligationId obligationId = BnplObligationId.next(IDS);
        BnplPurchaseId purchaseId = BnplPurchaseId.next(IDS);
        BnplAgreementId agreementId = BnplAgreementId.next(IDS);
        CorrelationId flow = CorrelationId.of("desk");
        com.finapp.bnpl.BnplPurchase purchase = new com.finapp.bnpl.BnplPurchase(purchaseId, com.finapp.bnpl.BnplOfferId.next(IDS),
                UUID.randomUUID(), customer, merchantId, com.finapp.bnpl.BnplProduct.BNPL_PAY_IN_4, com.finapp.bnpl.BnplPlan.PAY_IN_4, EUR,
                com.finapp.bnpl.BnplPurchaseStatus.FINANCED, LocalDate.of(2026, 7, 1), Instant.parse("2026-07-01T10:00:00Z"),
                Optional.of(Instant.parse("2026-07-01T10:00:00Z")), Optional.empty(), flow);
        com.finapp.bnpl.BnplRefundStore.LockedPurchase locked = new com.finapp.bnpl.BnplRefundStore.LockedPurchase(purchase, eur(600_00),
                eur(24_00), eur(576_00), obligationId);
        com.finapp.bnpl.BnplObligation writtenOff = new com.finapp.bnpl.BnplObligation(obligationId, purchaseId, agreementId, customer,
                UUID.randomUUID(), com.finapp.bnpl.BnplProduct.BNPL_PAY_IN_4, com.finapp.bnpl.BnplPlan.PAY_IN_4, EUR,
                com.finapp.bnpl.BnplObligationStatus.WRITTEN_OFF, LocalDate.of(2026, 7, 1), Instant.parse("2026-07-01T10:00:00Z"),
                Optional.empty(), flow);
        com.finapp.bnpl.BnplServicingStore.Locked lockedObligation = new com.finapp.bnpl.BnplServicingStore.Locked(writtenOff, agreementId, 1,
                com.finapp.bnpl.BnplTermsVersionId.of(IDS.next()), "BNPL_SCHEDULE_ENGINE_V1", Optional.empty(), "ALLOCATION_ENGINE_V1", "UTC");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplRefundDesk desk = new BnplRefundDesk(built(BnplRefunds.class,
                        answering(com.finapp.bnpl.BnplRefundStore.class, java.util.Map.of("lockAtMerchant", Optional.of(locked),
                                "lock", Optional.of(locked), "disputeOpen", false)),
                        answering(com.finapp.bnpl.BnplServicingStore.class, java.util.Map.of("lock", Optional.of(lockedObligation))),
                        (com.finapp.bnpl.BnplActingParty) (uow, actor) -> Optional.of(UUID.randomUUID())),
                built(com.finapp.bnpl.BnplAccruals.class),
                new com.finapp.platform.idempotency.IdempotentExecutor(claims(), Clock.systemUTC(), java.time.Duration.ofDays(1),
                        java.time.Duration.ofMinutes(1)),
                built(BnplTransactions.class, new org.springframework.transaction.support.TransactionTemplate(noTransactions())),
                new BnplRefundMetrics(registry));
        BnplRefundController.BnplRefundBody body = new BnplRefundController.BnplRefundBody("40.00", "EUR", "goods returned");
        com.finapp.merchant.AuthenticatedMerchant merchant = new com.finapp.merchant.AuthenticatedMerchant(
                com.finapp.merchant.MerchantId.of(merchantId), com.finapp.merchant.MerchantApiKeyId.of(IDS.next()),
                java.util.Set.of(com.finapp.merchant.MerchantApiKeyScope.BNPL_REFUND), Optional.of("key-issuer"));
        com.finapp.platform.security.Actor actor = new com.finapp.platform.security.Actor(merchantId.toString(),
                com.finapp.platform.security.ActorType.MERCHANT);
        try (com.finapp.platform.security.SecurityContext.Scope scope = com.finapp.platform.security.SecurityContext.enter(actor);
                com.finapp.platform.correlation.CorrelationContext.Scope correlation = com.finapp.platform.correlation.CorrelationContext.enter(
                        com.finapp.sharedkernel.correlation.Correlation.startingWith(flow))) {
            assertThatThrownBy(() -> desk.refundAsMerchant(merchant, purchaseId.value().toString(), "key-1", body))
                    .isInstanceOfSatisfying(ApiException.class, refused -> {
                        assertThat(refused.errorCode().code()).isEqualTo("bnpl.RefundNotPostable");
                        assertThat(refused.errorCode().status()).isEqualTo(409);
                        String said = (refused.errorCode().code() + " " + refused.errorCode().title() + " " + refused.getMessage() + " "
                                + refused.clientDetail().orElseThrow()).toLowerCase(java.util.Locale.ROOT);
                        for (String word : List.of("written off", "written-off", "writtenoff", "write-off", "default", "owed", "debt", "customer",
                                "agreement", "delinquen", "retry", "same key", " now")) {
                            assertThat(said).as(word).doesNotContain(word);
                        }
                    });
            try (com.finapp.platform.security.SecurityContext.Scope operator = com.finapp.platform.security.SecurityContext.enter(
                    new com.finapp.platform.security.Actor(UUID.randomUUID().toString(), com.finapp.platform.security.ActorType.EMPLOYEE))) {
                assertThatThrownBy(() -> desk.refundAsOperator(purchaseId.value().toString(), "key-2", body))
                        .as("the operator's door keeps the full code, through ApiErrorHandler")
                        .isInstanceOf(com.finapp.bnpl.BnplWrittenOffRefused.class);
            }
        }
        assertThat(registry.get(BnplRefundMetrics.REFUND).tags("kind", "partial", "outcome", "written_off").counter().count())
                .as("counted at each door").isEqualTo(2.0);
    }

    @Test
    @DisplayName("P14-TSK-022 (c): a customer disputing a written-off agreement, through the real desk and domain: 409 bnpl.ObligationWrittenOff in words that say it stands owed and where to take it, promising a review and no outcome; nothing written")
    @SuppressWarnings("try") // The Scopes are used for their close side effect (the idiom).
    void aCustomerDisputingAWrittenOffAgreementIsToldWhereToGo() {
        UUID customer = UUID.randomUUID();
        BnplObligationId obligationId = BnplObligationId.next(IDS);
        BnplPurchaseId purchaseId = BnplPurchaseId.next(IDS);
        BnplAgreementId agreementId = BnplAgreementId.next(IDS);
        CorrelationId flow = CorrelationId.of("desk");
        com.finapp.bnpl.BnplPurchase purchase = new com.finapp.bnpl.BnplPurchase(purchaseId, com.finapp.bnpl.BnplOfferId.next(IDS),
                UUID.randomUUID(), customer, IDS.next(), com.finapp.bnpl.BnplProduct.BNPL_PAY_IN_4, com.finapp.bnpl.BnplPlan.PAY_IN_4, EUR,
                com.finapp.bnpl.BnplPurchaseStatus.FINANCED, LocalDate.of(2026, 7, 1), Instant.parse("2026-07-01T10:00:00Z"),
                Optional.of(Instant.parse("2026-07-01T10:00:00Z")), Optional.empty(), flow);
        com.finapp.bnpl.BnplRefundStore.LockedPurchase locked = new com.finapp.bnpl.BnplRefundStore.LockedPurchase(purchase, eur(600_00),
                eur(24_00), eur(576_00), obligationId);
        com.finapp.bnpl.BnplObligation writtenOff = new com.finapp.bnpl.BnplObligation(obligationId, purchaseId, agreementId, customer,
                UUID.randomUUID(), com.finapp.bnpl.BnplProduct.BNPL_PAY_IN_4, com.finapp.bnpl.BnplPlan.PAY_IN_4, EUR,
                com.finapp.bnpl.BnplObligationStatus.WRITTEN_OFF, LocalDate.of(2026, 7, 1), Instant.parse("2026-07-01T10:00:00Z"),
                Optional.empty(), flow);
        com.finapp.bnpl.BnplServicingStore.Locked lockedObligation = new com.finapp.bnpl.BnplServicingStore.Locked(writtenOff, agreementId, 1,
                com.finapp.bnpl.BnplTermsVersionId.of(IDS.next()), "BNPL_SCHEDULE_ENGINE_V1", Optional.empty(), "ALLOCATION_ENGINE_V1", "UTC");
        com.finapp.bnpl.BnplPurchaseStore.Owned owned = (com.finapp.bnpl.BnplPurchaseStore.Owned) newOwned(writtenOff, purchase);
        Object store = answering(com.finapp.bnpl.BnplRefundStore.class, java.util.Map.of("lock", Optional.of(locked)));
        Object servicing = answering(com.finapp.bnpl.BnplServicingStore.class, java.util.Map.of("lock", Optional.of(lockedObligation)));
        Object purchases = answering(com.finapp.bnpl.BnplPurchaseStore.class, java.util.Map.of("agreementOwnedBy", Optional.of(owned)));
        Object disputes = answering(com.finapp.bnpl.BnplDisputeStore.class, java.util.Map.of());
        com.finapp.bnpl.BnplRefunds refunds = built(BnplRefunds.class, store, servicing, purchases, (com.finapp.bnpl.BnplActingParty) (uow, actor) -> Optional.of(UUID.randomUUID()));
        com.finapp.identity.IdentityId identity = com.finapp.identity.IdentityId.next(IDS);
        com.finapp.identity.Identity person = com.finapp.identity.Identity.rehydrate(identity, customer, new com.finapp.identity.LoginIdentifier("writtenoffcustomer"), com.finapp.identity.IdentityStatus.ACTIVE, Instant.EPOCH, Instant.EPOCH);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplDisputeDesk desk = built(BnplDisputeDesk.class, built(com.finapp.bnpl.BnplDisputes.class, store, servicing, purchases, disputes, refunds),
                built(com.finapp.bnpl.BnplAccruals.class), answering(com.finapp.identity.IdentityStore.class, java.util.Map.of("findById", Optional.of(person))),
                new com.finapp.platform.idempotency.IdempotentExecutor(claims(), Clock.systemUTC(), java.time.Duration.ofDays(1), java.time.Duration.ofMinutes(1)),
                built(BnplTransactions.class, new org.springframework.transaction.support.TransactionTemplate(noTransactions())),
                new BnplDisputeMetrics(registry), new BnplRefundMetrics(registry), refunds);
        Instant now = Instant.parse("2026-08-01T10:00:00Z");
        com.finapp.identity.Session session = com.finapp.identity.Session.rehydrate(com.finapp.identity.SessionId.next(IDS), identity, com.finapp.sharedkernel.security.Sensitive.of("hash"), com.finapp.identity.AssuranceLevel.MULTI_FACTOR,
                com.finapp.identity.SessionStatus.ACTIVE, now, now.plusSeconds(600), now.plusSeconds(3600), "test", null);
        com.finapp.platform.security.Actor actor = new com.finapp.platform.security.Actor(customer.toString(), com.finapp.platform.security.ActorType.CUSTOMER);
        try (com.finapp.platform.security.SecurityContext.Scope scope = com.finapp.platform.security.SecurityContext.enter(actor);
                com.finapp.platform.correlation.CorrelationContext.Scope correlation = com.finapp.platform.correlation.CorrelationContext.enter(
                        com.finapp.sharedkernel.correlation.Correlation.startingWith(flow))) {
            assertThatThrownBy(() -> desk.open(session, agreementId.value().toString(), "key-1", new BnplDisputeController.BnplDisputeOpeningBody("40.00", "EUR", "NOT_RECEIVED", "it never came")))
                    .isInstanceOfSatisfying(ApiException.class, refused -> {
                        assertThat(refused.errorCode().code()).isEqualTo("bnpl.ObligationWrittenOff");
                        assertThat(refused.errorCode().status()).isEqualTo(409);
                        assertThat(refused.clientDetail().orElseThrow()).isEqualTo(com.finapp.bnpl.BnplObligations.WRITTEN_OFF_DISPUTE_REFUSED);
                    });
        }
    }

    /** {@code BnplPurchaseStore.Owned}, the agreement the domain does not read here left as a record's zero. */
    private static Object newOwned(com.finapp.bnpl.BnplObligation obligation, com.finapp.bnpl.BnplPurchase purchase) {
        return new com.finapp.bnpl.BnplPurchaseStore.Owned(obligation, purchase, new org.springframework.objenesis.SpringObjenesis().newInstance(com.finapp.bnpl.BnplAgreement.class), eur(600_00));
    }

    /** A real collaborator whose every part is {@code known} or answers nothing: only what runs before the guard is ever asked. */
    @SuppressWarnings("unchecked")
    private static <T> T built(Class<T> type, Object... known) {
        java.lang.reflect.Constructor<?> widest = Arrays.stream(type.getDeclaredConstructors())
                .max(java.util.Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount))
                .orElseThrow(() -> new IllegalStateException("nothing to build " + type + " with"));
        Object[] arguments = Arrays.stream(widest.getParameterTypes()).map(part -> part(part, known)).toArray();
        try {
            widest.setAccessible(true);
            return (T) widest.newInstance(arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(type.getName(), failure);
        }
    }

    private static Object part(Class<?> type, Object[] known) {
        for (Object given : known) {
            if (type.isInstance(given)) {
                return given;
            }
        }
        if (type == IdGenerator.class) {
            return IDS;
        }
        if (type == Clock.class) {
            return Clock.systemUTC();
        }
        return type.isInterface() ? answering(type, java.util.Map.of("getConnection", noConnection())) : built(type, known);
    }

    private static Object answering(Class<?> type, java.util.Map<String, Object> answers) {
        return java.lang.reflect.Proxy.newProxyInstance(BnplRefundDeskTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (answers.containsKey(method.getName())) {
                        return answers.get(method.getName());
                    }
                    if (method.getReturnType() == Optional.class) {
                        return Optional.empty();
                    }
                    if (method.getReturnType() == List.class) {
                        return List.of();
                    }
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
                });
    }

    private static com.finapp.platform.idempotency.IdempotencyRecordStore<java.sql.Connection> claims() {
        return new com.finapp.platform.idempotency.IdempotencyRecordStore<>() {
            @Override
            public ClaimOutcome claim(java.sql.Connection unitOfWork, com.finapp.platform.idempotency.IdempotencyKey key,
                    com.finapp.platform.idempotency.RequestFingerprint fingerprint, CorrelationId correlationId, Instant now, Instant expiresAt,
                    java.time.Duration lease) {
                return ClaimOutcome.CLAIMED;
            }

            @Override
            public Optional<com.finapp.platform.idempotency.IdempotencyRecord> find(java.sql.Connection unitOfWork,
                    com.finapp.platform.idempotency.IdempotencyKey key) {
                return Optional.empty();
            }

            @Override
            public boolean complete(java.sql.Connection unitOfWork, com.finapp.platform.idempotency.IdempotencyKey key,
                    com.finapp.platform.idempotency.IdempotencyState terminalState, com.finapp.platform.idempotency.StoredResponse response,
                    Instant completedAt) {
                return true;
            }

            @Override
            public boolean reclaimIfLeaseExpired(java.sql.Connection unitOfWork, com.finapp.platform.idempotency.IdempotencyKey key,
                    CorrelationId correlationId, Instant now, Instant expiresAt, java.time.Duration lease) {
                return false;
            }
        };
    }

    private static org.springframework.transaction.PlatformTransactionManager noTransactions() {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {}

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {}
        };
    }

    private static java.sql.Connection noConnection() {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                BnplRefundDeskTest.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new UnsupportedOperationException("the door's fakes never touch the connection: " + method.getName());
                });
    }

    private static BnplRefunds.Refunded refunded(BnplRefunds.Kind kind) {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        UUID refund = IDS.next();
        BnplRefund row = new BnplRefund(refund, BnplPurchaseId.next(IDS), obligation, IDS.next(), BnplRefund.Door.MERCHANT,
                "MERCHANT:x", "the-merchants-secret-key", eur(600_00), eur(0), eur(24_00), eur(576_00), eur(450_00),
                eur(0), eur(150_00), BnplRefund.ExcessTo.WALLET, eur(0), eur(0), "REFUND_ALLOCATION_ENGINE_V1",
                BnplAgreementId.next(IDS), 2, 2, "the goods came back", JournalEntryId.of(IDS.next()),
                LocalDate.of(2026, 10, 20), Optional.of(Instant.parse("2026-10-20T10:00:00Z")), CorrelationId.of("desk"),
                List.of(new BnplRefund.Adjustment(IDS.next(), 4, IDS.next(),
                        com.finapp.bnpl.RefundAllocation.Cancellation.Kind.UNBILLED, eur(450_00), eur(0), true)));
        BnplClosure closure = new BnplClosure(IDS.next(), obligation, BnplClosureReason.REFUNDED, eur(0), Optional.empty(),
                Optional.empty(), Optional.of(refund), CorrelationId.of("desk"));
        return new BnplRefunds.Refunded(row, Optional.of(closure), false, kind);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
