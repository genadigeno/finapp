package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.crossborder.FxCrossBorderQuotes;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.crossborder.CrossBorderFx;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.QuoteIssuance;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Decision;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.kyc.CounterpartyScreenings;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * The cross-border offer through its door, against a live database, the simulated FX provider and the
 * simulated corridor provider (`P9-TSK-018`, PHASE_9_PLAN.md section 12.3; {@code INV-XB-02}, {@code INV-XB-03},
 * {@code INV-FX-02}, {@code INV-HIST-04}): the fee frozen on both sides, the corridor limit, one answer for
 * every non-payable beneficiary, a lapsed clearance re-screened (clear priced, a hit refused with the
 * beneficiary in review and no provider asked, an unavailable provider 503), a version superseded between the
 * transactions, ten requests under one key, the owner's read, the closed body and the needle's quote leg.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("cross-border offers (P9-TSK-018)")
class CrossBorderOfferDatabaseTest {

    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    /** Run between the transactions when armed - after the claim committed, before the record. */
    static final AtomicReference<Runnable> BETWEEN = new AtomicReference<>();

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private CounterpartyScreenings counterpartyScreenings;
    @Autowired private com.finapp.kyc.TransactionRunner kycTransactionRunner;

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
            FxTestClient.rates(fxEngine, Map.of());
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
        BETWEEN.set(null);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (FxTestClient.count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
    }

    // ------------------------------------------------------------------ the offer

    @Test
    @DisplayName("a fixed source is offered with the corridor fee, the total debit and the guaranteed destination,"
            + " frozen - read back by its owner, 404 to another; fx's quote is CROSS_BORDER")
    void aFixedSourceOfferIsFrozen() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String beneficiary = beneficiary(customer, "Clear Person");
        HttpResponse<String> offered = quote(customer, beneficiary, "FIXED_SOURCE", "100.00", FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        assertThat(FxTestClient.field(offered.body(), "sourceAmount")).isEqualTo("100.00");
        assertThat(FxTestClient.field(offered.body(), "fee")).isEqualTo("2.50");
        assertThat(FxTestClient.field(offered.body(), "totalDebit")).isEqualTo("102.50");
        assertThat(FxTestClient.field(offered.body(), "destinationCurrency")).isEqualTo("JPY");
        assertThat(offered.body()).contains("\"deliveryEstimateHours\":24").doesNotContain("providerRate", "PQ-");
        String id = FxTestClient.field(offered.body(), "id");
        assertThat(FxTestClient.scalar("SELECT purpose FROM fx.quote WHERE id = ?",
                        UUID.fromString(id)))
                .isEqualTo("CROSS_BORDER");
        assertThat(FxTestClient.count("SELECT count(*) FROM crossborder.payment_offer WHERE quote_id = ?"
                        + " AND total_debit_minor = source_minor + fee_minor AND fee_minor = 250", UUID.fromString(id)))
                .isEqualTo(1);
        HttpResponse<String> read = client().get(QUOTES + "/" + id, customer.token());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(FxTestClient.field(read.body(), "destinationAmount"))
                .isEqualTo(FxTestClient.field(offered.body(), "destinationAmount"));
        assertThat(client().get(QUOTES + "/" + id, client().verifiedCustomer().token()).statusCode()).isEqualTo(404);
        assertThat(client().get(QUOTES + "/not-a-uuid", customer.token()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a fixed destination guarantees exactly that amount; the fee is computed on the customer's source")
    void aFixedDestinationIsGuaranteed() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String beneficiary = beneficiary(customer, "Clear Person");
        HttpResponse<String> offered = quote(customer, beneficiary, "FIXED_DESTINATION", "15000", FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        assertThat(FxTestClient.field(offered.body(), "destinationAmount")).isEqualTo("15000");
        BigDecimal source = new BigDecimal(FxTestClient.field(offered.body(), "sourceAmount"));
        assertThat(new BigDecimal(FxTestClient.field(offered.body(), "fee"))).isEqualByComparingTo("2.50");
        assertThat(new BigDecimal(FxTestClient.field(offered.body(), "totalDebit"))).isEqualByComparingTo(source.add(new BigDecimal("2.50")));
    }

    @Test
    @DisplayName("a destination past the corridor's maximum is refused before any provider is asked")
    void theCorridorLimit() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String beneficiary = beneficiary(customer, "Clear Person");
        int before = fxEngine.quoteRequests();
        HttpResponse<String> refused = quote(customer, beneficiary, "FIXED_DESTINATION", "1500001", FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("crossborder.AmountExceedsCorridorLimit");
        assertThat(fxEngine.quoteRequests()).isEqualTo(before);
    }

    @Test
    @DisplayName("BeneficiaryNotPayable is one answer for a beneficiary being screened, in review, blocked, revoked,"
            + " unknown or another's - nothing priced")
    void notPayableIsOneAnswer() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String pending = beneficiary(customer, "DOWN Pending Person");
        String inReview = beneficiary(customer, "HIT Review Person");
        String blocked = beneficiary(customer, "HIT Blocked Person");
        decide(blocked, Decision.BLOCK, ReasonCode.TRUE_MATCH);
        String revoked = beneficiary(customer, "Clear Revoked Person");
        assertThat(client().post(BENEFICIARIES + "/" + revoked + "/revocation", "", customer.token(), FxTestClient.key()).statusCode())
                .isEqualTo(200);
        String anothers = beneficiary(client().verifiedCustomer(), "Clear Person");
        int before = fxEngine.quoteRequests();
        List<String> bodies = new ArrayList<>();
        for (String id : List.of(pending, inReview, blocked, revoked, anothers, FxTestClient.IDS.next().toString())) {
            HttpResponse<String> refused = quote(customer, id, "FIXED_SOURCE", "100.00", FxTestClient.key());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            bodies.add(refused.body().replaceAll("\"correlationId\":\"[^\"]+\"", "").replaceAll("\"instance\":\"[^\"]+\"", ""));
        }
        assertThat(bodies).containsOnly(bodies.get(0));
        assertThat(bodies.get(0)).contains("crossborder.BeneficiaryNotPayable");
        assertThat(fxEngine.quoteRequests()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ the re-screen

    @Test
    @DisplayName("a lapsed clearance is re-screened: clear is priced and becomes the current clearance; a hit is"
            + " refused with the beneficiary IN_REVIEW and no provider asked; an unavailable provider is 503")
    void aLapsedClearanceIsRescreened() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String clear = beneficiary(customer, "Clear Lapsed Person");
        UUID before = screeningOf(clear);
        lapse(before);
        HttpResponse<String> priced = quote(customer, clear, "FIXED_SOURCE", "100.00", FxTestClient.key());
        assertThat(priced.statusCode()).as(priced.body()).isEqualTo(201);
        assertThat(screeningOf(clear)).as("the re-screen is the current clearance").isNotEqualTo(before);

        String flip = beneficiary(customer, "FLIP Listed Later");
        lapse(screeningOf(flip));
        int asked = fxEngine.quoteRequests();
        HttpResponse<String> refused = quote(customer, flip, "FIXED_SOURCE", "100.00", FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("crossborder.BeneficiaryNotPayable");
        assertThat(FxTestClient.scalar("SELECT status FROM crossborder.beneficiary WHERE id = ?", UUID.fromString(flip)))
                .isEqualTo("IN_REVIEW");
        assertThat(fxEngine.quoteRequests()).as("nothing priced").isEqualTo(asked);

        String late = beneficiary(customer, "LATE Provider Later");
        lapse(screeningOf(late));
        HttpResponse<String> unavailable = quote(customer, late, "FIXED_SOURCE", "100.00", FxTestClient.key());
        assertThat(unavailable.statusCode()).as(unavailable.body()).isEqualTo(503);
        assertThat(unavailable.body()).contains("crossborder.ScreeningUnavailable");
        assertThat(FxTestClient.scalar("SELECT status FROM crossborder.beneficiary WHERE id = ?", UUID.fromString(late)))
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ pinned versions

    @Test
    @DisplayName("a corridor version activated between the transactions is 409 crossborder.PolicyStale; a pricing"
            + " version, 409 fx.PolicyStale - nothing offered either way")
    void aVersionSupersededBetweenTheTransactions() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String beneficiary = beneficiary(customer, "Clear Person");
        BETWEEN.set(() -> {
            try {
                activateCorridorPolicy();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        HttpResponse<String> stale = quote(customer, beneficiary, "FIXED_SOURCE", "100.00", FxTestClient.key());
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(stale.body()).contains("crossborder.PolicyStale");

        BETWEEN.set(() -> {
            try {
                FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.RACE);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        HttpResponse<String> fxStale = quote(customer, beneficiary, "FIXED_SOURCE", "100.00", FxTestClient.key());
        assertThat(fxStale.statusCode()).as(fxStale.body()).isEqualTo(409);
        assertThat(fxStale.body()).contains("fx.PolicyStale");
        assertThat(FxTestClient.count("SELECT count(*) FROM crossborder.payment_offer WHERE owner_party = ?", customer.party()))
                .isZero();
    }

    // ------------------------------------------------------------------ races and guards

    @Test
    @DisplayName("ten requests under one key: one provider call, one quote, one offer")
    void oneKeyOneOffer() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String beneficiary = beneficiary(customer, "Clear Person");
        String key = FxTestClient.key();
        int before = fxEngine.quoteRequests();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            codes.add(pool.submit(() -> {
                start.await();
                return quote(customer, beneficiary, "FIXED_SOURCE", "250.00", key).statusCode();
            }));
        }
        start.countDown();
        for (Future<Integer> code : codes) {
            assertThat(code.get(60, TimeUnit.SECONDS)).isIn(201, 409);
        }
        pool.shutdown();
        assertThat(quote(customer, beneficiary, "FIXED_SOURCE", "250.00", key).statusCode()).isEqualTo(201);
        assertThat(fxEngine.quoteRequests() - before).isEqualTo(1);
        assertThat(FxTestClient.count("SELECT count(*) FROM crossborder.payment_offer WHERE owner_party = ?", customer.party()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a closed body: a rate - or any unknown field - is 422 and nothing is asked or stored")
    void aClientCannotSupplyARate() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String beneficiary = beneficiary(customer, "Clear Person");
        int before = fxEngine.quoteRequests();
        HttpResponse<String> refused = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":\"EUR\","
                + "\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"100.00\",\"rate\":\"2.0\"}", customer.token(), FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("api.ValidationFailed");
        assertThat(fxEngine.quoteRequests()).isEqualTo(before);
    }

    @Test
    @DisplayName("the needle (quote leg): the beneficiary's name is in no table, event or response the offer touched")
    void theNameReachesNoOffer() throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        String name = "Ophelia Needlecombe " + UUID.randomUUID().toString().substring(0, 6);
        String beneficiary = beneficiary(customer, name);
        lapse(screeningOf(beneficiary));
        HttpResponse<String> offered = quote(customer, beneficiary, "FIXED_SOURCE", "100.00", FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        assertThat(offered.body()).doesNotContain(name);
        for (String table : List.of("crossborder.offer_request", "crossborder.payment_offer", "fx.quote_request", "fx.quote")) {
            assertThat(FxTestClient.count("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?", "%" + name + "%"))
                    .as(table).isZero();
        }
        assertThat(FxTestClient.count("SELECT count(*) FROM platform.outbox_event WHERE convert_from(payload, 'UTF8') LIKE ?",
                        "%" + name + "%"))
                .isZero();
    }

    // ------------------------------------------------------------------ plumbing

    private HttpResponse<String> quote(FxTestClient.Customer customer, String beneficiary, String side, String amount, String key)
            throws Exception {
        return client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":\"EUR\",\"fixedSide\":\""
                + side + "\",\"amount\":\"" + amount + "\"}", customer.token(), key);
    }

    /** Registers a JP/JPY beneficiary with a MATCH payee; its screening follows the scripted provider by name. */
    private String beneficiary(FxTestClient.Customer customer, String name) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("JP", "JPY", "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"JP\",\"currency\":\"JPY\",\"grant\":\""
                + grant + "\",\"name\":\"" + name + "\",\"nickname\":\"Osaka\",\"entityType\":\"INDIVIDUAL\"}",
                customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return FxTestClient.field(registered.body(), "id");
    }

    private void decide(String beneficiary, Decision decision, ReasonCode code) throws Exception {
        UUID screening = screeningOf(beneficiary);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)))) {
            kycTransactionRunner.inTransaction(uow -> counterpartyScreenings.review(uow, CounterpartyScreeningId.of(screening),
                    new Actor("reviewer-" + UUID.randomUUID(), ActorType.EMPLOYEE), decision, code, "reviewed in the suite",
                    CorrelationId.generate(FxTestClient.IDS)));
        }
    }

    private static UUID screeningOf(String beneficiary) throws Exception {
        return UUID.fromString(FxTestClient.scalar("SELECT screening_id::text FROM crossborder.beneficiary WHERE id = ?",
                UUID.fromString(beneficiary)));
    }

    /** Ages a screening past the corridor's seven-day validity - the owner's act, the freeze suspended for it. */
    private static void lapse(UUID screening) throws Exception {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (PreparedStatement off = owner.prepareStatement(
                            "ALTER TABLE kyc.counterparty_screening DISABLE TRIGGER counterparty_screening_permits_only_machine_edges");
                    PreparedStatement age = owner.prepareStatement("UPDATE kyc.counterparty_screening SET requested_at ="
                            + " requested_at - interval '8 days', decided_at = decided_at - interval '8 days' WHERE id = ?");
                    PreparedStatement on = owner.prepareStatement(
                            "ALTER TABLE kyc.counterparty_screening ENABLE TRIGGER counterparty_screening_permits_only_machine_edges")) {
                off.execute();
                age.setObject(1, screening);
                assertThat(age.executeUpdate()).isEqualTo(1);
                on.execute();
            }
            owner.commit();
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
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the offer suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + FxTestClient.field(proposed.body(), "id") + "/approval",
                        "{\"reason\":\"checked\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "xo." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client().post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity = UUID.fromString(FxTestClient.scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client().post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key());
        return FxTestClient.field(session.body(), "sessionToken");
    }

    @TestConfiguration
    static class Doubles {

        /**
         * The screening provider, scripted by the name: HIT is a hit, DOWN unavailable, FLIP clear once then a hit,
         * LATE clear once then unavailable, anything else clear.
         */
        @Bean
        @Primary
        CounterpartyScreeningProvider scriptedCounterpartyScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
            return (screening, subject) -> {
                String name = subject.name();
                int call = seen.computeIfAbsent(name, any -> new AtomicInteger()).incrementAndGet();
                if (name.startsWith("HIT") || (name.startsWith("FLIP") && call > 1)) {
                    return CounterpartyScreeningProvider.Answer.of(Verdict.HIT, evidence);
                }
                if (name.startsWith("DOWN") || (name.startsWith("LATE") && call > 1)) {
                    return CounterpartyScreeningProvider.Answer.withoutEvidence(Verdict.UNAVAILABLE);
                }
                return CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
            };
        }

        /** fx's quotes, with a hook run between the transactions when armed. */
        @Bean
        @Primary
        CrossBorderFx hookedCrossBorderFx(
                QuoteIssuance quoteIssuance,
                QuoteLifecycle quoteLifecycle,
                com.finapp.fx.CrossBorderAcceptance crossBorderAcceptance,
                com.finapp.fx.CoverDispatchNudge coverDispatchNudge) {
            CrossBorderFx real = new FxCrossBorderQuotes(quoteIssuance, quoteLifecycle, crossBorderAcceptance, coverDispatchNudge);
            return new CrossBorderFx() {
                @Override
                public Claim begin(Connection unitOfWork, String claimKey, Ask ask, CorrelationId correlation) {
                    return real.begin(unitOfWork, claimKey, ask, correlation);
                }

                @Override
                public Sourcing firmQuote(Claim claim) {
                    Sourcing sourcing = real.firmQuote(claim);
                    Runnable between = BETWEEN.getAndSet(null);
                    if (between != null) {
                        between.run();
                    }
                    return sourcing;
                }

                @Override
                public Quote issue(Connection unitOfWork, Claim claim, Sourcing sourcing, Actor actor, CorrelationId correlation) {
                    return real.issue(unitOfWork, claim, sourcing, actor, correlation);
                }

                @Override
                public java.util.Optional<Quote> read(Connection unitOfWork, UUID id, UUID owner) {
                    return real.read(unitOfWork, id, owner);
                }

                @Override
                public Accepted acceptWithin(
                        Connection unitOfWork, UUID quoteId, UUID owner, UUID payment, Actor actor, CorrelationId correlation) {
                    return real.acceptWithin(unitOfWork, quoteId, owner, payment, actor, correlation);
                }

                @Override
                public void dispatchCover(UUID coverId) {
                    real.dispatchCover(coverId);
                }
            };
        }
    }
}
