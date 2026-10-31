package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.LockGrace;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
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
 * The valuation rate set doors' boundary (`P14-TSK-014`; {@code PHASE_14_PLAN.md} sections 10, 12): every act keyed per
 * principal and replayed from its stored receipt - every rate a decimal string with its provenance - the domain's
 * refusals on the error contract (a stale or missing pair {@code 422} storing nothing, a decided set or an approved
 * period {@code 409}, the self-approval {@code 403} recorded {@code FAILED} in a transaction of its own), and the read.
 * The routes' permissions: the capture the accountant's {@code ACCOUNTING_CLOSE_PROPOSE}, the decisions and the read the
 * controller's {@code ACCOUNTING_CLOSE_APPROVE}, pinned here and by {@code RoutePermissionRegisterTest}. Hermetic: the
 * stores, the claims, the cutoff and the reference are in memory.
 */
@DisplayName("the valuation rate set doors (P14-TSK-014)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ValuationRateSetDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            ValuationRateSetDeskTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final List<CurrencyCode> VALUED = List.of(CurrencyCode.of("GBP"), CurrencyCode.of("JPY"));
    private static final UUID GBP_SNAPSHOT = UUID.randomUUID();

    private final InMemoryAccountingPeriodStore periodStore = new InMemoryAccountingPeriodStore();
    private final InMemoryValuationRateSetStore store = new InMemoryValuationRateSetStore().watching(periodStore);
    private final List<AuditRecord> audited = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private Instant gbpObservedAt = Instant.parse("2026-09-30T16:00:00Z");
    private Optional<LocalDate> closedThrough = Optional.empty();

    private final PostingCutoff cutoff = new PostingCutoff() {
        @Override
        public Optional<LocalDate> closedThrough(Connection unitOfWork) {
            return closedThrough;
        }

        @Override
        public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
            closedThrough = Optional.of(through);
            return true;
        }

        @Override
        public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
            closedThrough = to;
            return true;
        }
    };
    private final ValuationRates reference = new ValuationRates() {
        @Override
        public List<CurrencyCode> valued() {
            return VALUED;
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            return List.of(
                    new Observed(VALUED.get(0), Optional.of(new Observation(GBP_SNAPSHOT, "simulated-reference", EUR,
                            VALUED.get(0), new BigDecimal("0.8612000000"), gbpObservedAt, gbpObservedAt.plusSeconds(1)))),
                    new Observed(VALUED.get(1), Optional.of(new Observation(UUID.randomUUID(), "simulated-reference",
                            EUR, VALUED.get(1), new BigDecimal("161.2500000000"), Instant.parse("2026-09-30T16:00:00Z"),
                            Instant.parse("2026-09-30T16:00:01Z")))));
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
    private final AccountingPeriods periods = new AccountingPeriods(periodStore, new InMemoryGlMappingStore(), cutoff,
            LockGrace.standard(), (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
    private final ValuationRateSets sets = new ValuationRateSets(store, periodStore, reference,
            ValuationStaleness.standard(), (uow, record) -> audited.add(record), IDS, CLOCK);
    private final ValuationRateSetDesk desk = new ValuationRateSetDesk(sets,
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), unitOfWork,
            AccountingFlowScope.NONE);

    @Test
    @DisplayName("a capture is keyed per principal: a replayed key answers the stored receipt - every rate a decimal"
            + " string with its provenance - and writes nothing; another key is another proposal")
    void aCaptureIsKeyedAndReplayed() {
        String september = lockedSeptember();

        ValuationRateSetDesk.ValuationRateSetReceipt captured =
                as(ACCOUNTANT, () -> desk.capture("key-1", september, null));

        assertThat(captured.status()).isEqualTo("PROPOSED");
        assertThat(captured.period()).isEqualTo("2026-09");
        assertThat(captured.periodId()).isEqualTo(september);
        assertThat(captured.asOf()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(captured.maxAgeHours()).isEqualTo(96);
        assertThat(captured.rates()).extracting(ValuationRateSetDesk.ValuationRateView::rate)
                .containsExactly("0.8612000000", "161.2500000000");
        assertThat(captured.rates().get(0)).satisfies(rate -> {
            assertThat(rate.baseCurrency()).isEqualTo("EUR");
            assertThat(rate.quoteCurrency()).isEqualTo("GBP");
            assertThat(rate.source()).isEqualTo("simulated-reference");
            assertThat(rate.snapshotId()).isEqualTo(GBP_SNAPSHOT.toString());
            assertThat(rate.observedAt()).isEqualTo("2026-09-30T16:00:00Z");
            assertThat(rate.receivedAt()).isEqualTo("2026-09-30T16:00:01Z");
        });
        assertThat(as(ACCOUNTANT, () -> desk.capture("key-1", september, null))).isEqualTo(captured);
        assertThat(store.all()).hasSize(1);
        assertThat(as(ACCOUNTANT, () -> desk.capture("key-2", september, null)).id()).isNotEqualTo(captured.id());
        assertThat(store.all()).hasSize(2);
    }

    @Test
    @DisplayName("the refusals on the contract: unknown 404, an OPEN period 409, a stale pair 422 storing nothing, a"
            + " missing decision reason 422, a second approval for the period 409")
    void theRefusals() {
        refused(() -> as(ACCOUNTANT, () -> desk.capture("k", "not-a-uuid", null)), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.capture("k", UUID.randomUUID().toString(), null)),
                AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(CONTROLLER, () -> desk.approve("k", UUID.randomUUID().toString(), "why")),
                AccountingErrorCode.NOT_FOUND, 404);
        String september = openSeptember();
        refused(() -> as(ACCOUNTANT, () -> desk.capture("k-open", september, null)), AccountingErrorCode.PERIOD_STALE,
                409);
        periods.lock(UOW, periodId(september), ACCOUNTANT, null, CorrelationId.generate(IDS));

        gbpObservedAt = Instant.parse("2026-09-20T16:00:00Z");
        refused(() -> as(ACCOUNTANT, () -> desk.capture("k-stale", september, null)),
                AccountingErrorCode.VALUATION_RATE_STALE, 422);
        assertThat(claims).as("the refusal stored nothing").isEmpty();
        assertThat(store.all()).isEmpty();
        gbpObservedAt = Instant.parse("2026-09-30T16:00:00Z");
        assertThat(as(ACCOUNTANT, () -> desk.capture("k-stale", september, null)).status()).isEqualTo("PROPOSED");

        String first = as(ACCOUNTANT, () -> desk.capture("k-first", september, null)).id();
        String second = as(ACCOUNTANT, () -> desk.capture("k-second", september, null)).id();
        refused(() -> as(CONTROLLER, () -> desk.approve("k-r", first, " ")), AccountingErrorCode.REASON_REQUIRED, 422);
        as(CONTROLLER, () -> desk.approve("k-a1", first, "checked"));
        refused(() -> as(CONTROLLER, () -> desk.approve("k-a2", second, "checked")),
                AccountingErrorCode.VALUATION_SET_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.capture("k-after", september, null)),
                AccountingErrorCode.VALUATION_SET_STALE, 409);
    }

    @Test
    @DisplayName("the approval is four-eyes: the capturer's is 403 and recorded FAILED in its own transaction; a second"
            + " person's approves, its key replays the receipt; the read serves the set with its instants")
    void theApprovalIsFourEyes() {
        String september = lockedSeptember();
        String id = as(ACCOUNTANT, () -> desk.capture("capture", september, "month end")).id();

        refused(() -> as(ACCOUNTANT, () -> desk.approve("approve-self", id, "mine")),
                AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited).filteredOn(record -> record.outcome() == AuditOutcome.FAILED).singleElement()
                .satisfies(record -> {
                    assertThat(record.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
                    assertThat(record.targetId()).isEqualTo(id);
                    assertThat(record.changeSummary()).contains("period=2026-09, act=valuation:approve");
                });

        ValuationRateSetDesk.ValuationRateSetReceipt approved =
                as(CONTROLLER, () -> desk.approve("approve", id, "reviewed"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(as(CONTROLLER, () -> desk.approve("approve", id, "reviewed"))).isEqualTo(approved);
        assertThat(audited).filteredOn(record -> record.operation().code().equals("accounting.ValuationSetApproved"))
                .hasSize(1);

        ValuationRateSetDesk.ValuationRateSetView read = desk.read(id);
        assertThat(read.status()).isEqualTo("APPROVED");
        assertThat(read.rates()).isEqualTo(approved.rates());
        assertThat(read.capturedAt()).isNotNull();
        assertThat(read.decidedAt()).isNotNull();
        assertThatThrownBy(() -> desk.read("not-a-uuid")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a rejection is reasoned and terminal; its key replays")
    void aRejectionIsTerminal() {
        String id = as(ACCOUNTANT, () -> desk.capture("capture", lockedSeptember(), null)).id();
        ValuationRateSetDesk.ValuationRateSetReceipt rejected =
                as(CONTROLLER, () -> desk.reject("reject", id, "wrong source"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(as(CONTROLLER, () -> desk.reject("reject", id, "wrong source"))).isEqualTo(rejected);
        refused(() -> as(CONTROLLER, () -> desk.approve("approve", id, "late")), AccountingErrorCode.VALUATION_SET_STALE,
                409);
    }

    @Test
    @DisplayName("the stored receipt reads back exactly - every rate and its provenance")
    void theStoredReceiptRoundTrips() {
        ValuationRateSetDesk.ValuationRateSetReceipt receipt = new ValuationRateSetDesk.ValuationRateSetReceipt(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "2026-09", "PROPOSED",
                "2026-10-01T00:00:00Z", 96, List.of(
                        new ValuationRateSetDesk.ValuationRateView("EUR", "GBP", "0.8612000000", "simulated-reference",
                                UUID.randomUUID().toString(), "2026-09-30T16:00:00Z", "2026-09-30T16:00:01.123456Z"),
                        new ValuationRateSetDesk.ValuationRateView("EUR", "BHD", "0.4421000000", "simulated-reference",
                                UUID.randomUUID().toString(), "2026-09-30T15:00:00Z", "2026-09-30T15:00:02Z")));
        assertThat(ValuationRateSetDesk.fromStored(ValuationRateSetDesk.stored(receipt))).isEqualTo(receipt);
    }

    @Test
    @DisplayName("the routes: the capture ACCOUNTING_CLOSE_PROPOSE, the decisions and the read ACCOUNTING_CLOSE_APPROVE;"
            + " every POST keyed")
    void theRoutesAreThePartition() {
        Map<String, PermissionName> expected = Map.of(
                "captureValuationRateSet", PermissionName.ACCOUNTING_CLOSE_PROPOSE,
                "approveValuationRateSet", PermissionName.ACCOUNTING_CLOSE_APPROVE,
                "rejectValuationRateSet", PermissionName.ACCOUNTING_CLOSE_APPROVE,
                "readValuationRateSet", PermissionName.ACCOUNTING_CLOSE_APPROVE);
        int doors = 0;
        for (Method method : ValuationRateSetController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                doors++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                        .isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName())
                        .isEqualTo(!method.getName().startsWith("read"));
            }
        }
        assertThat(doors).isEqualTo(expected.size());
    }

    // -----------------------------------------------------------------

    /** September 2026, opened as the first period. */
    private String openSeptember() {
        new AccountingPeriods(periodStore, new InMemoryGlMappingStore(), cutoff, LockGrace.standard(), (uow, record) -> { },
                (uow, envelope, payload, mediaType) -> { }, IDS,
                Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneOffset.UTC))
                .openNext(UOW, Actor.SYSTEM, CorrelationId.generate(IDS));
        return periodStore.month(SEPTEMBER).orElseThrow().id().value().toString();
    }

    /** September 2026, opened and locked. */
    private String lockedSeptember() {
        String september = openSeptember();
        periods.lock(UOW, periodId(september), ACCOUNTANT, null, CorrelationId.generate(IDS));
        return september;
    }

    private static com.finapp.accounting.AccountingPeriodId periodId(String raw) {
        return com.finapp.accounting.AccountingPeriodId.of(UUID.fromString(raw));
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
