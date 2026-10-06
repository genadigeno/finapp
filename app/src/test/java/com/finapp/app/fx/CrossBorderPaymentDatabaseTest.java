package com.finapp.app.fx;

import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.crossborder.CorridorPolicyV1;
import com.finapp.app.payments.PaymentBeans;
import com.finapp.app.payments.SimulatedCorridorEngine;
import com.finapp.crossborder.CrossBorderLimitCheck;
import com.finapp.crossborder.CrossBorderRiskDecision;
import com.finapp.crossborder.CrossBorderVerdict;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Decision;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.kyc.CounterpartyScreenings;
import com.finapp.ledger.PostingService;
import com.finapp.payments.JdbcRoutingStore;
import com.finapp.payments.OutboundCreditId;
import com.finapp.payments.RoutingDecision;
import com.finapp.payments.RoutingInputs;
import com.finapp.payments.RoutingPlan;
import com.finapp.payments.RoutingPolicyVersion;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The cross-border payment's authorization and dispatch through its door, against a live database, the
 * simulated FX provider and the simulated corridor provider (`P9-TSK-019`, PHASE_9_PLAN.md section 12.8;
 * {@code INV-XB-02}, {@code INV-XB-03}, {@code INV-PAY-04}, {@code INV-RAIL-02}, {@code INV-HIST-04}): the hold
 * of the total debit, the quote accepted, the cover born and the credit dispatched in one commit; routing's
 * third subject recomputable; ten requests under one key and a second key on the same quote; one answer for
 * every non-payable beneficiary; a lapsed clearance; the Phase 13 seams' REFUSE and an unfunded wallet writing
 * nothing; the authorization serialised against a beneficiary moving; an unknown send; the needle.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("cross-border payments (P9-TSK-019)")
class CrossBorderPaymentDatabaseTest {

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    /** The Phase 13 seams' verdicts, PERMIT unless a test arms REFUSE. */
    static final AtomicReference<CrossBorderVerdict> RISK = new AtomicReference<>(CrossBorderVerdict.PERMIT);
    static final AtomicReference<CrossBorderVerdict> LIMIT = new AtomicReference<>(CrossBorderVerdict.PERMIT);

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
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
        RISK.set(CrossBorderVerdict.PERMIT);
        LIMIT.set(CrossBorderVerdict.PERMIT);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        awaitRoutingVersionInForce();
    }

    /**
     * Routing's seeded version 5 takes effect at the database's {@code now()} when payments V025 ran, but the
     * application resolves the version in force on ITS clock. A database clock running ahead of the host's (a
     * container VM's drift) leaves no seeded version in force for that long, and every route answers
     * {@code payments.NoEligibleRail}: waited out here, and named if it does not pass.
     */
    private static void awaitRoutingVersionInForce() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version WHERE version = 5 AND effective_from <= ?",
                java.sql.Timestamp.from(java.time.Instant.now())) == 0) {
            assertThat(System.nanoTime())
                    .as("routing version 5 is not yet in force by the host's clock - the database clock runs ahead"
                            + " of it (database now %s, host now %s)", scalar("SELECT now()::text"), java.time.Instant.now())
                    .isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    // ------------------------------------------------------------------ the authorization

    @Test
    @DisplayName("an offer is authorized in one commit - the total debit held, the quote accepted, the cover born, the"
            + " credit dispatched and sent - 202 PROCESSING, read back by its owner, 404 to another")
    void anOfferIsAuthorizedAndDispatched() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "Clear Person"));
        HttpResponse<String> paid = pay(customer, quote, FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        assertThat(field(paid.body(), "status")).isEqualTo("PROCESSING");
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        assertThat(field(paid.body(), "quoteId")).isEqualTo(quote);

        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ACCEPTED");
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ?", UUID.fromString(quote))).isEqualTo(1);
        assertThat(scalar("SELECT h.amount_minor || ' ' || h.currency FROM ledger.hold h JOIN payments.outbound_credit c"
                        + " ON c.hold_id = h.id WHERE c.subject_id = ?", payment))
                .as("the hold is the offer's total debit").isEqualTo("10250 EUR");
        assertThat(scalar("SELECT c.status || ' ' || c.rail || ' ' || c.amount_currency FROM payments.outbound_credit c"
                        + " WHERE c.subject_id = ?", payment))
                .isEqualTo("RECEIVED corridor-sim-a JPY");
        assertThat(scalar("SELECT d.chosen_rail || '|' || v.version || '|' || d.destination_country || '|'"
                        + " || array_to_string(d.reachable_rails, ',') FROM payments.routing_decision d"
                        + " JOIN payments.routing_policy_version v ON v.id = d.policy_version_id"
                        + " JOIN payments.outbound_credit c ON c.id = d.outbound_credit_id WHERE c.subject_id = ?", payment))
                .as("routing's third subject, under the seeded version in force").isEqualTo("corridor-sim-a|5|JP|corridor-sim-a");
        assertThat(count("SELECT count(*) FROM payments.provider_evidence e JOIN payments.outbound_credit c"
                        + " ON c.id = e.outbound_credit_id WHERE c.subject_id = ?", payment))
                .as("the corridor's answer retained").isEqualTo(1);
        String reference = scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE subject_id = ?", payment);
        assertThat(CORRIDOR.creditsOf(reference)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM crossborder.payment_event WHERE payment_id = ? AND cause = 'AUTHORIZED'",
                        payment))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderPaymentInitiated'"
                        + " AND aggregate_id = ?", payment))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'crossborder.CrossBorderPaymentAuthorized'"
                        + " AND target_id = ?", payment.toString()))
                .isEqualTo(1);

        HttpResponse<String> read = client().get(PAYMENTS + "/" + payment, customer.token());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("PROCESSING");
        assertThat(client().get(PAYMENTS + "/" + payment, client().verifiedCustomer().token()).statusCode()).isEqualTo(404);
        assertThat(client().get(PAYMENTS + "/not-a-uuid", customer.token()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("routing's decision recomputes from its stored inputs - the destination country and the reachable"
            + " rails - and its pinned version to the same rule and rail (INV-HIST-04)")
    void theRoutingDecisionRecomputes() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        HttpResponse<String> paid = pay(customer, offer(customer, beneficiary(customer, "Clear Person")), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?",
                UUID.fromString(field(paid.body(), "paymentId"))));
        JdbcRoutingStore store = new JdbcRoutingStore();
        try (Connection app = DatabaseRoles.application()) {
            RoutingDecision decision = store.findLatestDecisionForOutboundCredit(app, OutboundCreditId.of(credit)).orElseThrow();
            RoutingPolicyVersion version = store.findVersionById(app, decision.policyVersionId()).orElseThrow();
            RoutingPlan replayed = version.decide(new RoutingInputs(decision.direction(), decision.instrumentKind(),
                    decision.amount(), Optional.empty(), decision.destinationCountry(), decision.reachableRails()),
                    PaymentBeans.DECLARED_RAILS, Map.of());
            assertThat(replayed.chosen()).isEqualTo(decision.chosenRail());
            assertThat(replayed.matchedRuleIndex()).isEqualTo(decision.matchedRuleIndex()).contains(1);
            assertThat(decision.destinationCountry()).contains(com.finapp.sharedkernel.money.CountryCode.of("JP"));
        }
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    @DisplayName("ten requests under one key: one payment, one hold, one credit, one send; a second key on the same"
            + " quote is 409 fx.QuoteAlreadyAccepted and writes nothing")
    void oneKeyOnePayment() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "Clear Person"));
        String key = FxTestClient.key();
        int sends = CORRIDOR.credits();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            codes.add(pool.submit(() -> {
                start.await();
                return pay(customer, quote, key).statusCode();
            }));
        }
        start.countDown();
        for (Future<Integer> code : codes) {
            assertThat(code.get(60, TimeUnit.SECONDS)).isIn(202, 409);
        }
        pool.shutdown();
        HttpResponse<String> replay = pay(customer, quote, key);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM crossborder.payment WHERE owner_party = ?", customer.party())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.outbound_credit WHERE customer_party_id = ?", customer.party()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ?", UUID.fromString(quote))).isEqualTo(1);
        assertThat(CORRIDOR.credits() - sends).as("one send").isEqualTo(1);

        HttpResponse<String> second = pay(customer, quote, FxTestClient.key());
        assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
        assertThat(second.body()).contains("fx.QuoteAlreadyAccepted");
        assertThat(count("SELECT count(*) FROM crossborder.payment WHERE owner_party = ?", customer.party())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.outbound_credit WHERE customer_party_id = ?", customer.party()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("BeneficiaryNotPayable is one answer for a beneficiary in review, blocked or revoked after its offer"
            + " - nothing held, accepted or sent")
    void notPayableIsOneAnswer() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String inReview = beneficiary(customer, "FLIP Review Payee");
        String reviewQuote = offer(customer, inReview);
        rescreenToReview(customer, inReview);
        String blocked = beneficiary(customer, "FLIP Blocked Payee");
        String blockedQuote = offer(customer, blocked);
        rescreenToReview(customer, blocked);
        decide(blocked, Decision.BLOCK, ReasonCode.TRUE_MATCH);
        assertThat(scalar("SELECT status FROM crossborder.beneficiary WHERE id = ?", UUID.fromString(blocked)))
                .isEqualTo("BLOCKED");
        String revoked = beneficiary(customer, "Clear Revoked Payee");
        String revokedQuote = offer(customer, revoked);
        assertThat(client().post(BENEFICIARIES + "/" + revoked + "/revocation", "", customer.token(), FxTestClient.key())
                        .statusCode())
                .isEqualTo(200);
        int sends = CORRIDOR.credits();
        List<String> bodies = new ArrayList<>();
        for (String quote : List.of(reviewQuote, blockedQuote, revokedQuote)) {
            HttpResponse<String> refused = pay(customer, quote, FxTestClient.key());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            bodies.add(refused.body().replaceAll("\"correlationId\":\"[^\"]+\"", "").replaceAll("\"instance\":\"[^\"]+\"", ""));
            assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        }
        assertThat(bodies).containsOnly(bodies.get(0));
        assertThat(bodies.get(0)).contains("crossborder.BeneficiaryNotPayable");
        assertNothingWritten(customer);
        assertThat(CORRIDOR.credits()).isEqualTo(sends);

        HttpResponse<String> unknown = pay(customer, FxTestClient.IDS.next().toString(), FxTestClient.key());
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
        assertThat(unknown.body()).contains("crossborder.OfferNotFound");
        FxTestClient.Customer another = fundedCustomer("1000.00");
        HttpResponse<String> anothers = pay(another, offer(customer, beneficiary(customer, "Clear Person")), FxTestClient.key());
        assertThat(anothers.statusCode()).as(anothers.body()).isEqualTo(404);
        assertThat(anothers.body()).contains("crossborder.OfferNotFound");
    }

    @Test
    @DisplayName("a clearance lapsed since the offer is 409 crossborder.ScreeningRequired - nothing held or accepted")
    void aLapsedClearanceRequiresANewOffer() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String beneficiary = beneficiary(customer, "Clear Lapsed Payee");
        String quote = offer(customer, beneficiary);
        lapse(screeningOf(beneficiary));
        HttpResponse<String> refused = pay(customer, quote, FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("crossborder.ScreeningRequired");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("the Phase 13 seams: a risk or limit REFUSE is 422 with its reserved code and writes nothing; PERMIT"
            + " then proceeds")
    void theSeamsRefuseWritingNothing() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "Clear Person"));
        RISK.set(CrossBorderVerdict.REFUSE);
        HttpResponse<String> risk = pay(customer, quote, FxTestClient.key());
        assertThat(risk.statusCode()).as(risk.body()).isEqualTo(422);
        assertThat(risk.body()).contains("crossborder.RiskRefused");
        RISK.set(CrossBorderVerdict.PERMIT);
        LIMIT.set(CrossBorderVerdict.REFUSE);
        HttpResponse<String> limit = pay(customer, quote, FxTestClient.key());
        assertThat(limit.statusCode()).as(limit.body()).isEqualTo(422);
        assertThat(limit.body()).contains("crossborder.LimitRefused");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        assertNothingWritten(customer);
        LIMIT.set(CrossBorderVerdict.PERMIT);
        assertThat(pay(customer, quote, FxTestClient.key()).statusCode()).isEqualTo(202);
    }

    @Test
    @DisplayName("an unfunded wallet is 422 fx.InsufficientFunds after the quote was accepted in the same unit of"
            + " work - rolled back to the savepoint: the quote ISSUED, no cover, no hold, no credit; the key replays it")
    void anUnfundedWalletWritesNothing() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("50.00");
        String quote = offer(customer, beneficiary(customer, "Clear Person"));
        String key = FxTestClient.key();
        HttpResponse<String> refused = pay(customer, quote, key);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("fx.InsufficientFunds");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ?", UUID.fromString(quote))).isZero();
        assertThat(count("SELECT count(*) FROM fx.quote_event WHERE quote_id = ? AND to_status = 'ACCEPTED'",
                        UUID.fromString(quote)))
                .isZero();
        assertNothingWritten(customer);
        HttpResponse<String> replay = pay(customer, quote, key);
        assertThat(replay.statusCode()).isEqualTo(422);
        assertThat(replay.body()).contains("fx.InsufficientFunds");
    }

    @Test
    @DisplayName("step-up: an MFA-enrolled customer on a password-only session is 403 identity.AssuranceRequired and"
            + " nothing is accepted or held")
    void anEnrolledCustomerNeedsAStepUp() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "Clear Person"));
        enrolAndConfirm(customer.token());
        HttpResponse<String> refused = pay(customer, quote, FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        assertNothingWritten(customer);
    }

    // ------------------------------------------------------------------ races and failures

    @Test
    @DisplayName("the authorization holds the beneficiary FOR SHARE: a move to IN_REVIEW committed while it waits is"
            + " seen, and the payment is refused - never paid on a stale ACTIVE")
    void theAuthorizationSerialisesAgainstAMove() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String beneficiary = beneficiary(customer, "Clear Moving Payee");
        String quote = offer(customer, beneficiary);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> paid;
        try (Connection mover = DatabaseRoles.application()) {
            mover.setAutoCommit(false);
            try (PreparedStatement lock = mover.prepareStatement("SELECT id FROM crossborder.beneficiary WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(beneficiary));
                lock.executeQuery().close();
            }
            paid = pool.submit(() -> pay(customer, quote, FxTestClient.key()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND query LIKE '%crossborder.beneficiary%' AND query LIKE '%FOR SHARE%'") == 0) {
                assertThat(System.nanoTime()).as("the authorization waits on the beneficiary").isLessThan(deadline);
                Thread.sleep(50);
            }
            try (PreparedStatement move = mover.prepareStatement(
                    "UPDATE crossborder.beneficiary SET status = 'IN_REVIEW' WHERE id = ?")) {
                move.setObject(1, UUID.fromString(beneficiary));
                assertThat(move.executeUpdate()).isEqualTo(1);
            }
            mover.commit();
        }
        HttpResponse<String> refused = paid.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("crossborder.BeneficiaryNotPayable");
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("a send whose answer is lost leaves the credit UNKNOWN - still 202, the hold standing - and the key"
            + " replays the same payment with no second send")
    void aLostAnswerIsUnknown() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "Clear Person"));
        String key = FxTestClient.key();
        CORRIDOR.loseNextResponse();
        HttpResponse<String> paid = pay(customer, quote, key);
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        assertThat(scalar("SELECT status FROM payments.outbound_credit WHERE subject_id = ?", payment)).isEqualTo("UNKNOWN");
        assertThat(scalar("SELECT h.status FROM ledger.hold h JOIN payments.outbound_credit c ON c.hold_id = h.id"
                        + " WHERE c.subject_id = ?", payment))
                .isEqualTo("ACTIVE");
        int sends = CORRIDOR.credits();
        HttpResponse<String> replay = pay(customer, quote, key);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(202);
        assertThat(field(replay.body(), "paymentId")).isEqualTo(payment.toString());
        assertThat(CORRIDOR.credits()).isEqualTo(sends);
    }

    @Test
    @DisplayName("a closed body: an amount - or any unknown field - is 422 and nothing is accepted or held")
    void aClientCannotSupplyAnAmount() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String quote = offer(customer, beneficiary(customer, "Clear Person"));
        HttpResponse<String> refused = client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\",\"amount\":\"1.00\"}",
                customer.token(), FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("api.ValidationFailed");
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", UUID.fromString(quote))).isEqualTo("ISSUED");
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("the needle (payment leg): the beneficiary's name is in no table, event, audit record or response the"
            + " payment touched")
    void theNameReachesNoPayment() throws Exception {
        FxTestClient.Customer customer = fundedCustomer("1000.00");
        String name = "Ophelia Needlecombe " + UUID.randomUUID().toString().substring(0, 6);
        String quote = offer(customer, beneficiary(customer, name));
        HttpResponse<String> paid = pay(customer, quote, FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        assertThat(paid.body()).doesNotContain(name);
        for (String table : List.of("crossborder.payment", "crossborder.payment_event", "payments.outbound_credit",
                "payments.routing_decision", "fx.cover")) {
            assertThat(count("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?", "%" + name + "%")).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE convert_from(payload, 'UTF8') LIKE ?",
                        "%" + name + "%"))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record t WHERE t::text LIKE ?", "%" + name + "%")).isZero();
    }

    // ------------------------------------------------------------------ plumbing

    private HttpResponse<String> pay(FxTestClient.Customer customer, String quote, String key) throws Exception {
        return client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", customer.token(), key);
    }

    private FxTestClient.Customer fundedCustomer(String eur) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money(eur, "EUR"));
        return customer;
    }

    /** A 100.00 EUR fixed-source offer to the beneficiary: the total debit is 102.50. Returns the quote's id. */
    private String offer(FxTestClient.Customer customer, String beneficiary) throws Exception {
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"100.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        return field(offered.body(), "id");
    }

    /** Lapses a FLIP beneficiary's clearance and asks for an offer: the re-screen hits and moves it IN_REVIEW. */
    private void rescreenToReview(FxTestClient.Customer customer, String beneficiary) throws Exception {
        lapse(screeningOf(beneficiary));
        HttpResponse<String> refused = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"100.00\"}", customer.token(), FxTestClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(scalar("SELECT status FROM crossborder.beneficiary WHERE id = ?", UUID.fromString(beneficiary)))
                .isEqualTo("IN_REVIEW");
    }

    /** Enrols and confirms a TOTP factor on the session's identity; the session itself stays password-only. */
    private void enrolAndConfirm(String sessionToken) throws Exception {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(client().post("/v1/me/mfa", null, sessionToken, null).body());
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> secret = Sensitive.of(matcher.group(1));
        long step = java.time.Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        String confirming = Authenticator.codeAt(secret, TotpParameters.current(),
                java.time.Instant.ofEpochSecond((step - 1) * TotpParameters.current().periodSeconds()));
        assertThat(client().post("/v1/me/mfa/confirmation", "{\"code\":\"" + confirming + "\"}", sessionToken, null).statusCode())
                .isEqualTo(204);
    }

    private static void assertNothingWritten(FxTestClient.Customer customer) throws Exception {
        assertThat(count("SELECT count(*) FROM crossborder.payment WHERE owner_party = ?", customer.party())).isZero();
        assertThat(count("SELECT count(*) FROM payments.outbound_credit WHERE customer_party_id = ?", customer.party())).isZero();
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN ledger.ledger_account a ON a.id = h.ledger_account_id"
                        + " JOIN accounts.customer_account p ON p.id = a.owner_ref WHERE p.customer_id = ?", customer.party()))
                .as("no hold on the customer's wallet").isZero();
    }

    /** Registers a JP/JPY beneficiary with a MATCH payee; its screening follows the scripted provider by name. */
    private String beneficiary(FxTestClient.Customer customer, String name) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("JP", "JPY", "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"JP\",\"currency\":\"JPY\",\"grant\":\""
                + grant + "\",\"name\":\"" + name + "\",\"nickname\":\"Osaka\",\"entityType\":\"INDIVIDUAL\"}",
                customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
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
        return UUID.fromString(scalar("SELECT screening_id::text FROM crossborder.beneficiary WHERE id = ?",
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
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the payment suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                        "{\"reason\":\"checked\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "xp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

        /** The screening provider, scripted by the name: FLIP clear once then a hit, anything else clear. */
        @Bean
        @Primary
        CounterpartyScreeningProvider scriptedPaymentScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
            return (screening, subject) -> {
                String name = subject.name();
                int call = seen.computeIfAbsent(name, any -> new AtomicInteger()).incrementAndGet();
                if (name.startsWith("FLIP") && call > 1) {
                    return CounterpartyScreeningProvider.Answer.of(Verdict.HIT, evidence);
                }
                return CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
            };
        }

        /** The risk seam, as armed - the parameter Phase 13 replaces. */
        @Bean
        @Primary
        CrossBorderRiskDecision<Connection> armedRiskDecision() {
            return (unitOfWork, instruction) -> RISK.get();
        }

        /** The limit seam, as armed. */
        @Bean
        @Primary
        CrossBorderLimitCheck<Connection> armedLimitCheck() {
            return (unitOfWork, instruction) -> LIMIT.get();
        }
    }
}
