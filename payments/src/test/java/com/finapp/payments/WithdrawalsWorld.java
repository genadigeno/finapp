package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.BalanceProjection;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.DerivedBalance;
import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.HoldStatus;
import com.finapp.ledger.HoldStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * A payments-local world for the withdrawal engine's hermetic suites (`P13-TSK-009`): the REAL {@link Withdrawals},
 * {@link WithdrawalOutcomes}, {@link WithdrawalResolution}, ledger {@link HoldService} and platform
 * {@link IdempotentExecutor} over in-memory stores - the withdrawal rows with {@code V033}'s conditional edges, the
 * holds with their conditional release, the idempotency claims with their lease - a scripted risk seam and a scripted
 * push rail. One clock is everything's: the stores' "database clock" included, which a suite advances to pass a hold's
 * {@code held_until} - so the expiry is judged by the store's own now, never by a suite's instant.
 *
 * <p>The transaction runner keeps a snapshot and restores it on any exception, so a rolled-back transaction leaves
 * nothing - which is what lets a suite assert "no row, no hold, no claim" of an aborted dispatch. The posting path is a
 * tripwire, not a fake: a {@code PostingService} over JDBC stores with no database, so an acceptance (which posts) is
 * never scripted here - the rail answers {@code INDETERMINATE} or {@code REJECTED}. Single-threaded by design: the
 * concurrency is the owed database suites'.
 */
final class WithdrawalsWorld {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** The unit of work every transaction is handed: never used - every store here is in memory. */
    static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(
            WithdrawalsWorld.class.getClassLoader(), new Class<?>[] {Connection.class}, (self, method, args) -> {
                switch (method.getName()) {
                    case "toString":
                        return "the-world's-connection";
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return self == args[0];
                    default:
                        throw new AssertionError("the withdrawal world's connection was used: " + method.getName());
                }
            });
    static final RailId INSTANT = RailId.of("instant");

    /** A clock every collaborator shares, advanced by the suite. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-23T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Everything a transaction can write, snapshotted per transaction and restored on rollback. */
    static final class State {
        final Map<WithdrawalId, Withdrawal> withdrawals = new LinkedHashMap<>();
        final Map<WithdrawalId, String> dispatchKeys = new HashMap<>();
        final List<String> history = new ArrayList<>();
        final Map<HoldId, Hold> holds = new LinkedHashMap<>();
        final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
        final List<AuditRecord> audit = new ArrayList<>();
        final List<EventEnvelope> outbox = new ArrayList<>();
        final List<RoutingDecision> decisions = new ArrayList<>();
        final List<String> evidence = new ArrayList<>();

        State copy() {
            State copy = new State();
            copy.withdrawals.putAll(withdrawals);
            copy.dispatchKeys.putAll(dispatchKeys);
            copy.history.addAll(history);
            copy.holds.putAll(holds);
            copy.claims.putAll(claims);
            copy.audit.addAll(audit);
            copy.outbox.addAll(outbox);
            copy.decisions.addAll(decisions);
            copy.evidence.addAll(evidence);
            return copy;
        }
    }

    final MutableClock clock = new MutableClock();
    final IdGenerator ids = new IdGenerator(clock, new SecureRandom());
    State state = new State();

    final UUID party = UUID.randomUUID();
    final UUID customer = UUID.randomUUID();
    final UUID paymentMethod = UUID.randomUUID();
    /** The wallet's customer account (the ledger's owner_ref). */
    final UUID product = UUID.randomUUID();
    final LedgerAccount wallet;
    Money settled = Money.ofMinorUnits(100_000_00, EUR);

    /** What the risk seam answers next, and every withdrawal it was asked about. */
    WithdrawalVerdict verdict = WithdrawalVerdict.permit();
    RuntimeException seamFailure;
    final List<WithdrawalRiskDecision.Judged> judged = new ArrayList<>();

    /** What the push rail answers next, and every send it was asked for. */
    PushAnswer answer = PushAnswer.indeterminate();
    final List<PushRail.CreditTransfer> sends = new ArrayList<>();

    final Withdrawals engine;
    final WithdrawalOutcomes outcomes;
    final WithdrawalResolution resolution;
    final HoldService holdService;
    final InMemoryWithdrawals store = new InMemoryWithdrawals();

    WithdrawalsWorld() {
        wallet = LedgerAccount.owned(ids, clock, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR, product);
        LedgerAccountStore<Connection> accounts = proxy(LedgerAccountStore.class, Map.of(
                "lockForUpdate", args -> Optional.of(wallet),
                "lockForShare", args -> Optional.of(wallet)));
        BalanceDerivation<Connection> derivation = (uow, account, asOf) -> new DerivedBalance(account, settled);
        BalanceProjection<Connection> projection = proxy(BalanceProjection.class, Map.of(
                "adjustHolds", args -> null));
        holdService = new HoldService(accounts, derivation, new InMemoryHolds(), projection,
                (uow, record) -> state.audit.add(record),
                (uow, envelope, payload, mediaType) -> state.outbox.add(envelope), ids, clock);
        PaymentRails rails = PaymentRails.of(List.of(SimulatedInstantSchemeAdapter.RAIL));
        // The posting is a tripwire: an acceptance is never scripted in these suites.
        PostingService postings = new PostingService(
                new IdempotentExecutor(new com.finapp.platform.idempotency.JdbcIdempotencyRecordStore(), clock,
                        Duration.ofDays(1), Duration.ofMinutes(5)),
                new com.finapp.ledger.JdbcJournalEntryStore(ids), (uow, record) -> {}, new JdbcOutboxWriter(),
                new com.finapp.ledger.JdbcBalanceProjection(), ids, clock, PostingObserver.NONE);
        outcomes = new WithdrawalOutcomes(store, holdService, postings,
                new ChartOfAccounts<>(new JdbcLedgerAccountStore()),
                (uow, record) -> state.audit.add(record),
                (uow, envelope, payload, mediaType) -> state.outbox.add(envelope), ids, clock,
                RailOutcomeObserver.NONE, rails, new JdbcSchemeExecutionClaimStore(),
                new RecordingSettlementExpectations());
        RoutingPolicyVersion policy = RoutingPolicyVersion.create(ids, 1,
                List.of(new RoutingPolicyVersion.NewRule(PaymentDirection.PAY_OUT, InstrumentKind.BANK_ACCOUNT,
                        Optional.empty(), Optional.empty(), List.of(INSTANT))),
                Optional.empty(), "withdrawals-world", "the bank pay-out onto the instant rail", clock);
        RoutingStore<Connection> routing = proxy(RoutingStore.class, Map.of(
                "findVersionInForce", args -> Optional.of(policy),
                "availabilityByRail", args -> Map.of(),
                "insertDecision", args -> state.decisions.add((RoutingDecision) args[1])));
        ProviderEvidenceStore<Connection> evidence = proxy(ProviderEvidenceStore.class, Map.of(
                "appendForWithdrawal", args -> state.evidence.add(args[1].toString())));
        PushRail rail = proxy(PushRail.class, Map.of(
                "send", args -> {
                    sends.add((PushRail.CreditTransfer) args[0]);
                    return answer;
                },
                "schemeName", args -> "simulated-instant-scheme"));
        IdempotentExecutor executor = new IdempotentExecutor(new InMemoryClaims(), clock, Duration.ofDays(1),
                Duration.ofMinutes(5));
        TransactionRunner runner = new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                State before = state.copy();
                try {
                    return work.apply(CONNECTION);
                } catch (RuntimeException rolledBack) {
                    state = before;
                    throw rolledBack;
                }
            }
        };
        WithdrawalRiskDecision<Connection> seam = (uow, withdrawal) -> {
            judged.add(withdrawal);
            if (seamFailure != null) {
                throw seamFailure;
            }
            return verdict;
        };
        engine = new Withdrawals(store, outcomes, holdService, routing, rails, RailOperations.ofPush(INSTANT, rail),
                evidence, executor, (uow, record) -> state.audit.add(record), ids, clock, runner, seam);
        resolution = new WithdrawalResolution(store, outcomes, rail, rails, INSTANT, evidence,
                new WithdrawalResolution.Config(Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(5), 25),
                ids, clock, runner);
    }

    /** The caller's resolution - the person's wallet, instrument and destination. */
    Withdrawals.Resolved resolved() {
        return new Withdrawals.Resolved(party, customer, wallet.id(), EUR, paymentMethod,
                new ProviderReference("dest-world-" + party.toString().substring(0, 8)));
    }

    Withdrawals.Initiated withdraw(String key, long minor) {
        return engine.withdraw(key, Money.ofMinorUnits(minor, EUR), uow -> resolved());
    }

    Withdrawal row(WithdrawalId id) {
        return state.withdrawals.get(id);
    }

    Hold holdOf(WithdrawalId id) {
        return state.holds.get(row(id).holdId());
    }

    long activeHolds() {
        return state.holds.values().stream().filter(hold -> hold.status() == HoldStatus.ACTIVE).count();
    }

    List<String> eventTypes() {
        return state.outbox.stream().map(EventEnvelope::eventType).toList();
    }

    List<String> auditCodes() {
        return state.audit.stream().map(record -> record.operation().code()).toList();
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<?> type, Map<String, Function<Object[], Object>> answers) {
        return (T) Proxy.newProxyInstance(WithdrawalsWorld.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> {
                    Function<Object[], Object> answer = answers.get(method.getName());
                    if (answer != null) {
                        return answer.apply(args);
                    }
                    if (method.getName().equals("toString")) {
                        return type.getSimpleName() + "-in-the-world";
                    }
                    throw new AssertionError("the withdrawal world does not answer " + type.getSimpleName() + "."
                            + method.getName());
                });
    }

    // ------------------------------------------------------------------ the stores

    /** payments.withdrawal in memory: V033's conditional edges, the database clock the world's. */
    final class InMemoryWithdrawals implements WithdrawalStore<Connection> {

        @Override
        public Stored insert(Connection unitOfWork, Withdrawal born, String dispatchKey) {
            if (state.dispatchKeys.entrySet().stream().anyMatch(entry -> entry.getValue().equals(dispatchKey)
                    && state.withdrawals.get(entry.getKey()).customerId().equals(born.customerId()))) {
                throw new PaymentsStorageException("withdrawal_one_per_dispatch_key");
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Instant permit = born.createdAt().isAfter(now) ? born.createdAt() : now;
            Optional<Withdrawal.HeldWindow> held = born.held()
                    .map(window -> new Withdrawal.HeldWindow(permit, permit.plus(window.validity())));
            state.withdrawals.put(born.id(), born.withStoredBirth(permit, held));
            state.dispatchKeys.put(born.id(), dispatchKey);
            return new Stored(permit, held);
        }

        @Override
        public Stored insertInstructed(Connection unitOfWork, Withdrawal dispatched, String instructionReference) {
            return insert(unitOfWork, dispatched, instructionReference);
        }

        @Override
        public Optional<Instant> releaseHeld(Connection unitOfWork, Withdrawal before, String key, Instant at) {
            Withdrawal current = state.withdrawals.get(before.id());
            if (current == null || current.status() != WithdrawalStatus.HELD
                    || !current.held().orElseThrow().until().isAfter(clock.instant())) {
                return Optional.empty();
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Instant floor = current.lastDispatchedAt().plus(1, ChronoUnit.MICROS);
            Instant permit = now.isAfter(floor) ? now : floor;
            state.withdrawals.put(before.id(), current.release(permit, key));
            state.history.add(before.id() + ":HELD->DISPATCHED");
            return Optional.of(permit);
        }

        @Override
        public Optional<Withdrawal> claimExpiredHold(Connection unitOfWork, Set<WithdrawalId> passedOver) {
            return state.withdrawals.values().stream()
                    .filter(row -> row.status() == WithdrawalStatus.HELD)
                    .filter(row -> !row.held().orElseThrow().until().isAfter(clock.instant()))
                    .filter(row -> !passedOver.contains(row.id()))
                    .min(java.util.Comparator.comparing((Withdrawal row) -> row.held().orElseThrow().until())
                            .thenComparing(row -> row.id().value()));
        }

        @Override
        public Optional<Withdrawal> findByInstruction(Connection unitOfWork, String instructionReference) {
            return Optional.empty();
        }

        @Override
        public Optional<Instant> concludedAt(Connection unitOfWork, WithdrawalId id) {
            return Optional.empty();
        }

        @Override
        public Optional<Withdrawal> findOwned(Connection unitOfWork, WithdrawalId id, UUID customerId) {
            return Optional.ofNullable(state.withdrawals.get(id)).filter(row -> row.customerId().equals(customerId));
        }

        @Override
        public Optional<Withdrawal> findForUpdate(Connection unitOfWork, WithdrawalId id) {
            return Optional.ofNullable(state.withdrawals.get(id));
        }

        @Override
        public Optional<Withdrawal> findByDispatchKey(Connection unitOfWork, UUID customerId, String dispatchKey) {
            return state.dispatchKeys.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(dispatchKey))
                    .map(entry -> state.withdrawals.get(entry.getKey()))
                    .filter(row -> row.customerId().equals(customerId))
                    .findFirst();
        }

        @Override
        public Optional<Withdrawal> findByEndToEndReference(Connection unitOfWork, EndToEndReference reference) {
            return state.withdrawals.values().stream().filter(row -> row.reference().equals(reference)).findFirst();
        }

        @Override
        public Optional<Instant> renewSendPermit(Connection unitOfWork, Withdrawal before) {
            Withdrawal current = state.withdrawals.get(before.id());
            if (current == null || !current.status().isResolvable()
                    || current.lastDispatchedAt().isAfter(before.lastDispatchedAt())) {
                return Optional.empty();
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Instant floor = current.lastDispatchedAt().plus(1, ChronoUnit.MICROS);
            Instant permit = now.isAfter(floor) ? now : floor;
            state.withdrawals.put(before.id(), current.withSendPermit(permit));
            return Optional.of(permit);
        }

        @Override
        public boolean transition(Connection unitOfWork, Withdrawal before, Withdrawal after, Instant at) {
            Withdrawal current = state.withdrawals.get(before.id());
            if (current == null || current.status() != before.status()) {
                return false;
            }
            if (!before.status().canTransitionTo(after.status())) {
                throw new PaymentsStorageException("V033's machine trigger: " + before.status() + " -> "
                        + after.status());
            }
            state.withdrawals.put(after.id(), after);
            state.history.add(after.id() + ":" + before.status() + "->" + after.status());
            return true;
        }

        @Override
        public List<Withdrawal> pageCompleted(Connection unitOfWork, UUID after, int limit) {
            return List.of();
        }

        @Override
        public List<Withdrawal> findSweepable(Connection unitOfWork, Instant dispatchedBefore, Instant unknownBefore,
                int limit) {
            return state.withdrawals.values().stream()
                    .filter(row -> row.status().isResolvable())
                    .filter(row -> !row.lastDispatchedAt().isAfter(dispatchedBefore))
                    .limit(limit)
                    .toList();
        }

        @Override
        public PaymentAttemptStore.UnknownReading unknownReading(Connection unitOfWork, Duration dispatchedBound) {
            return new PaymentAttemptStore.UnknownReading(0, 0);
        }
    }

    /** ledger.hold in memory: the conditional release. */
    final class InMemoryHolds implements HoldStore<Connection> {

        @Override
        public void insert(Connection unitOfWork, Hold hold) {
            state.holds.put(hold.id(), hold);
        }

        @Override
        public Optional<Hold> findById(Connection unitOfWork, HoldId id) {
            return Optional.ofNullable(state.holds.get(id));
        }

        @Override
        public List<Hold> findActiveFor(Connection unitOfWork, LedgerAccountId account) {
            return state.holds.values().stream()
                    .filter(hold -> hold.account().equals(account) && hold.status() == HoldStatus.ACTIVE)
                    .toList();
        }

        @Override
        public long countActive(Connection unitOfWork) {
            return activeHolds();
        }

        @Override
        public boolean moveToReleased(Connection unitOfWork, HoldId id, Instant at) {
            Hold hold = state.holds.get(id);
            if (hold == null || hold.status() != HoldStatus.ACTIVE) {
                return false;
            }
            state.holds.put(id, hold.release(clock));
            return true;
        }
    }

    /** platform.idempotency_record in memory: one row per key, the lease on the world's clock. */
    final class InMemoryClaims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (state.claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            state.claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                    StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(state.claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = state.claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            state.claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
                    held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            IdempotencyRecord held = state.claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS
                    || held.createdAt().plus(lease).isAfter(now)) {
                return false;
            }
            state.claims.put(key, new IdempotencyRecord(key, held.fingerprint(), IdempotencyState.IN_PROGRESS,
                    held.response(), correlationId, now, expiresAt));
            return true;
        }
    }
}
