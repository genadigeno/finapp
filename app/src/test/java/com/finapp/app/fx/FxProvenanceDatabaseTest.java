package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.sql.Connection;
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
 * The FX investigator's door (`P9-TSK-013`; PHASE_9_PLAN.md sections 9 and 12.3): a trade's
 * provenance - reference, provider, internal and customer rates, the margins, the policy, the
 * entry, the cover and its execution - behind {@code FX_INVESTIGATE}, audited at every serving; a
 * role without it is refused 403 and an unknown trade is 404.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("an FX trade's provenance: the whole chain, FX_INVESTIGATE only, every read audited (P9-TSK-013)")
@SuppressWarnings("try")
class FxProvenanceDatabaseTest {

    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private Authorization authorization;

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
    @DisplayName("a reconciliation operator reads the whole chain of an executed conversion, audited once per read;"
            + " an FX controller is refused 403; an unknown trade is 404")
    void theProvenanceIsTheChain() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("2000.00", "EUR"));
        String trade = convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");
        dispatch.dispatchNow(coverOf(trade), Actor.SYSTEM);
        String path = "/v1/operator/fx/trades/" + trade + "/provenance";

        String operator = sessionWith(client, RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> read = client.get(path, operator);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(field(read.body(), "tradeId")).isEqualTo(trade);
        assertThat(field(read.body(), "providerCode")).isEqualTo("fx-sim-a");
        assertThat(field(read.body(), "providerRate")).isEqualTo("1.0850240000");
        assertThat(read.body())
                .contains("\"referenceRate\"", "\"internalRate\"", "\"customerRate\"", "\"disclosedMargin\"")
                .contains("\"customerSource\":{\"amount\":\"1000.00\",\"currency\":\"EUR\"}")
                .contains("\"positionDestination\":{\"amount\":\"1085.02\",\"currency\":\"USD\"}")
                .contains("\"status\":\"EXECUTED\"", "\"providerTradeReference\":\"FT-");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.TradeProvenanceRead'"
                + " AND target_id = ?", trade)).isEqualTo(1);
        client.get(path, operator);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.TradeProvenanceRead'"
                + " AND target_id = ?", trade)).as("every serving is recorded").isEqualTo(2);

        String controller = sessionWith(client, RoleName.FX_CONTROLLER);
        assertThat(client.get(path, controller).statusCode()).as("whoever sets prices does not investigate").isEqualTo(403);
        assertThat(client.get("/v1/operator/fx/trades/" + UUID.randomUUID() + "/provenance", operator).statusCode())
                .isEqualTo(404);
        assertThat(client.get("/v1/operator/fx/trades/not-a-trade/provenance", operator).statusCode()).isEqualTo(404);
    }

    /** A fresh identity granted {@code role} and signed in - the FX administration suite's fixture. */
    private String sessionWith(FxTestClient client, RoleName role) throws Exception {
        String login = "fxi." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                + " Lovelace\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity = UUID.fromString(FxTestClient.scalar(
                "SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client.post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null,
                FxTestClient.key());
        return field(session.body(), "sessionToken");
    }
}
