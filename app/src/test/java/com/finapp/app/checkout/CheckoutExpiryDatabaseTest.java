package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.checkout.CheckoutExpirySweeper;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.checkout.CheckoutTransactionRunner;
import com.finapp.checkout.JdbcCheckoutSessionStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * The expiry sweeper against the real schema (`P6-TSK-008`, ADR-0053 §4, {@code INV-MER-06}) —
 * the producer that makes {@code EXPIRED} a state rather than a filter.
 *
 * <p><strong>This suite creates no merchant, no fee schedule and no payment.</strong> Every one
 * of the session's outward references travels by value (ADR-0029), so the sweeper's own
 * properties can be proved against arbitrary UUIDs. The race that needs real money — a capture
 * landing on an expired offer, the merchant credited, the order born — is
 * {@code CheckoutFlowDatabaseTest}'s, because it needs the whole flow to be real.
 *
 * <p>The clock is <strong>injected and moved</strong>, never slept against: a test that waits
 * thirty minutes is a test nobody runs.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the checkout expiry sweeper (P6-TSK-008)")
class CheckoutExpiryDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Money AMOUNT = Money.ofMinorUnits(100_00L, CurrencyCode.of("EUR"));
    private static final Duration OFFER_WINDOW = Duration.ofMinutes(30);

    private final CheckoutSessionStore<Connection> sessions = new JdbcCheckoutSessionStore();

    // ----------------------------------------------------------------- the two edges

    @Test
    @DisplayName("an OPEN session past its deadline becomes EXPIRED - the state a filter"
            + " cannot be: audited, in history, and announced")
    void anOverdueOpenSessionExpires() throws Exception {
        Clock late = pastTheDeadline();
        drain(late);
        CheckoutSession session = insertOpen();

        CheckoutExpirySweeper.SweepResult result = sweeperAt(late).sweep();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(statusOf(session.id())).isEqualTo("EXPIRED");
        assertThat(historyRows(session.id(), "OPEN", "EXPIRED"))
                .as("append-only history, beside every other transition")
                .isEqualTo(1);
        assertThat(auditRecords(session.id(), "checkout.CheckoutSessionExpired"))
                .as("the regulatory trail, with the state it expired FROM")
                .isEqualTo(1);
        assertThat(outboxEvents(session.id(), "checkout.CheckoutSessionExpired")).isEqualTo(1);
        assertThat(auditReason(session.id()))
                .as("no reason: there is nobody to ask, and the actor is the platform")
                .isNull();
    }

    @Test
    @DisplayName("a PAYMENT_PENDING session expires too - THE ONLY TERMINAL ESCAPE for an"
            + " offer whose payment failed, because checkout has no failure state")
    void anOverduePendingSessionExpires() throws Exception {
        // The stuck row, exactly as production makes it: the customer confirmed, the payment
        // declined, and they closed the tab. ADR-0053 gave checkout no failure state on
        // purpose - a declined payment is the PAYMENT's state - so nothing else can ever end
        // this row, and without this edge it is stuck for ever (ADR-0044's doctrine).
        CheckoutSession session = insertOpen();
        CheckoutSession pending = session.confirm(CLOCK, IDS.next());
        runAsPlatform(uow -> sessions.transition(uow, session, pending));

        // Grace ZERO, so the only thing being tested is that the state is swept at all.
        sweeperAt(pastTheDeadline(), Duration.ZERO).sweep();
        // The tally is not asserted here - the STATE is. Counting is the next test's subject.

        assertThat(statusOf(session.id())).isEqualTo("EXPIRED");
        assertThat(historyRows(session.id(), "PAYMENT_PENDING", "EXPIRED")).isEqualTo(1);
        assertThat(auditDetail(session.id()))
                .as("OPEN and PAYMENT_PENDING are different operational facts")
                .contains("expiredFrom=PAYMENT_PENDING");
    }

    @Test
    @DisplayName("the GRACE holds: a PAYMENT_PENDING session inside it is left alone while an"
            + " OPEN one beside it expires - a provider answering slowly is not a dead offer")
    void theGraceProtectsAPaymentInFlight() throws Exception {
        CheckoutSession stillPaying = insertOpen();
        CheckoutSession pending = stillPaying.confirm(CLOCK, IDS.next());
        runAsPlatform(uow -> sessions.transition(uow, stillPaying, pending));
        CheckoutSession nobodyPaid = insertOpen();

        // One minute past the deadline, with ten minutes of grace: the OPEN offer is dead and
        // the one with a payment in flight is not. Expiring the second would be HARMLESS - the
        // EXPIRED -> COMPLETED_LATE edge catches the capture - but it would make the honest
        // exception the ordinary case, which is the whole point of the margin.
        sweeperAt(
                        Clock.offset(CLOCK, OFFER_WINDOW.plusMinutes(1)),
                        Duration.ofMinutes(10))
                .sweep();

        assertThat(statusOf(nobodyPaid.id())).isEqualTo("EXPIRED");
        assertThat(statusOf(stillPaying.id())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("a session that is NOT yet overdue is left alone - and the clock is re-judged"
            + " under the lock, never trusted from the candidate list")
    void aLiveOfferIsNotSwept() throws Exception {
        CheckoutSession session = insertOpen();

        // At the REAL clock, nothing anywhere in the schema is overdue by this offer's window,
        // so this tick's zero is about every live offer rather than only this one.
        CheckoutExpirySweeper.SweepResult result = sweeperAt(CLOCK).sweep();

        assertThat(result.expired()).isZero();
        assertThat(statusOf(session.id())).isEqualTo("OPEN");
        assertThat(historyRows(session.id(), "OPEN", "EXPIRED")).isZero();
    }

    // ----------------------------------------------------------------- the races

    @Test
    @DisplayName("TEN SWEEPERS on one overdue session produce ONE expiry - one history row,"
            + " one audit record, one event, and nine honest skips")
    void tenSweepersProduceOneExpiry() throws Exception {
        Clock late = pastTheDeadline();
        drain(late);
        CheckoutSession session = insertOpen();

        int racers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CheckoutExpirySweeper.SweepResult>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return sweeperAt(late).sweep();
                                }));
            }
            start.countDown();
            int expired = 0;
            for (Future<CheckoutExpirySweeper.SweepResult> result : results) {
                CheckoutExpirySweeper.SweepResult tick = result.get(120, TimeUnit.SECONDS);
                assertThat(tick.failedRows()).as("no racer errors; the losers converge").isZero();
                expired += tick.expired();
            }
            // THE COUNT THAT MATTERS: the meter reads this number, so a sweeper that reported
            // an expiry it did not commit would inflate the fleet's throughput by its own
            // replica count (P5-TSK-017's rule at a second vocabulary).
            assertThat(expired)
                    .as("exactly one tick's own conditional transition fired")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(session.id())).isEqualTo("EXPIRED");
        assertThat(historyRows(session.id(), "OPEN", "EXPIRED")).isEqualTo(1);
        assertThat(auditRecords(session.id(), "checkout.CheckoutSessionExpired")).isEqualTo(1);
        assertThat(outboxEvents(session.id(), "checkout.CheckoutSessionExpired"))
                .as("announced by the winner only, so consumers see one death")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a sweeper racing a CONFIRMATION on one row: exactly one wins, and the loser"
            + " leaves no trace at all")
    void aSweeperRacingAConfirmationHasOneWinner() throws Exception {
        CheckoutSession session = insertOpen();
        Clock late = pastTheDeadline();
        CheckoutSession confirmed = session.confirm(CLOCK, IDS.next());
        // A REAL grace, and the first draft of this test is why. With grace ZERO the sweeper
        // can win the race for OPEN -> EXPIRED *and*, having lost it, still take the
        // PAYMENT_PENDING row the confirmation just produced - two legitimate transitions in
        // one tick, which makes "exactly one writer moved this row" untestable. That is not a
        // flaw in the race; it is the grace earning its place, because a deployment with none
        // would expire a session the instant its payment began past the deadline.
        Duration grace = Duration.ofHours(1);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean took;
        try {
            Future<?> sweep =
                    pool.submit(
                            () -> {
                                start.await();
                                return sweeperAt(late, grace).sweep();
                            });
            Future<Boolean> confirm =
                    pool.submit(
                            () -> {
                                start.await();
                                return asPlatform(
                                        uow -> sessions.transition(uow, session, confirmed));
                            });
            start.countDown();
            sweep.get(120, TimeUnit.SECONDS);
            took = confirm.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // THE CLAIM IS ABOUT THE ROW, not about a tick's tally: the sweeper is unscoped and
        // this suite shares a database, so counting ticks here would be counting other tests.
        // What must hold is that ONE of the two writers moved this session and the other's
        // conditional refused it - which is exactly what one history row beyond birth says.
        //
        // Both outcomes are legitimate and the design says so: an expiry and a confirmation
        // racing on one OPEN row have exactly one winner (INV-CON-02), and if the confirmation
        // wins, the offer is simply alive a little longer - a later tick will expire the
        // PAYMENT_PENDING row past its grace, which is a DIFFERENT transition on a different
        // clock, not this race repeated.
        assertThat(statusOf(session.id())).isIn("EXPIRED", "PAYMENT_PENDING");
        assertThat(took)
                .as("the confirmation's own row count agrees with the state it produced")
                .isEqualTo("PAYMENT_PENDING".equals(statusOf(session.id())));
        assertThat(historyRows(session.id()))
                .as("the loser wrote nothing: no history, and therefore no half-transition")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a session already EXPIRED is a SKIP, not a second expiry - and a terminal"
            + " one is never a candidate at all")
    void alreadyEndedSessionsAreNotSweptAgain() throws Exception {
        Clock late = pastTheDeadline();
        drain(late);
        CheckoutSession session = insertOpen();
        sweeperAt(late).sweep();
        assertThat(statusOf(session.id())).isEqualTo("EXPIRED");

        CheckoutExpirySweeper.SweepResult again = sweeperAt(late).sweep();

        assertThat(again.expired())
                .as("EXPIRED is not in the candidate query at all, so it is not even a skip")
                .isZero();
        assertThat(historyRows(session.id(), "OPEN", "EXPIRED")).isEqualTo(1);
        assertThat(auditRecords(session.id(), "checkout.CheckoutSessionExpired")).isEqualTo(1);
    }

    @Test
    @DisplayName("the batch bounds the tick - a sweeper never takes more rows than it was told"
            + " to, so a backlog drains over ticks rather than in one transaction storm")
    void theBatchBoundsTheTick() throws Exception {
        Clock late = pastTheDeadline();
        drain(late);
        for (int i = 0; i < 4; i++) {
            insertOpen();
        }

        CheckoutExpirySweeper.SweepResult tick = sweeper(late, Duration.ZERO, 2).sweep();

        assertThat(tick.candidates()).isEqualTo(2);
        assertThat(tick.expired()).isEqualTo(2);
    }

    // ----------------------------------------------------------------- fixtures

    /**
     * Expires everything already overdue at {@code clock}, so the next tick's COUNT is about
     * this test's own row.
     *
     * <p>The sweeper is deliberately unscoped — it is the platform's, not a tenant's — so a
     * suite sharing a database with every other checkout test sees their leftovers as
     * candidates. Draining first is the honest way to assert on a tick's tally; scoping the
     * query to make a test easier would be testing a sweeper the platform does not run.
     */
    private void drain(Clock clock) {
        // Twice: the first pass may itself leave rows another suite committed mid-pass.
        sweeper(clock, Duration.ZERO, 500).sweep();
        sweeper(clock, Duration.ZERO, 500).sweep();
    }

    private CheckoutSession insertOpen() throws Exception {
        // Arbitrary UUIDs for every outward reference: they travel BY VALUE (ADR-0029), so the
        // sweeper's properties hold with no merchant, no fee schedule and no payment anywhere.
        CheckoutSession session =
                CheckoutSession.open(
                        IDS,
                        CLOCK,
                        IDS.next(),
                        AMOUNT,
                        "Two coffees and a pastry",
                        IDS.next(),
                        CheckoutSessionToken.issue(RANDOMNESS),
                        Instant.now(CLOCK).plus(OFFER_WINDOW));
        runAsPlatform(uow -> sessions.insert(uow, session));
        return session;
    }

    /** A clock a minute past the offer window, so an offer opened now is already dead. */
    private static Clock pastTheDeadline() {
        return Clock.offset(CLOCK, OFFER_WINDOW.plusMinutes(1));
    }

    private CheckoutExpirySweeper sweeperAt(Clock clock) {
        return sweeper(clock, Duration.ZERO, 50);
    }

    private CheckoutExpirySweeper sweeperAt(Clock clock, Duration grace) {
        return sweeper(clock, grace, 50);
    }

    private CheckoutExpirySweeper sweeper(Clock clock, Duration grace, int batchSize) {
        return new CheckoutExpirySweeper(
                testRunner(),
                sessions,
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                clock,
                grace,
                batchSize);
    }

    /** One transaction per call, on the application role — the production runner's contract. */
    private static CheckoutTransactionRunner testRunner() {
        return new CheckoutTransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                try (Connection unitOfWork = DatabaseRoles.application()) {
                    unitOfWork.setAutoCommit(false);
                    try {
                        R result = work.apply(unitOfWork);
                        unitOfWork.commit();
                        return result;
                    } catch (RuntimeException failure) {
                        unitOfWork.rollback();
                        throw failure;
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }

    // ----------------------------------------------------------------- reads

    private static String statusOf(CheckoutSessionId id) throws SQLException {
        return one("SELECT status FROM checkout.checkout_session WHERE id = ?", id.value());
    }

    private static String auditDetail(CheckoutSessionId id) throws SQLException {
        return one(
                "SELECT change_summary FROM platform.audit_record WHERE target_id = ?"
                        + " AND operation = 'checkout.CheckoutSessionExpired'",
                id.value().toString());
    }

    private static String auditReason(CheckoutSessionId id) throws SQLException {
        return one(
                "SELECT reason FROM platform.audit_record WHERE target_id = ?"
                        + " AND operation = 'checkout.CheckoutSessionExpired'",
                id.value().toString());
    }

    private static long historyRows(CheckoutSessionId id) throws SQLException {
        return count(
                "SELECT count(*) FROM checkout.checkout_session_event WHERE session_id = ?",
                id.value());
    }

    private static long historyRows(CheckoutSessionId id, String from, String to)
            throws SQLException {
        return count(
                "SELECT count(*) FROM checkout.checkout_session_event WHERE session_id = ?"
                        + " AND from_status = ? AND to_status = ?",
                id.value(),
                from,
                to);
    }

    private static long auditRecords(CheckoutSessionId id, String action) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                        + " AND operation = ?",
                id.value().toString(),
                action);
    }

    private static long outboxEvents(CheckoutSessionId id, String type) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                        + " AND event_type = ?",
                id.value(),
                type);
    }

    private static String one(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    // -----------------------------------------------------------------

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
}
