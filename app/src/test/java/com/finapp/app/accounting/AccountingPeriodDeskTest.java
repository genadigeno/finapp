package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.LockGrace;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
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
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
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
import java.time.LocalDate;
import java.time.YearMonth;
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
 * The accounting period doors' boundary (`P14-TSK-005`; {@code PHASE_14_PLAN.md} sections 10, 12): every act keyed per
 * principal and replayed from its stored receipt, the domain's refusals on the error contract - the self-approval
 * {@code 403} recorded {@code FAILED} in a transaction of its own, the busy cutoff {@code 503} storing nothing - and the
 * read. The routes' permissions are the partition of identity {@code V025} (lock and unlock proposal the accountant's,
 * the unlock approval the controller's, the list the accountant's), pinned here and by {@code RoutePermissionRegisterTest}.
 * Hermetic: the stores, the claims and the cutoff are in memory.
 */
@DisplayName("the accounting period doors (P14-TSK-005)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountingPeriodDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            AccountingPeriodDeskTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private final InMemoryAccountingPeriodStore store = new InMemoryAccountingPeriodStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private Optional<LocalDate> closedThrough = Optional.empty();
    private boolean busy;
    private final PostingCutoff cutoff = new PostingCutoff() {
        @Override
        public Optional<LocalDate> closedThrough(Connection unitOfWork) {
            return closedThrough;
        }

        @Override
        public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
            if (busy) {
                throw new PostingCutoff.CutoffBusy("postings held the key", null);
            }
            closedThrough = Optional.of(through);
            return true;
        }

        @Override
        public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
            closedThrough = to;
            return true;
        }
    };
    private final AccountingTransactions unitOfWork = new AccountingTransactions() {
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
    private final AccountingPeriods periods = new AccountingPeriods(store, new InMemoryGlMappingStore(), cutoff,
            LockGrace.standard(), (uow, record) -> audited.add(record),
            (uow, envelope, payload, mediaType) -> events.add(envelope.eventType()), IDS, CLOCK);
    private final AccountingPeriodDesk desk = new AccountingPeriodDesk(periods,
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), unitOfWork,
            AccountingFlowScope.NONE);

    @Test
    @DisplayName("a lock is keyed per principal: a replayed key answers the stored receipt and writes nothing; another"
            + " key on the LOCKED period answers LOCKED from the edge")
    void aLockIsKeyedAndConverges() {
        String id = september();
        AccountingPeriodDesk.AccountingPeriodReceipt locked = as(ACCOUNTANT, () -> desk.lock("key-1", id, null));
        assertThat(locked.status()).isEqualTo("LOCKED");
        assertThat(locked.period()).isEqualTo("2026-09");
        assertThat(as(ACCOUNTANT, () -> desk.lock("key-1", id, null))).isEqualTo(locked);
        assertThat(as(ACCOUNTANT, () -> desk.lock("key-2", id, null))).isEqualTo(locked);
        assertThat(events).containsExactly(AccountingPeriods.OPENED_EVENT, AccountingPeriods.LOCKED_EVENT);
        assertThat(closedThrough).contains(LocalDate.of(2026, 9, 30));

        List<AccountingPeriodDesk.AccountingPeriodView> listed = desk.list(null);
        assertThat(listed).singleElement().satisfies(view -> {
            assertThat(view.status()).isEqualTo("LOCKED");
            assertThat(view.lockableFrom()).isEqualTo("2026-10-02");
            assertThat(view.endDate()).isEqualTo("2026-09-30");
            assertThat(view.unlockPending()).isFalse();
        });
    }

    @Test
    @DisplayName("the refusals on the contract: unknown 404, too early or out of order 409, a busy cutoff 503 storing"
            + " nothing, an unreasoned unlock 422, a stale act 409")
    void theRefusals() {
        refused(() -> as(ACCOUNTANT, () -> desk.lock("k", "not-a-uuid", null)), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.lock("k", UUID.randomUUID().toString(), null)),
                AccountingErrorCode.NOT_FOUND, 404);
        periods.openNext(UOW, Actor.SYSTEM, CorrelationId.generate(IDS)); // October: before its grace
        String october = store.month(YearMonth.of(2026, 10)).orElseThrow().id().value().toString();
        refused(() -> as(ACCOUNTANT, () -> desk.lock("k-oct", october, null)),
                AccountingErrorCode.PERIOD_NOT_LOCKABLE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeUnlock("k-u", october, "why")),
                AccountingErrorCode.PERIOD_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeUnlock("k-r", october, " ")),
                AccountingErrorCode.REASON_REQUIRED, 422);
    }

    @Test
    @DisplayName("a busy cutoff is 503 and stores nothing under the key - the retry with the same key locks")
    void aBusyCutoffIsRetried() {
        String id = september();
        busy = true;
        refused(() -> as(ACCOUNTANT, () -> desk.lock("key-busy", id, null)), AccountingErrorCode.CUTOFF_BUSY, 503);
        assertThat(claims).as("the refusal stored nothing").isEmpty();
        busy = false;
        assertThat(as(ACCOUNTANT, () -> desk.lock("key-busy", id, null)).status()).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("the unlock is four-eyes: the proposer's approval is 403 and recorded FAILED in its own transaction;"
            + " a second person's approval opens the period")
    void theUnlockIsFourEyes() {
        String id = september();
        as(ACCOUNTANT, () -> desk.lock("lock", id, null));
        AccountingPeriodDesk.AccountingPeriodReceipt proposed =
                as(ACCOUNTANT, () -> desk.proposeUnlock("propose", id, "a missed accrual"));
        assertThat(proposed.unlockPending()).isTrue();
        refused(() -> as(ACCOUNTANT, () -> desk.approveUnlock("approve-self", id, "mine")),
                AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited).filteredOn(record -> record.outcome() == AuditOutcome.FAILED).singleElement()
                .satisfies(record -> {
                    assertThat(record.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
                    assertThat(record.targetId()).isEqualTo(id);
                });
        AccountingPeriodDesk.AccountingPeriodReceipt unlocked =
                as(CONTROLLER, () -> desk.approveUnlock("approve", id, "reviewed"));
        assertThat(unlocked.status()).isEqualTo("OPEN");
        assertThat(closedThrough).isEmpty();
    }

    @Test
    @DisplayName("the routes: list ACCOUNTING_READ, lock and unlock proposal ACCOUNTING_CLOSE_PROPOSE, the approval"
            + " ACCOUNTING_CLOSE_APPROVE; every POST keyed")
    void theRoutesAreThePartition() {
        Map<String, PermissionName> expected = Map.of(
                "listAccountingPeriods", PermissionName.ACCOUNTING_READ,
                "lockAccountingPeriod", PermissionName.ACCOUNTING_CLOSE_PROPOSE,
                "proposeAccountingPeriodUnlock", PermissionName.ACCOUNTING_CLOSE_PROPOSE,
                "approveAccountingPeriodUnlock", PermissionName.ACCOUNTING_CLOSE_APPROVE);
        int doors = 0;
        for (Method method : AccountingPeriodController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                doors++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                        .isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName())
                        .isEqualTo(!method.getName().startsWith("list"));
            }
        }
        assertThat(doors).isEqualTo(expected.size());
    }

    // -----------------------------------------------------------------

    /** September 2026, opened as the first period. */
    private String september() {
        new AccountingPeriods(store, new InMemoryGlMappingStore(), cutoff, LockGrace.standard(), (uow, record) -> {},
                (uow, envelope, payload, mediaType) -> events.add(envelope.eventType()), IDS,
                Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneOffset.UTC))
                .openNext(UOW, Actor.SYSTEM, CorrelationId.generate(IDS));
        return store.month(SEPTEMBER).orElseThrow().id().value().toString();
    }

    private static void refused(Runnable act, AccountingErrorCode code, int status) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.errorCode().status()).isEqualTo(status);
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

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
