package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.app.reconciliation.CorridorRuleSetV1;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Corridor settlement to cash (`P9-TSK-022`, PHASE_9_PLAN.md sections 12.4(g) and 12.9; scenario 10's corridor half;
 * {@code INV-SET-02}, {@code INV-SET-05}, {@code INV-SET-06}, {@code INV-REC-06}..{@code -09}, {@code INV-MON-04}): a
 * completed cross-border payment's {@code CORRIDOR_CLEARING} is discharged by the provider's report - the payout line
 * allocated to its {@code CROSSBORDER_PAYOUT}, the fee checked against the pinned schedule and posted at hop 1, the
 * remittance opened - and by the bank's statement at hop 2; section 12.4(g)'s USD cash identity (4.22) is reproduced
 * with the cover settled to cash beside it; each currency's fee is judged against its own row; every corridor
 * discrepancy is typed.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("corridor settlement to cash (P9-TSK-022)")
class CorridorSettlementToCashDatabaseTest {

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final String CORRIDOR_SOURCE = "corridor-sim-a.settlement";
    private static final String FX_SOURCE = "fx-sim-a.trade-report";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private PositionProof proof;

    private static SimulatedCorridorEngine corridor() {
        try {
            return SimulatedCorridorEngine.start("a-corridor-callback-test-key-of-32-byte".getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (fxEngine == null) {
            fxEngine = SimulatedFxEngine.start(new byte[32]);
            // Section 12.4's provider rate for EUR -> USD.
            FxTestClient.rates(fxEngine, Map.of("EUR/USD", "1.0850240000"));
        }
        registry.add("finapp.fx.provider.url", () -> fxEngine.baseUrl().toString());
        registry.add("finapp.corridor.provider.url", () -> CORRIDOR.baseUrl().toString());
    }

    @AfterAll
    static void stop() {
        fxEngine.close();
        CORRIDOR.close();
    }

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    @BeforeEach
    void policiesAndReferences() throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        activateCorridorRuleSet();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        awaitRoutingVersionInForce();
    }

    // ------------------------------------------------------------------ the cash identity

    @Test
    @DisplayName("12.4(g) to cash: 1,000.00 EUR to a US beneficiary - the payout allocated, the 1.20 fee exactly at the"
            + " schedule and posted at hop 1, the 1,080.80 remittance discharged by the bank, the cover settled beside it:"
            + " USD cash at rest 4.22 = spread - residual - corridor fee; the corridor's USD clearing nets to zero, proven"
            + " per counterparty; no break")
    void theUsdCashIdentityHolds() throws Exception {
        Paid paid = paid("EUR", "1100.00", "US", "USD", "1000.00");
        assertThat(paid.status()).isEqualTo("SENT");
        assertThat(scalar("SELECT total_debit_minor || ' ' || fee_minor || ' ' || destination_minor FROM crossborder.payment_offer"
                + " WHERE quote_id = ?", paid.quote())).as("section 12.4(g)'s offer").isEqualTo("100250 250 107960");

        // The cover, settled to cash as any cover is (P9-TSK-013).
        UUID cover = UUID.fromString(scalar("SELECT id::text FROM fx.cover WHERE quote_id = ? AND kind = 'COVER'", paid.quote()));
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        String coverRef = scalar("SELECT client_reference FROM fx.cover_attempt WHERE cover_id = ? AND attempt = 1", cover);
        String tradeRef = scalar("SELECT provider_trade_ref FROM fx.cover_execution WHERE cover_id = ?", cover);
        String valueDate = scalar("SELECT value_date::text FROM fx.cover_execution WHERE cover_id = ?", cover);
        String eurRemittance = "FXA-" + digits(8);
        String usdRemittance = "FXA-" + digits(8);
        acceptedBatch(FX_SOURCE, "H,SIM_FX_CSV,1,FXB-" + marker() + "-EUR,EUR," + valueDate + "\n"
                + "D,1,SOLD,-1000.00,," + tradeRef + "," + coverRef + "\nT,1,-1000.00," + eurRemittance + "\n");
        acceptedBatch(FX_SOURCE, "H,SIM_FX_CSV,1,FXB-" + marker() + "-USD,USD," + valueDate + "\n"
                + "D,1,BOUGHT,1085.02,," + tradeRef + "," + coverRef + "\nT,1,1085.02," + usdRemittance + "\n");
        matchUntilQuiet();
        UUID eurCash = acceptedBatch(BANK_SOURCE, nextStatement("EUR", valueDate, "D", 100_000, eurRemittance));
        UUID usdCash = acceptedBatch(BANK_SOURCE, nextStatement("USD", valueDate, "C", 108_502, usdRemittance));

        // Hop 1: the corridor's USD report - the payout and its fee, the trailer the net.
        String remittance = "XBA-" + (10_000 + RANDOMNESS.nextInt(80_000));
        UUID report = acceptedBatch(CORRIDOR_SOURCE, corridorReport("USD", List.of(
                payoutLine(1, "-1079.60", "1.20", paid)), "-1080.80", remittance));
        assertThat(linesOf(scalar("SELECT journal_entry_id::text FROM settlement.batch WHERE id = ?", report)))
                .as("the fee posted at hop 1 onto the corridor's own USD clearing")
                .containsExactlyInAnyOrder("USD PROCESSING_COSTS DEBIT 120", "USD CORRIDOR_CLEARING CREDIT 120");
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_PAYOUT' AND operation_ref = ?",
                paid.credit().toString())).isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.external_item i JOIN reconciliation.reconciliation_batch r"
                + " ON r.id = i.run_id WHERE r.batch_id = ? AND i.line_type = 'PAYOUT_FEE' AND i.status = 'CHECKED'", report))
                .as("the 1.20 judged exactly at the v1 schedule (0 + USD 1.20)").isEqualTo(1);
        assertThat(scalar("SELECT direction || ' ' || amount_minor || ' ' || currency FROM reconciliation.expectation"
                + " WHERE kind = 'REMITTANCE' AND operation_ref = ?", report.toString())).isEqualTo("OUTBOUND 108080 USD");

        // Hop 2: the bank's USD statement discharges the remittance.
        UUID corridorCash = acceptedBatch(BANK_SOURCE, nextStatement("USD", today(), "D", 108_080, remittance));
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE' AND operation_ref = ?",
                report.toString())).isEqualTo("SETTLED");

        // The cash identity: USD cash at rest = 1,085.02 - 1,080.80 = 4.22 = 5.43 spread - 0.01 residual - 1.20 fee.
        long usdCashDelta = cashDelta("USD", usdCash, corridorCash);
        long margin = Long.parseLong(scalar("SELECT margin_minor::text FROM fx.quote WHERE id = ?", paid.quote()));
        long residual = Long.parseLong(scalar("SELECT residual_minor::text FROM fx.quote WHERE id = ?", paid.quote()));
        assertThat(usdCashDelta).as("USD cash at rest").isEqualTo(422).isEqualTo(margin + residual - 120);
        // EUR: the wallet -1,002.50 = cash -1,000.00 - fee revenue 2.50.
        assertThat(cashDelta("EUR", eurCash)).isEqualTo(-100_000);
        assertThat(linesOf(scalar("SELECT id::text FROM ledger.journal_entry WHERE idempotency_scope = ?",
                        PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:" + paid.credit())))
                .contains("EUR CUSTOMER_WALLET DEBIT 100250", "EUR FEE_REVENUE CREDIT 250", "USD CORRIDOR_CLEARING CREDIT 107960");
        // The corridor's USD clearing over the completion, the fee and the cash: zero.
        assertThat(count("SELECT COALESCE(SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                        + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " AND a.purpose = 'CORRIDOR_CLEARING' WHERE l.currency = 'USD' AND l.entry_id IN ("
                        + " SELECT id FROM ledger.journal_entry WHERE idempotency_scope = ?"
                        + " UNION SELECT journal_entry_id FROM settlement.batch WHERE id IN (?, ?))",
                PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:" + paid.credit(), report, corridorCash))
                .as("corridor-sim-a's USD clearing nets to zero over the payout and its cash").isZero();
        assertThat(corridorVerdict(CurrencyCode.of("USD")).explained()).as("proven per (purpose, counterparty, currency)").isTrue();
        assertThat(count("SELECT count(*) FROM reconciliation.break b WHERE b.expectation_id IN (SELECT id FROM"
                + " reconciliation.expectation WHERE operation_ref IN (?, ?))", paid.credit().toString(), report.toString()))
                .as("no break").isZero();
    }

    // ------------------------------------------------------------------ the fee rows

    @Test
    @DisplayName("each currency's payout fee is judged against its own row: JPY 180 and BHD 0.450 CHECKED, a JPY 181"
            + " FEE_MISMATCH beyond the zero tolerance")
    void eachCurrencyFeeIsJudgedAgainstItsOwnRow() throws Exception {
        Paid jpy = paid("EUR", "200.00", "JP", "JPY", "100.00");
        UUID jpyReport = settledPayout(jpy, "JPY", "180");
        assertThat(feeChecked(jpyReport)).isEqualTo(1);
        assertThat(breaksOn(jpyReport)).isZero();

        Paid bhd = paid("USD", "200.00", "BH", "BHD", "100.00");
        UUID bhdReport = settledPayout(bhd, "BHD", "0.450");
        assertThat(feeChecked(bhdReport)).isEqualTo(1);
        assertThat(breaksOn(bhdReport)).isZero();

        Paid breach = paid("EUR", "200.00", "JP", "JPY", "100.00");
        UUID breachReport = settledPayout(breach, "JPY", "181");
        assertThat(scalar("SELECT b.type || ':' || b.cause || ':' || b.value_at_issue_minor::text FROM reconciliation.break b"
                + " JOIN reconciliation.external_item i ON i.id = b.external_item_id JOIN reconciliation.reconciliation_batch r"
                + " ON r.id = i.run_id WHERE r.batch_id = ? AND i.line_type = 'PAYOUT_FEE'", breachReport))
                .isEqualTo("FEE_MISMATCH:FEE_BEYOND_TOLERANCE:1");
    }

    // ------------------------------------------------------------------ the mismatch battery

    @Test
    @DisplayName("scenario 10 (corridor): a principal deduction is AMOUNT_MISMATCH on the payout; a JPY line naming a USD"
            + " payout is CURRENCY_MISMATCH, parked; a EUR file is REJECTED CURRENCY_NOT_SETTLED at parse, nothing posted")
    void everyCorridorDiscrepancyIsTyped() throws Exception {
        Paid deducted = paid("EUR", "1100.00", "US", "USD", "1000.00");
        long destination = Long.parseLong(scalar("SELECT destination_minor::text FROM crossborder.payment_offer WHERE quote_id = ?",
                deducted.quote()));
        long shortBy = 10;
        UUID report = acceptedBatch(CORRIDOR_SOURCE, corridorReport("USD", List.of(
                payoutLine(1, "-" + amount(destination - shortBy, 2), "", deducted)),
                "-" + amount(destination - shortBy, 2), "XBA-" + (10_000 + RANDOMNESS.nextInt(80_000))));
        matchUntilQuiet();
        assertThat(scalar("SELECT b.type || ':' || b.cause || ':' || b.value_at_issue_minor::text FROM reconciliation.break b"
                + " JOIN reconciliation.expectation e ON e.id = b.expectation_id WHERE e.kind = 'CROSSBORDER_PAYOUT'"
                + " AND e.operation_ref = ?", deducted.credit().toString()))
                .as("an intermediary's deduction under OUR").isEqualTo("AMOUNT_MISMATCH:AMOUNT_DIFFERS:" + shortBy);
        assertThat(report).isNotNull();

        Paid usd = paid("EUR", "1100.00", "US", "USD", "1000.00");
        UUID jpyReport = acceptedBatch(CORRIDOR_SOURCE, corridorReport("JPY", List.of(
                payoutLine(1, "-16221", "", usd)), "-16221", "XBA-" + (10_000 + RANDOMNESS.nextInt(80_000))));
        matchUntilQuiet();
        assertThat(scalar("SELECT i.status || ' ' || b.type || ':' || b.cause FROM reconciliation.external_item i"
                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id JOIN reconciliation.break b"
                + " ON b.external_item_id = i.id WHERE r.batch_id = ? AND i.line_type = 'PAYOUT_EXECUTED'", jpyReport))
                .isEqualTo("PARKED CURRENCY_MISMATCH:CURRENCY_DIFFERS");
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_PAYOUT' AND operation_ref = ?",
                usd.credit().toString())).as("the USD payout untouched by a JPY line").isEqualTo("OPEN");

        UUID eurFile = received(CORRIDOR_SOURCE, corridorReport("EUR", List.of(
                payoutLine(1, "-100.00", "", usd)), "-100.00", "XBA-" + (10_000 + RANDOMNESS.nextInt(80_000))));
        parsing.sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", eurFile))
                .isEqualTo("REJECTED:CURRENCY_NOT_SETTLED");
        assertThat(count("SELECT count(*) FROM settlement.batch WHERE file_id = ?", eurFile)).as("nothing parsed, nothing posted")
                .isZero();
    }

    // ------------------------------------------------------------------ plumbing

    record Paid(FxTestClient.Customer customer, UUID quote, UUID payment, UUID credit, String reference,
            String providerReference, String status) {}

    /** Pays a fresh fixed-source offer from a funded wallet; the provider accepts at once - the completion in the POST. */
    private Paid paid(String source, String fundedWith, String country, String destination, String amount) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, source);
        fund(postings, product, money(fundedWith, source));
        String beneficiary = beneficiary(customer, country, destination);
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":\""
                + source + "\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"" + amount + "\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        String quote = field(offered.body(), "id");
        HttpResponse<String> paid = client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", customer.token(), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?", payment));
        return new Paid(customer, UUID.fromString(quote), payment, credit,
                scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?", credit),
                scalar("SELECT provider_reference FROM payments.outbound_credit WHERE id = ?", credit),
                field(paid.body(), "status"));
    }

    /** The corridor's report for one payout at its exact destination amount and {@code fee}, accepted and matched. */
    private UUID settledPayout(Paid paid, String currency, String fee) throws Exception {
        int scale = CurrencyCode.of(currency).minorUnits();
        long destination = Long.parseLong(scalar("SELECT destination_minor::text FROM crossborder.payment_offer WHERE quote_id = ?",
                paid.quote()));
        long feeMinor = new BigDecimal(fee).movePointRight(scale).longValueExact();
        UUID report = acceptedBatch(CORRIDOR_SOURCE, corridorReport(currency, List.of(
                payoutLine(1, "-" + amount(destination, scale), fee, paid)),
                "-" + amount(destination + feeMinor, scale), "XBA-" + (10_000 + RANDOMNESS.nextInt(80_000))));
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_PAYOUT' AND operation_ref = ?",
                paid.credit().toString())).isEqualTo("SETTLED");
        return report;
    }

    private static String payoutLine(int sequence, String signedAmount, String fee, Paid paid) {
        return "D," + sequence + ",CREDITED," + signedAmount + "," + fee + "," + paid.providerReference() + "," + paid.reference();
    }

    private static String corridorReport(String currency, List<String> lines, String net, String remittance) {
        return "H,SIM_CORRIDOR_CSV,1,XB-" + marker() + "," + currency + "," + today() + "\n"
                + String.join("\n", lines) + "\n"
                + "T," + lines.size() + "," + net + "," + remittance + "\n";
    }

    private static long feeChecked(UUID report) throws Exception {
        return count("SELECT count(*) FROM reconciliation.external_item i JOIN reconciliation.reconciliation_batch r"
                + " ON r.id = i.run_id WHERE r.batch_id = ? AND i.line_type = 'PAYOUT_FEE' AND i.status = 'CHECKED'", report);
    }

    private static long breaksOn(UUID report) throws Exception {
        return count("SELECT count(*) FROM reconciliation.break b JOIN reconciliation.external_item i ON i.id = b.external_item_id"
                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE r.batch_id = ?", report);
    }

    /** The CASH_AT_BANK delta in {@code currency} over the statements' entries. */
    private static long cashDelta(String currency, UUID... statements) throws Exception {
        long delta = 0;
        for (UUID statement : statements) {
            delta += count("SELECT COALESCE(SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                    + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                    + " AND a.purpose = 'CASH_AT_BANK' WHERE l.currency = ? AND l.entry_id ="
                    + " (SELECT journal_entry_id FROM settlement.batch WHERE id = ?)", currency, statement);
        }
        return delta;
    }

    private PositionProof.PositionVerdict corridorVerdict(CurrencyCode currency) throws Exception {
        PositionProof.Report report;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            report = proof.sweep(app);
            app.rollback();
        }
        return report.verdicts().stream()
                .filter(verdict -> verdict.purpose() == AccountPurpose.CORRIDOR_CLEARING
                        && verdict.counterparty().equals(Optional.of("corridor-sim-a"))
                        && verdict.currency().equals(currency))
                .findFirst().orElseThrow();
    }

    private static List<String> linesOf(String entry) throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT l.currency, a.purpose, l.direction, l.amount_minor"
                        + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " WHERE l.entry_id = ?")) {
            read.setObject(1, UUID.fromString(entry));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1).trim() + " " + row.getString(2) + " " + row.getString(3) + " " + row.getLong(4));
                }
            }
        }
        return lines;
    }

    /** The bank account's next statement in {@code currency}, at that currency's scale. */
    private static String nextStatement(String currency, String date, String side, long minor, String remittance)
            throws Exception {
        int scale = CurrencyCode.of(currency).minorUnits();
        long sequence = 0;
        long opening = 0;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT b.statement_sequence, b.closing_minor FROM settlement.batch b JOIN settlement.source s"
                                + " ON s.id = b.source_id WHERE s.code = ? AND b.currency = ? AND b.statement_sequence"
                                + " IS NOT NULL AND b.status NOT IN ('REJECTED', 'REPUDIATED')"
                                + " ORDER BY b.statement_sequence DESC LIMIT 1")) {
            read.setString(1, BANK_SOURCE);
            read.setString(2, currency);
            try (ResultSet row = read.executeQuery()) {
                if (row.next()) {
                    sequence = row.getLong(1);
                    opening = row.getLong(2);
                }
            }
        }
        long closing = "C".equals(side) ? opening + minor : opening - minor;
        return ":20:SB-XB-" + marker() + "\n"
                + ":25:SIMBANK-" + currency + "-01\n"
                + ":28C:" + (sequence + 1) + "\n"
                + ":60F:" + balance(date, currency, opening, scale) + "\n"
                + ":61:" + date + "," + side + "," + amount(minor, scale) + "," + remittance + "\n"
                + ":62F:" + balance(date, currency, closing, scale) + "\n";
    }

    private static String balance(String date, String currency, long minor, int scale) {
        return (minor < 0 ? "D," : "C,") + date + "," + currency + "," + amount(Math.abs(minor), scale);
    }

    private static String amount(long minor, int scale) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }

    private UUID received(String source, String content) throws Exception {
        FileReception.Result result;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(source, com.finapp.settlement.DeliveryChannel.PULL,
                            content.getBytes(StandardCharsets.UTF_8), Optional.empty(), Actor.SYSTEM,
                            com.finapp.settlement.SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
        }
        assertThat(result).as("%s received", source).isInstanceOf(FileReception.Result.New.class);
        return ((FileReception.Result.New) result).fileId();
    }

    private UUID acceptedBatch(String source, String content) throws Exception {
        UUID fileId = received(source, content);
        parsing.sweep();
        acceptance.sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", fileId))
                .as(content).isEqualTo("ACCEPTED:-");
        return UUID.fromString(scalar("SELECT id::text FROM settlement.batch WHERE file_id = ?", fileId));
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String marker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).replaceAll("[0-9]", "q");
    }

    private static String digits(int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append(RANDOMNESS.nextInt(10));
        }
        return digits.toString();
    }

    private String beneficiary(FxTestClient.Customer customer, String country, String currency) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary(country, currency, "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"" + country + "\",\"currency\":\""
                + currency + "\",\"grant\":\"" + grant + "\",\"name\":\"Clear Person\",\"nickname\":\"Payee\","
                + "\"entityType\":\"INDIVIDUAL\"}", customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    private void activateCorridorRuleSet() throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                CorridorRuleSetV1.SOURCE) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSetAdministration.propose(app, CorridorRuleSetV1.proposal("The corridor source's first version"),
                    new Actor("op-corridor-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE),
                    "Reviewed against O7", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    private static void awaitRoutingVersionInForce() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version WHERE version = 5 AND effective_from <= ?",
                java.sql.Timestamp.from(Instant.now())) == 0) {
            assertThat(System.nanoTime()).as("routing version 5 is not yet in force by the host's clock (database now %s)",
                    scalar("SELECT now()::text")).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private void activateCorridorPolicy() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT id FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'");
                ResultSet row = select.executeQuery()) {
            if (row.next()) {
                client().post(POLICIES + "/" + row.getObject(1, UUID.class) + "/rejection", "{\"reason\":\"cleared\"}", first, null);
            }
        }
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the corridor cash suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                        "{\"reason\":\"checked\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "xs." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client().post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client().post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key());
        return field(session.body(), "sessionToken");
    }

    @TestConfiguration
    static class Doubles {

        /** The screening provider: every name clear. */
        @Bean
        @Primary
        CounterpartyScreeningProvider clearCorridorCashScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
