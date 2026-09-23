package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.checkout.JdbcCheckoutSessionStore;
import com.finapp.checkout.JdbcOrderStore;
import com.finapp.checkout.Order;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.util.Base64;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The checkout session and order against the real schema (`P6-TSK-006`, ADR-0053) — the
 * three-layer discipline's outer two ranks: what the <em>database</em> refuses, and what it
 * refuses <strong>for every writer</strong>.
 *
 * <p>Every trigger probe runs as the <strong>migrator</strong>, deliberately: the application
 * role's narrowed grant already refuses most of these, so only the writer the grants cannot
 * bind proves the trigger's own claim (`P6-TSK-003`'s recorded lesson).
 *
 * <p><strong>This suite creates no merchant, no fee schedule and no payment.</strong> All four
 * of the session's outward references travel by value (ADR-0029), so the checkout schema stands
 * up against arbitrary UUIDs — which is the module isolation showing up as a property of the
 * test rather than as a claim in a document.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the checkout session and order (P6-TSK-006)")
class CheckoutSessionDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(100_00L, EUR);
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final JdbcCheckoutSessionStore sessions = new JdbcCheckoutSessionStore();
    private final JdbcOrderStore orders = new JdbcOrderStore();

    // ----------------------------------------------------------------- round trip

    @Test
    @DisplayName("an offer round-trips exactly: amount, summary, the price pin and both refs")
    void anOfferRoundTrips() throws Exception {
        CheckoutSessionToken token = CheckoutSessionToken.issue(RANDOMNESS);
        CheckoutSession opened = open(token);
        runAsPlatform(uow -> sessions.insert(uow, opened));

        CheckoutSession read =
                asPlatform(uow -> sessions.findById(uow, opened.id()).orElseThrow());
        assertThat(read.merchantRef()).isEqualTo(opened.merchantRef());
        assertThat(read.amount()).isEqualTo(AMOUNT);
        assertThat(read.lineSummary()).isEqualTo("Two coffees and a pastry");
        assertThat(read.feeScheduleVersionRef()).isEqualTo(opened.feeScheduleVersionRef());
        assertThat(read.status()).isEqualTo(CheckoutSessionStatus.OPEN);
        assertThat(read.paymentIntentRef()).isEmpty();
        // WITHIN one microsecond, which is timestamptz's own resolution - and Postgres ROUNDS
        // to it rather than truncating, so a truncating assertion is wrong in the half of
        // cases that round up. The tolerance is stated rather than smoothed: it is exactly one
        // microsecond, so a real millisecond-scale drift would still fail. Immaterial to the
        // deadline itself, which the sweeper compares against the database's own clock.
        assertThat(read.expiresAt())
                .isCloseTo(
                        opened.expiresAt(),
                        org.assertj.core.api.Assertions.within(
                                1L, java.time.temporal.ChronoUnit.MICROS));
        assertThat(read.authenticates(token)).isTrue();
    }

    @Test
    @DisplayName("a token resolves its own session and nothing else - looked up by HASH")
    void aTokenResolvesItsOwnSession() throws Exception {
        CheckoutSessionToken mine = CheckoutSessionToken.issue(RANDOMNESS);
        CheckoutSession session = open(mine);
        runAsPlatform(uow -> sessions.insert(uow, session));

        var found = asPlatform(uow -> sessions.findByToken(uow, mine));
        assertThat(found.map(CheckoutSession::id)).contains(session.id());

        var anothers =
                asPlatform(
                        uow -> sessions.findByToken(uow, CheckoutSessionToken.issue(RANDOMNESS)));
        assertThat(anothers)
                .as("unknown, malformed and somebody-else's are one empty answer")
                .isEmpty();

        var guessed = asPlatform(uow -> sessions.findByToken(uow, CheckoutSessionToken.of("guess")));
        assertThat(guessed).isEmpty();
    }

    @Test
    @DisplayName("THE TOKEN IS NEVER STORED IN CLEAR - swept across every text column in every"
            + " schema (INV-IDN-01)")
    void noColumnHoldsTheToken() throws Exception {
        // THE NEEDLE IS RECONSTRUCTED FROM THE RANDOMNESS THE TEST SUPPLIED, not read off the
        // token: CheckoutSessionToken.plaintext() is package-private on purpose, and widening
        // it so a test could look would be the test weakening the property it checks. A
        // randomness source with known bytes gives the same plaintext issue() produced.
        byte[] known = new byte[CheckoutSessionToken.ENTROPY_BYTES];
        for (int i = 0; i < known.length; i++) {
            known[i] = (byte) (i * 7 + 3);
        }
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(known);

        CheckoutSessionToken token = CheckoutSessionToken.issue(fixedRandomness(known));
        assertThat(token.matches(token.hash().expose()))
                .as("the needle really is this token's value")
                .isTrue();
        CheckoutSession session = open(token);
        runAsPlatform(uow -> sessions.insert(uow, session));

        // Swept from information_schema rather than from a list somebody maintains, so it
        // covers the sink nobody thought of as well as the ones they did (P6-TSK-002's probe).
        assertThat(columnsHolding(plaintext))
                .as("a checkout token in any column is one stranger's purchase, takeable")
                .isEmpty();
        assertThat(columnsHolding(token.hash().expose()))
                .as("the HASH is stored, in exactly one place")
                .containsExactly("checkout.checkout_session.token_hash");
    }

    // ----------------------------------------------------------------- the machine

    @Test
    @DisplayName("a conditional transition lands with its history row, and a stale one writes"
            + " NOTHING")
    void transitionsAreConditionalAndRecorded() throws Exception {
        CheckoutSession session = open(CheckoutSessionToken.issue(RANDOMNESS));
        runAsPlatform(uow -> sessions.insert(uow, session));

        UUID intent = IDS.next();
        CheckoutSession confirmed = session.confirm(CLOCK, intent);
        boolean landed = asPlatform(uow -> sessions.transition(uow, session, confirmed));
        assertThat(landed).isTrue();
        CheckoutSession reread = asPlatform(uow -> sessions.findById(uow, session.id()).orElseThrow());
        assertThat(reread.paymentIntentRef()).contains(intent);

        // A writer holding the stale OPEN snapshot now loses: the row has moved on.
        boolean stale = asPlatform(uow -> sessions.transition(uow, session, session.abandon(CLOCK)));
        assertThat(stale)
                .as("the row count refuses a move from a state the row has left")
                .isFalse();
        assertThat(historyCount(session.id()))
                .as("and wrote no history for a transition that never happened")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("INV-MER-06 AT THE DATABASE: EXPIRED -> COMPLETED_LATE lands")
    void landedMoneyHasADestination() throws Exception {
        CheckoutSession session = open(CheckoutSessionToken.issue(RANDOMNESS));
        runAsPlatform(uow -> sessions.insert(uow, session));

        CheckoutSession pending = session.confirm(CLOCK, IDS.next());
        runAsPlatform(uow -> sessions.transition(uow, session, pending));
        CheckoutSession expired = pending.expire(CLOCK);
        runAsPlatform(uow -> sessions.transition(uow, pending, expired));

        // The capture landed after the clock ran out. Without this edge the trigger would
        // refuse the transition INSIDE the capture's own transaction - after the money moved.
        boolean late = asPlatform(uow -> sessions.transition(uow, expired, expired.completeLate(CLOCK)));
        assertThat(late).isTrue();
        CheckoutSession after = asPlatform(uow -> sessions.findById(uow, session.id()).orElseThrow());
        assertThat(after.status()).isEqualTo(CheckoutSessionStatus.COMPLETED_LATE);
    }

    @Test
    @DisplayName("EVERY illegal edge is refused BY THE TRIGGER, for the MIGRATOR - swept from"
            + " the machine's own cross-product")
    void everyIllegalEdgeIsRefusedByTheTrigger() throws Exception {
        for (CheckoutSessionStatus from : CheckoutSessionStatus.values()) {
            CheckoutSession session = inState(from);
            for (CheckoutSessionStatus to : CheckoutSessionStatus.values()) {
                if (from == to || from.canTransitionTo(to)) {
                    continue;
                }
                try (Connection migrator = DatabaseRoles.migrator()) {
                    assertThatThrownBy(
                                    () ->
                                            execute(
                                                    migrator,
                                                    "UPDATE checkout.checkout_session SET"
                                                            + " status = ?, status_changed_at ="
                                                            + " now() WHERE id = ?",
                                                    to.name(),
                                                    session.id().value()))
                            .as("%s -> %s must be refused for every writer", from, to)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("machine");
                }
            }
        }
    }

    @Test
    @DisplayName("the offer is FROZEN for every writer: the amount, the price pin and the token")
    void theOfferIsFrozenForEveryWriter() throws Exception {
        CheckoutSession session = open(CheckoutSessionToken.issue(RANDOMNESS));
        runAsPlatform(uow -> sessions.insert(uow, session));

        try (Connection migrator = DatabaseRoles.migrator()) {
            for (String[] change :
                    new String[][] {
                        {"amount_minor = 1", "the amount"},
                        {"fee_schedule_version_ref = gen_random_uuid()", "the price pin"},
                        {"line_summary = 'something else'", "what was bought"},
                        {"expires_at = now() + interval '1 year'", "the deadline"}
                    }) {
                assertThatThrownBy(
                                () ->
                                        execute(
                                                migrator,
                                                "UPDATE checkout.checkout_session SET "
                                                        + change[0] + " WHERE id = ?",
                                                session.id().value()))
                        .as("%s is what it was when the offer was made", change[1])
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("frozen");
            }
        }
    }

    @Test
    @DisplayName("ONE PAYMENT INTENT PER SESSION: the reference is SET ONCE, at the trigger")
    void thePaymentIntentReferenceIsSetOnce() throws Exception {
        CheckoutSession session = open(CheckoutSessionToken.issue(RANDOMNESS));
        runAsPlatform(uow -> sessions.insert(uow, session));
        CheckoutSession confirmed = session.confirm(CLOCK, IDS.next());
        runAsPlatform(uow -> sessions.transition(uow, session, confirmed));

        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE checkout.checkout_session SET"
                                                    + " payment_intent_ref = ? WHERE id = ?",
                                            IDS.next(),
                                            session.id().value()))
                    .as("a second value would mean the session quietly started a second payment")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("set once");
        }
    }

    @Test
    @DisplayName("A GATE FINDING, CLOSED: a transition carrying a DIFFERENT intent than the row"
            + " holds is REFUSED, not silently discarded")
    void aConflictingIntentIsRefusedRatherThanDiscarded() throws Exception {
        // FOUND BY THIS TASK'S COMPLETION GATE, and no probe made it: the write uses COALESCE
        // so that a transition which attaches no intent cannot blank one that is there. That
        // is right - but it also meant a caller carrying a DIFFERENT intent had its value
        // quietly dropped while the update reported success, because the column never changed
        // and the trigger therefore never fired. The set-once rule was loud at the trigger and
        // SILENT in the store, which is the worse of the two places to be quiet.
        //
        // The aggregate makes the case unreachable (withIntent throws), so this drives the
        // store directly - which is exactly the caller P6-TSK-007 is about to write.
        CheckoutSession session = open(CheckoutSessionToken.issue(RANDOMNESS));
        runAsPlatform(uow -> sessions.insert(uow, session));
        UUID theRealIntent = IDS.next();
        CheckoutSession pending = session.confirm(CLOCK, theRealIntent);
        runAsPlatform(uow -> sessions.transition(uow, session, pending));

        CheckoutSession sameStateOtherIntent =
                CheckoutSession.rehydrate(
                        session.id(),
                        session.merchantRef(),
                        session.amount(),
                        session.lineSummary(),
                        session.feeScheduleVersionRef(),
                        session.tokenHash(),
                        session.algorithm(),
                        Optional.of(IDS.next()),
                        CheckoutSessionStatus.COMPLETED,
                        session.expiresAt(),
                        session.createdAt(),
                        Instant.now(CLOCK));

        boolean landed =
                asPlatform(uow -> sessions.transition(uow, pending, sameStateOtherIntent));
        assertThat(landed)
                .as("refused, rather than succeeding while dropping the value")
                .isFalse();

        CheckoutSession after =
                asPlatform(uow -> sessions.findById(uow, session.id()).orElseThrow());
        assertThat(after.paymentIntentRef())
                .as("the one intent this session opened, unchanged")
                .contains(theRealIntent);
        assertThat(after.status())
                .as("and the refused write moved nothing at all")
                .isEqualTo(CheckoutSessionStatus.PAYMENT_PENDING);
        assertThat(historyCount(session.id()))
                .as("no history for a transition that never happened")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ONE TOKEN, ONE SESSION, ACROSS THE PLATFORM - the unique index, driven")
    void aTokenCannotOpenTwoSessions() throws Exception {
        // ADDED BY THE COMPLETION GATE: the claim was asserted against the migration's TEXT and
        // never against the database. Two sessions cannot share a token through the aggregate -
        // tokens are 32 random bytes - so the only way to drive it is raw SQL, which is also
        // the only way the defect this index guards against would arrive: a fixed seed, a
        // reused value, a test double reaching production.
        CheckoutSessionToken token = CheckoutSessionToken.issue(RANDOMNESS);
        CheckoutSession first = open(token);
        runAsPlatform(uow -> sessions.insert(uow, first));

        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "INSERT INTO checkout.checkout_session (id,"
                                                + " merchant_ref, amount_minor, amount_currency,"
                                                + " amount_scale, line_summary,"
                                                + " fee_schedule_version_ref, token_hash,"
                                                + " algorithm, status, expires_at, created_at,"
                                                + " status_changed_at) SELECT ?, merchant_ref,"
                                                + " amount_minor, amount_currency, amount_scale,"
                                                + " line_summary, fee_schedule_version_ref,"
                                                + " token_hash, algorithm, status, expires_at,"
                                                + " created_at, status_changed_at FROM"
                                                + " checkout.checkout_session WHERE id = ?",
                                            IDS.next(),
                                            first.id().value()))
                    .as("two doors with one key would let a holder act on a session not theirs")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("checkout_session_one_row_per_token");
        }
    }

    // ----------------------------------------------------------------- the order

    @Test
    @DisplayName("an order is born from its session and carries the entry that paid for it")
    void anOrderCarriesItsCapturedEntry() throws Exception {
        CheckoutSession completed = completedSession();
        UUID entry = IDS.next();
        Order order = asPlatform(uow -> {
            Order fact = Order.paid(IDS, CLOCK, completed, entry);
            orders.insert(uow, fact);
            return fact;
        });

        Order read = asPlatform(uow -> orders.findBySession(uow, completed.id()).orElseThrow());
        assertThat(read.id()).isEqualTo(order.id());
        assertThat(read.merchantRef()).isEqualTo(completed.merchantRef());
        assertThat(read.amount()).isEqualTo(AMOUNT);
        assertThat(read.capturedEntryRef())
                .as("order -> entry -> ADR-0050 §3's four lines: the traceable chain")
                .isEqualTo(entry);
    }

    @Test
    @DisplayName("A SESSION THAT DIES UNPAID PRODUCES NO ORDER - the platform manufactures no"
            + " commercial facts out of silence")
    void anUnpaidSessionProducesNoOrder() throws Exception {
        CheckoutSession session = open(CheckoutSessionToken.issue(RANDOMNESS));
        runAsPlatform(uow -> sessions.insert(uow, session));
        runAsPlatform(uow -> sessions.transition(uow, session, session.expire(CLOCK)));

        var order = asPlatform(uow -> orders.findBySession(uow, session.id()));
        assertThat(order).as("an abandoned checkout is not a cancelled order").isEmpty();
    }

    @Test
    @DisplayName("TEN INSTANCES completing one session produce ONE order - the unique index,"
            + " not a read-then-write")
    void tenConcurrentOrdersProduceOne() throws Exception {
        CheckoutSession completed = completedSession();
        int racers = 10;

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        runAsPlatform(
                                                uow ->
                                                        orders.insert(
                                                                uow,
                                                                Order.paid(
                                                                        IDS, CLOCK, completed,
                                                                        IDS.next())));
                                        return true;
                                    } catch (RuntimeException refused) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int landed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    landed++;
                }
            }
            assertThat(landed).as("exactly one commercial fact from one purchase").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(orderCount(completed.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("AN ORDER IS APPEND-ONLY: the app role has no grant, the MIGRATOR meets the"
            + " trigger")
    void anOrderCannotBeChanged() throws Exception {
        CheckoutSession completed = completedSession();
        Order order =
                asPlatform(uow -> {
                    Order fact = Order.paid(IDS, CLOCK, completed, IDS.next());
                    orders.insert(uow, fact);
                    return fact;
                });

        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE checkout.checkout_order SET amount_minor = 1"
                                                    + " WHERE id = ?",
                                            order.id().value()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE checkout.checkout_order SET amount_minor = 1"
                                                    + " WHERE id = ?",
                                            order.id().value()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("a fact");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "DELETE FROM checkout.checkout_order WHERE id = ?",
                                            order.id().value()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("a fact");
        }
    }

    // ----------------------------------------------------------------- fixtures

    private static CheckoutSession open(CheckoutSessionToken token) {
        // Arbitrary UUIDs for all three outward references: they travel BY VALUE (ADR-0029),
        // so this schema stands up with no merchant, no fee schedule and no payment anywhere.
        return CheckoutSession.open(
                IDS,
                CLOCK,
                IDS.next(),
                AMOUNT,
                "Two coffees and a pastry",
                IDS.next(),
                token,
                Instant.now(CLOCK).plus(Duration.ofMinutes(30)));
    }

    /** A session parked in {@code status}, moved there along real edges where they exist. */
    private CheckoutSession inState(CheckoutSessionStatus status) throws Exception {
        CheckoutSession session = open(CheckoutSessionToken.issue(RANDOMNESS));
        runAsPlatform(uow -> sessions.insert(uow, session));
        CheckoutSession current = session;
        for (CheckoutSessionStatus step : pathTo(status)) {
            CheckoutSession moved = move(current, step);
            CheckoutSession before = current;
            runAsPlatform(uow -> sessions.transition(uow, before, moved));
            current = moved;
        }
        return current;
    }

    private static List<CheckoutSessionStatus> pathTo(CheckoutSessionStatus target) {
        return switch (target) {
            case OPEN -> List.of();
            case PAYMENT_PENDING -> List.of(CheckoutSessionStatus.PAYMENT_PENDING);
            case COMPLETED ->
                    List.of(CheckoutSessionStatus.PAYMENT_PENDING, CheckoutSessionStatus.COMPLETED);
            case EXPIRED -> List.of(CheckoutSessionStatus.EXPIRED);
            case COMPLETED_LATE ->
                    List.of(CheckoutSessionStatus.EXPIRED, CheckoutSessionStatus.COMPLETED_LATE);
            case ABANDONED -> List.of(CheckoutSessionStatus.ABANDONED);
        };
    }

    private static CheckoutSession move(CheckoutSession session, CheckoutSessionStatus to) {
        return switch (to) {
            case PAYMENT_PENDING -> session.confirm(CLOCK, IDS.next());
            case COMPLETED -> session.complete(CLOCK);
            case COMPLETED_LATE -> session.completeLate(CLOCK);
            case EXPIRED -> session.expire(CLOCK);
            case ABANDONED -> session.abandon(CLOCK);
            case OPEN -> throw new IllegalStateException("birth is the only door");
        };
    }

    private CheckoutSession completedSession() throws Exception {
        return inState(CheckoutSessionStatus.COMPLETED);
    }

    // -----------------------------------------------------------------

    /** For work whose body is a void call — a lambda cannot be a {@code Function} then. */
    private void runAsPlatform(java.util.function.Consumer<Connection> work) throws Exception {
        asPlatform(
                unitOfWork -> {
                    work.accept(unitOfWork);
                    return null;
                });
    }

    private <R> R asPlatform(Function<Connection, R> work) throws Exception {
        try (SecurityContext.Scope platform =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                Connection unitOfWork = DatabaseRoles.application()) {
            unitOfWork.setAutoCommit(false);
            try {
                R result = work.apply(unitOfWork);
                unitOfWork.commit();
                return result;
            } catch (RuntimeException failure) {
                unitOfWork.rollback();
                throw failure;
            }
        }
    }

    private static long historyCount(CheckoutSessionId session) throws SQLException {
        return count(
                "SELECT count(*) FROM checkout.checkout_session_event WHERE session_id = ?",
                session.value());
    }

    private static long orderCount(CheckoutSessionId session) throws SQLException {
        return count(
                "SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?",
                session.value());
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet rows = read.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * Every text column in every application schema holding {@code value} — derived from
     * {@code information_schema} rather than from a list somebody maintains, so it covers the
     * sink nobody thought of ({@code P6-TSK-002}'s probe, reused).
     */
    private static List<String> columnsHolding(String value) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Connection app = DatabaseRoles.application()) {
            List<String[]> columns = new ArrayList<>();
            try (PreparedStatement read =
                            app.prepareStatement(
                                    "SELECT table_schema, table_name, column_name, data_type"
                                            + " FROM information_schema.columns WHERE data_type"
                                            + " IN ('text', 'character varying', 'bytea') AND"
                                            + " table_schema NOT IN ('pg_catalog',"
                                            + " 'information_schema')");
                    ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    columns.add(
                            new String[] {
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4)
                            });
                }
            }
            for (String[] column : columns) {
                String qualified = "\"" + column[0] + "\".\"" + column[1] + "\"";
                // A BYTEA COLUMN NEEDS A DIFFERENT CAST, and this is the P6-TSK-007 gate's
                // finding: `bytea::text` renders `\x7365...`, so a `LIKE` over it can never
                // match a printable needle. The one column on this platform that a credential
                // most plausibly leaks into -- platform.idempotency_record.response_body, the
                // stored response of a keyed command -- is exactly that type, so the sweep was
                // blind in precisely the place it most needed to see. `encode(col, 'escape')`
                // renders printable ASCII as itself and, unlike convert_from, never throws on
                // bytes that are not valid UTF-8.
                String readable =
                        "bytea".equals(column[3])
                                ? "encode(\"" + column[2] + "\", 'escape')"
                                : "\"" + column[2] + "\"::text";
                try (PreparedStatement probe =
                        app.prepareStatement(
                                "SELECT count(*) FROM " + qualified + " WHERE " + readable
                                        + " = ?")) {
                    probe.setString(1, value);
                    try (ResultSet row = probe.executeQuery()) {
                        if (row.next() && row.getLong(1) > 0) {
                            found.add(column[0] + "." + column[1] + "." + column[2]);
                        }
                    }
                } catch (SQLException unreadable) {
                    // A column this role cannot read cannot be holding our token for us.
                }
            }
        }
        return found;
    }

    /** A randomness source that hands out exactly {@code bytes} — the needle's provenance. */
    private static SecureRandom fixedRandomness(byte[] bytes) {
        return new SecureRandom() {
            @java.io.Serial private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] target) {
                System.arraycopy(bytes, 0, target, 0, target.length);
            }
        };
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

}
