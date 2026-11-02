package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The close run's doors (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 10, 12): the read on {@code ACCOUNTING_READ},
 * audited before it is served, carrying every check's evidence and never a person or a reason; the re-run on {@code
 * ACCOUNTING_CLOSE_PROPOSE}, keyed, reasoned, a new attempt, replayed as itself; the refusals on the contract. The
 * routes' 401/403 over HTTP are the interceptor's, held by {@code RoutePermissionRegisterTest}.
 */
@DisplayName("the close run doors (P14-TSK-011)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class CloseRunDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore store = new InMemoryCloseRunStore(periods);
    private final List<AuditRecord> audited = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final CloseRuns runs = new CloseRuns(store, periods, new InMemoryAccountingBreakStore(), CloseRuns.Leases.STANDARD,
            (uow, record) -> audited.add(record), IDS, CLOCK);
    private final AccountingTransactions transactions = new AccountingTransactions() {
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
    private final CloseRunDesk desk = new CloseRunDesk(runs,
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions,
            AccountingFlowScope.NONE);
    private AccountingPeriodId march;

    @BeforeEach
    void marchIsLocked() {
        march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        periods.markLocked(UOW, march, 1, ACCOUNTANT, Optional.empty());
    }

    @Test
    @DisplayName("the read: every check with its attempts, its failures, the versions read and its evidence - recorded before it is served,"
            + " naming no person and no reason")
    void theReadIsAuditedAndNamesNoOne() {
        runs.startMissing(UOW, Actor.SYSTEM);
        CloseRuns.ClaimedCheck held = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        runs.conclude(UOW, held, CheckVerdict.failed(held.check(), "report:TRIAL_BALANCE:v1", List.of("TRIAL_BALANCE_FALSE"),
                CanonicalJson.object().put("passes", false)));
        runs.rerun(UOW, march, held.check(), new Actor("accountant-secret", ActorType.CUSTOMER), "the secret reason",
                CorrelationId.generate(IDS));

        CloseRunDesk.CloseRunView view = as(ACCOUNTANT, () -> desk.closeRun(march.value().toString()));

        assertThat(view.period()).isEqualTo("2097-03");
        assertThat(view.current()).isTrue();
        assertThat(view.complete()).isFalse();
        assertThat(view.checks()).hasSize(CloseCheckCode.values().length);
        CloseRunDesk.CloseCheckView first = view.checks().get(0);
        assertThat(first.check()).isEqualTo(held.check().name());
        assertThat(first.attempts()).isEqualTo(2);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.history()).hasSize(2);
        assertThat(first.history().get(1).failures()).containsExactly("TRIAL_BALANCE_FALSE");
        assertThat(first.history().get(1).judgedBy()).isEqualTo("report:TRIAL_BALANCE:v1");
        assertThat(first.history().get(1).evidence()).contains("\"passes\":false");
        assertThat(view.toString()).doesNotContain("accountant-secret").doesNotContain("the secret reason");
        assertThat(audited).filteredOn(record -> record.operation().code().equals("accounting.CloseRunRead")).singleElement()
                .satisfies(record -> {
                    assertThat(record.targetId()).isEqualTo(view.runId());
                    assertThat(record.changeSummary().orElseThrow()).contains("period=2097-03", "current=true", "complete=false")
                            .doesNotContain("passes");
                });
    }

    @Test
    @DisplayName("no run: an unknown period, a malformed id and a lock not yet started are each accounting.NotFound, recording nothing")
    void noRunIsNotFound() {
        refused(() -> as(ACCOUNTANT, () -> desk.closeRun(march.value().toString())), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.closeRun(UUID.randomUUID().toString())), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.closeRun("not-a-uuid")), AccountingErrorCode.NOT_FOUND, 404);
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("the re-run: a new PENDING attempt, keyed - a replayed key answers the stored receipt, writing nothing")
    void theRerunIsKeyed() {
        runs.startMissing(UOW, Actor.SYSTEM);
        CloseRuns.ClaimedCheck held = runs.claim(UOW, Actor.SYSTEM).orElseThrow();
        runs.conclude(UOW, held, CheckVerdict.passed(held.check(), "report:TRIAL_BALANCE:v1", CanonicalJson.object().put("a", "b")));
        String check = held.check().name();

        CloseRunDesk.CloseCheckRerunReceipt first = as(ACCOUNTANT,
                () -> desk.rerun("key-1", march.value().toString(), check, "a correction posted"));
        assertThat(first.attempt()).isEqualTo(2);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.check()).isEqualTo(check);

        CloseRunDesk.CloseCheckRerunReceipt replay = as(ACCOUNTANT,
                () -> desk.rerun("key-1", march.value().toString(), check, "a correction posted"));
        assertThat(replay).isEqualTo(first);
        assertThat(store.all(store.runs().get(0).id())).filteredOn(attempt -> attempt.check() == held.check()).hasSize(2);
        assertThat(audited).filteredOn(record -> record.operation().code().equals("accounting.CloseCheckRerun")).hasSize(1);
    }

    @Test
    @DisplayName("the refusals: a pending attempt 409, a missing reason 422, an unknown check or a lock with no run 404, a period not LOCKED 409")
    void theRefusalsAreOnTheContract() {
        String id = march.value().toString();
        refused(() -> as(ACCOUNTANT, () -> desk.rerun("k1", id, CloseCheckCode.RULE_CONFORMANCE.name(), "why")),
                AccountingErrorCode.NOT_FOUND, 404);
        runs.startMissing(UOW, Actor.SYSTEM);
        refused(() -> as(ACCOUNTANT, () -> desk.rerun("k2", id, CloseCheckCode.RULE_CONFORMANCE.name(), "why")),
                AccountingErrorCode.CLOSE_CHECK_PENDING, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.rerun("k3", id, CloseCheckCode.RULE_CONFORMANCE.name(), " ")),
                AccountingErrorCode.REASON_REQUIRED, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.rerun("k4", id, "NOT_A_CHECK", "why")), AccountingErrorCode.NOT_FOUND, 404);
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.closeForTest(MARCH);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, MARCH.plusMonths(1), CorrelationId.generate(IDS)));
        refused(() -> as(ACCOUNTANT, () -> desk.rerun("k5", april.value().toString(), CloseCheckCode.RULE_CONFORMANCE.name(), "why")),
                AccountingErrorCode.PERIOD_STALE, 409);
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("the routes: the read ACCOUNTING_READ, unkeyed; the re-run ACCOUNTING_CLOSE_PROPOSE and keyed - never the approver's")
    void theRoutesAreThePartition() {
        Map<String, PermissionName> expected = Map.of(
                "readAccountingCloseRun", PermissionName.ACCOUNTING_READ,
                "rerunAccountingCloseCheck", PermissionName.ACCOUNTING_CLOSE_PROPOSE);
        int doors = 0;
        for (Method method : CloseRunController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                doors++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                        .isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName())
                        .isEqualTo(method.getName().startsWith("rerun"));
            }
        }
        assertThat(doors).isEqualTo(expected.size());
    }

    // ------------------------------------------------------------------ plumbing

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
