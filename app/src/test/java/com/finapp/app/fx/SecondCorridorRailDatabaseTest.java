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
 * The second corridor rail (`P9-TSK-026`, M9.8; ADR-0080's negative consequence made real; {@code INV-RAIL-02},
 * {@code INV-RAIL-04}, {@code INV-SET-05}): in a deployment where only {@code corridor-sim-b} is operable, a corridor
 * policy listing {@code corridor-sim-a} then {@code corridor-sim-b} for EUR -> USD/US selects {@code b} at registration
 * (each candidate a stored step); the beneficiary tokenised on {@code b} is routed, paid and completed on {@code b}, onto
 * {@code b}'s OWN clearing; {@code b}'s report settles it, a line on {@code a}'s report naming it is never allocated, and
 * {@code b}'s position is proven on its own.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("the second corridor rail (P9-TSK-026)")
class SecondCorridorRailDatabaseTest {

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final String ROUTING = "/v1/operator/routing-policy/versions";
    private static final String SOURCE_A = "corridor-sim-a.settlement";
    private static final String SOURCE_B = "corridor-sim-b.settlement";
    private static final String BANK_SOURCE = "simulated-bank.statement";
    private static final UUID CORRIDOR_SIM_B = UUID.fromString("01a0e2bc-8200-7025-8000-000000000002");
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final SimulatedCorridorEngine CORRIDOR_B = corridor();
    private static SimulatedFxEngine fxEngine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
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

    /** Only corridor-sim-b is configured: corridor-sim-a is not operable in this deployment. */
    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (fxEngine == null) {
            fxEngine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(fxEngine, Map.of("EUR/USD", "1.0850240000"));
        }
        registry.add("finapp.fx.provider.url", () -> fxEngine.baseUrl().toString());
        registry.add("finapp.corridor.provider.b.url", () -> CORRIDOR_B.baseUrl().toString());
    }

    @AfterAll
    static void stop() {
        fxEngine.close();
        CORRIDOR_B.close();
    }

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    @BeforeEach
    void theSecondRailEverywhere() throws Exception {
        CORRIDOR_B.acceptOnReceipt(true);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        ensureCorridorPolicyWithSecondRail();
        ensureRoutingWithSecondRail();
        ensureRuleSet(CorridorRuleSetV1.SOURCE, CorridorRuleSetV1.proposal("corridor-sim-a's source, first version"));
        ensureRuleSet(CorridorRuleSetV1.SOURCE_B, CorridorRuleSetV1.proposalB("corridor-sim-b's source, first version"));
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
    }

    @Test
    @DisplayName("corridor-sim-a not operable: the beneficiary is selected and tokenised on corridor-sim-b (both steps"
            + " stored), paid on it, completed onto corridor-sim-b's OWN clearing; a line on corridor-sim-a's report"
            + " naming it is never allocated; corridor-sim-b's report and the bank discharge it and its position is proven")
    void aBeneficiaryOnBIsPaidAndSettledOnB() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("1100.00", "EUR"));
        String grant = CORRIDOR_B.issueGrant(new SimulatedCorridorEngine.Beneficiary("US", "USD", "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"US\",\"currency\":\"USD\",\"grant\":\""
                + grant + "\",\"name\":\"Clear Person\",\"nickname\":\"Payee\",\"entityType\":\"INDIVIDUAL\"}",
                customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        String beneficiary = field(registered.body(), "id");
        assertThat(scalar("SELECT rail FROM crossborder.beneficiary WHERE id = ?::uuid", beneficiary)).isEqualTo("corridor-sim-b");
        assertThat(scalar("SELECT string_agg(s.rail || ':' || s.outcome, ',' ORDER BY s.ordinal)"
                        + " FROM crossborder.corridor_selection_step s JOIN crossborder.beneficiary_registration r"
                        + " ON r.selection_id = s.selection_id JOIN crossborder.beneficiary b ON b.registration_id = r.id"
                        + " WHERE b.id = ?::uuid", beneficiary))
                .as("each candidate judged in policy order, stored").isEqualTo("corridor-sim-a:UNAVAILABLE,corridor-sim-b:CHOSEN");

        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"1000.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        HttpResponse<String> paid = client().post(PAYMENTS, "{\"quoteId\":\"" + field(offered.body(), "id") + "\"}",
                customer.token(), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?", payment));
        assertThat(scalar("SELECT rail || ':' || status FROM payments.outbound_credit WHERE id = ?", credit))
                .as("routed to and completed on the beneficiary's own rail").isEqualTo("corridor-sim-b:COMPLETED");
        assertThat(scalar("SELECT a.owner_ref::text FROM ledger.journal_line l JOIN ledger.ledger_account a"
                + " ON a.id = l.ledger_account_id AND a.purpose = 'CORRIDOR_CLEARING' WHERE l.entry_id = (SELECT id FROM"
                + " ledger.journal_entry WHERE idempotency_scope = ?)", PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:"
                + credit)).as("onto corridor-sim-b's OWN clearing").isEqualTo(CORRIDOR_SIM_B.toString());
        String reference = scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?", credit);
        String providerReference = scalar("SELECT provider_reference FROM payments.outbound_credit WHERE id = ?", credit);
        long destination = Long.parseLong(scalar("SELECT amount_minor::text FROM payments.outbound_credit WHERE id = ?", credit));

        // The planted cross-counterparty allocation: corridor-sim-a's report naming corridor-sim-b's credit reaches nothing.
        UUID planted = acceptedBatch(SOURCE_A, report("-" + amount(destination, 2), "", providerReference, reference,
                "-" + amount(destination, 2)));
        matchUntilQuiet();
        assertThat(count("SELECT count(*) FROM reconciliation.external_item i JOIN reconciliation.reconciliation_batch r"
                + " ON r.id = i.run_id WHERE r.batch_id = ? AND i.status = 'MATCHED'", planted))
                .as("a line on another counterparty's report is never allocated to corridor-sim-b's payout").isZero();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_PAYOUT' AND operation_ref = ?",
                credit.toString())).isEqualTo("OPEN");

        // corridor-sim-b's own report (the payout and its USD 1.20 fee) and the bank: SETTLED and discharged.
        // corridor-sim-b remits in its OWN shape (XBB-), so the bank's line is attributed to its report alone.
        String remittance = "XBB-" + (10_000 + RANDOMNESS.nextInt(80_000));
        UUID settled = acceptedBatch(SOURCE_B, report("-" + amount(destination, 2), "1.20", providerReference, reference,
                "-" + amount(destination + 120, 2), remittance));
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'CROSSBORDER_PAYOUT' AND operation_ref = ?",
                credit.toString())).isEqualTo("SETTLED");
        acceptedBatch(BANK_SOURCE, nextStatement("USD", today(), "D", destination + 120, remittance));
        matchUntilQuiet();
        assertThat(scalar("SELECT status FROM reconciliation.expectation WHERE kind = 'REMITTANCE' AND operation_ref = ?",
                settled.toString())).isEqualTo("SETTLED");
        assertThat(verdict(AccountPurpose.CORRIDOR_CLEARING, "corridor-sim-b", "USD").explained())
                .as("corridor-sim-b's USD position, proven on its own").isTrue();
    }

    // ------------------------------------------------------------------ plumbing

    private static String report(String signed, String fee, String providerReference, String reference, String net) {
        return report(signed, fee, providerReference, reference, net, "XBA-" + (10_000 + RANDOMNESS.nextInt(80_000)));
    }

    private static String report(String signed, String fee, String providerReference, String reference, String net,
            String remittance) {
        return "H,SIM_CORRIDOR_CSV,1,XB-" + marker() + ",USD," + today() + "\n"
                + "D,1,CREDITED," + signed + "," + fee + "," + providerReference + "," + reference + "\n"
                + "T,1," + net + "," + remittance + "\n";
    }

    private void ensureCorridorPolicyWithSecondRail() throws Exception {
        if (count("SELECT count(*) FROM crossborder.corridor c JOIN crossborder.corridor_policy_version v ON v.id = c.policy_id"
                + " WHERE v.status = 'ACTIVE' AND c.source_currency = 'EUR' AND c.destination_currency = 'USD'"
                + " AND c.destination_country = 'US' AND 'corridor-sim-b' = ANY(c.rails)") > 0) {
            return;
        }
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
        HttpResponse<String> proposed = client().post(POLICIES,
                CorridorPolicyV1.jsonWithSecondRail("EUR to USD/US over both corridor rails"), first, FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved = client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                "{\"reason\":\"checked\"}", second, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
    }

    /** Routing version 5 carried forward, the cross-border credit onto corridor-sim-a then corridor-sim-b. */
    private void ensureRoutingWithSecondRail() throws Exception {
        if (count("SELECT count(*) FROM payments.routing_rule_rail WHERE rail = 'corridor-sim-b'") == 0) {
            String operator = sessionWith(RoleName.LEDGER_OPERATOR);
            HttpResponse<String> created = client().post(ROUTING, "{\"rules\":["
                    + "{\"direction\":\"PAY_IN\",\"instrumentKind\":\"CARD_TOKEN\",\"rails\":[\"card\"]},"
                    + "{\"direction\":\"PAY_OUT\",\"instrumentKind\":\"BANK_ACCOUNT\",\"rails\":[\"corridor-sim-a\","
                    + "\"corridor-sim-b\"],\"requiresDestinationCountry\":true},"
                    + "{\"direction\":\"PAY_OUT\",\"instrumentKind\":\"BANK_ACCOUNT\",\"rails\":[\"instant\"]},"
                    + "{\"direction\":\"PAY_IN\",\"instrumentKind\":\"BANK_ACCOUNT\",\"rails\":[\"instant\"]},"
                    + "{\"direction\":\"PAY_IN\",\"instrumentKind\":\"WALLET\",\"rails\":[\"book\"]}],"
                    + "\"reason\":\"the second corridor rail beside the first (P9-TSK-026)\"}", operator, FxTestClient.key());
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version v JOIN payments.routing_rule r"
                + " ON r.policy_version_id = v.id JOIN payments.routing_rule_rail rr ON rr.rule_id = r.id"
                + " WHERE rr.rail = 'corridor-sim-b' AND v.effective_from <= ?", java.sql.Timestamp.from(Instant.now())) == 0) {
            assertThat(System.nanoTime()).as("the second rail's routing version is not yet in force").isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private void ensureRuleSet(UUID source, com.finapp.reconciliation.RuleSetProposal proposal) throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'", source) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSetAdministration.propose(app, proposal, new Actor("op-corridor-controller-a", ActorType.EMPLOYEE),
                    Instant.now(), CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE),
                    "Reviewed against O7", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
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
        return ":20:SB-XS-" + marker() + "\n"
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

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String marker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).replaceAll("[0-9]", "q");
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "sc." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        CounterpartyScreeningProvider clearSecondRailScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
