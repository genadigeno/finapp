package com.finapp.credit;

import static com.finapp.credit.ScorecardFixtures.IDS;
import static com.finapp.credit.ScorecardFixtures.inOneTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.credit.CreditDecisionConsumptions.Consumption;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Credit's decision consumption port against a real database (`P11-TSK-001`; PHASE_11_PLAN.md section 12.10, ADR-0091;
 * {@code INV-LND-06}, {@code INV-CRD-09}, {@code INV-CRD-02}, {@code INV-IDEM-01}, {@code INV-HIST-01}).
 *
 * <p>Separate connections stand for separate instances (the {@code P0-TST-009} convention). Every race is made provable -
 * the racers seen waiting on the party's profile row, read from {@code pg_blocking_pids}, before it is released - and
 * every count is read from the rows. The "deciding transaction" a consumption races is the deciding transaction's own
 * exposure step - the party's profile through {@link CreditProfiles#lockForDecision}, then {@link JdbcReservedExposure}
 * - the exact primitives {@code DecisionMaking} composes. Each serialisation case runs through the port and through
 * {@code credit.consume_decision} called directly with no lock taken first: the function is the guard for any caller.
 */
@Tag("database")
@SuppressWarnings("try")
@DisplayName("the credit decision consumption port (P11-TSK-001)")
class CreditDecisionConsumptionDatabaseTest {

    private static final UUID MODEL = UUID.fromString("0190a1b2-5c0e-7000-8000-00000000c001");
    private static final JdbcDecisionRequestStore REQUESTS = new JdbcDecisionRequestStore();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money APPROVED = Money.ofMinorUnits(500_000, EUR);
    private static final int INSTANCES = 10;
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RAISE_EXCEPTION = "P0001";
    private static final String UNIQUE_VIOLATION = "23505";
    /** JdbcCreditProfiles' lock, as pg_stat_activity shows the waiting statement. */
    private static final String PROFILE_LOCK = "FROM credit.credit_profile WHERE party_id = $1 FOR UPDATE";

    private final Queue<ConsumptionObserver.Outcome> observed = new ConcurrentLinkedQueue<>();
    private final JdbcReservedExposure reserved = new JdbcReservedExposure();
    private final CreditProfiles<Connection> profiles = new JdbcCreditProfiles(IDS);
    private CreditDecisionConsumptions<Connection> port;

    /** How a case reaches the guard: the port, or the definer function with no lock taken first. */
    private enum Path { PORT, FUNCTION }

    @BeforeEach
    void wire() {
        port = port(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ at most once

    @Test
    @DisplayName("ten consumers of one decision, each its own ref, provably in flight together: one Consumed, nine"
            + " AlreadyConsumed naming the winner - one row, one audit record")
    void tenConsumersOneConsumption() throws Exception {
        UUID party = IDS.next();
        CreditDecisionId decision = approved(party, Duration.ofDays(30));
        List<ConsumerRef> consumers = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            consumers.add(agreement());
        }
        List<Consumption> answers = whileProfileHeld(party, consumers.stream()
                .<Callable<Consumption>>map(consumer -> () -> consumeAndCommit(port, decision, consumer)).toList());

        List<Consumption.Consumed> won = answers.stream().filter(Consumption.Consumed.class::isInstance)
                .map(Consumption.Consumed.class::cast).toList();
        assertThat(won).as("exactly one consumer takes the approval up").singleElement()
                .satisfies(consumed -> assertThat(consumed.replayed()).isFalse());
        ConsumerRef winner = consumerOf(decision).orElseThrow();
        assertThat(answers).filteredOn(Consumption.AlreadyConsumed.class::isInstance).hasSize(INSTANCES - 1)
                .allSatisfy(answer -> assertThat(((Consumption.AlreadyConsumed) answer).by()).isEqualTo(winner));
        assertThat(won.get(0).decision().id()).isEqualTo(decision);
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption WHERE decision_id = ?", decision.value()))
                .isEqualTo(1);
        assertThat(audits(decision)).as("written by the winner only").isEqualTo(1);
        assertThat(observed).containsExactlyInAnyOrderElementsOf(answers.stream()
                .map(ConsumptionObserver.Outcome::of).toList());
    }

    @Test
    @DisplayName("ten retries of one consumer, provably in flight together: one Consumed, nine replays of the same"
            + " consumption - one row, one audit record")
    void tenRetriesOfOneConsumerConsumeOnce() throws Exception {
        UUID party = IDS.next();
        CreditDecisionId decision = approved(party, Duration.ofDays(30));
        ConsumerRef consumer = agreement();
        List<Callable<Consumption>> retries = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            retries.add(() -> consumeAndCommit(port, decision, consumer));
        }
        List<Consumption> answers = whileProfileHeld(party, retries);
        assertThat(answers).allSatisfy(answer -> assertThat(answer).isInstanceOf(Consumption.Consumed.class));
        assertThat(answers.stream().map(Consumption.Consumed.class::cast).filter(c -> !c.replayed())).hasSize(1);
        assertThat(answers.stream().map(answer -> ((Consumption.Consumed) answer).id()).distinct())
                .as("every replay names the one consumption").hasSize(1);
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption WHERE decision_id = ?", decision.value()))
                .isEqualTo(1);
        assertThat(audits(decision)).isEqualTo(1);
    }

    @Test
    @DisplayName("the same consumer replays its own consumption - even after the approval lapsed; another consumer then"
            + " meets AlreadyConsumed, never Lapsed")
    void theSameConsumerReplays() throws Exception {
        UUID party = IDS.next();
        CreditDecisionId decision = approved(party, Duration.ofSeconds(2));
        ConsumerRef consumer = agreement();
        Consumption first = consumeAndCommit(port, decision, consumer);
        assertThat(first).isInstanceOf(Consumption.Consumed.class);
        assertThat(((Consumption.Consumed) first).replayed()).isFalse();

        Consumption again = consumeAndCommit(port, decision, consumer);
        // CreditDecision carries the snapshot's hash as an array, so the answers are compared by their parts.
        assertThat(again).isInstanceOfSatisfying(Consumption.Consumed.class, replay -> {
            assertThat(replay.id()).as("the one consumption").isEqualTo(((Consumption.Consumed) first).id());
            assertThat(replay.decisionId()).isEqualTo(decision);
            assertThat(replay.replayed()).isTrue();
        });

        awaitDatabase("SELECT valid_until <= statement_timestamp() FROM credit.credit_decision WHERE id = ?",
                decision.value());
        Consumption afterTheLapse = consumeAndCommit(port, decision, consumer);
        assertThat(afterTheLapse).as("a consumption committed while valid is a fact; its consumer's retry replays it")
                .isInstanceOf(Consumption.Consumed.class);
        assertThat(((Consumption.Consumed) afterTheLapse).replayed()).isTrue();
        assertThat(consumeAndCommit(port, decision, agreement()))
                .isEqualTo(new Consumption.AlreadyConsumed(decision, consumer));
        assertThat(audits(decision)).as("replays write no second record").isEqualTo(1);
        assertThat(scalar("SELECT (consumed_at < (SELECT valid_until FROM credit.credit_decision WHERE id = ?))::text"
                + " FROM credit.credit_decision_consumption WHERE decision_id = ?", decision.value(), decision.value()))
                .as("consumed before it lapsed").isEqualTo("true");
    }

    @Test
    @DisplayName("one consumer takes up one decision: ten decisions of ten parties raced by one agreement - one consumed,"
            + " nine refused by UNIQUE (consumer_kind, consumer_ref), a caller's defect surfacing as a storage failure")
    void oneConsumerTakesUpOneDecision() throws Exception {
        ConsumerRef agreement = agreement();
        List<CreditDecisionId> decisions = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            decisions.add(approved(IDS.next(), Duration.ofDays(30)));
        }
        CyclicBarrier start = new CyclicBarrier(INSTANCES);
        ExecutorService instances = Executors.newFixedThreadPool(INSTANCES);
        List<Future<Consumption>> pending = new ArrayList<>();
        try {
            for (CreditDecisionId decision : decisions) {
                pending.add(instances.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return consumeAndCommit(port, decision, agreement);
                }));
            }
            int consumed = 0;
            int refused = 0;
            for (Future<Consumption> each : pending) {
                try {
                    assertThat(each.get(2, TimeUnit.MINUTES)).isInstanceOf(Consumption.Consumed.class);
                    consumed++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(CreditStorageException.class)
                            .hasMessageContaining("SQLState " + UNIQUE_VIOLATION);
                    refused++;
                }
            }
            assertThat(consumed).as("one decision consumed for the agreement").isEqualTo(1);
            assertThat(refused).isEqualTo(INSTANCES - 1);
        } finally {
            instances.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption WHERE consumer_ref = ?", agreement.ref()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ only an approval, only while valid

    @Test
    @DisplayName("a declined decision is not consumable - NotApproved through the port, NOT_APPROVED from the function"
            + " itself; nothing written, nothing audited")
    void aDeclinedDecisionIsNotConsumable() throws Exception {
        CreditDecisionId declined = decided(IDS.next(), DecisionOutcome.DECLINED, Duration.ofDays(30));
        assertThat(consumeAndCommit(port, declined, agreement())).isEqualTo(new Consumption.NotApproved(declined));
        assertThat(callFunction(declined, agreement())).isEqualTo("NOT_APPROVED");
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption WHERE decision_id = ?", declined.value()))
                .isZero();
        assertThat(audits(declined)).isZero();
        assertThat(observed).containsExactly(ConsumptionObserver.Outcome.NOT_APPROVED);
    }

    @Test
    @DisplayName("a decision that does not exist is NotFound - from the port, and NOT_FOUND from the function")
    void aMissingDecisionIsNotFound() throws Exception {
        CreditDecisionId missing = CreditDecisionId.next(IDS);
        assertThat(consumeAndCommit(port, missing, agreement())).isEqualTo(new Consumption.NotFound(missing));
        assertThat(callFunction(missing, agreement())).isEqualTo("NOT_FOUND");
        assertThat(observed).containsExactly(ConsumptionObserver.Outcome.NOT_FOUND);
    }

    @Test
    @DisplayName("lapse at the microsecond, on the database clock alone: at t-1us, t and t+1us the function's judgement is"
            + " the reservation's own inequality - exactly one of reserves and lapsed, lapsed at valid_until itself; in"
            + " real time a decision read unreserved under the lock is Lapsed, a consumed one was reserved; and instance"
            + " clocks five seconds either way change nothing")
    void aLapsedDecisionIsNotConsumableAtTheMicrosecond() throws Exception {
        // (1) The predicate the function judges by, at three instants a microsecond apart, beside JdbcReservedExposure's
        // own inequality (valid_until > now) at the same instants.
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement();
                ResultSet row = statement.executeQuery("WITH t(v) AS (SELECT TIMESTAMPTZ '2026-10-10 12:00:00.000000+00')"
                        + " SELECT credit.decision_reserves(v, v - interval '1 microsecond'), credit.decision_reserves(v, v),"
                        + " credit.decision_reserves(v, v + interval '1 microsecond'), v > v - interval '1 microsecond',"
                        + " v > v, v > v + interval '1 microsecond' FROM t")) {
            row.next();
            assertThat(List.of(row.getBoolean(1), row.getBoolean(2), row.getBoolean(3)))
                    .as("consumable at t-1us; lapsed at valid_until itself and after").containsExactly(true, false, false);
            assertThat(List.of(row.getBoolean(4), row.getBoolean(5), row.getBoolean(6)))
                    .as("the reservation's inequality agrees at every instant - never both, never neither")
                    .containsExactly(row.getBoolean(1), row.getBoolean(2), row.getBoolean(3));
        }
        assertThat(scalar("SELECT pg_get_functiondef('credit.consume_decision(uuid,uuid,text,uuid)'::regprocedure)"))
                .as("the function judges the lapse by that predicate, on the instant read under its lock")
                .contains("IF NOT credit.decision_reserves(v_valid_until, v_now) THEN");

        // (2) Real time, around the boundary: approvals valid for 0, 4, 8 ... 156 ms, each asked for the moment after it
        // commits - some still valid, some lapsed by then. Under the profile lock the reservation is read, then the port
        // asked: unreserved implies Lapsed; Consumed implies it was reserved.
        UUID party = IDS.next();
        int consumed = 0;
        int lapsed = 0;
        for (int i = 0; i < 40; i++) {
            try (Connection connection = DatabaseRoles.application();
                    SecurityContext.Scope actor = SecurityContext.enter(customer());
                    CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
                connection.setAutoCommit(false);
                CreditDecisionId decision = approved(party, Duration.ofMillis(4L * i));
                profiles.lockForDecision(connection, party).orElseThrow();
                boolean wasReserved = reservesNow(connection, decision);
                Consumption answer = port.consume(connection, decision, agreement());
                if (answer instanceof Consumption.Consumed) {
                    assertThat(wasReserved).as("a consumed approval was reserving when read before it").isTrue();
                    consumed++;
                } else {
                    assertThat(answer).isInstanceOf(Consumption.Lapsed.class);
                    lapsed++;
                }
                if (!wasReserved) {
                    assertThat(answer).as("read unreserved under the lock, it is lapsed").isInstanceOf(Consumption.Lapsed.class);
                }
                connection.commit();
            }
        }
        assertThat(consumed).as("the sweep met approvals still valid").isPositive();
        assertThat(lapsed).as("and approvals already lapsed - it straddled the boundary").isPositive();

        // (3) Instance clocks skewed five seconds either way: only the audit's timestamp reads them.
        UUID skewed = IDS.next();
        CreditDecisionId lapsedOnTheDatabase = approved(skewed, Duration.ofMillis(-1));
        CreditDecisionId validOnTheDatabase = approved(skewed, Duration.ofSeconds(3));
        CreditDecisionConsumptions<Connection> behind = port(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(-5)));
        CreditDecisionConsumptions<Connection> ahead = port(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(5)));
        assertThat(reservedFor(skewed)).as("only the valid one reserves").isEqualTo(APPROVED);
        Consumption lapsedAnswer = consumeAndCommit(behind, lapsedOnTheDatabase, agreement());
        assertThat(lapsedAnswer).as("an instance five seconds behind still meets the lapse")
                .isInstanceOf(Consumption.Lapsed.class);
        assertThat(((Consumption.Lapsed) lapsedAnswer).validUntil())
                .isEqualTo(Instant.parse(scalar("SELECT to_char(valid_until AT TIME ZONE 'UTC',"
                        + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM credit.credit_decision WHERE id = ?",
                        lapsedOnTheDatabase.value())));
        assertThat(consumeAndCommit(ahead, validOnTheDatabase, agreement()))
                .as("an instance five seconds ahead still consumes a valid approval")
                .isInstanceOf(Consumption.Consumed.class);
        assertThat(reservedFor(skewed)).as("consumed and lapsed: nothing reserved").isEqualTo(Money.zero(EUR));
    }

    // ------------------------------------------------------------------ serialised on the profile

    @Test
    @DisplayName("consumption and a decision for the party serialise on the profile, both orders, through the port and"
            + " the function: the decider first - the approval lapsing while the consumer waits - reads it unreserved and"
            + " the consumer meets Lapsed; the decider first with it valid reads it reserved and the consumer consumes it;"
            + " the consumer first, the decider waits and reads it consumed - never a torn state")
    void consumptionAndADecisionForThePartySerialise() throws Exception {
        for (Path path : Path.values()) {
            deciderFirstWhileTheApprovalLapses(path);
            deciderFirstWhileTheApprovalIsValid(path);
            consumerFirst(path);
        }
    }

    private void deciderFirstWhileTheApprovalLapses(Path path) throws Exception {
        UUID party = IDS.next();
        CreditDecisionId decision = approved(party, Duration.ofSeconds(4));
        ExecutorService consumerInstance = Executors.newSingleThreadExecutor();
        try (Connection decider = DatabaseRoles.application()) {
            decider.setAutoCommit(false);
            profiles.lockForDecision(decider, party).orElseThrow();
            int deciderPid = backendPid(decider);
            Future<String> consuming = consumerInstance.submit(() -> consumeOver(path, decision, agreement()));
            List<String> waiting = waitingOn(deciderPid, consuming);
            assertThat(scalar("SELECT (valid_until > statement_timestamp())::text FROM credit.credit_decision"
                    + " WHERE id = ?", decision.value())).as("%s: the consumer queued while the approval was valid", path)
                    .isEqualTo("true");
            assertThat(waiting).hasSize(1);
            awaitDatabase("SELECT valid_until <= statement_timestamp() FROM credit.credit_decision WHERE id = ?",
                    decision.value());
            assertThat(reserved.reservedFor(decider, party, EUR)).as("%s: the decider reads the approval lapsed", path)
                    .isEqualTo(Money.zero(EUR));
            decider.commit();
            assertThat(consuming.get(1, TimeUnit.MINUTES)).as("%s: so the consumer, judged after the lock, meets the"
                    + " lapse - never a consumption the decider could not see", path).isEqualTo("LAPSED");
        } finally {
            consumerInstance.shutdownNow();
        }
        assertThat(consumerOf(decision)).isEmpty();
    }

    private void deciderFirstWhileTheApprovalIsValid(Path path) throws Exception {
        UUID party = IDS.next();
        CreditDecisionId decision = approved(party, Duration.ofDays(30));
        ExecutorService consumerInstance = Executors.newSingleThreadExecutor();
        try (Connection decider = DatabaseRoles.application()) {
            decider.setAutoCommit(false);
            profiles.lockForDecision(decider, party).orElseThrow();
            Future<String> consuming = consumerInstance.submit(() -> consumeOver(path, decision, agreement()));
            assertThat(waitingOn(backendPid(decider), consuming)).hasSize(1);
            assertThat(reserved.reservedFor(decider, party, EUR)).as("%s: the decider sees the reservation", path)
                    .isEqualTo(APPROVED);
            decider.commit();
            assertThat(consuming.get(1, TimeUnit.MINUTES)).isEqualTo("CONSUMED");
        } finally {
            consumerInstance.shutdownNow();
        }
        assertThat(reservedFor(party)).as("%s: consumed, it reserves nothing", path).isEqualTo(Money.zero(EUR));
    }

    private void consumerFirst(Path path) throws Exception {
        UUID party = IDS.next();
        CreditDecisionId decision = approved(party, Duration.ofDays(30));
        ExecutorService deciderInstance = Executors.newSingleThreadExecutor();
        try (Connection consumer = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(customer());
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            consumer.setAutoCommit(false);
            assertThat(consumeOn(consumer, path, decision, agreement())).isEqualTo("CONSUMED");
            Future<Money> deciding = deciderInstance.submit(() -> {
                try (Connection decider = DatabaseRoles.application()) {
                    decider.setAutoCommit(false);
                    profiles.lockForDecision(decider, party).orElseThrow();
                    Money seen = reserved.reservedFor(decider, party, EUR);
                    decider.commit();
                    return seen;
                }
            });
            assertThat(waitingOn(backendPid(consumer), deciding)).as("%s: the decider waits on the consumer's lock", path)
                    .hasSize(1);
            consumer.commit();
            assertThat(deciding.get(1, TimeUnit.MINUTES)).as("%s: and then reads the approval consumed", path)
                    .isEqualTo(Money.zero(EUR));
        } finally {
            deciderInstance.shutdownNow();
        }
    }

    @Test
    @DisplayName("the port waits on the party's profile at the profile lock itself - before it calls the function - and"
            + " the function called alone waits at its own call; released, each consumes, stamped after the release")
    void consumptionWaitsOnTheProfileLock() throws Exception {
        for (Path path : Path.values()) {
            UUID party = IDS.next();
            CreditDecisionId decision = approved(party, Duration.ofDays(30));
            ExecutorService consumerInstance = Executors.newSingleThreadExecutor();
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                try (PreparedStatement hold = holder.prepareStatement(
                        "SELECT id FROM credit.credit_profile WHERE party_id = ? FOR UPDATE")) {
                    hold.setObject(1, party);
                    hold.executeQuery().close();
                }
                Future<String> consuming = consumerInstance.submit(() -> consumeOver(path, decision, agreement()));
                List<String> waiting = waitingOn(backendPid(holder), consuming);
                assertThat(waiting).as("%s waits at one statement", path).singleElement()
                        .satisfies(query -> assertThat(query).contains(path == Path.PORT ? PROFILE_LOCK
                                : "FROM credit.consume_decision("));
                String released;
                try (Statement statement = holder.createStatement();
                        ResultSet now = statement.executeQuery("SELECT clock_timestamp()::text")) {
                    now.next();
                    released = now.getString(1);
                }
                holder.rollback();
                assertThat(consuming.get(1, TimeUnit.MINUTES)).isEqualTo("CONSUMED");
                assertThat(scalar("SELECT (consumed_at >= ?::timestamptz)::text FROM credit.credit_decision_consumption"
                        + " WHERE decision_id = ?", released, decision.value()))
                        .as("%s: consumed_at is the instant judged under the lock - after the holder let go, never the"
                                + " waiting statement's start", path).isEqualTo("true");
            } finally {
                consumerInstance.shutdownNow();
            }
        }
    }

    // ------------------------------------------------------------------ what a consumption does and does not leave

    @Test
    @DisplayName("a consumed decision reserves nothing - beside it, the party's other approval still reserves")
    void aConsumedDecisionReservesNothing() throws Exception {
        UUID party = IDS.next();
        CreditDecisionId consumedOne = approved(party, Duration.ofDays(30));
        approved(party, Duration.ofDays(30));
        assertThat(reservedFor(party)).isEqualTo(Money.ofMinorUnits(1_000_000, EUR));
        try (Connection connection = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(customer());
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            connection.setAutoCommit(false);
            assertThat(port.consume(connection, consumedOne, agreement())).isInstanceOf(Consumption.Consumed.class);
            assertThat(reserved.reservedFor(connection, party, EUR)).as("in the consuming transaction").isEqualTo(APPROVED);
            connection.commit();
        }
        assertThat(reservedFor(party)).as("and after it commits").isEqualTo(APPROVED);
    }

    @Test
    @DisplayName("a caller's rollback leaves no consumption and no audit record - and the approval stays consumable")
    void aRolledBackCallerLeavesNothing() throws Exception {
        CreditDecisionId decision = approved(IDS.next(), Duration.ofDays(30));
        try (Connection connection = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(customer());
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            connection.setAutoCommit(false);
            assertThat(port.consume(connection, decision, agreement())).isInstanceOf(Consumption.Consumed.class);
            connection.rollback();
        }
        assertThat(consumerOf(decision)).isEmpty();
        assertThat(audits(decision)).isZero();
        assertThat(consumeAndCommit(port, decision, agreement())).isInstanceOf(Consumption.Consumed.class);
    }

    @Test
    @DisplayName("no actor or no correlation in scope: refused before any lock or write")
    void aMissingScopeIsRefusedBeforeAnything() throws Exception {
        CreditDecisionId decision = approved(IDS.next(), Duration.ofDays(30));
        try (Connection connection = DatabaseRoles.application()) {
            assertThatThrownBy(() -> port.consume(connection, decision, agreement()))
                    .isInstanceOf(IllegalStateException.class);
            try (SecurityContext.Scope actor = SecurityContext.enter(customer())) {
                assertThatThrownBy(() -> port.consume(connection, decision, agreement()))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("correlation");
            }
        }
        assertThat(consumerOf(decision)).isEmpty();
        assertThat(observed).isEmpty();
    }

    // ------------------------------------------------------------------ the database guard

    @Test
    @DisplayName("the application role cannot insert a consumption directly - refused by privilege; the owner cannot"
            + " either - refused by the trigger: the definer function is the only path")
    void theApplicationRoleCannotInsertDirectly() throws Exception {
        CreditDecisionId decision = approved(IDS.next(), Duration.ofDays(30));
        String insert = "INSERT INTO credit.credit_decision_consumption (id, decision_id, consumed_at, consumer_kind,"
                + " consumer_ref) VALUES (gen_random_uuid(), '" + decision.value() + "', now(), 'LOAN_AGREEMENT',"
                + " gen_random_uuid())";
        try (Connection app = DatabaseRoles.application()) {
            refused(app, insert, INSUFFICIENT_PRIVILEGE);
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            assertThatExceptionOfType(SQLException.class).isThrownBy(() -> execute(owner, insert))
                    .matches(e -> RAISE_EXCEPTION.equals(e.getSQLState())
                            && e.getMessage().contains("only through credit.consume_decision"), "the trigger's refusal");
        }
        assertThat(consumerOf(decision)).isEmpty();
        assertThat(callFunction(decision, agreement())).as("the function is the path").isEqualTo("CONSUMED");
    }

    @Test
    @DisplayName("a consumption row is never updated, deleted or truncated - by the application (privilege) or the owner"
            + " (trigger)")
    void theConsumptionRowIsNeverUpdatedOrDeletedByAnyRole() throws Exception {
        CreditDecisionId decision = approved(IDS.next(), Duration.ofDays(30));
        ConsumerRef consumer = agreement();
        assertThat(consumeAndCommit(port, decision, consumer)).isInstanceOf(Consumption.Consumed.class);
        String where = " WHERE decision_id = '" + decision.value() + "'";
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "UPDATE credit.credit_decision_consumption SET consumer_ref = gen_random_uuid()" + where,
                    INSUFFICIENT_PRIVILEGE);
            refused(app, "DELETE FROM credit.credit_decision_consumption" + where, INSUFFICIENT_PRIVILEGE);
            refused(app, "TRUNCATE credit.credit_decision_consumption", INSUFFICIENT_PRIVILEGE);
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.credit_decision_consumption SET consumer_ref = gen_random_uuid()" + where,
                    RAISE_EXCEPTION);
            refused(owner, "DELETE FROM credit.credit_decision_consumption" + where, RAISE_EXCEPTION);
            refused(owner, "TRUNCATE credit.credit_decision_consumption", RAISE_EXCEPTION);
        }
        assertThat(consumerOf(decision)).contains(consumer);
    }

    @Test
    @DisplayName("the kind is a closed set: the function refuses a kind credit does not know (the CHECK)")
    void anUnknownKindIsRefused() throws Exception {
        CreditDecisionId decision = approved(IDS.next(), Duration.ofDays(30));
        try (Connection app = DatabaseRoles.application()) {
            refused(app, "SELECT * FROM credit.consume_decision('" + decision.value() + "', gen_random_uuid(), 'BNPL',"
                    + " gen_random_uuid())", "23514");
        }
        assertThat(consumerOf(decision)).isEmpty();
    }

    // ------------------------------------------------------------------ plumbing

    private CreditDecisionConsumptions<Connection> port(Clock clock) {
        return new JdbcCreditDecisionConsumptions(profiles, new JdbcCreditDecisions(), new JdbcAuditWriter(),
                observed::add, IDS, clock);
    }

    private static ConsumerRef agreement() {
        return new ConsumerRef(ConsumerKind.LOAN_AGREEMENT, IDS.next());
    }

    private static Actor customer() {
        return new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.generate(IDS));
    }

    /** One consumption in a transaction of its own, under an actor and a correlation, committed. */
    private static Consumption consumeAndCommit(
            CreditDecisionConsumptions<Connection> port, CreditDecisionId decision, ConsumerRef consumer)
            throws SQLException {
        try (Connection connection = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(customer());
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            connection.setAutoCommit(false);
            try {
                Consumption answer = port.consume(connection, decision, consumer);
                connection.commit();
                return answer;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    /** One consumption over {@code path} in a transaction of its own, committed - its answer as the function names it. */
    private String consumeOver(Path path, CreditDecisionId decision, ConsumerRef consumer) throws SQLException {
        try (Connection connection = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enter(customer());
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            connection.setAutoCommit(false);
            String answer = consumeOn(connection, path, decision, consumer);
            connection.commit();
            return answer;
        }
    }

    private String consumeOn(Connection connection, Path path, CreditDecisionId decision, ConsumerRef consumer)
            throws SQLException {
        if (path == Path.FUNCTION) {
            return function(connection, decision, consumer);
        }
        return switch (port.consume(connection, decision, consumer)) {
            case Consumption.Consumed consumed -> consumed.replayed() ? "REPLAYED" : "CONSUMED";
            case Consumption.Lapsed ignored -> "LAPSED";
            case Consumption.NotApproved ignored -> "NOT_APPROVED";
            case Consumption.AlreadyConsumed ignored -> "ALREADY_CONSUMED";
            case Consumption.NotFound ignored -> "NOT_FOUND";
        };
    }

    /** {@code credit.consume_decision} called directly by the application role, with no lock taken first. */
    private static String callFunction(CreditDecisionId decision, ConsumerRef consumer) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return function(app, decision, consumer);
        }
    }

    private static String function(Connection connection, CreditDecisionId decision, ConsumerRef consumer)
            throws SQLException {
        try (PreparedStatement call = connection.prepareStatement(
                "SELECT answer FROM credit.consume_decision(?, ?, ?, ?)")) {
            call.setObject(1, decision.value());
            call.setObject(2, IDS.next());
            call.setString(3, consumer.kind().name());
            call.setObject(4, consumer.ref());
            try (ResultSet row = call.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    /**
     * {@code work}, every item on its own connection, started while another transaction holds the party's profile row,
     * which is released only once the database shows every one of them waiting on it - directly or behind another
     * waiter (PostgreSQL's tuple-lock queue). Without the observation a "race" may be ten calls one after another.
     */
    private static <T> List<T> whileProfileHeld(UUID party, List<Callable<T>> work) throws Exception {
        ExecutorService instances = Executors.newFixedThreadPool(work.size());
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (PreparedStatement hold = holder.prepareStatement(
                    "SELECT pg_backend_pid() FROM credit.credit_profile WHERE party_id = ? FOR UPDATE")) {
                hold.setObject(1, party);
                try (ResultSet row = hold.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    holderPid = row.getInt(1);
                }
            }
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> each : work) {
                pending.add(instances.submit(each));
            }
            awaitDatabase("SELECT count(*) = ? FROM pg_stat_activity w WHERE ? = ANY (pg_blocking_pids(w.pid))"
                    + " OR EXISTS (SELECT 1 FROM pg_stat_activity f WHERE f.pid = ANY (pg_blocking_pids(w.pid))"
                    + " AND ? = ANY (pg_blocking_pids(f.pid)))", work.size(), holderPid, holderPid);
            holder.rollback();
            List<T> answers = new ArrayList<>();
            for (Future<T> each : pending) {
                answers.add(each.get(2, TimeUnit.MINUTES));
            }
            return answers;
        } finally {
            instances.shutdownNow();
        }
    }

    /**
     * The statements of the backends {@code holderPid} blocks, read on the application role (whose sessions' text it may
     * see) once there is one; {@code running} finishing first is the failure - it never waited on the holder.
     */
    private static List<String> waitingOn(int holderPid, Future<?> running) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application(); PreparedStatement read = observer.prepareStatement(
                "SELECT query FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))")) {
            read.setInt(1, holderPid);
            while (true) {
                List<String> queries = new ArrayList<>();
                try (ResultSet row = read.executeQuery()) {
                    while (row.next()) {
                        queries.add(row.getString(1));
                    }
                }
                if (!queries.isEmpty()) {
                    return queries;
                }
                if (running.isDone()) {
                    throw new AssertionError("finished without ever waiting on the held profile; it answered "
                            + running.get());
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("neither waited on the held profile nor finished");
                }
                Thread.sleep(20);
            }
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    /** An approval of 5,000.00 for {@code party}, valid for {@code validity} from its insert on the database clock. */
    private static CreditDecisionId approved(UUID party, Duration validity) {
        return decided(party, DecisionOutcome.APPROVED, validity);
    }

    /** A decision as the deciding transaction's store records it, on a request of its own, then that request closed. */
    private static CreditDecisionId decided(UUID party, DecisionOutcome outcome, Duration validity) {
        return inOneTransaction(uow -> {
            DecisionRequestId requestId = DecisionRequestId.of(DecisionRequestRows.submitted(uow, party,
                    CreditProduct.PERSONAL_LOAN));
            DecisionRequest request = REQUESTS.ownedBy(uow, requestId, party).orElseThrow();
            SnapshotContent content = new SnapshotContent(requestId.value(), party, CreditProduct.PERSONAL_LOAN,
                    request.application().requested(), Optional.of(36),
                    new PinnedVersions(CreditPolicyV1.PERSONAL_LOAN_ID.value(), MODEL, 1), CanonicalSnapshotTest.attributes());
            String canonical = CanonicalSnapshot.render(content);
            JdbcDecisionSnapshotStore snapshots = new JdbcDecisionSnapshotStore();
            snapshots.insertSnapshot(uow, DecisionSnapshotId.next(IDS), requestId.value(), 1, CanonicalSnapshot.FORMAT,
                    canonical, CanonicalSnapshot.sha256(canonical), content.versions());
            DecisionSnapshotStore.StoredSnapshot stored = snapshots.snapshotOf(uow, requestId.value(), 1).orElseThrow();
            DecisionSnapshot snapshot = new DecisionSnapshot(stored.id(), 1, stored.format(), stored.canonical(),
                    stored.sha256(), stored.frozenAt(), CanonicalSnapshot.parse(stored.canonical()));
            CreditDecisionId decision = CreditDecisionId.next(IDS);
            boolean approval = outcome == DecisionOutcome.APPROVED;
            new JdbcCreditDecisions().insert(uow, new JdbcCreditDecisions.NewDecision(decision, request, snapshot, outcome,
                    approval ? Optional.of(APPROVED) : Optional.empty(),
                    List.of(approval ? ReasonCode.AUTO_APPROVAL_CEILING : ReasonCode.SCORE_INSUFFICIENT), validity,
                    "system", "SYSTEM"));
            REQUESTS.transition(uow, requestId, DecisionRequestStatus.CANCELLABLE, DecisionRequestStatus.CANCELLED,
                    Optional.empty(), new Actor(party.toString(), ActorType.CUSTOMER), Optional.empty());
            return decision;
        });
    }

    private Money reservedFor(UUID party) throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            return reserved.reservedFor(connection, party, EUR);
        }
    }

    private static boolean reservesNow(Connection connection, CreditDecisionId decision) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT valid_until > statement_timestamp()"
                + " FROM credit.credit_decision WHERE id = ?")) {
            select.setObject(1, decision.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        }
    }

    private static Optional<ConsumerRef> consumerOf(CreditDecisionId decision) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(
                "SELECT consumer_kind, consumer_ref FROM credit.credit_decision_consumption WHERE decision_id = ?")) {
            select.setObject(1, decision.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(new ConsumerRef(ConsumerKind.valueOf(row.getString(1)),
                        row.getObject(2, UUID.class))) : Optional.empty();
            }
        }
    }

    private static long audits(CreditDecisionId decision) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionConsumed'"
                + " AND target_id = ?", decision.value().toString());
    }

    private static long count(String sql, Object... parameters) throws SQLException {
        return Long.parseLong(scalar(sql, parameters));
    }

    private static String scalar(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    /** Polls {@code sql} - a boolean - on the migrator until it is true, at most thirty seconds. */
    private static void awaitDatabase(String sql, Object... parameters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!"t".equals(scalar(sql, parameters))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never true: " + sql);
            }
            Thread.sleep(20);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatExceptionOfType(SQLException.class).as(sql).isThrownBy(() -> execute(connection, sql))
                .matches(e -> sqlState.equals(e.getSQLState()), "SQLState " + sqlState);
    }
}
