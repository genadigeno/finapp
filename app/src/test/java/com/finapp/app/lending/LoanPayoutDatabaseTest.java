package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanPayoutSteps;
import com.finapp.lending.LoanPayouts;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.WithdrawalResolution;
import com.finapp.payments.WithdrawalUnfundedException;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * The disbursement to an external bank account end to end (`P11-TSK-015`; {@code PHASE_11_PLAN.md} sections 7.1 T-dis /
 * T-pyo / T-pyr, 7.3 (d), 7.4, 12.4, {@code LENDING_LIFECYCLES.md} section 4.7; ADR-0097; {@code INV-LND-07},
 * {@code INV-RAIL-02}, {@code INV-RAIL-03}, {@code INV-PAY-01}, {@code INV-PAY-04}, {@code INV-LIFE-03}): an offer
 * accepted over HTTP naming the borrower's bank account registered through the real grant exchange, disbursed by the real
 * sweep into the borrower's wallet with the hold, and paid out by the real payout sweep through payments' REAL system
 * withdrawal over the REAL {@link SimulatedInstantSchemeAdapter} against the stubbed scheme - credit's, accounts' and
 * payments' real ports throughout. Scenario 1's payout half (ten dispatchers, one withdrawal) and scenario 5 (the lost
 * response held, concluded only by payments' inquiry and never re-sent) are counted from the rows and the wire.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}, X-TSK-016): the suite brings credit's seeded
 * policies into force, activates the lending terms and plants EUR lending capital by a posting from {@code CASH_AT_BANK}
 * - the fixture's, never a path the platform has.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the disbursement to an external bank account end to end (P11-TSK-015)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LoanPayoutDatabaseTest {

    private static final String OFFERS = "/v1/me/loan-offers";
    private static final String LOANS = "/v1/me/loans";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Duration AT_ONCE = Duration.ofMillis(1);

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination origination;
    @Autowired private LoanOfferMetrics offerMetrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private IdGenerator ids;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanDisbursements disbursements;
    @Autowired private LoanDisbursementMetrics disbursementMetrics;
    @Autowired private LoanPayoutSteps payoutSteps;
    @Autowired private LoanPayoutMetrics payoutMetrics;
    @Autowired private LoanPayouts loanPayouts;
    @Autowired private WithdrawalResolution resolution;
    @Autowired private Withdrawals withdrawals;

    private LoanApplicationClient client;
    private CreditFixtures customers;
    private LoanOriginationSchedule originationSweep;
    private LoanDisbursementSchedule disbursementSweep;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
        // The inquiry sweep's candidacy bounds, tiny so a lost answer is inquired NOW (WithdrawalDatabaseTest's).
        registry.add("finapp.payments.withdrawal.sweeper.dispatched-age", () -> "PT0.05S");
        registry.add("finapp.payments.withdrawal.sweeper.unknown-age", () -> "PT0.05S");
        registry.add("finapp.payments.withdrawal.sweeper.margin", () -> "PT1S");
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @BeforeEach
    void creditTermsCapitalAndTheScheme() {
        provider.reset();
        client = new LoanApplicationClient(port);
        customers = new CreditFixtures(port);
        CreditFixtures.seedsInForce();
        for (LoanProduct product : LoanProduct.values()) {
            lendingTransactions.inTransaction(uow -> {
                if (termsStore.active(uow, product).isEmpty()) {
                    var proposed = termsAdministration.propose(uow, LoanApplicationDatabaseTest.terms(product),
                            "the suite's terms", employee(), CorrelationId.generate(ids));
                    termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
                }
                return null;
            });
        }
        originationSweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics,
                Duration.ofSeconds(5), 50, Duration.ofHours(1));
        disbursementSweep = new LoanDisbursementSchedule(disbursements, lendingTransactions, disbursementMetrics,
                Duration.ofSeconds(5), 100, Duration.ofMinutes(1));
        Money free = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom();
        if (free.minorUnits() < 50_000_00) {
            plantCapital(Money.ofMinorUnits(100_000_00, EUR));
        }
    }

    // ------------------------------------------------------------------ scenario 1, the payout half

    @Test
    @DisplayName("scenario 1 - ten dispatchers, one withdrawal: ten payout sweepers racing make one system withdrawal,"
            + " one send of our reference, one wallet-withdrawal entry, the payout PAID_OUT; and with the claim's permit"
            + " lapsing at once, ten concurrent dispatches of one instruction and then ten re-driving sweepers still"
            + " make one withdrawal and one send - payments' key, not lending's claim, is the last arbiter")
    void tenDispatchersOneWithdrawal() throws Exception {
        schemeAccepts();
        Disbursed loan = disbursedExternally("3000.00");
        LoanPayoutSchedule racer = payoutSweep(Duration.ofMinutes(1));
        race(10, () -> racer.sweepOnce());
        assertOneWithdrawalAndOneSend(loan);
        assertThat(payoutStatus(loan)).isEqualTo("PAID_OUT");

        Disbursed contended = disbursedExternally("2900.00");
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            lendingTransactions.inTransaction(uow -> payoutSteps.claim(uow, 100, AT_ONCE));
        }
        assertThat(payoutStatus(contended)).isEqualTo("DISPATCHING");
        LoanPayouts.Instruction instruction = instructionOf(contended);
        List<LoanPayouts.Dispatch> answers = race(10, () -> asSystem(instruction.correlation(),
                () -> loanPayouts.dispatch(instruction)));
        String withdrawal = scalar("SELECT id::text FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + contended.loanId());
        assertThat(answers).as("every concurrent dispatch answers the one withdrawal, or no answer now")
                .allSatisfy(answer -> assertThat(answer).isIn(new LoanPayouts.Dispatch.Accepted(UUID.fromString(
                        withdrawal)), new LoanPayouts.Dispatch.NotNow()))
                .contains(new LoanPayouts.Dispatch.Accepted(UUID.fromString(withdrawal)));
        LoanPayoutSchedule reDriver = payoutSweep(AT_ONCE);
        race(10, () -> reDriver.sweepOnce());
        reDriver.sweepOnce();
        assertThat(payoutStatus(contended)).isEqualTo("PAID_OUT");
        assertThat(scalar("SELECT withdrawal_id::text FROM lending.loan_payout WHERE loan_id = ?::uuid",
                contended.loanId())).isEqualTo(withdrawal);
        assertOneWithdrawalAndOneSend(contended);
    }

    private static void assertOneWithdrawalAndOneSend(Disbursed loan) throws Exception {
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + loan.loanId())).as("one withdrawal").isEqualTo(1);
        String withdrawal = scalar("SELECT id::text FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + loan.loanId());
        String reference = scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE id = ?::uuid", withdrawal);
        assertThat(sends(reference)).as("one send of our reference (INV-PAY-04)").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:wallet-withdrawal:" + withdrawal)).as("one entry DR wallet / CR clearing").isEqualTo(1);
    }

    // ------------------------------------------------------------------ scenario 5

    @Test
    @DisplayName("scenario 5 - a lost response is held, then concluded by inquiry and never re-sent: payments UNKNOWN with"
            + " the hold standing, the payout DISPATCHED however often swept, no interest; payments' inquiry concludes"
            + " COMPLETED, the payout PAID_OUT, the accrual start the business date of payments' conclusion; one send,"
            + " one entry")
    void aLostResponseIsHeldThenConcludedByInquiryAndNeverResent() throws Exception {
        Disbursed loan = disbursedExternally("2600.00");
        provider.receivesTheRequestThenLosesTheResponse(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
        payoutSweep(AT_ONCE).sweepOnce();
        String withdrawal = scalar("SELECT withdrawal_id::text FROM lending.loan_payout WHERE loan_id = ?::uuid",
                loan.loanId());
        assertThat(scalar("SELECT status FROM payments.withdrawal WHERE id = ?::uuid", withdrawal)).isEqualTo("UNKNOWN");
        String reference = scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE id = ?::uuid", withdrawal);
        for (int round = 0; round < 4; round++) {
            Thread.sleep(20);
            payoutSweep(AT_ONCE).sweepOnce();
            assertThat(payoutStatus(loan)).isEqualTo("DISPATCHED");
        }
        assertThat(sends(reference)).as("never re-sent while UNKNOWN (INV-RAIL-02)").isEqualTo(1);
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN lending.loan_payout p ON p.hold_id = h.id"
                + " WHERE p.loan_id = ?::uuid", loan.loanId())).as("held (INV-LIFE-03)").isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?::uuid", loan.loanId())).isZero();
        assertThat(client.get(LOANS + "/" + loan.loanId(), loan.customer().token()).body())
                .contains("\"payoutStatus\":\"DISPATCHED\"");

        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + reference, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-lost-" + UUID.randomUUID() + "\",\"cycle\":\"C9\"}");
        Thread.sleep(80);
        resolution.sweep();
        assertThat(scalar("SELECT status FROM payments.withdrawal WHERE id = ?::uuid", withdrawal)).isEqualTo("COMPLETED");
        payoutSweep(AT_ONCE).sweepOnce();
        assertThat(payoutStatus(loan)).isEqualTo("PAID_OUT");
        assertAccrualFromPaymentsConclusion(loan);
        assertThat(sends(reference)).as("concluded by inquiry, still one send").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:wallet-withdrawal:" + withdrawal)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the outcomes

    @Test
    @DisplayName("interest starts at the terminal outcome: none while the payout is PENDING; the payout PAID_OUT and the"
            + " accrual start the business date of payments' conclusion, schedule v1 from it; the loan read shows the"
            + " payout status and never the destination")
    void interestStartsAtTheTerminalOutcome() throws Exception {
        schemeAccepts();
        Disbursed loan = disbursedExternally("2400.00");
        assertThat(count("SELECT count(*) FROM lending.accrual_start WHERE loan_id = ?::uuid", loan.loanId())).isZero();
        assertThat(client.get(LOANS + "/" + loan.loanId() + "/schedule", loan.customer().token()).statusCode())
                .as("no schedule until the payout concludes").isEqualTo(404);
        HttpResponse<String> pending = client.get(LOANS + "/" + loan.loanId(), loan.customer().token());
        assertThat(pending.body()).contains("\"status\":\"ACTIVE\"", "\"payoutStatus\":\"PENDING\"",
                "\"principalNotDue\":\"2400.00\"");
        double paidBefore = paid();
        payoutSweep(AT_ONCE).sweepOnce();
        assertThat(payoutStatus(loan)).isEqualTo("PAID_OUT");
        assertThat(paid()).as("counted once, after the commit").isEqualTo(paidBefore + 1);
        assertAccrualFromPaymentsConclusion(loan);
        HttpResponse<String> schedule = client.get(LOANS + "/" + loan.loanId() + "/schedule", loan.customer().token());
        assertThat(schedule.statusCode()).isEqualTo(200);
        assertThat(schedule.body()).contains("\"scheduleVersion\":1");
        String read = client.get(LOANS + "/" + loan.loanId(), loan.customer().token()).body();
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> peek = client.get(LOANS + "/" + loan.loanId(), stranger.token());
        assertThat(peek.statusCode()).as("another party's loan and its payout are one 404").isEqualTo(404);
        assertThat(peek.body()).doesNotContain("payoutStatus");
        assertThat(read).contains("\"payoutStatus\":\"PAID_OUT\"")
                .doesNotContain(scalar("SELECT destination_reference FROM paymentmethods.payment_method WHERE id = ?::uuid",
                        loan.methodId()));
    }

    @Test
    @DisplayName("the disbursement posted but the payout failed leaves the loan ACTIVE: the scheme rejects, payments"
            + " FAILED with the hold released, the payout FAILED, the receivable and the entry unchanged, the funds"
            + " spendable in the wallet, interest from payments' conclusion")
    void disbursementPostedButPayoutFailsLeavesTheLoanActive() throws Exception {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, "{\"status\":\"rejected\"}");
        Disbursed loan = disbursedExternally("2100.00");
        payoutSweep(AT_ONCE).sweepOnce();
        assertThat(payoutStatus(loan)).isEqualTo("FAILED");
        assertThat(scalar("SELECT w.status || '|' || w.failure_reason FROM payments.withdrawal w JOIN lending.loan_payout p"
                + " ON p.withdrawal_id = w.id WHERE p.loan_id = ?::uuid", loan.loanId())).isEqualTo("FAILED|DECLINED");
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loan.loanId())).isEqualTo("ACTIVE");
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN lending.loan_payout p ON p.hold_id = h.id"
                + " WHERE p.loan_id = ?::uuid", loan.loanId())).isEqualTo("RELEASED");
        assertThat(walletNet(loan)).as("the funds back (never left) in the wallet").isEqualTo("-210000");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.disbursement:" + loan.loanId())).isEqualTo(1);
        assertThat(client.get(LOANS + "/" + loan.loanId(), loan.customer().token()).body())
                .contains("\"principalNotDue\":\"2100.00\"", "\"payoutStatus\":\"FAILED\"");
        assertAccrualFromPaymentsConclusion(loan);
    }

    @Test
    @DisplayName("an unroutable payout releases the hold: no eligible rail - payments' recorded refusal - the payout"
            + " NOT_DISPATCHED, the hold RELEASED, no withdrawal, interest from today; the loan ACTIVE")
    void anUnroutablePayoutReleasesTheHold() throws Exception {
        Disbursed loan = disbursedExternally("1900.00");
        execute("INSERT INTO payments.rail_availability (rail, available, reason, changed_by, changed_at)"
                + " VALUES ('instant', false, 'suite: rail out', 'LoanPayoutDatabaseTest', now())"
                + " ON CONFLICT (rail) DO UPDATE SET available = false, reason = 'suite: rail out', changed_at = now()");
        try {
            payoutSweep(AT_ONCE).sweepOnce();
        } finally {
            execute("UPDATE payments.rail_availability SET available = true, reason = 'suite: rail back',"
                    + " changed_at = now() WHERE rail = 'instant'");
        }
        assertThat(scalar("SELECT status || '|' || not_dispatched_reason FROM lending.loan_payout WHERE loan_id = ?::uuid",
                loan.loanId())).isEqualTo("NOT_DISPATCHED|NO_ELIGIBLE_RAIL");
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN lending.loan_payout p ON p.hold_id = h.id"
                + " WHERE p.loan_id = ?::uuid", loan.loanId())).isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + loan.loanId())).isZero();
        assertThat(scalar("SELECT (start_date = lending.business_date(now(), 'UTC'))::text FROM lending.accrual_start"
                + " WHERE loan_id = ?::uuid", loan.loanId())).isEqualTo("true");
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loan.loanId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the hold is adopted, not duplicated: while payments holds the withdrawal UNKNOWN the wallet carries"
            + " exactly one ACTIVE hold - the disbursement's, named by the withdrawal - and nothing of the money is"
            + " spendable")
    void theHoldIsAdoptedNotDuplicated() throws Exception {
        Disbursed loan = disbursedExternally("1800.00");
        provider.receivesTheRequestThenLosesTheResponse(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
        payoutSweep(AT_ONCE).sweepOnce();
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN lending.loan_payout p"
                + " ON p.wallet_account_id = h.ledger_account_id WHERE p.loan_id = ?::uuid", loan.loanId()))
                .as("one hold ever placed on the wallet").isEqualTo(1);
        assertThat(scalar("SELECT (w.hold_reference = p.hold_id)::text || '|' || h.status FROM payments.withdrawal w"
                + " JOIN lending.loan_payout p ON p.withdrawal_id = w.id JOIN ledger.hold h ON h.id = p.hold_id"
                + " WHERE p.loan_id = ?::uuid", loan.loanId())).isEqualTo("true|ACTIVE");
        assertThatThrownBy(() -> personWithdraws(loan, "0.01")).isInstanceOf(WithdrawalUnfundedException.class);
    }

    @Test
    @DisplayName("the funds cannot be spent before the payout: between the disbursement and the payout the borrower's"
            + " own withdrawal of a single cent is refused unfunded under the wallet's lock, writing nothing")
    void theFundsCannotBeSpentBeforeThePayout() throws Exception {
        Disbursed loan = disbursedExternally("1700.00");
        schemeAccepts();
        assertThat(payoutStatus(loan)).isEqualTo("PENDING");
        assertThat(walletNet(loan)).as("credited").isEqualTo("-170000");
        assertThatThrownBy(() -> personWithdraws(loan, "0.01")).isInstanceOf(WithdrawalUnfundedException.class);
        assertThat(count("SELECT count(*) FROM payments.withdrawal w JOIN lending.loan_payout p"
                + " ON p.wallet_account_id = w.wallet_account_id WHERE p.loan_id = ?::uuid", loan.loanId())).isZero();
    }

    @Test
    @DisplayName("a crash between the claim and the dispatch re-drives under the same key: a claim committed and never"
            + " stepped, then payments asked with no step recorded - the lapsed permit re-drives, payments replays its"
            + " one withdrawal, nothing is sent twice, the payout DISPATCHED and concluded")
    void aCrashBetweenClaimAndDispatchReDrivesUnderTheSameKey() throws Exception {
        schemeAccepts();
        Disbursed loan = disbursedExternally("1600.00");
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            lendingTransactions.inTransaction(uow -> payoutSteps.claim(uow, 100, AT_ONCE));
        }
        assertThat(payoutStatus(loan)).as("claimed, then the instance died").isEqualTo("DISPATCHING");
        LoanPayouts.Instruction instruction = instructionOf(loan);
        LoanPayouts.Dispatch asked = asSystem(instruction.correlation(), () -> loanPayouts.dispatch(instruction));
        assertThat(asked).isInstanceOf(LoanPayouts.Dispatch.Accepted.class);
        assertThat(payoutStatus(loan)).as("payments answered, the instance died before recording it")
                .isEqualTo("DISPATCHING");
        String reference = scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + loan.loanId());
        Thread.sleep(20);
        payoutSweep(AT_ONCE).sweepOnce();
        assertThat(payoutStatus(loan)).isEqualTo("PAID_OUT");
        assertThat(scalar("SELECT withdrawal_id::text FROM lending.loan_payout WHERE loan_id = ?::uuid", loan.loanId()))
                .isEqualTo(((LoanPayouts.Dispatch.Accepted) asked).withdrawal().toString());
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?",
                "lending:" + loan.loanId())).isEqualTo(1);
        assertThat(sends(reference)).isEqualTo(1);
    }

    @Test
    @DisplayName("a return lands in the wallet and the loan is unaffected: payments keeps no return record for an"
            + " instant withdrawal (final on acceptance), so whatever credits the wallet after PAID_OUT - here a fixture"
            + " credit standing for returned funds - the payout stays PAID_OUT, the receivable, the accrual start and"
            + " schedule v1 stand, nothing is claimed again, and the terminal payout refuses every edge")
    void aReturnLandsInTheWalletAndTheLoanIsUnaffected() throws Exception {
        schemeAccepts();
        Disbursed loan = disbursedExternally("1500.00");
        payoutSweep(AT_ONCE).sweepOnce();
        assertThat(payoutStatus(loan)).isEqualTo("PAID_OUT");
        String accrual = scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?::uuid", loan.loanId());
        LedgerAccountId wallet = LedgerAccountId.of(UUID.fromString(scalar(
                "SELECT wallet_account_id::text FROM lending.loan_payout WHERE loan_id = ?::uuid", loan.loanId())));
        post("p11t15-returned:" + UUID.randomUUID(), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, Money.ofMinorUnits(1500_00, EUR)),
                new JournalLine(wallet, Direction.CREDIT, Money.ofMinorUnits(1500_00, EUR))));
        Thread.sleep(20);
        payoutSweep(AT_ONCE).sweepOnce();
        assertThat(payoutStatus(loan)).isEqualTo("PAID_OUT");
        assertThat(scalar("SELECT start_date::text FROM lending.accrual_start WHERE loan_id = ?::uuid", loan.loanId()))
                .isEqualTo(accrual);
        assertThat(count("SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?::uuid", loan.loanId()))
                .isEqualTo(1);
        assertThat(client.get(LOANS + "/" + loan.loanId(), loan.customer().token()).body())
                .contains("\"principalNotDue\":\"1500.00\"", "\"payoutStatus\":\"PAID_OUT\"");
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE lending.loan_payout SET status = 'FAILED'"
                    + " WHERE loan_id = '" + loan.loanId() + "'")).hasMessageContaining("terminal");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private record Disbursed(CreditFixtures.Customer customer, String loanId, String methodId) {}

    /** A consenting customer's external-account loan of {@code amount} EUR, accepted and disbursed: payout PENDING. */
    private Disbursed disbursedExternally(String amount) throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String method = registerBankAccount(customer.token());
        String offer = offered(customer, "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + amount + "\","
                + "\"currency\":\"EUR\",\"termMonths\":12,\"repaymentDay\":10,\"repaymentMethod\":\"WALLET\","
                + "\"payoutPreference\":\"EXTERNAL_ACCOUNT\",\"declaredMonthlyIncome\":\"3200.00\","
                + "\"declaredMonthlyExpenditure\":\"1400.00\"}");
        String hash = field(client.get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        HttpResponse<String> accepted = client.post(OFFERS + "/" + offer + "/acceptance",
                "{\"termsSha256\":\"" + hash + "\",\"payoutMethodId\":\"" + method + "\"}", customer.token(), key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String loanId = field(accepted.body(), "loanId");
        assertThat(disbursementSweep.sweepOnce()).isPositive();
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?::uuid", loanId)).isEqualTo("ACTIVE");
        Disbursed disbursed = new Disbursed(customer, loanId, method);
        assertThat(payoutStatus(disbursed)).isEqualTo("PENDING");
        return disbursed;
    }

    /** The borrower's bank account through the real grant exchange; the payment method's id. */
    private String registerBankAccount(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-loan-" + suffix
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered = client.post("/v1/me/payment-methods/bank-accounts",
                "{\"grant\":\"blg-" + suffix + "\",\"acknowledgeNoMatch\":false}", token, key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    private String offered(CreditFixtures.Customer customer, String body) throws Exception {
        HttpResponse<String> submitted = client.submit(customer.token(), body, key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> origination.hintRequest(uow, request));
        originationSweep.sweepOnce();
        return field(client.read(customer.token(), field(submitted.body(), "applicationId")).body(), "offerId");
    }

    private LoanPayoutSchedule payoutSweep(Duration permit) {
        return new LoanPayoutSchedule(payoutSteps, lendingTransactions, payoutMetrics, Duration.ofSeconds(5), 100, permit);
    }

    /** The payout's instruction as the step builds it - from the stored rows. */
    private LoanPayouts.Instruction instructionOf(Disbursed loan) throws Exception {
        String[] row = scalar("SELECT l.party_id::text || '|' || p.payment_method_id::text || '|'"
                + " || p.wallet_account_id::text || '|' || p.hold_id::text || '|' || h.amount_minor::text || '|'"
                + " || l.correlation_id FROM lending.loan_payout p JOIN lending.loan l ON l.id = p.loan_id"
                + " JOIN ledger.hold h ON h.id = p.hold_id WHERE p.loan_id = ?::uuid", loan.loanId()).split("\\|");
        return new LoanPayouts.Instruction(LoanId.of(UUID.fromString(loan.loanId())), UUID.fromString(row[0]),
                UUID.fromString(row[1]), LedgerAccountId.of(UUID.fromString(row[2])), HoldId.of(UUID.fromString(row[3])),
                Money.ofMinorUnits(Long.parseLong(row[4]), EUR), CorrelationId.of(row[5]));
    }

    /** The borrower's own withdrawal through payments' person door, as the customer, to their own bank account. */
    private void personWithdraws(Disbursed loan, String amount) throws Exception {
        String[] row = scalar("SELECT l.party_id::text || '|' || c.id::text || '|' || p.wallet_account_id::text || '|'"
                + " || m.destination_reference FROM lending.loan_payout p JOIN lending.loan l ON l.id = p.loan_id"
                + " JOIN party.customer c ON c.party_id = l.party_id JOIN paymentmethods.payment_method m"
                + " ON m.id = p.payment_method_id WHERE p.loan_id = ?::uuid", loan.loanId()).split("\\|");
        Withdrawals.Resolved resolved = new Withdrawals.Resolved(UUID.fromString(row[0]), UUID.fromString(row[1]),
                LedgerAccountId.of(UUID.fromString(row[2])), EUR, UUID.fromString(loan.methodId()),
                new ProviderReference(row[3]));
        try (SecurityContext.Scope actor = SecurityContext.enter(new Actor(row[0], ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(
                        CorrelationId.generate(ids)).causing(CausationId.of("p11t15")))) {
            withdrawals.withdraw(key(), Money.of(new java.math.BigDecimal(amount), EUR), unitOfWork -> resolved);
        }
    }

    /** Interest from payments' conclusion: the accrual start the business date of the withdrawal's terminal move, held
     * within the payout's life - never after the recording clock (decided_at), never before the birth, the birth winning
     * (lending V019, P11-TSK-033) - in the pinned zone; schedule v1 from it. */
    private static void assertAccrualFromPaymentsConclusion(Disbursed loan) throws Exception {
        assertThat(scalar("SELECT (s.start_date = lending.business_date(GREATEST(LEAST(e.occurred_at, p.decided_at),"
                + " p.created_at), t.servicing_zone) AND s.origin = 'PAYOUT_CONCLUDED'"
                + " AND p.concluded_at = GREATEST(LEAST(e.occurred_at, p.decided_at), p.created_at))::text"
                + " FROM lending.accrual_start s JOIN lending.loan_payout p ON p.loan_id = s.loan_id"
                + " JOIN payments.withdrawal_event e ON e.withdrawal_id = p.withdrawal_id"
                + " AND e.to_status IN ('COMPLETED', 'FAILED')"
                + " JOIN lending.loan l ON l.id = s.loan_id JOIN lending.loan_offer o ON o.id = l.offer_id"
                + " JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id WHERE s.loan_id = ?::uuid",
                loan.loanId())).as("the accrual start is payments' conclusion, in the pinned zone (A30)").isEqualTo("true");
        assertThat(scalar("SELECT (r.start_date = s.start_date)::text FROM lending.repayment_schedule r"
                + " JOIN lending.accrual_start s ON s.loan_id = r.loan_id WHERE r.loan_id = ?::uuid"
                + " AND r.schedule_version = 1", loan.loanId())).isEqualTo("true");
    }

    private static String payoutStatus(Disbursed loan) throws Exception {
        return scalar("SELECT status FROM lending.loan_payout WHERE loan_id = ?::uuid", loan.loanId());
    }

    /** The wallet's DR - CR over its lines: negative is money the borrower holds. */
    private static String walletNet(Disbursed loan) throws Exception {
        return scalar("SELECT coalesce(sum(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END),"
                + " 0)::text FROM ledger.journal_line l JOIN lending.loan_payout p"
                + " ON p.wallet_account_id = l.ledger_account_id WHERE p.loan_id = ?::uuid", loan.loanId());
    }

    private void schemeAccepts() {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-loan-{{request.headers.Idempotency-Key}}\","
                        + "\"cycle\":\"C1\"}");
    }

    private static long sends(String reference) {
        return provider.headerValues(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, "Idempotency-Key").stream()
                .filter(reference::equals).count();
    }

    private double paid() {
        return meterRegistry.find(LoanPayoutMetrics.PAYOUT).tag("outcome", "paid_out").counter().count();
    }

    private <R> R asSystem(CorrelationId correlation, java.util.function.Supplier<R> work) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation).causing(CausationId.of(correlation.value())))) {
            return work.get();
        }
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<Future<T>> pending = new java.util.ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new java.util.ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void plantCapital(Money amount) {
        post("p11t15-capital:" + UUID.randomUUID(), List.of(
                new JournalLine(cashAtBank(), Direction.DEBIT, amount),
                new JournalLine(lendingTransactions.inTransaction(uow -> headroom.capitalAccount(uow, EUR).id()),
                        Direction.CREDIT, amount)));
    }

    private LedgerAccountId cashAtBank() {
        return lendingTransactions.inTransaction(uow -> ledgerAccountStore.findOperational(uow,
                AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id());
    }

    private void post(String key, List<JournalLine> lines) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p11t15")))) {
            lendingTransactions.inTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                return postingService.post(uow, new PostingCommand(key, today, today, "the suite's fixture", lines));
            });
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
