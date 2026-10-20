package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplApplication;
import com.finapp.bnpl.BnplApplicationStore;
import com.finapp.bnpl.BnplApplications;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplPartyStanding;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProductBounds;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.identity.Authorization;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
 * The BNPL application against a real database, over HTTP and through the domain, with credit's real door
 * (`P12-TSK-010`; {@code BNPL_LIFECYCLES.md} section 4.2, {@code PHASE_12_PLAN.md} section 7.1 T-app;
 * {@code INV-BNPL-03}, {@code INV-IDEM-01}, {@code INV-LIFE-01}, {@code INV-LIFE-02}, {@code INV-CRD-04}): one act opening
 * both the application and credit's decision request from the BNPL origin for exactly the session's gross; every race
 * counted from the rows - ten customers on one session, one customer on many sessions, one key replayed; every refusal -
 * bnpl's or credit's - leaving neither row; a failure after each insert leaving neither; the withdrawal cancelling
 * credit's request, and racing the origination step's B1 move to exactly one of {@code WITHDRAWN} and the step's edge
 * ({@code CLOSED_UNDECIDED} since `P12-TSK-011`: {@code bnpl V004} admits {@code -> OFFERED} only beside an offer, so the
 * race's raw move takes the step's other B1 edge).
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings credit's BNPL policies and each BNPL
 * product's terms into force, which the shared container's suites expect to find as they left them. Written by
 * `P12-TSK-010` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction for the task).
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the BNPL application at checkout (P12-TSK-010)")
class BnplApplicationDatabaseTest {

    private static final int INSTANCES = 10;
    private static final long GROSS = 600_00L;
    private static boolean creditInForce;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration termsAdministration;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplApplicationStore applicationStore;
    @Autowired private BnplPartyStanding partyStanding;
    @Autowired private BnplCheckoutSessions checkoutSessions;
    @Autowired private BnplMerchants merchants;
    @Autowired private BnplProductBounds bounds;
    @Autowired private BnplCreditDecisions creditDecisions;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private IdGenerator ids;
    @Autowired private Clock clock;

    private BnplApplicationClient client;
    private CreditFixtures customers;

    @BeforeEach
    void world() {
        client = new BnplApplicationClient(port);
        customers = new CreditFixtures(port);
        if (!creditInForce) {
            CreditFixtures.seedsInForce();
            for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
                CreditFixtures.bnplInForce(product);
            }
            creditInForce = true;
        }
    }

    // ------------------------------------------------------------------ the product not offered

    @Test
    @Order(1)
    @DisplayName("without ACTIVE terms the product is not offered - 422 bnpl.ProductNotOffered for both products, nothing"
            + " written in bnpl or in credit; offered once two persons activate its terms")
    void withoutActiveTermsTheProductIsNotOffered() throws Exception {
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        for (String body : List.of(BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()),
                BnplApplicationClient.payInN(client.openSession(merchant, GROSS).token(), 6))) {
            HttpResponse<String> refused = client.submit(customer.token(), body, BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("bnpl.ProductNotOffered");
        }
        assertNothingWritten(customer.party());
        termsInForce();
        HttpResponse<String> applied = client.submit(customer.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()), BnplApplicationClient.key());
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(202);
    }

    // ------------------------------------------------------------------ one act, both records

    @Test
    @Order(2)
    @DisplayName("one act opens exactly one credit request from the BNPL origin for the session's gross - pay-in-4 no term,"
            + " pay-in-N the plan's months - the application AWAITING_DECISION with both history rows, its event and audit"
            + " record; the token never echoed")
    void oneActOpensExactlyOneCreditRequest() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        BnplApplicationClient.Opened session = client.openSession(merchant, GROSS);
        HttpResponse<String> applied = client.submit(customer.token(), BnplApplicationClient.payIn4(session.token()),
                BnplApplicationClient.key());
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(202);
        assertThat(applied.body()).doesNotContain(session.token()).doesNotContain(session.id().toString())
                .contains("\"status\":\"AWAITING_DECISION\"");
        String id = BnplApplicationClient.field(applied.body(), "applicationId");
        String request = BnplApplicationClient.field(applied.body(), "decisionRequestId");
        assertThat(BnplApplicationClient.scalar("SELECT origin || ':' || product || ':' || requested_minor || ':'"
                + " || coalesce(term_months::text, '-') || ':' || party_id FROM credit.decision_request WHERE id = ?::uuid",
                request)).isEqualTo("BNPL_APPLICATION:BNPL_PAY_IN_4:" + GROSS + ":-:" + customer.party());
        assertThat(BnplApplicationClient.scalar("SELECT status || ':' || checkout_session_id || ':' || merchant_id"
                + " FROM bnpl.bnpl_application WHERE id = ?::uuid", id))
                .isEqualTo("AWAITING_DECISION:" + session.id() + ":" + merchant.id());
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application_event WHERE application_id"
                + " = ?::uuid", id)).isEqualTo(2);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                + " AND aggregate_id = ?::uuid", BnplApplications.SUBMITTED_EVENT, id)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.ApplicationSubmitted' AND target_id = ?", id)).isEqualTo(1);

        HttpResponse<String> monthly = client.submit(customer.token(), BnplApplicationClient.payInN(
                client.openSession(merchant, GROSS).token(), 6), BnplApplicationClient.key());
        assertThat(monthly.statusCode()).as(monthly.body()).isEqualTo(202);
        assertThat(BnplApplicationClient.scalar("SELECT term_months::text FROM credit.decision_request WHERE id ="
                + " ?::uuid", BnplApplicationClient.field(monthly.body(), "decisionRequestId"))).isEqualTo("6");
    }

    @Test
    @Order(3)
    @DisplayName("ten customers applying for one session at once open one application and one credit request; nine 409"
            + " bnpl.ApplicationOpen, their credit requests rolled back - counted from the rows")
    void tenApplicationsForOneSessionOpenOne() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        BnplApplicationClient.Opened session = client.openSession(merchant, GROSS);
        List<CreditFixtures.Customer> applicants = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            applicants.add(customers.consentingCustomer());
        }
        List<HttpResponse<String>> answers = atOnce(applicants.stream()
                .<Callable<HttpResponse<String>>>map(applicant -> () -> client.submit(applicant.token(),
                        BnplApplicationClient.payIn4(session.token()), BnplApplicationClient.key()))
                .toList());
        Map<Integer, Long> statuses = answers.stream().collect(Collectors.groupingBy(HttpResponse::statusCode,
                Collectors.counting()));
        assertThat(statuses).as(answers.stream().map(HttpResponse::body).toList().toString())
                .containsEntry(202, 1L).containsEntry(409, 9L);
        assertThat(answers.stream().filter(answer -> answer.statusCode() == 409))
                .allSatisfy(answer -> assertThat(answer.body()).contains("bnpl.ApplicationOpen"));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application WHERE checkout_session_id"
                + " = ?", session.id())).isEqualTo(1);
        long requests = 0;
        for (CreditFixtures.Customer applicant : applicants) {
            requests += BnplApplicationClient.count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?",
                    applicant.party());
        }
        assertThat(requests).as("the losers' credit requests rolled back with them").isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("one customer applying for one product on ten sessions at once opens one - credit's one open request,"
            + " the losers 409 bnpl.ApplicationOpen; the other product stays free")
    void oneOpenApplicationPerPartyAndProduct() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            tokens.add(client.openSession(merchant, GROSS).token());
        }
        List<HttpResponse<String>> answers = atOnce(tokens.stream()
                .<Callable<HttpResponse<String>>>map(token -> () -> client.submit(customer.token(),
                        BnplApplicationClient.payIn4(token), BnplApplicationClient.key()))
                .toList());
        assertThat(answers.stream().filter(answer -> answer.statusCode() == 202)).hasSize(1);
        assertThat(answers.stream().filter(answer -> answer.statusCode() == 409
                && answer.body().contains("bnpl.ApplicationOpen"))).hasSize(INSTANCES - 1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?",
                customer.party())).isEqualTo(1);
        assertThat(client.submit(customer.token(), BnplApplicationClient.payInN(
                client.openSession(merchant, GROSS).token(), 3), BnplApplicationClient.key()).statusCode())
                .as("another product").isEqualTo(202);
    }

    @Test
    @Order(5)
    @DisplayName("one key replayed answers the stored application - one row, one credit request; the key with another"
            + " session's token is refused")
    void theSameKeyReplays() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        BnplApplicationClient.Opened session = client.openSession(merchant, GROSS);
        String key = BnplApplicationClient.key();
        HttpResponse<String> first = client.submit(customer.token(), BnplApplicationClient.payIn4(session.token()), key);
        HttpResponse<String> again = client.submit(customer.token(), BnplApplicationClient.payIn4(session.token()), key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(BnplApplicationClient.field(again.body(), "applicationId"))
                .isEqualTo(BnplApplicationClient.field(first.body(), "applicationId"));
        HttpResponse<String> other = client.submit(customer.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()), key);
        assertThat(other.statusCode()).as(other.body()).isBetween(400, 499);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application WHERE party_id = ?",
                customer.party())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?",
                customer.party())).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("the credit request and the application commit together: a failure injected after credit's insert, after"
            + " the application's and after its move leaves neither")
    void theCreditRequestAndTheApplicationCommitTogether() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        for (String failAt : List.of("credit", "insert", "transition")) {
            BnplApplicationClient.Opened session = client.openSession(merchant, GROSS);
            BnplApplications failing = new BnplApplications(failingStore(failAt), termsAdministration, partyStanding,
                    checkoutSessions, merchants, bounds, failingCredit(failAt), outboxWriter, auditWriter, ids, clock);
            Actor actor = new Actor(customer.party().toString(), ActorType.CUSTOMER);
            assertThatThrownBy(() -> BnplApplicationClient.as(actor, () -> transactions.inTransaction(unit ->
                    failing.submit(unit, customer.party(), new BnplApplications.Submission(Sensitive.of(session.token()),
                            BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4), actor, CorrelationId.generate(ids)))))
                    .as(failAt).hasMessageContaining("injected");
            assertNothingWritten(customer.party());
        }
    }

    // ------------------------------------------------------------------ the withdrawal

    @Test
    @Order(7)
    @DisplayName("a withdrawal cancels the credit request in the same commit - WITHDRAWN, credit CANCELLED from the BNPL"
            + " origin, the Closed event and the audit record; once credit has evaluated a request, 409"
            + " bnpl.ApplicationNotWithdrawable and nothing moves")
    void aWithdrawalCancelsTheCreditRequest() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> applied = client.submit(customer.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()), BnplApplicationClient.key());
        String id = BnplApplicationClient.field(applied.body(), "applicationId");
        String request = BnplApplicationClient.field(applied.body(), "decisionRequestId");
        HttpResponse<String> withdrawn = client.withdraw(customer.token(), id, BnplApplicationClient.key());
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(withdrawn.body()).contains("\"status\":\"WITHDRAWN\"");
        assertThat(BnplApplicationClient.scalar("SELECT status FROM credit.decision_request WHERE id = ?::uuid", request))
                .isEqualTo("CANCELLED");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.ApplicationWithdrawn' AND target_id = ?", id)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                + " AND aggregate_id = ?::uuid", BnplApplications.CLOSED_EVENT, id)).isEqualTo(1);

        HttpResponse<String> evaluatedFirst = client.submit(customer.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()), BnplApplicationClient.key());
        String evaluated = BnplApplicationClient.field(evaluatedFirst.body(), "applicationId");
        CreditFixtures.stepUntil(UUID.fromString(BnplApplicationClient.field(evaluatedFirst.body(),
                "decisionRequestId")), "EVALUATED");
        HttpResponse<String> refused = client.withdraw(customer.token(), evaluated, BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("bnpl.ApplicationNotWithdrawable");
        assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_application WHERE id = ?::uuid",
                evaluated)).isEqualTo("AWAITING_DECISION");
    }

    @Test
    @Order(8)
    @DisplayName("a withdrawal racing the origination step's B1 move leaves exactly one of WITHDRAWN and CLOSED_UNDECIDED,"
            + " over ten rounds - and a closed application's credit request is never cancelled by the withdrawal")
    void withdrawalAndAnOfferLeaveExactlyOne() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        for (int round = 0; round < INSTANCES; round++) {
            CreditFixtures.Customer customer = customers.consentingCustomer();
            HttpResponse<String> applied = client.submit(customer.token(),
                    BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()),
                    BnplApplicationClient.key());
            String id = BnplApplicationClient.field(applied.body(), "applicationId");
            String request = BnplApplicationClient.field(applied.body(), "decisionRequestId");
            List<Object> outcomes = atOnce(List.<Callable<Object>>of(
                    () -> client.withdraw(customer.token(), id, BnplApplicationClient.key()).statusCode(),
                    () -> closedUnderB1(UUID.fromString(id))));
            String status = BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_application WHERE id = ?::uuid",
                    id);
            assertThat(status).as("round %d: %s", round, outcomes).isIn("WITHDRAWN", "CLOSED_UNDECIDED");
            if (status.equals("CLOSED_UNDECIDED")) {
                assertThat(outcomes.get(0)).isEqualTo(409);
                assertThat(BnplApplicationClient.scalar("SELECT status FROM credit.decision_request WHERE id = ?::uuid",
                        request)).as("the withdrawal lost: it cancelled nothing").isNotEqualTo("CANCELLED");
            } else {
                assertThat(outcomes.get(0)).isEqualTo(200);
                assertThat(outcomes.get(1)).as("the step found it moved").isEqualTo(0);
            }
        }
    }

    @Test
    @Order(13)
    @DisplayName("another party's application is not found - the read and the withdrawal answer 404 bnpl.NotFound exactly"
            + " as an unknown id, and nothing moves (ADR-0031's negative)")
    void anotherPartysApplicationIsNotFound() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer owner = customers.consentingCustomer();
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> applied = client.submit(owner.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()), BnplApplicationClient.key());
        String id = BnplApplicationClient.field(applied.body(), "applicationId");
        for (String asked : List.of(id, UUID.randomUUID().toString())) {
            HttpResponse<String> read = client.read(stranger.token(), asked);
            assertThat(read.statusCode()).as(read.body()).isEqualTo(404);
            assertThat(read.body()).contains("bnpl.NotFound");
            HttpResponse<String> withdrawal = client.withdraw(stranger.token(), asked, BnplApplicationClient.key());
            assertThat(withdrawal.statusCode()).as(withdrawal.body()).isEqualTo(404);
        }
        assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_application WHERE id = ?::uuid", id))
                .isEqualTo("AWAITING_DECISION");
        assertThat(client.read(owner.token(), id).statusCode()).as("the owner reads it").isEqualTo(200);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    @Test
    @Order(9)
    @DisplayName("a session not open is not eligible - an unknown token, a session the merchant abandoned - 422"
            + " bnpl.SessionNotEligible, nothing written")
    void aSessionNotOpenIsNotEligible() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        BnplApplicationClient.Opened abandoned = client.openSession(merchant, GROSS);
        assertThat(client.post("/v1/checkout/sessions/" + abandoned.id() + "/abandonment", "{\"reason\":\"cancelled\"}",
                merchant.apiKey(), null).statusCode()).isEqualTo(200);
        for (String token : List.of("cs_unknown_token_value", abandoned.token())) {
            HttpResponse<String> refused = client.submit(customer.token(), BnplApplicationClient.payIn4(token),
                    BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("bnpl.SessionNotEligible").doesNotContain(token);
        }
        assertNothingWritten(customer.party());
    }

    @Test
    @Order(10)
    @DisplayName("a merchant not enabled refuses - 422 bnpl.MerchantNotEnabled, nothing written")
    void aMerchantNotEnabledRefuses() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = client.tradingMerchant(authorization);
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> refused = client.submit(customer.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()), BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("bnpl.MerchantNotEnabled");
        assertNothingWritten(customer.party());
    }

    @Test
    @Order(11)
    @DisplayName("a sale below its BNPL fee is not eligible - 422 bnpl.SessionNotEligible, nothing pinned, nothing written")
    void aSaleBelowItsFeeIsNotEligible() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = client.tradingMerchant(authorization);
        // A fixed BNPL fee of EUR 60.00: a EUR 55.00 sale (within pay-in-4's bounds, above the card fee) cannot cover it.
        BnplApplicationClient.bnplEnabled(merchant, enablements, pricing, feeSchedules, "0", 60_00);
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> refused = client.submit(customer.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, 55_00).token()), BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("bnpl.SessionNotEligible");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM merchant.bnpl_fee_pin WHERE merchant_id = ?::uuid",
                merchant.id())).isZero();
        assertNothingWritten(customer.party());
    }

    @Test
    @Order(12)
    @DisplayName("standing lost refuses - 409 credit.ApplicantNotEligible, credit's own answer, nothing written")
    void standingLostRefuses() throws Exception {
        termsInForce();
        BnplApplicationClient.Merchant merchant = enabledMerchant();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        try (Connection app = DatabaseRoles.application()) {
            BnplApplicationClient.execute(app, "UPDATE party.customer SET status = 'SUSPENDED', status_changed_at ="
                    + " GREATEST(now(), status_changed_at) WHERE party_id = ?", customer.party());
        }
        HttpResponse<String> refused = client.submit(customer.token(),
                BnplApplicationClient.payIn4(client.openSession(merchant, GROSS).token()), BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("credit.ApplicantNotEligible");
        assertNothingWritten(customer.party());
    }

    // ------------------------------------------------------------------ plumbing

    private BnplApplicationClient.Merchant enabledMerchant() throws Exception {
        BnplApplicationClient.Merchant merchant = client.tradingMerchant(authorization);
        BnplApplicationClient.bnplEnabled(merchant, enablements, pricing, feeSchedules, "0.0299", 0);
        return merchant;
    }

    /** Each BNPL product's terms in force, unless a version already is. */
    private void termsInForce() throws Exception {
        if (!BnplApplicationClient.termsActive(BnplProduct.BNPL_PAY_IN_4)) {
            BnplApplicationClient.termsInForce(termsAdministration, transactions,
                    BnplTermsDesk.terms(BnplTermsDeskTest.request(BnplTermsDeskTest.payIn4(), Map.of())));
        }
        if (!BnplApplicationClient.termsActive(BnplProduct.BNPL_PAY_IN_N)) {
            BnplApplicationClient.termsInForce(termsAdministration, transactions,
                    BnplTermsDesk.terms(BnplTermsDeskTest.request(BnplTermsDeskTest.payInN(), Map.of())));
        }
    }

    /**
     * The origination step's B1 move as `P12-TSK-011` makes it for a session found closed: the row locked, moved only from
     * AWAITING_DECISION ({@code -> OFFERED} needs its offer beside it since {@code bnpl V004}).
     */
    private static int closedUnderB1(UUID application) throws Exception {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (PreparedStatement lock = owner.prepareStatement(
                    "SELECT status FROM bnpl.bnpl_application WHERE id = ? FOR UPDATE");
                    PreparedStatement move = owner.prepareStatement("UPDATE bnpl.bnpl_application SET status ="
                            + " 'CLOSED_UNDECIDED', closure_reason = 'SESSION_CLOSED'"
                            + " WHERE id = ? AND status = 'AWAITING_DECISION'")) {
                lock.setObject(1, application);
                lock.executeQuery().close();
                move.setObject(1, application);
                int moved = move.executeUpdate();
                owner.commit();
                return moved;
            }
        }
    }

    private BnplApplicationStore failingStore(String failAt) {
        return new BnplApplicationStore() {
            @Override
            public boolean insert(Connection unitOfWork, NewApplication application) {
                boolean inserted = applicationStore.insert(unitOfWork, application);
                if (failAt.equals("insert")) {
                    throw new IllegalStateException("injected after the application's insert");
                }
                return inserted;
            }

            @Override
            public boolean transition(Connection unitOfWork, com.finapp.bnpl.BnplApplicationId id,
                    com.finapp.bnpl.BnplApplicationStatus from, com.finapp.bnpl.BnplApplicationStatus to) {
                boolean moved = applicationStore.transition(unitOfWork, id, from, to);
                if (failAt.equals("transition")) {
                    throw new IllegalStateException("injected after the application's move");
                }
                return moved;
            }

            @Override
            public void appendEvent(Connection unitOfWork, HistoryRow row) {
                applicationStore.appendEvent(unitOfWork, row);
            }

            @Override
            public java.util.Optional<BnplApplication> ownedBy(Connection unitOfWork,
                    com.finapp.bnpl.BnplApplicationId id, UUID party) {
                return applicationStore.ownedBy(unitOfWork, id, party);
            }

            @Override
            public java.util.Optional<BnplApplication> lockOwnedBy(Connection unitOfWork,
                    com.finapp.bnpl.BnplApplicationId id, UUID party) {
                return applicationStore.lockOwnedBy(unitOfWork, id, party);
            }

            @Override
            public boolean close(Connection unitOfWork, com.finapp.bnpl.BnplApplicationId id,
                    com.finapp.bnpl.BnplApplicationStatus to,
                    java.util.Optional<com.finapp.bnpl.BnplApplicationClosureReason> reason) {
                return applicationStore.close(unitOfWork, id, to, reason);
            }

            @Override
            public List<com.finapp.bnpl.BnplApplicationId> claimAwaiting(Connection unitOfWork, int limit,
                    java.time.Duration permit) {
                return applicationStore.claimAwaiting(unitOfWork, limit, permit);
            }

            @Override
            public java.util.Optional<BnplApplication> lockClaimed(Connection unitOfWork,
                    com.finapp.bnpl.BnplApplicationId id) {
                return applicationStore.lockClaimed(unitOfWork, id);
            }

            @Override
            public boolean hintRequest(Connection unitOfWork, UUID decisionRequest) {
                return applicationStore.hintRequest(unitOfWork, decisionRequest);
            }

            @Override
            public boolean hintSession(Connection unitOfWork, UUID checkoutSession) {
                return applicationStore.hintSession(unitOfWork, checkoutSession);
            }

            @Override
            public java.util.Optional<java.time.Duration> oldestAwaitingDecisionAge(Connection unitOfWork) {
                return applicationStore.oldestAwaitingDecisionAge(unitOfWork);
            }
        };
    }

    private BnplCreditDecisions failingCredit(String failAt) {
        return new BnplCreditDecisions() {
            @Override
            public UUID open(Connection unitOfWork, UUID party, CreditRequest request, Actor actor,
                    CorrelationId correlation) {
                UUID opened = creditDecisions.open(unitOfWork, party, request, actor, correlation);
                if (failAt.equals("credit")) {
                    throw new IllegalStateException("injected after credit's insert");
                }
                return opened;
            }

            @Override
            public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                    CorrelationId correlation) {
                creditDecisions.cancel(unitOfWork, decisionRequest, party, actor, correlation);
            }

            @Override
            public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
                return creditDecisions.reasonTexts(unitOfWork, decisionRequest);
            }

            @Override
            public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
                return creditDecisions.decisionFor(unitOfWork, decisionRequest, party);
            }

            @Override
            public java.util.Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
                return creditDecisions.requestOf(unitOfWork, decision);
            }

            @Override
            public boolean lockExposure(Connection unitOfWork, UUID party) {
                return creditDecisions.lockExposure(unitOfWork, party);
            }

            @Override
            public Consumption consume(Connection unitOfWork, UUID decision, com.finapp.bnpl.BnplAgreementId agreement) {
                return creditDecisions.consume(unitOfWork, decision, agreement);
            }
        };
    }

    private static void assertNothingWritten(UUID party) throws Exception {
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application WHERE party_id = ?", party))
                .as("no application").isZero();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", party))
                .as("no credit request").isZero();
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
                results.add(future.get(2, TimeUnit.MINUTES));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
