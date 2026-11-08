package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.deadletter.DeadLetterErrorCode;
import com.finapp.platform.deadletter.DeadLetterOperations;
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
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.InMemoryDeadLetterStore;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The boundary of the dead-letter doors (`P15-TSK-028`): the routes' declarations, the key, the refusal's own transaction. The
 * decisions are {@code DeadLetterOperationsTest}'s; 401, 403 and the step-up's refusal over HTTP are the interceptor's.
 */
@DisplayName("the dead-letter desk keys, records and refuses at the boundary (P15-TSK-028)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class DeadLetterDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor OPERATOR = new Actor("operator-1", ActorType.EMPLOYEE);
    private static final Actor SECOND = new Actor("operator-2", ActorType.EMPLOYEE);

    private final InMemoryDeadLetterStore store = new InMemoryDeadLetterStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();

    /** Rolls the audit trail and the claims back when the work throws, as the database would. */
    private final DeadLetterTransactions transactions = new DeadLetterTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = audited.size();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                claims.clear();
                claims.putAll(held);
                audited.subList(records, audited.size()).clear();
                throw failed;
            }
        }
    };

    private final DeadLetterDesk desk = new DeadLetterDesk(
            new DeadLetterOperations(store, (uow, record) -> audited.add(record), IDS, CLOCK, Duration.ofDays(14), type -> false),
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)),
            transactions,
            Spans.NONE);

    @Test
    @DisplayName("every door is EVENT_OPERATE, and every act is keyed and MULTI_FACTOR")
    void theDoorsAreDeclared() {
        List<Method> doors = Arrays.stream(DeadLetterController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(GetMapping.class))
                .toList();
        assertThat(doors).hasSize(7);
        for (Method door : doors) {
            assertThat(door.getAnnotation(RequiresPermission.class).value()).as(door.getName()).isEqualTo(PermissionName.EVENT_OPERATE);
            if (door.isAnnotationPresent(PostMapping.class)) {
                assertThat(door.isAnnotationPresent(RequiresIdempotencyKey.class)).as(door.getName() + " keyed").isTrue();
                assertThat(door.getAnnotation(RequiresAssurance.class).value()).as(door.getName() + " step-up").isEqualTo(AssuranceLevel.MULTI_FACTOR);
            }
        }
    }

    @Test
    @DisplayName("a retry succeeds once and the same key replays its receipt, with one record")
    void aRetryIsKeyed() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);

        try (var ignored = as(OPERATOR); var flow = flow()) {
            DeadLetterDesk.ActReceipt first = desk.retry("key-1", id.toString(), "the topic exists now");
            DeadLetterDesk.ActReceipt replayed = desk.retry("key-1", id.toString(), "the topic exists now");

            assertThat(first).isEqualTo(new DeadLetterDesk.ActReceipt(id.toString(), "RETURNED_TO_RELAY"));
            assertThat(replayed).isEqualTo(first);
        }
        assertThat(audited).hasSize(1);
    }

    @Test
    @DisplayName("a refused act is recorded DENIED in a transaction of its own, the act's own rolled back")
    void aRefusalIsRecorded() {
        UUID notAbandoned = UUID.randomUUID();
        store.published(notAbandoned, "probe.Type", Duration.ofDays(1), Instant.parse("2026-11-07T00:00:00Z"));

        try (var ignored = as(OPERATOR); var flow = flow()) {
            assertRefused(() -> desk.retry("key-2", notAbandoned.toString(), "why"), DeadLetterErrorCode.NOT_ABANDONED);
        }

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst().operation()).isEqualTo(PlatformAuditAction.DEAD_LETTER_ACT_REFUSED);
        assertThat(audited.getFirst().outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(audited.getFirst().changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("act=retry", "refusal=deadletter.NotAbandoned"));
    }

    @Test
    @DisplayName("a refusal stores nothing against the key, so a correction can use it")
    void aRefusalBurnsNoKey() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);
        try (var ignored = as(OPERATOR); var flow = flow()) {
            assertRefused(() -> desk.retry("key-3", id.toString(), "  "), DeadLetterErrorCode.REASON_REQUIRED);
            assertThat(desk.retry("key-3", id.toString(), "with a reason").status()).isEqualTo("RETURNED_TO_RELAY");
        }
    }

    @Test
    @DisplayName("a refused self-approval is recorded; the second person's approval then succeeds")
    void aSelfApprovalIsRefusedAndRecorded() {
        UUID id = UUID.randomUUID();
        store.abandoned(id);
        String discardId;
        try (var ignored = as(OPERATOR); var flow = flow()) {
            discardId = desk.proposeDiscard("p-1", "OUTBOX_EVENT", id.toString(), "unpublishable").subject();
            assertRefused(() -> desk.approveDiscard("a-1", discardId, "mine"), DeadLetterErrorCode.SELF_APPROVAL_REFUSED);
        }
        assertThat(store.event(id).discarded).isFalse();
        assertThat(audited.getLast().operation()).isEqualTo(PlatformAuditAction.DEAD_LETTER_ACT_REFUSED);

        try (var ignored = as(SECOND); var flow = flow()) {
            assertThat(desk.approveDiscard("a-2", discardId, "agreed").status()).isEqualTo("APPROVED");
        }
        assertThat(store.event(id).discarded).isTrue();
    }

    @Test
    @DisplayName("what a refused request asked for is never written: an identifier that is not one is recorded as such")
    void hostileIdentifiersAreNotRecorded() {
        try (var ignored = as(OPERATOR); var flow = flow()) {
            assertRefused(() -> desk.retry("key-4", "<script>alert(1)</script>", "why"), DeadLetterErrorCode.NOT_FOUND);
            assertRefused(() -> desk.proposeDiscard("key-5", "NO_SUCH_KIND", UUID.randomUUID().toString(), "why"), DeadLetterErrorCode.NOT_FOUND);
        }
        assertThat(audited.getFirst().targetId()).isEqualTo("unreadable-identifier");
        assertThat(audited.getFirst().changeSummary().orElseThrow()).doesNotContain("script");
    }

    @Test
    @DisplayName("a replay returns what it requeued, and its window must be two instants")
    void aReplayReturnsItsReceipt() {
        UUID id = UUID.randomUUID();
        store.published(id, "probe.Type", Duration.ofDays(1), Instant.parse("2026-11-07T01:00:00Z"));
        try (var ignored = as(OPERATOR); var flow = flow()) {
            DeadLetterDesk.ReplayReceipt receipt =
                    desk.replay("r-1", "probe.Type", "2026-11-07T00:00:00Z", "2026-11-07T02:00:00Z", "a handler arrived");
            assertThat(receipt.requeued()).isEqualTo(1);
            assertRefused(() -> desk.replay("r-2", "probe.Type", "yesterday", "2026-11-07T02:00:00Z", "why"), DeadLetterErrorCode.REPLAY_INVALID);
        }
    }

    @Test
    @DisplayName("the read names no payload, and serves the proposal's reason to whoever decides it")
    void theReadIsBounded() {
        store.abandoned(UUID.randomUUID());
        UUID discardable = UUID.randomUUID();
        store.abandoned(discardable);
        try (var ignored = as(OPERATOR); var flow = flow()) {
            desk.proposeDiscard("p-2", "OUTBOX_EVENT", discardable.toString(), "cannot be sent");
            DeadLetterDesk.DeadLettersView view = desk.read();

            assertThat(view.abandoned()).hasSize(2);
            assertThat(view.standingDiscards()).singleElement().satisfies(discard -> assertThat(discard.reason()).isEqualTo("cannot be sent"));
            assertThat(view.abandoned().getFirst().failureClass()).doesNotContain(":");
        }
    }

    // -----------------------------------------------------------------

    private static SecurityContext.Scope as(Actor actor) {
        return SecurityContext.enter(actor);
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("flow-1")));
    }

    private static void assertRefused(Runnable act, DeadLetterErrorCode code) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(code));
    }

    /** {@code IdempotentExecutor}'s store, in memory: a claim, its completion and a replay. */
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
