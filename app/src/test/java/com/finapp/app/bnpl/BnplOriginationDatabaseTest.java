package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplApplicationId;
import com.finapp.bnpl.BnplApplicationStore;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplOfferStore;
import com.finapp.bnpl.BnplOffers;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.identity.Authorization;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The origination step and the offer against a real database, with credit's real decisions and checkout's real
 * sessions (`P12-TSK-011`; {@code PHASE_12_PLAN.md} sections 5.1, 5.2, 7.1 T-off, 7.4 and 12.1; {@code INV-BNPL-03},
 * {@code INV-LIFE-01}, {@code INV-LIFE-02}, {@code INV-CRD-04}, {@code INV-EVT-04}): ten sweepers on two instances' worth
 * of schedules offer once, nothing written by the losers; hints twice, late or never converge; a session that closes
 * while the decision is awaited closes the application and cancels credit's request; an approval below the purchase is
 * not offered; a decline is assessed NOT_ELIGIBLE only; the offer never outlives its session or its decision; the
 * decline racing the expiry leaves exactly one; the terms are hashed over their bytes; a retired terms version offers
 * nothing. Every count from the rows.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings credit's BNPL policies and each BNPL
 * product's terms into force and activates successors. Written by `P12-TSK-011` and <strong>owed a first run</strong>
 * (the owner's no-{@code databaseTest} instruction for the task).
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the BNPL offer and the origination schedule (P12-TSK-011)")
class BnplOriginationDatabaseTest {

    private static final int INSTANCES = 10;
    private static final long GROSS = 600_00L;
    private static final Duration PERMIT = Duration.ofSeconds(30);
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static boolean creditInForce;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration termsAdministration;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplApplicationStore applicationStore;
    @Autowired private BnplOfferStore offerStore;
    @Autowired private BnplCheckoutSessions checkoutSessions;
    @Autowired private BnplCreditDecisions creditDecisions;
    @Autowired private BnplOrigination origination;
    @Autowired private BnplOffers offers;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private IdGenerator ids;
    @Autowired private Clock clock;

    private BnplApplicationClient client;
    private CreditFixtures customers;

    @BeforeEach
    void world() throws Exception {
        client = new BnplApplicationClient(port);
        customers = new CreditFixtures(port);
        if (!creditInForce) {
            CreditFixtures.seedsInForce();
            for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
                CreditFixtures.bnplInForce(product);
            }
            creditInForce = true;
        }
        if (!BnplApplicationClient.termsActive(BnplProduct.BNPL_PAY_IN_4)) {
            BnplApplicationClient.termsInForce(termsAdministration, transactions,
                    BnplTermsDesk.terms(BnplTermsDeskTest.request(BnplTermsDeskTest.payIn4(), Map.of())));
        }
        if (!BnplApplicationClient.termsActive(BnplProduct.BNPL_PAY_IN_N)) {
            BnplApplicationClient.termsInForce(termsAdministration, transactions,
                    BnplTermsDesk.terms(BnplTermsDeskTest.request(BnplTermsDeskTest.payInN(), Map.of())));
        }
    }

    // ------------------------------------------------------------------ one offer

    @Test
    @Order(1)
    @DisplayName("ten sweepers on two instances' worth of schedules offer once: five sweeping, five handed the id after a"
            + " lapsed permit - one offer, one terms row, one offer history row, one BnplOffered, nothing by the losers")
    void tenSweepersOfferOnce() throws Exception {
        Applied applied = applyFor(BnplApplicationClient::payIn4);
        assertThat(CreditFixtures.decide(applied.request())).isEqualTo("APPROVED");
        dueNow(applied.id());
        List<BnplOriginationSchedule> schedules = new ArrayList<>();
        for (int i = 0; i < INSTANCES / 2; i++) {
            schedules.add(new BnplOriginationSchedule(origination, transactions,
                    new BnplOriginationMetrics(new SimpleMeterRegistry()), Duration.ofSeconds(5), 20, PERMIT));
        }
        List<Callable<Object>> sweepers = new ArrayList<>();
        schedules.forEach(schedule -> sweepers.add(schedule::sweepOnce));
        for (int i = 0; i < INSTANCES / 2; i++) {
            sweepers.add(() -> {
                try (SecurityContext.Scope actor = SecurityContext.enter(SYSTEM)) {
                    return transactions.inTransaction(uow -> origination.originate(uow,
                            BnplApplicationId.of(applied.id()), SYSTEM)).kind();
                }
            });
        }
        List<Object> outcomes = atOnce(sweepers);
        assertThat(outcomes.stream().filter(o -> o == BnplOrigination.Outcome.Kind.OFFERED).count())
                .as("at most one direct step offered: %s", outcomes).isLessThanOrEqualTo(1L);
        assertThat(offerCount(applied.id())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_offer_terms t JOIN bnpl.bnpl_offer o"
                + " ON o.id = t.offer_id WHERE o.application_id = ?", applied.id())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_offer_event e JOIN bnpl.bnpl_offer o"
                + " ON o.id = e.offer_id WHERE o.application_id = ?", applied.id())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ? AND"
                + " convert_from(payload, 'UTF8') LIKE ?", BnplOrigination.OFFERED_EVENT, "%" + applied.id() + "%"))
                .isEqualTo(1);
        assertThat(status(applied.id())).isEqualTo("OFFERED");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application_event WHERE application_id"
                + " = ? AND to_status = 'OFFERED'", applied.id())).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("hints twice, late or never converge: credit's events and checkout's expiry only pull the permit, the"
            + " sweep's read decides - one offer however often or late the hint, and an offer without any once the"
            + " permit lapses")
    void hintsTwiceLateOrNeverConverge() throws Exception {
        Applied hinted = applyFor(BnplApplicationClient::payIn4);
        sweep();
        assertThat(CreditFixtures.decide(hinted.request())).isEqualTo("APPROVED");
        UUID decision = UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM credit.credit_decision WHERE"
                + " decision_request_id = ?", hinted.request()));
        for (int i = 0; i < 2; i++) {
            transactions.inTransaction(uow -> origination.hintDecision(uow, decision));
            transactions.inTransaction(uow -> origination.hintRequest(uow, hinted.request()));
        }
        sweep();
        assertThat(status(hinted.id())).isEqualTo("OFFERED");
        boolean late = transactions.inTransaction(uow -> origination.hintDecision(uow, decision));
        assertThat(late).as("late").isFalse();
        boolean moved = transactions.inTransaction(uow -> origination.hintSession(uow, hinted.session()));
        assertThat(moved).isFalse();
        assertThat(offerCount(hinted.id())).isEqualTo(1);

        Applied never = applyFor(BnplApplicationClient::payIn4);
        sweep();
        assertThat(CreditFixtures.decide(never.request())).isEqualTo("APPROVED");
        dueNow(never.id());
        sweep();
        assertThat(status(never.id())).as("no hint: offered once the permit lapsed").isEqualTo("OFFERED");
    }

    @Test
    @Order(3)
    @DisplayName("a session that closes while the decision is awaited closes the application SESSION_CLOSED and cancels"
            + " credit's still-cancellable request; an approval arriving later is never offered")
    void aSessionThatExpiresClosesTheApplication() throws Exception {
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        BnplApplicationClient.Opened session = client.openSession(merchant, GROSS);
        Applied applied = apply(customer, session);
        assertThat(client.post("/v1/checkout/sessions/" + session.id() + "/abandonment", "{\"reason\":\"cancelled\"}",
                merchant.apiKey(), null).statusCode()).isEqualTo(200);
        dueNow(applied.id());
        sweep();
        assertThat(BnplApplicationClient.scalar("SELECT status || ':' || closure_reason FROM bnpl.bnpl_application"
                + " WHERE id = ?", applied.id())).isEqualTo("CLOSED_UNDECIDED:SESSION_CLOSED");
        assertThat(BnplApplicationClient.scalar("SELECT status FROM credit.decision_request WHERE id = ?",
                applied.request())).isEqualTo("CANCELLED");
        assertThat(offerCount(applied.id())).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("an approval below the purchase is not offered - CLOSED_UNDECIDED (APPROVED_BELOW_PURCHASE), no offer, the"
            + " merchant's one signal NOT_ELIGIBLE")
    void anApprovalBelowThePurchaseIsNotOffered() throws Exception {
        Applied applied = applyFor(BnplApplicationClient::payIn4);
        assertThat(CreditFixtures.decide(applied.request())).isEqualTo("APPROVED");
        BnplCreditDecisions below = reduced(creditDecisions, 1);
        BnplOrigination reducing = new BnplOrigination(applicationStore, offerStore, termsAdministration, below,
                checkoutSessions, outboxWriter, ids, clock);
        BnplOrigination.Outcome outcome = transactions.inTransaction(uow -> reducing.originate(uow,
                BnplApplicationId.of(applied.id()), SYSTEM));
        assertThat(outcome.reason()).contains(com.finapp.bnpl.BnplApplicationClosureReason.APPROVED_BELOW_PURCHASE);
        assertThat(BnplApplicationClient.scalar("SELECT closure_reason FROM bnpl.bnpl_application WHERE id = ?",
                applied.id())).isEqualTo("APPROVED_BELOW_PURCHASE");
        assertThat(offerCount(applied.id())).isZero();
        assertThat(eligibility(applied.id())).isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    @Order(5)
    @DisplayName("a decline is DECLINED and assessed NOT_ELIGIBLE only - the event carries the session and the outcome,"
            + " never a reason, a score or an amount; the customer reads credit's reasons, the merchant nothing more")
    void aDeclineIsAssessedNotEligibleOnly() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        CreditFixtures.bureauBalance(customer.party(), 900_000_00L);
        Applied applied = apply(customer, client.openSession(enabledMerchant(), GROSS));
        assertThat(CreditFixtures.decide(applied.request())).isEqualTo("DECLINED");
        dueNow(applied.id());
        sweep();
        assertThat(status(applied.id())).isEqualTo("DECLINED");
        String payload = BnplApplicationClient.scalar("SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE"
                + " event_type = ? AND aggregate_id = ?", BnplOrigination.ELIGIBILITY_EVENT, applied.id());
        assertThat(payload).isEqualTo("{\"checkoutSessionId\":\"" + applied.session() + "\",\"outcome\":\"NOT_ELIGIBLE\"}");
        assertThat(client.read(customer.token(), applied.id().toString()).body()).contains("\"reasons\"");
    }

    @Test
    @Order(6)
    @DisplayName("the offer never outlives the session or the decision: expires_at at or before both, stamped by the"
            + " database, frozen for every role")
    void theOfferNeverOutlivesTheSessionOrTheDecision() throws Exception {
        Applied applied = applyFor(BnplApplicationClient::payIn4);
        assertThat(CreditFixtures.decide(applied.request())).isEqualTo("APPROVED");
        dueNow(applied.id());
        sweep();
        assertThat(BnplApplicationClient.scalar("SELECT (o.expires_at <= s.expires_at AND o.expires_at <= d.valid_until"
                + " AND o.expires_at = LEAST(s.expires_at, d.valid_until, o.offered_at + interval '30 minutes'))::text"
                + " FROM bnpl.bnpl_offer o JOIN checkout.checkout_session s ON s.id = o.checkout_session_id"
                + " JOIN credit.credit_decision d ON d.id = o.decision_id WHERE o.application_id = ?", applied.id()))
                .isEqualTo("true");
        try (Connection owner = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> BnplApplicationClient.execute(owner, "UPDATE bnpl.bnpl_offer SET expires_at ="
                    + " expires_at + interval '1 day' WHERE application_id = ?", applied.id()))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @Order(7)
    @DisplayName("the customer's decline racing the expiry at the boundary leaves exactly one of DECLINED and EXPIRED -"
            + " over five offers, the decliner's and the sweeper's instance clocks 5 s apart, one edge and one row each")
    void declineAndExpiryLeaveExactlyOne() throws Exception {
        for (int round = 0; round < 5; round++) {
            CreditFixtures.Customer customer = customers.consentingCustomer();
            Applied applied = apply(customer, client.openSession(enabledMerchant(), GROSS));
            assertThat(CreditFixtures.decide(applied.request())).isEqualTo("APPROVED");
            BnplCreditDecisions brief = briefly(creditDecisions, Duration.ofSeconds(2));
            BnplOrigination offering = new BnplOrigination(applicationStore, offerStore, termsAdministration, brief,
                    checkoutSessions, outboxWriter, ids, Clock.offset(clock, Duration.ofSeconds(5)));
            transactions.inTransaction(uow -> offering.originate(uow, BnplApplicationId.of(applied.id()), SYSTEM));
            BnplOfferId offer = BnplOfferId.of(UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM"
                    + " bnpl.bnpl_offer WHERE application_id = ?", applied.id())));
            Instant expiry = Instant.parse(BnplApplicationClient.scalar("SELECT to_char(expires_at AT TIME ZONE 'UTC',"
                    + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM bnpl.bnpl_offer WHERE id = ?", offer.value()));
            BnplOrigination skewed = new BnplOrigination(applicationStore, offerStore, termsAdministration,
                    creditDecisions, checkoutSessions, outboxWriter, ids, Clock.offset(clock, Duration.ofSeconds(-5)));
            Thread.sleep(Math.max(0, Duration.between(Instant.now(), expiry).toMillis() - 5));
            atOnce(List.<Callable<Object>>of(
                    () -> {
                        try {
                            return client.post("/v1/me/bnpl-offers/" + offer.value() + "/decline", null,
                                    customer.token(), BnplApplicationClient.key()).statusCode();
                        } catch (Exception failed) {
                            return failed.getClass().getSimpleName();
                        }
                    },
                    () -> {
                        for (int i = 0; i < 50; i++) {
                            try (SecurityContext.Scope actor = SecurityContext.enter(SYSTEM)) {
                                if (transactions.inTransaction(uow -> skewed.expire(uow, offer, SYSTEM)).isPresent()) {
                                    return "EXPIRED";
                                }
                            }
                            Thread.sleep(20);
                        }
                        return "not expired";
                    }));
            assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = ?", offer.value()))
                    .isIn("DECLINED", "EXPIRED");
            assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_offer_event WHERE offer_id = ? AND"
                    + " from_status = 'OFFERED'", offer.value())).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    @Order(8)
    @DisplayName("the terms are hashed over their bytes: the stored SHA-256 is sha256 of the stored text for every row, a"
            + " row whose hash is not its bytes is refused, and the terms never change")
    void theTermsAreHashedOverTheirBytes() throws Exception {
        Applied applied = applyFor(BnplApplicationClient::payIn4);
        assertThat(CreditFixtures.decide(applied.request())).isEqualTo("APPROVED");
        dueNow(applied.id());
        sweep();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_offer_terms WHERE terms_sha256 <>"
                + " sha256(convert_to(canonical_terms, 'UTF8'))")).isZero();
        String offer = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_offer WHERE application_id = ?",
                applied.id());
        try (Connection owner = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> BnplApplicationClient.execute(owner, "UPDATE bnpl.bnpl_offer_terms SET"
                    + " canonical_terms = canonical_terms || ' ' WHERE offer_id = ?::uuid", offer))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(() -> BnplApplicationClient.execute(app, "DELETE FROM bnpl.bnpl_offer_terms WHERE"
                    + " offer_id = ?::uuid", offer)).isInstanceOf(SQLException.class);
        }
        assertThat(client.read(applied.customer().token(), applied.id().toString()).body())
                .as("the owner finds the offer from the application").contains("\"offerId\":\"" + offer + "\"");
        HttpResponse<String> read = client.get("/v1/me/bnpl-offers/" + offer, applied.customer().token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"termsSha256\":\"" + BnplApplicationClient.scalar("SELECT"
                + " encode(terms_sha256, 'hex') FROM bnpl.bnpl_offer_terms WHERE offer_id = ?::uuid", offer));
    }

    @Test
    @Order(9)
    @DisplayName("a retired terms version offers nothing: the offer pins the successor ACTIVE at the offering instant, never"
            + " the version the application recorded")
    void aRetiredTermsVersionOffersNothing() throws Exception {
        Applied applied = applyFor(BnplApplicationClient::payIn4);
        String recorded = BnplApplicationClient.scalar("SELECT terms_version_id::text FROM bnpl.bnpl_application WHERE"
                + " id = ?", applied.id());
        BnplApplicationClient.termsInForce(termsAdministration, transactions,
                BnplTermsDesk.terms(BnplTermsDeskTest.request(BnplTermsDeskTest.payIn4(), Map.of())));
        assertThat(CreditFixtures.decide(applied.request())).isEqualTo("APPROVED");
        dueNow(applied.id());
        sweep();
        String pinned = BnplApplicationClient.scalar("SELECT terms_version_id::text FROM bnpl.bnpl_offer WHERE"
                + " application_id = ?", applied.id());
        assertThat(pinned).isNotEqualTo(recorded);
        assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_terms_version WHERE id = ?::uuid",
                recorded)).isEqualTo("RETIRED");
    }

    @Test
    @Order(10)
    @DisplayName("another party's offer is not found - the read and the decline answer 404 bnpl.NotFound exactly as an"
            + " unknown id, and nothing moves (ADR-0031's negative); the owner reads it")
    void anotherPartysOfferIsNotFound() throws Exception {
        Applied applied = applyFor(BnplApplicationClient::payIn4);
        assertThat(CreditFixtures.decide(applied.request())).isEqualTo("APPROVED");
        dueNow(applied.id());
        sweep();
        String offer = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_offer WHERE application_id = ?",
                applied.id());
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        for (String asked : List.of(offer, ids.next().toString())) {
            HttpResponse<String> read = client.get("/v1/me/bnpl-offers/" + asked, stranger.token());
            assertThat(read.statusCode()).as(read.body()).isEqualTo(404);
            assertThat(read.body()).contains("bnpl.NotFound");
            HttpResponse<String> decline = client.post("/v1/me/bnpl-offers/" + asked + "/decline", null,
                    stranger.token(), BnplApplicationClient.key());
            assertThat(decline.statusCode()).as(decline.body()).isEqualTo(404);
        }
        assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = ?::uuid", offer))
                .isEqualTo("OFFERED");
        assertThat(client.get("/v1/me/bnpl-offers/" + offer, applied.customer().token()).statusCode())
                .as("the owner reads it").isEqualTo(200);
    }

    // ------------------------------------------------------------------ plumbing

    private record Applied(UUID id, UUID request, UUID session, CreditFixtures.Customer customer) {}

    private interface Body {
        String of(String token);
    }

    private Applied applyFor(Body body) throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        BnplApplicationClient.Opened session = client.openSession(enabledMerchant(), GROSS);
        HttpResponse<String> applied = client.submit(customer.token(), body.of(session.token()),
                BnplApplicationClient.key());
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(202);
        return new Applied(UUID.fromString(BnplApplicationClient.field(applied.body(), "applicationId")),
                UUID.fromString(BnplApplicationClient.field(applied.body(), "decisionRequestId")), session.id(),
                customer);
    }

    private Applied apply(CreditFixtures.Customer customer, BnplApplicationClient.Opened session) throws Exception {
        HttpResponse<String> applied = client.submit(customer.token(), BnplApplicationClient.payIn4(session.token()),
                BnplApplicationClient.key());
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(202);
        return new Applied(UUID.fromString(BnplApplicationClient.field(applied.body(), "applicationId")),
                UUID.fromString(BnplApplicationClient.field(applied.body(), "decisionRequestId")), session.id(),
                customer);
    }

    private BnplApplicationClient.Merchant enabledMerchant() throws Exception {
        BnplApplicationClient.Merchant merchant = client.tradingMerchant(authorization);
        BnplApplicationClient.bnplEnabled(merchant, enablements, pricing, feeSchedules, "0.0299", 0);
        return merchant;
    }

    /** The application's permit made due now - a permit-only update the trigger admits while awaiting. */
    private static void dueNow(UUID application) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            BnplApplicationClient.execute(app, "UPDATE bnpl.bnpl_application SET next_step_at = clock_timestamp()"
                    + " - interval '1 second' WHERE id = ? AND status = 'AWAITING_DECISION'", application);
        }
    }

    private void sweep() {
        new BnplOriginationSchedule(origination, transactions, new BnplOriginationMetrics(new SimpleMeterRegistry()),
                Duration.ofSeconds(5), 50, PERMIT).sweepOnce();
    }

    private static String status(UUID application) throws Exception {
        return BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_application WHERE id = ?", application);
    }

    private static long offerCount(UUID application) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_offer WHERE application_id = ?",
                application);
    }

    private static String eligibility(UUID application) throws Exception {
        return BnplApplicationClient.scalar("SELECT substring(convert_from(payload, 'UTF8') FROM '\"outcome\":\"([A-Z_]+)\"')"
                + " FROM platform.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                BnplOrigination.ELIGIBILITY_EVENT, application);
    }

    /** Credit's real answer, an approval reduced by {@code minorUnits}. */
    private static BnplCreditDecisions reduced(BnplCreditDecisions real, long minorUnits) {
        return delegating(real, approved -> new BnplCreditDecisions.CreditOutcome.Approved(approved.decision(),
                approved.party(), approved.product(), approved.amount().minus(
                        com.finapp.sharedkernel.money.Money.ofMinorUnits(minorUnits, approved.amount().currency())),
                approved.termMonths(), approved.validUntil()));
    }

    /** Credit's real answer, an approval valid only {@code validity} from now. */
    private static BnplCreditDecisions briefly(BnplCreditDecisions real, Duration validity) {
        return delegating(real, approved -> new BnplCreditDecisions.CreditOutcome.Approved(approved.decision(),
                approved.party(), approved.product(), approved.amount(), approved.termMonths(),
                Instant.now().plus(validity)));
    }

    private static BnplCreditDecisions delegating(BnplCreditDecisions real,
            java.util.function.UnaryOperator<BnplCreditDecisions.CreditOutcome.Approved> change) {
        return new BnplCreditDecisions() {
            @Override
            public UUID open(Connection unitOfWork, UUID party, CreditRequest request, Actor actor,
                    CorrelationId correlation) {
                return real.open(unitOfWork, party, request, actor, correlation);
            }

            @Override
            public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                    CorrelationId correlation) {
                real.cancel(unitOfWork, decisionRequest, party, actor, correlation);
            }

            @Override
            public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
                return real.reasonTexts(unitOfWork, decisionRequest);
            }

            @Override
            public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
                CreditOutcome answer = real.decisionFor(unitOfWork, decisionRequest, party);
                return answer instanceof CreditOutcome.Approved approved ? change.apply(approved) : answer;
            }

            @Override
            public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
                return real.requestOf(unitOfWork, decision);
            }
        };
    }

    private static <T> List<T> atOnce(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
