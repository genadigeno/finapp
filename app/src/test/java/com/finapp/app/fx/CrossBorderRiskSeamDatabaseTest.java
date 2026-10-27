package com.finapp.app.fx;

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
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingService;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.ParameterValue;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cross-border payments judged by risk against a live PostgreSQL, through the composition root's own wiring
 * (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 2.2 C1/C6, 7.2-7.4, 12.1, 12.3; ADR-0108; {@code INV-RSK-01}…{@code -04},
 * {@code INV-RSK-08}, {@code INV-CON-03}, {@code INV-IDEM-01}, {@code INV-XB-01}): the REAL cross-border payment door,
 * crossborder's authorization judged in its first transaction by risk's real adapters. A refusal consumes nothing - the
 * quote stays {@code ISSUED}, no cover, no hold, no credit, no payment - and counts nothing, while its sealed assessment
 * commits beside the claim's outcome; a limit refusal answers {@code LimitRefused}; one assessment per movement across
 * both seams; a retry under the same key and under a new key on the same quote replays; ten movements of one party count
 * ten; the unavailable path is C1's {@code DENY} at every band, recorded; the door's {@code 403} writes nothing; no lock
 * cycle against a concurrent transfer and withdrawal of the same party. <strong>Written and owed</strong> (owner decision
 * C5: the database tiers are skipped per task; the owner-authorised database pass before the exit review runs it).
 *
 * <p><strong>Its own container</strong> ({@code own-container}): it activates a EUR {@code CROSS_BORDER} version, after
 * which every cross-border payment in the database is judged by it - a shared container's EUR 600 payments would be
 * stepped up, its USD-source ones refused by the fail-safe (no EUR leg on {@code USD-BHD-BH}, {@code GBP-USD-US}). The
 * version is the C8 fixture with one hard rule added: {@code CORRIDOR_HIGH_RISK} over {@code EUR-JPY-JP} decides
 * {@code DENY} - so the JP corridor is this suite's refused corridor and US/USD its authorised one. The first case runs
 * before the activation (the production default).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("cross-border payments judged by risk: the seams in the real authorization, the refusal recorded and"
        + " consuming nothing (P13-TSK-011)")
class CrossBorderRiskSeamDatabaseTest {

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Clock CLOCK = Clock.systemUTC();
    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    // v1's rows with room for the ten open quotes of one party the races hold: v1's cap of five refused the sixth offer
    // 429 fx.TooManyOpenQuotes before any authorisation raced (P13-TST-001's first run of this owed suite).
    private static final FxTestPolicy.Wanted TEN_OPEN_QUOTES = new FxTestPolicy.Wanted(FxTestPolicy.V1.pairs(), 10);
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;
    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private TransferExecution transferExecution;
    @Autowired private Withdrawals engine;

    private static SimulatedCorridorEngine corridor() {
        try {
            return SimulatedCorridorEngine.start("a-corridor-risk-seam-test-key-of-32by".getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) throws Exception {
        if (fxEngine == null) {
            fxEngine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(fxEngine, Map.of());
        }
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.fx.provider.url", () -> fxEngine.baseUrl().toString());
        registry.add("finapp.corridor.provider.url", () -> CORRIDOR.baseUrl().toString());
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
    }

    @AfterAll
    static void stop() {
        fxEngine.close();
        CORRIDOR.close();
        provider.close();
    }

    @BeforeEach
    void policiesAndReferences() throws Exception {
        provider.reset();
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"xbr-{{request.headers.Idempotency-Key}}\",\"cycle\":\"C1\"}");
        FxTestPolicy.ensure(administration, policyStore, TEN_OPEN_QUOTES);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
    }

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    // ------------------------------------------------------------------ before any version

    @Test
    @Order(1)
    @DisplayName("no policy: the production default - ALLOW(NO_POLICY_ACTIVE) recorded and counted under the quote's id,"
            + " the payment authorised")
    void noPolicyRecordsAllowAndThePaymentIsAuthorised() throws Exception {
        assertThat(ACTIVATED).as("this case runs before the suite activates a version").isFalse();
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "US", "USD"), "100.00");
        HttpResponse<String> paid = pay(customer, quote, FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        assertThat(scalar("SELECT decision || ',' || fail_safe_reason || ',' || velocity_outcome FROM risk.risk_assessment"
                + " WHERE operation = 'CROSS_BORDER' AND command_ref = ?", quote))
                .isEqualTo("ALLOW,NO_POLICY_ACTIVE,COUNTED");
    }

    // ------------------------------------------------------------------ the refusal

    @Test
    @Order(2)
    @DisplayName("#aHighRiskCorridorIsRefusedAndRecorded: the corridor is an input and a dimension - its rule FIRED on the"
            + " sealed hits with its hard DENY, the corridor among the sealed inputs, the beneficiary the DESTINATION")
    void aHighRiskCorridorIsRefusedAndRecorded() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String beneficiary = beneficiary(customer, "JP", "JPY");
        String quote = offer(customer, beneficiary, "100.00");
        assertThat(pay(customer, quote, FxTestClient.key()).statusCode()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM risk.risk_rule_hit h JOIN risk.risk_assessment a ON a.id = h.assessment_id"
                + " WHERE a.command_ref = ? AND h.kind = 'CORRIDOR_HIGH_RISK' AND h.outcome = 'FIRED'"
                + " AND h.hard_decision = 'DENY'", quote)).isEqualTo(1);
        assertThat(scalar("SELECT i.inputs_canonical FROM risk.risk_assessment_input i JOIN risk.risk_assessment a"
                + " ON a.id = i.assessment_id WHERE a.command_ref = ?", quote)).contains("EUR-JPY-JP");
        assertThat(scalar("SELECT destination_kind || ',' || destination_id FROM risk.risk_assessment"
                + " WHERE command_ref = ?", quote)).isEqualTo("BENEFICIARY," + beneficiary);
    }

    @Test
    @Order(2)
    @DisplayName("#aRefusedAuthorisationConsumesNothing: EUR-JPY-JP is the version's high-risk corridor - 422"
            + " crossborder.RiskRefused; the quote ISSUED, no cover, hold, credit or payment, nothing counted; the DENY"
            + " sealed under the quote's id beside the claim's ERR, announced once")
    void aRefusedAuthorisationConsumesNothing() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "JP", "JPY"), "100.00");
        String key = FxTestClient.key();
        HttpResponse<String> refused = pay(customer, quote, key);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("crossborder.RiskRefused")
                .doesNotContain("CORRIDOR").doesNotContain("score").doesNotContain("DENY");

        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ?", UUID.fromString(quote))).isZero();
        assertNothingWritten(customer);
        assertThat(scalar("SELECT decision || ',' || velocity_outcome FROM risk.risk_assessment"
                + " WHERE operation = 'CROSS_BORDER' AND command_ref = ?", quote)).isEqualTo("DENY,NOT_PERMITTED");
        assertThat(count("SELECT count(*) FROM risk.risk_assessment a JOIN platform.outbox_event e ON e.aggregate_id = a.id"
                + " WHERE a.command_ref = ? AND e.event_type = 'risk.RiskDecisionMade'", quote)).isEqualTo(1);
        assertThat(windowCount("PARTY", customer.party(), "D1")).as("a refusal counts nothing").isZero();
        assertThat(scalar("SELECT convert_from(response_body, 'UTF8') FROM platform.idempotency_record"
                + " WHERE idempotency_key = ?", key)).contains("RISK_REFUSED");
    }

    @Test
    @Order(3)
    @DisplayName("#aLimitRefusalAnswersLimitRefused / #tenMovementsOfOnePartyCountTen: ten EUR 100 payments of one party"
            + " from one wallet are authorised and counted ten; the eleventh is VELOCITY_COUNT's own REVIEW -"
            + " 422 crossborder.LimitRefused, nothing consumed")
    void aLimitRefusalAnswersLimitRefused() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("5000.00");
        String beneficiary = beneficiary(customer, "US", "USD");
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> paid = pay(customer, offer(customer, beneficiary, "100.00"), FxTestClient.key());
            assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        }
        assertThat(windowCount("PARTY", customer.party(), "D1")).isEqualTo(10);
        String eleventh = offer(customer, beneficiary, "100.00");
        HttpResponse<String> refused = pay(customer, eleventh, FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("crossborder.LimitRefused");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(eleventh))).isEqualTo("ISSUED");
        assertThat(scalar("SELECT decision FROM risk.risk_assessment WHERE command_ref = ?", eleventh))
                .isEqualTo("REVIEW");
        assertThat(windowCount("PARTY", customer.party(), "D1")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM crossborder.payment WHERE owner_party = ?", customer.party()))
                .isEqualTo(10);
    }

    // ------------------------------------------------------------------ one assessment, the key

    @Test
    @Order(4)
    @DisplayName("#oneAssessmentPerAuthorisation: both seams of one authorisation - ONE assessment under the quote's id, ONE"
            + " RiskDecisionMade, ONE increment per window; the payment's quote is the assessment's command ref")
    void oneAssessmentPerAuthorisation() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "US", "USD"), "100.00");
        assertThat(pay(customer, quote, FxTestClient.key()).statusCode()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE command_ref = ?", quote)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment a JOIN platform.outbox_event e ON e.aggregate_id = a.id"
                + " WHERE a.command_ref = ? AND e.event_type = 'risk.RiskDecisionMade'", quote)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM crossborder.payment p JOIN risk.risk_assessment a"
                + " ON a.command_ref = p.quote_id::text WHERE p.owner_party = ?", customer.party())).isEqualTo(1);
        assertThat(windowCount("PARTY", customer.party(), "D1")).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("#aRetryReplays: the refused authorisation retried under its key replays the claim's 422; under a new"
            + " key on the same quote the seal replays it - still one assessment, nothing consumed (INV-IDEM-01)")
    void aRetryReplays() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "JP", "JPY"), "100.00");
        String key = FxTestClient.key();
        assertThat(pay(customer, quote, key).statusCode()).isEqualTo(422);
        HttpResponse<String> replayed = pay(customer, quote, key);
        assertThat(replayed.statusCode()).isEqualTo(422);
        assertThat(replayed.body()).contains("crossborder.RiskRefused");
        HttpResponse<String> anotherKey = pay(customer, quote, FxTestClient.key());
        assertThat(anotherKey.statusCode()).isEqualTo(422);
        assertThat(anotherKey.body()).contains("crossborder.RiskRefused");
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE command_ref = ?", quote)).isEqualTo(1);
        assertNothingWritten(customer);
    }

    @Test
    @Order(6)
    @DisplayName("#tenAuthorisationsToOneBeneficiaryCountTen: ten authorisations of one party to one beneficiary on ten"
            + " quotes race - every one judged once, the PARTY and DESTINATION windows equal to the counted assessments")
    void tenAuthorisationsToOneBeneficiaryCountTen() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("5000.00");
        String beneficiary = beneficiary(customer, "US", "USD");
        List<String> quotes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            quotes.add(offer(customer, beneficiary, "10.00"));
        }
        List<Callable<Integer>> all = new ArrayList<>();
        for (String quote : quotes) {
            all.add(() -> pay(customer, quote, FxTestClient.key()).statusCode());
        }
        assertThat(race(all)).hasSize(10).allSatisfy(code -> assertThat(code).isIn(202, 422));
        long counted = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND operation = 'CROSS_BORDER'"
                + " AND velocity_outcome = 'COUNTED'", customer.party());
        assertThat(windowCount("PARTY", customer.party(), "D1")).isEqualTo(counted);
        assertThat(windowCount("DESTINATION", UUID.fromString(beneficiary), "D1")).isEqualTo(counted);
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND operation = 'CROSS_BORDER'",
                customer.party())).isEqualTo(10);
    }

    // ------------------------------------------------------------------ the fail-safe, the door, the lock order

    @Test
    @Order(7)
    @DisplayName("#riskUnavailableDeniesAtEveryBand (C1): with the party's subject lock held elsewhere past the 250 ms"
            + " budget, a EUR 1 and a EUR 400 authorisation are each DENY(RISK_UNAVAILABLE, TIMEOUT), recorded,"
            + " 422 crossborder.RiskRefused, nothing consumed")
    void riskUnavailableDeniesAtEveryBand() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String beneficiary = beneficiary(customer, "US", "USD");
        String small = offer(customer, beneficiary, "1.00");
        String large = offer(customer, beneficiary, "400.00");
        List<HttpResponse<String>> answers = new ArrayList<>();
        try (Connection blocker = DatabaseRoles.application()) {
            blocker.setAutoCommit(false);
            try (Statement lock = blocker.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(131, "
                        + VelocityWindows.lockKey(VelocityDimension.PARTY, customer.party().toString()) + ")");
            }
            answers.add(pay(customer, small, FxTestClient.key()));
            answers.add(pay(customer, large, FxTestClient.key()));
            blocker.rollback();
        }
        assertThat(answers).allSatisfy(answer -> {
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(422);
            assertThat(answer.body()).contains("crossborder.RiskRefused");
        });
        for (String quote : List.of(small, large)) {
            assertThat(scalar("SELECT decision || ',' || fail_safe_reason || ',' || unavailable_cause FROM"
                    + " risk.risk_assessment WHERE command_ref = ?", quote)).isEqualTo("DENY,RISK_UNAVAILABLE,TIMEOUT");
        }
        assertNothingWritten(customer);
    }

    @Test
    @Order(8)
    @DisplayName("#theDoorsStepUpWritesNothing: a EUR 600 payment (the version's CHALLENGE) on a password session is"
            + " 403 identity.AssuranceRequired before any row - no claim, no assessment, the quote ISSUED, nothing held")
    void theDoorsStepUpWritesNothing() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "US", "USD"), "600.00");
        String key = FxTestClient.key();
        HttpResponse<String> refused = pay(customer, quote, key);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired").doesNotContain("AMOUNT_BAND");
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?", key)).isZero();
        assertThat(count("SELECT count(*) FROM risk.risk_assessment WHERE command_ref = ?", quote)).isZero();
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        assertNothingWritten(customer);
    }

    @Test
    @Order(9)
    @DisplayName("#noLockCycleAgainstATransferAndAWithdrawalOfTheSameParty: ten cross-border payments, ten transfers and"
            + " ten withdrawals of one party over one EUR wallet at once - the quote and the wallet locked before risk's"
            + " rows in every one; no 40P01, every answer a domain outcome, the windows equal to the counted evidence")
    void noLockCycleAgainstATransferAndAWithdrawalOfTheSameParty() throws Exception {
        activateCrossBorderPolicy();
        FxTestClient.Customer customer = fundedCustomer("5000.00");
        UUID product = productOf(customer);
        FxTestClient.Customer payee = client().verifiedCustomer();
        UUID payeeProduct = client().openWallet(payee, "EUR");
        String beneficiary = beneficiary(customer, "US", "USD");
        List<String> quotes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            quotes.add(offer(customer, beneficiary, "1.00"));
        }
        List<Callable<Object>> all = new ArrayList<>();
        for (String quote : quotes) {
            all.add(() -> pay(customer, quote, FxTestClient.key()));
            all.add(() -> transfer(customer, product, payeeProduct, Money.ofMinorUnits(1_00, EUR)));
            all.add(() -> withdraw(customer, product, Money.ofMinorUnits(1_00, EUR)));
        }
        List<Object> answers = race(all);
        assertThat(answers).hasSize(30).allSatisfy(answer -> assertThat(answer).isNotNull());
        List<HttpResponse<?>> payments = answers.stream().filter(answer -> answer instanceof HttpResponse<?>)
                .<HttpResponse<?>>map(answer -> (HttpResponse<?>) answer).toList();
        assertThat(payments).hasSize(10).allSatisfy(answer -> assertThat(answer.statusCode())
                .as(String.valueOf(answer.body())).isIn(202, 403, 422));
        long counted = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND velocity_outcome = 'COUNTED'",
                customer.party());
        assertThat(windowCount("PARTY", customer.party(), "D1")).isEqualTo(counted);
    }

    // ------------------------------------------------------------------ the policies

    /**
     * The C8 {@code CROSS_BORDER} fixture with one hard rule added - {@code CORRIDOR_HIGH_RISK} over {@code EUR-JPY-JP}
     * deciding {@code DENY} - activated once through the domain by two test persons.
     */
    private static void activateCrossBorderPolicy() {
        if (!ACTIVATED.compareAndSet(false, true)) {
            return;
        }
        RiskPolicyAdministration riskPolicies = new RiskPolicyAdministration(new JdbcRiskPolicyStore(),
                new com.finapp.platform.audit.JdbcAuditWriter(), new com.finapp.platform.outbox.JdbcOutboxWriter(),
                FxTestClient.IDS, CLOCK);
        RiskPolicy base = RiskPolicyFixtures.v1(RiskOperation.CROSS_BORDER);
        List<RiskRule> rules = new ArrayList<>(base.rules());
        rules.add(RiskRule.deciding(rules.size() + 1, RuleKind.CORRIDOR_HIGH_RISK, RuleParameters.of(
                RuleKind.CORRIDOR_HIGH_RISK, Map.of("corridors", new ParameterValue.Corridors(Set.of("EUR-JPY-JP")))),
                RiskDecision.DENY));
        RiskPolicy policy = new RiskPolicy(RiskOperation.CROSS_BORDER, base.engine(), base.currency(), rules,
                base.scoreBands(), List.of(), base.failSafeBands(), base.challengeAssurance(), base.overrideThreshold(),
                base.holdValidity());
        RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> riskPolicies.propose(uow, policy,
                "the cross-border seam suite's version", employee(), CorrelationId.generate(FxTestClient.IDS)));
        inOneTransaction(uow -> riskPolicies.activate(uow, proposed.id(), employee(), "activated",
                CorrelationId.generate(FxTestClient.IDS)));
    }

    private void activateCorridorPolicy() throws Exception {
        String first = sessionWith(RoleName.FX_CONTROLLER);
        String second = sessionWith(RoleName.FX_CONTROLLER);
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the risk seam suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                        "{\"reason\":\"checked\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ the movements

    private HttpResponse<String> pay(FxTestClient.Customer customer, String quote, String key) throws Exception {
        return client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", customer.token(), key);
    }

    private FxTestClient.Customer fundedCustomer(String eur) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money(eur, "EUR"));
        return customer;
    }

    /** A fixed-source EUR offer of {@code amount} to the beneficiary. Returns the quote's id. */
    private String offer(FxTestClient.Customer customer, String beneficiary, String amount) throws Exception {
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"" + amount + "\"}", customer.token(),
                FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        return field(offered.body(), "id");
    }

    /** Registers a beneficiary in {@code country}/{@code currency} with a MATCH payee; the scripted screening clears. */
    private String beneficiary(FxTestClient.Customer customer, String country, String currency) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary(country, currency, "individual",
                "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"" + country + "\",\"currency\":\""
                + currency + "\",\"grant\":\"" + grant + "\",\"name\":\"Clear Person\",\"nickname\":\"Payee\","
                + "\"entityType\":\"INDIVIDUAL\"}", customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    private Object transfer(FxTestClient.Customer payer, UUID source, UUID destination, Money amount) throws Exception {
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?",
                payer.login()));
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(new Actor(identity.toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            app.setAutoCommit(false);
            Object result = transferExecution.execute(app, new TransferCommand(FxTestClient.key(), payer.party(), source,
                    destination, amount, "rent"));
            app.commit();
            return result;
        }
    }

    private Object withdraw(FxTestClient.Customer customer, UUID product, Money amount) throws Exception {
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?",
                customer.login()));
        UUID customerId = UUID.fromString(scalar("SELECT customer_id::text FROM accounts.customer_account WHERE id = ?",
                product));
        LedgerAccountId wallet = LedgerAccountId.of(UUID.fromString(scalar("SELECT id::text FROM ledger.ledger_account"
                + " WHERE owner_ref = ? AND purpose = 'CUSTOMER_WALLET' AND currency = 'EUR'", product)));
        Withdrawals.Resolved resolved = new Withdrawals.Resolved(customer.party(), customerId, wallet, EUR,
                FxTestClient.IDS.next(), new ProviderReference("dest-xbr-" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 10)));
        try (SecurityContext.Scope scope = SecurityContext.enter(new Actor(identity.toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            return engine.withdraw(FxTestClient.key(), amount, uow -> resolved);
        }
    }

    private static UUID productOf(FxTestClient.Customer customer) throws Exception {
        return UUID.fromString(scalar("SELECT a.id::text FROM accounts.customer_account a JOIN party.customer c"
                + " ON c.id = a.customer_id WHERE c.party_id = ? AND a.product_type = 'WALLET'", customer.party()));
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertNothingWritten(FxTestClient.Customer customer) throws Exception {
        assertThat(count("SELECT count(*) FROM crossborder.payment WHERE owner_party = ?", customer.party())).isZero();
        assertThat(count("SELECT count(*) FROM payments.outbound_credit WHERE customer_party_id = ?", customer.party()))
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN ledger.ledger_account a ON a.id = h.ledger_account_id"
                        + " JOIN accounts.customer_account p ON p.id = a.owner_ref JOIN party.customer c"
                        + " ON c.id = p.customer_id WHERE c.party_id = ?", customer.party()))
                .as("no hold on the customer's wallet").isZero();
    }

    private static long windowCount(String dimension, UUID subject, String kind) throws Exception {
        return count("SELECT COALESCE(sum(count), 0) FROM risk.velocity_window WHERE dimension = ? AND subject_ref = ?"
                + " AND window_kind = ?", dimension, subject.toString(), kind);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "xr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client().post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                        + " Lovelace\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key())
                .statusCode()).isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client().post("/v1/authentications", "{\"loginIdentifier\":\"" + login
                + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key());
        return field(session.body(), "sessionToken");
    }

    private static <R> R inOneTransaction(java.util.function.Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            connection.setAutoCommit(false);
            R result = work.apply(connection);
            connection.commit();
            return result;
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                answers.add(future.get(120, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    @TestConfiguration
    static class Doubles {

        /** The screening provider: every counterparty clears. */
        @Bean
        @Primary
        CounterpartyScreeningProvider clearingScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
