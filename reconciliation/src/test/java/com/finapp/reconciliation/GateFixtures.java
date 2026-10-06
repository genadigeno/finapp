package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The real wiring the Phase 8 exit review's gate-claim suites share (`P8-DOC-001`): the matcher,
 * the sweep, the suspense and the resolution machine over the real schema and ledger, seeded on
 * private sources, plus the two seams those suites need - a dynamic-proxy interceptor over a
 * port (failure injection and latches inside a real transaction) and a lock-waiter probe on
 * {@code pg_stat_activity} (a latch that knows the other transaction is BLOCKED, not merely
 * started).
 *
 * <p>The matcher's clock is deliberately months behind the database clock (the
 * {@code GraceAndRematchDatabaseTest} discipline): every window is judged in SQL on the database
 * clock. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition: this added that an expectation
 * opened at the real instant reads "opened after" every decision, "the rematch predicate's
 * reading" - the rematch now judges a reach on rows, never on clocks.)*
 */
final class GateFixtures {

    static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    static final Actor PROPOSER = new Actor("op-gate-proposer", ActorType.EMPLOYEE);
    static final Actor APPROVER = new Actor("op-gate-approver", ActorType.EMPLOYEE);
    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final CurrencyCode GBP = CurrencyCode.of("GBP");
    static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    static final LocalDate FAR_FUTURE = SETTLED_ON.plusYears(1);
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 500_000);

    /** Types by the key's prefix: {@code TERM} terminal, {@code INF-} in flight, else unknown. */
    static final InternalReferenceLookup LOOKUP =
            (unitOfWork, subject) -> {
                for (String value : subject.references().values()) {
                    if (value.startsWith("TERM")) {
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.TERMINAL,
                                Optional.of("op-term"),
                                Optional.of("VOIDED"));
                    }
                    if (value.startsWith("INF-")) {
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.IN_FLIGHT,
                                Optional.of("op-inflight"),
                                Optional.of("PENDING"));
                    }
                }
                return InternalReferenceLookup.InternalReference.unknown();
            };

    private GateFixtures() {}

    // ----------------------------------------------------------------- wiring

    static PostingService postingService(PostingObserver observer) {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                observer);
    }

    static Suspense suspense() {
        return suspense(PostingObserver.NONE);
    }

    static Suspense suspense(PostingObserver observer) {
        return new Suspense(postingService(observer), new JdbcLedgerAccountStore(), IDS);
    }

    static JdbcBreakRegister register() {
        return new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
    }

    /** The matcher's parts, each replaceable by a suite's seam. */
    static final class MatchingParts {
        MatchingStore store = new JdbcMatchingStore();
        BreakRegister breaks = register();
        Suspense suspense = suspense();
        Resolutions resolutions = ResolutionFixtures.resolutions(IDS, CLOCK);
        Matching.Config config = new Matching.Config(200, 2);
        boolean bypassTheTryLock = false;

        Matching build() {
            if (bypassTheTryLock) {
                return new Matching(
                        store, new MatchingRules(), breaks, suspense, resolutions, LOOKUP,
                        new JdbcLedgerAccountStore(), new JdbcOutboxWriter(),
                        new JdbcAuditWriter(), IDS, CLOCK, config, runner()) {
                    @Override
                    boolean claimSource(Connection unitOfWork, UUID sourceId) {
                        return true; // The probe: the try-lock only ORDERS.
                    }
                };
            }
            return new Matching(
                    store, new MatchingRules(), breaks, suspense, resolutions, LOOKUP,
                    new JdbcLedgerAccountStore(), new JdbcOutboxWriter(), new JdbcAuditWriter(),
                    IDS, CLOCK, config, runner());
        }
    }

    static Matching matching() {
        return new MatchingParts().build();
    }

    /** The observers on the REAL clock: ageing is judged on the database's day. */
    static ReconciliationSweep reconciliationSweep(BreakRegister breaks) {
        return new ReconciliationSweep(
                new JdbcMatchingStore(),
                breaks,
                new KeyCollisionBreaks(register(), new JdbcRuleSets(), IDS),
                new JdbcOutboxWriter(),
                IDS,
                Clock.systemUTC(),
                new ReconciliationSweep.Config(500, 3),
                runner());
    }

    static ResolutionMachine machine(ResolutionStore store, MatchingStore matchingStore) {
        return new ResolutionMachine(
                store,
                new JdbcBreakCaseStore(),
                suspense(),
                matchingStore,
                new JdbcRuleSets(),
                ResolutionFixtures.adjustments(IDS, CLOCK),
                new JdbcLedgerAccountStore(),
                new JdbcOutboxWriter(),
                new JdbcAuditWriter(),
                IDS,
                CLOCK,
                ReconciliationTelemetry.NONE,
                (unitOfWork, subject) ->
                        InternalReferenceLookup.InternalReference.unknown(),
                ReturnedPayouts.NONE,
                ResolvedCorridorReturns.NONE);
    }

    static ResolutionMachine machine() {
        return machine(new JdbcResolutionStore(), new JdbcMatchingStore());
    }

    static RunReplays replays() {
        return new RunReplays(
                runner(),
                new JdbcRunReplayStore(new MatchingRules()),
                new JdbcMatchingStore(),
                register(),
                new JdbcAuditWriter(),
                IDS,
                CLOCK,
                ReplayObserver.NONE);
    }

    /** One committed transaction per call - what the app's runner bean does. */
    static TransactionRunner runner() {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                return inCommittedTransaction(work::apply);
            }
        };
    }

    // ----------------------------------------------------------------- the machine's verbs

    static ResolutionMachine.Proposed propose(
            ResolutionMachine machine, Actor actor, UUID breakId, ResolutionKind kind,
            ResolutionReasonCode code, Optional<UUID> target, Optional<UUID> chosen) {
        return as(actor, uow -> machine.propose(uow, breakId,
                new ResolutionMachine.ProposalRequest(kind, code, "the gate suite's account",
                        target, Optional.empty(), chosen),
                actor, CorrelationContext.current().orElseThrow().correlationId()));
    }

    static ResolutionMachine.Decided approve(
            ResolutionMachine machine, Actor actor, UUID resolutionId) {
        return as(actor, uow -> machine.approve(uow, resolutionId, actor,
                CorrelationContext.current().orElseThrow().correlationId()));
    }

    // ----------------------------------------------------------------- seeding

    /** A private source's rule set v1: capture and refund one-to-one, the date tolerance. */
    static void seedRuleSet(UUID source, UUID ruleSet) {
        inCommittedTransaction(app -> {
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?, 'test', NULL,"
                            + " 'P8-DOC-001 gate suite private rule set', now(),"
                            + " 'p8-doc-001-test') ON CONFLICT (id) DO NOTHING",
                    ruleSet, source, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality, operation_anchored,"
                            + " grace_hours) VALUES"
                            + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                            + " 'ONE_TO_ONE', false, 48),"
                            + " (?, 2, 'REFUND', 'PSP_REFUND_REF', 'CARD_REFUND',"
                            + " 'ONE_TO_ONE', false, 48) ON CONFLICT DO NOTHING",
                    ruleSet, ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES (?, 'SETTLEMENT_DATE_DAYS', NULL,"
                            + " NULL, 2) ON CONFLICT DO NOTHING",
                    ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000), (?, 'GBP', 100000)"
                            + " ON CONFLICT DO NOTHING",
                    ruleSet, ruleSet);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE',"
                            + " decided_by = 'test-activator', decided_at = now()"
                            + " WHERE id = ? AND status = 'PROPOSED'",
                    ruleSet);
            return null;
        });
    }

    record Line(
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            CurrencyCode currency,
            ItemKeyKind keyKind,
            String keyValue,
            byte[] fingerprint,
            LocalDate settlementDate) {}

    static Line capture(int lineNo, long minor, String key) {
        return new Line(lineNo, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND, minor,
                EUR, ItemKeyKind.PSP_CAPTURE_REF, key, fingerprint(), SETTLED_ON);
    }

    static Line refund(int lineNo, long minor, String key) {
        return new Line(lineNo, ExternalLineType.REFUND, ExpectationDirection.OUTBOUND, minor,
                EUR, ItemKeyKind.PSP_REFUND_REF, key, fingerprint(), SETTLED_ON);
    }

    static byte[] fingerprint() {
        byte[] print = new byte[32];
        new SecureRandom().nextBytes(print);
        return print;
    }

    static long nextSequence() {
        return SEQUENCES.incrementAndGet();
    }

    /** A committed BATCH run with its PENDING items - what acceptance births. */
    static UUID seedRun(UUID source, UUID ruleSet, Line... lines) {
        List<UUID> itemIds = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            itemIds.add(IDS.next());
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            order.add(i);
        }
        return seedRun(source, ruleSet, nextSequence(), itemIds, order, lines);
    }

    /**
     * A committed BATCH run whose items carry {@code itemIds} (index-aligned with
     * {@code lines}) and are born in {@code insertionOrder} - the claimant-order probe's
     * control over both the row ids and the physical insertion order.
     */
    static UUID seedRun(
            UUID source,
            UUID ruleSet,
            long sequence,
            List<UUID> itemIds,
            List<Integer> insertionOrder,
            Line... lines) {
        UUID runId = IDS.next();
        return inCommittedTransaction(app -> {
            new JdbcReconciliationRuns().birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            runId, source, Optional.of(IDS.next()), RunKind.BATCH, ruleSet,
                            SETTLED_ON, Optional.of(sequence), lines.length, Optional.empty(),
                            Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (int index : insertionOrder) {
                Line line = lines[index];
                newItems.add(
                        new ExternalItems.NewItem(
                                itemIds.get(index), runId, source, IDS.next(), line.lineNo(),
                                line.type(), line.direction(),
                                Money.ofPersisted(line.minor(), line.currency(), 2),
                                AccountPurpose.SETTLEMENT_CLEARING, SETTLED_ON,
                                Optional.of(line.settlementDate()),
                                Optional.of(line.settlementDate()), line.fingerprint(),
                                Map.of(line.keyKind(), line.keyValue()), Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
            }
            new JdbcExternalItems().birthAll(app, PLATFORM, newItems);
            return runId;
        });
    }

    record Seeded(UUID id, String key) {}

    /** An expectation keyed by {@code key}, OPENED AT THE REAL INSTANT (after every decision). */
    static Seeded openExpectation(
            UUID source, UUID ruleSet, String key, long minor, ExpectationDirection direction,
            LocalDate expectedBy) {
        return openExpectation(source, ruleSet, key, minor, EUR, direction, expectedBy);
    }

    static Seeded openExpectation(
            UUID source, UUID ruleSet, String key, long minor, CurrencyCode currency,
            ExpectationDirection direction, LocalDate expectedBy) {
        String operationRef = UUID.randomUUID().toString();
        ExpectationKind kind = direction == ExpectationDirection.INBOUND
                ? ExpectationKind.CARD_CAPTURE : ExpectationKind.CARD_REFUND;
        KeyKind keyKind = direction == ExpectationDirection.INBOUND
                ? KeyKind.PSP_CAPTURE_REF : KeyKind.PSP_REFUND_REF;
        inCommittedTransaction(app -> {
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, currency)
                            .orElseThrow()
                            .id()
                            .value();
            new JdbcExpectationRegister(IDS).open(
                    app,
                    new NewExpectation(
                            kind, operationRef, "p8doc1:" + operationRef, source,
                            AccountPurpose.SETTLEMENT_CLEARING, position, direction,
                            Money.ofPersisted(minor, currency, 2), Optional.of(IDS.next()),
                            SETTLED_ON, Optional.empty(), expectedBy, ruleSet,
                            List.of(new NewExpectation.ExpectationKey(keyKind, key)),
                            PLATFORM, Instant.now(), CorrelationId.generate(IDS)));
            return null;
        });
        return new Seeded(
                (UUID) one("SELECT id FROM reconciliation.expectation WHERE operation_ref = ?"
                        + " AND kind = ?", operationRef, kind.name()),
                key);
    }

    static UUID raise(BreakType type, BreakCause cause, BreakRegister.Subject subject,
            long minor, UUID source, UUID ruleSet) {
        UUID breakId = IDS.next();
        inCommittedTransaction(app -> {
            register().raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId, type, cause, subject, source, ruleSet,
                            Money.ofPersisted(minor, EUR, 2), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            return null;
        });
        return breakId;
    }

    static Suspense.ParkResult park(UUID source, UUID item, UUID breakId, long minor,
            LocalDate decidedOn) {
        return as(PLATFORM, uow -> suspense().park(uow, new Suspense.ParkCommand(
                source, decidedOn,
                List.of(new Suspense.ParkedItem(item, breakId, Money.ofPersisted(minor, EUR, 2),
                        operational(AccountPurpose.SETTLEMENT_CLEARING))),
                PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS))));
    }

    static void completeRun(UUID run) {
        as(PLATFORM, uow -> {
            JdbcMatchingStore store = new JdbcMatchingStore();
            store.markRunInProgress(uow, run, PLATFORM, Instant.now(CLOCK));
            store.completeRun(uow, run, PLATFORM, Instant.now(CLOCK));
            return null;
        });
    }

    /** The stored window moved, never the clock: expiry stays a database-clock fact. */
    static void expireGrace(UUID runId) {
        inCommittedTransaction(app -> {
            execute(app, "UPDATE reconciliation.external_item SET grace_until = now()"
                    + " - interval '1 hour' WHERE run_id = ?", runId);
            return null;
        });
    }

    record Parked(UUID runId, UUID itemId, UUID breakId, UUID suspenseItemId) {}

    /**
     * An item parked under {@code type}/{@code cause}, its run completed - the
     * {@code ResolutionMachineDatabaseTest} fixture.
     */
    static Parked parked(UUID source, UUID ruleSet, Line line, BreakType type, BreakCause cause,
            LocalDate parkedOn) {
        UUID run = seedRun(source, ruleSet, line);
        UUID item = itemOf(run, line.lineNo());
        UUID breakId = raise(type, cause, BreakRegister.Subject.externalItem(item), line.minor(),
                source, ruleSet);
        park(source, item, breakId, line.minor(), parkedOn);
        completeRun(run);
        return new Parked(run, item, breakId, (UUID) one("SELECT id FROM"
                + " reconciliation.suspense_item WHERE external_item_id = ?", item));
    }

    /**
     * The planted AMBIGUOUS_MATCH (the engine never produces one: one expectation per key -
     * `V002`'s {@code expectation_key_once}): an item whose stored decision saw
     * {@code chosen} and {@code other}, parked under its AMBIGUOUS_MATCH break. The item quotes
     * {@code chosen}'s key, so a REPROCESS run's re-decision reaches the same pair a manual match
     * would allocate - the C11 race's two writers. (Since the Phase 8 -> 9 transition the rematch
     * leg does not: the planted decision has seen both candidates, and the rematch judges only a
     * reach no decision of the item has seen.)
     */
    static Parked plantAmbiguous(UUID source, UUID ruleSet, Seeded chosen, Seeded other,
            long minor) {
        return plantAmbiguous(source, ruleSet, chosen, other, minor, ExpectationDirection.INBOUND);
    }

    /** As above; an OUTBOUND item is a refund line over two refund candidates (a DEBIT park). */
    static Parked plantAmbiguous(UUID source, UUID ruleSet, Seeded chosen, Seeded other,
            long minor, ExpectationDirection direction) {
        boolean inbound = direction == ExpectationDirection.INBOUND;
        KeyKind keyKind = inbound ? KeyKind.PSP_CAPTURE_REF : KeyKind.PSP_REFUND_REF;
        UUID run = seedRun(source, ruleSet,
                inbound ? capture(1, minor, chosen.key()) : refund(1, minor, chosen.key()));
        UUID item = itemOf(run, 1);
        JdbcMatchingStore store = new JdbcMatchingStore();
        as(PLATFORM, uow -> {
            UUID decision = IDS.next();
            store.insertDecision(uow, new MatchingStore.NewDecision(
                    decision, item, run, DecisionOrigin.RUN, ruleSet, Optional.of(inbound ? 1 : 2),
                    Optional.of(Cardinality.ONE_TO_ONE), Optional.of(keyKind),
                    DecisionOutcome.PARKED, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                    LocalDate.now(CLOCK), CorrelationId.generate(IDS),
                    MatchingStore.Basis.match(
                            DecisionVerdict.AMBIGUOUS, JudgedStatus.PENDING, minor, false)));
            store.insertCandidates(uow, decision, store.lockExpectations(uow,
                    List.of(chosen.id(), other.id()),
                    Map.of(chosen.id(), keyKind, other.id(), keyKind)));
            return null;
        });
        UUID ambiguous = raise(BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                BreakRegister.Subject.externalItem(item), minor, source, ruleSet);
        park(source, item, ambiguous, minor, SETTLED_ON);
        completeRun(run);
        return new Parked(run, item, ambiguous, (UUID) one("SELECT id FROM"
                + " reconciliation.suspense_item WHERE external_item_id = ?", item));
    }

    static UUID openWallet() {
        return inCommittedTransaction(uow -> new JdbcLedgerAccountStore()
                .createOrConverge(uow, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                        AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next()))
                .account()
                .id()
                .value());
    }

    static UUID operational(AccountPurpose purpose) {
        return inCommittedTransaction(uow -> new JdbcLedgerAccountStore()
                .findOperational(uow, purpose, EUR).orElseThrow().id().value());
    }

    /** The database's own date - the clock every window here is judged on. */
    static LocalDate databaseToday() {
        Object today = one("SELECT current_date");
        return today instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) today;
    }

    // ----------------------------------------------------------------- seams

    /** What an intercepted call does instead: {@code proceed} runs the real call. */
    @FunctionalInterface
    interface Interception {
        Object around(Object[] args, Proceed proceed) throws Throwable;
    }

    @FunctionalInterface
    interface Proceed {
        Object call() throws Throwable;
    }

    /**
     * A port whose {@code method} calls run through {@code interception}; every other call
     * reaches {@code delegate} unchanged. Exceptions surface as the delegate threw them.
     */
    @SuppressWarnings("unchecked")
    static <T> T intercept(Class<T> port, T delegate, String method, Interception interception) {
        InvocationHandler handler = (proxy, invoked, args) -> {
            Proceed real = () -> {
                try {
                    return invoked.invoke(delegate, args);
                } catch (InvocationTargetException thrown) {
                    throw thrown.getCause();
                }
            };
            if (invoked.getName().equals(method)) {
                return interception.around(args, real);
            }
            return real.call();
        };
        return (T) Proxy.newProxyInstance(
                port.getClassLoader(), new Class<?>[] {port}, handler);
    }

    /** The injected fault: a crash between two writes of one transaction. */
    static final class InjectedFault extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        InjectedFault(String where) {
            super("injected fault: " + where);
        }
    }

    /**
     * Waits until some backend of this database is BLOCKED on a heavyweight lock whose query
     * text contains {@code queryFragment} - the latch that knows the racer is held, not merely
     * started. Returns early (false) if {@code racer} finished first: the caller's assertions
     * then judge what a racer that was never held did.
     */
    static boolean awaitLockWaiter(Future<?> racer, String queryFragment) throws Exception {
        for (int poll = 0; poll < 400; poll++) {
            if (racer.isDone()) {
                return false;
            }
            Object waiting = one("SELECT count(*) FROM pg_stat_activity WHERE"
                    + " datname = current_database() AND wait_event_type = 'Lock'"
                    + " AND query LIKE ?", "%" + queryFragment + "%");
            if (((Number) waiting).longValue() > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the racer was never seen blocked on a lock");
    }

    // ----------------------------------------------------------------- plumbing

    @FunctionalInterface
    interface Work<R> {
        R apply(Connection unitOfWork) throws SQLException;
    }

    /** One committed transaction as {@code actor}, inside its own correlation scope. */
    @SuppressWarnings("try")
    static <R> R as(Actor actor, Work<R> work) {
        try (SecurityContext.Scope identity = SecurityContext.enter(actor);
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return inCommittedTransaction(work);
        }
    }

    static <R> R inCommittedTransaction(Work<R> work) {
        try (Connection unitOfWork = DatabaseRoles.application()) {
            unitOfWork.setAutoCommit(false);
            try {
                R result = work.apply(unitOfWork);
                unitOfWork.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                unitOfWork.rollback();
                throw failure instanceof RuntimeException runtime
                        ? runtime
                        : new ReconciliationStorageException("test transaction failed", failure);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("test transaction failed", failure);
        }
    }

    static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
        }
    }

    static UUID itemOf(UUID run, int lineNo) {
        return (UUID) one("SELECT id FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_no = " + lineNo, run);
    }

    static long count(String sql, Object... args) {
        Object value = one(sql, args);
        return value == null ? 0L : ((Number) value).longValue();
    }

    static String string(String sql, Object... args) {
        Object value = one(sql, args);
        return value == null ? null : value.toString();
    }

    /** One fresh autocommit read: committed state only, safe from any thread. */
    static Object one(String sql, Object... args) {
        try (Connection read = DatabaseRoles.application();
                PreparedStatement statement = read.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("read failed: " + sql, failure);
        }
    }

    static List<String> column(String sql, Object... args) {
        try (Connection read = DatabaseRoles.application();
                PreparedStatement statement = read.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(String.valueOf(rows.getObject(1)));
                }
            }
            return values;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("read failed: " + sql, failure);
        }
    }
}
