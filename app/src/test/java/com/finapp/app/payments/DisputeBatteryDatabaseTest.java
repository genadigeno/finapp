package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentRefund;
import com.finapp.payments.RefundExceedsCaptureException;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The dispute battery (`P7-TST-002`): every dispute failure scenario `PHASE_7_PLAN.md` §14 names
 * — a chargeback on an already refunded payment (7), a duplicate notification (8), a
 * representment after resolution (9), a counterparty no longer postable (10) — with a refund
 * racing a chargeback, a second cycle, inquiries escalated and closed, late and out-of-order
 * statements, the PSP's fee and answers racing the network's verdict: thirteen scenarios twice
 * over, run together by four runners over SHARED wallets, so the stages of different disputes,
 * refunds and seeded captures contend on one counterparty — the lock order `P7-TST-001` fixed.
 *
 * <p><strong>Every notification is delivered TEN WAYS at once</strong>: five under one event id
 * (the inbox's rank) and five under fresh ids (the stage's conditional transition and the posting
 * keys' rank), each redelivered until acknowledged, as the PSP would — a {@code 409 api.Conflict}
 * is the inbox saying another instance holds that event id right now (ADR-0047's contended
 * delivery), and any other answer fails the battery. The multi-rail storm already carries the
 * common dispute path under load; this battery's subject is what the storm does not reach
 * (`P7-TST-001`'s recorded input).
 *
 * <p>The Phase 7 gate's two dispute criteria, under that load: a chargeback on an already refunded
 * payment debits the counterparty nothing twice ({@link Scenario#REFUNDED_FIRST}), and every stage
 * posts once however often it is notified — read for every dispute from the journal, the trail,
 * the audit record and the outbox.
 *
 * <p><strong>Reconciled</strong> means: each dispute's journal lines equal, as a multiset, the
 * lines its OWN record implies — the chargeback D against the clearing, the posted share S out of
 * the counterparty, the fee F, the win's exact inverse, the loss of exactly the excess D − S − P —
 * so a posting the record does not explain, or a record the journal does not carry, fails that
 * dispute by name; then the dispute accounts, the clearing and every wallet are read again, whole,
 * against the records the ledger never sees (the dispute rows and the refunds).
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the dispute battery (P7-TST-002)")
class DisputeBatteryDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final byte[] WEBHOOK_KEY =
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String RESPONSES = SimulatedCardPspAdapter.DISPUTE_RESPONSES_PATH;
    private static final String KEY_HEADER = SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER;
    private static final String WEBHOOK_METER = "finapp.payments.webhook";

    /** The inbox's contended delivery: unacknowledged, so the PSP redelivers (ADR-0047). */
    private static final String CONTENDED = "\"code\":\"api.Conflict\"";

    /** Every seeded payment is a captured 10.00. */
    private static final long CAPTURED = 10_00;

    /**
     * A partial chargeback: an escalated inquiry's (part of the transaction it asked about), and
     * the standing one the refund's way races beside.
     */
    private static final long PARTIAL = 6_00;

    /** The PSP's dispute fee. */
    private static final long FEE = 1_50;

    /** A racing refund: at most three fit a capture, and one beside a standing 6.00. */
    private static final long RACING_REFUND = 3_00;

    private static final int INSTANCES = 2;
    private static final int LOCK_ORDER_ROUNDS = 8;
    private static final int RUNNERS = 4;
    private static final int SHARED_WALLETS = 3;
    private static final int DELIVERIES = 10;
    private static final int IDENTICAL = 5;
    private static final Duration REDELIVERY_BOUND = Duration.ofSeconds(60);

    private static final List<String> WON_PATH = List.of("CHARGED_BACK", "REPRESENTED", "WON");
    private static final List<String> LOST_PATH = List.of("CHARGED_BACK", "LOST");
    private static final Set<String> TERMINAL = Set.of("WON", "LOST", "ACCEPTED", "CLOSED");

    /** Where a chargeback STANDS (DisputeStage#isStanding): the network holds the funds. */
    private static final String STANDING = "('CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED')";

    private static SimulatedProvider provider;
    private static HttpClient http;

    @LocalServerPort private int port;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private PostingService postingService;
    @Autowired private PaymentRefund paymentRefund;
    @Autowired private Authorization authorization;
    @Autowired private MeterRegistry registry;

    @BeforeAll
    static void start() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @AfterAll
    static void stop() {
        provider.close();
        http.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The battery
    // -----------------------------------------------------------------

    @Test
    @DisplayName("THE DISPUTE BATTERY: thirteen dispute scenarios twice over, every notification"
            + " ten ways (five under one event id, five fresh), all at once on shared wallets -"
            + " each stage posts once, the bound holds under racing refunds, a refunded payment is"
            + " never debited twice, a parked share is never refused, an answer after resolution"
            + " is refused; every dispute reconciled at rest against its own record, and the"
            + " dispute accounts, the clearing and every wallet against the records the ledger"
            + " never sees")
    void theDisputeBatteryHoldsUnderLoad() throws Exception {
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_rf");
        provider.succeedsWithMintedReference(RESPONSES, "psp_dr");
        List<LedgerAccountId> shared = new ArrayList<>();
        for (int i = 0; i < SHARED_WALLETS; i++) {
            shared.add(wallet());
        }
        World world = new World(shared, session(RoleName.LEDGER_OPERATOR));
        Map<String, Double> doorBefore = doorOutcomes();
        long seed = new SecureRandom().nextLong();

        List<Expected> disputes = runTogether(world, seed);

        String run = "seed " + seed + ", " + world.groups.get() + " statements ten ways, "
                + world.contended.get() + " contended redeliveries";
        UUID[] attempts = world.attempts();

        // 1. EVERY DISPUTE AGAINST ITS OWN RECORD - one row, one edge and one posting per stage.
        for (Expected dispute : disputes) {
            reconcile(dispute);
        }
        assertThat(count("SELECT count(*) FROM payments.dispute WHERE attempt_id = ANY (?)",
                        (Object) attempts))
                .as("%s: one dispute row per network reference, however often notified", run)
                .isEqualTo(disputes.size());

        // 2. INV-DSP-01 ON EVERY ATTEMPT: non-failed refunds plus standing attributions (posted
        // or parked) never exceed what the capture credited.
        assertThat(strings(
                        "SELECT a.id::text FROM payments.payment_attempt a WHERE a.id = ANY (?)"
                                + " AND (SELECT coalesce(sum(r.amount_minor), 0)"
                                + "        FROM payments.refund r"
                                + "       WHERE r.attempt_id = a.id AND r.status <> 'FAILED')"
                                + "   + (SELECT coalesce(sum("
                                + "            coalesce(d.counterparty_share_amount_minor, 0)"
                                + "          + coalesce(d.parked_share_amount_minor, 0)), 0)"
                                + "        FROM payments.dispute d"
                                + "       WHERE d.attempt_id = a.id"
                                + "         AND d.stage IN " + STANDING + ")"
                                + "   > coalesce(a.captured_amount_minor, 0)",
                        (Object) attempts))
                .as("%s: INV-DSP-01 - no attempt refunded plus charged back past its capture", run)
                .isEmpty();

        // 3. THE STAGE POSTINGS, WHOLE, AGAINST THE DISPUTE RECORDS: the dispute accounts hold
        // every fee and each standing chargeback's excess over its posted share; the clearing
        // gave up every standing chargeback and every fee; the counterparties bear the standing
        // posted shares.
        String[] ids = strings("SELECT id::text FROM payments.dispute WHERE attempt_id = ANY (?)",
                        (Object) attempts)
                .toArray(String[]::new);
        Map<String, Long> positions = netsOver(ids);
        long disputeAccounts =
                count("SELECT coalesce(sum(coalesce(dispute_fee_amount_minor, 0)"
                                + " + CASE WHEN stage IN " + STANDING
                                + "   THEN chargeback_amount_minor"
                                + "        - coalesce(counterparty_share_amount_minor, 0)"
                                + "   ELSE 0 END), 0)"
                                + " FROM payments.dispute WHERE attempt_id = ANY (?)",
                        (Object) attempts);
        long clearing =
                count("SELECT coalesce(sum(coalesce(dispute_fee_amount_minor, 0)"
                                + " + CASE WHEN stage IN " + STANDING
                                + "   THEN chargeback_amount_minor ELSE 0 END), 0)"
                                + " FROM payments.dispute WHERE attempt_id = ANY (?)",
                        (Object) attempts);
        long borne =
                count("SELECT coalesce(sum(CASE WHEN stage IN " + STANDING
                                + "   THEN coalesce(counterparty_share_amount_minor, 0)"
                                + "   ELSE 0 END), 0)"
                                + " FROM payments.dispute WHERE attempt_id = ANY (?)",
                        (Object) attempts);
        assertThat(positions.getOrDefault("CHARGEBACK_RECOVERABLE", 0L)
                        + positions.getOrDefault("DISPUTE_COSTS", 0L))
                .as("%s: CHARGEBACK_RECOVERABLE plus DISPUTE_COSTS over the battery's dispute"
                        + " entries against every dispute's amount, posted share and fee", run)
                .isEqualTo(disputeAccounts);
        assertThat(positions.getOrDefault("SETTLEMENT_CLEARING", 0L))
                .as("%s: SETTLEMENT_CLEARING against the standing chargebacks and the fees", run)
                .isEqualTo(-clearing);
        assertThat(positions.getOrDefault("CUSTOMER_WALLET", 0L))
                .as("%s: the counterparties bear exactly the standing posted shares", run)
                .isEqualTo(borne);
        assertThat(positions.keySet())
                .as("%s: a dispute entry touches only the clearing, the dispute accounts and the"
                        + " counterparty", run)
                .isSubsetOf("SETTLEMENT_CLEARING", "CHARGEBACK_RECOVERABLE", "DISPUTE_COSTS",
                        "CUSTOMER_WALLET");
        assertThat(positions.values().stream().mapToLong(Long::longValue).sum())
                .as("%s: the trial balance over the battery's dispute entries", run)
                .isZero();

        // 3b. ONE EXPECTATION PER MONEY STAGE (P8-TSK-005, INV-DSP-02): every clearing line the
        // battery's dispute entries hold - each chargeback, win and fee, out of order, walked,
        // second-cycle, parked, raced ten ways - has exactly one expectation copying it, and the
        // attribution, restoration, loss and re-attribution entries opened nothing.
        try (Connection app = DatabaseRoles.application()) {
            assertThat(ClearingLineCopies.assertEveryClearingLineIsCopied(
                                    app, run,
                                    "SELECT e.id FROM ledger.journal_entry e"
                                            + " WHERE e.reference = ANY (?)",
                                    (Object) ids)
                            .keySet())
                    .as("%s: the chargebacks, the wins and the fees each opened theirs", run)
                    .containsExactlyInAnyOrder(
                            "dispute-chargeback:", "dispute-won:", "dispute-fee:");
        }

        // 4. EVERY WALLET AGAINST ITS BOOK: what its captures credited, less the refunds that
        // completed and the shares standing - the refund rows and the dispute rows, never the
        // journal.
        assertThat(count("SELECT count(*) FROM payments.refund WHERE attempt_id = ANY (?)"
                                + " AND status <> 'COMPLETED'",
                        (Object) attempts))
                .as("%s: every refund the bound admitted completed", run)
                .isZero();
        for (LedgerAccountId wallet : world.shared) {
            UUID[] on = world.attemptsOn(wallet);
            long refunded =
                    count("SELECT coalesce(sum(amount_minor), 0) FROM payments.refund"
                                    + " WHERE attempt_id = ANY (?) AND status = 'COMPLETED'",
                            (Object) on);
            long charged =
                    count("SELECT coalesce(sum(counterparty_share_amount_minor), 0)"
                                    + " FROM payments.dispute"
                                    + " WHERE attempt_id = ANY (?) AND stage IN " + STANDING,
                            (Object) on);
            assertThat(balance(wallet))
                    .as("%s: shared wallet %s against its book - %s captures of 10.00, %s"
                            + " refunded, %s charged back and standing", run, wallet.value(),
                            on.length, refunded, charged)
                    .isEqualTo(CAPTURED * on.length - refunded - charged);
        }
        for (LedgerAccountId closed : world.closed) {
            assertThat(count("SELECT count(*) FROM ledger.journal_line"
                                    + " WHERE ledger_account_id = ?",
                            closed.value()))
                    .as("%s: nothing posted to a closed wallet - its capture and the spend that"
                            + " emptied it, and no more", run)
                    .isEqualTo(2);
            assertThat(balance(closed)).as("%s: the closed wallet stays empty", run).isZero();
        }

        // 5. EVERY SEND BY OUR REFERENCE, ONCE - and a refused refund or answer never sent.
        assertThat(provider.headerValues(SimulatedCardPspAdapter.REFUNDS_PATH, KEY_HEADER))
                .as("%s: every refund sent once, by our reference", run)
                .containsExactlyInAnyOrderElementsOf(strings(
                        "SELECT provider_idempotency_reference FROM payments.refund"
                                + " WHERE attempt_id = ANY (?)",
                        (Object) attempts));
        List<String> answered =
                strings("SELECT r.id::text FROM payments.dispute_response r"
                                + " JOIN payments.dispute d ON d.id = r.dispute_id"
                                + " WHERE d.attempt_id = ANY (?)",
                        (Object) attempts);
        assertThat(answered)
                .as("%s: the answers written are exactly the answers admitted - at least the one"
                        + " each standing-chargeback race admits", run)
                .hasSizeGreaterThanOrEqualTo(INSTANCES / 2)
                .containsExactlyInAnyOrderElementsOf(world.responses);
        for (String response : answered) {
            UUID id = UUID.fromString(response);
            assertThat(oneString("SELECT status FROM payments.dispute_response WHERE id = ?", id))
                    .as("%s: the PSP took answer %s", run, response)
                    .isEqualTo("SUBMITTED");
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?"
                                    + " AND operation = 'payments.DisputeEvidenceTransmitted'",
                            response))
                    .as("%s: INV-DSP-03 - answer %s's one transmission of evidence is on the"
                            + " record", run, response)
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                                    + " AND event_type = 'payments.DisputeResponseSubmitted'",
                            id))
                    .as("%s: answer %s announced once", run, response)
                    .isEqualTo(1);
        }
        assertThat(provider.headerValues(RESPONSES, KEY_HEADER))
                .as("%s: every answer sent exactly once, by our reference", run)
                .containsExactlyInAnyOrderElementsOf(strings(
                        "SELECT r.provider_idempotency_reference"
                                + " FROM payments.dispute_response r"
                                + " JOIN payments.dispute d ON d.id = r.dispute_id"
                                + " WHERE d.attempt_id = ANY (?)",
                        (Object) attempts));

        // 6. THE DOOR'S TALLY: every one of the ten deliveries acknowledged; the inbox absorbed
        // exactly the four repeats of each statement's shared event id, and the six others each
        // reached the dispute - where the trails above show each stage applied once.
        Map<String, Double> doorAfter = doorOutcomes();
        int groups = world.groups.get();
        assertThat(doorAfter.get("duplicate") - doorBefore.get("duplicate"))
                .as("%s: the inbox absorbed exactly the four repeats of each shared event id", run)
                .isEqualTo(4.0 * groups);
        assertThat(doorAfter.get("processed") - doorBefore.get("processed"))
                .as("%s: the first of the shared id and the five fresh ids reached the dispute",
                        run)
                .isEqualTo(6.0 * groups);
        assertThat(doorAfter.get("unmappable") - doorBefore.get("unmappable"))
                .as("%s: no statement the battery made contradicts the record", run)
                .isZero();
        assertThat(doorAfter.get("refused") - doorBefore.get("refused"))
                .as("%s: every delivery authentic", run)
                .isZero();
    }

    // -----------------------------------------------------------------
    // The scenarios
    // -----------------------------------------------------------------

    /** Every dispute failure scenario the plan names, and every stage the machine has. */
    private enum Scenario {
        /** Charged back, then won - REPRESENTED implied, or stated: the win the exact inverse. */
        WON,
        /** Charged back, then lost: the share stands as the counterparty's, nothing written off. */
        LOST,
        /** The verdict heard FIRST: opened at CHARGED_BACK and walked; the chargeback late. */
        OUT_OF_ORDER,
        /** An inquiry escalated to a PARTIAL chargeback, then accepted - or won. */
        ESCALATED,
        /** An inquiry closed without a chargeback - no money, ever; a late inquiry quiet. */
        INQUIRY_CLOSED,
        /** §14.7, THE GATE'S CRITERION: a chargeback on an already fully refunded payment. */
        REFUNDED_FIRST,
        /** Four refunds racing one full chargeback on one attempt (INV-DSP-01). */
        REFUND_RACING,
        /** A second-cycle chargeback on the same payment, after the first was resolved. */
        SECOND_CYCLE,
        /** §14.10: a counterparty no longer postable - its share parked, never refused. */
        CLOSED_WALLET,
        /** The PSP's fee, stated with the chargeback or first reported later: posted once. */
        FEE,
        /** §14.9: representments, acceptances and evidence after resolution - all refused. */
        ANSWERED_AFTER,
        /** Representments racing the network's verdict: at most one answer, and no money. */
        ANSWER_RACING,
        /** A stage racing refunds of the counterparty's OTHER payments (P7-TST-001's order). */
        LOCK_ORDER
    }

    /**
     * Every scenario instance at once, {@link #RUNNERS} in flight, in an order shuffled by
     * {@code seed}; each on a shared wallet chosen by its slot. The first failure is thrown with
     * the others suppressed, each naming its scenario.
     */
    private List<Expected> runTogether(World world, long seed) throws Exception {
        List<Callable<List<Expected>>> cases = new ArrayList<>();
        for (Scenario scenario : Scenario.values()) {
            for (int instance = 0; instance < INSTANCES; instance++) {
                String label = scenario + "#" + instance;
                int thisInstance = instance;
                LedgerAccountId wallet = world.wallet(cases.size());
                cases.add(() -> {
                    try {
                        return run(world, scenario, thisInstance, label, wallet);
                    } catch (Throwable failure) {
                        throw new AssertionError(
                                label + " (seed " + seed + ") failed: "
                                        + (failure instanceof AssertionError
                                                ? failure.getMessage()
                                                : failure.toString()),
                                failure);
                    }
                });
            }
        }
        Collections.shuffle(cases, new Random(seed));
        ExecutorService runners = Executors.newFixedThreadPool(RUNNERS);
        try {
            List<Future<List<Expected>>> running = new ArrayList<>();
            for (Callable<List<Expected>> scenario : cases) {
                running.add(runners.submit(scenario));
            }
            List<Expected> expected = new ArrayList<>();
            AssertionError failed = null;
            for (Future<List<Expected>> scenario : running) {
                try {
                    expected.addAll(scenario.get(10, TimeUnit.MINUTES));
                } catch (ExecutionException failure) {
                    AssertionError why =
                            failure.getCause() instanceof AssertionError assertion
                                    ? assertion
                                    : new AssertionError(failure.getCause());
                    if (failed == null) {
                        failed = why;
                    } else {
                        failed.addSuppressed(why);
                    }
                }
            }
            if (failed != null) {
                throw failed;
            }
            return expected;
        } finally {
            runners.shutdownNow();
        }
    }

    private List<Expected> run(
            World world, Scenario scenario, int instance, String label, LedgerAccountId wallet)
            throws Exception {
        return switch (scenario) {
            case WON -> won(world, label, instance, wallet);
            case LOST -> lost(world, label, instance, wallet);
            case OUT_OF_ORDER -> outOfOrder(world, label, instance, wallet);
            case ESCALATED -> escalated(world, label, instance, wallet);
            case INQUIRY_CLOSED -> inquiryClosed(world, label, instance, wallet);
            case REFUNDED_FIRST -> refundedFirst(world, label, instance, wallet);
            case REFUND_RACING -> refundRacing(world, label, instance, wallet);
            case SECOND_CYCLE -> secondCycle(world, label, instance, wallet);
            case CLOSED_WALLET -> closedWallet(world, label, instance);
            case FEE -> fee(world, label, instance, wallet);
            case ANSWERED_AFTER -> answeredAfter(world, label, instance, wallet);
            case ANSWER_RACING -> answerRacing(world, label, instance, wallet);
            case LOCK_ORDER -> lockOrder(world, label, instance, wallet);
        };
    }

    private List<Expected> won(World world, String label, int instance, LedgerAccountId wallet)
            throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        notify(world, label, dispute, "needs_response", null);
        if (instance == 1) {
            notify(world, label, dispute, "under_review", null);
        }
        notify(world, label, dispute, "won", null);
        return List.of(expect(label, dispute, WON_PATH, CAPTURED, 0, 0, 0));
    }

    private List<Expected> lost(World world, String label, int instance, LedgerAccountId wallet)
            throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        notify(world, label, dispute, "needs_response", null);
        if (instance == 1) {
            notify(world, label, dispute, "under_review", null);
        }
        notify(world, label, dispute, "lost", null);
        return List.of(expect(label, dispute,
                instance == 1 ? List.of("CHARGED_BACK", "REPRESENTED", "LOST") : LOST_PATH,
                CAPTURED, 0, 0, 0));
    }

    /** The verdict first; the statements it implies arrive after it, and move nothing. */
    private List<Expected> outOfOrder(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        notify(world, label, dispute, "won", null);
        notify(world, label, dispute, "needs_response", null);
        if (instance == 1) {
            notify(world, label, dispute, "under_review", null);
        }
        return List.of(expect(label, dispute, WON_PATH, CAPTURED, 0, 0, 0));
    }

    /** The chargeback's amount arrives with the chargeback: 6.00 of the 10.00 asked about. */
    private List<Expected> escalated(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        Stated dispute = stated(world, wallet, PARTIAL);
        notify(world, label, dispute, "warning_needs_response", null);
        notify(world, label, dispute, "needs_response", null);
        List<String> path;
        if (instance == 0) {
            notify(world, label, dispute, "accepted", null);
            path = List.of("INQUIRY", "CHARGED_BACK", "ACCEPTED");
        } else {
            notify(world, label, dispute, "won", null);
            path = List.of("INQUIRY", "CHARGED_BACK", "REPRESENTED", "WON");
        }
        return List.of(expect(label, dispute, path, PARTIAL, 0, 0, 0));
    }

    private List<Expected> inquiryClosed(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        notify(world, label, dispute, "warning_needs_response", null);
        notify(world, label, dispute, "warning_closed", null);
        if (instance == 1) {
            notify(world, label, dispute, "warning_under_review", null);
        }
        return List.of(uncharged(label, dispute, List.of("INQUIRY", "CLOSED")));
    }

    /** §14.7 and the gate's criterion: refunded in full, then disputed in full. */
    private List<Expected> refundedFirst(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        assertThat(refund(dispute.payment(), CAPTURED).status())
                .as("%s: the payment is refunded in full before the network disputes it", label)
                .isEqualTo(RefundStatus.COMPLETED);
        notify(world, label, dispute, "needs_response", null);
        assertThat(split(dispute))
                .as("%s: THE GATE'S CRITERION - a chargeback on an already refunded payment"
                        + " charges the counterparty nothing; all of it rests in"
                        + " CHARGEBACK_RECOVERABLE", label)
                .containsExactly(0L, 0L, CAPTURED);
        List<String> path = verdict(world, label, dispute, instance == 1);
        return List.of(expect(label, dispute, path, 0, 0, CAPTURED, 0));
    }

    /**
     * The combined bound, judged both ways under a race (INV-DSP-01).
     *
     * <p>Instance 0 - THE CHARGEBACK'S WAY: one refund of 3.00 completes, then three more race
     * the full chargeback's ten deliveries; whatever the interleaving, the counted refunds and
     * the share sum to the capture, and the LOSS that follows always meets a partial excess (the
     * case where "only the excess" differs from both nothing and all).
     *
     * <p>Instance 1 - THE REFUND'S WAY, forced rather than left to the race's luck: a PARTIAL
     * chargeback of 6.00 stands, then four refunds of 3.00 race the network's under_review -
     * exactly one fits beside the standing share, three are refused by the chargeback term, and
     * the WIN that follows restores the share while the refund stands.
     */
    private List<Expected> refundRacing(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        boolean chargebackLeads = instance == 1;
        Stated dispute = stated(world, wallet, chargebackLeads ? PARTIAL : CAPTURED);
        if (chargebackLeads) {
            notify(world, label, dispute, "needs_response", null);
        } else {
            assertThat(refund(dispute.payment(), RACING_REFUND).status())
                    .as("%s: one refund completes before the race", label)
                    .isEqualTo(RefundStatus.COMPLETED);
        }
        int refunds = chargebackLeads ? 4 : 3;
        List<Callable<Object>> racers =
                new ArrayList<>(tenWays(world, label, dispute,
                        chargebackLeads ? "under_review" : "needs_response", null));
        for (int i = 0; i < refunds; i++) {
            racers.add(() -> {
                try {
                    return refund(dispute.payment(), RACING_REFUND).status();
                } catch (RefundExceedsCaptureException refused) {
                    return "refused by the bound";
                }
            });
        }
        List<Object> outcomes = concurrently(racers);
        List<Object> refunded = outcomes.subList(DELIVERIES, DELIVERIES + refunds);
        assertThat(refunded)
                .as("%s: each racing refund completes or the bound refuses it", label)
                .allMatch(outcome -> outcome == RefundStatus.COMPLETED
                        || "refused by the bound".equals(outcome));
        long counted =
                count("SELECT coalesce(sum(amount_minor), 0) FROM payments.refund"
                                + " WHERE attempt_id = ? AND status <> 'FAILED'",
                        dispute.payment().attempt());
        long[] split = split(dispute);
        if (chargebackLeads) {
            assertThat(refunded)
                    .as("%s: THE REFUND'S WAY - beside a standing share of 6.00 exactly one"
                            + " refund of 3.00 fits; the chargeback term refuses the rest", label)
                    .filteredOn(outcome -> outcome == RefundStatus.COMPLETED)
                    .hasSize(1);
            assertThat(split).as("%s: the share the chargeback took stands whole", label)
                    .containsExactly(PARTIAL, 0L, 0L);
        } else {
            assertThat(counted + split[0] + split[1])
                    .as("%s: INV-DSP-01 RACED - the counted refunds and the attributed share sum"
                            + " to exactly the capture", label)
                    .isEqualTo(CAPTURED);
            assertThat(split[2])
                    .as("%s: the excess is exactly what the refunds had already returned", label)
                    .isEqualTo(counted);
        }
        List<String> path = verdict(world, label, dispute, chargebackLeads);
        return List.of(chargebackLeads
                ? expect(label, dispute, path, PARTIAL, 0, 0, 0)
                : expect(label, dispute, path, CAPTURED - counted, 0, counted, 0));
    }

    /**
     * A second cycle: a new network reference on the same payment after the first was resolved.
     * Lost first, its share still stands and the second takes nothing more from the counterparty;
     * won first, the capture is whole again and the second charges it in full.
     */
    private List<Expected> secondCycle(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        Stated first = stated(world, wallet, CAPTURED);
        Stated second = new Stated(first.payment(), someDisputeReference(), CAPTURED, future());
        boolean firstWon = instance == 1;
        notify(world, label, first, "needs_response", null);
        List<String> firstPath = verdict(world, label, first, firstWon);
        notify(world, label, second, "needs_response", null);
        long secondShare = firstWon ? CAPTURED : 0;
        assertThat(split(second))
                .as(firstWon
                        ? "%s: the won first cycle freed the capture - the second cycle charges"
                                + " the counterparty in full"
                        : "%s: the lost first cycle's share still stands - the second cycle"
                                + " charges the counterparty nothing more: all excess", label)
                .containsExactly(secondShare, 0L, CAPTURED - secondShare);
        List<String> secondPath = verdict(world, label, second, !firstWon);
        return List.of(
                expect(label + "/first", first, firstPath, CAPTURED, 0, 0, 0),
                expect(label + "/second", second, secondPath, secondShare, 0,
                        CAPTURED - secondShare, 0));
    }

    /** §14.10: the credited wallet spent to zero and CLOSED before the network disputes. */
    private List<Expected> closedWallet(World world, String label, int instance) throws Exception {
        LedgerAccountId closed = wallet();
        Stated dispute = stated(world, closed, CAPTURED);
        spend(closed, CAPTURED);
        closeLedgerAccount(closed);
        world.closed.add(closed);
        notify(world, label, dispute, "needs_response", null);
        assertThat(split(dispute))
                .as("%s: a counterparty that takes no postings has its share PARKED in the"
                        + " recoverable - recorded, never refused", label)
                .containsExactly(0L, CAPTURED, 0L);
        List<String> path = verdict(world, label, dispute, instance == 0);
        return List.of(expect(label, dispute, path, 0, CAPTURED, 0, 0));
    }

    /** The PSP's fee: with the chargeback and repeated with the verdict, or reported late. */
    private List<Expected> fee(World world, String label, int instance, LedgerAccountId wallet)
            throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        List<String> path;
        if (instance == 0) {
            notify(world, label, dispute, "needs_response", FEE);
            notify(world, label, dispute, "won", FEE);
            path = WON_PATH;
        } else {
            notify(world, label, dispute, "needs_response", null);
            notify(world, label, dispute, "under_review", FEE);
            notify(world, label, dispute, "lost", null);
            path = List.of("CHARGED_BACK", "REPRESENTED", "LOST");
        }
        return List.of(expect(label, dispute, path, CAPTURED, 0, 0, FEE));
    }

    /** §14.9: ten answers at once on a dispute the network already resolved. */
    private List<Expected> answeredAfter(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        notify(world, label, dispute, "needs_response", null);
        List<String> path = verdict(world, label, dispute, instance == 1);
        UUID id = disputeId(dispute);
        List<HttpResponse<String>> answers = new CopyOnWriteArrayList<>();
        List<Callable<Object>> late = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            late.add(() -> answers.add(answer(world, id, "representment")));
            late.add(() -> answers.add(answer(world, id, "acceptance")));
        }
        for (int i = 0; i < 2; i++) {
            late.add(() -> answers.add(attach(world, id)));
        }
        concurrently(late);
        assertThat(answers).hasSize(10);
        for (HttpResponse<String> refused : answers) {
            assertThat(refused.statusCode())
                    .as("%s: an answer after resolution is refused (%s)", label, refused.body())
                    .isEqualTo(409);
            assertThat(refused.body())
                    .as("%s: an answer after resolution is refused", label)
                    .contains("\"code\":\"payments.DisputeNotRespondable\"");
        }
        assertThat(count("SELECT count(*) FROM payments.dispute_response WHERE dispute_id = ?",
                        id))
                .as("%s: no answer written", label)
                .isZero();
        assertThat(count("SELECT count(*) FROM payments.dispute_evidence WHERE dispute_id = ?",
                        id))
                .as("%s: no document written", label)
                .isZero();
        assertThat(audits(id, "payments.DisputeResponseDispatched")
                        + audits(id, "payments.DisputeEvidenceUploaded"))
                .as("%s: and nothing on the record - a refused act is no act", label)
                .isZero();
        return List.of(expect(label, dispute, path, CAPTURED, 0, 0, 0));
    }

    /**
     * Evidence attached, then five representments at once: racing the network's LOST (instance
     * 0: one answer at most, whichever commits first), or racing only each other on the standing
     * chargeback (instance 1: EXACTLY one admitted, the rest refused as already answered) before
     * the network's under_review and WON. Either way the answer moves no stage and no money.
     */
    private List<Expected> answerRacing(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        Stated dispute = stated(world, wallet, CAPTURED);
        notify(world, label, dispute, "needs_response", null);
        UUID id = disputeId(dispute);
        HttpResponse<String> attached = attach(world, id);
        assertThat(attached.statusCode())
                .as("%s: evidence attached while the chargeback stands (%s)", label,
                        attached.body())
                .isEqualTo(201);
        boolean verdictRaces = instance == 0;
        List<HttpResponse<String>> answers = new CopyOnWriteArrayList<>();
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            racers.add(() -> answers.add(answer(world, id, "representment")));
        }
        if (verdictRaces) {
            racers.addAll(tenWays(world, label, dispute, "lost", null));
        }
        concurrently(racers);
        List<String> admitted = new ArrayList<>();
        for (HttpResponse<String> answered : answers) {
            if (answered.statusCode() == 201) {
                assertThat(field(answered.body(), "status"))
                        .as("%s: the PSP took the answer admitted", label)
                        .isEqualTo("SUBMITTED");
                admitted.add(field(answered.body(), "responseId"));
                continue;
            }
            assertThat(answered.statusCode())
                    .as("%s: a representment is admitted, or refused as answered or no longer"
                            + " respondable (%s)", label, answered.body())
                    .isEqualTo(409);
            if (verdictRaces) {
                assertThat(answered.body())
                        .as(label)
                        .containsAnyOf(
                                "\"code\":\"payments.DisputeAlreadyAnswered\"",
                                "\"code\":\"payments.DisputeNotRespondable\"");
            } else {
                assertThat(answered.body())
                        .as("%s: the chargeback stands - a loser is refused as answered", label)
                        .contains("\"code\":\"payments.DisputeAlreadyAnswered\"");
            }
        }
        if (verdictRaces) {
            assertThat(admitted)
                    .as("%s: ONE live answer at most, however many raced the verdict", label)
                    .hasSizeLessThanOrEqualTo(1);
        } else {
            assertThat(admitted)
                    .as("%s: five answers racing on a standing chargeback - exactly ONE admitted",
                            label)
                    .hasSize(1);
        }
        assertThat(strings("SELECT id::text FROM payments.dispute_response WHERE dispute_id = ?",
                        id))
                .as("%s: the answers written are exactly the answer admitted", label)
                .containsExactlyInAnyOrderElementsOf(admitted);
        assertThat(strings("SELECT reason FROM platform.audit_record WHERE target_id = ?"
                                + " AND operation = 'payments.DisputeResponseDispatched'",
                        id.toString()))
                .as("%s: the admitted answer's dispatch on the record, in the operator's own"
                        + " words; a refused racer's nowhere", label)
                .hasSize(admitted.size())
                .allMatch("the top-up was the customer's own"::equals);
        world.responses.addAll(admitted);
        List<String> path = LOST_PATH;
        if (!verdictRaces) {
            notify(world, label, dispute, "under_review", null);
            path = verdict(world, label, dispute, true);
        }
        return List.of(expect(label, dispute, path, CAPTURED, 0, 0, 0));
    }

    /**
     * The counterparty-first lock order under load, {@link #LOCK_ORDER_ROUNDS} rounds: the win
     * (instance 0) or the chargeback itself (instance 1) racing full refunds of three OTHER
     * payments to the same wallet - every stage share-locks the counterparty before its first
     * posting, and every refund's hold takes it for update. Rounds, because a race's window must
     * be opened often enough to be met - and still a race's verdict: with one round a run the
     * win's lock went missing unseen, with rounds the battery met the deadlock in three runs of
     * seven, and the chargeback suite's race, its deterministic catcher, in every run it joined.
     */
    private List<Expected> lockOrder(
            World world, String label, int instance, LedgerAccountId wallet) throws Exception {
        boolean winRaces = instance == 0;
        List<Expected> expected = new ArrayList<>();
        for (int round = 0; round < LOCK_ORDER_ROUNDS; round++) {
            String racing = label + "/round" + round;
            Stated dispute = stated(world, wallet, CAPTURED);
            List<Payment> others =
                    List.of(captured(world, wallet), captured(world, wallet),
                            captured(world, wallet));
            if (winRaces) {
                notify(world, racing, dispute, "needs_response", null);
            }
            List<Callable<Object>> racers =
                    new ArrayList<>(tenWays(world, racing, dispute,
                            winRaces ? "won" : "needs_response", null));
            for (Payment other : others) {
                racers.add(() -> {
                    assertThat(refund(other, CAPTURED).status())
                            .as("%s: a refund of the counterparty's other payment completes"
                                    + " beside the %s", racing, winRaces ? "win" : "chargeback")
                            .isEqualTo(RefundStatus.COMPLETED);
                    return "refunded";
                });
            }
            concurrently(racers);
            if (!winRaces) {
                notify(world, racing, dispute, "won", null);
            }
            expected.add(expect(racing, dispute, WON_PATH, CAPTURED, 0, 0, 0));
        }
        return expected;
    }

    /** The network's verdict, ten ways: WON through REPRESENTED, or LOST; answers the path. */
    private List<String> verdict(World world, String label, Stated dispute, boolean won)
            throws Exception {
        notify(world, label, dispute, won ? "won" : "lost", null);
        return won ? WON_PATH : LOST_PATH;
    }

    // -----------------------------------------------------------------
    // The reconciliation at rest
    // -----------------------------------------------------------------

    /** What one dispute must show at rest: the stages it entered, its split and its fee. */
    private record Expected(
            String label, Stated dispute, List<String> path, long[] split, long fee) {}

    private static Expected expect(
            String label,
            Stated dispute,
            List<String> path,
            long share,
            long parked,
            long excess,
            long fee) {
        return new Expected(label, dispute, path, new long[] {share, parked, excess}, fee);
    }

    private static Expected uncharged(String label, Stated dispute, List<String> path) {
        return new Expected(label, dispute, path, null, 0);
    }

    /**
     * One dispute against its own record: one row, one trail row per edge, one record and one
     * fact per stage, and exactly the journal lines the row implies ({@code INV-DSP-01},
     * {@code INV-DSP-02}).
     */
    private static void reconcile(Expected expected) throws SQLException {
        String label = expected.label();
        List<String> path = expected.path();
        String last = path.get(path.size() - 1);
        DisputeRow row = disputeRow(expected.dispute().reference());

        assertThat(row.stage())
                .as("%s: the dispute rests where the network last put it", label)
                .isEqualTo(last);

        // ONE EFFECT PER STAGE, HOWEVER OFTEN NOTIFIED: the trail, the record, the facts.
        List<String> edges = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            edges.add(path.get(i - 1) + ">" + path.get(i));
        }
        assertThat(trail(row.id()))
                .as("%s: one trail row per edge, in the machine's order - ten deliveries of a"
                        + " stage are one edge", label)
                .containsExactlyElementsOf(edges);
        assertThat(stagesApplied(row.id()))
                .as("%s: one DisputeStageApplied record per stage entered", label)
                .containsExactlyInAnyOrderElementsOf(path);
        assertThat(facts(row.id()))
                .as("%s: each stage fact published once", label)
                .containsExactlyInAnyOrderElementsOf(impliedFacts(path));
        assertThat(audits(row.id(), "payments.DisputeFeeRecorded"))
                .as("%s: the PSP's fee recorded once", label)
                .isEqualTo(expected.fee() > 0 ? 1 : 0);
        assertThat(audits(row.id(), "payments.ChargebackReattributed"))
                .as("%s: nothing in this battery frees headroom", label)
                .isZero();

        // THE RECORD: what the network took, who bears it, and the fee.
        if (expected.split() == null) {
            assertThat(row.chargeback())
                    .as("%s: never charged back, never an amount", label)
                    .isNull();
        } else {
            assertThat(row.chargeback())
                    .as("%s: the chargeback's own amount", label)
                    .isEqualTo(expected.dispute().amount());
            assertThat(new long[] {row.share(), row.parked(), row.excess()})
                    .as("%s: share, parked, excess", label)
                    .containsExactly(expected.split());
        }
        assertThat(row.fee())
                .as("%s: the fee", label)
                .isEqualTo(expected.fee() > 0 ? Long.valueOf(expected.fee()) : null);

        // THE JOURNAL AGAINST THE RECORD.
        List<String> lines = lines(row.id());
        assertThat(lines)
                .as("%s: every posting the dispute's record implies - each stage's once - and"
                        + " nothing else", label)
                .containsExactlyInAnyOrderElementsOf(impliedLines(row));
        Map<String, Long> nets = nets(lines);
        long fee = row.fee() == null ? 0 : row.fee();
        if (last.equals("WON")) {
            Map<String, Long> left = new LinkedHashMap<>(nets);
            left.values().removeIf(net -> net == 0L);
            assertThat(left)
                    .as("%s: INV-DSP-02 - the win is the chargeback's exact inverse: nothing is"
                            + " left on any account but the fee", label)
                    .isEqualTo(fee == 0
                            ? Map.of()
                            : Map.of("SETTLEMENT_CLEARING", -fee, "DISPUTE_COSTS", fee));
        } else if (last.equals("LOST") || last.equals("ACCEPTED")) {
            assertThat(nets.getOrDefault("DISPUTE_COSTS", 0L))
                    .as("%s: the loss wrote off exactly the excess, beside the fee", label)
                    .isEqualTo(row.excess() + fee);
            assertThat(nets.getOrDefault("CHARGEBACK_RECOVERABLE", 0L))
                    .as("%s: only a parked share stays recoverable", label)
                    .isEqualTo(row.parked());
            assertThat(nets.getOrDefault("CUSTOMER_WALLET", 0L))
                    .as("%s: the counterparty's share stands as its debt", label)
                    .isEqualTo(row.share());
        }
    }

    /**
     * The lines a dispute's record implies, as {@code operation|DIRECTION:PURPOSE:minor}: the
     * external fact, the attribution of the posted share, the fee, and the verdict's - the win's
     * exact inverse, or the loss of exactly the excess.
     */
    private static List<String> impliedLines(DisputeRow row) {
        List<String> lines = new ArrayList<>();
        if (row.chargeback() == null) {
            return lines;
        }
        long amount = row.chargeback();
        lines.add("dispute-chargeback|DEBIT:CHARGEBACK_RECOVERABLE:" + amount);
        lines.add("dispute-chargeback|CREDIT:SETTLEMENT_CLEARING:" + amount);
        if (row.share() > 0) {
            lines.add("dispute-attribution|DEBIT:CUSTOMER_WALLET:" + row.share());
            lines.add("dispute-attribution|CREDIT:CHARGEBACK_RECOVERABLE:" + row.share());
        }
        if (row.fee() != null) {
            lines.add("dispute-fee|DEBIT:DISPUTE_COSTS:" + row.fee());
            lines.add("dispute-fee|CREDIT:SETTLEMENT_CLEARING:" + row.fee());
        }
        switch (row.stage()) {
            case "WON" -> {
                lines.add("dispute-won|DEBIT:SETTLEMENT_CLEARING:" + amount);
                lines.add("dispute-won|CREDIT:CHARGEBACK_RECOVERABLE:" + amount);
                if (row.share() > 0) {
                    lines.add("dispute-restoration|DEBIT:CHARGEBACK_RECOVERABLE:" + row.share());
                    lines.add("dispute-restoration|CREDIT:CUSTOMER_WALLET:" + row.share());
                }
            }
            case "LOST", "ACCEPTED" -> {
                if (row.excess() > 0) {
                    lines.add("dispute-loss|DEBIT:DISPUTE_COSTS:" + row.excess());
                    lines.add("dispute-loss|CREDIT:CHARGEBACK_RECOVERABLE:" + row.excess());
                }
            }
            default -> {
                // CHARGED_BACK and REPRESENTED stand; nothing more is posted until the verdict.
            }
        }
        return lines;
    }

    /** The facts a dispute's path publishes: opened once, charged back once, resolved once. */
    private static List<String> impliedFacts(List<String> path) {
        List<String> facts = new ArrayList<>();
        facts.add("payments.DisputeOpened");
        if (path.contains("CHARGED_BACK")) {
            facts.add("payments.ChargebackReceived");
        }
        if (TERMINAL.contains(path.get(path.size() - 1))) {
            facts.add("payments.DisputeResolved");
        }
        return facts;
    }

    // -----------------------------------------------------------------
    // The world
    // -----------------------------------------------------------------

    /** What the battery made, shared by its runners. */
    private static final class World {
        private final List<LedgerAccountId> shared;
        private final String operator;
        private final Map<LedgerAccountId, List<Payment>> credited = new ConcurrentHashMap<>();
        private final List<LedgerAccountId> closed = new CopyOnWriteArrayList<>();
        private final List<String> responses = new CopyOnWriteArrayList<>();
        private final AtomicInteger groups = new AtomicInteger();
        private final AtomicInteger contended = new AtomicInteger();

        private World(List<LedgerAccountId> shared, String operator) {
            this.shared = List.copyOf(shared);
            this.operator = operator;
        }

        private LedgerAccountId wallet(int slot) {
            return shared.get(slot % shared.size());
        }

        private UUID[] attempts() {
            return credited.values().stream()
                    .flatMap(List::stream)
                    .map(Payment::attempt)
                    .toArray(UUID[]::new);
        }

        private UUID[] attemptsOn(LedgerAccountId account) {
            return credited.getOrDefault(account, List.of()).stream()
                    .map(Payment::attempt)
                    .toArray(UUID[]::new);
        }
    }

    /** A card payment and the account its capture credited. */
    private record Payment(UUID intent, UUID attempt, String operation, LedgerAccountId credit) {}

    /**
     * One dispute as the network states it: the payment, the network's reference, the
     * chargeback's amount and the respond-by deadline (a future date, so an answer meets only the
     * machine's refusals).
     */
    private record Stated(Payment payment, String reference, long amount, Instant respondBy) {

        /**
         * The statement at {@code stage} in the simulated PSP's wire shape: an inquiry's amount
         * is the transaction's it asks about and carries no deadline; from the chargeback on,
         * the chargeback's own amount and the deadline.
         */
        private String at(String stage, Long fee, String eventId) {
            boolean inquiry = stage.startsWith("warning_");
            return "{\"eventId\":\"" + eventId + "\",\"operation\":\"" + payment.operation()
                    + "\",\"status\":\"disputed\",\"dispute\":\"" + reference
                    + "\",\"stage\":\"" + stage + "\",\"reasonCode\":\"fraudulent\","
                    + "\"amountMinor\":\"" + (inquiry ? CAPTURED : amount) + "\","
                    + "\"currency\":\"EUR\",\"scale\":2"
                    + (fee == null ? "" : ",\"feeMinor\":\"" + fee + "\"")
                    + (inquiry ? "" : ",\"respondBy\":\"" + respondBy + "\"")
                    + "}";
        }
    }

    private Stated stated(World world, LedgerAccountId credit, long amount) throws Exception {
        return new Stated(captured(world, credit), someDisputeReference(), amount, future());
    }

    // -----------------------------------------------------------------
    // The wire
    // -----------------------------------------------------------------

    /** One statement delivered TEN WAYS at once, each redelivered until acknowledged. */
    private void notify(World world, String label, Stated dispute, String stage, Long fee)
            throws Exception {
        concurrently(tenWays(world, label, dispute, stage, fee));
    }

    /**
     * One statement's ten deliveries as racers - five under one event id, five under fresh ids
     * - so a scenario can release them behind the same gate as the refunds or answers they race.
     */
    private List<Callable<Object>> tenWays(
            World world, String label, Stated dispute, String stage, Long fee) {
        String shared = "evt_" + UUID.randomUUID();
        List<Callable<Object>> deliveries = new ArrayList<>();
        for (int i = 0; i < DELIVERIES; i++) {
            String body =
                    dispute.at(stage, fee, i < IDENTICAL ? shared : "evt_" + UUID.randomUUID());
            deliveries.add(() -> acknowledged(world, label, stage, body));
        }
        world.groups.incrementAndGet();
        return deliveries;
    }

    /**
     * Delivers until the door acknowledges. Only the inbox's own {@code 409} - {@code
     * api.Conflict}, another instance holding that event id in an open transaction - is
     * redelivered, as the PSP would; any other answer fails the battery at once, a {@code 409}
     * from INSIDE the effect included (a posting refused as {@code ledger.AccountNotPostable} is
     * a break, never contention - the gate's own find: this loop first read every 409 as
     * contention and spent a minute redelivering a refused posting).
     */
    private int acknowledged(World world, String label, String stage, String body)
            throws Exception {
        Instant bound = Instant.now(CLOCK).plus(REDELIVERY_BOUND);
        while (true) {
            HttpResponse<String> answer = deliver(body);
            if (answer.statusCode() == 204) {
                return 204;
            }
            assertThat(answer.statusCode() == 409 && answer.body().contains(CONTENDED))
                    .as("%s: a '%s' statement is acknowledged, or its event id is held by another"
                            + " instance and it is redelivered - never %s (%s)", label, stage,
                            answer.statusCode(), answer.body())
                    .isTrue();
            world.contended.incrementAndGet();
            assertThat(Instant.now(CLOCK))
                    .as("%s: a contended '%s' statement acknowledged within %s", label, stage,
                            REDELIVERY_BOUND)
                    .isBefore(bound);
            Thread.sleep(10 + ThreadLocalRandom.current().nextInt(40));
        }
    }

    private HttpResponse<String> deliver(String body) throws Exception {
        String timestamp = Long.toString(Instant.now(CLOCK).getEpochSecond());
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port
                                        + "/v1/providers/payments/webhooks"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                        .header(WebhookSignature.SIGNATURE_HEADER, hmacHex(timestamp + "." + body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** An operator's representment or acceptance on a payment with no merchant, keyed. */
    private HttpResponse<String> answer(World world, UUID dispute, String kind) throws Exception {
        return post("/v1/operator/disputes/" + dispute + "/" + kind,
                "{\"reason\":\"" + (kind.equals("representment")
                        ? "the top-up was the customer's own"
                        : "the customer's claim stands") + "\"}",
                world.operator, someKey());
    }

    /** An operator's document on behalf, reasoned. */
    private HttpResponse<String> attach(World world, UUID dispute) throws Exception {
        return post("/v1/operator/disputes/" + dispute + "/evidence",
                "{\"kind\":\"CUSTOMER_COMMUNICATION\",\"contentType\":\"PDF\",\"content\":\""
                        + Base64.getEncoder().encodeToString(
                                ("the chat log " + UUID.randomUUID())
                                        .getBytes(StandardCharsets.UTF_8))
                        + "\",\"reason\":\"the customer confirmed the top-up in chat\"}",
                world.operator, null);
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(body == null
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

    /** Runs every call at once behind one gate; answers their results in order. */
    private static <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<T>> running = new ArrayList<>();
            for (Callable<T> call : calls) {
                running.add(pool.submit(() -> {
                    open.await();
                    return call.call();
                }));
            }
            open.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> result : running) {
                try {
                    results.add(result.get(5, TimeUnit.MINUTES));
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof Exception cause) {
                        throw cause;
                    }
                    if (failure.getCause() instanceof Error cause) {
                        throw cause;
                    }
                    throw failure;
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String hmacHex(String signedPayload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    /** The door's own tally, by outcome: {@code finapp.payments.webhook} (P5-TSK-017). */
    private Map<String, Double> doorOutcomes() {
        Map<String, Double> outcomes = new LinkedHashMap<>();
        for (String outcome : List.of("processed", "duplicate", "refused", "unmappable")) {
            outcomes.put(outcome,
                    registry.find(WEBHOOK_METER).tag("outcome", outcome).counter().count());
        }
        return outcomes;
    }

    // -----------------------------------------------------------------
    // Seeds
    // -----------------------------------------------------------------

    /** A real customer wallet ledger account, owned by a fresh customer reference. */
    private LedgerAccountId wallet() throws Exception {
        return asActor(uow -> ledgerAccountStore
                .createOrConverge(
                        uow,
                        LedgerAccount.owned(
                                IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET,
                                EUR, IDS.next()))
                .account()
                .id());
    }

    /**
     * A captured 10.00 card payment crediting {@code credit}, seeded raw WITH its capture's
     * posting (the production key and lines: DR the card clearing / CR the counterparty), so the
     * counterparty holds exactly what the capture credited it.
     */
    private Payment captured(World world, LedgerAccountId credit) throws Exception {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        String capture = "cap-" + IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, credit_account_id, amount_minor, currency,"
                            + " scale, status, created_at, capture_mode)"
                            + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, 'SUCCEEDED', now(),"
                            + " 'AUTOMATIC')",
                    intent, IDS.next(), IDS.next(), IDS.next(), credit.value());
            execute(app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " capture_reference, auth_provider_reference,"
                            + " capture_provider_reference, authorized_amount_minor,"
                            + " authorized_currency, authorized_scale, captured_amount_minor,"
                            + " captured_currency, captured_scale, status, created_at, rail,"
                            + " interaction_model)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 1000, 'EUR', 2, 1000, 'EUR', 2,"
                            + " 'CAPTURED', now(), 'card', 'TWO_STEP')",
                    attempt, intent, "auth-" + IDS.next(), capture, "psp-auth-" + IDS.next(),
                    "psp-cap-" + IDS.next());
        }
        posted("payment-capture:" + attempt, attempt.toString(),
                List.of(
                        new JournalLine(clearing(), Direction.DEBIT,
                                Money.ofMinorUnits(CAPTURED, EUR)),
                        new JournalLine(credit, Direction.CREDIT,
                                Money.ofMinorUnits(CAPTURED, EUR))));
        Payment payment = new Payment(intent, attempt, capture, credit);
        world.credited.computeIfAbsent(credit, any -> new CopyOnWriteArrayList<>()).add(payment);
        return payment;
    }

    /** The customer spends {@code minor} out of the wallet, to the fee revenue. */
    private void spend(LedgerAccountId wallet, long minor) throws Exception {
        LedgerAccountId elsewhere =
                asActor(uow -> new ChartOfAccounts<>(ledgerAccountStore)
                        .resolve(uow, AccountPurpose.FEE_REVENUE, EUR)
                        .id());
        posted("test-spend:" + IDS.next(), "spend-" + IDS.next(),
                List.of(
                        new JournalLine(wallet, Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                        new JournalLine(elsewhere, Direction.CREDIT,
                                Money.ofMinorUnits(minor, EUR))));
    }

    private void closeLedgerAccount(LedgerAccountId account) throws Exception {
        asActor(uow -> ledgerAccountStore.moveStatus(
                uow, account, LedgerAccountStatus.ACTIVE, LedgerAccountStatus.CLOSED,
                Instant.now(CLOCK)));
    }

    private LedgerAccountId clearing() throws Exception {
        return asActor(uow -> new ChartOfAccounts<>(ledgerAccountStore)
                .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                .id());
    }

    /** One posting as the platform, through the production posting service. */
    private void posted(String key, String reference, List<JournalLine> lines) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope platform = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LocalDate today = LocalDate.now(CLOCK);
            postingService.post(app, new PostingCommand(key, today, today, reference, lines));
            app.commit();
        }
    }

    /** The production refund command, as an operator; the PSP approves every refund. */
    private PaymentRefund.RefundResult refund(Payment payment, long minor) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope operator =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.EMPLOYEE))) {
            return paymentRefund.refund(
                    PaymentIntentId.of(payment.intent()),
                    Money.ofMinorUnits(minor, EUR),
                    "dispute-battery refund",
                    "dbr-" + UUID.randomUUID());
        }
    }

    /** A session holding {@code role}, through the real registration and authentication. */
    private String session(RoleName role) throws Exception {
        String login = "battery." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration =
                post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey());
        assertThat(registration.statusCode()).as(registration.body()).isEqualTo(201);
        UUID identity =
                UUID.fromString(oneString(
                        "SELECT id FROM identity.identity WHERE login_identifier = ?", login));
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
        HttpResponse<String> authenticated =
                post("/v1/authentications",
                        "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                        null, someKey());
        return field(authenticated.body(), "sessionToken");
    }

    private <R> R asActor(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    private static Instant future() {
        return Instant.now(CLOCK).plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String someDisputeReference() {
        return "dp_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    // -----------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------

    /** One dispute row: its stage, and its split and fee in minor units (NULL-aware). */
    private record DisputeRow(
            UUID id, String stage, Long chargeback, long share, long parked, Long fee) {

        private long excess() {
            return chargeback == null ? 0 : chargeback - share - parked;
        }
    }

    private static DisputeRow disputeRow(String reference) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT id, stage, chargeback_amount_minor,"
                                + " counterparty_share_amount_minor, parked_share_amount_minor,"
                                + " dispute_fee_amount_minor FROM payments.dispute"
                                + " WHERE provider = ? AND provider_dispute_reference = ?")) {
            read.setString(1, SimulatedCardPspAdapter.NAME);
            read.setString(2, reference);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the dispute %s was recorded", reference).isTrue();
                DisputeRow found =
                        new DisputeRow(
                                row.getObject(1, UUID.class),
                                row.getString(2),
                                nullableLong(row, 3),
                                row.getLong(4),
                                row.getLong(5),
                                nullableLong(row, 6));
                assertThat(row.next())
                        .as("ONE row for the dispute %s, however often notified", reference)
                        .isFalse();
                return found;
            }
        }
    }

    private static Long nullableLong(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static UUID disputeId(Stated dispute) throws SQLException {
        return disputeRow(dispute.reference()).id();
    }

    /** {counterparty share, parked share, excess}, in minor units. */
    private static long[] split(Stated dispute) throws SQLException {
        DisputeRow row = disputeRow(dispute.reference());
        return new long[] {row.share(), row.parked(), row.excess()};
    }

    /** The dispute's trail, {@code FROM>TO}, in insertion order. */
    private static List<String> trail(UUID dispute) throws SQLException {
        return strings(
                "SELECT from_stage || '>' || to_stage FROM payments.dispute_event"
                        + " WHERE dispute_id = ? ORDER BY id",
                dispute);
    }

    /** The stage each DisputeStageApplied record names. */
    private static List<String> stagesApplied(UUID dispute) throws SQLException {
        return strings(
                "SELECT substring(change_summary FROM 'to=([A-Z_]+)$')"
                        + " FROM platform.audit_record WHERE target_id = ?"
                        + " AND operation = 'payments.DisputeStageApplied'",
                dispute.toString());
    }

    /** Every fact the dispute published, by type. */
    private static List<String> facts(UUID dispute) throws SQLException {
        return strings("SELECT event_type FROM platform.outbox_event WHERE aggregate_id = ?",
                dispute);
    }

    /**
     * Every line of every entry referencing the dispute, as {@code
     * operation|DIRECTION:PURPOSE:minor}.
     */
    private static List<String> lines(UUID dispute) throws SQLException {
        return strings(
                "SELECT split_part(substring(entry.idempotency_scope FROM"
                        + " length('ledger.post:') + 1), ':', 1) || '|' || line.direction"
                        + " || ':' || account.purpose || ':' || line.amount_minor"
                        + " FROM ledger.journal_entry entry"
                        + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
                        + " JOIN ledger.ledger_account account"
                        + "   ON account.id = line.ledger_account_id"
                        + " WHERE entry.reference = ? ORDER BY entry.id, line.seq",
                dispute.toString());
    }

    /** Per purpose, debits minus credits over {@code lines}. */
    private static Map<String, Long> nets(List<String> lines) {
        Map<String, Long> nets = new LinkedHashMap<>();
        for (String line : lines) {
            String[] parts = line.split("\\|")[1].split(":");
            long signed = Long.parseLong(parts[2]) * (parts[0].equals("DEBIT") ? 1 : -1);
            nets.merge(parts[1], signed, Long::sum);
        }
        return nets;
    }

    /** Per purpose, debits minus credits over every entry referencing one of {@code disputes}. */
    private static Map<String, Long> netsOver(String[] disputes) throws SQLException {
        Map<String, Long> nets = new LinkedHashMap<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT account.purpose, coalesce(sum(CASE line.direction"
                                + " WHEN 'DEBIT' THEN line.amount_minor"
                                + " ELSE -line.amount_minor END), 0)"
                                + " FROM ledger.journal_entry entry"
                                + " JOIN ledger.journal_line line ON line.entry_id = entry.id"
                                + " JOIN ledger.ledger_account account"
                                + "   ON account.id = line.ledger_account_id"
                                + " WHERE entry.reference = ANY (?)"
                                + " GROUP BY account.purpose")) {
            read.setArray(1, app.createArrayOf("text", disputes));
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    nets.put(rows.getString(1), rows.getLong(2));
                }
            }
        }
        return nets;
    }

    /** A liability-normal account's settled position from its lines: credits minus debits. */
    private static long balance(LedgerAccountId account) throws SQLException {
        return count(
                "SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_minor"
                        + " ELSE -amount_minor END), 0) FROM ledger.journal_line"
                        + " WHERE ledger_account_id = ?",
                account.value());
    }

    private static long audits(UUID target, String operation) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation = ?",
                target.toString(), operation);
    }

    private static String field(String body, String name) {
        Matcher found = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }

    private static String oneString(String sql, Object... arguments) throws SQLException {
        List<String> values = strings(sql, arguments);
        assertThat(values).as(sql).hasSize(1);
        return values.get(0);
    }

    private static List<String> strings(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            bind(app, read, arguments);
            List<String> values = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            bind(app, read, arguments);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, arguments);
            statement.executeUpdate();
        }
    }

    /** Positional arguments; a {@code UUID[]} binds as a {@code uuid[]} for {@code = ANY (?)}. */
    private static void bind(Connection app, PreparedStatement statement, Object... arguments)
            throws SQLException {
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i] instanceof UUID[] identifiers) {
                statement.setArray(i + 1, app.createArrayOf("uuid", identifiers));
            } else {
                statement.setObject(i + 1, arguments[i]);
            }
        }
    }
}
