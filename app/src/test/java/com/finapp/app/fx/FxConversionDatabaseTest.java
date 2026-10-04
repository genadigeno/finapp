package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.key;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.fx.AvailabilitySubject;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.ledger.PostingService;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The wallet conversion over HTTP (`P9-TSK-009`; PHASE_9_PLAN.md section 12.4; INV-FX-01, -03,
 * -04, -05, -07, INV-BAL-04, INV-LED-01): the worked examples (a), (d) and (e) posted exactly, line
 * by line, through FX_POSITION per currency; the destination wallet opened once; the trade, the
 * quote's two edges, the cover born DISPATCHED with T1, the events and the audit record; refusals
 * that leave the quote reusable; the doors' ownership and closed body.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the wallet conversion, posted exactly (P9-TSK-009)")
class FxConversionDatabaseTest {

    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private FxAvailability availability;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(new byte[32]);
            // The worked examples' provider rates (section 12.4): rp 1.085024, 149.8742, 397.5121.
            FxTestClient.rates(engine, Map.of("EUR/USD", "1.0850240000", "USD/JPY", "149.8742000000",
                    "BHD/JPY", "397.5121000000"));
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
    }

    @Test
    @DisplayName("the worked examples post exactly: (a) EUR->USD by source, (d) by destination, (e) USD->JPY"
            + " and BHD->JPY - each currency balanced through FX_POSITION, margin and residual in the computed"
            + " leg, the USD and JPY wallets opened once")
    void theWorkedExamplesPostExactly() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        client.addCurrency(customer, product, "BHD");
        fund(postings, product, money("3000.00", "EUR"));
        fund(postings, product, money("20.000", "BHD"));

        String a = convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");
        assertThat(lines(a)).containsExactlyInAnyOrder(
                "CUSTOMER_WALLET EUR DEBIT 100000", "FX_POSITION EUR CREDIT 100000",
                "FX_POSITION USD DEBIT 108502", "ROUNDING_RESIDUAL USD DEBIT 1",
                "CUSTOMER_WALLET USD CREDIT 107960", "FX_SPREAD_REVENUE USD CREDIT 543");

        String d = convertedTrade(client, customer, "EUR", "USD", "FIXED_DESTINATION", "1000.00");
        assertThat(lines(d)).containsExactlyInAnyOrder(
                "CUSTOMER_WALLET EUR DEBIT 92627", "FX_POSITION EUR CREDIT 92164", "FX_SPREAD_REVENUE EUR CREDIT 463",
                "FX_POSITION USD DEBIT 100000", "CUSTOMER_WALLET USD CREDIT 100000");

        String usdJpy = convertedTrade(client, customer, "USD", "JPY", "FIXED_SOURCE", "250.00");
        assertThat(lines(usdJpy)).containsExactlyInAnyOrder(
                "CUSTOMER_WALLET USD DEBIT 25000", "FX_POSITION USD CREDIT 25000",
                "FX_POSITION JPY DEBIT 37469", "CUSTOMER_WALLET JPY CREDIT 37281", "FX_SPREAD_REVENUE JPY CREDIT 187",
                "ROUNDING_RESIDUAL JPY CREDIT 1");

        String bhdJpy = convertedTrade(client, customer, "BHD", "JPY", "FIXED_SOURCE", "12.345");
        assertThat(lines(bhdJpy)).containsExactlyInAnyOrder(
                "CUSTOMER_WALLET BHD DEBIT 12345", "FX_POSITION BHD CREDIT 12345",
                "FX_POSITION JPY DEBIT 4907", "ROUNDING_RESIDUAL JPY DEBIT 1",
                "CUSTOMER_WALLET JPY CREDIT 4883", "FX_SPREAD_REVENUE JPY CREDIT 25");

        assertThat(count("SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ? AND purpose = 'CUSTOMER_WALLET'",
                        product))
                .as("EUR and BHD opened by the customer; USD and JPY each opened once, by a conversion")
                .isEqualTo(4);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'accounts.WalletCurrencyAdded'"
                        + " AND aggregate_id = ?", product))
                .isEqualTo(4);

        // The facts of (a): the quote's two edges, the cover born DISPATCHED with T1, the events, the audit.
        String quote = scalar("SELECT quote_id::text FROM fx.trade WHERE id = ?::uuid", a);
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?::uuid", quote)).isEqualTo("EXECUTED");
        assertThat(scalar("SELECT string_agg(to_status, ',' ORDER BY occurred_at, to_status) FROM fx.quote_event"
                        + " WHERE quote_id = ?::uuid", quote))
                .isEqualTo("ISSUED,ACCEPTED,EXECUTED");
        assertThat(scalar("SELECT status || '|' || kind || '|' || attempts || '|' || fixed_amount_minor FROM fx.cover"
                        + " WHERE quote_id = ?::uuid", quote))
                .isEqualTo("DISPATCHED|COVER|1|100000");
        assertThat(scalar("SELECT a.client_reference ~ '^T-[0-9a-f]{32}$' AND a.provider_quote_ref = q.provider_quote_reference"
                        + " FROM fx.cover_attempt a JOIN fx.cover c ON c.id = a.cover_id JOIN fx.quote q ON q.id = c.quote_id"
                        + " WHERE q.id = ?::uuid", quote))
                .isEqualTo("t");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type IN ('fx.FxQuoteAccepted',"
                        + " 'fx.FxTradeExecuted') AND aggregate_id IN (?::uuid, ?::uuid)", quote, a))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.ConversionExecuted'"
                        + " AND target_id = ?", a))
                .isEqualTo(1);
        assertThat(scalar("SELECT executed_rate = customer_rate FROM fx.trade WHERE id = ?::uuid", a)).isEqualTo("t");
        // The opener register's proof: wallet <-> FX_POSITION, FX_SPREAD_REVENUE, ROUNDING_RESIDUAL
        // - none a reconciled position, so the conversion opens no expectation.
        ClearingLineCopies.assertOpensNothing("fx-trade:" + a);
    }

    @Test
    @DisplayName("refusals leave the quote reusable: insufficient funds is 422 with nothing written - no wallet"
            + " opened - and the same quote converts once funded; a suspended pair is 409")
    void refusalsLeaveTheQuoteReusable() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("100.00", "EUR"));
        String quote = client.quoteId(customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");

        HttpResponse<String> poor = client.convert(customer, quote, key());
        assertThat(poor.statusCode()).as(poor.body()).isEqualTo(422);
        assertThat(poor.body()).contains("fx.InsufficientFunds");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?::uuid", quote)).isEqualTo("ISSUED");
        assertThat(count("SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ? AND currency = 'USD'", product))
                .as("the destination wallet the attempt opened was rolled back with it")
                .isZero();
        assertThat(count("SELECT count(*) FROM fx.trade WHERE quote_id = ?::uuid", quote)).isZero();

        fund(postings, product, money("1000.00", "EUR"));
        String key = key();
        HttpResponse<String> converted = client.convert(customer, quote, key);
        assertThat(converted.statusCode()).as(converted.body()).isEqualTo(201);
        HttpResponse<String> replay = client.convert(customer, quote, key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(field(replay.body(), "id")).isEqualTo(field(converted.body(), "id"));
        HttpResponse<String> again = client.convert(customer, quote, key());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("fx.QuoteAlreadyAccepted");

        String suspended = client.quoteId(customer, "EUR", "GBP", "FIXED_SOURCE", "10.00");
        suspend("EUR-GBP");
        try {
            HttpResponse<String> refused = client.convert(customer, suspended, key());
            assertThat(refused.statusCode()).isEqualTo(409);
            assertThat(refused.body()).contains("fx.PairSuspended");
            assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?::uuid", suspended)).isEqualTo("ISSUED");
        } finally {
            restart("EUR-GBP");
        }
    }

    @Test
    @DisplayName("the doors: a closed body, another owner's quote and trade absent, a retry replayed and a reused key refused, a cancelled quote not"
            + " acceptable, no wallet agreement refused, no session 401")
    void theDoors() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer owner = client.verifiedCustomer();
        FxTestClient.Customer stranger = client.verifiedCustomer();
        UUID product = client.openWallet(owner, "EUR");
        fund(postings, product, money("500.00", "EUR"));
        String quote = client.quoteId(owner, "EUR", "USD", "FIXED_SOURCE", "100.00");

        HttpResponse<String> open = client.post("/v1/me/fx/conversions",
                "{\"quoteId\":\"" + quote + "\",\"rate\":\"9.0\"}", owner.token(), key());
        assertThat(open.statusCode()).isEqualTo(422);
        assertThat(open.body()).contains("api.ValidationFailed");
        assertThat(client.convert(stranger, quote, key()).body()).contains("fx.QuoteNotFound");
        assertThat(client.convert(owner, "not-a-quote", key()).statusCode()).isEqualTo(404);
        assertThat(client.post("/v1/me/fx/conversions", "{\"quoteId\":\"" + quote + "\"}", null, key()).statusCode())
                .isEqualTo(401);

        String once = key();
        HttpResponse<String> converted = client.convert(owner, quote, once);
        assertThat(converted.statusCode()).isEqualTo(201);
        String trade = field(converted.body(), "id");
        // The retry after a lost response: the same key and body replay the stored 201, not a second
        // booking and not QuoteAlreadyAccepted; the same key under another body is api.Conflict.
        HttpResponse<String> replayed = client.convert(owner, quote, once);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "id")).isEqualTo(trade);
        String second = client.quoteId(owner, "EUR", "USD", "FIXED_SOURCE", "50.00");
        assertThat(client.convert(owner, second, once).body()).contains("api.Conflict");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?::uuid", second)).isEqualTo("ISSUED");
        assertThat(count("SELECT count(*) FROM fx.trade WHERE quote_id = ?::uuid", quote)).isEqualTo(1);
        assertThat(client.get("/v1/me/fx/conversions/" + trade, owner.token()).statusCode()).isEqualTo(200);
        assertThat(client.get("/v1/me/fx/conversions/" + trade, stranger.token()).body()).contains("fx.TradeNotFound");
        assertThat(converted.body()).doesNotContain("providerRate", "PQ-");

        String cancelled = client.quoteId(owner, "EUR", "USD", "FIXED_SOURCE", "100.00");
        assertThat(client.post("/v1/me/fx/quotes/" + cancelled + "/cancellation", null, owner.token(), key()).statusCode())
                .isEqualTo(200);
        assertThat(client.convert(owner, cancelled, key()).body()).contains("fx.QuoteNotAcceptable");

        String unwalleted = client.quoteId(stranger, "EUR", "USD", "FIXED_SOURCE", "100.00");
        HttpResponse<String> missing = client.convert(stranger, unwalleted, key());
        assertThat(missing.statusCode()).isEqualTo(422);
        assertThat(missing.body()).contains("fx.SourceWalletMissing");
    }

    // -----------------------------------------------------------------

    private String convertedTrade(FxTestClient client, FxTestClient.Customer customer, String source,
            String destination, String side, String amount) throws Exception {
        String quote = client.quoteId(customer, source, destination, side, amount);
        HttpResponse<String> converted = client.convert(customer, quote, key());
        assertThat(converted.statusCode()).as(converted.body()).isEqualTo(201);
        return field(converted.body(), "id");
    }

    /** The trade's entry, as "PURPOSE CURRENCY DIRECTION MINOR" lines. */
    private static List<String> lines(String trade) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT a.purpose, l.currency, l.direction, l.amount_minor FROM ledger.journal_line l"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " JOIN fx.trade t ON t.journal_entry_id = l.entry_id WHERE t.id = ?::uuid")) {
            select.setString(1, trade);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1) + " " + row.getString(2) + " " + row.getString(3) + " " + row.getLong(4));
                }
            }
        }
        return lines;
    }

    private void suspend(String pair) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            availability.disable(app, AvailabilitySubject.pair(pair), controller(), "suspended for the suite",
                    Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    private void restart(String pair) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            FxAvailability.EnableProposed proposed = availability.proposeEnable(app, AvailabilitySubject.pair(pair),
                    controller(), "restarted by the suite", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            availability.approveEnable(app, proposed.requestId(), controller(), "restarted by the suite", Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    private static Actor controller() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
