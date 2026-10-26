package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.RiskAuditAction;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.ScreeningListVersionStatus;
import com.finapp.risk.ScreeningListVersions;
import com.finapp.risk.testing.InMemoryScreeningListStore;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary half of the screening list doors, hermetic (`P13-TSK-022`): a {@code CUSTOMER}-typed operator - as the
 * platform's session interceptor establishes every one - records a list version and a second activates it; each act keyed
 * per principal ({@code risk.list:<type>:<id>}) and a lost answer replayed by its key without a second act; the domain's
 * refusals on the error contract - a second activation {@code 409 risk.PolicyStale}, the recorder's own {@code 403
 * risk.SelfApprovalRefused} recorded {@code FAILED} in a transaction of its own, a missing reason {@code 422
 * risk.ReasonRequired}, an unknown or malformed id {@code 404 risk.NotFound}. The doors over HTTP - the permission, the
 * step-up, the status codes - are {@code ScreeningListApiTest}'s (written, owed - C5).
 */
@DisplayName("the screening list desk: persons, keyed acts, the refusals on the error contract (P13-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RiskScreeningListDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) java.lang.reflect.Proxy.newProxyInstance(
            RiskScreeningListDeskTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor OFFICER = new Actor("officer-1", ActorType.CUSTOMER);
    private static final Actor SECOND = new Actor("officer-2", ActorType.CUSTOMER);

    private final InMemoryScreeningListStore store = new InMemoryScreeningListStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final FakeTransactions transactions = new FakeTransactions();
    private final RiskScreeningListDesk desk = new RiskScreeningListDesk(
            new ScreeningListVersions(store, (AuditWriter<Connection>) (uow, record) -> audited.add(record), IDS, CLOCK),
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions,
            RiskFlowScope.NONE);

    @Test
    @DisplayName("a CUSTOMER-typed operator records, a second CUSTOMER-typed operator activates: 201 RECORDED then 202"
            + " ACTIVE, keyed per principal; nothing is enqueued by the door")
    void aCustomerTypedOperatorRecordsAndASecondActivates() {
        RiskScreeningListDesk.RiskScreeningListReceipt recorded = as(OFFICER, () -> desk.record("key-1",
                request("SANCTIONS", "2026-10-26", "the provider published a new edition")));
        assertThat(recorded.status()).isEqualTo("RECORDED");
        assertThat(recorded.list()).isEqualTo("SANCTIONS");
        assertThat(recorded.version()).isEqualTo("2026-10-26");
        assertThat(recorded.ordinal()).isEqualTo(1);
        assertThat(claims.keySet()).extracting(IdempotencyKey::scope).containsExactly("risk.list:CUSTOMER:officer-1");

        RiskScreeningListDesk.RiskScreeningListReceipt activated = as(SECOND,
                () -> desk.activate("key-2", recorded.id(), "the edition is in force"));
        assertThat(activated.status()).isEqualTo("ACTIVE");
        assertThat(activated.retiredId()).isNull();
        assertThat(store.all().getFirst().status()).isEqualTo(ScreeningListVersionStatus.ACTIVE);
        assertThat(store.all().getFirst().enqueueNextAt()).as("the sweep's enqueue is due; the door enqueues nothing")
                .isPresent();
        assertThat(audited).extracting(AuditRecord::operation).containsExactly(RiskAuditAction.LIST_VERSION_RECORDED,
                RiskAuditAction.LIST_VERSION_ACTIVATED);
        assertThat(audited).extracting(record -> record.actor().type()).containsOnly(ActorType.CUSTOMER);
    }

    @Test
    @DisplayName("a lost answer replays by its key: the same key, the same receipt, no second act or record")
    void aLostAnswerReplays() {
        RiskScreeningListDesk.RiskScreeningListReceipt first = as(OFFICER, () -> desk.record("key-1",
                request("PEP", "v9", "a new edition")));
        RiskScreeningListDesk.RiskScreeningListReceipt replayed = as(OFFICER, () -> desk.record("key-1",
                request("PEP", "v9", "a new edition")));
        assertThat(replayed).isEqualTo(first);
        assertThat(store.all()).hasSize(1);
        assertThat(audited).hasSize(1);
    }

    @Test
    @DisplayName("the refusals in the contract's words: a second activation 409 risk.PolicyStale; the recorder's own 403"
            + " risk.SelfApprovalRefused recorded FAILED after the act rolled back; no reason 422; an unknown id 404")
    void theRefusals() {
        String id = as(OFFICER, () -> desk.record("k1", request("SANCTIONS", "v1", "a new edition"))).id();

        assertThatThrownBy(() -> as(OFFICER, () -> desk.activate("k2", id, "my own")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(RiskErrorCode.SELF_APPROVAL_REFUSED));
        AuditRecord refusal = audited.getLast();
        assertThat(refusal.operation()).isEqualTo(RiskAuditAction.SELF_APPROVAL_REFUSED);
        assertThat(refusal.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(refusal.targetId()).isEqualTo(id);
        assertThat(transactions.rolledBack).as("the act rolled back before its refusal was recorded").isEqualTo(1);
        assertThat(store.all().getFirst().status()).isEqualTo(ScreeningListVersionStatus.RECORDED);

        as(SECOND, () -> desk.activate("k3", id, "in force"));
        assertThatThrownBy(() -> as(new Actor("officer-3", ActorType.CUSTOMER), () -> desk.activate("k4", id, "again")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(RiskErrorCode.POLICY_STALE));
        assertThatThrownBy(() -> as(OFFICER, () -> desk.record("k5", request("PEP", "v1", " "))))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(RiskErrorCode.REASON_REQUIRED));
        for (String unknown : List.of(UUID.randomUUID().toString(), "not-an-id")) {
            assertThatThrownBy(() -> as(SECOND, () -> desk.activate("k6-" + unknown, unknown, "in force")))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                            .isEqualTo(RiskErrorCode.NOT_FOUND));
        }
    }

    // ------------------------------------------------------------------ the world

    private static RiskScreeningListController.RiskScreeningListVersionRequest request(String list, String version,
            String reason) {
        return new RiskScreeningListController.RiskScreeningListVersionRequest(list, version, reason);
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope scope = SecurityContext.enter(actor)) {
            return work.get();
        }
    }

    /** risk's unit of work over the in-memory store: a thrown act puts the rows and the claims back. */
    private final class FakeTransactions implements RiskTransactions {
        int rolledBack;

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            var rows = store.snapshot();
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = audited.size();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                store.restore(rows);
                claims.clear();
                claims.putAll(held);
                audited.subList(records, audited.size()).clear();
                rolledBack++;
                throw failed;
            } finally {
                store.release();
            }
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            return work.apply(UOW);
        }
    }

    /** The idempotency records in memory (bnpl's world's shape). */
    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(),
                    correlationId, now, expiresAt));
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
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
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
