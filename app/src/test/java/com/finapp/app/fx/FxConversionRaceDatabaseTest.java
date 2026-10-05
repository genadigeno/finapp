package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.key;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.fx.ConversionParticipants;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.FxConversion;
import com.finapp.fx.FxQuoteId;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.TradeStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.lang.reflect.Proxy;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
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
 * The conversion under concurrency (`P9-TSK-009`; PHASE_9_PLAN.md section 13's scenarios 1 and 2;
 * INV-FX-04): ten acceptors with the same and different keys book one trade, one entry, one cover;
 * ten conversions into a currency the customer does not hold open one wallet and abort nothing; two
 * hundred quotes expiring mid-race under ten acceptors and ten sweepers each take exactly one
 * terminal path; and a failure injected at each step of the booking leaves nothing behind. Under a
 * short-window version ({@link FxTestPolicy#RACE}): EUR-USD both ways, 5 s, a hundred open quotes.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the conversion under concurrency: scenarios 1 and 2 (P9-TSK-009)")
class FxConversionRaceDatabaseTest {

    /** A wallet's balance from its journal lines - credits less debits, the stored value the platform owes. */
    private static final String EUR_BALANCE =
            "SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                    + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                    + " WHERE a.owner_ref = ? AND a.currency = 'EUR' AND a.purpose = 'CUSTOMER_WALLET'";

    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private QuoteLifecycle lifecycle;
    @Autowired private TransactionRunner fxTransactionRunner;
    @Autowired private QuoteStore quotes;
    @Autowired private TradeStore trades;
    @Autowired private FxAvailability availability;
    @Autowired private ConversionParticipants participants;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private OutboxWriter<Connection> outboxWriter;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(engine, Map.of());
        }
        registry.add("finapp.fx.provider.url", () -> engine.baseUrl().toString());
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @BeforeEach
    void raceVersionAndFreshReferences() throws Exception {
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.RACE);
        FxTestClient.freshReferences();
    }

    @Test
    @DisplayName("scenario 1: ten acceptors of one quote - five sharing a key, five with their own - book one"
            + " trade, one entry, one cover; the losers replay it or answer 409 (counted)")
    void tenAcceptorsBookOnce() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("1000.00", "EUR"));
        String quote = client.quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "100.00");
        String shared = key();
        List<HttpResponse<String>> answers = race(10, index -> client.convert(customer, quote, index < 5 ? shared : key()));
        Set<String> trades = ConcurrentHashMap.newKeySet();
        for (HttpResponse<String> answer : answers) {
            if (answer.statusCode() == 201) {
                trades.add(field(answer.body(), "id"));
            } else {
                assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
                assertThat(answer.body()).containsAnyOf("fx.QuoteAlreadyAccepted", "api.IdempotencyInProgress");
            }
        }
        assertThat(trades).as("every 201 is the one trade").hasSize(1);
        assertThat(count("SELECT count(*) FROM fx.trade WHERE quote_id = ?::uuid", quote)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry e JOIN fx.trade t ON t.journal_entry_id = e.id"
                + " WHERE t.quote_id = ?::uuid", quote)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                "fx-trade:" + trades.iterator().next())).as("one entry under the posting key").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ?::uuid", quote)).isEqualTo(1);
        assertThat(scalar(EUR_BALANCE, product))
                .as("debited once: 1000.00 - 100.00")
                .isEqualTo("90000");
    }

    @Test
    @DisplayName("ten conversions into a currency the customer does not hold: ten trades, ONE wallet opened,"
            + " no transaction aborted (D28, counted)")
    void tenConversionsOpenOneWallet() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("1000.00", "EUR"));
        List<String> quoteIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            quoteIds.add(client.quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "10.00"));
        }
        List<HttpResponse<String>> answers = race(10, index -> client.convert(customer, quoteIds.get(index), key()));
        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isEqualTo(201));
        assertThat(count("SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ? AND currency = 'USD'"
                + " AND purpose = 'CUSTOMER_WALLET'", product)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'accounts.WalletCurrencyAdded'"
                + " AND aggregate_id = ? AND convert_from(payload, 'UTF8') LIKE '%USD%'", product))
                .as("announced once, by the act whose insert returned the row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 2: two hundred quotes expiring mid-race under ten acceptors and ten sweepers - each"
            + " exactly one of {EXECUTED with one trade, accepted before expiry; EXPIRED with one event, no trade}")
    void theExpiryRace() throws Exception {
        FxTestClient client = new FxTestClient(port);
        List<FxTestClient.Customer> owners = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            FxTestClient.Customer owner = client.verifiedCustomer();
            fund(postings, client.openWallet(owner, "EUR"), money("10000.00", "EUR"));
            owners.add(owner);
        }
        List<String[]> issued = Collections.synchronizedList(new ArrayList<>());
        race(10, index -> {
            for (int i = 0; i < 20; i++) {
                FxTestClient.Customer owner = owners.get(index % 2);
                issued.add(new String[] {client.quoteId(owner, "EUR", "USD", "FIXED_SOURCE", "10.00"), String.valueOf(index % 2)});
            }
            return null;
        });
        assertThat(issued).hasSize(200);
        Instant until = Instant.now().plusSeconds(12);
        race(20, index -> {
            if (index < 10) {
                // An acceptor: walks every quote, again and again, each attempt its own key.
                while (Instant.now().isBefore(until)) {
                    for (String[] quote : issued) {
                        client.convert(owners.get(Integer.parseInt(quote[1])), quote[0], key());
                    }
                }
            } else {
                while (Instant.now().isBefore(until)) {
                    sweep();
                }
            }
            return null;
        });
        sweep();
        int executed = 0;
        for (String[] quote : issued) {
            String status = scalar("SELECT status FROM fx.quote WHERE id = ?::uuid", quote[0]);
            long tradesOf = count("SELECT count(*) FROM fx.trade WHERE quote_id = ?::uuid", quote[0]);
            long expiries = count("SELECT count(*) FROM fx.quote_event WHERE quote_id = ?::uuid AND to_status = 'EXPIRED'", quote[0]);
            if ("EXECUTED".equals(status)) {
                executed++;
                assertThat(tradesOf).isEqualTo(1);
                assertThat(expiries).isZero();
                assertThat(scalar("SELECT e.occurred_at < q.expires_at FROM fx.quote_event e JOIN fx.quote q"
                                + " ON q.id = e.quote_id WHERE q.id = ?::uuid AND e.to_status = 'ACCEPTED'", quote[0]))
                        .as("accepted before its expiry, on the database clock")
                        .isEqualTo("t");
            } else {
                assertThat(status).as("quote %s", quote[0]).isEqualTo("EXPIRED");
                assertThat(tradesOf).isZero();
                assertThat(expiries).as("one expiry event for %s", quote[0]).isEqualTo(1);
            }
        }
        assertThat(executed).as("the race really ran both ways").isBetween(1, 199);
    }

    @Test
    @DisplayName("both orders forced: a lapsed quote the sweep expired first answers QuoteExpired and writes"
            + " nothing; a lapsed, unswept quote is expired BY the acceptance (detectedBy ACCEPTANCE), once")
    void bothExpiryOrders() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        fund(postings, client.openWallet(customer, "EUR"), money("100.00", "EUR"));
        String swept = client.quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "10.00");
        String unswept = client.quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "10.00");
        awaitLapsedOnTheDatabaseClock(swept, unswept);
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            fxTransactionRunner.inTransaction(unitOfWork -> {
                assertThat(quotes.expire(unitOfWork, FxQuoteId.of(UUID.fromString(swept)))).isTrue();
                return null;
            });
        }
        HttpResponse<String> late = client.convert(customer, swept, key());
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("fx.QuoteExpired");

        String keyOfTheLate = key();
        HttpResponse<String> found = client.convert(customer, unswept, keyOfTheLate);
        assertThat(found.statusCode()).isEqualTo(409);
        assertThat(found.body()).contains("fx.QuoteExpired");
        assertThat(scalar("SELECT detected_by FROM fx.quote_event WHERE quote_id = ?::uuid AND to_status = 'EXPIRED'", unswept))
                .isEqualTo("ACCEPTANCE");
        assertThat(client.convert(customer, unswept, keyOfTheLate).body())
                .as("the committed failure replays")
                .contains("fx.QuoteExpired");
        sweep();
        assertThat(count("SELECT count(*) FROM fx.quote_event WHERE quote_id = ?::uuid AND to_status = 'EXPIRED'", unswept))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxQuoteExpired'"
                + " AND aggregate_id = ?::uuid", unswept)).isEqualTo(1);
    }

    /**
     * Waits until the DATABASE's clock - the one that stamped {@code expires_at} and judges it -
     * has passed each quote's expiry. A JVM sleep of the 5 s window plus one second measured the
     * wrong clock: the container's VM clock was measured stepping back 1.7 s at once
     * (`X-TSK-005`), so the database could still hold a quote live when the conditional expire ran
     * (found red by `X-TSK-016`'s full tier run).
     */
    private static void awaitLapsedOnTheDatabaseClock(String... quoteIds) throws Exception {
        Instant deadline = Instant.now().plusSeconds(60);
        for (String quote : quoteIds) {
            while (!"t".equals(scalar("SELECT expires_at <= statement_timestamp() FROM fx.quote"
                    + " WHERE id = ?::uuid", quote))) {
                assertThat(Instant.now())
                        .as("quote %s lapsed on the database clock within a minute", quote)
                        .isBefore(deadline);
                Thread.sleep(100);
            }
        }
    }

    @Test
    @DisplayName("atomicity: a failure injected at each step of the booking - the trade, its entry, the cover,"
            + " the events, the audit - leaves nothing: the quote ISSUED, no trade, no entry, no cover, no new"
            + " wallet, the balance unchanged")
    void aFailureAtEachStepLeavesNothing() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("1000.00", "EUR"));
        List<String> steps = List.of("insert", "attachEntry", "insertCover", "outbox", "audit");
        for (String step : steps) {
            String quote = client.quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "100.00");
            FxConversion conversion = conversionFailingAt(step);
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                    SecurityContext.Scope actor = SecurityContext.enter(new Actor(customer.party().toString(), ActorType.CUSTOMER))) {
                assertThatThrownBy(() -> fxTransactionRunner.inTransaction(unitOfWork ->
                                conversion.convert(unitOfWork, FxQuoteId.of(UUID.fromString(quote)), customer.party(),
                                        new Actor(customer.party().toString(), ActorType.CUSTOMER),
                                        CorrelationId.generate(FxTestClient.IDS))))
                        .as(step)
                        .hasMessageContaining("injected");
            }
            assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?::uuid", quote)).as(step).isEqualTo("ISSUED");
            assertThat(count("SELECT count(*) FROM fx.trade WHERE quote_id = ?::uuid", quote)).as(step).isZero();
            assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ?::uuid", quote)).as(step).isZero();
            assertThat(count("SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ? AND currency = 'USD'", product))
                    .as(step).isZero();
            assertThat(scalar(EUR_BALANCE, product))
                    .as(step).isEqualTo("100000");
        }
        assertThat(count("SELECT count(*) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " WHERE a.owner_ref = ?", product)).as("only the funding line ever touched the wallet").isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private void sweep() {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            while (fxTransactionRunner.inTransaction(unitOfWork ->
                    lifecycle.expirePage(unitOfWork, 50, Instant.now(), SecurityContext.require())).size() == 50) {
                // the next page
            }
        }
    }

    /** The conversion with a failure injected at one step - the rest are the real collaborators. */
    @Test
    @DisplayName("an acceptance judged in time records THAT instant: the booking outlives the quote's expiry"
            + " before the ACCEPTED event is written, and the event still reads before expires_at (X-TSK-016)")
    void theAcceptedEventRecordsTheInstantItWasJudged() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        fund(postings, client.openWallet(customer, "EUR"), money("100.00", "EUR"));
        String quote = client.quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "10.00");
        // The trade's insert runs after the accept and before the events: held, in the converting
        // transaction itself, until the database's clock has passed the quote's expiry.
        TradeStore heldTrades = (TradeStore) Proxy.newProxyInstance(TradeStore.class.getClassLoader(),
                new Class<?>[] {TradeStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("insert")) {
                        holdPastExpiry((Connection) args[0], quote);
                    }
                    try {
                        return method.invoke(trades, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        FxConversion conversion = new FxConversion(quotes, heldTrades, availability, participants,
                ledgerAccountStore, new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                new ChartOfAccounts<>(ledgerAccountStore), postings, auditWriter, outboxWriter,
                FxTestClient.IDS, Clock.systemUTC());
        Actor actor = new Actor(customer.party().toString(), ActorType.CUSTOMER);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope scope = SecurityContext.enter(actor)) {
            fxTransactionRunner.inTransaction(unitOfWork -> conversion.convert(unitOfWork,
                    FxQuoteId.of(UUID.fromString(quote)), customer.party(), actor,
                    CorrelationId.generate(FxTestClient.IDS)));
        }
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?::uuid", quote)).isEqualTo("EXECUTED");
        assertThat(scalar("SELECT e.occurred_at >= q.expires_at FROM fx.quote_event e JOIN fx.quote q"
                        + " ON q.id = e.quote_id WHERE q.id = ?::uuid AND e.to_status = 'EXECUTED'", quote))
                .as("the hold outlived the quote: the EXECUTED event, stamped by its own statement, reads past"
                        + " expiry - so the check below is not vacuous")
                .isEqualTo("t");
        assertThat(scalar("SELECT e.occurred_at < q.expires_at FROM fx.quote_event e JOIN fx.quote q"
                        + " ON q.id = e.quote_id WHERE q.id = ?::uuid AND e.to_status = 'ACCEPTED'", quote))
                .as("the ACCEPTED event records the instant the conditional judged, before expiry - never a"
                        + " later statement's clock, which would make the history contradict the rule")
                .isEqualTo("t");
    }

    /** Waits, on the transaction's own connection, until the database's clock is past the expiry. */
    private static void holdPastExpiry(Connection unitOfWork, String quote) throws Exception {
        Instant deadline = Instant.now().plusSeconds(60);
        try (java.sql.PreparedStatement lapsed = unitOfWork.prepareStatement(
                "SELECT statement_timestamp() > expires_at FROM fx.quote WHERE id = ?::uuid")) {
            lapsed.setString(1, quote);
            while (true) {
                try (java.sql.ResultSet row = lapsed.executeQuery()) {
                    row.next();
                    if (row.getBoolean(1)) {
                        return;
                    }
                }
                assertThat(Instant.now()).as("the quote lapsed within a minute").isBefore(deadline);
                Thread.sleep(100);
            }
        }
    }

    private FxConversion conversionFailingAt(String step) {
        TradeStore failingTrades = (TradeStore) Proxy.newProxyInstance(TradeStore.class.getClassLoader(),
                new Class<?>[] {TradeStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals(step)) {
                        throw new IllegalStateException("injected at " + step);
                    }
                    try {
                        return method.invoke(trades, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        OutboxWriter<Connection> outbox = step.equals("outbox")
                ? (unitOfWork, envelope, payload, mediaType) -> {
                    throw new IllegalStateException("injected at outbox");
                }
                : outboxWriter;
        AuditWriter<Connection> audit = step.equals("audit")
                ? (unitOfWork, record) -> {
                    throw new IllegalStateException("injected at audit");
                }
                : auditWriter;
        return new FxConversion(quotes, failingTrades, availability, participants, ledgerAccountStore,
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                new ChartOfAccounts<>(ledgerAccountStore), postings, audit, outbox, FxTestClient.IDS, Clock.systemUTC());
    }

    @FunctionalInterface
    private interface Racer<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> race(int racers, Racer<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return work.run(index);
                };
                pending.add(pool.submit(task));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(5, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
