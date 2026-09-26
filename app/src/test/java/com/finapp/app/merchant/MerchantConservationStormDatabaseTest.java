package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.ProjectionVerification.Verdict;
import com.finapp.ledger.TrialBalance;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <strong>The merchant conservation storm</strong> (`P6-TST-002`) — Phase 6's composition
 * demonstration: checkouts, captures that take a fee, merchant refunds and payouts, all at once,
 * on three merchants in three currencies, while the trial-balance and projection sweeps run.
 * {@code P5-TST-003}'s discipline, on the merchant book.
 *
 * <h2>Why a storm, when every flow is proven alone</h2>
 *
 * <p>Each Phase 6 flow has its own suite: the capture's four lines, the refund funded by its net,
 * the payout held against the payable, the destination's four eyes. None of them has run beside
 * the others on the same payable, and that is the only place mechanisms designed apart can meet:
 * a payout's hold and a refund's reservation judged against one account at once, a capture
 * crediting a payable a payout is draining, a price changing while customers are at the payment
 * page. Everything here goes through the application's own routes, so the composition under test
 * is the one Spring wires, not one assembled by hand.
 *
 * <h2>Conservation, read four ways, against records the ledger never sees</h2>
 *
 * <ol>
 *   <li><strong>Each payable, term by term.</strong> The merchant's own payable view - captured,
 *       fees, refunded, fees returned, paid out, other, position - and the ledger's derivation
 *       of the position must equal an <em>independent recomputation</em> from
 *       {@code payments.payment_attempt}, {@code payments.refund}, {@code merchant.merchant_payout}
 *       and each sale's <strong>pinned</strong> fee version, computed here with exact decimal
 *       arithmetic rather than by {@code FeeCalculation} ({@code INV-MER-02}, {@code INV-MER-03},
 *       {@code INV-HIST-04}).
 *   <li><strong>{@code PAYOUT_CLEARING}</strong> received exactly the completed payouts, per
 *       currency.
 *   <li><strong>{@code SETTLEMENT_CLEARING}</strong> holds exactly captured minus refunded, and
 *       <strong>{@code FEE_REVENUE}</strong> exactly fees minus fees returned, per currency.
 *   <li><strong>The tally</strong>: no payout or refund left in flight, no hold left standing, and
 *       no merchant paid out past what it was owed - a {@code RETURNED} payable never below zero,
 *       a {@code RETAINED} one never below minus the fee shares it kept ({@code INV-MER-07}).
 * </ol>
 *
 * <p><strong>The payable and {@code PAYOUT_CLEARING} readings are also taken every sweep round,
 * under load</strong>, inside one {@code REPEATABLE READ} snapshot: each capture, refund and payout
 * commits its posting with its own status, so the journal and the records agree exactly in any one
 * snapshot, however much is in flight. At rest the full reading adds every view term and the other
 * two clearing accounts.
 *
 * <p>The three clearing readings are summed over <strong>the storm's own entries</strong> - lines
 * whose entry touches a storm payable - never as before-and-after deltas. {@code P5-TST-003} could
 * take deltas because nothing else was posting; this suite runs inside an application whose own
 * schedulers may resolve another suite's leftovers into the same shared accounts mid-storm.
 *
 * <h2>The boundaries are contested, and that is a checked fact</h2>
 *
 * <p>Payers-out drain every payable, so refunds meet a payable with nothing available. The suite
 * asserts that {@code merchant.PayoutUnfunded}, {@code payments.RefundUnfunded} and
 * {@code payments.RefundExceedsCaptured} each actually occurred - a storm too polite to reach its
 * bounds proves less than the suites it composes (the {@code P4-TST-001} and {@code P5-TST-003}
 * lesson, applied at design time). Refunders find their payments <strong>in the database</strong>,
 * whatever state they are in, so a refund routinely arrives at a payment still being captured.
 *
 * <h2>A repricing mid-storm</h2>
 *
 * <p>Half-way through, every merchant's schedule gains a dearer version. Buyers confirm sessions
 * they opened two rounds earlier, so some sales are opened under the old price and captured under
 * the new one's reign. The oracle prices each sale by the version its pin names; a capture that priced
 * by the version in force would miss the reconciliation by the difference.
 *
 * <h2>Ten movers are ten instances</h2>
 *
 * <p>Every command is an HTTP request with its own pooled connection, so the movers contend in the
 * database exactly as ten instances would; the beans they share hold no correctness state, which
 * {@code NoSingleInstanceAssumptionRulesTest} enforces. Anything that is not a domain outcome - a
 * {@code 500}, a deadlock, an unexpected code - fails the storm rather than being retried away.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the merchant conservation storm (P6-TST-002)")
class MerchantConservationStormDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    /** Every sale is this, so the fee and every refund share can be reasoned about exactly. */
    private static final long SALE_MINOR = 10_00;

    /**
     * Refund amounts against a 10.00 sale. Full refunds drain, partials share a sale's budget,
     * and 12.00 can never be afforded, so the capture bound is contested by construction.
     */
    private static final long[] REFUND_MINOR = {3_00, 5_00, 10_00, 3_00, 12_00};

    /**
     * Payout amounts. Small ones drain each payable towards zero - which is what makes a refund
     * meet a payable that cannot fund it - and 500.00 is never affordable.
     */
    private static final long[] PAYOUT_MINOR = {3_00, 8_00, 15_00, 500_00};

    private static final int BUYERS = 3;
    private static final int REFUNDERS = 4;
    private static final int PAYERS_OUT = 3;

    /** How far back a refunder reaches: the newest payments, captured or not. */
    private static final int RECENT_PAYMENTS = 9;

    /** The floors that make "sustained" a fact rather than a word. */
    private static final int MIN_SWEEPS_UNDER_LOAD = 25;

    private static final int MIN_COMMANDS_UNDER_LOAD = 240;

    private static final int COMMANDS_BETWEEN_SWEEPS =
            MIN_COMMANDS_UNDER_LOAD / MIN_SWEEPS_UNDER_LOAD;

    /** The round at which every merchant is repriced - half-way, so both prices see traffic. */
    private static final int REPRICE_AT_ROUND = MIN_SWEEPS_UNDER_LOAD / 2;

    private static final Pattern FIELD = Pattern.compile("\"%s\":\"([^\"]*)\"");

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final TrialBalance trialBalance = new TrialBalance();
    private final ProjectionVerification verification =
            new ProjectionVerification(new JdbcBalanceDerivation());

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        stubTheProviders();
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @org.junit.jupiter.api.AfterEach
    void closeClient() {
        http.close();
    }

    /**
     * Every operation answered, each with a reference of its own: every provider-reference
     * column is {@code UNIQUE}, so a fixed body would make the second concurrent operation a
     * {@code 23505} - a harness limit, not a finding ({@code P5-TST-003}).
     */
    private static void stubTheProviders() {
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "psp_a");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_r");
        provider.succeedsWith(
                SimulatedPayoutProvider.PAYOUTS_PATH,
                200,
                "{\"status\":\"paid\",\"reference\":\"po-{{request.headers.Idempotency-Key}}\"}");
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // Generous timeouts, deliberately: a provider that answers late under load would turn a
        // payout or refund UNKNOWN, and ambiguity under load is P5-TST-001's subject, not this
        // one's. A payment that does go UNKNOWN is still caught - by the tally's "nothing left in
        // flight" - rather than silently absorbed.
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT10S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT10S");
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
        registry.add("finapp.merchant.payout.provider.timeout", () -> "PT10S");
    }

    // ----------------------------------------------------------------- the storm

    @Test
    @DisplayName("ten movers checking out, refunding and paying out on three merchants in three"
            + " currencies: every sweep balances, every payable explains itself, no merchant is"
            + " paid past what it is owed, and every payable reconciles term by term to the"
            + " records the ledger never sees")
    void conservationHoldsThroughTheMerchantStorm() throws Exception {
        String administrator = operatorSession(RoleName.MERCHANT_ADMINISTRATOR);
        String ledgerOperator = operatorSession(RoleName.LEDGER_OPERATOR);
        List<Tenant> tenants =
                List.of(
                        tenant(administrator, "EUR", "RETAINED"),
                        tenant(administrator, "GBP", "RETURNED"),
                        tenant(administrator, "USD", "RETAINED"));
        List<Customer> customers = List.of(payingCustomer(), payingCustomer());

        // PRIMED: one sale per merchant before the sweeps begin, so the first round reads a
        // journal in all three currencies and the refunders and payers-out have something to
        // contend for from their first turn (a sweep over an empty set asserts nothing).
        for (Tenant tenant : tenants) {
            assertThat(purchase(tenant, customers.get(0))).isEqualTo("COMPLETED");
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong committed = new AtomicLong(tenants.size());
        Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(BUYERS + REFUNDERS + PAYERS_OUT);
        List<Future<Void>> movers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            movers.add(pool.submit(buyer(tenants, customers, i, stop, committed, outcomes)));
        }
        for (int i = 0; i < REFUNDERS; i++) {
            movers.add(pool.submit(refunder(tenants, ledgerOperator, i, stop, committed, outcomes)));
        }
        for (int i = 0; i < PAYERS_OUT; i++) {
            movers.add(pool.submit(payerOut(tenants, i, stop, committed, outcomes)));
        }

        long rounds = 0;
        Instant repricedAt = null;
        Map<String, String> repriced = new HashMap<>();
        try (Connection own = DatabaseRoles.application()) {
            // The sweeper ends the storm, so the overlap with live traffic is the loop's exit
            // condition rather than luck (no sleeps anywhere). The cap is a FAILURE bound: if
            // the movers have died, fall through so mover.get() reports why.
            while ((rounds < MIN_SWEEPS_UNDER_LOAD || committed.get() < MIN_COMMANDS_UNDER_LOAD)
                    && rounds < 10_000) {
                rounds++;
                sweep(own, tenants, rounds);

                if (rounds == REPRICE_AT_ROUND) {
                    for (Tenant tenant : tenants) {
                        repriced.put(tenant.id(), reprice(administrator, tenant));
                    }
                    repricedAt = Instant.now(CLOCK);
                }

                // Wait on the CONDITION - more committed commands - so the next sweep provably
                // straddles live traffic. The deadline is a FAILURE bound.
                long required =
                        Math.min(rounds * COMMANDS_BETWEEN_SWEEPS, MIN_COMMANDS_UNDER_LOAD);
                long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
                while (committed.get() < required && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
            }
        } finally {
            stop.set(true);
            pool.shutdown();
        }
        for (Future<Void> mover : movers) {
            mover.get(); // Propagates any outcome a mover could not classify as a domain one.
        }

        assertThat(rounds).as("the sweeps really ran under load")
                .isGreaterThanOrEqualTo(MIN_SWEEPS_UNDER_LOAD);
        assertThat(committed.get())
                .as("the storm really was a storm - outcomes: %s", outcomes)
                .isGreaterThanOrEqualTo(MIN_COMMANDS_UNDER_LOAD);

        // THE BOUNDARIES WERE CONTESTED, as checked facts: a storm whose amounts never reached
        // a bound proves less than the suites it composes.
        assertThat(outcomes.keySet())
                .as("a drained payable refused a payout (INV-MER-05) - outcomes: %s", outcomes)
                .contains("payout:merchant.PayoutUnfunded");
        assertThat(outcomes.keySet())
                .as("a drained payable refused a refund (INV-MER-07) - outcomes: %s", outcomes)
                .contains("refund:payments.RefundUnfunded");
        assertThat(outcomes.keySet())
                .as("the capture bound refused a refund (INV-PAY-05) - outcomes: %s", outcomes)
                .contains("refund:payments.RefundExceedsCaptured");
        assertThat(outcomes.keySet())
                .as("duplicates were delivered and were harmless - outcomes: %s", outcomes)
                .contains("refund:replayed", "payout:replayed");

        try (Connection app = DatabaseRoles.application()) {
            long straddled = 0;
            for (Tenant tenant : tenants) {
                straddled += reconcile(app, tenant, repriced.get(tenant.id()), repricedAt);
            }
            assertThat(straddled)
                    .as("sales priced under the old version were captured after the repricing -"
                            + " the customer at the payment page while the price changed")
                    .isPositive();
            TrialBalance.Report settled = trialBalance.sweep(app);
            assertThat(settled.outOfBalance()).as("settled: zero per currency").isEmpty();
            for (Tenant tenant : tenants) {
                assertThat(verification.verdictOf(app, tenant.payable()))
                        .as("settled: %s's payable projection equals replay-from-zero",
                                tenant.currency())
                        .isEqualTo(Verdict.CLEAN);
            }
        }
    }

    // ----------------------------------------------------------------- the sweeps

    /**
     * One round of the sweeper: the trial balance, each payable's projection verdict, each
     * payable's debt bound read in ONE snapshot with the refunds that bound it, and each
     * merchant's own view of its payable explaining itself.
     */
    private void sweep(Connection own, List<Tenant> tenants, long round) throws Exception {
        TrialBalance.Report trial = trialBalance.sweep(own);
        assertThat(trial.outOfBalance())
                .as("mid-storm trial balance, round %s: zero per currency while money enters by"
                        + " capture and leaves by refund and payout (INV-ACC-01)", round)
                .isEmpty();
        assertThat(trial.currenciesVerified())
                .as("the sweep sees all three currencies, or it asserts less than it claims")
                .isGreaterThanOrEqualTo(3);

        for (Tenant tenant : tenants) {
            assertThat(verification.verdictOf(own, tenant.payable()))
                    .as("mid-storm projection verdict for %s, round %s: IN_FLIGHT is legitimate,"
                            + " DRIFTING is not", tenant.currency(), round)
                    .isNotEqualTo(Verdict.DRIFTING);
        }

        // RECONCILED UNDER LOAD, not only bounded. A capture's posting commits with its CAPTURED
        // status, a refund's with COMPLETED, a payout's with its completion - so inside ONE
        // snapshot the journal and the records it must explain agree exactly, however much is
        // in flight around them. REPEATABLE READ fixes that snapshot at the first statement.
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            for (Tenant tenant : tenants) {
                long settled = settledOf(snapshot, tenant);
                Books books = books(snapshot, tenant);
                assertThat(settled)
                        .as("mid-storm, round %s: %s's payable equals captured - fees - refunded"
                                + " + returned - paid out, recomputed in the SAME snapshot from"
                                + " records the ledger never sees (INV-MER-02)",
                                round, tenant.currency())
                        .isEqualTo(books.owed());
                assertThat(net(snapshot, tenant, "PAYOUT_CLEARING", "CREDIT"))
                        .as("mid-storm, round %s: %s's PAYOUT_CLEARING holds exactly the"
                                + " completed payouts", round, tenant.currency())
                        .isEqualTo(books.paidOut());
                assertThat(settled)
                        .as("mid-storm, round %s: %s's payable is never below what INV-MER-07"
                                + " allows - minus the fee shares kept on refunded sales, zero"
                                + " under RETURNED - so no merchant is paid past what it is owed",
                                round, tenant.currency())
                        .isGreaterThanOrEqualTo(-books.allowance());
            }
            snapshot.commit();
        }

        for (Tenant tenant : tenants) {
            String view = get("/v1/merchant/payable", tenant.key()).body();
            assertThat(explainsItself(view))
                    .as("mid-storm, round %s: %s's payable view explains itself exactly - %s",
                            round, tenant.currency(), view)
                    .isTrue();
        }
    }

    // ----------------------------------------------------------------- the movers

    /**
     * Customers buying: opening a session, then confirming the one they opened two rounds
     * earlier, so sessions routinely straddle the repricing - opened under one price, captured
     * under the next one's reign.
     */
    private Callable<Void> buyer(
            List<Tenant> tenants,
            List<Customer> customers,
            int index,
            AtomicBoolean stop,
            AtomicLong committed,
            Map<String, AtomicLong> outcomes) {
        return () -> {
            Deque<String> opened = new ArrayDeque<>();
            int round = 0;
            while (!stop.get()) {
                Tenant tenant = tenants.get((index + round) % tenants.size());
                Customer customer = customers.get((index + round) % customers.size());
                round++;
                HttpResponse<String> created = openSession(tenant);
                if (created.statusCode() != 201) {
                    throw unexpected("open", created, outcomes);
                }
                opened.addLast(field(created.body(), "sessionToken"));
                committed.incrementAndGet();
                count(outcomes, "session:opened");
                if (opened.size() < 3) {
                    continue;
                }
                HttpResponse<String> confirmed = confirm(customer, opened.removeFirst());
                if (confirmed.statusCode() != 200
                        || !"COMPLETED".equals(field(confirmed.body(), "status"))) {
                    throw unexpected("confirm", confirmed, outcomes);
                }
                committed.incrementAndGet();
                count(outcomes, "session:paid");
            }
            return null;
        };
    }

    /**
     * An operator refunding recent sales, found IN THE DATABASE whatever state they are in, and
     * replaying every refund that succeeded once, with the same key.
     */
    private Callable<Void> refunder(
            List<Tenant> tenants,
            String operator,
            int index,
            AtomicBoolean stop,
            AtomicLong committed,
            Map<String, AtomicLong> outcomes) {
        return () -> {
            int round = 0;
            while (!stop.get()) {
                List<String[]> recent = recentPayments(tenants);
                if (recent.isEmpty()) {
                    Thread.onSpinWait();
                    continue;
                }
                String[] subject = recent.get((index + round) % recent.size());
                long amount = REFUND_MINOR[round % REFUND_MINOR.length];
                round++;
                String key = UUID.randomUUID().toString();
                HttpResponse<String> answer = refund(subject[0], subject[1], amount, operator, key);
                String outcome = refundOutcome(answer);
                if (outcome == null) {
                    throw unexpected("refund", answer, outcomes);
                }
                committed.incrementAndGet();
                count(outcomes, outcome);
                if (answer.statusCode() == 201) {
                    // A DUPLICATE, delivered on purpose: the same command under the same key must
                    // converge on the same refund, and a second effect would show in every
                    // reading below.
                    HttpResponse<String> replay =
                            refund(subject[0], subject[1], amount, operator, key);
                    assertThat(replay.statusCode()).as(replay.body()).isEqualTo(201);
                    assertThat(field(replay.body(), "id")).isEqualTo(field(answer.body(), "id"));
                    count(outcomes, "refund:replayed");
                }
            }
            return null;
        };
    }

    /** Merchants paying themselves out, draining each payable, replaying every success once. */
    private Callable<Void> payerOut(
            List<Tenant> tenants,
            int index,
            AtomicBoolean stop,
            AtomicLong committed,
            Map<String, AtomicLong> outcomes) {
        return () -> {
            int round = 0;
            while (!stop.get()) {
                Tenant tenant = tenants.get((index + round) % tenants.size());
                long amount = PAYOUT_MINOR[round % PAYOUT_MINOR.length];
                round++;
                String key = UUID.randomUUID().toString();
                HttpResponse<String> answer = payout(tenant, amount, key);
                if (answer.statusCode() == 201) {
                    committed.incrementAndGet();
                    count(outcomes, "payout:" + field(answer.body(), "status"));
                    HttpResponse<String> replay = payout(tenant, amount, key);
                    assertThat(replay.statusCode()).as(replay.body()).isEqualTo(201);
                    assertThat(field(replay.body(), "id")).isEqualTo(field(answer.body(), "id"));
                    count(outcomes, "payout:replayed");
                } else if (answer.statusCode() == 409
                        && "merchant.PayoutUnfunded".equals(code(answer))) {
                    committed.incrementAndGet();
                    count(outcomes, "payout:merchant.PayoutUnfunded");
                } else {
                    throw unexpected("payout", answer, outcomes);
                }
            }
            return null;
        };
    }

    /** A refund's answer as a domain outcome, or null when it is not one. */
    private static String refundOutcome(HttpResponse<String> answer) {
        if (answer.statusCode() == 201) {
            return "refund:" + field(answer.body(), "status");
        }
        String code = code(answer);
        boolean domain =
                (answer.statusCode() == 409
                                && ("payments.RefundUnfunded".equals(code)
                                        || "payments.NotRefundable".equals(code)))
                        || (answer.statusCode() == 422
                                && "payments.RefundExceedsCaptured".equals(code));
        return domain ? "refund:" + code : null;
    }

    private static AssertionError unexpected(
            String what, HttpResponse<String> answer, Map<String, AtomicLong> outcomes) {
        count(outcomes, what + ":" + answer.statusCode() + ":" + code(answer));
        return new AssertionError(
                "not a domain outcome - " + what + " answered " + answer.statusCode() + ": "
                        + answer.body() + " (outcomes so far: " + outcomes + ")");
    }

    private static void count(Map<String, AtomicLong> outcomes, String key) {
        outcomes.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    // ----------------------------------------------------------------- the readings

    /**
     * The merchant's books against the records the ledger never sees: every term of its payable
     * view, the ledger's own derivation, the three clearing accounts' share of the storm's
     * entries, the tally and the debt bound.
     *
     * @return how many of the tenant's sales were priced before the repricing and captured after
     */
    private long reconcile(Connection app, Tenant tenant, String newVersion, Instant repricedAt)
            throws Exception {
        Books books = books(app, tenant);
        List<Sale> sales = books.sales();
        long gross = books.gross();
        long fees = books.fees();
        long refunded = books.refunded();
        long returned = books.returned();
        long paidOut = books.paidOut();
        long owed = books.owed();
        String code = tenant.currency();

        // Reading 1: the merchant's own view, term by term, and the ledger's derivation.
        String view = get("/v1/merchant/payable", tenant.key()).body();
        assertThat(field(view, "captured")).as("%s captured", code).isEqualTo(decimal(gross));
        assertThat(field(view, "fees")).as("%s fees, each by its PIN", code).isEqualTo(decimal(fees));
        assertThat(field(view, "refunded")).as("%s refunded", code).isEqualTo(decimal(refunded));
        assertThat(field(view, "feesReturned"))
                .as("%s fees returned (RETURNED only)", code)
                .isEqualTo(decimal(returned));
        assertThat(field(view, "paidOut")).as("%s paid out", code).isEqualTo(decimal(paidOut));
        assertThat(field(view, "other")).as("%s nothing unexplained", code).isEqualTo(decimal(0));
        assertThat(field(view, "position"))
                .as("%s: the payable is captured - fees - refunded + returned - paid out, to the"
                        + " minor unit, recomputed from records the ledger never sees (INV-MER-02)",
                        code)
                .isEqualTo(decimal(owed));
        assertThat(settledOf(app, tenant)).as("%s: the ledger's own derivation", code)
                .isEqualTo(owed);

        // Readings 2 and 3: the counterparts, summed over the storm's OWN entries.
        assertThat(net(app, tenant, "PAYOUT_CLEARING", "CREDIT"))
                .as("%s: PAYOUT_CLEARING received exactly the completed payouts", code)
                .isEqualTo(paidOut);
        assertThat(net(app, tenant, "SETTLEMENT_CLEARING", "DEBIT"))
                .as("%s: clearing holds exactly captured - refunded - the capture pair closed",
                        code)
                .isEqualTo(gross - refunded);
        assertThat(net(app, tenant, "FEE_REVENUE", "CREDIT"))
                .as("%s: revenue earned exactly the fees less those returned", code)
                .isEqualTo(fees - returned);

        // Reading 4: the tally.
        UUID merchant = UUID.fromString(tenant.id());
        assertThat(sum(app, "SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?"
                        + " AND status NOT IN ('COMPLETED', 'FAILED')", merchant))
                .as("%s: no payout left DISPATCHED or UNKNOWN", code)
                .isZero();
        assertThat(sum(app, "SELECT count(*) FROM payments.refund refund"
                        + " JOIN payments.payment_attempt attempt ON attempt.id = refund.attempt_id"
                        + " JOIN payments.payment_intent intent ON intent.id = attempt.intent_id"
                        + " WHERE intent.credit_account_id = ?"
                        + " AND refund.status NOT IN ('COMPLETED', 'FAILED')",
                        tenant.payable().value()))
                .as("%s: no refund left in flight", code)
                .isZero();
        assertThat(sum(app, "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?"
                        + " AND status = 'ACTIVE'", tenant.payable().value()))
                .as("%s: no hold left standing - every payout and refund released or posted its"
                        + " reservation", code)
                .isZero();
        assertThat(owed)
                .as("%s: no merchant paid past what it is owed (INV-MER-07)", code)
                .isGreaterThanOrEqualTo(-books.allowance());
        assertThat(sum(app, "SELECT count(*) FROM checkout.checkout_order WHERE merchant_ref = ?",
                        merchant))
                .as("%s: every captured sale produced its order - landed money is never"
                        + " orphaned (INV-MER-06)", code)
                .isEqualTo(sales.size());

        // The storm really moved each flow, under both prices.
        assertThat(sales.size()).as("%s sales", code).isGreaterThanOrEqualTo(10);
        assertThat(refunded).as("%s refunds completed", code).isPositive();
        assertThat(paidOut).as("%s payouts completed", code).isPositive();
        assertThat(sales)
                .as("%s: sales priced under the new version", code)
                .anyMatch(sale -> sale.version().equals(newVersion));
        return sales.stream()
                .filter(sale -> !sale.version().equals(newVersion)
                        && sale.confirmedAt().isAfter(repricedAt))
                .count();
    }

    /**
     * Every captured sale of the tenant, priced here from its pin: the fee by the pinned
     * version's own terms, and the fee share its completed refunds reach - returned to the
     * merchant under RETURNED, kept by the platform under RETAINED. Exact decimal arithmetic, not
     * {@code FeeCalculation}, so the reading is independent of the code it checks.
     */
    private static List<Sale> sales(Connection app, Tenant tenant) throws SQLException {
        List<Sale> sales = new ArrayList<>();
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT attempt.captured_amount_minor, version.id, version.rate,"
                                + " version.fixed_amount_minor, version.rounding_policy,"
                                + " version.refund_fee_policy, intent.created_at,"
                                + " (SELECT COALESCE(SUM(refund.amount_minor), 0)"
                                + "    FROM payments.refund refund"
                                + "   WHERE refund.attempt_id = attempt.id"
                                + "     AND refund.status = 'COMPLETED')"
                                + " FROM payments.payment_attempt attempt"
                                + " JOIN payments.payment_intent intent"
                                + "   ON intent.id = attempt.intent_id"
                                + " JOIN merchant.payment_fee_pin pin"
                                + "   ON pin.payment_intent_ref = intent.id"
                                + " JOIN merchant.fee_schedule_version version"
                                + "   ON version.id = pin.fee_schedule_version_id"
                                + " WHERE intent.credit_account_id = ?"
                                + "   AND attempt.status = 'CAPTURED'")) {
            read.setObject(1, tenant.payable().value());
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    long gross = rows.getLong(1);
                    RoundingMode rounding = RoundingPolicy.ofName(rows.getString(5)).mode();
                    long fee =
                            BigDecimal.valueOf(gross)
                                            .multiply(rows.getBigDecimal(3))
                                            .setScale(0, rounding)
                                            .longValueExact()
                                    + rows.getLong(4);
                    long refunded = rows.getLong(8);
                    long share =
                            BigDecimal.valueOf(fee)
                                    .multiply(BigDecimal.valueOf(refunded))
                                    .divide(BigDecimal.valueOf(gross), 0, rounding)
                                    .longValueExact();
                    boolean returnedPolicy = "RETURNED".equals(rows.getString(6));
                    sales.add(
                            new Sale(
                                    gross,
                                    fee,
                                    refunded,
                                    returnedPolicy ? share : 0L,
                                    returnedPolicy ? 0L : share,
                                    rows.getString(2),
                                    rows.getTimestamp(7).toInstant()));
                }
            }
        }
        return sales;
    }

    /**
     * What the records say a merchant is owed, read on {@code app} - one snapshot when the caller
     * holds one: every captured sale priced by its pin, the completed refunds, the completed
     * payouts.
     */
    private static Books books(Connection app, Tenant tenant) throws SQLException {
        List<Sale> sales = sales(app, tenant);
        long paidOut =
                sum(app, "SELECT COALESCE(SUM(amount_minor), 0) FROM merchant.merchant_payout"
                        + " WHERE merchant_id = ? AND status = 'COMPLETED'",
                        UUID.fromString(tenant.id()));
        return new Books(sales, paidOut);
    }

    /** The records' account of one payable. */
    private record Books(List<Sale> sales, long paidOut) {
        long gross() {
            return sales.stream().mapToLong(Sale::gross).sum();
        }

        long fees() {
            return sales.stream().mapToLong(Sale::fee).sum();
        }

        long refunded() {
            return sales.stream().mapToLong(Sale::refunded).sum();
        }

        long returned() {
            return sales.stream().mapToLong(Sale::returned).sum();
        }

        /** The debt the payable may carry: the fee shares kept on refunded sales. */
        long allowance() {
            return sales.stream().mapToLong(Sale::retained).sum();
        }

        /** captured - fees - refunded + returned - paid out. */
        long owed() {
            return gross() - fees() - refunded() + returned() - paidOut;
        }
    }

    /** A counterpart's net movement - in its own normal direction - across the storm's entries. */
    private static long net(Connection app, Tenant tenant, String purpose, String normal)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT COALESCE(SUM(CASE WHEN line.direction = ? THEN line.amount_minor"
                                + " ELSE -line.amount_minor END), 0)"
                                + " FROM ledger.journal_line line"
                                + " JOIN ledger.ledger_account account"
                                + "   ON account.id = line.ledger_account_id"
                                + " WHERE account.purpose = ?"
                                + "   AND line.entry_id IN (SELECT mine.entry_id"
                                + "     FROM ledger.journal_line mine"
                                + "    WHERE mine.ledger_account_id = ?)")) {
            read.setString(1, normal);
            read.setString(2, purpose);
            read.setObject(3, tenant.payable().value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBigDecimal(1).longValueExact();
            }
        }
    }

    private static long settledOf(Connection app, Tenant tenant) {
        return new JdbcBalanceDerivation()
                .derive(app, tenant.payable(), AsOf.latest())
                .settled()
                .minorUnits();
    }

    /** The storm's newest payments, WHATEVER their state - an operator's view of the world. */
    private static List<String[]> recentPayments(List<Tenant> tenants) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id, currency FROM payments.payment_intent"
                                        + " WHERE credit_account_id = ANY (?)"
                                        + " ORDER BY created_at DESC LIMIT ?")) {
            read.setArray(
                    1,
                    app.createArrayOf(
                            "uuid",
                            tenants.stream().map(tenant -> tenant.payable().value()).toArray()));
            read.setInt(2, RECENT_PAYMENTS);
            List<String[]> found = new ArrayList<>();
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    found.add(new String[] {rows.getString(1), rows.getString(2).strip()});
                }
            }
            return found;
        }
    }

    /** position == captured - fees - refunded + feesReturned - paidOut + other, from the body. */
    private static boolean explainsItself(String body) {
        BigDecimal position = new BigDecimal(field(body, "position"));
        BigDecimal terms =
                new BigDecimal(field(body, "captured"))
                        .subtract(new BigDecimal(field(body, "fees")))
                        .subtract(new BigDecimal(field(body, "refunded")))
                        .add(new BigDecimal(field(body, "feesReturned")))
                        .subtract(new BigDecimal(field(body, "paidOut")))
                        .add(new BigDecimal(field(body, "other")));
        return position.compareTo(terms) == 0;
    }

    // ----------------------------------------------------------------- the world

    /** A trading merchant: its key, currency, refund policy, payable and schedule. */
    private record Tenant(
            String id,
            String key,
            String currency,
            String policy,
            LedgerAccountId payable,
            String schedule) {}

    private record Customer(String token, String methodId) {}

    /** One captured sale, priced by its pin. */
    private record Sale(
            long gross,
            long fee,
            long refunded,
            long returned,
            long retained,
            String version,
            Instant confirmedAt) {}

    /**
     * An onboarded, keyed merchant settling in {@code currency}, priced 2.9% + 0.30 under
     * {@code policy}, with an effective payout destination. The destination is a fixture: its
     * four-eyes flow is {@code PayoutDestinationDatabaseTest}'s subject.
     */
    private Tenant tenant(String operator, String currency, String policy) throws Exception {
        UUID party = IDS.next();
        raw("INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                + " (?, 'ORGANISATION', 'Storm Holdings', now())", party);
        raw("INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                + " VALUES (?, ?, 'ACTIVE', now() - interval '1 hour', now())", IDS.next(), party);
        HttpResponse<String> onboarded =
                post(
                        "/v1/operator/merchants",
                        "{\"partyId\":\"" + party + "\",\"legalName\":\"Storm " + currency
                                + " GmbH\",\"displayName\":\"Storm\",\"settlementCurrency\":\""
                                + currency + "\"}",
                        operator,
                        someKey());
        assertThat(onboarded.statusCode()).as(onboarded.body()).isEqualTo(201);
        String merchant = field(onboarded.body(), "merchantId");

        HttpResponse<String> schedule =
                post(
                        "/v1/operator/fee-schedules",
                        "{\"name\":\"Storm " + UUID.randomUUID() + "\",\"currency\":\"" + currency
                                + "\"}",
                        operator,
                        null);
        assertThat(schedule.statusCode()).as(schedule.body()).isEqualTo(201);
        String scheduleId = field(schedule.body(), "feeScheduleId");
        addVersion(operator, scheduleId, "0.029", 30, policy);
        HttpResponse<String> assigned =
                put(
                        "/v1/operator/merchants/" + merchant + "/fee-schedule",
                        "{\"feeScheduleId\":\"" + scheduleId + "\",\"reason\":\"standard terms\"}",
                        operator);
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);

        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + merchant + "/api-keys", null, operator, someKey());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        String key = field(issued.body(), "keyId") + "." + field(issued.body(), "secret");

        raw("INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until, effective_at) VALUES"
                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '4 days',"
                        + " 'fixture', 'fixture-b', now() - interval '4 days', now() - interval"
                        + " '1 day', now() - interval '1 hour')",
                IDS.next(),
                UUID.fromString(merchant),
                "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

        UUID payable;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM ledger.ledger_account WHERE owner_ref = ? AND"
                                        + " purpose = 'MERCHANT_PAYABLE'")) {
            read.setObject(1, UUID.fromString(merchant));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                payable = row.getObject(1, UUID.class);
            }
        }
        return new Tenant(merchant, key, currency, policy, LedgerAccountId.of(payable), scheduleId);
    }

    /** A dearer version, effective at once: 3.9% + 0.50, the same refund policy. */
    private String reprice(String operator, Tenant tenant) throws Exception {
        return addVersion(operator, tenant.schedule(), "0.039", 50, tenant.policy());
    }

    private String addVersion(
            String operator, String schedule, String rate, long fixedMinor, String policy)
            throws Exception {
        HttpResponse<String> version =
                post(
                        "/v1/operator/fee-schedules/" + schedule + "/versions",
                        "{\"rate\":" + rate + ",\"fixedAmountMinor\":" + fixedMinor
                                + ",\"roundingPolicy\":\"HALF_EVEN\",\"refundFeePolicy\":\""
                                + policy + "\",\"reason\":\"storm pricing\"}",
                        operator,
                        null);
        assertThat(version.statusCode()).as(version.body()).isEqualTo(201);
        return field(version.body(), "feeScheduleVersionId");
    }

    /** A verified customer with an attached instrument, able to pay in any currency. */
    private Customer payingCustomer() throws Exception {
        String login = "storm." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        raw("UPDATE party.customer SET status = 'ACTIVE', status_changed_at ="
                + " GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id"
                + " FROM identity.identity WHERE login_identifier = ?)", login);
        String token = field(authenticate(login).body(), "sessionToken");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached =
                post(
                        "/v1/me/payment-methods",
                        "{\"clientToken\":\"ctok_" + suffix + "\"}",
                        token,
                        null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        return new Customer(token, field(attached.body(), "id"));
    }

    /** One completed sale: open, confirm, capture. */
    private String purchase(Tenant tenant, Customer customer) throws Exception {
        HttpResponse<String> created = openSession(tenant);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return field(confirm(customer, field(created.body(), "sessionToken")).body(), "status");
    }

    private String operatorSession(RoleName role) throws Exception {
        String login = "storm.ops." + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        assertThat(register(login).statusCode()).isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return field(authenticate(login).body(), "sessionToken");
    }

    // ----------------------------------------------------------------- the routes

    private HttpResponse<String> openSession(Tenant tenant) throws Exception {
        return post(
                "/v1/checkout/sessions",
                "{\"amountMinor\":" + SALE_MINOR + ",\"currency\":\"" + tenant.currency()
                        + "\",\"lineSummary\":\"A storm in a teacup\"}",
                tenant.key(),
                someKey());
    }

    private HttpResponse<String> confirm(Customer customer, String sessionToken) throws Exception {
        return post(
                "/v1/checkout/sessions/confirmation",
                "{\"sessionToken\":\"" + sessionToken + "\",\"paymentMethodId\":\""
                        + customer.methodId() + "\"}",
                customer.token(),
                null);
    }

    private HttpResponse<String> refund(
            String intent, String currency, long amountMinor, String operator, String key)
            throws Exception {
        return post(
                "/v1/payments/" + intent + "/refund",
                "{\"amount\":\"" + decimal(amountMinor) + "\",\"currency\":\"" + currency
                        + "\",\"reason\":\"storm refund\"}",
                operator,
                key);
    }

    private HttpResponse<String> payout(Tenant tenant, long amountMinor, String key)
            throws Exception {
        return post(
                "/v1/merchant/payouts",
                "{\"amount\":\"" + decimal(amountMinor) + "\",\"currency\":\"" + tenant.currency()
                        + "\"}",
                tenant.key(),
                key);
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    // ----------------------------------------------------------------- HTTP

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        return send("POST", path, body, token, key);
    }

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        return send("PUT", path, body, token, null);
    }

    private HttpResponse<String> send(
            String method, String path, String body, String token, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String body, String name) {
        Matcher found = Pattern.compile(String.format(FIELD.pattern(), name)).matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }

    /** The problem body's code, or empty when the answer carries none. */
    private static String code(HttpResponse<String> answer) {
        Matcher found = Pattern.compile("\"code\":\"([^\"]*)\"").matcher(answer.body());
        return found.find() ? found.group(1) : "";
    }

    /** Minor units as the API's decimal string - every storm currency has two minor units. */
    private static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    // ----------------------------------------------------------------- SQL

    private static void raw(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long sum(Connection app, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBigDecimal(1).longValueExact();
            }
        }
    }
}
