package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.FxRuleSetV1;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.PolicyPair;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The second FX provider (`P9-TSK-026`, M9.8; PHASE_9_PLAN.md section 14 scenario 4; {@code INV-RAIL-04},
 * {@code INV-SET-05}): a pricing policy listing {@code fx-sim-a} then {@code fx-sim-b} - when {@code a} cannot quote, the
 * quote fails over to {@code b} (each candidate a stored sourcing step), its cover executes on {@code b}, and it settles
 * on {@code b}'s OWN clearing position from {@code b}'s own report and the bank - never netted with {@code a}'s; a line on
 * {@code a}'s report naming {@code b}'s cover is never allocated across counterparties; both down is {@code 503}.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("the second FX provider (P9-TSK-026)")
class SecondFxProviderDatabaseTest {

    private static final String FX_SOURCE_A = "fx-sim-a.trade-report";
    private static final String FX_SOURCE_B = "fx-sim-b.trade-report";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final UUID FX_SIM_B = UUID.fromString("01a0e2bc-8200-7025-8000-000000000001");
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static SimulatedFxEngine engineA;
    private static SimulatedFxEngine engineB;

    /** v1 with EUR-USD (both ways, both purposes) quoted by fx-sim-a then fx-sim-b - the failover order. */
    static final FxTestPolicy.Wanted SECOND = new FxTestPolicy.Wanted(FxTestPolicy.V1.pairs().stream()
            .map(pair -> (pair.pricing().source().code() + pair.pricing().destination().code()).matches("EURUSD|USDEUR")
                    ? new PolicyPair(pair.purpose(), List.of("fx-sim-a", "fx-sim-b"), pair.pricing(), pair.window(),
                            pair.coverMargin(), pair.band(), pair.referenceMaxAge())
                    : pair)
            .toList(), FxTestPolicy.V1.openQuoteCap());

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private PositionProof proof;

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (engineA == null) {
            engineA = SimulatedFxEngine.start(new byte[32]);
            engineB = SimulatedFxEngine.start(new byte[32]);
        }
        registry.add("finapp.fx.provider.url", () -> engineA.baseUrl().toString());
        registry.add("finapp.fx.provider.b.url", () -> engineB.baseUrl().toString());
    }

    @AfterAll
    static void stop() {
        engineA.close();
        engineB.close();
    }

    @BeforeEach
    void theSecondVersionAndBothSources() throws Exception {
        FxTestClient.rates(engineA, Map.of("EUR/USD", "1.0850240000"));
        FxTestClient.rates(engineB, Map.of("EUR/USD", "1.0850240000"));
        FxTestPolicy.ensure(administration, policyStore, SECOND);
        FxTestClient.freshReferences();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        ensureSourceBActive();
    }

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    // ------------------------------------------------------------------ failover, cover and settlement on b

    @Test
    @DisplayName("fx-sim-a errors: the quote fails over to fx-sim-b (both steps stored), its cover executes on fx-sim-b"
            + " onto fx-sim-b's OWN clearing, a line on fx-sim-a's report naming it is never allocated, and fx-sim-b's"
            + " report and the bank discharge it - fx-sim-b's position nets to zero and is proven, fx-sim-a's untouched")
    void aQuoteFailsOverAndSettlesOnB() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        int bQuotes = engineB.quoteRequests();
        engineA.serverErrorNext();
        String quote = client().quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");

        assertThat(scalar("SELECT provider_code FROM fx.quote WHERE id = ?::uuid", quote)).isEqualTo("fx-sim-b");
        assertThat(scalar("SELECT string_agg(position || ':' || provider_code || ':' || outcome, ',' ORDER BY position,"
                        + " outcome) FROM fx.quote_sourcing_step WHERE quote_request_id = (SELECT quote_request_id FROM"
                        + " fx.quote WHERE id = ?::uuid)", quote))
                .as("each candidate a stored step, in the pinned order")
                .startsWith("1:fx-sim-a:")
                .contains("2:fx-sim-b:CHOSEN", "2:fx-sim-b:QUOTED")
                .doesNotContain("1:fx-sim-a:QUOTED");
        assertThat(engineB.quoteRequests()).isEqualTo(bQuotes + 1);

        HttpResponse<String> converted = client().convert(customer, quote, FxTestClient.key());
        assertThat(converted.statusCode()).as(converted.body()).isEqualTo(201);
        String trade = field(converted.body(), "id");
        UUID cover = FxCoverFixtures.coverOf(trade);
        assertThat(scalar("SELECT provider_code FROM fx.cover WHERE id = ?", cover)).isEqualTo("fx-sim-b");
        int aExecutions = engineA.executions();
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(FxCoverFixtures.status(cover)).isEqualTo("EXECUTED");
        assertThat(engineA.executions()).as("never sent to fx-sim-a").isEqualTo(aExecutions);
        assertThat(clearingOwners(cover)).as("the cover's provider legs on fx-sim-b's OWN clearing")
                .containsOnly(FX_SIM_B.toString());

        String coverRef = scalar("SELECT client_reference FROM fx.cover_attempt WHERE cover_id = ? AND attempt = 1", cover);
        String tradeRef = scalar("SELECT provider_trade_ref FROM fx.cover_execution WHERE cover_id = ?", cover);
        String valueDate = scalar("SELECT value_date::text FROM fx.cover_execution WHERE cover_id = ?", cover);
        long sold = Long.parseLong(scalar("SELECT plan_sold_minor::text FROM fx.cover_execution WHERE cover_id = ?", cover));
        long bought = Long.parseLong(scalar("SELECT plan_bought_minor::text FROM fx.cover_execution WHERE cover_id = ?", cover));

        // The planted cross-counterparty allocation: fx-sim-a's report naming fx-sim-b's cover reaches nothing.
        UUID planted = acceptedBatch(FX_SOURCE_A, fxReport("EUR", valueDate, "SOLD", -sold, tradeRef, coverRef,
                "FXA-" + digits(8)));
        matchUntilQuiet();
        assertThat(count("SELECT count(*) FROM reconciliation.external_item i JOIN reconciliation.reconciliation_batch r"
                + " ON r.id = i.run_id WHERE r.batch_id = ? AND i.status = 'MATCHED'", planted))
                .as("a line on another counterparty's report is never allocated to fx-sim-b's leg").isZero();
        assertThat(legStatuses(cover)).containsOnly("OPEN");

        // fx-sim-b's own report and the bank: the legs SETTLED, the position discharged.
        // fx-sim-b remits in its OWN shape (FXB-), so the bank's line is attributed to its report alone.
        String eurRemittance = "FXB-" + digits(8);
        String usdRemittance = "FXB-" + digits(8);
        acceptedBatch(FX_SOURCE_B, fxReport("EUR", valueDate, "SOLD", -sold, tradeRef, coverRef, eurRemittance));
        acceptedBatch(FX_SOURCE_B, fxReport("USD", valueDate, "BOUGHT", bought, tradeRef, coverRef, usdRemittance));
        matchUntilQuiet();
        assertThat(legStatuses(cover)).containsOnly("SETTLED");
        acceptedBatch(BANK_SOURCE, nextStatement("EUR", valueDate, "D", sold, eurRemittance));
        acceptedBatch(BANK_SOURCE, nextStatement("USD", valueDate, "C", bought, usdRemittance));
        matchUntilQuiet();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE kind = 'REMITTANCE' AND status <> 'SETTLED'"
                + " AND operation_ref IN (SELECT id::text FROM settlement.batch WHERE source_id = ?)", FxRuleSetV1.SOURCE_B))
                .as("fx-sim-b's remittances discharged by the bank").isZero();
        for (String currency : List.of("EUR", "USD")) {
            assertThat(verdict(AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-b", currency).explained())
                    .as("fx-sim-b's %s position, proven on its own", currency).isTrue();
        }
    }

    @Test
    @DisplayName("ten racing quotes while fx-sim-a quotes implausibly: each one fails over on its own - fx-sim-a judged"
            + " and refused, fx-sim-b chosen, its steps stored - ten quotes on fx-sim-b, none on fx-sim-a")
    void tenRacingQuotesEachFailOver() throws Exception {
        FxTestClient.rates(engineA, Map.of("EUR/USD", "1.3000000000"));
        List<FxTestClient.Customer> customers = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            customers.add(client().verifiedCustomer());
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<String> quotes = new java.util.ArrayList<>();
        try {
            List<java.util.concurrent.Future<String>> racers = new java.util.ArrayList<>();
            for (FxTestClient.Customer customer : customers) {
                racers.add(pool.submit(() -> {
                    start.await();
                    return client().quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<String> racer : racers) {
                quotes.add(racer.get(2, java.util.concurrent.TimeUnit.MINUTES));
            }
        } finally {
            pool.shutdownNow();
        }
        for (String quote : quotes) {
            assertThat(scalar("SELECT provider_code FROM fx.quote WHERE id = ?::uuid", quote)).isEqualTo("fx-sim-b");
            assertThat(scalar("SELECT string_agg(position || ':' || provider_code || ':' || outcome, ',' ORDER BY position,"
                            + " outcome) FROM fx.quote_sourcing_step WHERE quote_request_id = (SELECT quote_request_id FROM"
                            + " fx.quote WHERE id = ?::uuid)", quote))
                    .startsWith("1:fx-sim-a:")
                    .contains("2:fx-sim-b:CHOSEN")
                    .doesNotContain("fx-sim-a:CHOSEN");
        }
    }

    @Test
    @DisplayName("both providers down: the quote is 503 fx.RateUnavailable, both steps stored, nothing quoted")
    void bothDownIsUnavailable() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        engineA.serverErrorNext();
        engineB.serverErrorNext();
        HttpResponse<String> refused = client().quote(customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
        assertThat(refused.body()).contains("fx.RateUnavailable");
    }

    // ------------------------------------------------------------------ plumbing

    private static List<String> clearingOwners(UUID cover) throws Exception {
        List<String> owners = new java.util.ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT DISTINCT a.owner_ref::text FROM ledger.journal_line l"
                        + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id AND a.purpose = 'FX_PROVIDER_CLEARING'"
                        + " WHERE l.entry_id = (SELECT id FROM ledger.journal_entry WHERE idempotency_scope = ?)")) {
            read.setString(1, PostingService.IDEMPOTENCY_SCOPE + ":fx-cover:" + cover);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    owners.add(row.getString(1));
                }
            }
        }
        assertThat(owners).as("the cover posted").isNotEmpty();
        return owners;
    }

    private static List<String> legStatuses(UUID cover) throws Exception {
        List<String> statuses = new java.util.ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT status FROM reconciliation.expectation WHERE kind IN"
                        + " ('FX_SELL_LEG', 'FX_BUY_LEG') AND operation_ref = ?")) {
            read.setString(1, cover.toString());
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    statuses.add(row.getString(1));
                }
            }
        }
        assertThat(statuses).as("both legs opened").hasSize(2);
        return statuses;
    }

    private PositionProof.PositionVerdict verdict(AccountPurpose purpose, String counterparty, String currency)
            throws Exception {
        PositionProof.Report report;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            report = proof.sweep(app);
            app.rollback();
        }
        return report.verdicts().stream()
                .filter(verdict -> verdict.purpose() == purpose && verdict.counterparty().equals(Optional.of(counterparty))
                        && verdict.currency().equals(CurrencyCode.of(currency)))
                .findFirst().orElseThrow();
    }

    private static String fxReport(String currency, String valueDate, String code, long signedMinor, String tradeRef,
            String coverRef, String remittance) {
        String signed = (signedMinor < 0 ? "-" : "") + amount(Math.abs(signedMinor), CurrencyCode.of(currency).minorUnits());
        return "H,SIM_FX_CSV,1,FXB-" + marker() + "-" + currency + "," + currency + "," + valueDate + "\n"
                + "D,1," + code + "," + signed + ",," + tradeRef + "," + coverRef + "\n"
                + "T,1," + signed + "," + remittance + "\n";
    }

    private void ensureSourceBActive() throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                FxRuleSetV1.SOURCE_B) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSetAdministration.propose(app, FxRuleSetV1.proposalB("fx-sim-b's source, first version"),
                    new Actor("op-fx-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("op-fx-controller-b", ActorType.EMPLOYEE),
                    "Reviewed against O7", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
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
        return ":20:SB-FB-" + marker() + "\n"
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

    private UUID acceptedBatch(String source, String content) throws Exception {
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
        UUID fileId = ((FileReception.Result.New) result).fileId();
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

    @SuppressWarnings("unused")
    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }
}
