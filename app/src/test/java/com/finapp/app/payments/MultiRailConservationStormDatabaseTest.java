package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.ProjectionVerification.Verdict;
import com.finapp.ledger.TrialBalance;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.PayInResolution;
import com.finapp.payments.PaymentSweeper;
import com.finapp.payments.ReturnResolution;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.WithdrawalResolution;
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
import java.nio.charset.StandardCharsets;
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
import java.util.Base64;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * <strong>The multi-rail conservation storm</strong> (`P7-TST-001`) — Phase 7's composition
 * demonstration: every rail and every money path at once, on shared wallets and payables,
 * reconciled to the minor unit in every sweep round and again at rest. {@code P6-TST-002}'s
 * discipline, across rails.
 *
 * <h2>Why a storm, when every rail is proven alone</h2>
 *
 * <p>Each Phase 7 path has its own suite: the card rail's capture and void, the instant rail's
 * withdrawal and pay-in, the return, the wallet paying a checkout on the book rail, the
 * chargeback and its combined bound. None has run beside the others on the same money, and that
 * is the only place mechanisms designed apart meet: a withdrawal completing while a wallet
 * payment and a transfer drain the same wallet, a chargeback racing a refund for one sale's
 * headroom, a pay-in's duplicate callbacks landing while two resolvers sweep, a capture whose
 * answer was lost resolved by a query while a refund arrives at the same attempt. Everything goes
 * through the application's own routes and doors, so the composition under test is the one
 * Spring wires.
 *
 * <h2>Conservation across rails, read against records the ledger never sees</h2>
 *
 * <p>Every round takes ONE {@code REPEATABLE READ} snapshot — every completion commits its
 * posting with its own status, so the journal and the records agree exactly inside any one
 * snapshot however much is in flight — and reconciles:
 *
 * <ol>
 *   <li><strong>Each external rail's clearing position against that rail's own records</strong>
 *       ({@code INV-RAIL-04}'s Verify line): {@code SETTLEMENT_CLEARING} holds exactly the card
 *       rail's captures less its completed refunds, the chargebacks the network took and has not
 *       returned, and the dispute fees; {@code INSTANT_CLEARING} exactly the instant rail's
 *       executions less its completed withdrawals and returns. A posting on another rail's
 *       position misses both readings.
 *   <li><strong>The dispute accounts</strong>: {@code CHARGEBACK_RECOVERABLE} plus
 *       {@code DISPUTE_COSTS} hold exactly each standing or lost chargeback's excess over the
 *       share its counterparty bears, plus the fees ({@code INV-DSP-01}'s split, as money).
 *   <li><strong>Each wallet</strong> against an independent book: card top-ups captured, pay-ins
 *       executed, their completed refunds, completed withdrawals, executed wallet payments, their
 *       book refunds, transfers each way — and its active holds against exactly the debits in
 *       flight, and its available balance never below zero ({@code INV-BAL-04},
 *       {@code INV-CON-01}).
 *   <li><strong>Each payable</strong> against its sales on all three rails, each priced from its
 *       pin with exact decimal arithmetic (never {@code FeeCalculation}), less completed refunds
 *       and the fee shares a {@code RETURNED} policy gives back, less the chargeback shares
 *       standing against it; and {@code FEE_REVENUE} against the same arithmetic.
 *   <li><strong>The combined bound</strong> on every storm card attempt: non-failed refunds plus
 *       attributed chargeback shares never exceed what the capture credited ({@code INV-DSP-01}).
 *   <li><strong>The trial balance</strong>, zero per currency ({@code INV-ACC-01}).
 * </ol>
 *
 * <p>The clearing and dispute readings are summed over <strong>the storm's own entries</strong> —
 * lines of an entry touching a storm wallet or payable, and the dispute entries keyed by a storm
 * dispute (the external fact touches only platform accounts) — never as before-and-after deltas:
 * the shared accounts carry every other suite's history.
 *
 * <h2>The gate's four scenarios, counted rather than argued</h2>
 *
 * <ol>
 *   <li><strong>The provider acts and the answer is lost</strong> — for a card capture (every
 *       top-up of 20.07), a withdrawal (every one of 12.07) and a pay-in (every one of 15.07,
 *       whose callback never comes): each goes {@code UNKNOWN} or waits, the provider answers the
 *       inquiry by our reference, and at rest each is resolved to exactly one entry. The
 *       providers answer TRUTHFULLY - approved when the request reached them, unrecognised once
 *       the traffic stops when it never did - so an answer a transport failure lost under load is
 *       resolved exactly as a chosen one is.
 *   <li><strong>Two instances attempt one operation</strong> — ten-way bursts on a confirmation,
 *       a withdrawal, a refund and a chargeback notification, and two resolvers sweeping at
 *       once: one effect each, counted in the tables.
 *   <li><strong>One callback, many deliveries</strong> — every instant confirmation delivered
 *       three times (twice under one event id, once under a fresh one), every clearing notice
 *       twice, every chargeback notification three times: one effect each.
 *   <li><strong>A hold released while other debits run</strong> — withdrawals completing while
 *       wallet payments, transfers and refunds of top-ups drain the same four wallets: the
 *       availability reading holds in every snapshot.
 * </ol>
 *
 * <h2>The boundaries are contested, and that is a checked fact</h2>
 *
 * <p>Debits outrun credits on purpose, and some of every kind of wallet debit - a withdrawal, a
 * transfer, a wallet checkout - asks for more than any storm wallet holds, so each refusal is made
 * to happen rather than hoped for. Refunders find payments in the database whatever their state,
 * so refunds arrive at captures still resolving and at sales already charged back. On the dispute
 * desk's own merchant, whose sales no other mover touches, one choreographed pair refunds a sale
 * before its chargeback (the whole chargeback excess - the gate's no-double-debit criterion) and
 * charges one back before its refund (refused past what remains), and every race burst sets four
 * partial refunds against a full chargeback for one sale's headroom. The suite asserts each
 * refusal actually occurred ({@code P4-TST-001} and {@code P5-TST-003}'s lesson).
 *
 * <h2>The meters, a second tally</h2>
 *
 * <p>At rest, every {@code finapp.payments.rail.outcome} count the storm's instance made -
 * rail by rail, type by type, outcome by outcome - equals the judgements the tables committed
 * over the same span: each transition into a judged state, and each book attempt born
 * {@code EXECUTED}. Counted after the commit, where the judgement is written (`P7-TSK-015`).
 *
 * <h2>Ten movers are ten instances</h2>
 *
 * <p>Every command is an HTTP request with its own pooled connection, so the movers contend in
 * the database exactly as ten instances would; the beans they share hold no correctness state,
 * which {@code NoSingleInstanceAssumptionRulesTest} enforces. Anything that is not a domain
 * outcome — a {@code 500}, a deadlock, an unexpected code — fails the storm rather than being
 * retried away. Every scheduled sweep is off in test contexts, so the only resolvers are this
 * storm's own two, racing; and the database is private to the test JVM, which runs one class at
 * a time — so while the storm runs, the storm is the only writer.
 *
 * <h2>What it found</h2>
 *
 * <p>A dispute WIN deadlocked with a refund of another payment to the same counterparty
 * ({@code 40P01}, a 500 at the card door): the win's first entry took the clearing's and the
 * recoverable's balance rows before its restoration touched the counterparty, while the refund
 * held the counterparty's account for its hold's release and waited on the clearing. The win now
 * share-locks the counterparty before its first posting ({@code ChargebackAccounting}), and
 * {@code ChargebackAccountingDatabaseTest#aWinRacingRefundsOfTheSameCounterpartyNeverDeadlocks}
 * holds it. And an honest {@code UNKNOWN} refund, its answer lost to a transport failure no one
 * chose, stood unresolved at rest while the providers answered only the storm's chosen losses:
 * truth, not a script, is what the resolvers are asked for.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the multi-rail conservation storm (P7-TST-001)")
class MultiRailConservationStormDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final byte[] CARD_WEBHOOK_KEY =
            "storm-card-webhook-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INSTANT_WEBHOOK_KEY =
            "storm-instant-webhook-key-32byte".getBytes(StandardCharsets.UTF_8);

    // --- the amounts: every one chosen for what it contests --------------------------------

    /** A card top-up, and the one whose capture answer is lost (every fifth). */
    private static final long CARD_TOP_UP = 20_00;

    private static final long LOST_CARD_TOP_UP = 20_07;

    /** A pay-in top-up, and the one whose confirmation never comes (every fourth). */
    private static final long PAY_IN = 15_00;

    private static final long LOST_PAY_IN = 15_07;

    /** A withdrawal, and the one whose answer is lost (every fourth). */
    private static final long WITHDRAWAL = 12_00;

    private static final long LOST_WITHDRAWAL = 12_07;

    private static final long TRANSFER = 8_00;

    /** Every checkout, on every rail - one gross, so each fee is one exact figure. */
    private static final long SALE = 10_00;

    private static final long PARTIAL_CHARGEBACK = 6_00;

    private static final long DISPUTE_FEE = 1_50;

    /** The racing refunds of the INV-DSP-01 burst: four of these cannot all fit one sale. */
    private static final long RACING_REFUND = 3_00;

    /**
     * Asked for by some of every kind of wallet debit - a withdrawal, a transfer, a wallet
     * checkout - and held by no storm wallet, so each refusal is made to happen rather than
     * hoped for (the P6-TST-002 payout's 500.00, one rail on).
     */
    private static final long UNAFFORDABLE = 5_000_00;

    // --- the crowd -------------------------------------------------------------------------

    private static final int CUSTOMERS = 4;
    private static final int SHOPPERS = 2;
    private static final int RESOLVERS = 2;

    /** The floors that make "sustained" a fact rather than a word. */
    private static final int MIN_ROUNDS_UNDER_LOAD = 30;

    private static final int MIN_COMMANDS_UNDER_LOAD = 600;

    private static final int COMMANDS_BETWEEN_ROUNDS =
            MIN_COMMANDS_UNDER_LOAD / MIN_ROUNDS_UNDER_LOAD;

    /** The round the choreographed refund-then-chargeback pair runs at: mid-storm. */
    private static final int CHOREOGRAPHY_ROUND = MIN_ROUNDS_UNDER_LOAD / 2;

    /** Stages at which a chargeback stands (or was lost) - the money is out of the clearing. */
    private static final String STANDING =
            "('CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED')";

    private static final Pattern CODE = Pattern.compile("\"code\":\"([^\"]*)\"");

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private com.finapp.app.reconciliation.PositionProof positionProof;
    @Autowired private PaymentSweeper paymentSweeper;
    @Autowired private WithdrawalResolution withdrawalResolution;
    @Autowired private PayInResolution payInResolution;
    @Autowired private ReturnResolution returnResolution;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

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
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @AfterEach
    void closeClient() {
        http.close();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // One stub server, every provider. Generous timeouts, deliberately: an answer that is
        // merely slow under load must not turn into ambiguity - the storm chooses its own lost
        // answers, and a stray UNKNOWN is still resolved by the resolvers, never absorbed.
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT10S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT10S");
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(CARD_WEBHOOK_KEY));
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT10S");
        registry.add(
                "finapp.payments.instant.webhook.key",
                () -> Base64.getEncoder().encodeToString(INSTANT_WEBHOOK_KEY));
        // The resolvers' candidacy bounds: an UNKNOWN row is asked about promptly, a dispatch
        // only once it is plainly overdue - a sweep asking about a row still in flight is legal
        // and harmless (an unanswered inquiry concludes nothing), but it is noise, not the
        // subject. Batches wide enough to reach the storm's rows past every other suite's
        // leftovers (the shared-database citizenship lesson).
        registry.add("finapp.payments.sweeper.dispatched-age", () -> "PT2S");
        registry.add("finapp.payments.sweeper.unknown-age", () -> "PT0.2S");
        registry.add("finapp.payments.sweeper.batch", () -> "2000");
        registry.add("finapp.payments.withdrawal.sweeper.dispatched-age", () -> "PT2S");
        registry.add("finapp.payments.withdrawal.sweeper.unknown-age", () -> "PT0.2S");
        // NEVER_RECEIVED stays honest: the scheme's DECLARED deadline plus this margin, so a
        // withdrawal the scheme truly never saw concludes inside the drain rather than minutes on.
        registry.add("finapp.payments.withdrawal.sweeper.margin", () -> "PT1S");
        registry.add("finapp.payments.withdrawal.sweeper.batch", () -> "2000");
        registry.add("finapp.payments.payin.sweeper.initiation-age", () -> "PT0.5S");
        registry.add("finapp.payments.payin.sweeper.batch", () -> "2000");
        registry.add("finapp.payments.return.sweeper.dispatched-age", () -> "PT2S");
        registry.add("finapp.payments.return.sweeper.unknown-age", () -> "PT0.2S");
        registry.add("finapp.payments.return.sweeper.batch", () -> "2000");
    }

    /**
     * Every operation answered with a reference of its own - each provider-reference column is
     * {@code UNIQUE}, so a fixed body would turn the second concurrent operation into a
     * {@code 23505}, a harness limit and not a finding ({@code P5-TST-003}) - and the chosen
     * victims' answers lost, above the ordinary stubs.
     */
    private static void stubTheRails() {
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "psp_a");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_r");
        // The PSP releases authorizations (the Phase 7 -> 8 transition): a capture the provider
        // never received now redirects into the void, so the truthful PSP must answer voids too.
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.VOIDS_PATH, "psp_v");
        provider.losesTheResponseWhenTheBodyContains(
                SimulatedCardPspAdapter.CAPTURES_PATH, amountOnTheWire(LOST_CARD_TOP_UP));
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-w-"
                        + "{{request.headers.Idempotency-Key}}\",\"cycle\":\"C1\"}");
        provider.losesTheResponseWhenTheBodyContains(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH, amountOnTheWire(LOST_WITHDRAWAL));
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH,
                200,
                "{\"status\":\"initiated\",\"handle\":\"https://payer-psp.example/authorize/"
                        + "{{request.headers.Idempotency-Key}}\"}");
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.RETURNS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-r-"
                        + "{{request.headers.Idempotency-Key}}\",\"cycle\":\"C3\"}");
    }

    // ----------------------------------------------------------------- the storm

    @Test
    @DisplayName("ten movers on every rail and every money path at once - card, instant and wallet"
            + " payments, withdrawals, transfers, refunds, returns and chargebacks on four shared"
            + " wallets and two payables, with lost answers, duplicate commands and duplicate"
            + " callbacks: every round reconciles each clearing position to its own rail's records"
            + " and every wallet and payable to independent books, and at rest nothing is in"
            + " flight and every duplicate had one effect")
    void conservationHoldsAcrossEveryRail() throws Exception {
        // The meters' own tally, opened before anything moves (P7-TSK-015's design input).
        Map<String, Long> meteredBefore = meteredJudgements();
        Map<String, Long> recordedBefore;
        try (Connection app = DatabaseRoles.application()) {
            recordedBefore = recordedJudgements(app);
        }
        World unprimed = world();
        stubTheRails();
        World world = prime(unprimed);

        Storm storm = new Storm(world);
        ExecutorService pool = Executors.newFixedThreadPool(11);
        List<Future<Void>> movers = new ArrayList<>();
        movers.add(pool.submit(topUpper(storm)));
        for (int i = 0; i < SHOPPERS; i++) {
            movers.add(pool.submit(shopper(storm, i)));
        }
        movers.add(pool.submit(withdrawer(storm)));
        movers.add(pool.submit(transferrer(storm)));
        movers.add(pool.submit(refunder(storm)));
        movers.add(pool.submit(callbacker(storm)));
        movers.add(pool.submit(disputer(storm)));
        for (int i = 0; i < RESOLVERS; i++) {
            movers.add(pool.submit(resolver(storm)));
        }
        movers.add(pool.submit(duplicator(storm)));

        long rounds = 0;
        Choreography choreography = null;
        try (Connection own = DatabaseRoles.application()) {
            // The rounds end the storm, so the overlap with live traffic is the loop's exit
            // condition rather than luck. The cap is a FAILURE bound: if the movers have died,
            // fall through so mover.get() reports why.
            while ((rounds < MIN_ROUNDS_UNDER_LOAD
                            || storm.committed.get() < MIN_COMMANDS_UNDER_LOAD)
                    && rounds < 10_000
                    && movers.stream().noneMatch(Future::isDone)) {
                rounds++;
                reconcile(own, world, "round " + rounds);
                if (rounds == CHOREOGRAPHY_ROUND) {
                    choreography = choreograph(storm);
                }
                long required =
                        Math.min(rounds * COMMANDS_BETWEEN_ROUNDS, MIN_COMMANDS_UNDER_LOAD);
                long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
                while (storm.committed.get() < required
                        && System.nanoTime() < deadline
                        && movers.stream().noneMatch(Future::isDone)) {
                    Thread.onSpinWait();
                }
            }
        } finally {
            storm.stop.set(true);
            pool.shutdown();
        }
        for (Future<Void> mover : movers) {
            mover.get(5, TimeUnit.MINUTES); // Propagates any outcome no mover could classify.
        }
        pool.awaitTermination(1, TimeUnit.MINUTES);

        assertThat(rounds).as("the rounds really ran under load")
                .isGreaterThanOrEqualTo(MIN_ROUNDS_UNDER_LOAD);
        assertThat(storm.committed.get())
                .as("the storm really was a storm - outcomes: %s", storm.outcomes)
                .isGreaterThanOrEqualTo(MIN_COMMANDS_UNDER_LOAD);
        assertThat(choreography).as("the choreographed pair ran mid-storm").isNotNull();

        // AT REST: drain what is still in flight - the last callbacks delivered, the last lost
        // answers given, the resolvers run until nothing moves.
        drain(storm);

        try (Connection app = DatabaseRoles.application()) {
            reconcile(app, world, "at rest");
            tally(app, storm, choreography);
            // The copies' reading is not vacuous: every settling flow the storm drove is in
            // scope and proven - captures, executions, refunds and returns, withdrawals and
            // the chargebacks the choreography forces (P8-TSK-005).
            UUID[] stormAccounts = world.accounts();
            assertThat(ClearingLineCopies.assertEveryClearingLineIsCopied(
                                    app, "at rest", STORM_ENTRIES, stormAccounts, stormAccounts)
                            .keySet())
                    .as("at rest: each externally settling completion the storm drove opened"
                            + " its expectations")
                    .contains("payment-capture:", "payment-execution:", "payment-refund:",
                            "wallet-withdrawal:", "dispute-chargeback:");
            // And the reading's "nothing else opened" half is not vacuous for the transfers:
            // the scope holds the storm's wallet-to-wallet entries, none of them expected.
            assertThat(sum(app,
                            "SELECT count(*) FROM ledger.journal_entry e"
                                    + " WHERE e.idempotency_scope LIKE 'ledger.post:transfer:%'"
                                    + "   AND e.id IN (" + STORM_ENTRIES + ")",
                            stormAccounts, stormAccounts))
                    .as("at rest: the storm's transfers are in the copies' scope")
                    .isPositive();

            // THE OPENING POSITION AND THE PROOF AT REST (P8-TSK-007, ADR-0067 sections
            // 8-9): the backfill over the storm's live-opened history adds nothing - the
            // uniques converge - and the position proof and completeness verifier read 0 on
            // every clearing purpose, with SUSPENSE_UNMATCHED honestly counting exactly the
            // parkings' own suspense lines until P8-TSK-020 adopts them.
            String controller = operatorSession(RoleName.RECONCILIATION_CONTROLLER);
            // Scoped to the storm's own entries: the shared container carries earlier
            // suites' history, and adopting whatever of it never met a live opener is the
            // backfill DOING ITS JOB - the adds-nothing claim is that everything the storm
            // itself drove was live-opened, so for these entries the uniques converge.
            String stormExpectations =
                    "SELECT count(*) FROM reconciliation.expectation"
                            + " WHERE journal_entry_id IN (" + STORM_ENTRIES + ")";
            long expectationsBefore = sum(app, stormExpectations, stormAccounts, stormAccounts);
            assertThat(send("POST", "/v1/operator/reconciliation/opening-position",
                            "{\"reason\":\"the storm's at-rest adoption\"}", controller,
                            someKey())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(sum(app, stormExpectations, stormAccounts, stormAccounts))
                    .as("at rest: the backfill over live-opened history adds nothing")
                    .isEqualTo(expectationsBefore);
            assertProofHoldsAtRest(app);

            // THE ALL-KINDS ADOPTION EQUIVALENCE: the register emptied (the platform's own
            // root, triggers disabled - history's shape, not a production path) and rebuilt
            // by the backfill alone - every clearing line's copy returns and the proof holds
            // again, which is section 8's whole claim demonstrated across EVERY kind the
            // storm drove.
            try (Connection root = DatabaseRoles.bootstrap()) {
                root.setAutoCommit(false);
                execute(root, "ALTER TABLE reconciliation.expectation_key DISABLE TRIGGER"
                        + " expectation_key_is_append_only");
                execute(root, "ALTER TABLE reconciliation.expectation_event DISABLE TRIGGER"
                        + " expectation_event_is_append_only");
                execute(root, "ALTER TABLE reconciliation.expectation DISABLE TRIGGER"
                        + " expectation_is_never_deleted");
                try {
                    // Since P8-TSK-011 an expectation history names - an allocation or
                    // a candidate snapshot - is held by those rows' foreign keys,
                    // exactly the immutability the records claim; the emptied-register
                    // equivalence is judged over the rest (the storm's own rows are
                    // never allocated - no matcher runs here).
                    // Since P8-TSK-015 a break or a resolution naming an expectation holds
                    // it too; their NULLs filtered (NOT IN over a NULL matches nothing).
                    String unheld = " NOT IN (SELECT expectation_id FROM"
                            + " reconciliation.allocation UNION SELECT expectation_id"
                            + " FROM reconciliation.match_candidate UNION SELECT"
                            + " expectation_id FROM reconciliation.break WHERE"
                            + " expectation_id IS NOT NULL UNION SELECT chosen_expectation_id"
                            + " FROM reconciliation.resolution WHERE chosen_expectation_id IS"
                            + " NOT NULL)";
                    execute(root, "DELETE FROM reconciliation.expectation_key WHERE"
                            + " expectation_id" + unheld);
                    execute(root, "DELETE FROM reconciliation.expectation_event WHERE"
                            + " expectation_id" + unheld);
                    execute(root, "DELETE FROM reconciliation.expectation WHERE id"
                            + unheld);
                } finally {
                    execute(root, "ALTER TABLE reconciliation.expectation ENABLE TRIGGER"
                            + " expectation_is_never_deleted");
                    execute(root, "ALTER TABLE reconciliation.expectation_event ENABLE"
                            + " TRIGGER expectation_event_is_append_only");
                    execute(root, "ALTER TABLE reconciliation.expectation_key ENABLE TRIGGER"
                            + " expectation_key_is_append_only");
                }
                root.commit();
            }
            assertThat(send("POST", "/v1/operator/reconciliation/opening-position",
                            "{\"reason\":\"rebuilding the register from the books alone\"}",
                            controller, someKey())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(ClearingLineCopies.assertEveryClearingLineIsCopied(
                                    app, "rebuilt from the books", STORM_ENTRIES,
                                    stormAccounts, stormAccounts)
                            .keySet())
                    .as("rebuilt: every kind the storm drove re-adopted from the books alone")
                    .contains("payment-capture:", "payment-execution:", "payment-refund:",
                            "wallet-withdrawal:", "dispute-chargeback:");
            assertProofHoldsAtRest(app);
            // THE METERS, A SECOND AND INDEPENDENT TALLY: every judgement the storm's instance
            // counted after its commit is exactly one the tables committed, rail by rail, type by
            // type, outcome by outcome - valid because nothing else judges in this JVM while the
            // storm runs (every schedule off in test contexts, one test class at a time, the
            // database private to the JVM).
            assertThat(delta(meteredJudgements(), meteredBefore))
                    .as("finapp.payments.rail.outcome counted exactly the judgements the tables"
                            + " committed - none lost, none counted twice")
                    .isEqualTo(delta(recordedJudgements(app), recordedBefore));
            TrialBalance.Report settled = trialBalance.sweep(app);
            assertThat(settled.outOfBalance()).as("at rest: zero per currency").isEmpty();
            for (UUID account : world.accounts()) {
                assertThat(verification.verdictOf(app, LedgerAccountId.of(account)))
                        .as("at rest: %s's projection equals replay-from-zero", account)
                        .isEqualTo(Verdict.CLEAN);
            }
        }
        contested(storm);
    }

    // ----------------------------------------------------------------- the readings

    /**
     * One round's reconciliation: the trial balance, then everything else inside ONE snapshot -
     * each clearing position against its own rail, the dispute accounts, the fee revenue, every
     * wallet and payable against its books, and the combined bound on every card attempt.
     */
    private void reconcile(Connection own, World world, String when) throws Exception {
        TrialBalance.Report trial = trialBalance.sweep(own);
        assertThat(trial.outOfBalance())
                .as("%s: zero per currency while money enters and leaves on three rails"
                        + " (INV-ACC-01)", when)
                .isEmpty();

        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            UUID[] accounts = world.accounts();
            Map<String, Long> positions = stormPositions(snapshot, accounts);

            assertThat(positions.getOrDefault("SETTLEMENT_CLEARING", 0L))
                    .as("%s: SETTLEMENT_CLEARING holds exactly the card rail's captures less its"
                            + " completed refunds, the chargebacks standing and the dispute fees -"
                            + " nothing another rail posted (INV-RAIL-04)", when)
                    .isEqualTo(cardRailRecords(snapshot, accounts));
            assertThat(positions.getOrDefault("INSTANT_CLEARING", 0L))
                    .as("%s: INSTANT_CLEARING holds exactly the instant rail's executions less its"
                            + " completed withdrawals and returns (INV-RAIL-04)", when)
                    .isEqualTo(instantRailRecords(snapshot, accounts, world.wallets()));
            assertThat(positions.getOrDefault("CHARGEBACK_RECOVERABLE", 0L)
                            + positions.getOrDefault("DISPUTE_COSTS", 0L))
                    .as("%s: the dispute accounts hold exactly each standing or lost chargeback's"
                            + " excess over the counterparty's share, plus the fees (INV-DSP-01)",
                            when)
                    .isEqualTo(disputeRecords(snapshot, accounts));

            long fees = 0;
            long returned = 0;
            for (Merchant merchant : world.everyMerchant()) {
                PayableBooks books = payableBooks(snapshot, merchant.payable());
                fees += books.fees();
                returned += books.returned();
                assertThat(settled(snapshot, merchant.payable()))
                        .as("%s: the %s payable equals its sales on three rails, each priced from"
                                + " its pin, less refunds and returned fees and the chargeback"
                                + " shares standing - from records the ledger never sees",
                                when, merchant.policy())
                        .isEqualTo(books.owed());
            }
            assertThat(-positions.getOrDefault("FEE_REVENUE", 0L))
                    .as("%s: FEE_REVENUE earned exactly the storm's fees less those returned",
                            when)
                    .isEqualTo(fees - returned);

            for (Customer customer : world.customers()) {
                WalletBooks books = walletBooks(snapshot, customer.wallet());
                long settled = settled(snapshot, customer.wallet());
                assertThat(settled)
                        .as("%s: a wallet equals its top-ups less their refunds, withdrawals,"
                                + " wallet payments and transfers out, plus book refunds and"
                                + " transfers in - %s", when, books)
                        .isEqualTo(books.balance());
                assertThat(books.held())
                        .as("%s: a wallet's active holds are exactly its debits in flight - %s",
                                when, books)
                        .isEqualTo(books.inFlight());
                assertThat(settled - books.held())
                        .as("%s: available never below zero while withdrawals complete beside"
                                + " wallet payments, transfers and refunds (INV-BAL-04)", when)
                        .isGreaterThanOrEqualTo(0L);
            }

            for (long[] attempt : combinedBound(snapshot, accounts)) {
                assertThat(attempt[1] + attempt[2])
                        .as("%s: non-failed refunds %s plus attributed shares %s never exceed the"
                                + " %s captured (INV-DSP-01)",
                                when, attempt[1], attempt[2], attempt[0])
                        .isLessThanOrEqualTo(attempt[0]);
            }

            // EVERY CLEARING LINE HAS ITS EXPECTATION, IN THE SAME SNAPSHOT (P8-TSK-005,
            // ADR-0067): each capture, execution, refund, return, withdrawal and money stage the
            // storm committed opened exactly one copy of its clearing line IN ITS OWN COMMIT - a
            // round's snapshot taken mid-storm never sees a line without its copy - and the
            // transfers and book movements beside them opened nothing.
            ClearingLineCopies.assertEveryClearingLineIsCopied(
                    snapshot, when, STORM_ENTRIES, accounts, accounts);
            snapshot.commit();
        }
    }

    /**
     * The storm's own entries' net DEBIT movement per platform purpose: every entry touching a
     * storm wallet or payable, and the dispute entries keyed by a storm dispute.
     */
    /**
     * The storm's own entries: every entry touching a storm wallet or payable, and the dispute
     * entries keyed by a storm dispute - two bindings, the storm's accounts twice. One
     * definition for the positions and the expectation copies (P8-TSK-005), so the two
     * readings can never be taken over different scopes.
     */
    private static final String STORM_ENTRIES =
            "SELECT mine.entry_id FROM ledger.journal_line mine"
                    + "  WHERE mine.ledger_account_id = ANY (?)"
                    + " UNION"
                    + " SELECT e.id FROM ledger.journal_entry e"
                    + "   JOIN payments.dispute d"
                    + "     ON e.idempotency_scope LIKE 'ledger.post:dispute-%:'"
                    + "        || d.id::text || '%'"
                    + "   JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                    + "   JOIN payments.payment_intent i ON i.id = a.intent_id"
                    + "  WHERE i.credit_account_id = ANY (?)";

    private static Map<String, Long> stormPositions(Connection app, UUID[] accounts)
            throws SQLException {
        Map<String, Long> positions = new java.util.HashMap<>();
        try (PreparedStatement read =
                app.prepareStatement(
                        "WITH storm_entry AS (" + STORM_ENTRIES + ")"
                                + " SELECT account.purpose, COALESCE(SUM(CASE"
                                + "   WHEN line.direction = 'DEBIT' THEN line.amount_minor"
                                + "   ELSE -line.amount_minor END), 0)"
                                + " FROM ledger.journal_line line"
                                + " JOIN ledger.ledger_account account"
                                + "   ON account.id = line.ledger_account_id"
                                + " WHERE line.entry_id IN (SELECT entry_id FROM storm_entry)"
                                + "   AND account.owner_ref IS NULL"
                                + " GROUP BY account.purpose")) {
            read.setArray(1, app.createArrayOf("uuid", accounts));
            read.setArray(2, app.createArrayOf("uuid", accounts));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    positions.put(rows.getString(1), rows.getBigDecimal(2).longValueExact());
                }
            }
        }
        return positions;
    }

    /** The card rail's records: captures - completed refunds - standing chargebacks - fees. */
    private static long cardRailRecords(Connection app, UUID[] accounts) throws SQLException {
        return sum(app,
                        "SELECT COALESCE(SUM(a.captured_amount_minor), 0)"
                                + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'card' AND a.status = 'CAPTURED'"
                                + "   AND i.credit_account_id = ANY (?)",
                        (Object) accounts)
                - sum(app,
                        "SELECT COALESCE(SUM(r.amount_minor), 0) FROM payments.refund r"
                                + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'card' AND r.status = 'COMPLETED'"
                                + "   AND i.credit_account_id = ANY (?)",
                        (Object) accounts)
                - sum(app,
                        "SELECT COALESCE(SUM(CASE WHEN d.stage IN " + STANDING
                                + "   THEN d.chargeback_amount_minor ELSE 0 END"
                                + "   + COALESCE(d.dispute_fee_amount_minor, 0)), 0)"
                                + " FROM payments.dispute d"
                                + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE i.credit_account_id = ANY (?)",
                        (Object) accounts);
    }

    /** The instant rail's records: executions - completed withdrawals - completed returns. */
    private static long instantRailRecords(Connection app, UUID[] accounts, UUID[] wallets)
            throws SQLException {
        return sum(app,
                        "SELECT COALESCE(SUM(i.amount_minor), 0)"
                                + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'instant' AND a.status = 'EXECUTED'"
                                + "   AND i.credit_account_id = ANY (?)",
                        (Object) accounts)
                - sum(app,
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM payments.withdrawal"
                                + " WHERE status = 'COMPLETED' AND wallet_account_id = ANY (?)",
                        (Object) wallets)
                - sum(app,
                        "SELECT COALESCE(SUM(r.amount_minor), 0) FROM payments.refund r"
                                + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'instant' AND r.status = 'COMPLETED'"
                                + "   AND i.credit_account_id = ANY (?)",
                        (Object) accounts);
    }

    /**
     * The dispute accounts' records: every fee, and each standing or lost chargeback's excess
     * over the share posted to its counterparty (a parked share rests in the recoverable too).
     */
    private static long disputeRecords(Connection app, UUID[] accounts) throws SQLException {
        return sum(app,
                "SELECT COALESCE(SUM(COALESCE(d.dispute_fee_amount_minor, 0)"
                        + "   + CASE WHEN d.stage IN " + STANDING
                        + "     THEN d.chargeback_amount_minor"
                        + "          - COALESCE(d.counterparty_share_amount_minor, 0)"
                        + "     ELSE 0 END), 0)"
                        + " FROM payments.dispute d"
                        + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                        + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                        + " WHERE i.credit_account_id = ANY (?)",
                (Object) accounts);
    }

    /** Every storm card attempt: captured, non-failed refunds, attributed shares standing. */
    private static List<long[]> combinedBound(Connection app, UUID[] accounts)
            throws SQLException {
        List<long[]> attempts = new ArrayList<>();
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT COALESCE(a.captured_amount_minor, 0),"
                                + " (SELECT COALESCE(SUM(r.amount_minor), 0)"
                                + "    FROM payments.refund r"
                                + "   WHERE r.attempt_id = a.id AND r.status <> 'FAILED'),"
                                + " (SELECT COALESCE(SUM("
                                + "      COALESCE(d.counterparty_share_amount_minor, 0)"
                                + "      + COALESCE(d.parked_share_amount_minor, 0)), 0)"
                                + "    FROM payments.dispute d"
                                + "   WHERE d.attempt_id = a.id AND d.stage IN " + STANDING + ")"
                                + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'card' AND i.credit_account_id = ANY (?)")) {
            read.setArray(1, app.createArrayOf("uuid", accounts));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    attempts.add(
                            new long[] {
                                rows.getLong(1),
                                rows.getBigDecimal(2).longValueExact(),
                                rows.getBigDecimal(3).longValueExact()
                            });
                }
            }
        }
        return attempts;
    }

    /** One wallet's account in the records, read in the caller's snapshot. */
    private static WalletBooks walletBooks(Connection app, UUID wallet) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT"
                                + " (SELECT COALESCE(SUM(a.captured_amount_minor), 0)"
                                + "    FROM payments.payment_attempt a"
                                + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + "   WHERE i.credit_account_id = ? AND a.rail = 'card'"
                                + "     AND a.status = 'CAPTURED'),"
                                + " (SELECT COALESCE(SUM(i.amount_minor), 0)"
                                + "    FROM payments.payment_attempt a"
                                + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + "   WHERE i.credit_account_id = ? AND a.rail = 'instant'"
                                + "     AND a.status = 'EXECUTED'),"
                                + " (SELECT COALESCE(SUM(r.amount_minor), 0)"
                                + "    FROM payments.refund r"
                                + "    JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + "   WHERE i.credit_account_id = ? AND r.status = 'COMPLETED'),"
                                + " (SELECT COALESCE(SUM(amount_minor), 0)"
                                + "    FROM payments.withdrawal"
                                + "   WHERE wallet_account_id = ? AND status = 'COMPLETED'),"
                                + " (SELECT COALESCE(SUM(i.amount_minor), 0)"
                                + "    FROM payments.payment_attempt a"
                                + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + "   WHERE i.debit_account_id = ? AND a.rail = 'book'"
                                + "     AND a.status = 'EXECUTED'),"
                                + " (SELECT COALESCE(SUM(r.amount_minor), 0)"
                                + "    FROM payments.refund r"
                                + "    JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + "   WHERE i.debit_account_id = ? AND r.status = 'COMPLETED'),"
                                + " (SELECT COALESCE(SUM(amount_minor), 0) FROM transfers.transfer"
                                + "   WHERE source_account_id = ? AND status = 'COMPLETED'),"
                                + " (SELECT COALESCE(SUM(amount_minor), 0) FROM transfers.transfer"
                                + "   WHERE destination_account_id = ? AND status = 'COMPLETED'),"
                                + " (SELECT COALESCE(SUM(amount_minor), 0) FROM ledger.hold"
                                + "   WHERE ledger_account_id = ? AND status = 'ACTIVE'),"
                                + " (SELECT COALESCE(SUM(amount_minor), 0)"
                                + "    FROM payments.withdrawal WHERE wallet_account_id = ?"
                                + "     AND status IN ('DISPATCHED', 'UNKNOWN')),"
                                + " (SELECT COALESCE(SUM(r.amount_minor), 0)"
                                + "    FROM payments.refund r"
                                + "    JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + "    JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + "   WHERE i.credit_account_id = ?"
                                + "     AND r.status IN ('DISPATCHED', 'UNKNOWN'))")) {
            for (int i = 1; i <= 11; i++) {
                read.setObject(i, wallet);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                long[] terms = new long[11];
                for (int i = 0; i < 11; i++) {
                    terms[i] = row.getBigDecimal(i + 1).longValueExact();
                }
                return new WalletBooks(terms);
            }
        }
    }

    /** A wallet's records, term by term. */
    private record WalletBooks(long[] terms) {
        long balance() {
            return terms[0] + terms[1] - terms[2] - terms[3] - terms[4] + terms[5] - terms[6]
                    + terms[7];
        }

        long held() {
            return terms[8];
        }

        long inFlight() {
            return terms[9] + terms[10];
        }

        @Override
        public String toString() {
            return "card top-ups " + terms[0] + ", pay-ins " + terms[1] + ", their refunds "
                    + terms[2] + ", withdrawals " + terms[3] + ", wallet payments " + terms[4]
                    + ", book refunds " + terms[5] + ", transfers out " + terms[6]
                    + ", transfers in " + terms[7] + ", held " + terms[8]
                    + ", in flight " + terms[9] + "+" + terms[10];
        }
    }

    /**
     * Every sale a payable was credited, on all three rails, priced from its pin: the fee by the
     * pinned version's own terms, the completed refunds, the fee share those refunds reach
     * (returned under RETURNED), and the chargeback shares standing against it. Exact decimal
     * arithmetic, not {@code FeeCalculation}, so the reading is independent of the code it checks.
     */
    private static PayableBooks payableBooks(Connection app, UUID payable) throws SQLException {
        long gross = 0;
        long fees = 0;
        long refunded = 0;
        long returned = 0;
        long charged = 0;
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT CASE WHEN a.rail = 'card' THEN a.captured_amount_minor"
                                + "   ELSE i.amount_minor END,"
                                + " version.rate, version.fixed_amount_minor,"
                                + " version.rounding_policy, version.refund_fee_policy,"
                                + " (SELECT COALESCE(SUM(r.amount_minor), 0)"
                                + "    FROM payments.refund r"
                                + "   WHERE r.attempt_id = a.id AND r.status = 'COMPLETED'),"
                                + " (SELECT COALESCE(SUM("
                                + "      COALESCE(d.counterparty_share_amount_minor, 0)), 0)"
                                + "    FROM payments.dispute d"
                                + "   WHERE d.attempt_id = a.id AND d.stage IN " + STANDING + ")"
                                + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " JOIN merchant.payment_fee_pin pin"
                                + "   ON pin.payment_intent_ref = i.id"
                                + " JOIN merchant.fee_schedule_version version"
                                + "   ON version.id = pin.fee_schedule_version_id"
                                + " WHERE i.credit_account_id = ?"
                                + "   AND ((a.rail = 'card' AND a.status = 'CAPTURED')"
                                + "     OR (a.rail IN ('instant', 'book')"
                                + "         AND a.status = 'EXECUTED'))")) {
            read.setObject(1, payable);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    long sale = rows.getLong(1);
                    RoundingMode rounding = RoundingPolicy.ofName(rows.getString(4)).mode();
                    long fee =
                            BigDecimal.valueOf(sale)
                                            .multiply(rows.getBigDecimal(2))
                                            .setScale(0, rounding)
                                            .longValueExact()
                                    + rows.getLong(3);
                    long back = rows.getBigDecimal(6).longValueExact();
                    long share =
                            BigDecimal.valueOf(fee)
                                    .multiply(BigDecimal.valueOf(back))
                                    .divide(BigDecimal.valueOf(sale), 0, rounding)
                                    .longValueExact();
                    gross += sale;
                    fees += fee;
                    refunded += back;
                    returned += "RETURNED".equals(rows.getString(5)) ? share : 0L;
                    charged += rows.getBigDecimal(7).longValueExact();
                }
            }
        }
        return new PayableBooks(gross, fees, refunded, returned, charged);
    }

    /** The records' account of one payable. */
    private record PayableBooks(long gross, long fees, long refunded, long returned, long charged) {
        /** sales - fees - refunded + returned - charged back. */
        long owed() {
            return gross - fees - refunded + returned - charged;
        }
    }

    private static long settled(Connection app, UUID account) {
        return new JdbcBalanceDerivation()
                .derive(app, LedgerAccountId.of(account), AsOf.latest())
                .settled()
                .minorUnits();
    }

    // ----------------------------------------------------------------- at rest

    /**
     * The last of the traffic: every pending callback delivered, every lost answer given, and the
     * resolvers run until no storm operation is left in flight. The deadline is a FAILURE bound.
     */
    private void drain(Storm storm) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
        long inFlight = Long.MAX_VALUE;
        while (System.nanoTime() < deadline) {
            deliverPendingCallbacks(storm);
            answerInquiries(storm, true);
            sweepAll();
            try (Connection app = DatabaseRoles.application()) {
                inFlight = inFlight(app, storm.world);
            }
            if (inFlight == 0) {
                break;
            }
            Thread.sleep(250);
        }
        String stranded = "";
        if (inFlight != 0) {
            try (Connection app = DatabaseRoles.application()) {
                stranded = strandedRows(app, storm.world);
            }
        }
        assertThat(inFlight)
                .as("at rest: nothing left in flight - every storm attempt, withdrawal and refund"
                        + " reached its outcome (INV-LIFE-03's other half: ambiguity resolved) -"
                        + " stranded: %s", stranded)
                .isZero();
    }

    /** What the drain could not settle, named - a failure message that says where to look. */
    private static String strandedRows(Connection app, World world) throws SQLException {
        UUID[] accounts = world.accounts();
        List<String> found = new ArrayList<>();
        for (String[] row :
                rows(app,
                        "SELECT 'attempt ' || a.id || ' ' || a.rail, a.status,"
                                + " i.amount_minor::text || ' created ' || a.created_at::text"
                                + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE (i.credit_account_id = ANY (?)"
                                + "        OR i.debit_account_id = ANY (?))"
                                + "   AND a.status NOT IN ('CAPTURED', 'EXECUTED', 'FAILED',"
                                + "                        'VOIDED')",
                        accounts, accounts)) {
            found.add(String.join(" ", row));
        }
        for (String[] row :
                rows(app,
                        "SELECT 'withdrawal ' || id, status, amount_minor::text"
                                + " FROM payments.withdrawal WHERE wallet_account_id = ANY (?)"
                                + "   AND status NOT IN ('COMPLETED', 'FAILED')",
                        (Object) world.wallets())) {
            found.add(String.join(" ", row));
        }
        for (String[] row :
                rows(app,
                        "SELECT 'refund ' || r.id || ' of ' || a.rail, r.status,"
                                + " r.amount_minor::text FROM payments.refund r"
                                + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE (i.credit_account_id = ANY (?)"
                                + "        OR i.debit_account_id = ANY (?))"
                                + "   AND r.status NOT IN ('COMPLETED', 'FAILED')",
                        accounts, accounts)) {
            found.add(String.join(" ", row));
        }
        return found.toString();
    }

    private static long inFlight(Connection app, World world) throws SQLException {
        UUID[] accounts = world.accounts();
        return sum(app,
                        "SELECT count(*) FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE (i.credit_account_id = ANY (?)"
                                + "        OR i.debit_account_id = ANY (?))"
                                + "   AND a.status NOT IN ('CAPTURED', 'EXECUTED', 'FAILED',"
                                + "                        'VOIDED')",
                        accounts, accounts)
                + sum(app,
                        "SELECT count(*) FROM payments.withdrawal"
                                + " WHERE wallet_account_id = ANY (?)"
                                + "   AND status NOT IN ('COMPLETED', 'FAILED')",
                        (Object) world.wallets())
                + sum(app,
                        "SELECT count(*) FROM payments.refund r"
                                + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE (i.credit_account_id = ANY (?)"
                                + "        OR i.debit_account_id = ANY (?))"
                                + "   AND r.status NOT IN ('COMPLETED', 'FAILED')",
                        accounts, accounts);
    }

    /**
     * The tally, at rest: no hold standing, every lost answer resolved to exactly ONE entry
     * through the ambiguity it was lost into, every duplicated callback and every ten-way burst
     * one effect, every sale its order, and the choreographed pair's outcome.
     */
    private void tally(Connection app, Storm storm, Choreography choreography) throws Exception {
        World world = storm.world;
        UUID[] accounts = world.accounts();
        assertThat(sum(app,
                        "SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ANY (?)"
                                + " AND status = 'ACTIVE'",
                        (Object) accounts))
                .as("at rest: no hold left standing on any storm wallet or payable")
                .isZero();

        // SCENARIO 1 - the lost answers, each through its ambiguity to what the provider did:
        // a request the provider received (every chosen loss is lost AFTER receipt) completes
        // with exactly one entry; one a transport failure stopped before it arrived is truthfully
        // unrecognised and fails with none. The scenario itself must have happened.
        List<String> captureRequests =
                provider.headerValues(SimulatedCardPspAdapter.CAPTURES_PATH, "Idempotency-Key");
        long capturesLost = 0;
        for (String[] attempt :
                pairs(app,
                        "SELECT a.id::text, a.capture_reference"
                                + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'card' AND i.amount_minor = ?"
                                + "   AND a.capture_reference IS NOT NULL"
                                + "   AND i.credit_account_id = ANY (?)",
                        LOST_CARD_TOP_UP, world.wallets())) {
            boolean received = captureRequests.contains(attempt[1]);
            capturesLost += received ? 1 : 0;
            assertThat(one(app, "SELECT status FROM payments.payment_attempt WHERE id = ?::uuid",
                            attempt[0]))
                    .as("the lost capture %s: the provider %s it", attempt[0],
                            received ? "took" : "never saw")
                    // A capture the provider never saw releases its authorization (the Phase 7 ->
                    // 8 transition): the void redirect, never FAILED with the hold standing.
                    .isEqualTo(received ? "CAPTURED" : "VOIDED");
            assertThat(entries(app, "payment-capture:" + attempt[0]))
                    .as("the lost capture %s resolved to one entry, or none", attempt[0])
                    .isEqualTo(received ? 1 : 0);
            if (received) {
                assertThat(sum(app,
                                "SELECT count(*) FROM payments.payment_attempt_event"
                                        + " WHERE attempt_id = ?::uuid"
                                        + "   AND to_status = 'CAPTURE_UNKNOWN'",
                                attempt[0]))
                        .as("the lost capture %s went CAPTURE_UNKNOWN first", attempt[0])
                        .isPositive();
            }
        }
        assertThat(capturesLost)
                .as("captures the PSP took were lost and resolved (scenario 1)")
                .isPositive();
        List<String> transferRequests =
                provider.headerValues(
                        SimulatedInstantSchemeAdapter.TRANSFERS_PATH, "Idempotency-Key");
        long withdrawalsLost = 0;
        for (String[] withdrawal :
                pairs(app,
                        "SELECT id::text, end_to_end_reference FROM payments.withdrawal"
                                + " WHERE amount_minor = ? AND wallet_account_id = ANY (?)",
                        LOST_WITHDRAWAL, world.wallets())) {
            boolean received = transferRequests.contains(withdrawal[1]);
            withdrawalsLost += received ? 1 : 0;
            assertThat(one(app, "SELECT status FROM payments.withdrawal WHERE id = ?::uuid",
                            withdrawal[0]))
                    .as("the lost withdrawal %s: the scheme %s it", withdrawal[0],
                            received ? "accepted" : "never saw")
                    .isEqualTo(received ? "COMPLETED" : "FAILED");
            assertThat(entries(app, "wallet-withdrawal:" + withdrawal[0]))
                    .as("the lost withdrawal %s resolved to one entry, or none", withdrawal[0])
                    .isEqualTo(received ? 1 : 0);
            if (received) {
                assertThat(sum(app,
                                "SELECT count(*) FROM payments.withdrawal_event"
                                        + " WHERE withdrawal_id = ?::uuid"
                                        + "   AND to_status = 'UNKNOWN'",
                                withdrawal[0]))
                        .as("the lost withdrawal %s went UNKNOWN first", withdrawal[0])
                        .isPositive();
            }
        }
        assertThat(withdrawalsLost)
                .as("withdrawals the scheme accepted were lost and resolved (scenario 1)")
                .isPositive();
        List<String[]> lostPayIns =
                pairs(app,
                        "SELECT a.id::text, a.end_to_end_reference"
                                + " FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'instant' AND i.amount_minor = ?"
                                + "   AND i.credit_account_id = ANY (?)",
                        LOST_PAY_IN, world.wallets());
        assertThat(lostPayIns).as("the storm lost pay-in confirmations").isNotEmpty();
        for (String[] payIn : lostPayIns) {
            assertThat(provider.requestCount(
                            SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + payIn[1]))
                    .as("the lost pay-in %s was resolved by the inquiry, never a callback",
                            payIn[0])
                    .isPositive();
            assertThat(one(app, "SELECT status FROM payments.payment_attempt WHERE id = ?::uuid",
                            payIn[0]))
                    .isEqualTo("EXECUTED");
            assertThat(entries(app, "payment-execution:" + payIn[0]))
                    .as("the lost pay-in %s resolved to EXACTLY ONE entry", payIn[0])
                    .isEqualTo(1);
        }

        // SCENARIO 3 - every duplicated callback, one effect.
        List<String> executed =
                strings(app,
                        "SELECT a.id::text FROM payments.payment_attempt a"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE a.rail = 'instant' AND a.status = 'EXECUTED'"
                                + "   AND i.credit_account_id = ANY (?)",
                        (Object) accounts);
        assertThat(executed).as("pay-ins executed on the instant rail").isNotEmpty();
        for (String attempt : executed) {
            assertThat(entries(app, "payment-execution:" + attempt))
                    .as("the execution %s posted once under every delivery", attempt)
                    .isEqualTo(1);
            assertThat(sum(app,
                            "SELECT count(*) FROM payments.payment_attempt_event"
                                    + " WHERE attempt_id = ?::uuid AND to_status = 'EXECUTED'",
                            attempt))
                    .as("and transitioned once", attempt)
                    .isEqualTo(1);
        }
        assertThat(storm.clearingNotices).as("clearing notices were delivered").isNotEmpty();
        for (UUID attempt : storm.clearingNotices) {
            assertThat(sum(app,
                            "SELECT count(*) FROM payments.clearing_record WHERE attempt_id = ?",
                            attempt))
                    .as("the clearing notice for %s recorded once under two deliveries",
                            attempt)
                    .isEqualTo(1);
        }
        List<String> disputes =
                strings(app,
                        "SELECT d.id::text FROM payments.dispute d"
                                + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE i.credit_account_id = ANY (?)",
                        (Object) accounts);
        assertThat(disputes).as("chargebacks were delivered").isNotEmpty();
        for (String dispute : disputes) {
            assertThat(entries(app, "dispute-chargeback:" + dispute))
                    .as("the chargeback %s posted its external fact once", dispute)
                    .isEqualTo(1);
        }
        for (Map.Entry<UUID, String> opened : storm.disputes.entrySet()) {
            assertThat(sum(app,
                            "SELECT count(*) FROM payments.dispute"
                                    + " WHERE provider_dispute_reference = ?",
                            opened.getValue()))
                    .as("one dispute however often notified: %s", opened.getValue())
                    .isEqualTo(1);
        }

        // EVERY MONEY PATH RAN - read from the tables, not from what the movers counted: a path
        // that silently stopped would leave every reconciliation above green over less traffic
        // (the gate's find: the paths were observed in the counts but asserted nowhere).
        for (String rail : List.of("card", "instant", "book")) {
            assertThat(sum(app,
                            "SELECT count(*) FROM payments.refund r"
                                    + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = ? AND r.status = 'COMPLETED'"
                                    + "   AND i.credit_account_id = ANY (?)",
                            rail, accounts))
                    .as("a refund completed on the %s rail - the PSP's refund, the return payment"
                            + " and the book movement each ran", rail)
                    .isPositive();
        }
        assertThat(sum(app,
                        "SELECT count(*) FROM payments.withdrawal WHERE status = 'COMPLETED'"
                                + " AND wallet_account_id = ANY (?)",
                        (Object) world.wallets()))
                .as("withdrawals completed")
                .isPositive();
        assertThat(sum(app,
                        "SELECT count(*) FROM transfers.transfer WHERE status = 'COMPLETED'"
                                + " AND source_account_id = ANY (?)",
                        (Object) world.wallets()))
                .as("transfers completed between the storm's wallets")
                .isPositive();
        for (String stage : List.of("WON", "LOST")) {
            assertThat(sum(app,
                            "SELECT count(*) FROM payments.dispute d"
                                    + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE d.stage = ? AND i.credit_account_id = ANY (?)",
                            stage, accounts))
                    .as("disputes reached %s - a win's exact inverse and a loss's write-off ran",
                            stage)
                    .isPositive();
        }
        assertThat(sum(app,
                        "SELECT count(*) FROM payments.dispute d"
                                + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE d.dispute_fee_amount_minor IS NOT NULL"
                                + "   AND i.credit_account_id = ANY (?)",
                        (Object) accounts))
                .as("dispute fees were posted")
                .isPositive();

        // SCENARIO 2 - every ten-way burst, one effect.
        assertThat(storm.bursts.stream().map(Burst::kind).distinct())
                .as("every burst kind ran")
                .contains(BurstKind.values());
        for (Burst burst : storm.bursts) {
            assertThat(burst.answered())
                    .as("every racer of burst %s that succeeded was answered with the ONE row",
                            burst.subject())
                    .hasSizeLessThanOrEqualTo(1);
            switch (burst.kind()) {
                case CONFIRM -> {
                    assertThat(sum(app,
                                    "SELECT count(*) FROM payments.payment_attempt a"
                                            + " JOIN checkout.checkout_session s"
                                            + "   ON s.payment_intent_ref = a.intent_id"
                                            + " WHERE s.id = ?::uuid",
                                    burst.subject()))
                            .as("ten confirmations of session %s: one attempt", burst.subject())
                            .isEqualTo(1);
                    String status =
                            one(app,
                                    "SELECT a.status FROM payments.payment_attempt a"
                                            + " JOIN checkout.checkout_session s"
                                            + "   ON s.payment_intent_ref = a.intent_id"
                                            + " WHERE s.id = ?::uuid",
                                    burst.subject());
                    assertThat(sum(app,
                                    "SELECT count(*) FROM checkout.checkout_order"
                                            + " WHERE session_ref = ?::uuid",
                                    burst.subject()))
                            .as("and one order if it captured - it is %s", status)
                            .isEqualTo(status.equals("CAPTURED") ? 1 : 0);
                }
                case WITHDRAW -> assertThat(strings(app,
                                "SELECT id::text FROM payments.withdrawal WHERE dispatch_key = ?",
                                burst.subject()))
                        .as("ten withdrawals under key %s: exactly the one row every racer that"
                                + " succeeded was answered with - none when all were refused",
                                burst.subject())
                        .containsExactlyInAnyOrderElementsOf(burst.answered());
                case REFUND -> assertThat(strings(app,
                                "SELECT id::text FROM payments.refund WHERE dispatch_key = ?",
                                burst.subject()))
                        .as("ten refunds under key %s: exactly the one row every racer that"
                                + " succeeded was answered with - none when all were refused",
                                burst.subject())
                        .containsExactlyInAnyOrderElementsOf(burst.answered());
                case CHARGEBACK -> {
                    assertThat(sum(app,
                                    "SELECT count(*) FROM payments.dispute"
                                            + " WHERE provider_dispute_reference = ?",
                                    burst.subject()))
                            .as("ten notifications of dispute %s: one dispute", burst.subject())
                            .isEqualTo(1);
                    String dispute =
                            one(app,
                                    "SELECT id::text FROM payments.dispute"
                                            + " WHERE provider_dispute_reference = ?",
                                    burst.subject());
                    assertThat(entries(app, "dispute-chargeback:" + dispute)).isEqualTo(1);
                }
                case RACE -> {
                    // INV-DSP-01 RACED: four partial refunds against a full chargeback on one
                    // sale. Whatever order the attempt lock gave them, the counted refunds and
                    // the counterparty's share sum to exactly the capture, and the excess is
                    // exactly what the refunds had already returned.
                    long refunded =
                            sum(app,
                                    "SELECT COALESCE(SUM(amount_minor), 0) FROM payments.refund"
                                            + " WHERE attempt_id = ?::uuid AND status <> 'FAILED'",
                                    burst.subject());
                    long share =
                            sum(app,
                                    "SELECT COALESCE(SUM(counterparty_share_amount_minor), 0)"
                                            + " FROM payments.dispute WHERE attempt_id = ?::uuid",
                                    burst.subject());
                    assertThat(refunded + share)
                            .as("the raced sale %s: refunds %s + share %s == the capture",
                                    burst.subject(), refunded, share)
                            .isEqualTo(SALE);
                }
                default -> throw new IllegalStateException("unknown burst " + burst.kind());
            }
        }

        // The choreographed pair: the gate's no-double-debit criterion under load, and the
        // refusal past what remains.
        assertThat(sum(app,
                        "SELECT COALESCE(SUM(counterparty_share_amount_minor), 0)"
                                + " FROM payments.dispute WHERE attempt_id = ?::uuid",
                        choreography.refundedFirst()))
                .as("a chargeback on a sale refunded in full charges the merchant NOTHING - the"
                        + " whole chargeback rests in CHARGEBACK_RECOVERABLE (the gate's"
                        + " criterion, under load)")
                .isZero();
        assertThat(choreography.refundAfterChargeback())
                .as("a refund after a full chargeback is refused past what remains")
                .isEqualTo("payments.RefundExceedsCaptured");

        // Landed money is never orphaned: every sale produced its order.
        for (Merchant merchant : world.everyMerchant()) {
            long sales =
                    sum(app,
                            "SELECT count(*) FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE i.credit_account_id = ?"
                                    + "   AND ((a.rail = 'card' AND a.status = 'CAPTURED')"
                                    + "     OR (a.rail IN ('instant', 'book')"
                                    + "         AND a.status = 'EXECUTED'))",
                            merchant.payable());
            assertThat(sum(app,
                            "SELECT count(*) FROM checkout.checkout_order WHERE merchant_ref = ?",
                            UUID.fromString(merchant.id())))
                    .as("the %s merchant: every sale on every rail produced its order",
                            merchant.policy())
                    .isEqualTo(sales);
        }
        for (Merchant merchant : world.merchants()) {
            for (String rail : List.of("card", "instant", "book")) {
                assertThat(sum(app,
                                "SELECT count(*) FROM payments.payment_attempt a"
                                        + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                        + " WHERE i.credit_account_id = ? AND a.rail = ?"
                                        + "   AND a.status IN ('CAPTURED', 'EXECUTED')",
                                merchant.payable(), rail))
                        .as("the %s merchant sold on the %s rail", merchant.policy(), rail)
                        .isPositive();
            }
        }
    }

    /** Every rail outcome the storm's instance counted: "rail|type|outcome" to its count. */
    private Map<String, Long> meteredJudgements() {
        return com.finapp.app.telemetry.RailOutcomeCounts.all(meterRegistry);
    }

    /**
     * Every judgement the tables hold, in the meter's own terms: each committed transition into a
     * judged state - an attempt's, a refund's, a withdrawal's - and each book attempt, born
     * EXECUTED inside its own transaction with no transition to record.
     */
    private static Map<String, Long> recordedJudgements(Connection app) throws SQLException {
        Map<String, Long> recorded = new java.util.TreeMap<>();
        for (String[] row :
                rows(app,
                        "SELECT a.rail,"
                                + " CASE WHEN e.to_status LIKE '%UNKNOWN' THEN 'unknown'"
                                + "      ELSE lower(e.to_status) END,"
                                + " count(*)::text"
                                + " FROM payments.payment_attempt_event e"
                                + " JOIN payments.payment_attempt a ON a.id = e.attempt_id"
                                + " WHERE e.to_status IN ('AUTHORIZED', 'CAPTURED', 'EXECUTED',"
                                + "   'VOIDED', 'FAILED', 'AUTH_UNKNOWN', 'CAPTURE_UNKNOWN',"
                                + "   'EXECUTION_UNKNOWN', 'VOID_UNKNOWN')"
                                + " GROUP BY 1, 2")) {
            recorded.merge(row[0] + "|payment|" + row[1], Long.parseLong(row[2]), Long::sum);
        }
        recorded.merge(
                "book|payment|executed",
                sum(app,
                        "SELECT count(*) FROM payments.payment_attempt"
                                + " WHERE rail = 'book' AND status = 'EXECUTED'"),
                Long::sum);
        for (String[] row :
                rows(app,
                        "SELECT a.rail, lower(e.to_status), count(*)::text"
                                + " FROM payments.refund_event e"
                                + " JOIN payments.refund r ON r.id = e.refund_id"
                                + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                + " WHERE e.to_status IN ('COMPLETED', 'FAILED', 'UNKNOWN')"
                                + " GROUP BY 1, 2")) {
            recorded.merge(row[0] + "|refund|" + row[1], Long.parseLong(row[2]), Long::sum);
        }
        for (String[] row :
                rows(app,
                        "SELECT w.rail, lower(e.to_status), count(*)::text"
                                + " FROM payments.withdrawal_event e"
                                + " JOIN payments.withdrawal w ON w.id = e.withdrawal_id"
                                + " WHERE e.to_status IN ('COMPLETED', 'FAILED', 'UNKNOWN')"
                                + " GROUP BY 1, 2")) {
            recorded.merge(row[0] + "|withdrawal|" + row[1], Long.parseLong(row[2]), Long::sum);
        }
        return recorded;
    }

    /** What moved between two tallies - the keys that did not move left out. */
    private static Map<String, Long> delta(Map<String, Long> after, Map<String, Long> before) {
        Map<String, Long> moved = new java.util.TreeMap<>();
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            long change = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
            if (change != 0) {
                moved.put(entry.getKey(), change);
            }
        }
        return moved;
    }

    /**
     * The boundaries were contested, as checked facts: a storm whose amounts never reached a
     * bound proves less than the suites it composes.
     */
    private static void contested(Storm storm) {
        Map<String, AtomicLong> outcomes = storm.outcomes;
        assertThat(outcomes.keySet())
                .as("a drained wallet refused a withdrawal (INV-BAL-04) - outcomes: %s", outcomes)
                .contains("withdrawal:payments.WithdrawalUnfunded");
        assertThat(outcomes.keySet())
                .as("a drained wallet refused a wallet payment - outcomes: %s", outcomes)
                .contains("wallet-sale:payments.WalletPaymentUnfunded");
        assertThat(outcomes.keySet())
                .as("a drained wallet failed a transfer - outcomes: %s", outcomes)
                .contains("transfer:FAILED");
        assertThat(outcomes.keySet())
                .as("the capture bound refused a refund - outcomes: %s", outcomes)
                .contains("refund:payments.RefundExceedsCaptured");
        assertThat(outcomes.keySet())
                .as("duplicates were delivered and were harmless - outcomes: %s", outcomes)
                .contains("refund:replayed", "transfer:replayed", "withdrawal:retried");
        assertThat(outcomes.keySet())
                .as("pay-ins, card top-ups and every checkout rail ran - outcomes: %s", outcomes)
                .contains("top-up:card", "top-up:pay-in", "sale:CARD", "sale:BANK", "sale:WALLET");
    }

    // ----------------------------------------------------------------- the movers

    /** A customer topping up: by card (every fifth capture answer lost), then by bank. */
    private Callable<Void> topUpper(Storm storm) {
        return () -> {
            int round = 0;
            int cards = 0;
            int payIns = 0;
            while (!storm.stop.get()) {
                Customer customer = storm.world.customers().get(round % CUSTOMERS);
                round++;
                if (round % 2 == 1) {
                    cards++;
                    long amount = cards % 5 == 0 ? LOST_CARD_TOP_UP : CARD_TOP_UP;
                    HttpResponse<String> confirmed = pay(customer, customer.card(), amount);
                    String status = field(confirmed.body(), "status");
                    if (confirmed.statusCode() != 200
                            || !(status.equals("SUCCEEDED") || status.equals("PROCESSING"))) {
                        throw unexpected("card top-up", confirmed, storm);
                    }
                    storm.committed("top-up:card");
                } else {
                    payIns++;
                    long amount = payIns % 4 == 0 ? LOST_PAY_IN : PAY_IN;
                    HttpResponse<String> confirmed = pay(customer, customer.bank(), amount);
                    if (confirmed.statusCode() != 200
                            || !"PROCESSING".equals(field(confirmed.body(), "status"))) {
                        throw unexpected("pay-in", confirmed, storm);
                    }
                    storm.committed("top-up:pay-in");
                }
            }
            return null;
        };
    }

    /**
     * A customer shopping: a checkout paid by card, by bank or from the wallet, in turn - every
     * fifth wallet checkout priced past anything a wallet holds.
     */
    private Callable<Void> shopper(Storm storm, int index) {
        return () -> {
            int round = 0;
            while (!storm.stop.get()) {
                Merchant merchant = storm.world.merchants().get((index + round) % 2);
                Customer customer = storm.world.customers().get((index + round) % CUSTOMERS);
                Instrument instrument = Instrument.values()[round % Instrument.values().length];
                round++;
                long price =
                        instrument == Instrument.WALLET && round % 5 == 0 ? UNAFFORDABLE : SALE;
                String session = openSession(merchant, storm, price);
                HttpResponse<String> confirmed = confirm(customer, session, instrument);
                String outcome = saleOutcome(instrument, confirmed);
                if (outcome == null) {
                    throw unexpected("sale by " + instrument, confirmed, storm);
                }
                storm.committed(outcome);
            }
            return null;
        };
    }

    /**
     * A customer withdrawing, every fourth answer lost and then RETRIED under the same key, every
     * seventh asking for more than any wallet holds.
     */
    private Callable<Void> withdrawer(Storm storm) {
        return () -> {
            int round = 0;
            while (!storm.stop.get()) {
                Customer customer = storm.world.customers().get(round % CUSTOMERS);
                round++;
                long amount =
                        round % 7 == 0
                                ? UNAFFORDABLE
                                : round % 4 == 0 ? LOST_WITHDRAWAL : WITHDRAWAL;
                String key = someKey();
                HttpResponse<String> answer = withdraw(customer, amount, key);
                if (answer.statusCode() == 201) {
                    String status = field(answer.body(), "status");
                    storm.committed("withdrawal:" + status);
                    if (status.equals("UNKNOWN")) {
                        // The client's own retry after a timeout: the same key replays the
                        // claim's recorded answer and sends nothing twice (INV-PAY-04).
                        HttpResponse<String> retried = withdraw(customer, amount, key);
                        if (retried.statusCode() != 201
                                || !field(retried.body(), "id")
                                        .equals(field(answer.body(), "id"))) {
                            throw unexpected("withdrawal retry", retried, storm);
                        }
                        storm.count("withdrawal:retried");
                    }
                } else if (answer.statusCode() == 422
                        && "payments.WithdrawalUnfunded".equals(code(answer))) {
                    storm.committed("withdrawal:payments.WithdrawalUnfunded");
                } else {
                    throw unexpected("withdrawal", answer, storm);
                }
            }
            return null;
        };
    }

    /**
     * Customers paying each other, round the ring, every success replayed once and every fourth
     * transfer asking for more than any wallet holds.
     */
    private Callable<Void> transferrer(Storm storm) {
        return () -> {
            int round = 0;
            while (!storm.stop.get()) {
                Customer source = storm.world.customers().get(round % CUSTOMERS);
                Customer destination = storm.world.customers().get((round + 1) % CUSTOMERS);
                round++;
                long amount = round % 4 == 0 ? UNAFFORDABLE : TRANSFER;
                String key = someKey();
                HttpResponse<String> answer = transfer(source, destination, amount, key);
                if (answer.statusCode() != 201) {
                    throw unexpected("transfer", answer, storm);
                }
                String status = field(answer.body(), "status");
                storm.committed("transfer:" + status);
                if (status.equals("COMPLETED")) {
                    HttpResponse<String> replay = transfer(source, destination, amount, key);
                    if (replay.statusCode() != 201
                            || !field(replay.body(), "id").equals(field(answer.body(), "id"))) {
                        throw unexpected("transfer replay", replay, storm);
                    }
                    storm.count("transfer:replayed");
                }
            }
            return null;
        };
    }

    /**
     * An operator refunding storm payments found IN THE DATABASE whatever their state - top-ups
     * and sales on every rail - every fifth one asked for a unit more than was paid, every
     * success replayed once under its key.
     */
    private Callable<Void> refunder(Storm storm) {
        return () -> {
            int round = 0;
            while (!storm.stop.get()) {
                List<String[]> recent = recentPayments(storm.world);
                if (recent.isEmpty()) {
                    Thread.onSpinWait();
                    continue;
                }
                String[] subject = recent.get(round % recent.size());
                round++;
                long amount = Long.parseLong(subject[1]) + (round % 5 == 0 ? 1 : 0);
                String key = someKey();
                HttpResponse<String> answer = refund(storm, subject[0], amount, key);
                String outcome = refundOutcome(answer);
                if (outcome == null) {
                    throw unexpected("refund", answer, storm);
                }
                storm.committed(outcome);
                if (answer.statusCode() == 201) {
                    HttpResponse<String> replay = refund(storm, subject[0], amount, key);
                    if (replay.statusCode() != 201
                            || !field(replay.body(), "id").equals(field(answer.body(), "id"))) {
                        throw unexpected("refund replay", replay, storm);
                    }
                    storm.count("refund:replayed");
                }
            }
            return null;
        };
    }

    /**
     * The rails calling back: every waiting pay-in's execution delivered three times (twice
     * under one event id, once under a fresh one) - except the lost ones, which only an inquiry
     * resolves, and which hear a late duplicate once they have; every captured card attempt's
     * clearing notice twice.
     */
    private Callable<Void> callbacker(Storm storm) {
        return () -> {
            while (!storm.stop.get()) {
                int delivered = deliverPendingCallbacks(storm);
                if (delivered == 0) {
                    Thread.sleep(20);
                }
            }
            return null;
        };
    }

    /**
     * The card network disputing sales: a chargeback on a fresh sale with a capture reference
     * (full or partial, a fee on every third), delivered three times; and an open dispute
     * resolved WON or LOST in turn, delivered three times too.
     */
    private Callable<Void> disputer(Storm storm) {
        return () -> {
            int round = 0;
            while (!storm.stop.get()) {
                round++;
                if (round % 3 != 0) {
                    List<String[]> candidates = undisputedSales(storm);
                    if (candidates.isEmpty()) {
                        Thread.sleep(20);
                        continue;
                    }
                    String[] sale = candidates.get(round % candidates.size());
                    UUID attempt = UUID.fromString(sale[0]);
                    long amount = round % 2 == 0 ? SALE : PARTIAL_CHARGEBACK;
                    Long fee = round % 3 == 1 ? DISPUTE_FEE : null;
                    String reference = "dsp-" + IDS.next();
                    if (storm.disputes.putIfAbsent(attempt, reference) != null) {
                        continue;
                    }
                    storm.disputeAmounts.put(reference, amount);
                    deliverThrice(storm, chargeback(sale[1], reference, "needs_response", amount,
                            fee));
                    storm.resolvable.add(reference);
                    storm.committed("dispute:opened");
                } else {
                    String reference = storm.resolvable.poll();
                    if (reference == null) {
                        continue;
                    }
                    String capture = storm.captureOf(reference);
                    String stage = round % 2 == 0 ? "won" : "lost";
                    deliverThrice(storm, chargeback(capture, reference, stage,
                            storm.disputeAmounts.get(reference), null));
                    storm.committed("dispute:" + stage);
                }
            }
            return null;
        };
    }

    /**
     * A resolver: the lost answers given by our reference, then every sweep. Two of them race -
     * the leaderless pattern, where the conditional transitions are the only arbiter.
     */
    private Callable<Void> resolver(Storm storm) {
        return () -> {
            while (!storm.stop.get()) {
                answerInquiries(storm, false);
                sweepAll();
                storm.committed("resolver:swept");
                Thread.sleep(100);
            }
            return null;
        };
    }

    /**
     * Ten-way bursts, one kind at a time: a session confirmed ten times at once, a withdrawal
     * sent ten times under one key, a refund ten times under one key, a chargeback notified ten
     * times (five under one event id, five fresh), and four partial refunds racing a full
     * chargeback for one sale's headroom.
     */
    private Callable<Void> duplicator(Storm storm) {
        return () -> {
            ExecutorService racers = Executors.newFixedThreadPool(10);
            try {
                int round = 0;
                long next = 20;
                while (!storm.stop.get()) {
                    if (storm.committed.get() < next) {
                        Thread.sleep(20);
                        continue;
                    }
                    next = storm.committed.get() + 30;
                    BurstKind kind = BurstKind.values()[round % BurstKind.values().length];
                    round++;
                    Burst burst = burst(storm, racers, kind, round);
                    if (burst != null) {
                        storm.bursts.add(burst);
                        storm.committed("burst:" + kind);
                    }
                }
            } finally {
                racers.shutdownNow();
            }
            return null;
        };
    }

    private Burst burst(Storm storm, ExecutorService racers, BurstKind kind, int round)
            throws Exception {
        Customer customer = storm.world.customers().get(round % CUSTOMERS);
        switch (kind) {
            case CONFIRM -> {
                Merchant merchant = storm.world.merchants().get(round % 2);
                HttpResponse<String> opened = openSessionResponse(merchant);
                String session = field(opened.body(), "sessionToken");
                String checkout = field(opened.body(), "checkoutId");
                for (HttpResponse<String> answer :
                        race(racers, 10, () -> confirm(customer, session, Instrument.CARD))) {
                    if (answer.statusCode() != 200
                            && !answer.body().contains("IdempotencyInProgress")) {
                        throw unexpected("burst confirm", answer, storm);
                    }
                }
                return new Burst(kind, checkout);
            }
            case WITHDRAW -> {
                String key = someKey();
                Set<String> answered = new java.util.HashSet<>();
                for (HttpResponse<String> answer :
                        race(racers, 10, () -> withdraw(customer, WITHDRAWAL, key))) {
                    int status = answer.statusCode();
                    boolean domain =
                            status == 201
                                    || answer.body().contains("IdempotencyInProgress")
                                    || (status == 422
                                            && "payments.WithdrawalUnfunded".equals(code(answer)));
                    if (!domain) {
                        throw unexpected("burst withdrawal", answer, storm);
                    }
                    if (status == 201) {
                        answered.add(field(answer.body(), "id"));
                    }
                }
                return new Burst(kind, key, answered);
            }
            case REFUND -> {
                List<String[]> sales = capturedCardSales(storm.world);
                if (sales.isEmpty()) {
                    return null;
                }
                String intent = sales.get(round % sales.size())[2];
                String key = someKey();
                Set<String> answered = new java.util.HashSet<>();
                for (HttpResponse<String> answer :
                        race(racers, 10, () -> refund(storm, intent, SALE, key))) {
                    if (refundOutcome(answer) == null
                            && !answer.body().contains("IdempotencyInProgress")) {
                        throw unexpected("burst refund", answer, storm);
                    }
                    if (answer.statusCode() == 201) {
                        answered.add(field(answer.body(), "id"));
                    }
                }
                return new Burst(kind, key, answered);
            }
            case CHARGEBACK -> {
                List<String[]> sales = undisputedSales(storm);
                if (sales.isEmpty()) {
                    return null;
                }
                String[] sale = sales.get(round % sales.size());
                String reference = "dsp-burst-" + IDS.next();
                if (storm.disputes.putIfAbsent(UUID.fromString(sale[0]), reference) != null) {
                    return null;
                }
                storm.disputeAmounts.put(reference, SALE);
                String eventId = "evt_" + IDS.next();
                List<String> bodies = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    bodies.add(chargebackWithEvent(i < 5 ? eventId : "evt_" + IDS.next(),
                            sale[1], reference, "needs_response", SALE, null));
                }
                for (Integer status : raceDeliveries(racers, bodies)) {
                    if (status != 204) {
                        throw new AssertionError("burst chargeback answered " + status);
                    }
                }
                return new Burst(kind, reference);
            }
            case RACE -> {
                String[] sale = deskSale(storm, customer);
                String reference = "dsp-race-" + IDS.next();
                storm.disputes.put(UUID.fromString(sale[0]), reference);
                storm.disputeAmounts.put(reference, SALE);
                CountDownLatch open = new CountDownLatch(1);
                List<Future<Object>> raced = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    raced.add(racers.submit(() -> {
                        open.await();
                        HttpResponse<String> answer =
                                refund(storm, sale[2], RACING_REFUND, someKey());
                        if (refundOutcome(answer) == null) {
                            throw unexpected("raced refund", answer, storm);
                        }
                        return null;
                    }));
                }
                String body = chargeback(sale[1], reference, "needs_response", SALE, null);
                raced.add(racers.submit(() -> {
                    open.await();
                    int status = deliverCard(body);
                    if (status != 204) {
                        throw new AssertionError("raced chargeback answered " + status);
                    }
                    return null;
                }));
                open.countDown();
                for (Future<Object> racer : raced) {
                    racer.get(2, TimeUnit.MINUTES);
                }
                return new Burst(kind, sale[0]);
            }
            default -> throw new IllegalStateException("unknown burst " + kind);
        }
    }

    /**
     * Mid-storm, on the controller: one captured sale refunded in full and THEN charged back (the
     * excess, the whole chargeback, rests in the recoverable), another charged back in full and
     * THEN refunded (refused past what remains) - the two orders of ADR-0061's arithmetic, each
     * made to happen rather than hoped for.
     */
    private Choreography choreograph(Storm storm) throws Exception {
        String[] first = deskSale(storm, storm.world.customers().get(1));
        String[] second = deskSale(storm, storm.world.customers().get(2));
        String firstReference = "dsp-chor-" + IDS.next();
        String secondReference = "dsp-chor-" + IDS.next();
        storm.disputes.put(UUID.fromString(first[0]), firstReference);
        storm.disputes.put(UUID.fromString(second[0]), secondReference);
        storm.disputeAmounts.put(firstReference, SALE);
        storm.disputeAmounts.put(secondReference, SALE);

        HttpResponse<String> refunded = refund(storm, first[2], SALE, someKey());
        assertThat(refunded.statusCode()).as(refunded.body()).isEqualTo(201);
        assertThat(field(refunded.body(), "status")).isEqualTo("COMPLETED");
        assertThat(deliverCard(chargeback(first[1], firstReference, "needs_response", SALE, null)))
                .isEqualTo(204);

        assertThat(deliverCard(chargeback(second[1], secondReference, "needs_response", SALE,
                        null)))
                .isEqualTo(204);
        HttpResponse<String> refused = refund(storm, second[2], SALE, someKey());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        return new Choreography(first[0], code(refused));
    }

    // ----------------------------------------------------------------- the resolvers' work

    /**
     * The providers answering inquiries TRUTHFULLY, by OUR reference - what a real PSP or scheme
     * does about an operation whose answer was lost: approved (accepted, on the scheme) when the
     * request reached it, and, once the traffic has stopped, unrecognised when it never did. The
     * storm's chosen losses are the certain cases; a transport failure under load - an
     * {@code IOException} the wire client rightly folds into ambiguity - is resolved exactly the
     * same way, which is what the gate's first scenario says ambiguity is for. The lost pay-ins
     * the payer executed. Registered per reference, never for a path: another suite's leftover
     * rows stay unanswered (a 404 is not an answer), so the storm resolves only its own.
     */
    private void answerInquiries(Storm storm, boolean atRest) throws SQLException {
        UUID[] accounts = storm.world.accounts();
        try (Connection app = DatabaseRoles.application()) {
            for (String[] attempt :
                    rows(app,
                            "SELECT a.status, a.auth_reference, a.capture_reference"
                                    + " FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'card'"
                                    + "   AND a.status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN',"
                                    + "                    'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN')"
                                    + "   AND i.credit_account_id = ANY (?)",
                            (Object) accounts)) {
                boolean authorising = attempt[0].startsWith("AUTH");
                answerCard(storm,
                        authorising ? attempt[1] : attempt[2],
                        authorising
                                ? SimulatedCardPspAdapter.AUTHORIZATIONS_PATH
                                : SimulatedCardPspAdapter.CAPTURES_PATH,
                        authorising ? "psp_a" : "psp_c",
                        atRest);
            }
            // A void the redirect sent (the Phase 7 -> 8 transition): asked by its own
            // reference, answered as truthfully as every other operation.
            for (String reference :
                    strings(app,
                            "SELECT a.void_reference FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'card'"
                                    + "   AND a.status IN ('VOID_DISPATCHED', 'VOID_UNKNOWN')"
                                    + "   AND i.credit_account_id = ANY (?)",
                            (Object) accounts)) {
                answerCard(storm, reference, SimulatedCardPspAdapter.VOIDS_PATH, "psp_v", atRest);
            }
            for (String reference :
                    strings(app,
                            "SELECT r.provider_idempotency_reference FROM payments.refund r"
                                    + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'card'"
                                    + "   AND r.status IN ('DISPATCHED', 'UNKNOWN')"
                                    + "   AND i.credit_account_id = ANY (?)",
                            (Object) accounts)) {
                answerCard(storm, reference, SimulatedCardPspAdapter.REFUNDS_PATH, "psp_r", atRest);
            }
            for (String reference :
                    strings(app,
                            "SELECT end_to_end_reference FROM payments.withdrawal"
                                    + " WHERE status IN ('DISPATCHED', 'UNKNOWN')"
                                    + "   AND wallet_account_id = ANY (?)",
                            (Object) storm.world.wallets())) {
                answerScheme(storm, reference, SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                        SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH, "sch-w", atRest);
            }
            for (String reference :
                    strings(app,
                            "SELECT r.provider_idempotency_reference FROM payments.refund r"
                                    + " JOIN payments.payment_attempt a ON a.id = r.attempt_id"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'instant'"
                                    + "   AND r.status IN ('DISPATCHED', 'UNKNOWN')"
                                    + "   AND i.credit_account_id = ANY (?)",
                            (Object) accounts)) {
                answerScheme(storm, reference, SimulatedInstantSchemeAdapter.RETURNS_PATH,
                        SimulatedInstantSchemeAdapter.RETURN_STATUS_PATH, "sch-r", atRest);
            }
            for (String reference :
                    strings(app,
                            "SELECT a.end_to_end_reference FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'instant' AND i.amount_minor = ?"
                                    + "   AND a.end_to_end_reference IS NOT NULL"
                                    + "   AND a.status IN ('AWAITING_PAYER', 'EXECUTION_UNKNOWN')"
                                    + "   AND i.credit_account_id = ANY (?)",
                            LOST_PAY_IN, storm.world.wallets())) {
                if (storm.answered.add(reference)) {
                    provider.succeedsWith(
                            SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + reference,
                            200,
                            "{\"status\":\"accepted\",\"reference\":\"sch-pq-" + reference
                                    + "\",\"cycle\":\"C4\",\"amount\":\"15.07\","
                                    // The executed amount the applier judges (the
                                    // Phase 7 -> 8 transition): the lost pay-in's ask.
                                    + "\"currency\":\"EUR\"}");
                }
            }
        }
    }

    /** The card PSP's truthful answer about {@code reference}, sent on {@code path}. */
    private void answerCard(
            Storm storm, String reference, String path, String prefix, boolean atRest) {
        if (reference == null || storm.answered.contains(reference)) {
            return;
        }
        if (provider.headerValues(path, "Idempotency-Key").contains(reference)) {
            if (storm.answered.add(reference)) {
                provider.succeedsWith(
                        SimulatedCardPspAdapter.OPERATIONS_PATH + reference,
                        200,
                        "{\"status\":\"approved\",\"reference\":\"" + prefix + "-" + reference
                                + "\"}");
                storm.count("inquiry:answered");
            }
        } else if (atRest && storm.answered.add(reference)) {
            provider.succeedsWith(
                    SimulatedCardPspAdapter.OPERATIONS_PATH + reference,
                    200,
                    "{\"status\":\"unrecognised\"}");
            storm.count("inquiry:unrecognised");
        }
    }

    /** The scheme's truthful answer about {@code reference}, sent on {@code path}. */
    private void answerScheme(
            Storm storm,
            String reference,
            String path,
            String statusPath,
            String prefix,
            boolean atRest) {
        if (reference == null || storm.answered.contains(reference)) {
            return;
        }
        if (provider.headerValues(path, "Idempotency-Key").contains(reference)) {
            if (storm.answered.add(reference)) {
                provider.succeedsWith(
                        statusPath + reference,
                        200,
                        "{\"status\":\"accepted\",\"reference\":\"" + prefix + "-" + reference
                                + "\",\"cycle\":\"C2\"}");
                storm.count("inquiry:answered");
            }
        } else if (atRest && storm.answered.add(reference)) {
            provider.succeedsWith(statusPath + reference, 200, "{\"status\":\"unrecognised\"}");
            storm.count("inquiry:unrecognised");
        }
    }

    /**
     * Every resolver the platform runs, once: the card sweep, and the three instant ones. The card
     * sweep counts a row it could not process; the instant sweeps contain one per row and log it,
     * and such a row would stay in flight - which the drain refuses at rest.
     */
    private void sweepAll() {
        assertThat(paymentSweeper.sweep().failedRows())
                .as("no row failed the card sweep: every resolution is a domain outcome")
                .isZero();
        withdrawalResolution.sweep();
        payInResolution.sweep();
        returnResolution.sweep();
    }

    /**
     * One pass of the rails' callbacks; answers how many deliveries it made. Executions are
     * delivered with the intent's own amount (the door refuses any other).
     */
    private int deliverPendingCallbacks(Storm storm) throws Exception {
        int delivered = 0;
        List<String[]> waiting;
        List<String[]> late;
        List<String[]> captured;
        try (Connection app = DatabaseRoles.application()) {
            waiting =
                    rows(app,
                            "SELECT a.id::text, a.end_to_end_reference, i.amount_minor::text"
                                    + " FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'instant' AND a.status = 'AWAITING_PAYER'"
                                    + "   AND a.end_to_end_reference IS NOT NULL"
                                    + "   AND i.amount_minor <> ?"
                                    + "   AND i.credit_account_id = ANY (?)",
                            LOST_PAY_IN, storm.world.accounts());
            late =
                    rows(app,
                            "SELECT a.id::text, a.end_to_end_reference, i.amount_minor::text"
                                    + " FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'instant' AND a.status = 'EXECUTED'"
                                    + "   AND i.amount_minor = ?"
                                    + "   AND i.credit_account_id = ANY (?)",
                            LOST_PAY_IN, storm.world.wallets());
            captured =
                    rows(app,
                            "SELECT a.id::text, a.capture_reference, ''"
                                    + " FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'card' AND a.status = 'CAPTURED'"
                                    + "   AND i.credit_account_id = ANY (?)",
                            (Object) storm.world.accounts());
        }
        for (String[] payIn : waiting) {
            if (!storm.executedCallbacks.add(UUID.fromString(payIn[0]))) {
                continue;
            }
            String eventId = "evt_" + IDS.next();
            String scheme = "sch-cb-" + IDS.next();
            String amount = BigDecimal.valueOf(Long.parseLong(payIn[2]), 2).toPlainString();
            for (String event : List.of(eventId, eventId, "evt_" + IDS.next())) {
                int status = deliverInstant(executed(event, payIn[1], scheme, amount));
                if (status != 204) {
                    throw new AssertionError("an execution callback answered " + status);
                }
                delivered++;
            }
            storm.committed("callback:executed");
        }
        for (String[] payIn : late) {
            if (!storm.lateCallbacks.add(UUID.fromString(payIn[0]))) {
                continue;
            }
            // The confirmation that never came, arriving after the inquiry resolved it: a late
            // duplicate, harmless.
            String amount = BigDecimal.valueOf(Long.parseLong(payIn[2]), 2).toPlainString();
            int status =
                    deliverInstant(executed("evt_" + IDS.next(), payIn[1], "sch-late-" + IDS.next(),
                            amount));
            if (status != 204) {
                throw new AssertionError("a late execution callback answered " + status);
            }
            delivered++;
            storm.committed("callback:late");
        }
        for (String[] attempt : captured) {
            if (!storm.clearingNotices.add(UUID.fromString(attempt[0]))) {
                continue;
            }
            String eventId = "evt_" + IDS.next();
            String arn = "arn-" + IDS.next();
            for (String event : List.of(eventId, "evt_" + IDS.next())) {
                int status = deliverCard(clearing(event, attempt[1], arn));
                if (status != 204) {
                    throw new AssertionError("a clearing notice answered " + status);
                }
                delivered++;
            }
            storm.committed("callback:cleared");
        }
        return delivered;
    }

    /** Three deliveries of one card statement: twice under its event id, once under a fresh one. */
    private void deliverThrice(Storm storm, String body) {
        String again = body.replaceFirst("\"eventId\":\"[^\"]+\"", "\"eventId\":\"evt_" + IDS.next()
                + "\"");
        for (String delivery : List.of(body, body, again)) {
            int status = deliverCard(delivery);
            if (status != 204) {
                throw new AssertionError("a dispute notification answered " + status + ": "
                        + delivery + " (outcomes so far: " + storm.outcomes + ")");
            }
        }
    }

    // ----------------------------------------------------------------- the storm's own state

    /**
     * The storm's bookkeeping - the test's, not the application's: what it delivered, whom it
     * disputed, which answers it gave, what each burst was about. Nothing the platform reads.
     */
    private static final class Storm {
        final World world;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong committed = new AtomicLong(0);
        final Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();
        final Set<UUID> executedCallbacks = ConcurrentHashMap.newKeySet();
        final Set<UUID> lateCallbacks = ConcurrentHashMap.newKeySet();
        final Set<UUID> clearingNotices = ConcurrentHashMap.newKeySet();
        final Set<String> answered = ConcurrentHashMap.newKeySet();
        /** attempt -> the network's dispute reference. */
        final Map<UUID, String> disputes = new ConcurrentHashMap<>();
        final Map<String, Long> disputeAmounts = new ConcurrentHashMap<>();
        /** The disputer's own open disputes, each resolved once, WON or LOST. */
        final java.util.Queue<String> resolvable =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final List<Burst> bursts = Collections.synchronizedList(new ArrayList<>());

        Storm(World world) {
            this.world = world;
        }

        void count(String key) {
            outcomes.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        }

        void committed(String key) {
            committed.incrementAndGet();
            count(key);
        }

        String captureOf(String reference) throws SQLException {
            try (Connection app = DatabaseRoles.application()) {
                return one(app,
                        "SELECT a.capture_reference FROM payments.dispute d"
                                + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                                + " WHERE d.provider_dispute_reference = ?",
                        reference);
            }
        }
    }

    private enum Instrument {
        CARD,
        BANK,
        WALLET
    }

    private enum BurstKind {
        CONFIRM,
        WITHDRAW,
        REFUND,
        CHARGEBACK,
        RACE
    }

    /** One ten-way burst: what it was about, and every id a racer was answered with. */
    private record Burst(BurstKind kind, String subject, Set<String> answered) {
        Burst(BurstKind kind, String subject) {
            this(kind, subject, Set.of());
        }
    }

    private record Choreography(String refundedFirst, String refundAfterChargeback) {}

    // ----------------------------------------------------------------- the world

    private record Customer(String token, String product, UUID wallet, String card, String bank) {}

    private record Merchant(String id, String key, String policy, UUID payable) {
        Merchant withPayable(UUID account) {
            return new Merchant(id, key, policy, account);
        }
    }

    /**
     * The storm's population. {@code merchants} trade with everyone; {@code desk} is the dispute
     * desk's own merchant, whose sales only the choreography and the INV-DSP-01 race touch, so
     * those two can make an ORDER happen (a refund before its chargeback, a chargeback before its
     * refund) that another mover's refund would otherwise pre-empt. Every one is reconciled.
     */
    private record World(
            List<Customer> customers, List<Merchant> merchants, Merchant desk, String operator) {
        UUID[] wallets() {
            return customers.stream().map(Customer::wallet).toArray(UUID[]::new);
        }

        /** The trading merchants' payables - the ones the movers pick their subjects from. */
        UUID[] tradingPayables() {
            return merchants.stream().map(Merchant::payable).toArray(UUID[]::new);
        }

        /** Every storm payable, the desk's included. */
        UUID[] payables() {
            return everyMerchant().stream().map(Merchant::payable).toArray(UUID[]::new);
        }

        List<Merchant> everyMerchant() {
            return Stream.concat(merchants.stream(), Stream.of(desk)).toList();
        }

        /** Every storm-owned account: the reconciliation's scope. */
        UUID[] accounts() {
            return Stream.concat(Stream.of(wallets()), Stream.of(payables()))
                    .toArray(UUID[]::new);
        }

        /** The accounts the movers act on: every wallet and the trading payables. */
        UUID[] tradingAccounts() {
            return Stream.concat(Stream.of(wallets()), Stream.of(tradingPayables()))
                    .toArray(UUID[]::new);
        }
    }

    /**
     * Four verified customers, each with a EUR wallet, a card and a bank account registered
     * through the real exchange; two trading merchants, one under each refund-fee policy, and
     * the dispute desk's merchant; and an operator who refunds.
     */
    private World world() throws Exception {
        String administrator = operatorSession(RoleName.MERCHANT_ADMINISTRATOR);
        String operator = operatorSession(RoleName.LEDGER_OPERATOR);
        List<Customer> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMERS; i++) {
            customers.add(customer());
        }
        List<Merchant> merchants = new ArrayList<>();
        for (String policy : List.of("RETAINED", "RETURNED")) {
            merchants.add(merchant(administrator, policy));
        }
        return new World(
                List.copyOf(customers),
                List.copyOf(merchants),
                merchant(administrator, "RETAINED"),
                operator);
    }

    /**
     * PRIMED: every customer funded by card and by bank, every trading merchant selling once on
     * each rail and the desk once by card - so the first round already reads both clearing
     * positions, every wallet and every payable, and the movers contend from their first turn (a
     * reading over an empty set asserts nothing). Answers the world with its payables, which
     * exist once the merchants have sold.
     */
    private World prime(World world) throws Exception {
        Storm primer = new Storm(world);
        for (Customer customer : world.customers()) {
            assertThat(field(pay(customer, customer.card(), 40_00).body(), "status"))
                    .isEqualTo("SUCCEEDED");
            assertThat(field(pay(customer, customer.bank(), PAY_IN).body(), "status"))
                    .isEqualTo("PROCESSING");
        }
        for (int i = 0; i < world.merchants().size(); i++) {
            Merchant merchant = world.merchants().get(i);
            for (Instrument instrument : Instrument.values()) {
                HttpResponse<String> confirmed =
                        confirm(world.customers().get(i), openSession(merchant, primer),
                                instrument);
                assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
            }
        }
        assertThat(field(confirm(world.customers().get(0), openSession(world.desk(), primer),
                                Instrument.CARD).body(), "status"))
                .isEqualTo("COMPLETED");
        List<Merchant> merchants = new ArrayList<>();
        Merchant desk;
        try (Connection app = DatabaseRoles.application()) {
            for (Merchant merchant : world.merchants()) {
                merchants.add(merchant.withPayable(payableOf(app, merchant)));
            }
            desk = world.desk().withPayable(payableOf(app, world.desk()));
        }
        World primed = new World(world.customers(), List.copyOf(merchants), desk,
                world.operator());
        // The pending pay-ins and bank sales executed, now that their accounts are known.
        deliverPendingCallbacks(new Storm(primed));
        return primed;
    }

    private static UUID payableOf(Connection app, Merchant merchant) throws SQLException {
        return UUID.fromString(
                one(app,
                        "SELECT id::text FROM ledger.ledger_account WHERE owner_ref = ?::uuid"
                                + " AND purpose = 'MERCHANT_PAYABLE' AND currency = 'EUR'",
                        merchant.id()));
    }

    private Customer customer() throws Exception {
        String login = "mrs." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "UPDATE party.customer SET status = 'ACTIVE', status_changed_at ="
                            + " GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id"
                            + " FROM identity.identity WHERE login_identifier = ?)",
                    login);
        }
        String token = field(authenticate(login).body(), "sessionToken");
        HttpResponse<String> opened =
                post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                        token, someKey());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String product = field(opened.body(), "id");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached =
                post("/v1/me/payment-methods", "{\"clientToken\":\"ctok_" + suffix + "\"}",
                        token, null);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);

        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-mrs-" + UUID.randomUUID()
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered =
                post("/v1/me/payment-methods/bank-accounts",
                        "{\"grant\":\"blg-" + UUID.randomUUID()
                                + "\",\"acknowledgeNoMatch\":false}",
                        token, someKey());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);

        UUID wallet;
        try (Connection app = DatabaseRoles.application()) {
            wallet =
                    UUID.fromString(
                            one(app,
                                    "SELECT id::text FROM ledger.ledger_account"
                                            + " WHERE owner_ref = ?::uuid"
                                            + "   AND purpose = 'CUSTOMER_WALLET'",
                                    product));
        }
        return new Customer(token, product, wallet, field(attached.body(), "id"),
                field(registered.body(), "id"));
    }

    /** An onboarded, ACTIVE merchant, priced at 2.9% + 0.30 under {@code policy}, keyed. */
    private Merchant merchant(String administrator, String policy) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, 'ORGANISATION', 'Storm Traders', now())",
                    party);
            execute(app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now())",
                    IDS.next(), party);
        }
        HttpResponse<String> created =
                post("/v1/operator/merchants",
                        "{\"partyId\":\"" + party + "\",\"legalName\":\"Storm Traders GmbH\","
                                + "\"displayName\":\"Storm\",\"settlementCurrency\":\"EUR\"}",
                        administrator, someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String merchantId = field(created.body(), "merchantId");
        String schedule =
                field(post("/v1/operator/fee-schedules",
                                        "{\"name\":\"Storm " + UUID.randomUUID()
                                                + "\",\"currency\":\"EUR\"}",
                                        administrator, null)
                                .body(),
                        "feeScheduleId");
        assertThat(post("/v1/operator/fee-schedules/" + schedule + "/versions",
                                "{\"rate\":0.029,\"fixedAmountMinor\":30,"
                                        + "\"roundingPolicy\":\"HALF_EVEN\","
                                        + "\"refundFeePolicy\":\"" + policy + "\","
                                        + "\"reason\":\"storm pricing\"}",
                                administrator, null)
                        .statusCode())
                .isEqualTo(201);
        assertThat(put("/v1/operator/merchants/" + merchantId + "/fee-schedule",
                                "{\"feeScheduleId\":\"" + schedule
                                        + "\",\"reason\":\"standard terms\"}",
                                administrator)
                        .statusCode())
                .isEqualTo(200);
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + merchantId + "/api-keys", null, administrator,
                        someKey());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return new Merchant(merchantId,
                field(issued.body(), "keyId") + "." + field(issued.body(), "secret"), policy,
                null);
    }

    /** A signed-in staff member holding exactly {@code role}. */
    private String operatorSession(RoleName role) throws Exception {
        String login = "ops." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application()) {
            identity =
                    UUID.fromString(
                            one(app,
                                    "SELECT id::text FROM identity.identity"
                                            + " WHERE login_identifier = ?",
                                    login));
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity),
                    "test fixture");
            app.commit();
        }
        return field(authenticate(login).body(), "sessionToken");
    }

    // ----------------------------------------------------------------- the calls

    /** Creates and confirms a top-up of {@code amountMinor} on {@code method}. */
    private HttpResponse<String> pay(Customer customer, String method, long amountMinor)
            throws Exception {
        HttpResponse<String> created =
                post("/v1/payments",
                        "{\"paymentMethodId\":\"" + method + "\",\"amount\":\""
                                + decimal(amountMinor) + "\",\"currency\":\"EUR\"}",
                        customer.token(), someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return post("/v1/payments/" + field(created.body(), "id") + "/confirmation", null,
                customer.token(), null);
    }

    private String openSession(Merchant merchant, Storm storm) throws Exception {
        return openSession(merchant, storm, SALE);
    }

    private String openSession(Merchant merchant, Storm storm, long amountMinor)
            throws Exception {
        HttpResponse<String> created = openSessionResponse(merchant, amountMinor);
        if (created.statusCode() != 201) {
            throw unexpected("open", created, storm);
        }
        return field(created.body(), "sessionToken");
    }

    private HttpResponse<String> openSessionResponse(Merchant merchant) throws Exception {
        return openSessionResponse(merchant, SALE);
    }

    private HttpResponse<String> openSessionResponse(Merchant merchant, long amountMinor)
            throws Exception {
        return post("/v1/checkout/sessions",
                "{\"amountMinor\":" + amountMinor + ",\"currency\":\"EUR\","
                        + "\"lineSummary\":\"A storm of coffees\"}",
                merchant.key(), someKey());
    }

    private HttpResponse<String> confirm(Customer customer, String session, Instrument instrument)
            throws Exception {
        String arm =
                switch (instrument) {
                    case CARD -> "\"paymentMethodId\":\"" + customer.card() + "\"";
                    case BANK -> "\"paymentMethodId\":\"" + customer.bank() + "\"";
                    case WALLET -> "\"instrument\":\"WALLET\"";
                };
        return post("/v1/checkout/sessions/confirmation",
                "{\"sessionToken\":\"" + session + "\"," + arm + "}", customer.token(), null);
    }

    /** A checkout's answer as a domain outcome, or null when it is not one. */
    private static String saleOutcome(Instrument instrument, HttpResponse<String> answer) {
        if (answer.statusCode() == 200) {
            String status = field(answer.body(), "status");
            // A pending card or bank payment may already have landed by the time the answer is
            // rendered - the callbacker executes a pay-in the moment its handle exists - so both
            // read the session as it stands; only the book rail is final by construction.
            boolean expected =
                    switch (instrument) {
                        case CARD, BANK ->
                                status.equals("COMPLETED") || status.equals("PAYMENT_PENDING");
                        case WALLET -> status.equals("COMPLETED");
                    };
            return expected ? "sale:" + instrument : null;
        }
        if (instrument == Instrument.WALLET
                && answer.statusCode() == 422
                && "payments.WalletPaymentUnfunded".equals(code(answer))) {
            return "wallet-sale:payments.WalletPaymentUnfunded";
        }
        return null;
    }

    private HttpResponse<String> withdraw(Customer customer, long amountMinor, String key)
            throws Exception {
        return post("/v1/me/withdrawals",
                "{\"paymentMethodId\":\"" + customer.bank() + "\",\"amount\":\""
                        + decimal(amountMinor) + "\",\"currency\":\"EUR\"}",
                customer.token(), key);
    }

    private HttpResponse<String> transfer(
            Customer source, Customer destination, long amountMinor, String key)
            throws Exception {
        return post("/v1/transfers",
                "{\"sourceAccountId\":\"" + source.product() + "\",\"destinationAccountId\":\""
                        + destination.product() + "\",\"amount\":\"" + decimal(amountMinor)
                        + "\",\"currency\":\"EUR\",\"reference\":\"storm\"}",
                source.token(), key);
    }

    private HttpResponse<String> refund(Storm storm, String intent, long amountMinor, String key)
            throws Exception {
        return post("/v1/payments/" + intent + "/refund",
                "{\"amount\":\"" + decimal(amountMinor) + "\",\"currency\":\"EUR\","
                        + "\"reason\":\"storm refund\"}",
                storm.world.operator(), key);
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

    /** The storm's newest payments - top-ups and sales - WHATEVER their state. */
    private static List<String[]> recentPayments(World world) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return rows(app,
                    "SELECT id::text, amount_minor::text, '' FROM payments.payment_intent"
                            + " WHERE credit_account_id = ANY (?)"
                            + " ORDER BY created_at DESC LIMIT 12",
                    (Object) world.tradingAccounts());
        }
    }

    /** Card sales with a capture reference, undisputed: attempt, capture, intent. */
    private static List<String[]> undisputedSales(Storm storm) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            List<String[]> sales =
                    rows(app,
                            "SELECT a.id::text, a.capture_reference, i.id::text"
                                    + " FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " WHERE a.rail = 'card' AND a.capture_reference IS NOT NULL"
                                    + "   AND i.credit_account_id = ANY (?)"
                                    + "   AND NOT EXISTS (SELECT 1 FROM payments.dispute d"
                                    + "                    WHERE d.attempt_id = a.id)"
                                    + " ORDER BY a.created_at DESC LIMIT 12",
                            (Object) storm.world.tradingPayables());
            sales.removeIf(sale -> storm.disputes.containsKey(UUID.fromString(sale[0])));
            return sales;
        }
    }

    /** The trading merchants' CAPTURED card sales, newest first: attempt, capture, intent. */
    private static List<String[]> capturedCardSales(World world) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return rows(app,
                    "SELECT a.id::text, a.capture_reference, i.id::text"
                            + " FROM payments.payment_attempt a"
                            + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                            + " WHERE a.rail = 'card' AND a.status = 'CAPTURED'"
                            + "   AND i.credit_account_id = ANY (?)"
                            + " ORDER BY a.created_at DESC LIMIT 12",
                    (Object) world.tradingPayables());
        }
    }

    /**
     * A fresh card sale on the dispute desk's merchant, CAPTURED: attempt, capture reference,
     * intent. Nothing else in the storm acts on it, so what the caller does to it happens in the
     * caller's order.
     */
    private String[] deskSale(Storm storm, Customer customer) throws Exception {
        HttpResponse<String> opened = openSessionResponse(storm.world.desk());
        if (opened.statusCode() != 201) {
            throw unexpected("desk open", opened, storm);
        }
        HttpResponse<String> confirmed =
                confirm(customer, field(opened.body(), "sessionToken"), Instrument.CARD);
        if (confirmed.statusCode() != 200
                || !"COMPLETED".equals(field(confirmed.body(), "status"))) {
            throw unexpected("desk sale", confirmed, storm);
        }
        try (Connection app = DatabaseRoles.application()) {
            List<String[]> sale =
                    rows(app,
                            "SELECT a.id::text, a.capture_reference, i.id::text"
                                    + " FROM payments.payment_attempt a"
                                    + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                    + " JOIN checkout.checkout_session s"
                                    + "   ON s.payment_intent_ref = i.id"
                                    + " WHERE s.id = ?::uuid AND a.status = 'CAPTURED'",
                            field(opened.body(), "checkoutId"));
            assertThat(sale).as("the desk's sale captured").hasSize(1);
            return sale.get(0);
        }
    }

    // ----------------------------------------------------------------- the wire

    private static String executed(String eventId, String reference, String scheme, String amount) {
        return "{\"eventId\":\"" + eventId + "\",\"reference\":\"" + reference
                + "\",\"status\":\"executed\",\"schemeReference\":\"" + scheme
                + "\",\"settlementCycle\":\"C7\",\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}";
    }

    private static String clearing(String eventId, String capture, String arn) {
        return "{\"eventId\":\"" + eventId + "\",\"operation\":\"" + capture
                + "\",\"status\":\"cleared\",\"arn\":\"" + arn
                + "\",\"networkTransactionId\":\"ntx-" + arn + "\"}";
    }

    private static String chargeback(
            String capture, String dispute, String stage, long amountMinor, Long feeMinor) {
        return chargebackWithEvent("evt_" + IDS.next(), capture, dispute, stage, amountMinor,
                feeMinor);
    }

    private static String chargebackWithEvent(
            String eventId, String capture, String dispute, String stage, long amountMinor,
            Long feeMinor) {
        return "{\"eventId\":\"" + eventId + "\",\"operation\":\"" + capture
                + "\",\"status\":\"disputed\",\"dispute\":\"" + dispute + "\",\"stage\":\""
                + stage + "\",\"reasonCode\":\"fraudulent\",\"amountMinor\":\"" + amountMinor
                + "\",\"currency\":\"EUR\",\"scale\":2"
                + (feeMinor == null ? "" : ",\"feeMinor\":\"" + feeMinor + "\"") + "}";
    }

    private int deliverCard(String body) {
        return provider.deliverTimestampSignedCallback(
                URI.create("http://localhost:" + port + "/v1/providers/payments/webhooks"),
                body, CARD_WEBHOOK_KEY, Instant.now(CLOCK).getEpochSecond(), 1);
    }

    private int deliverInstant(String body) {
        return provider.deliverTimestampSignedCallback(
                URI.create("http://localhost:" + port + "/v1/providers/payments/instant/webhooks"),
                body, INSTANT_WEBHOOK_KEY, Instant.now(CLOCK).getEpochSecond(), 1);
    }

    /** {@code racers} concurrent calls released together; every answer, in submission order. */
    private static List<HttpResponse<String>> race(
            ExecutorService pool, int racers, Callable<HttpResponse<String>> call)
            throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> answers = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            answers.add(pool.submit(() -> {
                open.await();
                return call.call();
            }));
        }
        open.countDown();
        List<HttpResponse<String>> collected = new ArrayList<>();
        for (Future<HttpResponse<String>> answer : answers) {
            collected.add(answer.get(2, TimeUnit.MINUTES));
        }
        return collected;
    }

    private List<Integer> raceDeliveries(ExecutorService pool, List<String> bodies)
            throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        List<Future<Integer>> answers = new ArrayList<>();
        for (String body : bodies) {
            answers.add(pool.submit(() -> {
                open.await();
                return deliverCard(body);
            }));
        }
        open.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> answer : answers) {
            statuses.add(answer.get(2, TimeUnit.MINUTES));
        }
        return statuses;
    }

    private static String amountOnTheWire(long minor) {
        return "\"amountMinor\":\"" + minor + "\"";
    }

    private static AssertionError unexpected(
            String what, HttpResponse<String> answer, Storm storm) {
        storm.count(what + ":" + answer.statusCode() + ":" + code(answer));
        return new AssertionError(
                "not a domain outcome - " + what + " answered " + answer.statusCode() + ": "
                        + answer.body() + " (outcomes so far: " + storm.outcomes + ")");
    }

    // ----------------------------------------------------------------- HTTP

    private HttpResponse<String> register(String login) throws Exception {
        return post("/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null, someKey());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null, null);
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
                        .timeout(Duration.ofSeconds(90))
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
        Matcher found =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]*)\"").matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }

    /** The problem body's code, or empty when the answer carries none. */
    private static String code(HttpResponse<String> answer) {
        Matcher found = CODE.matcher(answer.body());
        return found.find() ? found.group(1) : "";
    }

    /** Minor units as the API's decimal string - EUR has two minor units. */
    private static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * The two verdicts at rest (P8-TSK-007): every clearing position's identity holds and
     * every clearing line is known, in one fresh {@code REPEATABLE READ} snapshot;
     * {@code SUSPENSE_UNMATCHED}'s unattributed count equals exactly the parkings' own
     * suspense lines — the recorded truth until `P8-TSK-020` adopts them.
     */
    private void assertProofHoldsAtRest(Connection app) throws SQLException {
        com.finapp.app.reconciliation.PositionProof.Report report;
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            report = positionProof.sweep(snapshot);
            snapshot.commit();
        }
        for (com.finapp.app.reconciliation.PositionProof.PositionVerdict verdict :
                report.verdicts()) {
            assertThat(verdict.explained())
                    .as("at rest: %s %s explained - DR-CR %s = open remainders %s"
                            + " (INV-REC-06)",
                            verdict.purpose(), verdict.currency(), verdict.ledgerBalance(),
                            verdict.openRemainders())
                    .isTrue();
        }
        for (com.finapp.ledger.AccountPurpose purpose :
                com.finapp.app.reconciliation.PositionProof.PROVEN) {
            assertThat(report.unattributedByPurpose().get(purpose))
                    .as("at rest: every %s line is known", purpose)
                    .isZero();
        }
        long suspenseLines =
                sum(app,
                        "SELECT count(*) FROM ledger.journal_line l"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE a.purpose = 'SUSPENSE_UNMATCHED'");
        assertThat(report.unattributedByPurpose()
                        .get(com.finapp.ledger.AccountPurpose.SUSPENSE_UNMATCHED))
                .as("at rest: SUSPENSE_UNMATCHED truthfully counts exactly the parkings'"
                        + " suspense lines until P8-TSK-020 adopts them")
                .isEqualTo(suspenseLines);
    }

    // ----------------------------------------------------------------- SQL

    private static void execute(Connection app, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = prepared(app, sql, arguments)) {
            statement.executeUpdate();
        }
    }

    private static long sum(Connection app, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement read = prepared(app, sql, arguments);
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getBigDecimal(1).longValueExact();
        }
    }

    private static long entries(Connection app, String key) throws SQLException {
        return sum(app,
                "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:" + key);
    }

    private static String one(Connection app, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement read = prepared(app, sql, arguments);
                ResultSet row = read.executeQuery()) {
            assertThat(row.next()).as("expected one row: %s", sql).isTrue();
            return row.getString(1);
        }
    }

    private static List<String> strings(Connection app, String sql, Object... arguments)
            throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement read = prepared(app, sql, arguments);
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }

    private static List<String[]> pairs(Connection app, String sql, Object... arguments)
            throws SQLException {
        List<String[]> found = new ArrayList<>();
        try (PreparedStatement read = prepared(app, sql, arguments);
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                found.add(new String[] {rows.getString(1), rows.getString(2)});
            }
        }
        return found;
    }

    private static List<String[]> rows(Connection app, String sql, Object... arguments)
            throws SQLException {
        List<String[]> found = new ArrayList<>();
        try (PreparedStatement read = prepared(app, sql, arguments);
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                found.add(new String[] {rows.getString(1), rows.getString(2), rows.getString(3)});
            }
        }
        return found;
    }

    /** A statement with its arguments bound - a {@code UUID[]} as a SQL array. */
    private static PreparedStatement prepared(Connection app, String sql, Object... arguments)
            throws SQLException {
        PreparedStatement statement = app.prepareStatement(sql);
        try {
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i] instanceof UUID[] uuids) {
                    statement.setArray(i + 1, app.createArrayOf("uuid", uuids));
                } else {
                    statement.setObject(i + 1, arguments[i]);
                }
            }
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }
}
