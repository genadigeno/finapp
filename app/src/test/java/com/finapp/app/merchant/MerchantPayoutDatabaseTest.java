package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.app.telemetry.MerchantMeters;
import com.finapp.app.telemetry.ReconciliationMetrics;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.KeyKind;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldExceedsAvailableBalanceException;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantNotTradingException;
import com.finapp.merchant.MerchantPayable;
import com.finapp.merchant.MerchantPayout;
import com.finapp.merchant.MerchantPayoutId;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.MerchantPayoutUnfundedException;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.NoEffectiveDestinationException;
import com.finapp.merchant.PayoutCurrencyMismatchException;
import com.finapp.merchant.PayoutDestinationId;
import com.finapp.merchant.PayoutDestinationReference;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.merchant.PayoutDestinationTokenisation;
import com.finapp.merchant.PayoutDestinations;
import com.finapp.merchant.PayoutEvidenceStore;
import com.finapp.merchant.PayoutFailureReason;
import com.finapp.merchant.PayoutProvider;
import com.finapp.merchant.PayoutQueryAnswer;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The merchant payout against the real schema (`P6-TSK-012`, ADR-0051, ADR-0057): the
 * hold-then-dispatch, the three outcomes' money, the payable bound raced and contested, the
 * timeout's standing hold resolved by the sweep, the takeover's convergence and its send
 * permit, and `V007` refusing — for raw SQL — what the domain refuses. The HTTP surface is
 * {@code MerchantPayoutEndpointDatabaseTest}'s.
 *
 * <p>Every count is read from the tables, never inferred from a return value.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest
@DisplayName("the merchant payout against the real schema (P6-TSK-012)")
class MerchantPayoutDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final String PATH = SimulatedPayoutProvider.PAYOUTS_PATH;
    private static final String PAID =
            "{\"status\":\"paid\",\"reference\":\"po-{{request.headers.Idempotency-Key}}\"}";
    private static final int RACERS = 10;

    private static SimulatedProvider provider;

    @Autowired private MerchantPayouts payouts;
    @Autowired private com.finapp.merchant.MerchantAdministration administration;
    @Autowired private MerchantPayoutStore<Connection> payoutStore;
    @Autowired private MerchantPayoutOutcomes outcomes;
    @Autowired private PayoutEvidenceStore<Connection> evidence;
    @Autowired private PayoutDestinationStore<Connection> destinations;
    @Autowired private PayoutDestinations destinationChanges;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private HoldService holds;
    @Autowired private PostingService postings;
    @Autowired private IdempotentExecutor idempotentExecutor;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private MerchantPayableQuery payables;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @Autowired private PositionProof positionProof;
    @Autowired private com.finapp.reconciliation.RunReadings runReadings;
    @Autowired private com.finapp.settlement.SettlementFileStore<Connection> settlementFileStore;
    @Autowired private com.finapp.settlement.SettlementSources settlementSources;

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
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
        registry.add("finapp.merchant.payout.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The money
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a paid payout releases its hold and posts DR payable / CR PAYOUT_CLEARING, once")
    void aPaidPayoutReleasesAndPosts() throws Exception {
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, PAID);

        MerchantPayouts.Initiated paid = initiate(merchant, "40.00", key());

        assertThat(paid.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(holdStatuses(merchant)).containsExactly("RELEASED");
        assertThat(entryLines(paid.payout()))
                .containsExactlyInAnyOrder(
                        "DEBIT:MERCHANT_PAYABLE:4000", "CREDIT:PAYOUT_CLEARING:4000");
        assertThat(positionMinor(merchant)).isEqualTo(6000);
        assertThat(outboxCount("merchant.MerchantPayoutInitiated", paid.payout())).isEqualTo(1);
        assertThat(outboxCount("merchant.MerchantPayoutCompleted", paid.payout())).isEqualTo(1);
        assertThat(auditCount("merchant.MerchantPayoutInitiated", paid.payout())).isEqualTo(1);
        assertThat(auditCount("merchant.MerchantPayoutOutcomeApplied", paid.payout()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_evidence WHERE payout_id = ?"
                                + " AND kind = 'RESPONSE'",
                        paid.payout().value()))
                .as("the provider's answer is retained, encrypted")
                .isEqualTo(1);
        assertThat(provider.headerValues(PATH, SimulatedPayoutProvider.IDEMPOTENCY_KEY_HEADER))
                .as("our minted reference was committed before the wire and sent as the key")
                .containsExactly(stored(merchant, paid.payout()).reference().value());

        // ITS EXPECTATION (P8-TSK-005, ADR-0067), through merchant's OWN port and app's one
        // recorder: the completion's PAYOUT_CLEARING line's copy, OUTBOUND, keyed by the
        // provider's reference and ours (pyo-...).
        String payoutId = paid.payout().value().toString();
        ClearingLineCopies.Opened opened =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.MERCHANT_PAYOUT, payoutId,
                        MerchantPayoutOutcomes.POSTING_KEY_PREFIX + payoutId,
                        ExpectationDirection.OUTBOUND);
        assertThat(opened.amountMinor()).isEqualTo(4000);
        assertThat(opened.settlementCycle()).as("a payout announces no cycle").isEmpty();
        ClearingLineCopies.assertKeyed(
                opened,
                KeyKind.PAYOUT_PROVIDER_REF,
                stored(merchant, paid.payout()).providerReference().orElseThrow().value());
        ClearingLineCopies.assertKeyed(
                opened, KeyKind.OUR_REF, stored(merchant, paid.payout()).reference().value());
    }

    @Test
    @DisplayName("a paid payout's open expectation explains PAYOUT_CLEARING in the ledger's own"
            + " sign: DR-CR of the CREDIT-normal position reads negative, equal to the OUTBOUND"
            + " remainder, and the proof gauge reads 0 (INV-REC-06)")
    void aPaidPayoutIsExplainedOnItsPosition() throws Exception {
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, PAID);
        MerchantPayouts.Initiated paid = initiate(merchant, "40.00", key());
        assertThat(paid.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);

        // PAYOUT_CLEARING is CREDIT-normal (ledger V012), so its derived balance is CR-DR;
        // the identity reads DR-CR whatever the chart's classification (ADR-0067 section 3).
        // The payout's expectation stands open, so the position is non-zero and the sign is
        // JUDGED - zero is sign-blind (P8-TSK-007's recorded find). Only this position is
        // asserted: the shared container's other positions are other suites' to prove.
        PositionProof.Report report = sweep();
        List<PositionProof.PositionVerdict> payoutClearing =
                report.verdicts().stream()
                        .filter(verdict -> verdict.purpose() == AccountPurpose.PAYOUT_CLEARING)
                        .toList();
        assertThat(payoutClearing)
                .extracting(verdict -> verdict.currency().code())
                .containsExactlyInAnyOrder("EUR", "GBP", "USD");
        for (PositionProof.PositionVerdict verdict : payoutClearing) {
            assertThat(verdict.explained())
                    .as("%s %s: DR-CR %s = open remainders %s - open items %s (INV-REC-06)",
                            verdict.purpose(), verdict.currency(), verdict.ledgerBalance(),
                            verdict.openRemainders(), verdict.openItems())
                    .isTrue();
        }
        PositionProof.PositionVerdict eur =
                payoutClearing.stream()
                        .filter(verdict -> verdict.currency().equals(EUR))
                        .findFirst()
                        .orElseThrow();
        assertThat(eur.ledgerBalance().isNegative())
                .as("the payout's CR line reads negative in DR-CR - the sign is judged: %s",
                        eur.ledgerBalance())
                .isTrue();
        assertThat(eur.openRemainders().minus(eur.openItems())).isEqualTo(eur.ledgerBalance());

        // The gauge consumes the same verdict: a FRESH instance, so its first read sweeps
        // now rather than answering a reading the application's instance cached earlier.
        io.micrometer.core.instrument.simple.SimpleMeterRegistry scraped =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new ReconciliationMetrics(
                positionProof,
                runReadings,
                settlementFileStore,
                settlementSources,
                DatabaseRoles::application,
                CLOCK,
                scraped);
        assertThat(scraped.find(ReconciliationMetrics.PROOF)
                        .tag("purpose", AccountPurpose.PAYOUT_CLEARING.name())
                        .gauge()
                        .value())
                .as("finapp.reconciliation.position.proof{purpose=PAYOUT_CLEARING}")
                .isZero();
    }

    @Test
    @DisplayName("a declined payout is FAILED(DECLINED): the hold released and nothing posted")
    void aDeclinedPayoutPostsNothing() throws Exception {
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, "{\"status\":\"declined\"}");

        MerchantPayouts.Initiated declined = initiate(merchant, "40.00", key());

        assertThat(declined.status()).isEqualTo(MerchantPayoutStatus.FAILED);
        assertThat(stored(merchant, declined.payout()).failureReason())
                .contains(PayoutFailureReason.DECLINED);
        assertThat(holdStatuses(merchant)).containsExactly("RELEASED");
        assertThat(entryLines(declined.payout())).isEmpty();
        assertThat(positionMinor(merchant)).as("the payable is whole").isEqualTo(10000);
        assertThat(outboxCount("merchant.MerchantPayoutFailed", declined.payout())).isEqualTo(1);
        assertThat(ClearingLineCopies.expectationsOf(
                        ExpectationKind.MERCHANT_PAYOUT, declined.payout().value().toString()))
                .as("nothing posted, nothing expected (P8-TSK-005)")
                .isZero();
    }

    @Test
    @DisplayName("a refused connection on the first send is FAILED(PROVIDER_UNAVAILABLE), released")
    void nothingSentOnTheFirstSend() throws Exception {
        Funded merchant = funded("100.00");
        MerchantPayouts.Initiated refused =
                initiateWith(payoutsSendingTo(unreachable(), idempotentExecutor), merchant,
                        "40.00", key());

        assertThat(refused.status()).isEqualTo(MerchantPayoutStatus.FAILED);
        assertThat(stored(merchant, refused.payout()).failureReason())
                .contains(PayoutFailureReason.PROVIDER_UNAVAILABLE);
        assertThat(holdStatuses(merchant)).containsExactly("RELEASED");
        assertThat(entryLines(refused.payout())).isEmpty();
    }

    // -----------------------------------------------------------------
    // The bound (INV-MER-05)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("ten concurrent payouts against one payable dispatch exactly the affordable set")
    void tenConcurrentPayoutsDispatchExactlyTheAffordableSet() throws Exception {
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, PAID);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            racers.add(
                    () -> {
                        try {
                            return initiate(merchant, "25.00", key());
                        } catch (MerchantPayoutUnfundedException unfunded) {
                            return unfunded;
                        }
                    });
        }
        List<Object> outcomes = race(racers);

        assertThat(outcomes.stream().filter(MerchantPayouts.Initiated.class::isInstance))
                .as("100.00 funds exactly four payouts of 25.00")
                .hasSize(4);
        assertThat(outcomes.stream().filter(MerchantPayoutUnfundedException.class::isInstance))
                .hasSize(RACERS - 4);
        assertThat(count(
                        "SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?",
                        merchant.id().value()))
                .isEqualTo(4);
        assertThat(holdStatuses(merchant)).hasSize(4).containsOnly("RELEASED");
        assertThat(count(
                        "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope LIKE"
                                + " 'ledger.post:merchant-payout:%' AND reference IN (SELECT"
                                + " id::text FROM merchant.merchant_payout WHERE merchant_id = ?)",
                        merchant.id().value()))
                .isEqualTo(4);
        assertThat(positionMinor(merchant)).as("never below zero: nothing over-paid").isZero();
    }

    @Test
    @DisplayName("the unfunded refusal commits nothing: no row, no hold, no claim, no record")
    void theUnfundedRefusalCommitsNothing() throws Exception {
        Funded merchant = funded("10.00");
        String key = key();
        assertThatThrownBy(() -> initiate(merchant, "20.00", key))
                .isInstanceOf(MerchantPayoutUnfundedException.class);

        assertThat(count(
                        "SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?",
                        merchant.id().value()))
                .isZero();
        assertThat(holdStatuses(merchant)).isEmpty();
        assertThat(count(
                        "SELECT count(*) FROM platform.idempotency_record WHERE scope = ? AND"
                                + " idempotency_key = ?",
                        MerchantPayouts.IDEMPOTENCY_SCOPE_PREFIX + merchant.id().value(),
                        key))
                .as("the key is unspent: a later retry may fit")
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation LIKE"
                                + " 'merchant.MerchantPayout%' AND change_summary LIKE ?",
                        "%merchant=" + merchant.id() + "%"))
                .isZero();
        assertThat(provider.requestCount(PATH)).isZero();
    }

    @Test
    @DisplayName("a negative payable refuses every payout until a later capture restores it")
    void aNegativePayableRefusesEveryPayout() throws Exception {
        Funded merchant = funded("0.00");
        // A RETAINED fee's share kept on a refund, the payable left owing the platform
        // (ADR-0054): refund-shaped, DEBIT payable / CREDIT the payer's wallet.
        post(merchant, "5.00", Direction.DEBIT);
        assertThat(positionMinor(merchant)).isEqualTo(-500);
        assertThatThrownBy(() -> initiate(merchant, "0.01", key()))
                .isInstanceOf(MerchantPayoutUnfundedException.class);

        post(merchant, "20.00", Direction.CREDIT);
        provider.succeedsWith(PATH, 200, PAID);
        assertThat(initiate(merchant, "15.00", key()).status())
                .as("the capture repaid the debt first, and only the remainder may leave")
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThatThrownBy(() -> initiate(merchant, "0.01", key()))
                .isInstanceOf(MerchantPayoutUnfundedException.class);
    }

    @Test
    @DisplayName("a refund dispatched while a payout is in flight is judged against what its hold leaves")
    void aRefundIsJudgedAgainstThePayoutsHold() throws Exception {
        Funded merchant = funded("100.00");
        provider.neverResponds(PATH);
        MerchantPayouts.Initiated inFlight = initiate(merchant, "80.00", key());
        assertThat(inFlight.status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);

        // The refund's funding check IS a hold on the payable (ADR-0054's mechanism): the
        // payout's standing hold leaves 20.00, so 30.00 is refused and 20.00 fits.
        assertThatThrownBy(() -> hold(merchant, "30.00"))
                .isInstanceOf(HoldExceedsAvailableBalanceException.class);
        hold(merchant, "20.00");
    }

    // -----------------------------------------------------------------
    // Ambiguity and its resolution
    // -----------------------------------------------------------------

    @Test
    @DisplayName("timeout: UNKNOWN with the hold standing, a retry replays, and the sweep resolves it")
    void aTimeoutIsResolvedByTheSweep() throws Exception {
        Funded merchant = funded("100.00");
        provider.neverResponds(PATH);
        String key = key();

        MerchantPayouts.Initiated unknown = initiate(merchant, "40.00", key);
        assertThat(unknown.status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);
        assertThat(holdStatuses(merchant)).containsExactly("ACTIVE");
        assertThat(positionMinor(merchant)).as("a hold is not a posting").isEqualTo(10000);
        assertThatThrownBy(() -> initiate(merchant, "70.00", key()))
                .as("but it narrows what the next payout may take")
                .isInstanceOf(MerchantPayoutUnfundedException.class);

        MerchantPayouts.Initiated retried = initiate(merchant, "40.00", key);
        assertThat(retried.replayed()).isTrue();
        assertThat(retried.status())
                .as("the retry answers the judgement it was given")
                .isEqualTo(MerchantPayoutStatus.UNKNOWN);
        assertThat(provider.requestCount(PATH))
                .as("retry-after-timeout converges to ONE wire operation")
                .isEqualTo(1);

        queryAnswers(merchant, unknown.payout(), "paid");
        MerchantPayoutResolution.SweepResult swept = resolution(Duration.ZERO).sweep();
        assertThat(swept.resolved()).isGreaterThanOrEqualTo(1);
        assertThat(stored(merchant, unknown.payout()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(holdStatuses(merchant)).containsExactly("RELEASED");
        assertThat(entryLines(unknown.payout()))
                .containsExactlyInAnyOrder(
                        "DEBIT:MERCHANT_PAYABLE:4000", "CREDIT:PAYOUT_CLEARING:4000");
        assertThat(count(
                        "SELECT count(*) FROM merchant.payout_evidence WHERE payout_id = ?"
                                + " AND kind = 'QUERY_RESULT'",
                        unknown.payout().value()))
                .isEqualTo(1);
        // The provider has no webhook: the sweep is the completion's other arrival, and it
        // opens the expectation exactly as the first answer would have (P8-TSK-005).
        String payoutId = unknown.payout().value().toString();
        ClearingLineCopies.assertOpensItsClearingLinesCopy(
                ExpectationKind.MERCHANT_PAYOUT, payoutId,
                MerchantPayoutOutcomes.POSTING_KEY_PREFIX + payoutId,
                ExpectationDirection.OUTBOUND);
    }

    @Test
    @DisplayName("ten concurrent sweeps leave one outcome, one entry, one fact, one record")
    void tenConcurrentSweepsLeaveOneOutcome() throws Exception {
        Funded merchant = funded("100.00");
        provider.neverResponds(PATH);
        MerchantPayouts.Initiated unknown = initiate(merchant, "40.00", key());
        queryAnswers(merchant, unknown.payout(), "paid");

        List<Callable<Object>> sweepers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            sweepers.add(() -> resolution(Duration.ZERO).sweep());
        }
        List<Object> swept = race(sweepers);

        assertThat(stored(merchant, unknown.payout()).status())
                .isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(swept.stream()
                        .map(MerchantPayoutResolution.SweepResult.class::cast)
                        .flatMap(result -> result.actingJudgements().stream())
                        .filter(MerchantPayoutStatus.COMPLETED::equals))
                .as("the meter's tally (P6-TSK-013): ten sweeps, ONE acting completion - nine"
                        + " converged on a judgement they did not make")
                .hasSize(1);
        assertThat(entryLines(unknown.payout())).hasSize(2);
        assertThat(outboxCount("merchant.MerchantPayoutCompleted", unknown.payout()))
                .isEqualTo(1);
        assertThat(auditCount("merchant.MerchantPayoutOutcomeApplied", unknown.payout()))
                .as("DISPATCHED -> UNKNOWN once, UNKNOWN -> COMPLETED once")
                .isEqualTo(2);
        assertThat(ClearingLineCopies.expectationsOf(
                        ExpectationKind.MERCHANT_PAYOUT, unknown.payout().value().toString()))
                .as("ten sweeps, one expectation - the acting exit decides (P8-TSK-005)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the WIRED stuck-payout gauges read the real schema through the application's own"
            + " pool and the sweep's own bound: a number, never NaN")
    void theWiredGaugesReadTheRealSchema() throws Exception {
        // DOD-OBS's "verified against a running instance" (P6-TSK-013): the hermetic suite stubs
        // the reading and the schema suite calls the store directly, so only this proves the
        // bean the running application registered - its pool, its grants, its placeholder -
        // publishes something an alert can evaluate.
        Funded merchant = funded("100.00");
        provider.neverResponds(PATH);
        assertThat(initiate(merchant, "40.00", key()).status())
                .isEqualTo(MerchantPayoutStatus.UNKNOWN);

        // The floor: a reading an earlier test took may stand for up to MIN_REFRESH, so the
        // payout's arrival in it is awaited, never assumed.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                meterRegistry
                                                        .get("finapp.merchant.payout.unknown.active")
                                                        .gauge()
                                                        .value())
                                        .as("an unknown payout the running instance can see")
                                        .isNotNaN()
                                        .isGreaterThanOrEqualTo(1.0d));
        assertThat(meterRegistry.get("finapp.merchant.payout.unknown.age").gauge().value())
                .isNotNaN()
                .isGreaterThanOrEqualTo(0.0d);
    }

    @Test
    @DisplayName("the schedule counts the sweep's acting judgements, once, after they committed")
    void theScheduleCountsActingJudgementsOnce() throws Exception {
        Funded merchant = funded("100.00");
        MerchantPayoutId crashed = crash(merchant, "40.00", key());
        queryAnswers(merchant, crashed, "paid");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantPayoutResolutionSchedule schedule =
                new MerchantPayoutResolutionSchedule(
                        resolution(Duration.ZERO),
                        new MerchantMeters(registry),
                        Duration.ofMillis(50));
        schedule.start();
        try {
            await().atMost(Duration.ofSeconds(20)).until(() -> completedPayouts(registry) >= 1.0d);
            // Later ticks find the payout resolved: a skipped candidate is no judgement.
            await().during(Duration.ofMillis(400))
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> completedPayouts(registry) == 1.0d);
        } finally {
            schedule.stop();
        }
        assertThat(stored(merchant, crashed).status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(completedPayouts(registry))
                .as("one completion, counted by the tick that made it (P6-TSK-013)")
                .isEqualTo(1.0d);
    }

    private static double completedPayouts(SimpleMeterRegistry registry) {
        return registry.get("finapp.merchant.payout").tag("outcome", "completed").counter().count();
    }

    // -----------------------------------------------------------------
    // The takeover and the send permit (ADR-0057 sections 3-4)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a crashed dispatch is taken over: no second hold, the STORED reference re-sent")
    void theCrashedDispatchIsTakenOver() throws Exception {
        Funded merchant = funded("100.00");
        String key = key();
        MerchantPayoutId crashed = crash(merchant, "40.00", key);
        assertThat(stored(merchant, crashed).status()).isEqualTo(MerchantPayoutStatus.DISPATCHED);
        assertThat(provider.requestCount(PATH)).as("the crash sent nothing").isZero();

        expireTheLease(merchant, key);
        provider.succeedsWith(PATH, 200, PAID);
        MerchantPayouts.Initiated takenOver = initiate(merchant, "40.00", key);

        assertThat(takenOver.payout()).isEqualTo(crashed);
        assertThat(takenOver.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(holdStatuses(merchant)).as("no second hold").containsExactly("RELEASED");
        assertThat(provider.headerValues(PATH, SimulatedPayoutProvider.IDEMPOTENCY_KEY_HEADER))
                .as("the re-send presents the reference stored before the crash (INV-PAY-04)")
                .containsExactly(stored(merchant, crashed).reference().value());
        assertThat(count(
                        "SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?",
                        merchant.id().value()))
                .isEqualTo(1);
        assertThat(auditCount("merchant.MerchantPayoutInitiated", crashed))
                .as("the takeover converges: it initiates nothing")
                .isEqualTo(1);

        MerchantPayouts.Initiated replay = initiate(merchant, "40.00", key);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
    }

    @Test
    @DisplayName("a takeover after the sweep resolved the payout sends nothing and answers the row")
    void aTakeoverAfterTheSweepSendsNothing() throws Exception {
        Funded merchant = funded("100.00");
        String key = key();
        MerchantPayoutId crashed = crash(merchant, "40.00", key);
        queryAnswers(merchant, crashed, "paid");
        resolution(Duration.ZERO).sweep();
        assertThat(stored(merchant, crashed).status()).isEqualTo(MerchantPayoutStatus.COMPLETED);

        expireTheLease(merchant, key);
        provider.succeedsWith(PATH, 200, PAID);
        MerchantPayouts.Initiated takenOver = initiate(merchant, "40.00", key);

        assertThat(takenOver.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(provider.requestCount(PATH)).as("a resolved payout is never sent again").isZero();
        assertThat(entryLines(crashed)).hasSize(2);
    }

    @Test
    @DisplayName("never-received waits for the permit: fresh is not swept, old is failed, and no send follows")
    void neverReceivedWaitsForThePermit() throws Exception {
        Funded merchant = funded("100.00");
        String key = key();
        MerchantPayoutId crashed = crash(merchant, "40.00", key);
        queryAnswers(merchant, crashed, "unrecognised");

        assertThat(resolution(Duration.ofMinutes(10)).sweep().resolved())
                .as("a permit younger than the bound is not a candidate")
                .isZero();
        assertThat(provider.requestCount(PATH + "/" + stored(merchant, crashed).reference().value()))
                .as("not even queried: the candidate filter is its own layer")
                .isZero();
        assertThat(stored(merchant, crashed).status()).isEqualTo(MerchantPayoutStatus.DISPATCHED);

        resolution(Duration.ZERO).sweep();
        assertThat(stored(merchant, crashed).status()).isEqualTo(MerchantPayoutStatus.FAILED);
        assertThat(stored(merchant, crashed).failureReason())
                .contains(PayoutFailureReason.NEVER_RECEIVED);
        assertThat(holdStatuses(merchant)).containsExactly("RELEASED");

        expireTheLease(merchant, key);
        provider.succeedsWith(PATH, 200, PAID);
        assertThat(initiate(merchant, "40.00", key).status())
                .isEqualTo(MerchantPayoutStatus.FAILED);
        assertThat(provider.requestCount(PATH))
                .as("NEVER_RECEIVED is a claim about the future too: nothing is sent after it")
                .isZero();
    }

    @Test
    @DisplayName("the never-received bound is re-judged on the locked row: a renewed permit wins")
    void theBoundIsRejudgedUnderTheLock() throws Exception {
        Funded merchant = funded("100.00");
        MerchantPayoutId crashed = crash(merchant, "40.00", key());
        PayoutQueryAnswer unrecognised =
                PayoutQueryAnswer.unrecognised(
                        "{\"status\":\"unrecognised\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // As if a takeover renewed the permit after the sweep read its candidates: the bound
        // the sweep brings is older than the permit, so absence proves nothing yet.
        MerchantPayoutOutcomes.Applied tooSoon =
                asPlatform(
                        uow ->
                                outcomes.applyQueryAnswer(
                                        uow,
                                        payoutStore.findForUpdate(uow, merchant.id(), crashed)
                                                .orElseThrow(),
                                        unrecognised,
                                        Instant.now().minus(Duration.ofMinutes(10)),
                                        correlation()));
        assertThat(tooSoon.acting()).isFalse();
        assertThat(tooSoon.status()).isEqualTo(MerchantPayoutStatus.DISPATCHED);

        MerchantPayoutOutcomes.Applied old =
                asPlatform(
                        uow ->
                                outcomes.applyQueryAnswer(
                                        uow,
                                        payoutStore.findForUpdate(uow, merchant.id(), crashed)
                                                .orElseThrow(),
                                        unrecognised,
                                        Instant.now().plusSeconds(1),
                                        correlation()));
        assertThat(old.acting()).isTrue();
        assertThat(old.status()).isEqualTo(MerchantPayoutStatus.FAILED);
    }

    @Test
    @DisplayName("a sweep racing a takeover's renewal judges the renewed permit: the resolver's row lock orders them")
    void aSweepRacingARenewalJudgesTheRenewedPermit() throws Exception {
        Funded merchant = funded("100.00");
        MerchantPayoutId crashed = crash(merchant, "40.00", key());
        queryAnswers(merchant, crashed, "unrecognised");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection takeover = DatabaseRoles.application()) {
            takeover.setAutoCommit(false);
            // A takeover's renewal, mid-transaction: the row locked and the permit moved past any
            // bound, not yet committed - once it commits, the takeover sends.
            try (PreparedStatement renewal =
                    takeover.prepareStatement(
                            "UPDATE merchant.merchant_payout SET last_dispatched_at = now() +"
                                    + " interval '1 hour' WHERE id = ?")) {
                renewal.setObject(1, crashed.value());
                assertThat(renewal.executeUpdate()).isEqualTo(1);
            }
            Future<MerchantPayoutResolution.SweepResult> sweep =
                    pool.submit(() -> resolution(Duration.ZERO).sweep());
            // The sweep reaches the row and waits behind the renewal - on its locking read, or
            // on its transition if the read did not lock.
            awaitWaiting("merchant.merchant_payout");
            takeover.commit();
            sweep.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // theBoundIsRejudgedUnderTheLock proves the arithmetic single-threaded; this is the lock.
        assertThat(stored(merchant, crashed).status())
                .as("judged on the renewed permit: absence proves nothing yet")
                .isEqualTo(MerchantPayoutStatus.DISPATCHED);
        assertThat(holdStatuses(merchant)).containsExactly("ACTIVE");
    }

    @Test
    @DisplayName("a takeover racing the sweep's verdict sends nothing: the renewal's conditional is the permit")
    void aTakeoverRacingTheVerdictSendsNothing() throws Exception {
        Funded merchant = funded("100.00");
        String key = key();
        MerchantPayoutId crashed = crash(merchant, "40.00", key);
        expireTheLease(merchant, key);
        provider.succeedsWith(PATH, 200, PAID);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection resolver = DatabaseRoles.application()) {
            resolver.setAutoCommit(false);
            // A resolver's verdict, mid-transaction: the row locked and moved, not yet committed.
            try (PreparedStatement verdict =
                    resolver.prepareStatement(
                            "UPDATE merchant.merchant_payout SET status = 'FAILED', failure_reason"
                                    + " = 'NEVER_RECEIVED' WHERE id = ?")) {
                verdict.setObject(1, crashed.value());
                assertThat(verdict.executeUpdate()).isEqualTo(1);
            }
            // The takeover reads DISPATCHED (the verdict is uncommitted) and waits on the row to
            // renew its permit: exactly the window ADR-0057 section 4 closes.
            Future<MerchantPayouts.Initiated> takeover =
                    pool.submit(() -> initiate(merchant, "40.00", key));
            awaitWaiting("SET last_dispatched_at");
            resolver.commit();

            MerchantPayouts.Initiated answered = takeover.get(60, TimeUnit.SECONDS);
            assertThat(answered.payout()).isEqualTo(crashed);
            assertThat(answered.status())
                    .as("the row's truth, never a re-send's")
                    .isEqualTo(MerchantPayoutStatus.FAILED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(provider.requestCount(PATH))
                .as("the renewal matched no row, so nothing was sent")
                .isZero();
    }

    @Test
    @DisplayName("a refused connection on a RE-send moves nothing: the first send may have paid")
    void nothingSentOnAReSendMovesNothing() throws Exception {
        Funded merchant = funded("100.00");
        String key = key();
        MerchantPayoutId crashed = crash(merchant, "40.00", key);
        expireTheLease(merchant, key);

        MerchantPayouts.Initiated takenOver =
                initiateWith(payoutsSendingTo(unreachable(), idempotentExecutor), merchant,
                        "40.00", key);

        assertThat(takenOver.status())
                .as("never FAILED from a re-send's refused connection")
                .isEqualTo(MerchantPayoutStatus.DISPATCHED);
        assertThat(holdStatuses(merchant)).containsExactly("ACTIVE");
        assertThat(stored(merchant, crashed).failureReason()).isEmpty();
    }

    /**
     * The Phase 6 -> 7 transition's I5 finding: a merchant owed money could be closed, and then
     * nothing could pay it out - payouts refuse any merchant that is not ACTIVE, CLOSED is
     * terminal, and captures of sessions already paying kept crediting it. A close now requires the
     * payable settled: zero, no hold standing, nothing on its way (Phase 3's account close).
     */
    @Test
    @DisplayName("a merchant owed money, holding a reservation or with a payment in flight cannot"
            + " be closed, and a settled one can (the Phase 6 -> 7 transition)")
    void onlyASettledMerchantCanBeClosed() throws Exception {
        Funded owed = funded("40.00");
        assertThatThrownBy(() -> asOperator(uow -> administration.close(uow, owed.id(), "ended")))
                .isInstanceOf(com.finapp.merchant.MerchantNotSettledException.class);

        Funded inFlight = fundedWithoutDestination("0.00");
        raw(
                "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                        + " payment_method_id, credit_account_id, amount_minor, currency, scale,"
                        + " status, created_at, capture_mode) VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2,"
                        + " 'PROCESSING', now(), 'AUTOMATIC')",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                inFlight.payable().value());
        assertThatThrownBy(
                        () -> asOperator(uow -> administration.close(uow, inFlight.id(), "ended")))
                .as("a capture on its way would land on a merchant nothing can pay out")
                .isInstanceOf(com.finapp.merchant.MerchantNotSettledException.class);

        // Settled back to zero while a reservation still stands on it.
        Funded held = funded("10.00");
        asOperator(uow -> holds.place(uow, held.payable(), eur("10.00")));
        post(held, "10.00", Direction.DEBIT);
        assertThatThrownBy(() -> asOperator(uow -> administration.close(uow, held.id(), "ended")))
                .isInstanceOf(com.finapp.merchant.MerchantNotSettledException.class);

        for (Funded refused : List.of(owed, inFlight, held)) {
            assertThat(
                            count(
                                    "SELECT count(*) FROM merchant.merchant WHERE id = ?"
                                            + " AND status = 'ACTIVE'",
                                    refused.id().value()))
                    .as("a refused close writes nothing")
                    .isEqualTo(1);
        }

        Funded settled = fundedWithoutDestination("0.00");
        assertThat(
                        asOperator(uow -> administration.close(uow, settled.id(), "ended"))
                                .status())
                .isEqualTo(com.finapp.merchant.MerchantStatus.CLOSED);
    }

    /**
     * The first-send rule judged against the ROW, not the request (the Phase 6 -> 7
     * transition, found by its audits in the payout and in the refund alike): a first flight
     * stalls past its lease, a takeover renews the permit and re-sends - and may be paid - and
     * only then does the first flight's refused connection arrive. It is still about the first
     * flight's own send, which proves nothing about the takeover's. Deterministic: the wire
     * renews the permit, as the takeover would, on its own connection before answering.
     */
    @Test
    @DisplayName("a first send's refused connection moves nothing once a later permit exists - the"
            + " locked row's permit decides, not the request's")
    void aFirstSendsRefusedConnectionAfterARenewalMovesNothing() throws Exception {
        Funded merchant = funded("100.00");
        PayoutProvider renewsThenRefuses =
                new PayoutProvider() {
                    @Override
                    public com.finapp.merchant.PayoutAnswer dispatch(PayoutRequest request) {
                        try {
                            raw(
                                    "UPDATE merchant.merchant_payout SET last_dispatched_at ="
                                            + " last_dispatched_at + interval '1 second'"
                                            + " WHERE provider_idempotency_reference = ?",
                                    request.reference().value());
                        } catch (SQLException failure) {
                            throw new IllegalStateException(failure);
                        }
                        return com.finapp.merchant.PayoutAnswer.nothingSent();
                    }

                    @Override
                    public PayoutQueryAnswer query(com.finapp.merchant.PayoutReference ours) {
                        throw new IllegalStateException("unused");
                    }
                };

        MerchantPayouts.Initiated first =
                initiateWith(payoutsSendingTo(renewsThenRefuses, idempotentExecutor), merchant,
                        "40.00", key());

        assertThat(first.status())
                .as("a later permit exists, so this refused connection proves nothing")
                .isEqualTo(MerchantPayoutStatus.DISPATCHED);
        assertThat(holdStatuses(merchant)).containsExactly("ACTIVE");
    }

    // -----------------------------------------------------------------
    // The refusals that write nothing
    // -----------------------------------------------------------------

    @Test
    @DisplayName("no effective destination, a suspended merchant, a foreign currency: refused, nothing written")
    void theRefusalsWriteNothing() throws Exception {
        Funded noDestination = fundedWithoutDestination("100.00");
        assertThatThrownBy(() -> initiate(noDestination, "10.00", key()))
                .isInstanceOf(NoEffectiveDestinationException.class);

        Funded suspended = funded("100.00");
        raw("UPDATE merchant.merchant SET status = 'SUSPENDED' WHERE id = ?", suspended.id().value());
        assertThatThrownBy(() -> initiate(suspended, "10.00", key()))
                .isInstanceOf(MerchantNotTradingException.class);

        Funded euro = funded("100.00");
        assertThatThrownBy(
                        () ->
                                initiateAmount(
                                        euro, Money.of(new java.math.BigDecimal("10.00"),
                                                CurrencyCode.of("GBP")), key()))
                .isInstanceOf(PayoutCurrencyMismatchException.class);

        for (Funded merchant : List.of(noDestination, suspended, euro)) {
            assertThat(holdStatuses(merchant)).isEmpty();
            assertThat(count(
                            "SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?",
                            merchant.id().value()))
                    .isZero();
        }
    }

    // -----------------------------------------------------------------
    // The payable view (INV-MER-02, inherited from P6-TSK-010)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the payable reconciles with a paid and a FAILED payout in the picture: paidOut is its own term")
    void thePayableReconcilesWithPayouts() throws Exception {
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, PAID);
        initiate(merchant, "30.00", key());
        provider.reset();
        provider.succeedsWith(PATH, 200, "{\"status\":\"declined\"}");
        initiate(merchant, "20.00", key());

        MerchantPayable.Payable payable = payables.payablesOf(merchant.id()).get(0);
        assertThat(payable.position()).isEqualTo(eur("70.00"));
        assertThat(payable.captured()).isEqualTo(eur("100.00"));
        assertThat(payable.paidOut()).as("only the paid payout, never the failed one").isEqualTo(eur("30.00"));
        assertThat(payable.other()).isEqualTo(eur("0.00"));
        assertThat(payable.terms()).isEqualTo(payable.position());
        assertThat(positionMinor(merchant))
                .as("independent SQL over the payable's lines agrees")
                .isEqualTo(7000);
        assertThat(count(
                        "SELECT COALESCE(sum(line.amount_minor), 0) FROM ledger.journal_line line"
                                + " JOIN ledger.ledger_account account ON account.id ="
                                + " line.ledger_account_id JOIN ledger.journal_entry entry ON"
                                + " entry.id = line.entry_id WHERE account.purpose ="
                                + " 'PAYOUT_CLEARING' AND line.direction = 'CREDIT' AND"
                                + " entry.reference IN (SELECT id::text FROM"
                                + " merchant.merchant_payout WHERE merchant_id = ?)",
                        merchant.id().value()))
                .as("PAYOUT_CLEARING moved by exactly the completed payout")
                .isEqualTo(3000);
    }

    // -----------------------------------------------------------------
    // V007, for every writer
    // -----------------------------------------------------------------

    @Test
    @DisplayName("V007 refuses what the domain refuses, for raw SQL")
    void theSchemaRefusesWhatTheDomainRefuses() throws Exception {
        Funded merchant = funded("100.00");
        Funded other = funded("100.00");
        provider.succeedsWith(PATH, 200, "{\"status\":\"declined\"}");
        MerchantPayoutId failed = initiate(merchant, "10.00", key()).payout();

        // Born DISPATCHED, to its own merchant's EFFECTIVE destination. Another merchant's
        // destination is refused by the BEFORE INSERT trigger first (23514) - triggers run
        // before the composite foreign key is checked, which stands behind it as the backstop.
        assertSqlState("23514", () -> insertRaw(merchant, merchant.destination(), "COMPLETED"));
        assertSqlState("23514", () -> insertRaw(merchant, other.destination(), "DISPATCHED"));
        UUID proposed = proposedDestination(merchant);
        assertSqlState("23514", () -> insertRaw(merchant, proposed, "DISPATCHED"));
        // A terminal payout never moves, and a resolved one is never re-sent.
        assertThatThrownBy(
                        () ->
                                raw(
                                        "UPDATE merchant.merchant_payout SET status = 'UNKNOWN',"
                                                + " failure_reason = NULL WHERE id = ?",
                                        failed.value()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                raw(
                                        "UPDATE merchant.merchant_payout SET last_dispatched_at ="
                                                + " now() + interval '1 hour' WHERE id = ?",
                                        failed.value()))
                .isInstanceOf(SQLException.class);
        // The dispatch is frozen: not even updatable by the app role.
        assertSqlState(
                "42501",
                () ->
                        raw(
                                "UPDATE merchant.merchant_payout SET amount_minor = 1 WHERE id = ?",
                                failed.value()));
        // No payout ever disappears, and no evidence is ever edited.
        assertSqlState(
                "42501", () -> raw("DELETE FROM merchant.merchant_payout WHERE id = ?", failed.value()));
        assertSqlState(
                "42501",
                () ->
                        raw(
                                "UPDATE merchant.payout_evidence SET key_version = 2 WHERE"
                                        + " payout_id = ?",
                                failed.value()));
        assertThat(stored(merchant, failed).status()).isEqualTo(MerchantPayoutStatus.FAILED);

        // The one backward move no earlier rule stands in front of: an UNKNOWN payout carries
        // no recorded outcome to freeze, so only the edge rule keeps it from DISPATCHED.
        provider.reset();
        provider.neverResponds(PATH);
        MerchantPayoutId unknown = initiate(merchant, "10.00", key()).payout();
        assertThat(stored(merchant, unknown).status()).isEqualTo(MerchantPayoutStatus.UNKNOWN);
        assertThatThrownBy(
                        () ->
                                raw(
                                        "UPDATE merchant.merchant_payout SET status = 'DISPATCHED'"
                                                + " WHERE id = ?",
                                        unknown.value()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("moves only along the machine");
    }

    @Test
    @DisplayName("a resolution whose hold is no longer standing refuses: nothing posts, the payout stays")
    void aResolutionWithoutItsHoldRefuses() throws Exception {
        Funded merchant = funded("100.00");
        MerchantPayoutId crashed = crash(merchant, "40.00", key());
        HoldId hold = stored(merchant, crashed).holdId();
        // Another writer releases the payout's hold behind its back - the defect this belt
        // exists to surface: the money is no longer reserved, so posting now would move it twice.
        asOperator(uow -> holds.release(uow, hold));
        queryAnswers(merchant, crashed, "paid");

        MerchantPayoutResolution.SweepResult swept = resolution(Duration.ZERO).sweep();

        assertThat(swept.failedRows()).as("refused loudly, never guessed at").isPositive();
        assertThat(stored(merchant, crashed).status())
                .as("the transition rolled back with the refusal")
                .isEqualTo(MerchantPayoutStatus.DISPATCHED);
        assertThat(entryLines(crashed)).as("nothing posted").isEmpty();
        assertThat(count(
                        "SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?",
                        crashed.value()))
                .as("only the dispatch's own fact")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // The serialisation points (ADR-0057 sections 7 and 11)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a supersession racing a dispatch waits for it: the payout commits to the destination effective at its commit")
    void aSupersessionWaitsForTheDispatch() throws Exception {
        Funded merchant = funded("100.00");
        UUID next = approvedAndDue(merchant);
        provider.succeedsWith(PATH, 200, PAID);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            lockThePayable(blocker, merchant);
            Future<MerchantPayouts.Initiated> dispatch =
                    pool.submit(() -> initiate(merchant, "10.00", key()));
            // The dispatch holds the effective destination FOR SHARE and waits on the payable.
            awaitWaiting("FROM ledger.ledger_account");
            Future<?> supersession = pool.submit(() -> effectuation().sweep());
            // The supersession's FOR UPDATE on that destination waits behind the share lock.
            awaitWaiting("status = 'EFFECTIVE' FOR UPDATE");
            blocker.rollback();

            MerchantPayouts.Initiated paid = dispatch.get(60, TimeUnit.SECONDS);
            supersession.get(60, TimeUnit.SECONDS);
            assertThat(paid.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
            assertThat(stored(merchant, paid.payout()).destinationId().value())
                    .as("bound to the destination still effective when the payout committed")
                    .isEqualTo(merchant.destination());
            assertThat(destinationStatus(merchant.destination())).isEqualTo("SUPERSEDED");
            assertThat(destinationStatus(next)).isEqualTo("EFFECTIVE");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a payout dispatched during the cooling-off is bound to the prior destination;"
            + " once the change takes effect, the next is bound to the new one")
    void aPayoutDuringTheCoolingOffGoesToThePriorDestination() throws Exception {
        // P6-DOC-001: PHASE_GATES asks for the cooling-off gate proven "by a dispatch during the
        // window using the prior destination", and the proof it had
        // (PayoutDestinationDatabaseTest#aDispatchDuringTheCoolingOffUsesThePriorDestination)
        // only READ the effective destination - no payout was ever dispatched while a change
        // cooled off. This one dispatches for real, on both sides of the deadline.
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, PAID);

        // The change through the real four-eyes flow: one operator proposes, a SECOND approves
        // (asOperator is a fresh person on every call), and the approval pins the deadline the
        // context's cooling-off gives it.
        String reference = "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PayoutDestinationTokenisation.TokenisedDestination account =
                new PayoutDestinationTokenisation.TokenisedDestination(
                        PayoutDestinationReference.of(reference), "4000");
        PayoutDestinationId next =
                asOperator(
                        uow ->
                                destinationChanges
                                        .propose(
                                                uow,
                                                new PayoutDestinations.ProposeCommand(
                                                        UUID.randomUUID().toString(),
                                                        merchant.id(),
                                                        account,
                                                        "the merchant's new account"))
                                        .destinationId());
        PayoutDestinations.Approval approval =
                asOperator(uow -> destinationChanges.approve(uow, merchant.id(), next, "verified"));
        assertThat(approval).isInstanceOf(PayoutDestinations.Approved.class);
        Instant deadline =
                ((PayoutDestinations.Approved) approval)
                        .destination()
                        .coolingOffUntil()
                        .orElseThrow();

        // DURING THE WINDOW, after a sweep an hour into it: what keeps the prior destination in
        // place is the pinned deadline, not a sweep that has not run yet.
        effectuationAt(Duration.ofHours(1)).sweep();
        assertThat(destinationStatus(next.value())).isEqualTo("APPROVED");
        MerchantPayouts.Initiated during = initiate(merchant, "10.00", key());
        assertThat(during.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(stored(merchant, during.payout()).destinationId().value())
                .as("dispatched while the change cooled off: bound to the PRIOR destination")
                .isEqualTo(merchant.destination());

        // PAST THE DEADLINE the platform effects the change and supersedes the prior one.
        effectuationAt(Duration.between(Instant.now(CLOCK), deadline).plusMinutes(1)).sweep();
        assertThat(destinationStatus(next.value())).isEqualTo("EFFECTIVE");
        assertThat(destinationStatus(merchant.destination())).isEqualTo("SUPERSEDED");
        MerchantPayouts.Initiated after = initiate(merchant, "10.00", key());
        assertThat(after.status()).isEqualTo(MerchantPayoutStatus.COMPLETED);
        assertThat(stored(merchant, after.payout()).destinationId())
                .as("dispatched once the change took effect: bound to the NEW destination")
                .isEqualTo(next);
        assertThat(stored(merchant, during.payout()).destinationId().value())
                .as("and the earlier payout still records where it went: the binding is frozen")
                .isEqualTo(merchant.destination());
    }

    @Test
    @DisplayName("a suspension racing a dispatch waits for it: the merchant row is the serialisation point")
    void aSuspensionWaitsForTheDispatch() throws Exception {
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, PAID);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            lockThePayable(blocker, merchant);
            Future<MerchantPayouts.Initiated> dispatch =
                    pool.submit(() -> initiate(merchant, "10.00", key()));
            awaitWaiting("FROM ledger.ledger_account");
            Future<?> suspension =
                    pool.submit(
                            () -> {
                                raw(
                                        "UPDATE merchant.merchant SET status = 'SUSPENDED' WHERE"
                                                + " id = ?",
                                        merchant.id().value());
                                return null;
                            });
            // The suspension waits on the merchant row the dispatch locked.
            awaitWaiting("UPDATE merchant.merchant SET status");
            blocker.rollback();

            assertThat(dispatch.get(60, TimeUnit.SECONDS).status())
                    .as("ordered before the suspension, so dispatched while trading")
                    .isEqualTo(MerchantPayoutStatus.COMPLETED);
            suspension.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThatThrownBy(() -> initiate(merchant, "10.00", key()))
                .as("and once suspended, nothing more is dispatched")
                .isInstanceOf(MerchantNotTradingException.class);
    }

    // -----------------------------------------------------------------
    // Nothing sensitive leaves (INV-AUD-02, PHASE_6_PLAN section 10)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("nothing sensitive leaves: facts carry identifiers and names, records no reference, evidence is ciphertext")
    void nothingSensitiveLeaves() throws Exception {
        Funded merchant = funded("100.00");
        provider.succeedsWith(PATH, 200, PAID);
        MerchantPayoutId paid = initiate(merchant, "37.19", key()).payout();
        provider.reset();
        provider.succeedsWith(PATH, 200, "{\"status\":\"declined\"}");
        MerchantPayoutId declined = initiate(merchant, "12.34", key()).payout();
        String reference = destinationReference(merchant);
        java.util.regex.Pattern fieldName = java.util.regex.Pattern.compile("\"([A-Za-z]+)\"\\s*:");

        for (MerchantPayoutId payout : List.of(paid, declined)) {
            List<String> payloads =
                    strings(
                            "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event"
                                    + " WHERE aggregate_id = ?",
                            payout.value());
            assertThat(payloads).as("the dispatch's fact and the outcome's").hasSize(2);
            for (String payload : payloads) {
                List<String> fields =
                        fieldName.matcher(payload).results().map(found -> found.group(1)).toList();
                assertThat(fields)
                        .as("identifiers and enumerated names only, never an amount: %s", payload)
                        .isSubsetOf("status", "merchantId", "destinationId", "failureReason");
                assertThat(payload).doesNotContain(reference);
            }
            for (String summary :
                    strings(
                            "SELECT change_summary FROM platform.audit_record WHERE target_id = ?",
                            payout.value().toString())) {
                assertThat(summary).as("never the destination's reference").doesNotContain(reference);
            }
            List<byte[]> retained =
                    byteArrays(
                            "SELECT content_ciphertext FROM merchant.payout_evidence WHERE"
                                    + " payout_id = ?",
                            payout.value());
            assertThat(retained).isNotEmpty();
            for (byte[] ciphertext : retained) {
                assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.ISO_8859_1))
                        .as("the provider's answer is ciphertext at rest")
                        .doesNotContain("\"status\"");
            }
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /** A merchant, its payable, and an effective destination. */
    private record Funded(MerchantId id, LedgerAccountId payable, UUID destination) {}

    private Funded funded(String amount) throws Exception {
        Funded bare = fundedWithoutDestination(amount);
        UUID destination = IDS.next();
        raw(
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until, effective_at) VALUES"
                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '4 days',"
                        + " 'fixture', 'fixture-b', now() - interval '4 days', now() - interval"
                        + " '1 day', now() - interval '1 hour')",
                destination,
                bare.id().value(),
                "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return new Funded(bare.id(), bare.payable(), destination);
    }

    private Funded fundedWithoutDestination(String amount) throws Exception {
        MerchantId merchant = MerchantId.next(IDS);
        // The timestamps come from the SAME clock the domain transitions the merchant with -
        // production stamps createdAt from the injected Clock, never the database's now(). Two
        // clocks here made close() stamp a statusChangedAt preceding createdAt whenever the
        // database's clock ran ahead of the JVM's (found at P7-TSK-014's gate: the Docker VM's
        // clock measured gaining ~55 ms a second between step-backs), and the domain rightly
        // refused it.
        OffsetDateTime created =
                OffsetDateTime.ofInstant(
                        Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        raw(
                "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                        + " settlement_currency, status, created_at, status_changed_at) VALUES"
                        + " (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', ?, ?)",
                merchant.value(),
                UUID.randomUUID(),
                created,
                created);
        LedgerAccountId payable =
                asOperator(
                        uow ->
                                ledgerAccountStore
                                        .createOrConverge(
                                                uow,
                                                LedgerAccount.owned(
                                                        IDS,
                                                        CLOCK,
                                                        AccountType.LIABILITY,
                                                        AccountPurpose.MERCHANT_PAYABLE,
                                                        EUR,
                                                        merchant.value()))
                                        .account()
                                        .id());
        Funded funded = new Funded(merchant, payable, null);
        if (!eur(amount).isZero()) {
            post(funded, amount, Direction.CREDIT);
        }
        return funded;
    }

    /**
     * A book-rail sale ({@code CREDIT}: DR a payer's wallet / CR payable) or a book refund
     * ({@code DEBIT}: DR payable / CR the wallet), so the payable view reads it as captured or
     * refunded - the payer's wallet is the book rail's sale counterparty, in the same column of
     * {@code MerchantPayable}'s table as a clearing (P7-TSK-011).
     *
     * <p>The counterparty was {@code SETTLEMENT_CLEARING} until the position proof
     * (P8-TSK-007, {@code INV-REC-06}) made every line on a reconciled position answer to an
     * expectation. A real card capture opens its own (P8-TSK-005); this fixture opened none, and
     * its entry is no operation the opening backfill could ever adopt, so every line it left in
     * the shared container stood unexplained - the opening suite's and the multi-rail storm's
     * proofs failed whenever this suite ran first. The book rail settles nothing externally
     * ({@code SettlementModel.NONE}), so its sale touches no reconciled position and is owed no
     * expectation: the one capture shape that is honest without one. {@code FEE_REVENUE},
     * P8-TSK-006's substitute, would not do here - the view reads it as {@code other}, and
     * {@code thePayableReconcilesWithPayouts} pins {@code captured} and {@code other}.
     *
     * <p>The payer is a fresh wallet per entry, left below zero by a sale: a receivable from
     * the customer, a legal ledger state (ADR-0061 section 5) that no other suite reads - every
     * wallet read elsewhere is scoped to its own owner.
     */
    private void post(Funded merchant, String amount, Direction payableSide) throws Exception {
        asOperator(
                uow -> {
                    LedgerAccount payer =
                            ledgerAccountStore
                                    .createOrConverge(
                                            uow,
                                            LedgerAccount.owned(
                                                    IDS,
                                                    CLOCK,
                                                    AccountType.LIABILITY,
                                                    AccountPurpose.CUSTOMER_WALLET,
                                                    EUR,
                                                    UUID.randomUUID()))
                                    .account();
                    LocalDate today = LocalDate.now(CLOCK.withZone(ZoneOffset.UTC));
                    Direction payerSide =
                            payableSide == Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT;
                    UUID reference = UUID.randomUUID();
                    return postings.post(
                            uow,
                            new PostingCommand(
                                    "payout-test-fixture:" + reference,
                                    today,
                                    today,
                                    reference.toString(),
                                    List.of(
                                            new JournalLine(payer.id(), payerSide, eur(amount)),
                                            new JournalLine(
                                                    merchant.payable(), payableSide, eur(amount)))));
                });
    }

    private void hold(Funded merchant, String amount) throws Exception {
        asOperator(uow -> holds.place(uow, merchant.payable(), eur(amount)));
    }

    private UUID proposedDestination(Funded merchant) throws SQLException {
        UUID id = IDS.next();
        raw(
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason)"
                        + " VALUES (?, ?, 'pdr_proposedone', '3000', 'PROPOSED', 'fixture',"
                        + " now(), 'fixture')",
                id,
                merchant.id().value());
        return id;
    }

    private static void insertRaw(Funded merchant, UUID destination, String status)
            throws SQLException {
        raw(
                "INSERT INTO merchant.merchant_payout (id, merchant_id, amount_minor, currency,"
                        + " scale, destination_id, hold_reference, provider_idempotency_reference,"
                        + " provider_reference, status, failure_reason, dispatch_key, requested_by,"
                        + " requested_by_type, reason, created_at, last_dispatched_at) VALUES"
                        + " (?, ?, 100, 'EUR', 2, ?, ?, ?, ?, ?, NULL, ?, 'raw', 'MERCHANT', NULL,"
                        + " now(), now())",
                IDS.next(),
                merchant.id().value(),
                destination,
                IDS.next(),
                "pyo-" + UUID.randomUUID(),
                status.equals("COMPLETED") ? "po_raw" : null,
                status,
                "raw-" + UUID.randomUUID());
    }

    // -----------------------------------------------------------------
    // Driving the command
    // -----------------------------------------------------------------

    private MerchantPayouts.Initiated initiate(Funded merchant, String amount, String key)
            throws Exception {
        return initiateWith(payouts, merchant, amount, key);
    }

    private MerchantPayouts.Initiated initiateAmount(Funded merchant, Money amount, String key)
            throws Exception {
        return asMerchant(
                merchant,
                () ->
                        payouts.initiate(
                                new MerchantPayouts.InitiateCommand(
                                        merchant.id(), amount, key, Optional.empty(),
                                        Optional.empty())));
    }

    private MerchantPayouts.Initiated initiateWith(
            MerchantPayouts command, Funded merchant, String amount, String key) throws Exception {
        return asMerchant(
                merchant,
                () ->
                        command.initiate(
                                new MerchantPayouts.InitiateCommand(
                                        merchant.id(), eur(amount), key, Optional.empty(),
                                        Optional.empty())));
    }

    /**
     * The crash between the two transactions: the dispatch commits, then the wire call dies
     * before anything is sent, leaving the payout DISPATCHED and its claim IN_PROGRESS.
     */
    private MerchantPayoutId crash(Funded merchant, String amount, String key) throws Exception {
        PayoutProvider crashing =
                new PayoutProvider() {
                    @Override
                    public com.finapp.merchant.PayoutAnswer dispatch(PayoutRequest request) {
                        throw new IllegalStateException("the instance died mid-call");
                    }

                    @Override
                    public PayoutQueryAnswer query(com.finapp.merchant.PayoutReference ours) {
                        throw new IllegalStateException("unused");
                    }
                };
        assertThatThrownBy(
                        () ->
                                initiateWith(
                                        payoutsSendingTo(crashing, idempotentExecutor),
                                        merchant,
                                        amount,
                                        key))
                .isInstanceOf(IllegalStateException.class);
        try (Connection app = DatabaseRoles.application()) {
            return payoutStore.findByDispatchKey(app, merchant.id(), key).orElseThrow().id();
        }
    }

    private void expireTheLease(Funded merchant, String key) throws SQLException {
        raw(
                "UPDATE platform.idempotency_record SET lease_expires_at = now() - interval"
                        + " '1 minute' WHERE scope = ? AND idempotency_key = ? AND state ="
                        + " 'IN_PROGRESS'",
                MerchantPayouts.IDEMPOTENCY_SCOPE_PREFIX + merchant.id().value(),
                key);
    }

    private MerchantPayouts payoutsSendingTo(PayoutProvider wire, IdempotentExecutor executor) {
        return new MerchantPayouts(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                executor,
                new com.finapp.merchant.JdbcMerchantStore(),
                destinations,
                ledgerAccountStore,
                holds,
                payoutStore,
                wire,
                outcomes,
                evidence,
                auditWriter,
                IDS,
                CLOCK);
    }

    /**
     * "Due now" is the smallest positive bound, never zero: since `P6-DOC-001` the sweep refuses
     * a bound that would let it conclude NEVER_RECEIVED of a send still in flight. A microsecond
     * is below anything these tests can observe between a dispatch and its sweep.
     */
    private static final Duration DUE_NOW = Duration.ofNanos(1_000);

    private MerchantPayoutResolution resolution(Duration dispatchedAge) {
        return new MerchantPayoutResolution(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                payoutStore,
                new SimulatedPayoutProvider(
                        URI.create(provider.baseUrl()), Duration.ofSeconds(2), new byte[32]),
                outcomes,
                evidence,
                IDS,
                CLOCK,
                dispatchedAge.isZero() ? DUE_NOW : dispatchedAge,
                Duration.ZERO,
                1000);
    }

    private static PayoutProvider unreachable() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        return new SimulatedPayoutProvider(
                URI.create("http://127.0.0.1:" + closed), Duration.ofSeconds(2), new byte[32]);
    }

    private void queryAnswers(Funded merchant, MerchantPayoutId payout, String word)
            throws Exception {
        String reference = stored(merchant, payout).reference().value();
        provider.succeedsWith(
                PATH + "/" + reference,
                200,
                word.equals("paid")
                        ? "{\"status\":\"paid\",\"reference\":\"po-q-" + reference + "\"}"
                        : "{\"status\":\"" + word + "\"}");
    }

    /** An APPROVED change whose cooling-off already elapsed: the next sweep effects it. */
    private UUID approvedAndDue(Funded merchant) throws SQLException {
        UUID id = IDS.next();
        raw(
                "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until) VALUES (?, ?, ?, '4000',"
                        + " 'APPROVED', 'fixture-a', now() - interval '5 days', 'fixture',"
                        + " 'fixture-b', now() - interval '4 days', now() - interval '1 hour')",
                id,
                merchant.id().value(),
                "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return id;
    }

    private com.finapp.merchant.PayoutDestinationEffectuation effectuation() {
        return new com.finapp.merchant.PayoutDestinationEffectuation(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                destinations,
                auditWriter,
                IDS,
                CLOCK,
                1000);
    }

    /**
     * The same sweep on a clock moved forward by {@code offset} - the
     * {@code PayoutDestinationDatabaseTest} shape, so a cooling-off is crossed, never waited for.
     */
    private com.finapp.merchant.PayoutDestinationEffectuation effectuationAt(Duration offset) {
        return new com.finapp.merchant.PayoutDestinationEffectuation(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                destinations,
                auditWriter,
                IDS,
                Clock.offset(CLOCK, offset),
                1000);
    }

    private static void lockThePayable(Connection blocker, Funded merchant) throws SQLException {
        try (PreparedStatement lock =
                blocker.prepareStatement(
                        "SELECT id FROM ledger.ledger_account WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, merchant.payable().value());
            lock.executeQuery().close();
        }
    }

    /**
     * Waits until some session is blocked on a lock while running a statement containing
     * {@code fragment} - the deterministic way to know a racer reached its lock, never a sleep
     * standing in for one.
     */
    private static void awaitWaiting(String fragment) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (count(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                    + " AND query LIKE ?",
                            "%" + fragment + "%")
                    > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no session waited on a lock running: " + fragment);
    }

    private static String destinationStatus(UUID destination) throws SQLException {
        return strings("SELECT status FROM merchant.payout_destination WHERE id = ?", destination)
                .get(0);
    }

    private static String destinationReference(Funded merchant) throws SQLException {
        return strings(
                        "SELECT destination_reference FROM merchant.payout_destination WHERE id = ?",
                        merchant.destination())
                .get(0);
    }

    private static List<byte[]> byteArrays(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            List<byte[]> values = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getBytes(1));
                }
            }
            return values;
        }
    }

    // -----------------------------------------------------------------
    // Scopes
    // -----------------------------------------------------------------

    private <R> R asMerchant(Funded merchant, Supplier<R> work) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(correlation());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(merchant.id().value().toString(), ActorType.MERCHANT))) {
            return work.get();
        }
    }

    private <R> R asOperator(Function<Connection, R> work) throws Exception {
        return committed(new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER), work);
    }

    private <R> R asPlatform(Function<Connection, R> work) throws Exception {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(correlation());
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    private <R> R committed(Actor actor, Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(correlation());
                SecurityContext.Scope acting = SecurityContext.enter(actor);
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                R result = work.apply(app);
                app.commit();
                return result;
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    private static Correlation correlation() {
        return Correlation.startingWith(CorrelationId.generate(IDS));
    }

    // -----------------------------------------------------------------
    // Counters - in the tables, never inferred
    // -----------------------------------------------------------------

    private MerchantPayout stored(Funded merchant, MerchantPayoutId payout) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            return payoutStore.find(app, merchant.id(), payout).orElseThrow();
        }
    }

    /** The position proof in one {@code REPEATABLE READ} snapshot, as its callers run it. */
    private PositionProof.Report sweep() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                PositionProof.Report report = positionProof.sweep(app);
                app.commit();
                return report;
            } catch (RuntimeException failure) {
                app.rollback();
                throw failure;
            }
        }
    }

    private static List<String> holdStatuses(Funded merchant) throws SQLException {
        return strings(
                "SELECT status FROM ledger.hold WHERE ledger_account_id = ? ORDER BY placed_at",
                merchant.payable().value());
    }

    private static List<String> entryLines(MerchantPayoutId payout) throws SQLException {
        return strings(
                "SELECT line.direction || ':' || account.purpose || ':' || line.amount_minor"
                        + " FROM ledger.journal_line line JOIN ledger.journal_entry entry ON"
                        + " entry.id = line.entry_id JOIN ledger.ledger_account account ON"
                        + " account.id = line.ledger_account_id WHERE entry.idempotency_scope = ?",
                "ledger.post:" + MerchantPayoutOutcomes.POSTING_KEY_PREFIX + payout.value());
    }

    private static long positionMinor(Funded merchant) throws SQLException {
        return count(
                "SELECT COALESCE(sum(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE"
                        + " -amount_minor END), 0) FROM ledger.journal_line WHERE"
                        + " ledger_account_id = ?",
                merchant.payable().value());
    }

    private static long outboxCount(String type, MerchantPayoutId payout) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.outbox_event WHERE event_type = ? AND"
                        + " aggregate_id = ?",
                type,
                payout.value());
    }

    private static long auditCount(String operation, MerchantPayoutId payout)
            throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?",
                operation,
                payout.value().toString());
    }

    private static Money eur(String amount) {
        return Money.of(new java.math.BigDecimal(amount), EUR);
    }

    private static String key() {
        return "pay-" + UUID.randomUUID();
    }

    private static List<Object> race(List<Callable<Object>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> racer : racers) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return racer.call();
                                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertSqlState(String state, ThrowingRunnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(state));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void raw(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static List<String> strings(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
        }
    }
}
