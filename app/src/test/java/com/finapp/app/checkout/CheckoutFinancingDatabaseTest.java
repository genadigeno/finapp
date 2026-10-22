package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.bnpl.BnplTransactions;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.checkout.CheckoutExpirySweeper;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.checkout.CheckoutTransactionRunner;
import com.finapp.checkout.JdbcCheckoutSessionStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.JournalEntryId;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A checkout session completed by a BNPL purchase, against the real schema and over real HTTP (`P12-TSK-007`;
 * PHASE_12_PLAN.md sections 5.2a, 7.2 B3, 7.3 (c) and 7.4; BNPL_LIFECYCLES.md section 4.4; {@code INV-MER-06},
 * {@code INV-BNPL-03}, {@code INV-LIFE-02}, {@code INV-LIFE-04}).
 *
 * <p>The financing is driven through bnpl's own port ({@link BnplCheckoutSessions}, app's {@code CheckoutBnplSessions})
 * in bnpl's own unit of work ({@link BnplTransactions}) - B3's lock and judgement, then the completion - exactly as
 * {@code P12-TSK-013}'s T-pur will call it. <strong>The financing entry is a by-value reference</strong> until
 * {@code -013} posts F1: the order's {@code captured_entry_ref} travels by value (ADR-0029, no foreign key), so checkout's
 * half is proven with a fresh entry identifier and {@code -013} re-asserts it against the real financing entry. The card
 * and wallet confirmations are the real doors (the simulated PSP, a real top-up), so "one session, completed once - by a
 * payment or by a financing" is proven against the paths production takes.
 *
 * <p><strong>Its own database</strong> ({@code own-container}): the expiry race runs the platform's unscoped sweeper on
 * clocks five seconds either side of the truth, which would expire other suites' offers in a shared one.
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("checkout completed by a BNPL purchase: once, never beside a payment, never after expiry (P12-TSK-007)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class CheckoutFinancingDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final long AMOUNT_MINOR = 100_00L;

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplCheckoutSessions bnplCheckoutSessions;
    @Autowired private BnplTransactions bnplTransactions;

    private final CheckoutSessionStore<Connection> sessions = new JdbcCheckoutSessionStore();

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // ================================================================== the edge

    @Test
    @DisplayName("a financing completes an OPEN session and births ONE order: intent-less, the purchase its financing"
            + " reference, the financing entry its captured_entry_ref, announced and read as FINANCED")
    void financingCompletesAnOpenSessionAndBirthsOneOrder() throws Exception {
        Merchant merchant = tradingMerchant();
        Opened session = openSession(merchant);
        BnplPurchaseId purchase = BnplPurchaseId.next(IDS);
        JournalEntryId entry = JournalEntryId.of(IDS.next());

        BnplCheckoutSessions.Completion completed = finance(session.id(), purchase, entry);

        assertThat(completed).isInstanceOf(BnplCheckoutSessions.Completed.class);
        UUID orderId = ((BnplCheckoutSessions.Completed) completed).orderRef();
        try (Connection app = DatabaseRoles.application()) {
            assertThat(one(app, "SELECT status FROM checkout.checkout_session WHERE id = ?", session.id()))
                    .isEqualTo("COMPLETED");
            assertThat(one(app, "SELECT financed_by_ref::text FROM checkout.checkout_session WHERE id = ?", session.id()))
                    .isEqualTo(purchase.value().toString());
            assertThat(one(app, "SELECT payment_intent_ref::text FROM checkout.checkout_session WHERE id = ?",
                    session.id())).as("intent-less").isNull();
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?", session.id()))
                    .isEqualTo(1);
            assertThat(one(app, "SELECT captured_entry_ref::text FROM checkout.checkout_order WHERE session_ref = ?",
                    session.id())).isEqualTo(entry.value().toString());
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_session_event WHERE session_id = ?"
                    + " AND from_status = 'OPEN' AND to_status = 'COMPLETED'", session.id())).isEqualTo(1);
            assertThat(one(app, "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                    + " WHERE event_type = 'checkout.OrderPaid' AND aggregate_id = ?", orderId))
                    .contains("\"completedAs\":\"FINANCED\"")
                    .contains("\"capturedEntryId\":\"" + entry.value() + "\"");
            assertThat(one(app, "SELECT change_summary FROM platform.audit_record WHERE operation = 'checkout.OrderCreated'"
                    + " AND target_id = ?", orderId.toString()))
                    .contains("financedBy=" + purchase.value())
                    .contains("completedAs=FINANCED");
            assertThat(count(app, "SELECT count(*) FROM payments.payment_intent WHERE id IN"
                    + " (SELECT payment_intent_ref FROM checkout.checkout_session WHERE id = ?)", session.id()))
                    .as("no payment intent, no attempt, no rail").isZero();
        }

        // The merchant's read: one order, whatever paid for it, and how it was paid.
        HttpResponse<String> read = get("/v1/checkout/sessions/" + session.id(), merchant.apiKey());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("COMPLETED");
        assertThat(field(read.body(), "completedAs")).isEqualTo("FINANCED");
        assertThat(field(read.body(), "orderId")).isEqualTo(orderId.toString());
        assertThat(field(read.body(), "paymentIntentId")).isNull();
    }

    @Test
    @DisplayName("a financing and a CARD confirmation of one session complete it ONCE, in both orders: the card first ->"
            + " the financing NotPayable; the financing first (the card's write waiting on B3) -> the card 409"
            + " checkout.NotConfirmable")
    void financingAndACardConfirmationCompleteOnce() throws Exception {
        Merchant merchant = tradingMerchant();

        // The card first: the confirmation moved it OPEN -> PAYMENT_PENDING and its capture completed it.
        Customer card = payingCustomer();
        Opened paid = openSession(merchant);
        providerAuthorises();
        providerCaptures();
        HttpResponse<String> confirmed = confirm(card, paid.token());
        assertThat(field(confirmed.body(), "status")).as(confirmed.body()).isEqualTo("COMPLETED");
        assertThat(finance(paid.id(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next())))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN));
        assertOneOrder(paid.id(), false);

        // The financing first: B3 held while the card confirmation's conditional write waits on the row.
        Opened financed = openSession(merchant);
        providerAuthorises();
        providerCaptures();
        HttpResponse<String> lost = raceAConfirmationBehindB3(financed, () -> confirm(card, financed.token()));
        assertThat(lost.statusCode()).as(lost.body()).isEqualTo(409);
        assertThat(lost.body()).contains("checkout.NotConfirmable");
        assertOneOrder(financed.id(), true);
    }

    @Test
    @DisplayName("a financing and a WALLET confirmation of one session complete it ONCE, in both orders")
    void financingAndAWalletConfirmationCompleteOnce() throws Exception {
        Merchant merchant = tradingMerchant();
        WalletCustomer wallet = walletCustomer("300.00");

        Opened paid = openSession(merchant);
        HttpResponse<String> confirmed = confirmFromWallet(wallet.token(), paid.token());
        assertThat(field(confirmed.body(), "status")).as(confirmed.body()).isEqualTo("COMPLETED");
        assertThat(finance(paid.id(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next())))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN));
        assertOneOrder(paid.id(), false);

        Opened financed = openSession(merchant);
        HttpResponse<String> lost =
                raceAConfirmationBehindB3(financed, () -> confirmFromWallet(wallet.token(), financed.token()));
        assertThat(lost.statusCode()).as(lost.body()).isEqualTo(409);
        assertThat(lost.body()).contains("checkout.NotConfirmable");
        assertOneOrder(financed.id(), true);
    }

    @Test
    @DisplayName("a financing and the expiry sweeper at the deadline, the sweeper's clock 5 s either side of the"
            + " database's: EXACTLY ONE of COMPLETED and EXPIRED, never both - and never COMPLETED at or after the"
            + " deadline on the database clock")
    void financingAndExpiryLeaveExactlyOne() throws Exception {
        for (Duration skew : List.of(Duration.ofSeconds(-5), Duration.ofSeconds(5))) {
            for (int round = 0; round < 5; round++) {
                CheckoutSession session = insertOpen(Duration.ofMillis(400));
                Instant deadline = session.expiresAt();
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    Future<BnplCheckoutSessions.Completion> financing = pool.submit(() -> {
                        start.await();
                        untilAbout(deadline);
                        return finance(session.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next()));
                    });
                    Future<CheckoutExpirySweeper.SweepResult> sweep = pool.submit(() -> {
                        start.await();
                        untilAbout(deadline);
                        return sweeper(Clock.offset(CLOCK, skew)).sweep();
                    });
                    start.countDown();
                    BnplCheckoutSessions.Completion answer = financing.get(60, TimeUnit.SECONDS);
                    sweep.get(60, TimeUnit.SECONDS);

                    try (Connection app = DatabaseRoles.application()) {
                        String status = one(app, "SELECT status FROM checkout.checkout_session WHERE id = ?",
                                session.id().value());
                        long movesOutOfOpen = count(app, "SELECT count(*) FROM checkout.checkout_session_event"
                                + " WHERE session_id = ? AND from_status = 'OPEN'", session.id().value());
                        assertThat(movesOutOfOpen).as("skew %s round %d: at most one move out of OPEN", skew, round)
                                .isLessThanOrEqualTo(1);
                        assertThat(answer instanceof BnplCheckoutSessions.Completed)
                                .as("the financing's answer agrees with the row").isEqualTo("COMPLETED".equals(status));
                        if ("COMPLETED".equals(status)) {
                            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_session"
                                    + " WHERE id = ? AND status_changed_at < expires_at", session.id().value()))
                                    .as("completed before the deadline on the database clock").isEqualTo(1);
                        } else {
                            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?",
                                    session.id().value())).isZero();
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
                // Whatever the race left OPEN is the sweeper's in the end - never a financing's after the deadline.
                sweeper(Clock.offset(CLOCK, Duration.ofSeconds(10))).sweep();
                try (Connection app = DatabaseRoles.application()) {
                    assertThat(one(app, "SELECT status FROM checkout.checkout_session WHERE id = ?",
                            session.id().value())).isIn("COMPLETED", "EXPIRED");
                }
                assertThat(finance(session.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next())))
                        .as("nothing finances it now").isInstanceOf(BnplCheckoutSessions.NotPayable.class);
            }
        }
    }

    @Test
    @DisplayName("a session NOT OPEN - pending, completed by a capture, expired, abandoned - or OPEN past its deadline is"
            + " refused at B3 at once, and nothing is written")
    void aSessionNotOpenIsRefusedAtOnce() throws Exception {
        CheckoutSession pending = insertOpen(Duration.ofMinutes(30));
        transition(pending, pending.confirm(CLOCK, IDS.next()));
        CheckoutSession captured = insertOpen(Duration.ofMinutes(30));
        CheckoutSession capturedPending = captured.confirm(CLOCK, IDS.next());
        transition(captured, capturedPending);
        transition(capturedPending, capturedPending.complete(CLOCK));
        CheckoutSession expired = insertOpen(Duration.ofMinutes(30));
        transition(expired, expired.expire(CLOCK));
        CheckoutSession abandoned = insertOpen(Duration.ofMinutes(30));
        transition(abandoned, abandoned.abandon(CLOCK));

        for (CheckoutSession session : List.of(pending, captured, expired, abandoned)) {
            long history = historyOf(session.id());
            assertThat(lockForPurchase(session.id().value()))
                    .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN));
            assertThat(finance(session.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next())))
                    .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN));
            assertThat(historyOf(session.id())).as("nothing written").isEqualTo(history);
        }

        CheckoutSession dead = insertOpen(Duration.ofMillis(200));
        Thread.sleep(400);
        assertThat(lockForPurchase(dead.id().value()))
                .as("OPEN by its row, past its deadline on the database clock")
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.EXPIRED));
        assertThat(finance(dead.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next())))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.EXPIRED));
        try (Connection app = DatabaseRoles.application()) {
            assertThat(one(app, "SELECT status FROM checkout.checkout_session WHERE id = ?", dead.id().value()))
                    .isEqualTo("OPEN");
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?",
                    dead.id().value())).isZero();
        }
    }

    @Test
    @DisplayName("TEN completions of one session on ten connections: one Completed, nine NotPayable, ONE order, one"
            + " announcement, one history row - the winner's purchase the financing reference")
    void tenCompletionsOneOrder() throws Exception {
        CheckoutSession session = insertOpen(Duration.ofMinutes(30));
        int racers = 10;
        List<BnplPurchaseId> purchases = new ArrayList<>();
        List<Callable<BnplCheckoutSessions.Completion>> tasks = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            BnplPurchaseId purchase = BnplPurchaseId.next(IDS);
            purchases.add(purchase);
            tasks.add(() -> finance(session.id().value(), purchase, JournalEntryId.of(IDS.next())));
        }
        List<BnplCheckoutSessions.Completion> answers = concurrently(tasks);

        assertThat(answers).filteredOn(BnplCheckoutSessions.Completed.class::isInstance).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.equals(
                new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN))).hasSize(racers - 1);
        try (Connection app = DatabaseRoles.application()) {
            UUID id = session.id().value();
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?", id)).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type = 'checkout.OrderPaid'"
                    + " AND aggregate_id = (SELECT id FROM checkout.checkout_order WHERE session_ref = ?)", id))
                    .isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_session_event WHERE session_id = ?"
                    + " AND to_status = 'COMPLETED'", id)).isEqualTo(1);
            assertThat(purchases.stream().map(purchase -> purchase.value().toString()).toList())
                    .contains(one(app, "SELECT financed_by_ref::text FROM checkout.checkout_session WHERE id = ?", id));
        }
    }

    // ================================================================== the database rank

    @Test
    @DisplayName("financed_by_ref is SET ONCE, exclusive with the intent, and one purchase finances one session - for"
            + " every writer")
    void financedByRefIsSetOnceAndExclusiveWithTheIntent() throws Exception {
        CheckoutSession session = insertOpen(Duration.ofMinutes(30));
        finance(session.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next()));
        UUID purchase = UUID.fromString(financedByOf(session.id()));

        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> execute(migrator,
                    "UPDATE checkout.checkout_session SET financed_by_ref = ? WHERE id = ?", IDS.next(),
                    session.id().value()))
                    .isInstanceOf(SQLException.class).hasMessageContaining("set once");
            assertThatThrownBy(() -> execute(migrator,
                    "UPDATE checkout.checkout_session SET payment_intent_ref = ? WHERE id = ?", IDS.next(),
                    session.id().value()))
                    .as("never financed AND paid by an intent")
                    .isInstanceOfSatisfying(SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("23514"))
                    .hasMessageContaining("checkout_session_financed_is_completed_without_intent");

            CheckoutSession other = insertOpen(Duration.ofMinutes(30));
            assertThatThrownBy(() -> execute(migrator,
                    "UPDATE checkout.checkout_session SET status = 'COMPLETED', status_changed_at = GREATEST(now(), created_at),"
                            + " financed_by_ref = ? WHERE id = ?", purchase, other.id().value()))
                    .as("one purchase finances one session")
                    .isInstanceOfSatisfying(SQLException.class,
                            refused -> assertThat(refused.getSQLState()).isEqualTo("23505"))
                    .hasMessageContaining("checkout_session_one_session_per_financing");

            CheckoutSession pending = insertOpen(Duration.ofMinutes(30));
            transition(pending, pending.confirm(CLOCK, IDS.next()));
            assertThatThrownBy(() -> execute(migrator,
                    "UPDATE checkout.checkout_session SET status = 'COMPLETED', status_changed_at = GREATEST(now(), created_at),"
                            + " financed_by_ref = ? WHERE id = ?", IDS.next(), pending.id().value()))
                    .as("PAYMENT_PENDING -> COMPLETED is a capture's, never a financing's")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("only by the financing edge");
        }
    }

    @Test
    @DisplayName("the edge OPEN -> COMPLETED is refused to EVERY ROLE without a financing reference, beside an intent,"
            + " past the deadline, or without its order at commit")
    void theEdgeIsRefusedToEveryRoleWithoutAFinancingRef() throws Exception {
        for (String role : List.of("application", "migrator")) {
            try (Connection writer = "application".equals(role) ? DatabaseRoles.application() : DatabaseRoles.migrator()) {
                CheckoutSession bare = insertOpen(Duration.ofMinutes(30));
                assertThatThrownBy(() -> execute(writer,
                        "UPDATE checkout.checkout_session SET status = 'COMPLETED', status_changed_at = GREATEST(now(), created_at)"
                                + " WHERE id = ?", bare.id().value()))
                        .as("%s: OPEN -> COMPLETED without a financing reference", role)
                        .isInstanceOf(SQLException.class).hasMessageContaining("by financing only");

                CheckoutSession withIntent = insertOpen(Duration.ofMinutes(30));
                assertThatThrownBy(() -> execute(writer,
                        "UPDATE checkout.checkout_session SET status = 'COMPLETED', status_changed_at = GREATEST(now(), created_at),"
                                + " payment_intent_ref = ?, financed_by_ref = ? WHERE id = ?",
                        IDS.next(), IDS.next(), withIntent.id().value()))
                        .as("%s: a financing beside an intent", role)
                        .isInstanceOf(SQLException.class);

                CheckoutSession dead = insertOpen(Duration.ofMillis(200));
                // Past the deadline on the DATABASE clock - the rule's own (statement_timestamp()), never the JVM's: the
                // deadline is stamped from the JVM clock, so a fixed 400 ms sleep left a database clock lagging the JVM's
                // by more than 200 ms before the deadline, the edge admitted and the deferred order rule refusing at
                // commit instead (the Phase 12 -> 13 transition gate's baseline, G3 - a test defect, not production's).
                awaitPastTheDeadlineOnTheDatabaseClock(dead);
                assertThatThrownBy(() -> execute(writer,
                        "UPDATE checkout.checkout_session SET status = 'COMPLETED', status_changed_at = GREATEST(now(), created_at),"
                                + " financed_by_ref = ? WHERE id = ?", IDS.next(), dead.id().value()))
                        .as("%s: a financing after the deadline", role)
                        .isInstanceOf(SQLException.class).hasMessageContaining("closes at the deadline");

                CheckoutSession orderless = insertOpen(Duration.ofMinutes(30));
                assertThatThrownBy(() -> execute(writer,
                        "UPDATE checkout.checkout_session SET status = 'COMPLETED', status_changed_at = GREATEST(now(), created_at),"
                                + " financed_by_ref = ? WHERE id = ?", IDS.next(), orderless.id().value()))
                        .as("%s: completed by financing without its order, refused at commit", role)
                        .isInstanceOf(SQLException.class).hasMessageContaining("without its order");

                for (CheckoutSession session : List.of(bare, withIntent, dead, orderless)) {
                    assertThat(one(writer, "SELECT status FROM checkout.checkout_session WHERE id = ?",
                            session.id().value())).isEqualTo("OPEN");
                }
            }
        }
    }

    @Test
    @DisplayName("a rolled-back financing leaves the session OPEN - no order, no event, no history, no reference - and a"
            + " later one completes it")
    void aRolledBackFinancingLeavesTheSessionOpen() throws Exception {
        CheckoutSession session = insertOpen(Duration.ofMinutes(30));
        assertThatThrownBy(() -> asCustomer(() -> bnplTransactions.inTransaction(uow -> {
            assertThat(bnplCheckoutSessions.lockForPurchase(uow, session.id().value()))
                    .isInstanceOf(BnplCheckoutSessions.Payable.class);
            assertThat(bnplCheckoutSessions.completeByFinancing(uow, session.id().value(), BnplPurchaseId.next(IDS),
                    JournalEntryId.of(IDS.next()), correlation())).isInstanceOf(BnplCheckoutSessions.Completed.class);
            throw new IllegalStateException("the purchase failed after the completion (a forced failure)");
        }))).isInstanceOf(IllegalStateException.class);

        try (Connection app = DatabaseRoles.application()) {
            UUID id = session.id().value();
            assertThat(one(app, "SELECT status FROM checkout.checkout_session WHERE id = ?", id)).isEqualTo("OPEN");
            assertThat(one(app, "SELECT financed_by_ref::text FROM checkout.checkout_session WHERE id = ?", id)).isNull();
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?", id)).isZero();
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_session_event WHERE session_id = ?", id))
                    .isZero();
        }
        assertThat(finance(session.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next())))
                .isInstanceOf(BnplCheckoutSessions.Completed.class);
    }

    @Test
    @DisplayName("NO COMPLETED_LATE BY FINANCING: a financed session goes nowhere, an EXPIRED one is never financed, and"
            + " the late edge refuses a financing reference")
    void noCompletedLateByFinancing() throws Exception {
        CheckoutSession financed = insertOpen(Duration.ofMinutes(30));
        finance(financed.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next()));
        CheckoutSession expired = insertOpen(Duration.ofMinutes(30));
        transition(expired, expired.expire(CLOCK));

        assertThat(finance(expired.id().value(), BnplPurchaseId.next(IDS), JournalEntryId.of(IDS.next())))
                .isEqualTo(new BnplCheckoutSessions.NotPayable(BnplCheckoutSessions.Reason.NOT_OPEN));
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> execute(migrator,
                    "UPDATE checkout.checkout_session SET status = 'COMPLETED_LATE', status_changed_at = GREATEST(now(), created_at)"
                            + " WHERE id = ?", financed.id().value()))
                    .as("a financed session is terminal").isInstanceOf(SQLException.class)
                    .hasMessageContaining("machine");
            assertThatThrownBy(() -> execute(migrator,
                    "UPDATE checkout.checkout_session SET status = 'COMPLETED_LATE', status_changed_at = GREATEST(now(), created_at),"
                            + " financed_by_ref = ? WHERE id = ?", IDS.next(), expired.id().value()))
                    .as("EXPIRED -> COMPLETED_LATE stays a capture's").isInstanceOf(SQLException.class)
                    .hasMessageContaining("only by the financing edge");
        }
    }

    // ================================================================== the port, in bnpl's unit of work

    private BnplCheckoutSessions.Completion finance(UUID sessionRef, BnplPurchaseId purchase, JournalEntryId entry)
            throws Exception {
        return asCustomer(() -> bnplTransactions.inTransaction(uow -> {
            BnplCheckoutSessions.Lock judged = bnplCheckoutSessions.lockForPurchase(uow, sessionRef);
            if (judged instanceof BnplCheckoutSessions.NotPayable refused) {
                return refused;
            }
            return bnplCheckoutSessions.completeByFinancing(uow, sessionRef, purchase, entry, correlation());
        }));
    }

    private BnplCheckoutSessions.Lock lockForPurchase(UUID sessionRef) throws Exception {
        return asCustomer(() -> bnplTransactions.inTransaction(uow -> bnplCheckoutSessions.lockForPurchase(uow, sessionRef)));
    }

    /**
     * B3 taken and held in one thread while {@code confirmation} runs in another; once the confirmation's conditional
     * write is seen waiting on the session row, the financing completes and commits - the confirmation then finds the
     * session financed.
     */
    private HttpResponse<String> raceAConfirmationBehindB3(Opened session, Callable<HttpResponse<String>> confirmation)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BnplCheckoutSessions.Completion> financing = pool.submit(() -> asCustomer(() ->
                    bnplTransactions.inTransaction(uow -> {
                        assertThat(bnplCheckoutSessions.lockForPurchase(uow, session.id()))
                                .isInstanceOf(BnplCheckoutSessions.Payable.class);
                        locked.countDown();
                        try {
                            assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return bnplCheckoutSessions.completeByFinancing(uow, session.id(), BnplPurchaseId.next(IDS),
                                JournalEntryId.of(IDS.next()), correlation());
                    })));
            assertThat(locked.await(60, TimeUnit.SECONDS)).isTrue();
            Future<HttpResponse<String>> confirm = pool.submit(confirmation);
            awaitLockWaitOn("checkout.checkout_session", "UPDATE");
            release.countDown();
            assertThat(financing.get(60, TimeUnit.SECONDS)).isInstanceOf(BnplCheckoutSessions.Completed.class);
            return confirm.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertOneOrder(UUID sessionRef, boolean financed) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?", sessionRef))
                    .isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM checkout.checkout_session WHERE id = ?"
                    + " AND status = 'COMPLETED' AND (financed_by_ref IS NOT NULL) = ?", sessionRef, financed))
                    .as("completed once, by %s", financed ? "the financing" : "the payment").isEqualTo(1);
            if (financed) {
                assertThat(one(app, "SELECT payment_intent_ref::text FROM checkout.checkout_session WHERE id = ?",
                        sessionRef)).as("the losing confirmation's intent rolled back with it").isNull();
            }
        }
    }

    private static <T> T asCustomer(Callable<T> work) throws Exception {
        try (SecurityContext.Scope customer = SecurityContext.enter(
                        new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(correlation())) {
            return work.call();
        }
    }

    private static Correlation correlation() {
        return Correlation.startingWith(CorrelationId.generate(IDS));
    }

    // ================================================================== sessions by value

    /** An OPEN session with arbitrary outward references - they travel by value (ADR-0029). */
    /**
     * An OPEN session born on this JVM's clock. The raw edges below stamp {@code status_changed_at} as
     * {@code GREATEST(now(), created_at)} - production's own clamp ({@code CheckoutSession.completeByFinancing}): a raw
     * {@code now()} read the database clock a few milliseconds behind the JVM's that stamped {@code created_at}, and
     * {@code checkout_session_status_change_is_not_before_creation} refused the row before the rule under test (the Phase 12
     * database pass, `P12-TSK-028`).
     */
    private CheckoutSession insertOpen(Duration window) throws Exception {
        CheckoutSession session = CheckoutSession.open(IDS, CLOCK, IDS.next(),
                Money.ofMinorUnits(AMOUNT_MINOR, CurrencyCode.of("EUR")), "A bicycle", IDS.next(),
                CheckoutSessionToken.issue(new SecureRandom()), Instant.now(CLOCK).plus(window));
        asCustomer(() -> inTransaction(uow -> {
            sessions.insert(uow, session);
            return null;
        }));
        return session;
    }

    private void transition(CheckoutSession before, CheckoutSession after) throws Exception {
        boolean moved = asCustomer(() -> inTransaction(uow -> sessions.transition(uow, before, after)));
        assertThat(moved).isTrue();
    }

    private static <R> R inTransaction(Function<Connection, R> work) throws SQLException {
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
        }
    }

    private CheckoutExpirySweeper sweeper(Clock clock) {
        return new CheckoutExpirySweeper(
                new CheckoutTransactionRunner() {
                    @Override
                    public <R> R inTransaction(Function<Connection, R> work) {
                        try {
                            return CheckoutFinancingDatabaseTest.inTransaction(work);
                        } catch (SQLException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                },
                sessions, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, clock, Duration.ofMinutes(10), 50,
                com.finapp.checkout.UndispatchedPayments.none());
    }

    /** Busy-waits to within a millisecond of {@code instant} on this JVM's clock, so two racers meet the deadline. */
    private static void untilAbout(Instant instant) {
        while (Instant.now(CLOCK).isBefore(instant.minusMillis(1))) {
            Thread.onSpinWait();
        }
    }

    private static long historyOf(CheckoutSessionId id) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return count(app, "SELECT count(*) FROM checkout.checkout_session_event WHERE session_id = ?", id.value());
        }
    }

    private static String financedByOf(CheckoutSessionId id) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return one(app, "SELECT financed_by_ref::text FROM checkout.checkout_session WHERE id = ?", id.value());
        }
    }

    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Until a backend waits on a lock for a statement touching {@code table} with {@code marker}. */
    private static void awaitLockWaitOn(String table, String marker) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity"
                        + " WHERE wait_event_type = 'Lock' AND query LIKE ? AND query LIKE ?")) {
            select.setString(1, "%" + table + "%");
            select.setString(2, "%" + marker + "%");
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= 1) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError("the confirmation never waited on the session row - B3 was not held");
    }

    // ================================================================== merchants and customers, over HTTP

    private record Merchant(String id, String apiKey) {}

    private record Customer(String token, String methodId) {}

    private record WalletCustomer(String token, String product) {}

    private record Opened(UUID id, String token) {}

    private Opened openSession(Merchant merchant) throws Exception {
        HttpResponse<String> created = post("/v1/checkout/sessions",
                "{\"amountMinor\":" + AMOUNT_MINOR + ",\"currency\":\"EUR\",\"lineSummary\":\"A bicycle\"}",
                merchant.apiKey(), someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return new Opened(UUID.fromString(field(created.body(), "checkoutId")), field(created.body(), "sessionToken"));
    }

    private Merchant tradingMerchant() throws Exception {
        String operator = operatorSession(RoleName.MERCHANT_ADMINISTRATOR);
        String merchantId = onboard(operator);
        String schedule = field(post("/v1/operator/fee-schedules",
                "{\"name\":\"Std " + UUID.randomUUID() + "\",\"currency\":\"EUR\"}", operator, null).body(),
                "feeScheduleId");
        assertThat(post("/v1/operator/fee-schedules/" + schedule + "/versions",
                "{\"rate\":0.029,\"fixedAmountMinor\":30,\"roundingPolicy\":\"HALF_EVEN\","
                        + "\"refundFeePolicy\":\"RETAINED\",\"reason\":\"initial pricing\"}", operator, null)
                .statusCode()).isEqualTo(201);
        assertThat(put("/v1/operator/merchants/" + merchantId + "/fee-schedule",
                "{\"feeScheduleId\":\"" + schedule + "\",\"reason\":\"standard terms\"}", operator).statusCode())
                .isEqualTo(200);
        HttpResponse<String> issued = post("/v1/operator/merchants/" + merchantId + "/api-keys", null, operator, someKey());
        assertThat(issued.statusCode()).isEqualTo(201);
        return new Merchant(merchantId, field(issued.body(), "keyId") + "." + field(issued.body(), "secret"));
    }

    private String onboard(String operator) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                    + " (?, 'ORGANISATION', 'Acme Holdings', now())", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES"
                    + " (?, ?, 'ACTIVE', now() - interval '1 hour', now())", IDS.next(), party);
        }
        HttpResponse<String> created = post("/v1/operator/merchants",
                "{\"partyId\":\"" + party + "\",\"legalName\":\"Acme GmbH\","
                        + "\"displayName\":\"Acme\",\"settlementCurrency\":\"EUR\"}", operator, someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        return field(created.body(), "merchantId");
    }

    private Customer payingCustomer() throws Exception {
        String login = "fin." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)"
                    + " WHERE party_id = (SELECT party_id FROM identity.identity WHERE login_identifier = ?)", login);
        }
        String token = field(authenticate(login).body(), "sessionToken");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached = post("/v1/me/payment-methods", "{\"clientToken\":\"ctok_" + suffix + "\"}",
                token, null);
        assertThat(attached.statusCode()).isEqualTo(201);
        return new Customer(token, field(attached.body(), "id"));
    }

    private WalletCustomer walletCustomer(String funding) throws Exception {
        Customer carded = payingCustomer();
        HttpResponse<String> opened = post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                carded.token(), someKey());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        providerAuthorises();
        providerCaptures();
        String paymentId = field(post("/v1/payments", "{\"paymentMethodId\":\"" + carded.methodId()
                + "\",\"amount\":\"" + funding + "\",\"currency\":\"EUR\"}", carded.token(), someKey()).body(), "id");
        HttpResponse<String> topUp = post("/v1/payments/" + paymentId + "/confirmation", null, carded.token(), null);
        assertThat(field(topUp.body(), "status")).as(topUp.body()).isEqualTo("SUCCEEDED");
        return new WalletCustomer(carded.token(), field(opened.body(), "id"));
    }

    private String operatorSession(RoleName role) throws Exception {
        String login = "ops." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application()) {
            identity = UUID.fromString(one(app, "SELECT id::text FROM identity.identity WHERE login_identifier = ?",
                    login));
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(correlation());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return field(authenticate(login).body(), "sessionToken");
    }

    private HttpResponse<String> confirm(Customer customer, String sessionToken) throws Exception {
        return post("/v1/checkout/sessions/confirmation", "{\"sessionToken\":\"" + sessionToken
                + "\",\"paymentMethodId\":\"" + customer.methodId() + "\"}", customer.token(), null);
    }

    private HttpResponse<String> confirmFromWallet(String token, String sessionToken) throws Exception {
        return post("/v1/checkout/sessions/confirmation",
                "{\"sessionToken\":\"" + sessionToken + "\",\"instrument\":\"WALLET\"}", token, null);
    }

    private void providerAuthorises() {
        provider.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_auth-" + UUID.randomUUID() + "\"}");
    }

    private void providerCaptures() {
        provider.succeedsWith(SimulatedCardPspAdapter.CAPTURES_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_cap-" + UUID.randomUUID() + "\"}");
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null, someKey());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, null);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    /**
     * Waits - at most 30 s - until {@code session}'s deadline has passed on the database clock, the instant the financing
     * edge's trigger judges ({@code OLD.expires_at <= statement_timestamp()}, checkout V004), whatever the JVM clock says.
     */
    private static void awaitPastTheDeadlineOnTheDatabaseClock(CheckoutSession session) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.application()) {
            while (!"true".equals(one(observer, "SELECT (expires_at <= statement_timestamp())::text FROM"
                    + " checkout.checkout_session WHERE id = ?", session.id().value()))) {
                assertThat(System.nanoTime()).as("the deadline passes on the database clock").isLessThan(deadline);
                Thread.sleep(50);
            }
        }
    }

    private static String one(Connection connection, String sql, Object argument) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }
}
