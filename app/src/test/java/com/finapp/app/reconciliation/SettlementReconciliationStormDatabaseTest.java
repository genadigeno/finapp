package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.merchant.PayoutReturnSweep;
import com.finapp.app.settlement.SimulatedBankStatements;
import com.finapp.app.settlement.SimulatedPayoutReports;
import com.finapp.app.settlement.SimulatedSchemeReports;
import com.finapp.app.settlement.SimulatedSettlementReports;
import com.finapp.app.telemetry.ReconciliationOutcomeMeters;
import com.finapp.app.telemetry.ReconciliationReplayMeters;
import com.finapp.app.telemetry.SettlementMeters;
import com.finapp.app.telemetry.SettlementPullMetrics;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.InternalReferenceLookup;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.MatchingRules;
import com.finapp.reconciliation.MatchingStore;
import com.finapp.reconciliation.ReconciliationSweep;
import com.finapp.reconciliation.Resolutions;
import com.finapp.reconciliation.Suspense;
import com.finapp.settlement.AcceptedBatchIntake;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.IntakeOutcomeObserver;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementPull;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <strong>The settlement and reconciliation storm</strong> (`P8-TST-001`) — Phase 8's composition
 * demonstration: every source's evidence, every provider-shaped fault that produces a break and
 * every recovery path at once, under ten matcher instances, reconciled to the minor unit in every
 * round and again at rest. A reconciliation of the reconciliation, counted rather than argued.
 *
 * <h2>The traffic, through the application's own doors</h2>
 *
 * <p>Card top-ups and card checkout sales captured on the PSP, refunds of them, bank top-ups the
 * scheme executes by its signed callback (delivered three times), withdrawals, and merchant
 * payouts through the real {@code MerchantPayouts} against the simulated payout provider — the
 * movers of {@link StormTraffic}, a purpose-built smaller set MIRRORING the doors
 * {@code MultiRailConservationStormDatabaseTest} drives (that storm is not refactored). Each
 * completion posts its clearing line and opens its expectation in its own transaction, so the
 * internal records the storm reconciles are production's, and they are made while the matchers
 * run.
 *
 * <h2>The evidence, rendered from those records, with every fault seeded</h2>
 *
 * <p>The simulated providers' reports are rendered from what the payments and merchant tables say
 * the providers did ({@link SimulatedSettlementReports}, {@link SimulatedSchemeReports},
 * {@link SimulatedPayoutReports}) and the bank's statements from the accepted remittances
 * ({@link SimulatedBankStatements}). The census oracle — each fault and EXACTLY the break it must
 * produce, and no other:
 *
 * <ul>
 *   <li>a duplicate line (one capture twice in a report) — {@code DUPLICATE_EXTERNAL /
 *       REPEATED_FINGERPRINT}, parked in CREDIT suspense;
 *   <li>a dropped line — {@code MISSING_EXTERNAL / EXPECTATION_OVERDUE} once the expectation ages
 *       (its {@code expected_by} is frozen by trigger, so it is aged by root with that one trigger
 *       disabled for the one {@code UPDATE}, the register-wipe precedent), raised ONCE under ten
 *       ageing sweepers;
 *   <li>an amount under — {@code AMOUNT_MISMATCH / AMOUNT_DIFFERS} on the expectation, closed by a
 *       four-eyes {@code WRITE_OFF} over HTTP; an amount over — the same type on the item, its
 *       excess parked, closed {@code EVIDENCED} by the counterparty's correction (a
 *       {@code COUNTERPARTY_ADJUSTMENT} in the next day's report offsetting it);
 *   <li>a fee delta — {@code FEE_MISMATCH / FEE_BEYOND_TOLERANCE} (one line beyond the per-line
 *       bound, the batch within its own), whose {@code ACKNOWLEDGE} a second operator REJECTS;
 *   <li>a late date — {@code TIMING_DIFFERENCE / LATE_MATCH}; a cycle shift (a pay-in announced in
 *       one cycle, reported in another) — {@code TIMING_DIFFERENCE / CYCLE_MISMATCH};
 *   <li>an unknown line — {@code UNKNOWN_EXTERNAL / GRACE_EXPIRED}, owned AT RUN TIME: an
 *       {@code OTHER_IN} no rule set can name has a grace of zero, so the production path parks it
 *       with its break in the run's own chunk. *(Corrected 2026-10-02 by the Phase 8 -> 9
 *       transition, REC-2: the storm reached this break only by writing the line's
 *       {@code grace_until} by hand - the production path had left it waiting with no clock and no
 *       break, for ever.)*
 *   <li>a wrong currency (a whole report in GBP naming a EUR capture) — {@code CURRENCY_MISMATCH /
 *       CURRENCY_DIFFERS}, never allocated nor converted;
 *   <li>a malformed field and a bad trailer — files {@code REJECTED} whole ({@code MALFORMED},
 *       {@code CONTROL_TOTAL_MISMATCH}): no batch, no break;
 *   <li>a PAN and an IBAN in free text — refused AT THE DOOR ({@code PRIMARY_ACCOUNT_NUMBER},
 *       {@code ACCOUNT_IDENTIFIER}), metadata only: the plaintext in no chunk and no captured log;
 *   <li>a statement gap (the EUR chain's third statement delivered before its second) —
 *       {@code SETTLEMENT_MISMATCH / STATEMENT_GAP}, CRITICAL, EUR's cash verdict NOT proven while
 *       it stands and proven once the late statement stitches the chain, the break closing
 *       {@code EVIDENCED};
 *   <li>a non-zero opening — {@code SETTLEMENT_MISMATCH / OPENING_BALANCE} on USD's first
 *       statement; USD's cash verdict failing is the EXPECTED verdict from then on, every other
 *       verdict zero;
 *   <li>a payout return — applied once by {@code PayoutReturnSweep} (the payable restored once),
 *       then allocated by the rematch leg: no break.
 * </ul>
 *
 * <h2>Delivered twice, out of order, by ten instances, through crashes</h2>
 *
 * <p>Every file is delivered twice — uploaded over {@code POST /v1/operator/settlement/files} and
 * attested by a second operator, and fetched by ten racing pulls of its business key from the
 * simulated provider serving all four sources by path — upload-first and pull-first in turn:
 * one file, one batch, one run and one recognition entry per delivery set, a receipt per
 * delivery, the herd's nine losers recording nothing. Files arrive out of order (a cycle's
 * report before the one it follows, the EUR chain's third statement before its second, a payout's
 * return a day after its execution).
 *
 * <p>Ten {@code Matching} instances are built here from the composition's own beans — the
 * reconciliation {@code TransactionRunner} and the metered {@code BreakRegister}, so every meter
 * counts after commit exactly as production's, never a raw connection runner whose rolled-back
 * crash work would be counted — each with its own clock (the JVM clock plus one offset measured
 * once: the server's lead, clamped at zero so no instance reads behind the SERVER - the
 * {@code SimulatedInstance} rule - plus {@code i} ms, so the ten are milliseconds apart) and
 * chunks of three, so every run spans chunks; every round all ten sweep at once, each also driving
 * the parse and accept legs, the payout return worker and time's observers. THREE crashes are
 * injected, each simulated as a crash — a thrown {@link Error} or the connection killed with
 * {@code pg_terminate_backend} (a {@code RuntimeException} would be the legs' own "our failure"
 * path, scenario 5, not a crash): after {@value #CRASH_AFTER_LINES} of the report's lines are
 * written (a test-built {@link FileParsing} over a decorated {@link SettlementBatchStore}), after
 * the posting inside acceptance (a test-built {@link BatchAcceptance} over a decorated
 * {@link AcceptedBatchIntake}, which acceptance calls right after the recognition posts), and
 * mid-chunk (a decorated {@link MatchingStore} killing its own connection before the cursor
 * advances); and the TWO sweep boundaries are stopped at and read - between parse and accept, and
 * between accept and the first chunk. Each crash is resumed by another instance; nothing partial
 * survives.
 *
 * <h2>Every round, in ONE {@code REPEATABLE READ} snapshot, and again at rest</h2>
 *
 * <ol>
 *   <li>the position proof per clearing position and currency ({@code INV-REC-06}), the suspense
 *       proof, suspense ownership ({@code INV-REC-09}), completeness (every line on a reconciled
 *       position known, {@code INV-SET-02}) and the cash proof — each verdict asserted exactly,
 *       the cash verdict per currency against the chain the same snapshot holds;
 *   <li>the trial balance, zero per currency ({@code INV-ACC-01});
 *   <li>the break census against the oracle — never a break the oracle does not name;
 *   <li>every external item in at most one positive allocation per expectation, and neither an
 *       expectation nor an item allocated past its amount, counted from the allocation rows.
 * </ol>
 *
 * <p>At rest additionally: every run replayed over {@code POST /runs/{id}/replay} —
 * {@code IDENTICAL}, each, over exactly the twelve runs the twelve accepted batches made; the
 * break census EQUAL to the oracle, statuses included, each break on its fault's own subject;
 * the ITEM census - every external item the storm produced at its expected final status
 * (matched, checked, offset, resolved or parked, none left waiting) - and the EXPECTATION census
 * (settled, but for the dropped capture, the wrong-currency capture and its report's remittance,
 * still owing, and the written-off remainder); one effect per delivery set; the platform's acts
 * counted (one
 * {@code settlement.SettlementBatchAccepted}, one {@code reconciliation.RunCompleted}, one
 * {@code reconciliation.BreakRaised} per batch, run and break, each with its outbox event); and
 * <strong>the second tally</strong> ({@code P7-TSK-015}'s design, valid because every schedule is
 * off in test contexts and the database is private to this JVM): the meters' deltas equal the
 * tables' facts, source by source and outcome by outcome.
 *
 * <h2>What it found</h2>
 *
 * <p>The payout return's line waited for its 72-hour grace in one run of two: the rematch leg's
 * operation-anchored clause read "a {@code PAYOUT_RETURN} opened after the item's latest
 * decision", comparing the worker instance's {@code opened_at} with the matcher instance's
 * {@code decided_at} - two instances' clocks - and the instances here are milliseconds apart, as
 * production's are. The clause now asks whether any decision of the item has SEEN the return as
 * a candidate (judged on rows alone, {@code JdbcMatchingStore}), and
 * {@code PayoutMatchingDatabaseTest#theAnchoredClauseComparesNoTwoClocks} holds it with a matcher
 * a minute fast.
 *
 * <p><strong>Its own container.</strong> Run alone ({@code :app:databaseTest --tests
 * "*SettlementReconciliationStormDatabaseTest"}): the proofs are global and absolute, and each
 * currency's bank chain (one account per currency, {@code SIMBANK-<CCY>-01}) is otherwise
 * {@code BankStatementCashDatabaseTest}'s. Anything that is not a domain outcome — a contained
 * chunk, parse, accept, grace, rematch or return failure the storm did not inject — fails the
 * storm, read from the captured log rather than retried away.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest (X-TSK-016)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the settlement and reconciliation storm (P8-TST-001)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class SettlementReconciliationStormDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static final String PSP = "simulated-psp.settlement";
    private static final String SCHEME = "simulated-scheme.cycle-report";
    private static final String PAYOUT = "simulated-payout.settlement";
    private static final String BANK = "simulated-bank.statement";
    private static final String FILES = "/v1/operator/settlement/files";
    private static final String RECON = "/v1/operator/reconciliation";

    /** Ten instances; chunks of three, so every run spans chunks. */
    private static final int INSTANCES = 10;

    private static final int CHUNK = 3;

    /** The parse crash lands after this many of the first report's lines are written. */
    private static final int CRASH_AFTER_LINES = 10;
    private static final int PULL_RACERS = 10;
    private static final int MAX_ROUNDS = 80;

    /** Rounds that do nothing while a file or run is still in flight before the storm fails. */
    private static final int STUCK_ROUNDS = 20;

    /** The pull's schedule window: the herd's nine losers are paced inside it. */
    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** This run's report credential, for every source: 32 fresh random bytes. */
    private static final String REPORT_KEY = freshKey();

    /** The PSP's published terms in rule set v1: round_half_up(1.5% x gross) + 0.25. */
    private static final BigDecimal PSP_RATE = new BigDecimal("0.015");

    private static final long PSP_FIXED = 25;
    private static final String SCHEME_FEE = "0.10";
    private static final String PAYOUT_FEE = "0.25";

    /** The free-text needles the door must refuse, and nothing may ever echo. */
    private static final String PAN_NEEDLE = "4111 1111 1111 1111";

    private static final String IBAN_NEEDLE = "DE89370400440532013000";

    private static SimulatedProvider provider;

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // One stub server, every provider and every report path. Generous timeouts: an answer
        // that is merely slow under load must not turn into ambiguity the storm did not choose.
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT10S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT10S");
        registry.add("finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(StormTraffic.CARD_WEBHOOK_KEY));
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT10S");
        registry.add("finapp.payments.instant.webhook.key",
                () -> Base64.getEncoder().encodeToString(StormTraffic.INSTANT_WEBHOOK_KEY));
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
        for (String source : List.of("psp.report", "scheme.report", "payout.report",
                "bank.statement")) {
            registry.add("finapp.settlement." + source + ".url", () -> provider.baseUrl());
            registry.add("finapp.settlement." + source + ".key", () -> REPORT_KEY);
        }
        registry.add("finapp.settlement.pull.timeout", () -> "PT10S");
        registry.add("finapp.settlement.pull.window", WINDOW::toString);
    }

    @AfterAll
    static void stopProvider() {
        if (provider != null) {
            provider.close();
        }
    }

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MerchantPayouts merchantPayouts;
    @Autowired private PositionProof proof;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private SettlementPull settlementPull;
    @Autowired private PayoutReturnSweep payoutReturnSweep;
    @Autowired private ReconciliationSweep reconciliationSweep;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private IdGenerator idGenerator;
    @Autowired private Clock clock;
    // The composition's own parts, from which the storm builds its instances.
    @Autowired private SettlementFileStore<Connection> settlementFileStore;
    @Autowired private SettlementBatchStore<Connection> settlementBatchStore;
    @Autowired private SettlementSources settlementSources;
    @Autowired private Map<SettlementFormatId, SettlementFormat> settlementFormats;
    @Autowired private IntakeOutcomeObserver intakeOutcomeObserver;
    @Autowired private AcceptedBatchIntake acceptedBatchIntake;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private com.finapp.settlement.TransactionRunner settlementTransactionRunner;
    @Autowired private MatchingStore matchingStore;
    @Autowired private MatchingRules matchingRules;
    @Autowired private BreakRegister breakRegister;
    @Autowired private Suspense suspense;
    @Autowired private Resolutions resolutions;
    @Autowired private InternalReferenceLookup internalReferenceLookup;
    @Autowired private com.finapp.reconciliation.TransactionRunner reconciliationTransactionRunner;
    @Autowired private ReconciliationOutcomeMeters reconciliationOutcomeMeters;

    private final TrialBalance trialBalance = new TrialBalance();

    /** The census oracle: every break the storm's faults must raise by the end, and no other. */
    private final List<String> oracle = new ArrayList<>();

    // ----------------------------------------------------------------- the storm

    @Test
    @DisplayName("every source's evidence with every break-producing fault, delivered twice, out"
            + " of order and late, under ten matcher instances through three injected crashes and"
            + " two sweep boundaries, rematches, payout returns and two operators' resolutions:"
            + " every round's proofs, trial balance, census and allocations hold in one snapshot;"
            + " at rest the break, item and expectation censuses equal their oracles, every run"
            + " replays IDENTICAL, each delivery set had one effect, the platform's acts are"
            + " counted and the meters' tally equals the tables'")
    void theStormReconcilesTheReconciliation(CapturedOutput output) throws Exception {
        // Every date is derived from ONE reading of the database's UTC date - no midnight drift
        // between this line and the database's later judgements.
        LocalDate today =
                ((java.sql.Date) one("SELECT (now() AT TIME ZONE 'UTC')::date")).toLocalDate();
        String marker = StormTraffic.letters(6);
        // The scheme's cycle tokens, a letter before the marker (the format's no-card-run
        // class joins digits across single dashes).
        String cycleA = "CYC-" + today.minusDays(1) + "-A" + marker;
        String cycleB = "CYC-" + today.minusDays(1) + "-B" + marker;
        String cycleC = "CYC-" + today + "-C" + marker;

        StormTraffic traffic = new StormTraffic(port, provider, authorization, merchantPayouts);
        Instances instances = new Instances(INSTANCES);
        ExecutorService movers = Executors.newFixedThreadPool(6);
        try {
            traffic.stubTheRails(cycleA);
            StormTraffic.Staff administrator = traffic.staff(RoleName.MERCHANT_ADMINISTRATOR);
            StormTraffic.Staff refunder = traffic.staff(RoleName.LEDGER_OPERATOR);
            StormTraffic.Staff uploader = traffic.staff(RoleName.RECONCILIATION_OPERATOR);
            StormTraffic.Staff attester = traffic.staff(RoleName.RECONCILIATION_OPERATOR);
            StormTraffic.Staff proposer = traffic.staff(RoleName.RECONCILIATION_OPERATOR);
            StormTraffic.Staff approver = traffic.staff(RoleName.RECONCILIATION_OPERATOR);
            List<StormTraffic.Customer> customers =
                    List.of(traffic.customer(), traffic.customer(), traffic.customer());
            StormTraffic.Merchant merchant = traffic.merchant(administrator);
            Deliveries deliveries = new Deliveries(traffic, uploader, attester);

            Map<String, Long> metersBefore = meterTally();
            Map<String, Long> factsBefore = tableTally();
            reconcile("before the storm");

            // ===================================================== round 1: the traffic
            // Phase A at once: card top-ups, card sales, pay-in initiations.
            Map<String, String> topUps = new java.util.concurrent.ConcurrentHashMap<>();
            Map<String, String> payIns = new java.util.concurrent.ConcurrentHashMap<>();
            long[] topUpAmounts = {20_11, 20_13, 20_17, 20_19, 20_23, 20_29, 20_31, 20_37, 20_41};
            List<Callable<Void>> phaseA = new ArrayList<>();
            for (int c = 0; c < 3; c++) {
                int owner = c;
                phaseA.add(() -> {
                    for (int t = owner; t < topUpAmounts.length; t += 3) {
                        topUps.put("T" + (t + 1),
                                traffic.cardTopUp(customers.get(owner), topUpAmounts[t]));
                    }
                    return null;
                });
            }
            long[] saleAmounts = {30_11, 30_13, 30_17};
            phaseA.add(() -> {
                for (int s = 0; s < saleAmounts.length; s++) {
                    traffic.cardSale(merchant, customers.get(s), saleAmounts[s]);
                }
                return null;
            });
            long[] payInAmounts = {15_11, 15_13, 15_17, 15_19};
            phaseA.add(() -> {
                for (int p = 0; p < payInAmounts.length; p++) {
                    payIns.put("P" + (p + 1),
                            traffic.bankTopUp(customers.get(p % 3), payInAmounts[p]));
                }
                return null;
            });
            runAll(movers, phaseA);
            StormTraffic.Merchant trading = traffic.withPayable(merchant);

            // Phase B at once: the scheme executes every pay-in (announcing cycle A, P4 included
            // - the report will settle P4 in cycle B), withdrawals in cycle A, refunds, payouts.
            Map<String, String> schemeRefs = new LinkedHashMap<>();
            for (int p = 1; p <= payInAmounts.length; p++) {
                schemeRefs.put("P" + p, "SCH-PI-" + StormTraffic.letters(12));
            }
            Map<String, UUID> payoutIds = new java.util.concurrent.ConcurrentHashMap<>();
            List<Callable<Void>> phaseB = new ArrayList<>();
            phaseB.add(() -> {
                for (int p = 1; p <= payInAmounts.length; p++) {
                    traffic.executePayIn(payIns.get("P" + p), payInAmounts[p - 1],
                            schemeRefs.get("P" + p), cycleA);
                }
                return null;
            });
            phaseB.add(() -> {
                traffic.withdraw(customers.get(1), 6_11);
                traffic.withdraw(customers.get(2), 6_13);
                return null;
            });
            phaseB.add(() -> {
                traffic.refund(refunder, topUps.get("T2"), 5_00);
                traffic.refund(refunder, topUps.get("T1"), 4_00);
                return null;
            });
            phaseB.add(() -> {
                payoutIds.put("Y1", traffic.payout(trading, 10_11));
                payoutIds.put("Y2", traffic.payout(trading, 10_13));
                return null;
            });
            runAll(movers, phaseB);
            reconcile("round 1's traffic committed");

            // ===================================================== round 1: the evidence
            Records records = new Records();
            Map<String, Capture> captures = new LinkedHashMap<>();
            for (Map.Entry<String, String> topUp : topUps.entrySet()) {
                captures.put(topUp.getKey(), records.captureOfIntent(topUp.getValue()));
            }
            List<Capture> sales = records.salesOf(trading);
            assertThat(sales).as("three card sales captured").hasSize(3);

            // PSP D1, every capture-level fault in one report.
            LocalDate d1 = today.minusDays(2);
            String unknownRef = "MISC-" + StormTraffic.letters(10);
            SimulatedSettlementReports pspD1 =
                    new SimulatedSettlementReports("PSPB-STORM-1-" + marker, "EUR", d1,
                            pspRemittance());
            pspD1.with(captureLine(captures.get("T1"), 0, 0));
            pspD1.with(captureLine(captures.get("T2"), 0, 0));
            // T3 is DROPPED: its expectation will age into MISSING_EXTERNAL.
            pspD1.with(captureLine(captures.get("T4"), 0, 0));
            pspD1.with(captureLine(captures.get("T5"), -1_00, 0)); // amount under
            pspD1.with(captureLine(captures.get("T6"), 50, 0)); // amount over
            pspD1.with(captureLine(captures.get("T7"), 0, 30)); // fee beyond the line's bound
            pspD1.with(captureLine(captures.get("T8"), 0, 0).settledOn(today.plusDays(6)));
            for (Capture sale : sales) {
                pspD1.with(captureLine(sale, 0, 0));
            }
            for (RefundRecord refund : records.refunds()) {
                pspD1.with(SimulatedSettlementReports.Line.refund(
                        refund.providerRef(), refund.ourRef(), decimal(refund.amount())));
            }
            pspD1.with(captureLine(captures.get("T4"), 0, 0).withoutFee()); // the duplicate
            pspD1.with(SimulatedSettlementReports.Line.unknown(unknownRef, "5.00"));
            byte[] pspD1Bytes = pspD1.render();
            // T9 is reported ONLY in a whole report in GBP: the wrong currency.
            byte[] gbpBytes =
                    new SimulatedSettlementReports("PSPB-STORM-GBP-" + marker, "GBP",
                                    today.minusDays(1), pspRemittance())
                            .with(SimulatedSettlementReports.Line.capture(
                                    captures.get("T9").ref(), "", "",
                                    decimal(captures.get("T9").amount()), ""))
                            .render();
            oracle.addAll(List.of(
                    "DUPLICATE_EXTERNAL:REPEATED_FINGERPRINT:OPEN",
                    "MISSING_EXTERNAL:EXPECTATION_OVERDUE:OPEN",
                    "AMOUNT_MISMATCH:AMOUNT_DIFFERS:RESOLVED",
                    "AMOUNT_MISMATCH:AMOUNT_DIFFERS:RESOLVED",
                    // Its acknowledgement rejected: the break returns to INVESTIGATING
                    // (ResolutionMachine's rejection edge), still standing.
                    "FEE_MISMATCH:FEE_BEYOND_TOLERANCE:INVESTIGATING",
                    "TIMING_DIFFERENCE:LATE_MATCH:OPEN",
                    "UNKNOWN_EXTERNAL:GRACE_EXPIRED:OPEN",
                    "CURRENCY_MISMATCH:CURRENCY_DIFFERS:OPEN",
                    "TIMING_DIFFERENCE:CYCLE_MISMATCH:OPEN",
                    "SETTLEMENT_MISMATCH:STATEMENT_GAP:RESOLVED",
                    "SETTLEMENT_MISMATCH:OPENING_BALANCE:OPEN"));

            // The scheme's cycle A (P1..P3 and both withdrawals) and cycle B (P4, announced A).
            List<WithdrawalRecord> withdrawals = records.withdrawals();
            assertThat(withdrawals).as("two withdrawals completed in cycle A").hasSize(2)
                    .allSatisfy(w -> assertThat(w.cycle()).isEqualTo(cycleA));
            SimulatedSchemeReports schemeA =
                    new SimulatedSchemeReports(cycleA, "EUR", today.minusDays(1).toString(),
                            schemeRemittance());
            for (int p = 1; p <= 3; p++) {
                schemeA.with(schemeCredit(payInAmounts[p - 1], schemeRefs.get("P" + p)));
            }
            for (WithdrawalRecord withdrawal : withdrawals) {
                schemeA.with(schemeDebit(withdrawal.amount(), withdrawal.schemeRef()));
            }
            byte[] schemeABytes = schemeA.render();
            byte[] schemeBBytes =
                    new SimulatedSchemeReports(cycleB, "EUR", today.minusDays(1).toString(),
                                    schemeRemittance())
                            .with(schemeCredit(payInAmounts[3], schemeRefs.get("P4")))
                            .render();
            SimulatedPayoutReports payoutD1 =
                    new SimulatedPayoutReports("PAYDAY-1-" + marker, "EUR", d1.toString(),
                            payoutRemittance());
            for (String payout : List.of("Y1", "Y2")) {
                payoutD1.with(records.payoutLine(payoutIds.get(payout), true));
            }
            byte[] payoutD1Bytes = payoutD1.render();

            // ===================================================== the crashes, on PSP D1
            Delivery d1Delivery =
                    new Delivery("PSP D1", PSP, d1.toString(), d1, pspD1Bytes, Fate.ACCEPTED, true);
            UUID d1File = deliveries.twice(d1Delivery);
            crashesOnTheFirstReport(d1File, pspD1Bytes);

            // ===================================================== round 1 under ten instances
            List<Delivery> roundOne =
                    List.of(
                            // Out of order: cycle B's report before cycle A's.
                            new Delivery("scheme B", SCHEME, cycleB, today.minusDays(1),
                                    schemeBBytes, Fate.ACCEPTED, false),
                            new Delivery("scheme A", SCHEME, cycleA, today.minusDays(1),
                                    schemeABytes, Fate.ACCEPTED, true),
                            new Delivery("payout D1", PAYOUT, d1.toString(), d1, payoutD1Bytes,
                                    Fate.ACCEPTED, false),
                            new Delivery("PSP GBP", PSP, today.minusDays(1).toString(),
                                    today.minusDays(1), gbpBytes, Fate.ACCEPTED, true),
                            new Delivery("PSP malformed", PSP, today.minusDays(3).toString(),
                                    today.minusDays(3),
                                    faultyReport(today.minusDays(3),
                                            SimulatedSettlementReports.Fault.MALFORMED_FIELD),
                                    Fate.REJECTED, false),
                            new Delivery("PSP bad trailer", PSP, today.minusDays(4).toString(),
                                    today.minusDays(4),
                                    faultyReport(today.minusDays(4),
                                            SimulatedSettlementReports.Fault.BAD_TRAILER_NET),
                                    Fate.REJECTED, true),
                            new Delivery("PSP PAN", PSP, today.minusDays(5).toString(),
                                    today.minusDays(5),
                                    faultyReport(today.minusDays(5),
                                            SimulatedSettlementReports.Fault.PAN_IN_FREE_TEXT),
                                    Fate.REFUSED, false),
                            new Delivery("PSP IBAN", PSP, today.minusDays(6).toString(),
                                    today.minusDays(6),
                                    faultyReport(today.minusDays(6),
                                            SimulatedSettlementReports.Fault.IBAN_IN_FREE_TEXT),
                                    Fate.REFUSED, true));
            instances.runWhile("round 1", () -> {
                for (Delivery delivery : roundOne) {
                    deliveries.twice(delivery);
                }
                return null;
            });
            assertThat(fileStatus(deliveries.file("PSP malformed")))
                    .isEqualTo("REJECTED:MALFORMED");
            assertThat(fileStatus(deliveries.file("PSP bad trailer")))
                    .isEqualTo("REJECTED:CONTROL_TOTAL_MISMATCH");

            // ===================================================== round 2: traffic and evidence
            traffic.withdrawalsSettleIn(cycleC);
            String p5Ref = "SCH-PI-" + StormTraffic.letters(12);
            Map<String, String> roundTwoTopUps = new java.util.concurrent.ConcurrentHashMap<>();
            instances.runWhile("round 2's traffic", () -> {
                List<Callable<Void>> roundTwo = new ArrayList<>();
                roundTwo.add(() -> {
                    roundTwoTopUps.put("T10", traffic.cardTopUp(customers.get(0), 21_11));
                    roundTwoTopUps.put("T11", traffic.cardTopUp(customers.get(1), 21_13));
                    return null;
                });
                roundTwo.add(() -> {
                    String p5 = traffic.bankTopUp(customers.get(1), 16_11);
                    traffic.executePayIn(p5, 16_11, p5Ref, cycleC);
                    traffic.withdraw(customers.get(0), 7_11);
                    return null;
                });
                roundTwo.add(() -> {
                    payoutIds.put("Y3", traffic.payout(trading, 10_17));
                    return null;
                });
                runAll(movers, roundTwo);
                return null;
            });

            SimulatedSettlementReports pspD2 =
                    new SimulatedSettlementReports("PSPB-STORM-2-" + marker, "EUR", today,
                            pspRemittance());
            for (String topUp : List.of("T10", "T11")) {
                pspD2.with(captureLine(records.captureOfIntent(roundTwoTopUps.get(topUp)), 0, 0));
            }
            // The counterparty's correction claws back T6's over-payment: offset, EVIDENCED.
            pspD2.with(SimulatedSettlementReports.Line.adjustment(captures.get("T6").ref(),
                    "-0.50"));
            WithdrawalRecord w3 =
                    records.withdrawals().stream()
                            .filter(w -> w.cycle().equals(cycleC))
                            .findFirst()
                            .orElseThrow();
            byte[] schemeCBytes =
                    new SimulatedSchemeReports(cycleC, "EUR", today.toString(), schemeRemittance())
                            .with(schemeCredit(16_11, p5Ref))
                            .with(schemeDebit(w3.amount(), w3.schemeRef()))
                            .render();
            // A day late: Y1's return arrives the day after its execution, beside Y3's.
            byte[] payoutD2Bytes =
                    new SimulatedPayoutReports("PAYDAY-2-" + marker, "EUR",
                                    today.minusDays(1).toString(), payoutRemittance())
                            .with(records.payoutLine(payoutIds.get("Y3"), true))
                            .with(records.payoutLine(payoutIds.get("Y1"), false))
                            .render();
            List<Delivery> roundTwoFiles =
                    List.of(
                            new Delivery("PSP D2", PSP, today.toString(), today,
                                    pspD2.render(), Fate.ACCEPTED, false),
                            new Delivery("scheme C", SCHEME, cycleC, today, schemeCBytes,
                                    Fate.ACCEPTED, true),
                            new Delivery("payout D2", PAYOUT, today.minusDays(1).toString(),
                                    today.minusDays(1), payoutD2Bytes, Fate.ACCEPTED, false));
            instances.runWhile("round 2's evidence", () -> {
                for (Delivery delivery : roundTwoFiles) {
                    deliveries.twice(delivery);
                }
                return null;
            });
            assertThat(count("SELECT count(*) FROM merchant.payout_return WHERE payout_id = ?",
                            payoutIds.get("Y1")))
                    .as("the payout return applied ONCE by the worker, ten instances racing")
                    .isEqualTo(1);

            // ===================================================== the bank: cash, a gap, USD
            Remittance pspD1Remit = remittanceOf(d1File);
            Remittance schemeARemit = remittanceOf(deliveries.file("scheme A"));
            Remittance schemeBRemit = remittanceOf(deliveries.file("scheme B"));
            Remittance payoutD1Remit = remittanceOf(deliveries.file("payout D1"));
            Remittance pspD2Remit = remittanceOf(deliveries.file("PSP D2"));
            Remittance schemeCRemit = remittanceOf(deliveries.file("scheme C"));
            Remittance payoutD2Remit = remittanceOf(deliveries.file("payout D2"));
            LocalDate s1 = today.minusDays(3);
            LocalDate s2 = today.minusDays(2);
            LocalDate s3 = today.minusDays(1);
            SimulatedBankStatements eur1 =
                    bankLines(new SimulatedBankStatements("SB-EUR-1-" + marker, "EUR", 1, s1, 0),
                            s1, pspD1Remit, schemeARemit, schemeBRemit, payoutD1Remit);
            SimulatedBankStatements eur2 =
                    bankLines(new SimulatedBankStatements("SB-EUR-2-" + marker, "EUR", 2, s2,
                                    eur1.closingMinor()),
                            s2, schemeCRemit);
            SimulatedBankStatements eur3 =
                    bankLines(new SimulatedBankStatements("SB-EUR-3-" + marker, "EUR", 3, s3,
                                    eur2.closingMinor()),
                            s3, pspD2Remit, payoutD2Remit);
            byte[] usdBytes =
                    new SimulatedBankStatements("SB-USD-1-" + marker, "USD", 1, today, 5_00)
                            .fee(today, 50)
                            .render(today);
            instances.runWhile("the first statements", () -> {
                deliveries.twice(new Delivery("EUR statement 1", BANK, s1.toString(), s1,
                        eur1.render(s1), Fate.ACCEPTED, true));
                deliveries.twice(new Delivery("USD statement 1", BANK, today.toString(), today,
                        usdBytes, Fate.ACCEPTED, false));
                return null;
            });
            // Out of order: the EUR chain's THIRD statement before its second - the gap.
            instances.runWhile("the third statement, before the second", () -> {
                deliveries.twice(new Delivery("EUR statement 3", BANK, s3.toString(), s3,
                        eur3.render(s3), Fate.ACCEPTED, false));
                return null;
            });
            assertThat(cashOf(EUR).explained())
                    .as("the platform does not know its EUR cash across the open gap")
                    .isFalse();

            // ===================================================== the operators, and time
            resolveBy(proposer, approver, captures);
            assertTheUnknownLineWasOwnedAtRunTime(d1File, unknownRef);
            ageTheDroppedCapture(captures.get("T3"), today);
            instances.drain("the dropped capture aged");

            // The late statement stitches the chain: the gap closes EVIDENCED.
            instances.runWhile("the second statement, late", () -> {
                deliveries.twice(new Delivery("EUR statement 2", BANK, s2.toString(), s2,
                        eur2.render(s2), Fate.ACCEPTED, true));
                return null;
            });
            assertThat(cashOf(EUR).explained())
                    .as("stitched: EUR cash is the unbroken chain's closing again")
                    .isTrue();

            // ===================================================== at rest
            instances.drain("at rest");
            assertNothingFailedButTheInjected(output);
            replayEveryRun(proposer);
            reconcile("at rest, after every replay");
            assertTheCensusEqualsTheOracle(captures, schemeRefs, unknownRef, deliveries);
            // F2: the balance projection every Phase 8 posting maintained under ten instances,
            // verified at rest against replay-from-zero - as the Phase 7 storm verifies its own.
            assertTheProjectionIsCleanAtRest();

            // The item and expectation censuses: every external item the storm produced, and
            // every expectation, at its expected final status - a genuine line left waiting,
            // or an expectation left owing, fails the storm.
            Map<String, Capture> roundTwoCaptures = new LinkedHashMap<>();
            for (String topUp : List.of("T10", "T11")) {
                roundTwoCaptures.put(topUp, records.captureOfIntent(roundTwoTopUps.get(topUp)));
            }
            List<String> expectedItems = new ArrayList<>();
            List<String> expectedExpectations = new ArrayList<>();
            for (String topUp : List.of("T1", "T2", "T4", "T5", "T7", "T8")) {
                // T5's line is allocated whole (its remainder is the expectation's break).
                feeBearingCapture(expectedItems, captures.get(topUp).ref(), "MATCHED");
            }
            // T6's excess was parked, then offset by the correction: the item RESOLVED.
            feeBearingCapture(expectedItems, captures.get("T6").ref(), "RESOLVED");
            for (Capture sale : sales) {
                feeBearingCapture(expectedItems, sale.ref(), "MATCHED");
            }
            for (Capture capture : roundTwoCaptures.values()) {
                feeBearingCapture(expectedItems, capture.ref(), "MATCHED");
            }
            expectedItems.add(itemKey(PSP, "CAPTURE", captures.get("T4").ref(), "PARKED"));
            expectedItems.add(itemKey(PSP, "OTHER_IN", unknownRef, "PARKED"));
            expectedItems.add(itemKey(PSP, "CAPTURE", captures.get("T9").ref(), "PARKED"));
            expectedItems.add(itemKey(PSP, "COUNTERPARTY_ADJUSTMENT", captures.get("T6").ref(),
                    "OFFSET"));
            for (RefundRecord refund : records.refunds()) {
                expectedItems.add(itemKey(PSP, "REFUND", refund.providerRef(), "MATCHED"));
                expectedExpectations.add(expectationKey("CARD_REFUND", refund.providerRef(),
                        "SETTLED"));
            }
            List<String> payInRefs = new ArrayList<>(schemeRefs.values());
            payInRefs.add(p5Ref);
            for (String schemeRef : payInRefs) {
                expectedItems.add(itemKey(SCHEME, "CREDIT_IN", schemeRef, "MATCHED"));
                expectedItems.add(itemKey(SCHEME, "SCHEME_FEE", schemeRef, "CHECKED"));
                expectedExpectations.add(expectationKey("PUSH_PAY_IN", schemeRef, "SETTLED"));
            }
            for (WithdrawalRecord withdrawal : records.withdrawals()) {
                expectedItems.add(itemKey(SCHEME, "DEBIT_OUT", withdrawal.schemeRef(), "MATCHED"));
                expectedItems.add(itemKey(SCHEME, "SCHEME_FEE", withdrawal.schemeRef(),
                        "CHECKED"));
                expectedExpectations.add(expectationKey("PUSH_WITHDRAWAL", withdrawal.schemeRef(),
                        "SETTLED"));
            }
            for (String payout : List.of("Y1", "Y2", "Y3")) {
                String providerRef = records.payoutProviderRef(payoutIds.get(payout));
                expectedItems.add(itemKey(PAYOUT, "PAYOUT_EXECUTED", providerRef, "MATCHED"));
                expectedItems.add(itemKey(PAYOUT, "PAYOUT_FEE", providerRef, "CHECKED"));
                expectedExpectations.add(expectationKey("MERCHANT_PAYOUT", providerRef,
                        "SETTLED"));
            }
            expectedItems.add(itemKey(PAYOUT, "PAYOUT_RETURNED",
                    records.payoutProviderRef(payoutIds.get("Y1")), "MATCHED"));
            expectedExpectations.add(expectationKey("PAYOUT_RETURN",
                    payoutIds.get("Y1").toString(), "SETTLED"));
            for (Remittance paid : List.of(pspD1Remit, schemeARemit, schemeBRemit, payoutD1Remit,
                    schemeCRemit, pspD2Remit, payoutD2Remit)) {
                expectedItems.add(itemKey(BANK, paid.netMinor() > 0 ? "BANK_CREDIT" : "BANK_DEBIT",
                        paid.reference(), "MATCHED"));
                expectedExpectations.add(expectationKey("REMITTANCE", paid.reference(),
                        "SETTLED"));
            }
            expectedItems.add(itemKey(BANK, "BANK_FEE", "-", "CHECKED"));
            // The GBP report's remittance: nothing pays a report naming the wrong currency.
            expectedExpectations.add(expectationKey("REMITTANCE",
                    remittanceOf(deliveries.file("PSP GBP")).reference(), "OPEN"));
            for (Map.Entry<String, Capture> capture : captures.entrySet()) {
                String status =
                        switch (capture.getKey()) {
                            case "T3" -> "OPEN"; // the dropped line: overdue, owing
                            case "T9" -> "OPEN"; // reported only in GBP: never allocated
                            case "T5" -> "RESOLVED_BY_ADJUSTMENT"; // the write-off
                            default -> "SETTLED";
                        };
                expectedExpectations.add(expectationKey("CARD_CAPTURE", capture.getValue().ref(),
                        status));
            }
            for (Capture capture : sales) {
                expectedExpectations.add(expectationKey("CARD_CAPTURE", capture.ref(), "SETTLED"));
            }
            for (Capture capture : roundTwoCaptures.values()) {
                expectedExpectations.add(expectationKey("CARD_CAPTURE", capture.ref(), "SETTLED"));
            }
            assertThat(rows(ITEM_CENSUS))
                    .as("at rest: every external item at its expected final status - each line"
                            + " allocated, checked, offset, resolved or parked as its fault says,"
                            + " none left waiting; a rejected file's lines nowhere")
                    .containsExactlyInAnyOrderElementsOf(expectedItems);
            assertThat(rows(EXPECTATION_CENSUS))
                    .as("at rest: every expectation at its expected final status - settled, but"
                            + " for the dropped capture, the wrong-currency capture and its"
                            + " report's remittance, still owing, and the written-off remainder")
                    .containsExactlyInAnyOrderElementsOf(expectedExpectations);

            // The payout return: allocated by the REMATCH leg to its PAYOUT_RETURN, and the
            // payable restored by exactly one return entry.
            UUID returnedPayout = payoutIds.get("Y1");
            assertThat(rows("SELECT d.origin || ':' || e.kind || ':' || e.operation_ref FROM"
                            + " reconciliation.allocation a JOIN reconciliation.match_decision d"
                            + " ON d.id = a.decision_id JOIN reconciliation.expectation e ON"
                            + " e.id = a.expectation_id JOIN reconciliation.external_item i ON"
                            + " i.id = a.external_item_id WHERE i.line_type = 'PAYOUT_RETURNED'"))
                    .as("the returned line allocated by the rematch leg to its PAYOUT_RETURN")
                    .containsExactly("REMATCH:PAYOUT_RETURN:" + returnedPayout);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope"
                            + " LIKE ?", "%merchant-payout-return:" + returnedPayout))
                    .as("the payable restored ONCE: one merchant-payout-return entry, ten"
                            + " instances' workers racing")
                    .isEqualTo(1);

            assertOneEffectPerDeliverySet(deliveries);
            assertThePlatformsActsAreCounted();
            assertTheNeedlesReachNoSink(output);
            Map<String, Long> metered = delta(meterTally(), metersBefore);
            Map<String, Long> recorded = delta(tableTally(), factsBefore);
            assertThat(metered)
                    .as("the second tally: every meter's delta equals the tables' facts, source"
                            + " by source and outcome by outcome (P7-TSK-015's design)")
                    .isEqualTo(recorded);
            assertThat(recorded.keySet())
                    .as("the tally is not vacuous: every family moved")
                    .anySatisfy(key -> assertThat(key).startsWith("received|"))
                    .anySatisfy(key -> assertThat(key).startsWith("refused|"))
                    .anySatisfy(key -> assertThat(key).startsWith("rejected|"))
                    .anySatisfy(key -> assertThat(key).startsWith("accepted|"))
                    .anySatisfy(key -> assertThat(key).startsWith("item|"))
                    .anySatisfy(key -> assertThat(key).startsWith("rematch|"))
                    .anySatisfy(key -> assertThat(key).startsWith("raised|"))
                    .anySatisfy(key -> assertThat(key).startsWith("resolution|"))
                    .anySatisfy(key -> assertThat(key).startsWith("adjustment|"))
                    .anySatisfy(key -> assertThat(key).startsWith("latency|"))
                    .anySatisfy(key -> assertThat(key).startsWith("replay|"));
            assertThat(metered.keySet())
                    .as("finapp.settlement.pull.failure did not move: every pull the storm made"
                            + " fetched its report (the tables record no failed pull to compare)")
                    .noneSatisfy(key -> assertThat(key).startsWith("pull-failure|"));
        } finally {
            movers.shutdownNow();
            instances.close();
            traffic.close();
        }
    }

    // ----------------------------------------------------------------- the crashes

    /**
     * The crash sequence on the first PSP report — each crash a real one (an {@link Error}, or the
     * connection killed), each resumed by ANOTHER instance, and after each the books read whole:
     * nothing partial survives any of them.
     */
    private void crashesOnTheFirstReport(UUID file, byte[] content) throws Exception {
        int parsedLines =
                ((SettlementFormat.Result.Parsed) SimPspCsvFormat.INSTANCE.parse(content))
                        .batch()
                        .lines()
                        .size();
        reconcile("PSP D1 received, before any leg");

        // 1. A crash after N of the report's M parsed lines are written: the instance dies
        //    mid-batch, before the rest and before the file's edge - the whole transaction gone.
        assertThat(parsedLines).as("the report has lines after the crash point")
                .isGreaterThan(CRASH_AFTER_LINES);
        AtomicInteger written = new AtomicInteger(-1);
        FileParsing crashingParser =
                new FileParsing(
                        settlementFileStore, crashingAfterLines(written), settlementFormats,
                        new FileParsing.Config(10, Duration.ofMinutes(1), Duration.ofHours(1)),
                        intakeOutcomeObserver, outboxWriter, auditWriter, idGenerator, clock,
                        settlementTransactionRunner, settlementSources);
        assertThatThrownBy(crashingParser::sweep)
                .as("the parse leg crashes mid-batch")
                .isInstanceOf(SimulatedCrash.class);
        assertThat(written.get())
                .as("the crash came after %s of the %s parsed lines were written",
                        CRASH_AFTER_LINES, parsedLines)
                .isEqualTo(CRASH_AFTER_LINES);
        assertThat(one("SELECT status || ':' || parse_failures::text || ':'"
                        + " || (next_parse_at IS NULL)::text FROM settlement.file WHERE id = ?",
                        file))
                .as("a crash is not our recorded failure: RECEIVED, no failure counted, no"
                        + " back-off - any instance re-claims it at once")
                .isEqualTo("RECEIVED:0:true");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", file)
                        + count("SELECT count(*) FROM settlement.line WHERE file_id = ?", file))
                .as("nothing of the crashed parse survives: no batch, no line")
                .isZero();
        reconcile("after the crash mid-parse");

        // 2. Another instance parses it; the sweep boundary between parse and accept.
        parsing.sweep();
        assertThat(fileStatus(file)).isEqualTo("PARSED:-");
        UUID batch = batchOf(file);
        assertThat(count("SELECT count(*) FROM settlement.line WHERE batch_id = ?", batch))
                .isEqualTo(parsedLines);
        assertThat(count("SELECT count(*) FROM reconciliation.reconciliation_batch WHERE"
                        + " batch_id = ?", batch))
                .as("between parse and accept: parsed, nothing recognised, no run")
                .isZero();
        reconcile("between parse and accept");

        // 3. A crash after the posting inside acceptance.
        AtomicReference<UUID> postedEntry = new AtomicReference<>();
        BatchAcceptance crashingAcceptor =
                new BatchAcceptance(
                        settlementFileStore, settlementBatchStore, settlementSources,
                        crashingAfterThePosting(postedEntry), postingService,
                        ledgerAccountStore, new BatchAcceptance.Config(10),
                        intakeOutcomeObserver, outboxWriter, auditWriter, idGenerator, clock,
                        settlementTransactionRunner);
        assertThatThrownBy(crashingAcceptor::sweep)
                .as("the accept leg crashes right after the recognition posts")
                .isInstanceOf(SimulatedCrash.class);
        assertThat(postedEntry.get())
                .as("the crash came after the posting call returned an entry")
                .isNotNull();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE id = ?",
                        postedEntry.get())
                        + count("SELECT count(*) FROM ledger.journal_entry WHERE"
                                + " idempotency_scope LIKE ?", "%settlement-batch:" + batch))
                .as("the crashed acceptance's recognition entry is gone with its transaction")
                .isZero();
        assertThat(one("SELECT b.status || ':' || coalesce(b.source_sequence::text, '-') || ':'"
                        + " || f.status FROM settlement.batch b JOIN settlement.file f ON f.id ="
                        + " b.file_id WHERE b.id = ?", batch))
                .as("still PARSED, its sequence number released (gapless)")
                .isEqualTo("PARSED:-:PARSED");
        assertThat(count("SELECT count(*) FROM reconciliation.reconciliation_batch WHERE"
                        + " batch_id = ?", batch)
                        + count("SELECT count(*) FROM reconciliation.expectation WHERE kind ="
                                + " 'REMITTANCE' AND operation_ref = ?", batch.toString())
                        + count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'settlement.SettlementBatchAccepted' AND target_id = ?",
                                batch.toString())
                        + count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                                + " 'settlement.SettlementBatchAccepted' AND aggregate_id = ?",
                                file))
                .as("no run, no remittance, no audit, no event: nothing partial")
                .isZero();
        reconcile("after the crash mid-acceptance");

        // 4. Another instance accepts it; the boundary between accept and the first chunk.
        acceptance.sweep();
        assertThat(one("SELECT b.status || ':' || b.source_sequence::text FROM settlement.batch b"
                        + " WHERE b.id = ?", batch))
                .as("accepted ONCE, the first PSP sequence")
                .isEqualTo("ACCEPTED:1");
        UUID run = runOf(batch);
        assertThat(one("SELECT r.cursor::text || ':' || (SELECT count(*) FROM"
                        + " reconciliation.match_decision d WHERE d.run_id = r.id)::text FROM"
                        + " reconciliation.reconciliation_batch r WHERE r.id = ?", run))
                .as("between accept and the first chunk: the run born, nothing decided")
                .isEqualTo("0:0");
        reconcile("between accept and the first chunk");

        // 5. Mid-chunk: an instance whose store kills its own connection in its SECOND chunk,
        //    after the chunk's decisions are written and before its cursor advances.
        AtomicLong killedAt = new AtomicLong(-1);
        Matching killer =
                matching(instanceClock(INSTANCES), killingInSecondChunk(killedAt));
        killer.sweep();
        assertThat(killedAt.get()).as("the second chunk was killed mid-flight").isPositive();
        long cursor = ((Number) one("SELECT cursor FROM reconciliation.reconciliation_batch"
                + " WHERE id = ?", run)).longValue();
        assertThat(cursor)
                .as("rolled back to the last committed cursor: the first chunk stands, the"
                        + " killed one (to line %s) does not", killedAt.get())
                .isPositive()
                .isLessThan(killedAt.get());
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                        + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                        + " d.run_id = ? AND i.line_no > ?", run, cursor))
                .as("no decision of the killed chunk survives")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE run_id = ?",
                        run))
                .as("every item up to the cursor decided once")
                .isEqualTo(count("SELECT count(*) FROM reconciliation.external_item WHERE"
                        + " run_id = ? AND line_no <= ?", run, cursor));
        reconcile("after the crash mid-chunk");
    }

    // ----------------------------------------------------------------- the operators

    /**
     * Two operators over HTTP: a four-eyes {@code WRITE_OFF} of the under-payment's remainder
     * (the proposer's own approval refused, the second operator's posting exactly the remainder),
     * and an {@code ACKNOWLEDGE} of the fee overcharge the second operator REJECTS.
     */
    private void resolveBy(
            StormTraffic.Staff proposer, StormTraffic.Staff approver, Map<String, Capture> captures)
            throws Exception {
        StormTraffic traffic = new StormTraffic(port, provider, authorization, merchantPayouts);
        try {
            UUID under = expectationOf(captures.get("T5").ref());
            UUID writeOffBreak =
                    (UUID) one("SELECT id FROM reconciliation.break WHERE type ="
                            + " 'AMOUNT_MISMATCH' AND expectation_id = ?", under);
            assertThat(writeOffBreak).as("the under-payment's remainder stands as a break")
                    .isNotNull();
            HttpResponse<String> proposed =
                    traffic.post(RECON + "/breaks/" + writeOffBreak + "/resolutions",
                            "{\"kind\":\"WRITE_OFF\",\"reasonCode\":\"LOSS_ACCEPTED\","
                                    + "\"narrative\":\"the PSP short-paid one sale\"}",
                            proposer.token(), "rsl-" + UUID.randomUUID());
            assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
            String writeOff = StormTraffic.field(proposed.body(), "resolutionId");
            HttpResponse<String> self =
                    traffic.post(RECON + "/resolutions/" + writeOff + "/approval", null,
                            proposer.token(), null);
            assertThat(self.statusCode()).as("four eyes: %s", self.body()).isEqualTo(409);
            HttpResponse<String> approved =
                    traffic.post(RECON + "/resolutions/" + writeOff + "/approval", null,
                            approver.token(), null);
            assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
            assertThat(one("SELECT string_agg(a.purpose || ':' || l.direction || ':'"
                            + " || l.amount_minor::text, ',' ORDER BY l.direction) FROM"
                            + " ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                            + " l.ledger_account_id WHERE l.entry_id = ?::uuid",
                            StormTraffic.field(approved.body(), "journalEntryId")))
                    .as("the write-off posts exactly the 1.00 remainder")
                    .isEqualTo("SETTLEMENT_CLEARING:CREDIT:100,RECONCILIATION_LOSSES:DEBIT:100");

            UUID feeBreak =
                    (UUID) one("SELECT id FROM reconciliation.break WHERE type ="
                            + " 'FEE_MISMATCH'");
            HttpResponse<String> acknowledged =
                    traffic.post(RECON + "/breaks/" + feeBreak + "/resolutions",
                            "{\"kind\":\"ACKNOWLEDGE\",\"reasonCode\":\"FEE_ACCEPTED_AS_CHARGED\","
                                    + "\"narrative\":\"accept the overcharge\"}",
                            proposer.token(), "rsl-" + UUID.randomUUID());
            assertThat(acknowledged.statusCode()).as(acknowledged.body()).isEqualTo(201);
            HttpResponse<String> rejected =
                    traffic.post(RECON + "/resolutions/"
                                    + StormTraffic.field(acknowledged.body(), "resolutionId")
                                    + "/rejection",
                            "{\"reason\":\"recover the fee from the PSP instead\"}",
                            approver.token(), null);
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
            assertThat(StormTraffic.field(rejected.body(), "status")).isEqualTo("REJECTED");
        } finally {
            traffic.close();
        }
        reconcile("after the operators' resolutions");
    }

    /**
     * The unknown line was owned by the production path itself, in its run's chunk: PARKED with
     * its UNKNOWN_EXTERNAL break and its suspense item, and it never waited on a grace window -
     * nothing here moves a stored window (the Phase 8 -> 9 transition's REC-2).
     */
    private static void assertTheUnknownLineWasOwnedAtRunTime(UUID file, String unknownRef)
            throws SQLException {
        UUID item =
                (UUID) one("SELECT i.id FROM reconciliation.external_item i JOIN"
                        + " settlement.line_reference r ON r.line_id = i.settlement_line_id"
                        + " JOIN settlement.line l ON l.id = i.settlement_line_id WHERE"
                        + " l.file_id = ? AND r.value = ?", file, unknownRef);
        assertThat(one("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("the unknown line owned at run time, never left waiting")
                .isEqualTo("PARKED");
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE id = ?", item))
                .as("no grace window: no rule set can ever allocate an OTHER_IN")
                .isNull();
        assertThat(one("SELECT b.type || '/' || b.cause FROM reconciliation.break b WHERE"
                + " b.external_item_id = ?", item))
                .isEqualTo("UNKNOWN_EXTERNAL/GRACE_EXPIRED");
    }

    /**
     * The dropped line's expectation aged — {@code expected_by} is frozen by trigger for every
     * writer, so it is moved by the platform's root with that ONE trigger disabled for the one
     * {@code UPDATE} (the register-wipe precedent: history's shape, never a production path) —
     * then ten ageing sweepers at once: exactly one {@code MISSING_EXTERNAL}.
     */
    private void ageTheDroppedCapture(Capture dropped, LocalDate today) throws Exception {
        UUID expectation = expectationOf(dropped.ref());
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            try {
                execute(root, "ALTER TABLE reconciliation.expectation DISABLE TRIGGER"
                        + " expectation_permits_only_machine_edges");
                execute(root, "UPDATE reconciliation.expectation SET expected_by = ? WHERE"
                        + " id = ? AND status = 'OPEN'", today.minusDays(5), expectation);
                execute(root, "ALTER TABLE reconciliation.expectation ENABLE TRIGGER"
                        + " expectation_permits_only_machine_edges");
                root.commit();
            } catch (SQLException | RuntimeException failure) {
                // One transaction: rolling back re-enables the trigger with the UPDATE undone,
                // and the original error stands - never masked by a failing ENABLE.
                try {
                    root.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
        ExecutorService sweepers = Executors.newFixedThreadPool(INSTANCES);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ReconciliationSweep.SweepResult>> swept = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
                swept.add(sweepers.submit(() -> {
                    start.await();
                    return reconciliationSweep.sweep();
                }));
            }
            start.countDown();
            int aged = 0;
            for (Future<ReconciliationSweep.SweepResult> result : swept) {
                aged += result.get(2, TimeUnit.MINUTES).aged();
            }
            assertThat(aged).as("ten ageing sweepers age the expectation once").isEqualTo(1);
        } finally {
            sweepers.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ? AND"
                        + " type = 'MISSING_EXTERNAL'", expectation))
                .as("exactly one MISSING_EXTERNAL under ten sweepers")
                .isEqualTo(1);
        reconcile("the dropped capture aged by ten sweepers");
    }

    /** Every run replayed over HTTP under its pinned version: IDENTICAL, each. */
    private void replayEveryRun(StormTraffic.Staff investigator) throws Exception {
        StormTraffic traffic = new StormTraffic(port, provider, authorization, merchantPayouts);
        try {
            List<String> runs = rows("SELECT id::text FROM reconciliation.reconciliation_batch"
                    + " ORDER BY created_at");
            assertThat((long) runs.size())
                    .as("every run replayed: one per accepted batch, plus any batch-less run")
                    .isEqualTo(count("SELECT count(*) FROM settlement.batch WHERE status ="
                                    + " 'ACCEPTED'")
                            + count("SELECT count(*) FROM reconciliation.reconciliation_batch"
                                    + " WHERE batch_id IS NULL"));
            assertThat(runs)
                    .as("the storm's twelve accepted batches: three PSP reports, three scheme"
                            + " cycles, two payout reports, four bank statements - and no"
                            + " reprocess run")
                    .hasSize(12);
            for (String run : runs) {
                HttpResponse<String> replayed =
                        traffic.post(RECON + "/runs/" + run + "/replay", null,
                                investigator.token(), null);
                assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
                assertThat(StormTraffic.field(replayed.body(), "verdict"))
                        .as("run %s replays exactly from its stored snapshots: %s", run,
                                replayed.body())
                        .isEqualTo("IDENTICAL");
                assertThat(replayed.body()).contains("\"divergences\":0");
            }
        } finally {
            traffic.close();
        }
    }

    // ----------------------------------------------------------------- every round

    /**
     * One reading in ONE {@code REPEATABLE READ} snapshot — the proofs, the census, the
     * allocations and the trial balance, all on that one connection: each verdict asserted exactly,
     * every reading collected before any fails (soft), so a probe's failure names EVERY reading
     * that caught it.
     */
    private void reconcile(String when) throws SQLException {
        SoftAssertions softly = new SoftAssertions();
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                PositionProof.Report report = proof.sweep(snapshot);
                softly.assertThat(report.verdicts())
                        .as("%s: every clearing position explained (INV-REC-06)", when)
                        .allSatisfy(verdict -> assertThat(verdict.explained())
                                .as("%s: %s %s balance %s = remainders %s - items %s", when,
                                        verdict.purpose(), verdict.currency(),
                                        verdict.ledgerBalance(), verdict.openRemainders(),
                                        verdict.openItems())
                                .isTrue());
                softly.assertThat(report.suspenseVerdicts())
                        .as("%s: every suspense identity explained", when)
                        .allSatisfy(verdict -> assertThat(verdict.explained())
                                .as("%s: suspense %s balance %s = CR %s - DR %s + unadopted %s",
                                        when, verdict.currency(), verdict.ledgerBalance(),
                                        verdict.creditRemainders(), verdict.debitRemainders(),
                                        verdict.unadoptedParkings())
                                .isTrue());
                softly.assertThat(report.suspenseUnowned())
                        .as("%s: every open suspense value owned by a break (INV-REC-09,"
                                + " suspense.unowned)", when)
                        .isZero();
                softly.assertThat(report.unattributedByPurpose())
                        .as("%s: completeness - every line on a reconciled position known"
                                + " (INV-SET-02, line.unattributed)", when)
                        .allSatisfy((purpose, unknown) -> assertThat(unknown)
                                .as("%s: unattributed %s lines", when, purpose)
                                .isZero());
                boolean eurGap = exists(snapshot, gapSql("EUR"));
                boolean usdStatement = exists(snapshot, statementSql("USD"));
                for (PositionProof.CashVerdict cash : report.cashVerdicts()) {
                    boolean expected =
                            cash.currency().equals(EUR)
                                    ? !eurGap
                                    : !cash.currency().equals(USD) || !usdStatement;
                    softly.assertThat(cash.explained())
                            .as("%s: cash %s - ledger %s against the chain's closing %s"
                                            + " (unbroken %s); EUR gap open %s, USD's non-zero"
                                            + " opening accepted %s - only those fail, and"
                                            + " they must", when, cash.currency(),
                                    cash.ledgerBalance(), cash.chainClosing(), cash.unbroken(),
                                    eurGap, usdStatement)
                            .isEqualTo(expected);
                }
                assertTheCensusIsWithinTheOracle(softly, snapshot, when);
                assertNoOverAllocation(softly, snapshot, when);
                softly.assertThat(trialBalance.sweep(snapshot).outOfBalance())
                        .as("%s: the trial balance, zero per currency (INV-ACC-01)", when)
                        .isEmpty();
            } finally {
                snapshot.rollback();
            }
        }
        softly.assertAll();
    }

    /** Never a break the oracle does not name, by type and cause (statuses move mid-storm). */
    private void assertTheCensusIsWithinTheOracle(
            SoftAssertions softly, Connection snapshot, String when) throws SQLException {
        Map<String, Long> allowed = new TreeMap<>();
        for (String expected : oracle) {
            allowed.merge(expected.substring(0, expected.lastIndexOf(':')), 1L, Long::sum);
        }
        for (String[] row : pairs(snapshot, "SELECT type || ':' || cause, count(*)::text FROM"
                + " reconciliation.break GROUP BY 1")) {
            softly.assertThat(Long.parseLong(row[1]))
                    .as("%s: break %s raised %s time(s); the oracle names it %s time(s) - each"
                            + " fault produces exactly its break and no other", when, row[0],
                            row[1], allowed.getOrDefault(row[0], 0L))
                    .isLessThanOrEqualTo(allowed.getOrDefault(row[0], 0L));
        }
    }

    /**
     * Every item in at most one positive allocation per expectation, and no expectation nor item
     * allocated past its amount — counted from the allocation ROWS, not the stored sums.
     */
    private static void assertNoOverAllocation(
            SoftAssertions softly, Connection snapshot, String when) throws SQLException {
        softly.assertThat(pairs(snapshot, "SELECT external_item_id::text,"
                        + " expectation_id::text FROM reconciliation.allocation WHERE"
                        + " reverses_allocation_id IS NULL AND amount_minor > 0 GROUP BY 1, 2"
                        + " HAVING count(*) > 1"))
                .as("%s: an item holds at most one positive allocation per expectation", when)
                .isEmpty();
        softly.assertThat(pairs(snapshot, "SELECT e.id::text, e.allocated_minor::text FROM"
                        + " reconciliation.expectation e WHERE e.allocated_minor <> (SELECT"
                        + " coalesce(sum(CASE WHEN a.reverses_allocation_id IS NULL THEN"
                        + " a.amount_minor ELSE -a.amount_minor END), 0) FROM"
                        + " reconciliation.allocation a WHERE a.expectation_id = e.id)"))
                .as("%s: every expectation's stored allocated sum is its allocation rows'", when)
                .isEmpty();
        softly.assertThat(pairs(snapshot, "SELECT e.id::text, e.amount_minor::text FROM"
                + " reconciliation.expectation e WHERE (SELECT coalesce(sum(CASE WHEN"
                + " a.reverses_allocation_id IS NULL THEN a.amount_minor ELSE -a.amount_minor"
                + " END), 0) FROM reconciliation.allocation a WHERE a.expectation_id = e.id)"
                + " + e.resolved_minor > e.amount_minor"))
                .as("%s: no expectation allocated past its amount (counted from the rows)", when)
                .isEmpty();
        softly.assertThat(pairs(snapshot, "SELECT i.id::text, i.amount_minor::text FROM"
                + " reconciliation.external_item i WHERE (SELECT coalesce(sum(CASE WHEN"
                + " a.reverses_allocation_id IS NULL THEN a.amount_minor ELSE -a.amount_minor"
                + " END), 0) FROM reconciliation.allocation a WHERE a.external_item_id = i.id)"
                + " + i.parked_minor + i.offset_minor > i.amount_minor"))
                .as("%s: no item allocated and parked past its amount (counted from the rows)",
                        when)
                .isEmpty();
    }

    // ----------------------------------------------------------------- at rest

    /**
     * The census EQUAL to the oracle — type, cause and status — and each break on its fault's
     * own subject.
     */
    private void assertTheCensusEqualsTheOracle(
            Map<String, Capture> captures,
            Map<String, String> schemeRefs,
            String unknownRef,
            Deliveries deliveries)
            throws SQLException {
        List<String> census =
                rows("SELECT type || ':' || cause || ':' || status FROM reconciliation.break");
        assertThat(census)
                .as("at rest: the census is the oracle - each fault exactly its break, no other")
                .containsExactlyInAnyOrderElementsOf(oracle);
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE event_type ="
                        + " 'SEVERITY_ESCALATED'"))
                .as("no break escalated inside the storm (the raise's severity stands)")
                .isZero();

        assertThat(subjectOf("DUPLICATE_EXTERNAL", "REPEATED_FINGERPRINT"))
                .as("the duplicate parked on the REPEATED T4 line")
                .contains(captures.get("T4").ref());
        assertThat(one("SELECT i.line_no > (SELECT min(j.line_no) FROM"
                        + " reconciliation.external_item j WHERE j.run_id = i.run_id AND"
                        + " j.canonical_fingerprint = i.canonical_fingerprint) FROM"
                        + " reconciliation.break b JOIN reconciliation.external_item i ON i.id ="
                        + " b.external_item_id WHERE b.type = 'DUPLICATE_EXTERNAL'"))
                .as("the LATER of the two lines parked; the first allocated")
                .isEqualTo(true);
        assertThat(one("SELECT b.expectation_id = ? FROM reconciliation.break b WHERE b.type ="
                        + " 'MISSING_EXTERNAL'", expectationOf(captures.get("T3").ref())))
                .as("the dropped capture's expectation went missing")
                .isEqualTo(true);
        assertThat(one("SELECT b.status || ':' || r.kind || ':' || r.status FROM"
                        + " reconciliation.break b JOIN reconciliation.resolution r ON"
                        + " r.break_id = b.id WHERE b.type = 'AMOUNT_MISMATCH' AND"
                        + " b.expectation_id = ?", expectationOf(captures.get("T5").ref())))
                .as("the under-payment closed by the approved write-off")
                .isEqualTo("RESOLVED:WRITE_OFF:APPROVED");
        assertThat(one("SELECT b.status || ':' || r.kind FROM reconciliation.break b JOIN"
                        + " reconciliation.resolution r ON r.break_id = b.id WHERE b.type ="
                        + " 'AMOUNT_MISMATCH' AND b.external_item_id IS NOT NULL"))
                .as("the over-payment's parked excess offset by the correction: EVIDENCED")
                .isEqualTo("RESOLVED:EVIDENCED");
        assertThat(subjectOf("AMOUNT_MISMATCH", "AMOUNT_DIFFERS"))
                .contains(captures.get("T5").ref())
                .contains(captures.get("T6").ref());
        assertThat(one("SELECT count(*) FILTER (WHERE status = 'OFFSET')::text || ':'"
                        + " || count(*) FILTER (WHERE line_type = 'COUNTERPARTY_ADJUSTMENT')::text"
                        + " FROM reconciliation.external_item WHERE line_type ="
                        + " 'COUNTERPARTY_ADJUSTMENT'"))
                .as("the correction offset, once")
                .isEqualTo("1:1");
        assertThat(subjectOf("FEE_MISMATCH", "FEE_BEYOND_TOLERANCE"))
                .as("the fee break on T7's fee line")
                .contains(captures.get("T7").ref());
        assertThat(one("SELECT string_agg(r.kind || ':' || r.status, ',') FROM"
                        + " reconciliation.resolution r JOIN reconciliation.break b ON b.id ="
                        + " r.break_id WHERE b.type = 'FEE_MISMATCH'"))
                .as("the acknowledgement was rejected by the second operator")
                .isEqualTo("ACKNOWLEDGE:REJECTED");
        assertThat(subjectOf("TIMING_DIFFERENCE", "LATE_MATCH"))
                .contains(captures.get("T8").ref());
        assertThat(subjectOf("UNKNOWN_EXTERNAL", "GRACE_EXPIRED")).contains(unknownRef);
        assertThat(subjectOf("CURRENCY_MISMATCH", "CURRENCY_DIFFERS"))
                .contains(captures.get("T9").ref());
        assertThat(one("SELECT status || ':' || allocated_minor::text FROM"
                        + " reconciliation.expectation WHERE id = ?",
                        expectationOf(captures.get("T9").ref())))
                .as("a wrong currency is never allocated nor converted (INV-MON-04)")
                .isEqualTo("OPEN:0");
        assertThat(subjectOf("TIMING_DIFFERENCE", "CYCLE_MISMATCH"))
                .contains(schemeRefs.get("P4"));
        assertThat(one("SELECT b.run_id = ? FROM reconciliation.break b WHERE b.cause ="
                        + " 'STATEMENT_GAP'", runOf(batchOf(deliveries.file("EUR statement 3")))))
                .as("the gap raised on the statement delivered before its predecessor")
                .isEqualTo(true);
        assertThat(one("SELECT r.kind || ':' || r.narrative FROM reconciliation.resolution r JOIN"
                        + " reconciliation.break b ON b.id = r.break_id WHERE b.cause ="
                        + " 'STATEMENT_GAP'"))
                .as("closed EVIDENCED, naming the filling statement")
                .isEqualTo("EVIDENCED:statement=" + batchOf(deliveries.file("EUR statement 2")));
        assertThat(one("SELECT b.run_id = ? FROM reconciliation.break b WHERE b.cause ="
                        + " 'OPENING_BALANCE'", runOf(batchOf(deliveries.file("USD statement 1")))))
                .as("the non-zero opening on USD's first statement")
                .isEqualTo(true);
        assertThat(cashOf(USD).explained())
                .as("USD's cash fails, loudly and truthfully - never posted around")
                .isFalse();
        assertThat(one("SELECT count(*) FILTER (WHERE status = 'MATCHED')::text || ':'"
                        + " || count(*)::text FROM reconciliation.external_item WHERE line_type ="
                        + " 'PAYOUT_RETURNED'"))
                .as("the payout return allocated by the rematch leg after the worker applied it:"
                        + " %s", rows("SELECT i.status || ' decisions=' || (SELECT"
                        + " string_agg(d.origin || ':' || d.outcome, ',') FROM"
                        + " reconciliation.match_decision d WHERE d.external_item_id = i.id)"
                        + " || ' expectations=' || (SELECT string_agg(e.kind || ':' || e.status,"
                        + " ',') FROM reconciliation.expectation e WHERE e.kind ="
                        + " 'PAYOUT_RETURN') FROM reconciliation.external_item i WHERE"
                        + " i.line_type = 'PAYOUT_RETURNED'"))
                .isEqualTo("1:1");
    }

    /** One file, batch, run and recognition entry per delivery set; a receipt per delivery. */
    /**
     * F2 at rest: every ledger account any journal line reaches - the clearing positions the
     * recognitions, parks and unparks move, {@code PROCESSING_COSTS}, the suspense, the cash, and
     * the losses the resolutions post - has its {@code ledger.account_balance}
     * projection equal to the replay-from-zero derivation, with nothing in flight. The Phase 8
     * purposes the storm posts to are required among the verified, so the sweep is never
     * vacuous.
     */
    private static void assertTheProjectionIsCleanAtRest() throws SQLException {
        com.finapp.ledger.ProjectionVerification verification =
                new com.finapp.ledger.ProjectionVerification(
                        new com.finapp.ledger.JdbcBalanceDerivation());
        List<String> posted =
                rows("SELECT DISTINCT a.id::text || '|' || a.purpose FROM ledger.ledger_account a"
                        + " JOIN ledger.journal_line l ON l.ledger_account_id = a.id");
        java.util.Set<String> purposes = new java.util.TreeSet<>();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (String account : posted) {
                String[] parts = account.split("\\|");
                purposes.add(parts[1]);
                assertThat(verification.verdictOf(
                                app, com.finapp.ledger.LedgerAccountId.of(
                                        UUID.fromString(parts[0]))))
                        .as("F2 at rest: %s %s's projection equals replay-from-zero", parts[1],
                                parts[0])
                        .isEqualTo(com.finapp.ledger.ProjectionVerification.Verdict.CLEAN);
            }
            com.finapp.ledger.ProjectionVerification.Report swept = verification.verify(app);
            assertThat(swept.drifting())
                    .as("F2 at rest: no drifting projection anywhere %s", swept.driftingAccounts())
                    .isZero();
            assertThat(swept.inFlight()).as("F2 at rest: nothing in flight").isZero();
            app.rollback();
        }
        // RECONCILIATION_GAINS is absent by the storm's own traffic: no resolution it drives
        // recognises a gain (the T5 write-off is its one loss), so no line reaches that account.
        assertThat(purposes)
                .as("F2: the sweep reached every position the storm's Phase 8 postings move"
                        + " (verified: %s)", purposes)
                .contains("SETTLEMENT_CLEARING", "INSTANT_CLEARING", "PAYOUT_CLEARING",
                        "CASH_AT_BANK", "PROCESSING_COSTS", "SUSPENSE_UNMATCHED",
                        "RECONCILIATION_LOSSES");
    }

    private static void assertOneEffectPerDeliverySet(Deliveries deliveries) throws Exception {
        for (Delivery delivery : deliveries.delivered()) {
            String sha = sha256Hex(delivery.content());
            if (delivery.fate() == Fate.REFUSED) {
                assertThat(count("SELECT count(*) FROM settlement.file WHERE content_sha256 ="
                                + " decode(?, 'hex')", sha))
                        .as("%s: refused at the door - never stored", delivery.name())
                        .isZero();
                assertThat(count("SELECT count(*) FROM settlement.refused_delivery WHERE"
                                + " content_sha256 = decode(?, 'hex')", sha))
                        .as("%s: one refusal per delivery - the upload and the herd's ONE pull,"
                                + " the nine paced pulls recording nothing", delivery.name())
                        .isEqualTo(2);
                continue;
            }
            assertThat(count("SELECT count(*) FROM settlement.file WHERE content_sha256 ="
                            + " decode(?, 'hex')", sha))
                    .as("%s: one file per content address", delivery.name())
                    .isEqualTo(1);
            UUID file = deliveries.file(delivery.name());
            assertThat(one("SELECT string_agg(outcome || ':' || channel, ',' ORDER BY"
                            + " received_at, outcome) FROM settlement.file_receipt WHERE"
                            + " file_id = ?", file))
                    .as("%s: two deliveries, two receipts - the herd's nine losers recorded"
                            + " nothing", delivery.name())
                    .isEqualTo(delivery.uploadFirst()
                            ? "NEW:UPLOAD,DUPLICATE:PULL"
                            : "NEW:PULL,DUPLICATE:UPLOAD");
            if (delivery.fate() == Fate.REJECTED) {
                assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", file))
                        .as("%s: rejected whole - no batch", delivery.name())
                        .isZero();
                continue;
            }
            UUID batch = batchOf(file);
            assertThat((String) one("SELECT b.status || ':' || (SELECT count(*) FROM"
                            + " reconciliation.reconciliation_batch r WHERE r.batch_id = b.id)"
                            + " || ':' || (SELECT count(*) FROM ledger.journal_entry e WHERE"
                            + " e.idempotency_scope LIKE '%settlement-batch:' || b.id::text)"
                            + " || ':' || b.posting_omitted::text"
                            + " FROM settlement.batch b WHERE b.id = ?", batch))
                    .as("%s: accepted once - one run, and exactly one recognition entry for a"
                            + " report with fees or a statement that moves cash; the GBP report,"
                            + " which carries no fee, omits its posting", delivery.name())
                    .isEqualTo(delivery.name().equals("PSP GBP")
                            ? "ACCEPTED:1:0:true"
                            : "ACCEPTED:1:1:false");
        }
    }

    /**
     * The platform's acts, counted: one {@code settlement.SettlementBatchAccepted} audit and event
     * per accepted batch, one {@code reconciliation.RunCompleted} audit and
     * {@code ReconciliationRunCompleted} event per run, one {@code reconciliation.BreakRaised}
     * audit and {@code ReconciliationBreakRaised} event per break — the losers recording nothing.
     */
    private static void assertThePlatformsActsAreCounted() throws SQLException {
        // The acceptance event's aggregate is the batch's FILE (SettlementFileEvents).
        assertThat(pairs(platformActsSql("settlement.batch", "status = 'ACCEPTED'",
                        "settlement.SettlementBatchAccepted",
                        "settlement.SettlementBatchAccepted", "file_id")))
                .as("one acceptance audit and one acceptance event per accepted batch")
                .isEmpty();
        assertThat(pairs(platformActsSql("reconciliation.reconciliation_batch",
                        "status = 'COMPLETED'", "reconciliation.RunCompleted",
                        "reconciliation.ReconciliationRunCompleted", "id")))
                .as("one completion audit and one completion event per run")
                .isEmpty();
        assertThat(pairs(platformActsSql("reconciliation.break", "true",
                        "reconciliation.BreakRaised", "reconciliation.ReconciliationBreakRaised",
                        "id")))
                .as("one raise audit and one raise event per break")
                .isEmpty();
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE status = 'ACCEPTED'"))
                .as("the acts were counted over every accepted batch")
                .isPositive();
    }

    /** The subjects (of {@code table} rows matching {@code where}) whose acts are not exactly 1:1. */
    private static String platformActsSql(
            String table,
            String where,
            String auditOperation,
            String eventType,
            String aggregateColumn) {
        String audits = "(SELECT count(*) FROM platform.audit_record a WHERE a.operation = '"
                + auditOperation + "' AND a.target_id = t.id::text)";
        String events = "(SELECT count(*) FROM platform.outbox_event o WHERE o.event_type = '"
                + eventType + "' AND o.aggregate_id = t." + aggregateColumn + ")";
        return "SELECT t.id::text, " + audits + "::text || ':' || " + events + "::text FROM "
                + table + " t WHERE " + where + " AND (" + audits + " <> 1 OR " + events
                + " <> 1)";
    }

    /** The PAN and IBAN refused with metadata only: no chunk holds them, no log echoed them. */
    private static void assertTheNeedlesReachNoSink(CapturedOutput output) throws SQLException {
        // The weight is carried by NEVER STORED: a refused delivery's content address is in no
        // file row, so no chunk of it exists at all (a search of ciphertext for plaintext would
        // prove nothing, and is not made).
        assertThat(count("SELECT count(*) FROM settlement.file f WHERE f.content_sha256 IN"
                        + " (SELECT d.content_sha256 FROM settlement.refused_delivery d)"))
                .as("no refused delivery's bytes were ever stored as a file")
                .isZero();
        for (String needle : List.of(PAN_NEEDLE, PAN_NEEDLE.replace(" ", ""), IBAN_NEEDLE)) {
            assertThat(output.getAll())
                    .as("no captured log carries the needle '%s'", needle)
                    .doesNotContain(needle);
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE"
                            + " coalesce(change_summary, '') || coalesce(reason, '') LIKE ?",
                            "%" + needle + "%"))
                    .isZero();
        }
        assertThat(rows("SELECT reason || ':' || channel FROM settlement.refused_delivery ORDER BY"
                        + " reason, channel"))
                .as("each needle refused once per delivery, as metadata")
                .containsExactly("ACCOUNT_IDENTIFIER:PULL", "ACCOUNT_IDENTIFIER:UPLOAD",
                        "PRIMARY_ACCOUNT_NUMBER:PULL", "PRIMARY_ACCOUNT_NUMBER:UPLOAD");
    }

    /**
     * Nothing failed that the storm did not inject: the one chunk it killed is the only contained
     * chunk failure in the log; no parse, accept, grace, rematch, reprocess or payout return
     * failed, no item was contained as poisoned, and no leg or row of time's observers failed.
     */
    private static void assertNothingFailedButTheInjected(CapturedOutput output) {
        String log = output.getAll();
        assertThat(occurrences(log, "A matching chunk failed and rolled back"))
                .as("the one injected mid-chunk kill is the only contained chunk failure: %s",
                        linesWith(log, "A matching chunk failed and rolled back"))
                .isEqualTo(1);
        for (String failure : List.of("A matching chunk failed before claiming",
                "parse leg could not process", "accept leg could not process",
                "leg's batch failed and rolled back", "A rematch candidate was skipped",
                "A poisoned item was contained", "A reprocess chunk failed",
                "A payout return failed", "leg failed: " /* ReconciliationSweep's "The {} leg
                failed" */, "An ageing row failed", "An escalation row failed",
                "A run-block row failed", "could not be counted")) {
            assertThat(occurrences(log, failure))
                    .as("no contained failure the storm did not inject: %s",
                            linesWith(log, failure))
                    .isZero();
        }
    }

    // ----------------------------------------------------------------- the second tally

    /** Every meter the tally reads, keyed {@code family|tag|tag}. */
    private Map<String, Long> meterTally() {
        Map<String, Long> tally = new TreeMap<>();
        counters(tally, "received", SettlementMeters.RECEIVED, "source", "outcome");
        counters(tally, "refused", SettlementMeters.REFUSED, "source", "outcome");
        counters(tally, "rejected", SettlementMeters.REJECTED, "source", "outcome");
        counters(tally, "accepted", SettlementMeters.BATCH_ACCEPTED, "source");
        counters(tally, "item", ReconciliationOutcomeMeters.ITEM, "source", "outcome");
        counters(tally, "rematch", ReconciliationOutcomeMeters.REMATCH, "source", "outcome");
        counters(tally, "raised", ReconciliationOutcomeMeters.BREAK_RAISED, "type", "severity");
        counters(tally, "resolution", ReconciliationOutcomeMeters.RESOLUTION, "type", "outcome");
        counters(tally, "replay", ReconciliationReplayMeters.REPLAY, "outcome");
        counters(tally, "adjustment", ReconciliationOutcomeMeters.ADJUSTMENT, "type");
        // A timer's COUNT - one recording per completed batch run - never its durations.
        for (Timer timer : meterRegistry.find(ReconciliationOutcomeMeters.RUN_LATENCY).timers()) {
            tally.merge("latency|" + timer.getId().getTag("source"), timer.count(), Long::sum);
        }
        // Expected never to move: every pull fetched its report (no table side to compare).
        counters(tally, "pull-failure", SettlementPullMetrics.PULL_FAILURE, "source", "outcome");
        return tally;
    }

    private void counters(Map<String, Long> tally, String family, String name, String... tags) {
        for (Counter counter : meterRegistry.find(name).counters()) {
            StringBuilder key = new StringBuilder(family);
            for (String tag : tags) {
                key.append('|').append(counter.getId().getTag(tag));
            }
            tally.merge(key.toString(), (long) counter.count(), Long::sum);
        }
    }

    /** The same facts the meters count, read from the tables in the meters' own terms. */
    private static Map<String, Long> tableTally() throws SQLException {
        Map<String, Long> tally = new TreeMap<>();
        facts(tally, "received", "SELECT s.code || '|' || lower(r.outcome), count(*) FROM"
                + " settlement.file_receipt r JOIN settlement.file f ON f.id = r.file_id JOIN"
                + " settlement.source s ON s.id = f.source_id GROUP BY 1");
        facts(tally, "refused", "SELECT s.code || '|' || lower(d.reason), count(*) FROM"
                + " settlement.refused_delivery d JOIN settlement.source s ON s.id = d.source_id"
                + " GROUP BY 1");
        facts(tally, "rejected", "SELECT s.code || '|' || lower(f.rejection_code), count(*) FROM"
                + " settlement.file f JOIN settlement.source s ON s.id = f.source_id WHERE"
                + " f.status = 'REJECTED' GROUP BY 1");
        facts(tally, "accepted", "SELECT s.code, count(*) FROM settlement.batch b JOIN"
                + " settlement.source s ON s.id = b.source_id WHERE b.status = 'ACCEPTED'"
                + " GROUP BY 1");
        // A completed batch run's FIRST decisions (origin RUN): the match rate counts each item's
        // decision at the run's completion, never a later grace re-judgement of the same item -
        // told apart by what each decision JUDGED (the run leg judges a PENDING item, the grace
        // leg an UNMATCHED one, both stored by V012), never by comparing two instances' clocks.
        facts(tally, "item", "SELECT s.code || '|' || lower(d.outcome), count(*) FROM"
                + " reconciliation.match_decision d JOIN reconciliation.reconciliation_batch r ON"
                + " r.id = d.run_id JOIN settlement.source s ON s.id = r.source_id WHERE d.origin"
                + " = 'RUN' AND d.judged_status = 'PENDING' AND r.batch_id IS NOT NULL AND"
                + " r.status = 'COMPLETED' GROUP BY 1");
        // The adjustments a resolution's approval posted, by kind: an approved posting
        // resolution's journal entry.
        facts(tally, "adjustment", "SELECT lower(kind), count(*) FROM reconciliation.resolution"
                + " WHERE status = 'APPROVED' AND kind <> 'EVIDENCED' AND journal_entry_id IS NOT"
                + " NULL GROUP BY 1");
        // The run latency's recordings: one per completed BATCH run, by source.
        facts(tally, "latency", "SELECT s.code, count(*) FROM"
                + " reconciliation.reconciliation_batch r JOIN settlement.source s ON s.id ="
                + " r.source_id WHERE r.batch_id IS NOT NULL AND r.status = 'COMPLETED'"
                + " GROUP BY 1");
        facts(tally, "rematch", "SELECT s.code || '|' || lower(d.outcome), count(*) FROM"
                + " reconciliation.match_decision d JOIN reconciliation.external_item i ON i.id ="
                + " d.external_item_id JOIN settlement.source s ON s.id = i.source_id WHERE"
                + " d.origin = 'REMATCH' GROUP BY 1");
        facts(tally, "raised", "SELECT lower(type) || '|' || lower(severity), count(*) FROM"
                + " reconciliation.break GROUP BY 1");
        facts(tally, "resolution", "SELECT lower(kind) || '|' || CASE WHEN kind = 'EVIDENCED'"
                + " THEN 'evidenced' ELSE lower(status) END, count(*) FROM"
                + " reconciliation.resolution WHERE status <> 'PROPOSED' GROUP BY 1");
        facts(tally, "replay", "SELECT lower(verdict), count(*) FROM reconciliation.run_replay"
                + " GROUP BY 1");
        return tally;
    }

    private static void facts(Map<String, Long> tally, String family, String sql)
            throws SQLException {
        for (String[] row : pairs(sql)) {
            tally.merge(family + "|" + row[0], Long.parseLong(row[1]), Long::sum);
        }
    }

    /** What moved between two tallies - the keys that did not move left out. */
    private static Map<String, Long> delta(Map<String, Long> after, Map<String, Long> before) {
        Map<String, Long> moved = new TreeMap<>();
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            long change = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
            if (change != 0) {
                moved.put(entry.getKey(), change);
            }
        }
        return moved;
    }

    // ----------------------------------------------------------------- the instances

    /**
     * Ten matcher instances, each with its own clock; every round all ten sweep at once, each
     * also driving the parse and accept legs, the payout return worker and time's observers.
     */
    private final class Instances implements AutoCloseable {

        private final List<Matching> matchers = new ArrayList<>();
        private final ExecutorService pool;

        Instances(int count) throws SQLException {
            for (int i = 0; i < count; i++) {
                matchers.add(matching(instanceClock(i), matchingStore));
            }
            pool = Executors.newFixedThreadPool(count);
        }

        /** One round: every instance's pass at once; whether any did work. */
        boolean round() throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> passes = new ArrayList<>();
            for (Matching matcher : matchers) {
                passes.add(pool.submit(() -> {
                    start.await();
                    return pass(matcher);
                }));
            }
            start.countDown();
            boolean worked = false;
            for (Future<Boolean> pass : passes) {
                worked |= pass.get(3, TimeUnit.MINUTES);
            }
            return worked;
        }

        /**
         * Rounds, each reconciled, while {@code work} runs beside them; then drained. The cap
         * counts rounds that DID work, never idle ones: an idle round waits on the work instead
         * of spinning, so how fast idle rounds run cannot exhaust it.
         */
        void runWhile(String when, Callable<Void> work) throws Exception {
            ExecutorService beside = Executors.newSingleThreadExecutor();
            try {
                Future<Void> running = beside.submit(work);
                int round = 0;
                int productive = 0;
                while (!running.isDone()) {
                    round++;
                    boolean worked = round();
                    reconcile(when + ", round " + round + " (work in flight)");
                    if (worked) {
                        if (++productive > MAX_ROUNDS) {
                            throw new AssertionError(when + ": " + MAX_ROUNDS
                                    + " rounds of work and the work never finished");
                        }
                    } else {
                        try {
                            running.get(200, TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.TimeoutException stillRunning) {
                            // Nothing to match yet: wait on the work, then round again.
                        }
                    }
                }
                running.get();
            } finally {
                beside.shutdownNow();
            }
            drain(when);
        }

        /**
         * Rounds until two in a row find nothing to do and nothing is left in flight - capped by
         * rounds that did work, and by rounds that did none while something was still in flight
         * (a stuck run or file is a failure, never a wait).
         */
        void drain(String when) throws Exception {
            int idle = 0;
            int productive = 0;
            int stuck = 0;
            for (int round = 1; ; round++) {
                boolean worked = round();
                reconcile(when + ", drain round " + round);
                boolean quiet = quiet();
                if (!worked && quiet) {
                    if (++idle >= 2) {
                        return;
                    }
                    continue;
                }
                idle = 0;
                if (worked ? ++productive > MAX_ROUNDS : ++stuck > STUCK_ROUNDS) {
                    break;
                }
            }
            throw new AssertionError(when + ": never came to rest - " + rows("SELECT 'file '"
                    + " || id || ' ' || status FROM settlement.file WHERE status IN ('RECEIVED',"
                    + " 'PARSED') UNION ALL SELECT 'run ' || id || ' ' || status FROM"
                    + " reconciliation.reconciliation_batch WHERE status <> 'COMPLETED'"));
        }

        @Override
        public void close() {
            pool.shutdownNow();
        }
    }

    /** One instance's pass; anything it did counts as work. A blocked run fails the storm. */
    private boolean pass(Matching matcher) {
        boolean worked = parsing.sweep().candidates() > 0;
        worked |= acceptance.sweep().candidates() > 0;
        Matching.SweepResult matched = matcher.sweep();
        assertThat(matched.blockedRuns()).as("no run blocks in the storm").isZero();
        worked |= matched.chunks() + matched.decided() + matched.completedRuns()
                        + matched.graced() + matched.rematched() + matched.reprocessed()
                > 0;
        worked |= payoutReturnSweep.sweep().applied() > 0;
        ReconciliationSweep.SweepResult observed = reconciliationSweep.sweep();
        assertThat(observed.blocked()).as("no lost block in the storm").isZero();
        worked |= observed.aged() + observed.escalated() + observed.collisions() > 0;
        return worked;
    }

    /** Nothing left in flight: no file awaiting a leg, every run complete. */
    private static boolean quiet() throws SQLException {
        return count("SELECT count(*) FROM settlement.file WHERE status = 'RECEIVED' OR (status"
                        + " = 'PARSED' AND (received_via = 'PULL' OR attested_by IS NOT NULL))")
                        + count("SELECT count(*) FROM reconciliation.reconciliation_batch WHERE"
                                + " status <> 'COMPLETED'")
                == 0;
    }

    /** A matcher from the composition's own beans - the metered register, the Spring runner. */
    private Matching matching(Clock instanceClock, MatchingStore store) {
        return new Matching(
                store, matchingRules, breakRegister, suspense, resolutions,
                internalReferenceLookup, ledgerAccountStore, outboxWriter, auditWriter,
                idGenerator, instanceClock, new Matching.Config(CHUNK, 3),
                reconciliationTransactionRunner, reconciliationOutcomeMeters);
    }

    /** The server's lead over the JVM clock, measured once (null until first needed). */
    private Duration serverLead;

    /**
     * Instance {@code i}'s own clock: ticking, the JVM clock plus ONE fixed offset - the server's
     * lead over the JVM, measured once around a single statement (so connection setup is never
     * folded into it) and clamped at zero, so no instance reads behind the server (the
     * {@code SimulatedInstance} rule) - plus {@code i} milliseconds: instance {@code i} reads the
     * server's time plus about {@code i} ms, the instances {@code i} ms apart.
     */
    private Clock instanceClock(int i) throws SQLException {
        if (serverLead == null) {
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement read = app.prepareStatement("SELECT clock_timestamp()")) {
                Instant before = Instant.now(CLOCK);
                Instant server;
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    server = row.getTimestamp(1).toInstant();
                }
                Instant after = Instant.now(CLOCK);
                Instant midpoint = before.plus(Duration.between(before, after).dividedBy(2));
                Duration lead = Duration.between(midpoint, server);
                serverLead = lead.isNegative() ? Duration.ZERO : lead;
            }
        }
        return Clock.offset(Clock.systemUTC(), serverLead.plusMillis(i));
    }

    // ----------------------------------------------------------------- the crash decorators

    /** A crash, as a crash: an {@link Error} no leg contains as "our failure". */
    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String where) {
            super("simulated crash " + where);
        }
    }

    /**
     * The batch store, crashing mid-batch: the real store writes the batch row and batches its
     * lines' {@code INSERT}s; on the line {@value #CRASH_AFTER_LINES} + 1 the first
     * {@value #CRASH_AFTER_LINES} are flushed to the database, counted there, and the instance
     * dies before the rest.
     */
    @SuppressWarnings("unchecked")
    private SettlementBatchStore<Connection> crashingAfterLines(AtomicInteger written) {
        SettlementBatchStore<Connection> real = settlementBatchStore;
        return (SettlementBatchStore<Connection>) Proxy.newProxyInstance(
                SettlementBatchStore.class.getClassLoader(),
                new Class<?>[] {SettlementBatchStore.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("insertParsedBatch")) {
                        return invoke(real, method, arguments);
                    }
                    Connection unitOfWork = (Connection) arguments[0];
                    SettlementBatchStore.NewBatch batch =
                            (SettlementBatchStore.NewBatch) arguments[1];
                    Object[] crashing = arguments.clone();
                    crashing[0] = crashingLineInserts(unitOfWork, batch.batchId(), written);
                    return invoke(real, method, crashing);
                });
    }

    /** The connection, its line-insert statement crashing after {@code CRASH_AFTER_LINES}. */
    private static Connection crashingLineInserts(
            Connection real, UUID batchId, AtomicInteger written) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(real, method, arguments);
                    if (method.getName().equals("prepareStatement")
                            && arguments.length == 1
                            && ((String) arguments[0]).startsWith("INSERT INTO settlement.line (")) {
                        PreparedStatement lines = (PreparedStatement) result;
                        AtomicInteger batched = new AtomicInteger();
                        return Proxy.newProxyInstance(
                                PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (statement, call, callArguments) -> {
                                    if (call.getName().equals("addBatch")
                                            && callArguments == null
                                            && batched.incrementAndGet()
                                                    == CRASH_AFTER_LINES + 1) {
                                        lines.executeBatch();
                                        written.set((int) countOn(real, "SELECT count(*) FROM"
                                                + " settlement.line WHERE batch_id = ?", batchId));
                                        throw new SimulatedCrash("after " + written.get()
                                                + " parsed lines were written");
                                    }
                                    return invoke(lines, call, callArguments);
                                });
                    }
                    return result;
                });
    }

    /** The acceptance intake, crashing when acceptance hands it the recognition just posted. */
    private AcceptedBatchIntake crashingAfterThePosting(AtomicReference<UUID> posted) {
        AcceptedBatchIntake real = acceptedBatchIntake;
        return (AcceptedBatchIntake) Proxy.newProxyInstance(
                AcceptedBatchIntake.class.getClassLoader(),
                new Class<?>[] {AcceptedBatchIntake.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("recognised")) {
                        Connection unitOfWork = (Connection) arguments[0];
                        @SuppressWarnings("unchecked")
                        Optional<UUID> entry = (Optional<UUID>) arguments[3];
                        assertThat(entry).as("a report with fees posts its recognition")
                                .isPresent();
                        assertThat(countOn(unitOfWork, "SELECT count(*) FROM"
                                        + " ledger.journal_entry WHERE id = ?", entry.get()))
                                .as("the posting is written on the acceptance's connection")
                                .isEqualTo(1);
                        posted.set(entry.get());
                        throw new SimulatedCrash("after the recognition posted");
                    }
                    return invoke(real, method, arguments);
                });
    }

    /**
     * The matcher's store, killing its own connection in the SECOND chunk — after the chunk's
     * decisions, allocations and parks are written, before its cursor advances and commits.
     */
    private MatchingStore killingInSecondChunk(AtomicLong killedAt) {
        MatchingStore real = matchingStore;
        AtomicInteger advances = new AtomicInteger();
        return (MatchingStore) Proxy.newProxyInstance(
                MatchingStore.class.getClassLoader(),
                new Class<?>[] {MatchingStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("advanceCursor")
                            && advances.incrementAndGet() == 2) {
                        killedAt.set((Long) arguments[2]);
                        try (Statement kill = ((Connection) arguments[0]).createStatement()) {
                            kill.execute("SELECT pg_terminate_backend(pg_backend_pid())");
                        } catch (SQLException terminated) {
                            // The backend is gone with the statement: exactly the crash.
                        }
                    }
                    return invoke(real, method, arguments);
                });
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    // ----------------------------------------------------------------- delivery

    /** What becomes of a delivered file. */
    private enum Fate {
        ACCEPTED,
        REJECTED,
        REFUSED
    }

    /** One file, its pull key, and which channel delivers it first. */
    private record Delivery(
            String name,
            String source,
            String businessKey,
            LocalDate businessDate,
            byte[] content,
            Fate fate,
            boolean uploadFirst) {}

    /**
     * Every file delivered twice: uploaded and attested by a second operator, and fetched by ten
     * racing pulls of its business key - one of which takes the permit and fetches, nine paced.
     */
    private final class Deliveries {

        private final StormTraffic traffic;
        private final StormTraffic.Staff uploader;
        private final StormTraffic.Staff attester;
        private final Map<String, UUID> files = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<Delivery> delivered = Collections.synchronizedList(new ArrayList<>());

        Deliveries(StormTraffic traffic, StormTraffic.Staff uploader, StormTraffic.Staff attester) {
            this.traffic = traffic;
            this.uploader = uploader;
            this.attester = attester;
        }

        UUID file(String name) {
            UUID file = files.get(name);
            assertThat(file).as("%s was delivered and stored", name).isNotNull();
            return file;
        }

        List<Delivery> delivered() {
            synchronized (delivered) {
                return List.copyOf(delivered);
            }
        }

        UUID twice(Delivery delivery) throws Exception {
            provider.succeedsWith(
                    com.finapp.app.settlement.HttpSettlementReportCollector.REPORTS_PATH
                            + delivery.source() + "/" + delivery.businessKey(),
                    200, new String(delivery.content(), StandardCharsets.UTF_8));
            UUID file;
            if (delivery.uploadFirst()) {
                file = upload(delivery, true);
                pullRace(delivery, file);
            } else {
                file = pullRace(delivery, null);
                upload(delivery, false);
            }
            if (file != null) {
                files.put(delivery.name(), file);
            }
            delivered.add(delivery);
            return file;
        }

        /** The upload door; a first upload of a file to be accepted is attested. */
        private UUID upload(Delivery delivery, boolean first) throws Exception {
            HttpResponse<String> landed =
                    traffic.post(FILES,
                            "{\"sourceCode\":\"" + delivery.source() + "\",\"businessDate\":\""
                                    + delivery.businessDate() + "\",\"content\":\""
                                    + Base64.getEncoder().encodeToString(delivery.content())
                                    + "\"}",
                            uploader.token(), "upl-" + UUID.randomUUID());
            if (delivery.fate() == Fate.REFUSED) {
                assertThat(landed.statusCode()).as("%s: %s", delivery.name(), landed.body())
                        .isEqualTo(422);
                assertThat(landed.body())
                        .contains("settlement.DeliveryRefused")
                        .doesNotContain(PAN_NEEDLE)
                        .doesNotContain(IBAN_NEEDLE);
                return null;
            }
            assertThat(landed.statusCode()).as("%s: %s", delivery.name(), landed.body())
                    .isEqualTo(202);
            UUID file = UUID.fromString(StormTraffic.field(landed.body(), "fileId"));
            if (!first) {
                assertThat(StormTraffic.field(landed.body(), "duplicateOf"))
                        .as("%s: the upload converges on the pulled file", delivery.name())
                        .isEqualTo(file.toString());
                return file;
            }
            if (delivery.fate() == Fate.ACCEPTED) {
                HttpResponse<String> attested =
                        traffic.post(FILES + "/" + file + "/attestation", null, attester.token(),
                                null);
                assertThat(attested.statusCode())
                        .as("%s: the second person attests: %s", delivery.name(),
                                attested.body())
                        .isEqualTo(200);
            }
            return file;
        }

        /** Ten racing pulls of the key: one fetch, nine paced. */
        private UUID pullRace(Delivery delivery, UUID uploaded) throws Exception {
            ExecutorService racers = Executors.newFixedThreadPool(PULL_RACERS);
            List<SettlementPull.Outcome> outcomes = new ArrayList<>();
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<SettlementPull.Outcome>> pulls = new ArrayList<>();
                for (int i = 0; i < PULL_RACERS; i++) {
                    pulls.add(racers.submit(() -> {
                        start.await();
                        Correlation correlation =
                                Correlation.startingWith(CorrelationId.generate(IDS));
                        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                                CorrelationContext.Scope flow =
                                        CorrelationContext.enter(correlation)) {
                            return settlementPull.pull(delivery.source(), delivery.businessKey(),
                                    Optional.of(WINDOW), SecurityContext.require(), correlation);
                        }
                    }));
                }
                start.countDown();
                for (Future<SettlementPull.Outcome> pull : pulls) {
                    outcomes.add(pull.get(2, TimeUnit.MINUTES));
                }
            } finally {
                racers.shutdownNow();
            }
            List<SettlementPull.Outcome> fetched =
                    outcomes.stream()
                            .filter(outcome -> !(outcome instanceof SettlementPull.Outcome.Paced))
                            .toList();
            assertThat(fetched)
                    .as("%s: ten racing pulls, ONE fetch - nine lost the permit: %s",
                            delivery.name(), outcomes)
                    .hasSize(1);
            assertThat(fetched.get(0)).isInstanceOf(SettlementPull.Outcome.Received.class);
            FileReception.Result result =
                    ((SettlementPull.Outcome.Received) fetched.get(0)).result();
            if (delivery.fate() == Fate.REFUSED) {
                assertThat(result).as("%s: the pull meets the same door", delivery.name())
                        .isInstanceOf(FileReception.Result.Refused.class);
                return null;
            }
            if (uploaded == null) {
                assertThat(result).as("%s: the pull lands first", delivery.name())
                        .isInstanceOf(FileReception.Result.New.class);
                return ((FileReception.Result.New) result).fileId();
            }
            assertThat(result).as("%s: the pull converges on the upload", delivery.name())
                    .isEqualTo(new FileReception.Result.Duplicate(uploaded));
            return uploaded;
        }
    }

    // ----------------------------------------------------------------- the records

    /** A captured card operation as the PSP saw it: its reference and gross. */
    private record Capture(String ref, long amount) {}

    private record RefundRecord(String providerRef, String ourRef, long amount) {}

    private record WithdrawalRecord(String schemeRef, String cycle, long amount) {}

    /** The settlement remittance an accepted report declares: its signed net and reference. */
    private record Remittance(long netMinor, String reference) {}

    /** What the simulated providers did, read off the payments and merchant tables. */
    private static final class Records {

        Capture captureOfIntent(String intent) throws SQLException {
            String row =
                    (String) one("SELECT a.capture_provider_reference || '|'"
                            + " || a.captured_amount_minor::text FROM payments.payment_attempt a"
                            + " WHERE a.intent_id = ?::uuid AND a.status = 'CAPTURED'", intent);
            assertThat(row).as("intent %s captured", intent).isNotNull();
            String[] fields = row.split("\\|");
            return new Capture(fields[0], Long.parseLong(fields[1]));
        }

        List<Capture> salesOf(StormTraffic.Merchant merchant) throws SQLException {
            List<Capture> sales = new ArrayList<>();
            for (String row : rows("SELECT a.capture_provider_reference || '|'"
                    + " || a.captured_amount_minor::text FROM payments.payment_attempt a JOIN"
                    + " payments.payment_intent i ON i.id = a.intent_id WHERE a.status ="
                    + " 'CAPTURED' AND i.credit_account_id = ? ORDER BY a.captured_amount_minor",
                    merchant.payable())) {
                String[] fields = row.split("\\|");
                sales.add(new Capture(fields[0], Long.parseLong(fields[1])));
            }
            return sales;
        }

        List<RefundRecord> refunds() throws SQLException {
            List<RefundRecord> refunds = new ArrayList<>();
            for (String row : rows("SELECT provider_reference || '|'"
                    + " || provider_idempotency_reference || '|' || amount_minor::text FROM"
                    + " payments.refund WHERE status = 'COMPLETED' ORDER BY amount_minor")) {
                String[] fields = row.split("\\|");
                refunds.add(new RefundRecord(fields[0], fields[1], Long.parseLong(fields[2])));
            }
            return refunds;
        }

        List<WithdrawalRecord> withdrawals() throws SQLException {
            List<WithdrawalRecord> withdrawals = new ArrayList<>();
            for (String row : rows("SELECT scheme_reference || '|' || settlement_cycle || '|'"
                    + " || amount_minor::text FROM payments.withdrawal WHERE status ="
                    + " 'COMPLETED' ORDER BY amount_minor")) {
                String[] fields = row.split("\\|");
                withdrawals.add(
                        new WithdrawalRecord(fields[0], fields[1], Long.parseLong(fields[2])));
            }
            return withdrawals;
        }

        String payoutProviderRef(UUID payout) throws SQLException {
            return (String) one("SELECT provider_reference FROM merchant.merchant_payout WHERE"
                    + " id = ?", payout);
        }

        /** The provider's line for a payout, read off {@code merchant.merchant_payout}. */
        SimulatedPayoutReports.Entry payoutLine(UUID payout, boolean settled)
                throws SQLException {
            String[] fields =
                    ((String) one("SELECT provider_reference || '|'"
                            + " || provider_idempotency_reference || '|' || amount_minor::text"
                            + " FROM merchant.merchant_payout WHERE id = ?", payout))
                            .split("\\|");
            String amount = decimal(Long.parseLong(fields[2]));
            return settled
                    ? SimulatedPayoutReports.Entry.settled(amount, PAYOUT_FEE, fields[0],
                            fields[1])
                    : SimulatedPayoutReports.Entry.returned(amount, fields[0], fields[1]);
        }
    }

    /**
     * Every external item as {@code source|line type|primary reference|status} — the primary
     * reference the line's own most specific one (a fee line's is its original's), a bank fee's
     * {@code -}.
     */
    private static final String ITEM_CENSUS =
            "SELECT s.code || '|' || i.line_type || '|' || coalesce((SELECT r.value FROM"
                    + " settlement.line_reference r WHERE r.line_id = i.settlement_line_id ORDER"
                    + " BY CASE r.kind WHEN 'PSP_CAPTURE_REF' THEN 1 WHEN 'PSP_REFUND_REF' THEN 2"
                    + " WHEN 'SCHEME_REF' THEN 3 WHEN 'PAYOUT_PROVIDER_REF' THEN 4 WHEN"
                    + " 'REMITTANCE_REF' THEN 5 WHEN 'ORIGINAL_REF' THEN 6 ELSE 7 END LIMIT 1),"
                    + " '-') || '|' || i.status FROM reconciliation.external_item i JOIN"
                    + " settlement.source s ON s.id = i.source_id";

    /**
     * Every expectation as {@code kind|primary key|status} — the counterparty's own reference,
     * or the remittance reference; a {@code PAYOUT_RETURN}, which opens no key, by its operation.
     */
    private static final String EXPECTATION_CENSUS =
            "SELECT e.kind || '|' || coalesce((SELECT k.key_value FROM"
                    + " reconciliation.expectation_key k WHERE k.expectation_id = e.id ORDER BY"
                    + " CASE k.key_kind WHEN 'PSP_CAPTURE_REF' THEN 1 WHEN 'PSP_REFUND_REF' THEN 2"
                    + " WHEN 'SCHEME_REF' THEN 3 WHEN 'PAYOUT_PROVIDER_REF' THEN 4 WHEN"
                    + " 'REMITTANCE_REF' THEN 5 ELSE 6 END LIMIT 1), e.operation_ref) || '|'"
                    + " || e.status FROM reconciliation.expectation e";

    private static String itemKey(String source, String lineType, String ref, String status) {
        return source + "|" + lineType + "|" + ref + "|" + status;
    }

    private static String expectationKey(String kind, String ref, String status) {
        return kind + "|" + ref + "|" + status;
    }

    /** A PSP capture line at {@code status}, and its split fee line, CHECKED. */
    private static void feeBearingCapture(List<String> items, String ref, String status) {
        items.add(itemKey(PSP, "CAPTURE", ref, status));
        items.add(itemKey(PSP, "PROCESSING_FEE", ref, "CHECKED"));
    }

    /**
     * A capture's report line: its gross moved by {@code amountDelta} and its fee - the pinned
     * schedule's own, priced on the RECORDED gross the matcher prices it on - moved by
     * {@code feeDelta}. Both zero is a genuine line.
     */
    private static SimulatedSettlementReports.Line captureLine(
            Capture capture, long amountDelta, long feeDelta) {
        return SimulatedSettlementReports.Line.capture(
                capture.ref(), "", "", decimal(capture.amount() + amountDelta),
                decimal(pspFee(capture.amount()) + feeDelta));
    }

    private static long pspFee(long grossMinor) {
        return PSP_RATE.multiply(BigDecimal.valueOf(grossMinor))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact()
                + PSP_FIXED;
    }

    private static SimulatedSchemeReports.Entry schemeCredit(long minor, String schemeRef) {
        return new SimulatedSchemeReports.Entry("CT", "C", decimal(minor), SCHEME_FEE, schemeRef,
                Optional.empty(), Optional.empty());
    }

    private static SimulatedSchemeReports.Entry schemeDebit(long minor, String schemeRef) {
        return new SimulatedSchemeReports.Entry("CT", "D", decimal(minor), SCHEME_FEE, schemeRef,
                Optional.empty(), Optional.empty());
    }

    /** A parse-level fault on a report of one capture no platform record names. */
    private static byte[] faultyReport(LocalDate day, SimulatedSettlementReports.Fault fault) {
        return new SimulatedSettlementReports("PSPB-STORM-F-" + StormTraffic.letters(8), "EUR",
                        day, pspRemittance())
                .with(SimulatedSettlementReports.Line.capture(
                        "PSP-CAP-" + StormTraffic.letters(10), "", "", "12.00", "0.43"))
                .faulted(fault)
                .render();
    }

    /** The bank pays each remittance by its reference, in the remittance's own direction. */
    private static SimulatedBankStatements bankLines(
            SimulatedBankStatements statement, LocalDate valueDate, Remittance... remittances) {
        for (Remittance remittance : remittances) {
            if (remittance.netMinor() > 0) {
                statement.credit(valueDate, remittance.netMinor(),
                        Optional.of(remittance.reference()));
            } else {
                statement.debit(valueDate, -remittance.netMinor(),
                        Optional.of(remittance.reference()));
            }
        }
        return statement;
    }

    private static Remittance remittanceOf(UUID file) throws SQLException {
        String[] fields =
                ((String) one("SELECT net_minor::text || '|' || remittance_reference FROM"
                        + " settlement.batch WHERE file_id = ? AND status = 'ACCEPTED'", file))
                        .split("\\|");
        assertThat(Long.parseLong(fields[0])).as("a remittance moves money").isNotZero();
        return new Remittance(Long.parseLong(fields[0]), fields[1]);
    }

    // ----------------------------------------------------------------- reads

    private PositionProof.CashVerdict cashOf(CurrencyCode currency) throws SQLException {
        try (Connection snapshot = DatabaseRoles.application()) {
            snapshot.setAutoCommit(false);
            snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return proof.sweep(snapshot).cashOf(currency).orElseThrow();
            } finally {
                snapshot.rollback();
            }
        }
    }

    private static String gapSql(String currency) {
        return "SELECT 1 FROM settlement.batch b JOIN settlement.source s ON s.id = b.source_id"
                + " WHERE s.code = '" + BANK + "' AND b.currency = '" + currency + "' AND"
                + " b.status = 'ACCEPTED' AND b.statement_sequence > 1 AND NOT EXISTS (SELECT 1"
                + " FROM settlement.batch p WHERE p.source_id = b.source_id AND p.currency ="
                + " b.currency AND p.status = 'ACCEPTED' AND p.statement_sequence ="
                + " b.statement_sequence - 1)";
    }

    private static String statementSql(String currency) {
        return "SELECT 1 FROM settlement.batch b JOIN settlement.source s ON s.id = b.source_id"
                + " WHERE s.code = '" + BANK + "' AND b.currency = '" + currency + "' AND"
                + " b.status = 'ACCEPTED'";
    }

    /** Every reference a break's subject carries: its item's line references, its expectation's keys. */
    private static String subjectOf(String type, String cause) throws SQLException {
        return (String) one("SELECT string_agg(coalesce((SELECT string_agg(r.value, ',') FROM"
                + " settlement.line_reference r JOIN reconciliation.external_item i ON"
                + " i.settlement_line_id = r.line_id WHERE i.id = b.external_item_id), '') || ','"
                + " || coalesce((SELECT string_agg(k.key_value, ',') FROM"
                + " reconciliation.expectation_key k WHERE k.expectation_id = b.expectation_id),"
                + " '') || ',' || coalesce((SELECT string_agg(k.key_value, ',') FROM"
                + " reconciliation.allocation a JOIN reconciliation.expectation_key k ON"
                + " k.expectation_id = a.expectation_id WHERE a.decision_id = b.decision_id),"
                + " ''), ';') FROM reconciliation.break b WHERE b.type = ? AND b.cause = ?",
                type, cause);
    }

    private static UUID expectationOf(String captureRef) throws SQLException {
        UUID id =
                (UUID) one("SELECT expectation_id FROM reconciliation.expectation_key WHERE"
                        + " key_kind = 'PSP_CAPTURE_REF' AND key_value = ?", captureRef);
        assertThat(id).as("the capture %s opened its expectation", captureRef).isNotNull();
        return id;
    }

    private static UUID batchOf(UUID file) throws SQLException {
        UUID batch = (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", file);
        assertThat(batch).as("file %s parsed into a batch", file).isNotNull();
        return batch;
    }

    private static UUID runOf(UUID batch) throws SQLException {
        UUID run =
                (UUID) one("SELECT id FROM reconciliation.reconciliation_batch WHERE batch_id = ?",
                        batch);
        assertThat(run).as("batch %s has its run", batch).isNotNull();
        return run;
    }

    private static String fileStatus(UUID file) throws SQLException {
        return (String) one("SELECT status || ':' || coalesce(rejection_code, '-') FROM"
                + " settlement.file WHERE id = ?", file);
    }

    // ----------------------------------------------------------------- plumbing

    private static void runAll(ExecutorService pool, List<Callable<Void>> tasks)
            throws Exception {
        List<Future<Void>> running = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            running.add(pool.submit(task));
        }
        for (Future<Void> task : running) {
            task.get(5, TimeUnit.MINUTES);
        }
    }

    private static String pspRemittance() {
        return "PSP-REM-81" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    private static String schemeRemittance() {
        return "SCH-REM-81" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    private static String payoutRemittance() {
        return "PAY-REM-81" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    private static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String freshKey() {
        byte[] key = new byte[32];
        RANDOMNESS.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** The log lines carrying {@code needle} - a failing reading names what it found. */
    private static List<String> linesWith(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).toList();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        Matcher found = Pattern.compile(Pattern.quote(needle)).matcher(text);
        while (found.find()) {
            count++;
        }
        return count;
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            statement.executeUpdate();
        }
    }

    private static Object one(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        return ((Number) one(sql, arguments)).longValue();
    }

    private static long countOn(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static boolean exists(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            return row.next();
        }
    }

    private static List<String> rows(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, arguments);
            List<String> rows = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    rows.add(row.getString(1));
                }
            }
            return rows;
        }
    }

    private static List<String[]> pairs(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return pairs(app, sql);
        }
    }

    private static List<String[]> pairs(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            List<String[]> rows = new ArrayList<>();
            while (row.next()) {
                rows.add(new String[] {row.getString(1), row.getString(2)});
            }
            return rows;
        }
    }

    private static void bind(PreparedStatement statement, Object... arguments)
            throws SQLException {
        for (int i = 0; i < arguments.length; i++) {
            statement.setObject(i + 1, arguments[i]);
        }
    }
}
