package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
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
 * Phase 9's reports and the payment trace (`P9-TSK-027`, PHASE_9_PLAN.md section 15; ADR-0072, {@code INV-AUD-02}):
 * the FX position with its open legs, a month's FX revenue and a month's corridors, each read by an investigator with
 * {@code FX_INVESTIGATE} in one snapshot and audited at every serving; the trace walking a payment's identifier chain;
 * a malformed or unreportable month 422 before any read; a customer and a price-setter refused; the in-transit age
 * gauge reading a real age, never NaN, once a payment is in transit.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("Phase 9's reports and the trace (P9-TSK-027)")
class Phase9ReportsDatabaseTest {

    private static final String REPORTS = "/v1/operator/reports";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private MeterRegistry meters;

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
    void policies() throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        awaitRoutingVersionInForce();
    }

    @Test
    @DisplayName("the three reports: the FX position with the new conversion's open cover leg, the month's EUR-USD"
            + " revenue row and the EUR-USD-US corridor row with its fee - each audited at every serving; a malformed"
            + " or future month 422 and unaudited; a customer and an FX controller refused")
    void theReportsReadAndAreAudited() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        String trade = convertedTrade(client(), customer, "EUR", "USD", "FIXED_SOURCE", "100.00");
        UUID cover = FxCoverFixtures.coverOf(trade);
        String paid = paidCrossBorder();
        String investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        long audited = audits();

        HttpResponse<String> position = client().get(REPORTS + "/fx/position", investigator);
        assertThat(position.statusCode()).as(position.body()).isEqualTo(200);
        assertThat(position.body()).contains("\"currency\":\"EUR\"", "\"coverId\":\"" + cover + "\"", "\"truncated\"");

        String month = YearMonth.now(ZoneOffset.UTC).toString();
        HttpResponse<String> revenue = client().get(REPORTS + "/fx/revenue?month=" + month, investigator);
        assertThat(revenue.statusCode()).as(revenue.body()).isEqualTo(200);
        assertThat(revenue.body()).contains("\"pair\":\"EUR-USD\"", "\"purpose\":\"CONVERSION\"", "\"month\":\"" + month + "\"");

        HttpResponse<String> corridors = client().get(REPORTS + "/cross-border/corridors", investigator);
        assertThat(corridors.statusCode()).as(corridors.body()).isEqualTo(200);
        assertThat(corridors.body()).contains("\"corridor\":\"EUR-USD-US\"", "\"feesCharged\":{\"amount\":");
        assertThat(audits() - audited).as("every serving audited").isEqualTo(3);

        for (String bad : new String[] {"2026-13", "202610", YearMonth.now(ZoneOffset.UTC).plusMonths(1).toString()}) {
            HttpResponse<String> refused = client().get(REPORTS + "/fx/revenue?month=" + bad, investigator);
            assertThat(refused.statusCode()).as(bad + ": " + refused.body()).isEqualTo(422);
        }
        assertThat(audits() - audited).as("a refused period reads and audits nothing").isEqualTo(3);

        assertThat(client().get(REPORTS + "/fx/position", customer.token()).statusCode()).isIn(401, 403);
        assertThat(client().get(REPORTS + "/fx/position", sessionWith(RoleName.FX_CONTROLLER)).statusCode()).isEqualTo(403);
        assertThat(paid).isNotNull();
    }

    @Test
    @DisplayName("the trace walks a payment's identifier chain - quote, offer, outbound credit with E and the provider's"
            + " reference, the completion entry, CROSSBORDER_PAYOUT, the trade and its cover with T1 - audited; the"
            + " in-transit age reads a real age, never NaN; an unknown payment is 404")
    void theTraceWalksTheChain() throws Exception {
        String payment = paidCrossBorder();
        String investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> trace = client().get("/v1/operator/cross-border/payments/" + payment + "/trace", investigator);
        assertThat(trace.statusCode()).as(trace.body()).isEqualTo(200);
        String credit = scalar("SELECT outbound_credit_id::text FROM crossborder.payment WHERE id = ?::uuid", payment);
        String reference = scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?::uuid", credit);
        String quote = scalar("SELECT quote_id::text FROM crossborder.payment WHERE id = ?::uuid", payment);
        String t1 = scalar("SELECT a.client_reference FROM fx.cover_attempt a JOIN fx.cover c ON c.id = a.cover_id"
                + " WHERE c.quote_id = ?::uuid AND a.attempt = 1", quote);
        assertThat(trace.body()).contains("\"outboundCreditId\":\"" + credit + "\"", "\"endToEndReference\":\"" + reference + "\"",
                "\"quoteId\":\"" + quote + "\"", "\"kind\":\"CROSSBORDER_PAYOUT\"", t1, "\"completionEntryId\":\"");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'crossborder.PaymentTraceRead'"
                + " AND target_id = ?", payment)).isEqualTo(1);
        assertThat(client().get("/v1/operator/cross-border/payments/" + UUID.randomUUID() + "/trace", investigator)
                .statusCode()).isEqualTo(404);

        Gauge age = meters.find("finapp.crossborder.payment.in.transit.age").tag("corridor", "EUR-USD-US").gauge();
        assertThat(age).isNotNull();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (Double.isNaN(age.value()) && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
        assertThat(age.value()).as("a real age once a payment is in transit").isNotNaN().isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------ plumbing

    /** Pays section 12.4(g)'s offer; the provider accepts at once - the payment IN_TRANSIT. */
    private String paidCrossBorder() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("1100.00", "EUR"));
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("US", "USD", "individual", "match"));
        HttpResponse<String> registered = client().post("/v1/me/cross-border/beneficiaries", "{\"country\":\"US\",\"currency\":"
                + "\"USD\",\"grant\":\"" + grant + "\",\"name\":\"Clear Person\",\"nickname\":\"Payee\",\"entityType\":"
                + "\"INDIVIDUAL\"}", customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        HttpResponse<String> offered = client().post("/v1/me/cross-border/quotes", "{\"beneficiaryId\":\""
                + field(registered.body(), "id") + "\",\"sourceCurrency\":\"EUR\",\"fixedSide\":\"FIXED_SOURCE\","
                + "\"amount\":\"1000.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        HttpResponse<String> paid = client().post("/v1/me/cross-border/payments", "{\"quoteId\":\"" + field(offered.body(), "id")
                + "\"}", customer.token(), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        return field(paid.body(), "paymentId");
    }

    private static long audits() throws Exception {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation IN ('fx.FxPositionReportRead',"
                + " 'fx.FxRevenueReportRead', 'crossborder.CorridorReportRead')");
    }

    private static void awaitRoutingVersionInForce() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version WHERE version = 5 AND effective_from <= ?",
                java.sql.Timestamp.from(Instant.now())) == 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
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
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the reports suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval", "{\"reason\":\"checked\"}",
                second, null).statusCode()).isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "rp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

        @Bean
        @Primary
        CounterpartyScreeningProvider clearReportsScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
