package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.HoldStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.merchant.PayableInFlight;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActionOutcome;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MerchantRiskActions} over merchant's real {@link MerchantAdministration} on a fake store (`P13-TSK-017`;
 * ADR-0112 decision 2; {@code INV-RSK-02}, {@code INV-IDEM-01}): a merchant suspended <strong>through merchant's own
 * command</strong> - its row locked and moved by merchant, audited {@code merchant.MerchantSuspended} as the platform with
 * the action's code - keyed {@code risk.action:<id>} so a re-drive replays the first answer; {@code ALREADY} for a
 * suspended merchant, {@code REFUSED} for a closed or unknown one, nothing moved.
 */
@SuppressWarnings({"try", "unchecked"}) // Scopes for their close side effect; the stores' proxies are their types.
@DisplayName("merchant's risk-action door: through merchant's own suspension, keyed, audited as the platform"
        + " (P13-TSK-017)")
class MerchantRiskActionsTest {

    private final Map<MerchantId, Merchant> rows = new LinkedHashMap<>();
    private final List<String> locks = new ArrayList<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new LinkedHashMap<>();

    private final MerchantStore<Connection> merchants = new MerchantStore<>() {
        @Override
        public void insert(Connection unitOfWork, Merchant merchant) {
            rows.put(merchant.id(), merchant);
        }

        @Override
        public Optional<Merchant> findById(Connection unitOfWork, MerchantId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Merchant> findByIdForUpdate(Connection unitOfWork, MerchantId id) {
            locks.add(id.value().toString());
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Merchant> findByIdForShare(Connection unitOfWork, MerchantId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean transition(Connection unitOfWork, Merchant before, Merchant transitioned) {
            Merchant row = rows.get(before.id());
            if (row == null || row.status() != before.status()) {
                return false;
            }
            rows.put(before.id(), transitioned);
            return true;
        }
    };

    private final MerchantAdministration administration = new MerchantAdministration(merchants,
            refusing(LedgerAccountStore.class), refusing(BalanceDerivation.class), refusing(HoldStore.class),
            refusing(PayableInFlight.class), (unitOfWork, record) -> audit.add(record), RiskEvaluationWorld.IDS,
            RiskEvaluationWorld.CLOCK);
    private final MerchantRiskActions door = new MerchantRiskActions(administration, merchants,
            new IdempotentExecutor(new Claims(), RiskEvaluationWorld.CLOCK, Duration.ofDays(1), Duration.ofMinutes(1)),
            new IdentityRiskActions.Transactions() {
                @Override
                public <T> T inOne(Function<Connection, T> work) {
                    return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
                }
            });

    @Test
    @DisplayName("SUSPEND_MERCHANT moves an ACTIVE merchant through merchant's administration - its row locked first,"
            + " moved by merchant, audited merchant.MerchantSuspended as the platform with the action's code; the same"
            + " key replays DONE and moves nothing; another key reads ALREADY")
    void aSuspensionGoesThroughMerchantsCommand() {
        Merchant active = merchant();
        assertThat(asThePlatform(() -> door.suspend(active.id().value(), "risk.action:m", "RISK_CASE_MERCHANT_ABUSE")))
                .isEqualTo(ActionOutcome.DONE);
        assertThat(rows.get(active.id()).status()).isEqualTo(MerchantStatus.SUSPENDED);
        assertThat(locks).first().as("merchant's own order: the row first").isEqualTo(active.id().value().toString());
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("merchant.MerchantSuspended");
            assertThat(record.actor()).as("the platform executes").isEqualTo(Actor.SYSTEM);
            assertThat(record.reason()).contains("RISK_CASE_MERCHANT_ABUSE");
        });

        assertThat(asThePlatform(() -> door.suspend(active.id().value(), "risk.action:m", "RISK_CASE_MERCHANT_ABUSE")))
                .as("INV-IDEM-01: the key's answer replayed").isEqualTo(ActionOutcome.DONE);
        assertThat(audit).hasSize(1);
        assertThat(asThePlatform(() -> door.suspend(active.id().value(), "risk.action:n", "RISK_CASE_MERCHANT_ABUSE")))
                .isEqualTo(ActionOutcome.ALREADY);
        assertThat(audit).as("nothing moved again").hasSize(1);
        assertThat(claims.keySet()).extracting(IdempotencyKey::scope).containsOnly(MerchantRiskActions.SCOPE);
    }

    @Test
    @DisplayName("a closed merchant, an unknown one and an id that is no platform identifier are REFUSED - nothing moved")
    void theRefusals() {
        Merchant closed = merchant();
        rows.put(closed.id(), closed.close(RiskEvaluationWorld.CLOCK));
        assertThat(asThePlatform(() -> door.suspend(closed.id().value(), "risk.action:c", "RISK_CASE_MERCHANT_ABUSE")))
                .isEqualTo(ActionOutcome.REFUSED);
        assertThat(asThePlatform(() -> door.suspend(RiskEvaluationWorld.IDS.next(), "risk.action:u",
                "RISK_CASE_MERCHANT_ABUSE"))).isEqualTo(ActionOutcome.REFUSED);
        assertThat(asThePlatform(() -> door.suspend(UUID.randomUUID(), "risk.action:v", "RISK_CASE_MERCHANT_ABUSE")))
                .isEqualTo(ActionOutcome.REFUSED);
        assertThat(audit).isEmpty();
        assertThat(rows.get(closed.id()).status()).isEqualTo(MerchantStatus.CLOSED);
    }

    // ------------------------------------------------------------------

    private Merchant merchant() {
        Merchant merchant = Merchant.onboard(RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK,
                RiskEvaluationWorld.IDS.next(), "Shop GmbH", "Shop", CurrencyCode.of("EUR"));
        rows.put(merchant.id(), merchant);
        return merchant;
    }

    private static <T> T refusing(Class<?> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new AssertionError("a suspension never calls " + type.getSimpleName() + "." + method.getName());
        });
    }

    private static <T> T asThePlatform(Supplier<T> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

    /** The platform's claim store, in memory. */
    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                    StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
                    held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
