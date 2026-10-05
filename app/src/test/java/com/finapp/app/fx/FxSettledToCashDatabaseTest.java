package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxCoverFixtures.positionNet;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
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
 * Scenario 10 for FX - a conversion explained all the way to cash (`P9-TSK-013`; PHASE_9_PLAN.md
 * sections 12.4(c) and 12.9): the cover executes; the provider's EUR and USD files settle each leg
 * by its own currency's key - nothing posts at hop 1, each file opening one REMITTANCE on fx-sim-a's
 * own clearing; the bank's statements discharge both remittances at hop 2. What this conversion
 * moved on {@code FX_POSITION} and on {@code FX_PROVIDER_CLEARING(fx-sim-a)} nets to 0 in each
 * currency, and no break was raised. (The shared container's other suites leave their own covers
 * open, so the zero proven is this conversion's - the FX books proof judges the container whole.)
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("FX settled to cash: two legs, two remittances, two statements - every movement netted (P9-TSK-013)")
@SuppressWarnings("try")
class FxSettledToCashDatabaseTest {

    private static final String FX_SOURCE = "fx-sim-a.trade-report";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static SimulatedFxEngine engine;

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

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(engine, Map.of("EUR/USD", "1.0850240000"));
        }
        registry.add("finapp.fx.provider.url", () -> engine.baseUrl().toString());
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @BeforeEach
    void v1AndFreshReferences() throws Exception {
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
    }

    @Test
    @DisplayName("1,000.00 EUR -> USD: the cover, the provider's two files allocated leg by leg, two remittances, the"
            + " bank's two statements - FX_POSITION and fx-sim-a's clearing net to 0 for this conversion, no break")
    void aConversionIsExplainedToCash() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("2000.00", "EUR"));
        String trade = convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");
        UUID cover = coverOf(trade);
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(FxCoverFixtures.status(cover)).isEqualTo("EXECUTED");
        String reference = FxCoverFixtures.reference(cover, 1);
        String tradeRef = scalar("SELECT provider_trade_ref FROM fx.cover_execution WHERE cover_id = ?", cover);
        String valueDate = scalar("SELECT value_date::text FROM fx.cover_execution WHERE cover_id = ?", cover);

        // Hop 1: the provider's two single-currency files, each leg keyed by its cover reference.
        String eurRemittance = "FXA-" + digits(8);
        String usdRemittance = "FXA-" + digits(8);
        UUID eurReport = acceptedBatch(FX_SOURCE, ("H,SIM_FX_CSV,1,FXB-" + marker() + "-EUR,EUR," + valueDate + "\n"
                + "D,1,SOLD,-1000.00,," + tradeRef + "," + reference + "\n"
                + "T,1,-1000.00," + eurRemittance + "\n").getBytes(StandardCharsets.UTF_8));
        UUID usdReport = acceptedBatch(FX_SOURCE, ("H,SIM_FX_CSV,1,FXB-" + marker() + "-USD,USD," + valueDate + "\n"
                + "D,1,BOUGHT,1085.02,," + tradeRef + "," + reference + "\n"
                + "T,1,1085.02," + usdRemittance + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(scalar("SELECT journal_entry_id::text FROM settlement.batch WHERE id = ?", eurReport))
                .as("no fee, nothing posts at hop 1").isNull();
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE operation_ref = ? AND kind = 'FX_SELL_LEG'",
                cover.toString())).isEqualTo("SETTLED");
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE operation_ref = ? AND kind = 'FX_BUY_LEG'",
                cover.toString())).isEqualTo("SETTLED");
        assertThat(scalar("SELECT direction || ' ' || amount_minor || ' ' || currency FROM reconciliation.expectation"
                + " WHERE kind = 'REMITTANCE' AND operation_ref = ?", eurReport.toString())).isEqualTo("OUTBOUND 100000 EUR");
        assertThat(scalar("SELECT direction || ' ' || amount_minor || ' ' || currency FROM reconciliation.expectation"
                + " WHERE kind = 'REMITTANCE' AND operation_ref = ?", usdReport.toString())).isEqualTo("INBOUND 108502 USD");

        // Hop 2: the bank's cash discharges both remittances.
        UUID eurStatement = acceptedBatch(BANK_SOURCE, nextStatement("EUR", valueDate, "D", "1000.00", eurRemittance));
        UUID usdStatement = acceptedBatch(BANK_SOURCE, nextStatement("USD", valueDate, "C", "1085.02", usdRemittance));
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE' AND operation_ref = ?",
                eurReport.toString())).isEqualTo("SETTLED");
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE' AND operation_ref = ?",
                usdReport.toString())).isEqualTo("SETTLED");

        // At rest: what this conversion moved nets to zero, in each currency, on both positions.
        assertThat(positionNet(trade, "EUR")).isZero();
        assertThat(positionNet(trade, "USD")).isZero();
        for (String currency : new String[] {"EUR", "USD"}) {
            assertThat(count("SELECT COALESCE(SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor"
                    + " END), 0) FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                    + " AND a.purpose = 'FX_PROVIDER_CLEARING' WHERE l.currency = ? AND l.entry_id IN ("
                    + " SELECT journal_entry_id FROM fx.cover_execution WHERE cover_id = ?"
                    + " UNION SELECT journal_entry_id FROM settlement.batch WHERE id IN (?, ?))",
                    currency, cover, eurStatement, usdStatement))
                    .as("fx-sim-a's %s clearing nets to 0 over the cover and the cash", currency).isZero();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.break b WHERE b.expectation_id IN (SELECT id FROM"
                + " reconciliation.expectation WHERE operation_ref IN (?, ?, ?))",
                cover.toString(), eurReport.toString(), usdReport.toString())).as("no break").isZero();
    }

    // -----------------------------------------------------------------

    /** The bank account's next statement: the chain's next sequence, opening at the last closing. */
    private static byte[] nextStatement(String currency, String date, String side, String amount, String remittance)
            throws Exception {
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
        long moved = new BigDecimal(amount).movePointRight(2).longValueExact();
        long closing = "C".equals(side) ? opening + moved : opening - moved;
        String text = ":20:SB-FX-" + marker() + "\n"
                + ":25:SIMBANK-" + currency + "-01\n"
                + ":28C:" + (sequence + 1) + "\n"
                + ":60F:" + balance(date, currency, opening) + "\n"
                + ":61:" + date + "," + side + "," + amount + "," + remittance + "\n"
                + ":62F:" + balance(date, currency, closing) + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String balance(String date, String currency, long minor) {
        return (minor < 0 ? "D," : "C,") + date + "," + currency + ","
                + BigDecimal.valueOf(Math.abs(minor), 2).toPlainString();
    }

    private UUID acceptedBatch(String source, byte[] bytes) throws Exception {
        FileReception.Result result;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            result = settlementTransactionRunner.inTransaction(uow -> reception.receive(uow,
                    new FileReception.Delivery(source, com.finapp.settlement.DeliveryChannel.PULL, bytes,
                            java.util.Optional.empty(), Actor.SYSTEM,
                            com.finapp.settlement.SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                            CorrelationContext.current().orElseThrow())));
        }
        assertThat(result).as("%s received", source).isInstanceOf(FileReception.Result.New.class);
        UUID fileId = ((FileReception.Result.New) result).fileId();
        parsing.sweep();
        acceptance.sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED:-");
        return UUID.fromString(scalar("SELECT id::text FROM settlement.batch WHERE file_id = ?", fileId));
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    private static String marker() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String digits(int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append(RANDOMNESS.nextInt(10));
        }
        return digits.toString();
    }
}
