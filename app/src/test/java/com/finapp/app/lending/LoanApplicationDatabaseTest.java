package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.line;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.consent.ConsentPurpose;
import com.finapp.lending.AgreementTemplate;
import com.finapp.lending.AllocationComponent;
import com.finapp.lending.AllocationSequence;
import com.finapp.lending.DayCountConvention;
import com.finapp.lending.EngineVersion;
import com.finapp.lending.LoanApplication;
import com.finapp.lending.LoanApplicationStore;
import com.finapp.lending.LoanApplications;
import com.finapp.lending.LoanCreditDecisions;
import com.finapp.lending.LoanPartyStanding;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTerms;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.lending.OriginationFeeTreatment;
import com.finapp.lending.OverpaymentTreatment;
import com.finapp.lending.PayoutPreference;
import com.finapp.lending.RateType;
import com.finapp.lending.RepaymentMethod;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
 * The loan application against a real database, over HTTP and through the domain (`P11-TSK-010`;
 * {@code LENDING_LIFECYCLES.md} section 4.3, {@code PHASE_11_PLAN.md} section 7.1 T-app; {@code INV-IDEM-01},
 * {@code INV-IDEM-03}, {@code INV-LIFE-01}, {@code INV-LIFE-02}, {@code INV-CNS-01}): one act opening both the
 * application and credit's decision request, every race counted from the rows, every refusal - lending's or credit's -
 * leaving neither, a crash between the two leaving neither, the withdrawal cancelling credit's request and refused once
 * credit has evaluated it, and every machine edge judged by {@code lending V004} for every role.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}, X-TSK-016): the suite brings credit's seeded
 * policies and scorecard into force and activates both products' lending terms, which the shared container's suites
 * expect to find as they left them.
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the loan application (P11-TSK-010)")
class LoanApplicationDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @LocalServerPort private int port;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanApplicationStore applicationStore;
    @Autowired private LoanPartyStanding partyStanding;
    @Autowired private LoanCreditDecisions creditDecisions;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private IdGenerator ids;
    @Autowired private Clock clock;

    private LoanApplicationClient client;
    private CreditFixtures customers;

    @BeforeEach
    void creditInForce() {
        client = new LoanApplicationClient(port);
        customers = new CreditFixtures(port);
        CreditFixtures.seedsInForce();
    }

    // ------------------------------------------------------------------ the product not offered

    @Test
    @Order(1)
    @DisplayName("no ACTIVE terms for the product is 422 lending.ProductNotOffered for both products - nothing written in"
            + " lending or in credit; the product offered once two persons activate its terms")
    void noActiveTermsRefusesTheProduct() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        for (String body : List.of(loan("10000.00", 36), line("2000.00"))) {
            HttpResponse<String> refused = client.submit(customer.token(), body, key());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("lending.ProductNotOffered");
        }
        assertNothingWritten(customer);
        termsInForce();
        assertThat(client.submit(customer.token(), loan("10000.00", 36), key()).statusCode()).isEqualTo(202);
    }

    // ------------------------------------------------------------------ one act, both records

    @Test
    @Order(2)
    @DisplayName("one act opens both: the application AWAITING_DECISION (SUBMITTED kept in its history), credit's request"
            + " SUBMITTED with the terms applied for, one correlation, both modules' events - and the owner's read")
    void oneActOpensBoth() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> submitted = client.submit(customer.token(), loan("10000.00", 36, 10), key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String id = field(submitted.body(), "applicationId");
        String request = field(submitted.body(), "decisionRequestId");
        assertThat(field(submitted.body(), "status")).isEqualTo("AWAITING_DECISION");
        assertThat(submitted.body()).contains("\"repaymentDay\":10", "\"termsVersion\":1")
                .doesNotContain("10000", "3200", "1400");

        assertThat(scalar("SELECT status FROM lending.loan_application WHERE id = ?", UUID.fromString(id)))
                .isEqualTo("AWAITING_DECISION");
        assertThat(scalar("SELECT decision_request_id::text FROM lending.loan_application WHERE id = ?",
                UUID.fromString(id))).isEqualTo(request);
        assertThat(scalar("SELECT terms_version_id::text FROM lending.loan_application WHERE id = ?", UUID.fromString(id)))
                .isEqualTo(scalar("SELECT id::text FROM lending.loan_terms_version WHERE product = 'PERSONAL_LOAN'"
                        + " AND status = 'ACTIVE'"));
        assertThat(scalar("SELECT status || '|' || product || '|' || requested_minor || '|' || term_months || '|'"
                + " || party_id FROM credit.decision_request WHERE id = ?", UUID.fromString(request)))
                .isEqualTo("SUBMITTED|PERSONAL_LOAN|1000000|36|" + customer.party());
        assertThat(scalar("SELECT correlation_id FROM credit.decision_request WHERE id = ?", UUID.fromString(request)))
                .as("one act, one correlation")
                .isEqualTo(scalar("SELECT correlation_id FROM lending.loan_application WHERE id = ?", UUID.fromString(id)));
        assertThat(scalar("SELECT string_agg(coalesce(from_status, '-') || '>' || to_status || ':' || reason, ','"
                + " ORDER BY from_status NULLS FIRST) FROM lending.loan_application_event WHERE application_id = ?",
                UUID.fromString(id)))
                .isEqualTo("->SUBMITTED:APPLICATION_SUBMITTED,SUBMITTED>AWAITING_DECISION:CREDIT_REQUEST_OPENED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'lending.LoanApplicationSubmitted'"
                + " AND aggregate_id = ?", UUID.fromString(id))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequested'"
                + " AND aggregate_id = ?", UUID.fromString(request))).isEqualTo(1);

        HttpResponse<String> read = client.read(customer.token(), id);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("AWAITING_DECISION");
        assertThat(read.body()).contains("\"payoutPreference\":\"WALLET\"", "\"repaymentMethod\":\"WALLET\"");

        HttpResponse<String> lineApplied = client.submit(customer.token(), line("2000.00"), key());
        assertThat(lineApplied.statusCode()).as("one open application per product: a line beside the loan")
                .isEqualTo(202);
        assertThat(field(lineApplied.body(), "product")).isEqualTo("CREDIT_LINE");
    }

    // ------------------------------------------------------------------ the counted races

    @Test
    @Order(3)
    @DisplayName("ten submissions under one key leave one application and one credit request; every answer is its 202 or"
            + " 409 while in progress, and a later replay is byte for byte; the key with another body is refused")
    void tenSubmissionsOneKey() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String key = key();
        List<HttpResponse<String>> answers = race(10, () -> client.submit(customer.token(), loan("10000.00", 36), key));
        List<HttpResponse<String>> accepted = answers.stream().filter(answer -> answer.statusCode() == 202).toList();
        assertThat(accepted).isNotEmpty();
        assertThat(accepted.stream().map(HttpResponse::body).distinct()).as("one response, replayed").hasSize(1);
        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isIn(202, 409));
        answers.stream().filter(answer -> answer.statusCode() == 409)
                .forEach(answer -> assertThat(answer.body()).contains("api.IdempotencyInProgress"));
        assertOne(customer);
        HttpResponse<String> later = client.submit(customer.token(), loan("10000.00", 36), key);
        assertThat(later.body()).as("a lost 202 is replayed byte for byte").isEqualTo(accepted.get(0).body());
        HttpResponse<String> different = client.submit(customer.token(), loan("12000.00", 36), key);
        assertThat(different.statusCode()).as("INV-IDEM-03: " + different.body()).isEqualTo(409);
        assertThat(different.body()).contains("api.Conflict");
        assertOne(customer);
    }

    @Test
    @Order(4)
    @DisplayName("ten keys for one party and product leave one application and one credit request; the nine others are"
            + " 409 lending.ApplicationOpen - nothing of theirs written")
    void twoKeysOnePartyAndProductOneApplication() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        List<HttpResponse<String>> answers = race(10, () -> client.submit(customer.token(), loan("10000.00", 36), key()));
        assertThat(answers.stream().filter(answer -> answer.statusCode() == 202)).hasSize(1);
        assertThat(answers.stream().filter(answer -> answer.statusCode() != 202)).hasSize(9).allSatisfy(answer -> {
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
            assertThat(answer.body()).contains("lending.ApplicationOpen");
        });
        assertOne(customer);
    }

    @Test
    @Order(5)
    @DisplayName("lending's own one-open backstop: credit's request closed while the application still awaits the"
            + " origination step - a new submission is 409 lending.ApplicationOpen and credit's new request rolls back")
    void lendingsOwnOneOpenBackstop() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String request = field(client.submit(customer.token(), loan("10000.00", 36), key()).body(), "decisionRequestId");
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.executeUpdate("UPDATE credit.decision_request SET status = 'ABANDONED', closure_reason ="
                    + " 'STANDING_LOST' WHERE id = '" + request + "'");
        }
        HttpResponse<String> refused = client.submit(customer.token(), loan("10000.00", 36), key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("lending.ApplicationOpen");
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party()))
                .as("credit's second request rolled back with the refusal").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_application WHERE party_id = ?", customer.party())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the refusals, each leaving neither

    @Test
    @Order(6)
    @DisplayName("credit's refusals roll the application back and pass through unchanged - consent absent, the applicant"
            + " not verified - and the bounds are lending.AmountOutOfRange: credit's figures and the terms' days")
    void creditRefusalLeavesNoApplication() throws Exception {
        termsInForce();
        CreditFixtures.Customer none = customers.customer(true);
        HttpResponse<String> noConsent = client.submit(none.token(), loan("10000.00", 36), key());
        assertThat(noConsent.statusCode()).as(noConsent.body()).isEqualTo(409);
        assertThat(noConsent.body()).contains("consent.ConsentRequired", "CREDIT_BUREAU_ACCESS");
        assertNothingWritten(none);

        CreditFixtures.Customer pending = customers.customer(false);
        CreditFixtures.consent(pending.party(), ConsentPurpose.CREDIT_BUREAU_ACCESS);
        CreditFixtures.consent(pending.party(), ConsentPurpose.FINANCIAL_DATA_ACCESS);
        HttpResponse<String> unverified = client.submit(pending.token(), loan("10000.00", 36), key());
        assertThat(unverified.statusCode()).as(unverified.body()).isEqualTo(409);
        assertThat(unverified.body()).contains("credit.ApplicantNotEligible");
        assertNothingWritten(pending);

        CreditFixtures.Customer customer = customers.consentingCustomer();
        for (String refused : List.of(loan("499.99", 36), loan("25000.01", 36), loan("10000.00", 5), loan("10000.00", 61),
                loan("10000.00", 36, 11), line("249.99"), line("5000.01"),
                "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"10000.00\",\"currency\":\"USD\",\"termMonths\":36,"
                        + "\"repaymentDay\":5,\"repaymentMethod\":\"WALLET\"}",
                "{\"product\":\"CREDIT_LINE\",\"amount\":\"1000.00\",\"currency\":\"EUR\",\"termMonths\":12,"
                        + "\"repaymentDay\":5,\"repaymentMethod\":\"WALLET\"}")) {
            HttpResponse<String> answer = client.submit(customer.token(), refused, key());
            assertThat(answer.statusCode()).as(refused + " " + answer.body()).isEqualTo(422);
            assertThat(answer.body()).as(refused).contains("lending.AmountOutOfRange");
        }
        HttpResponse<String> external = client.submit(customer.token(), "{\"product\":\"CREDIT_LINE\",\"amount\":"
                + "\"1000.00\",\"currency\":\"EUR\",\"repaymentDay\":5,\"repaymentMethod\":\"WALLET\","
                + "\"payoutPreference\":\"EXTERNAL_ACCOUNT\"}", key());
        assertThat(external.statusCode()).as("a line draws to the wallet only: " + external.body()).isEqualTo(422);
        assertThat(external.body()).contains("api.ValidationFailed");
        assertNothingWritten(customer);
    }

    @Test
    @Order(7)
    @DisplayName("a crash between credit's request and the commit leaves neither - after the open, and after every write;"
            + " the same act committed leaves both")
    void aCrashBetweenLeavesNeither() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        Actor actor = new Actor(customer.party().toString(), ActorType.CUSTOMER);
        LoanCreditDecisions crashAfterOpen = new LoanCreditDecisions() {
            @Override
            public UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor who, CorrelationId correlation) {
                creditDecisions.open(unitOfWork, party, terms, who, correlation);
                throw new IllegalStateException("the instance died after credit's request was written");
            }

            @Override
            public void cancel(Connection unitOfWork, UUID request, UUID party, Actor who, CorrelationId correlation) {
                creditDecisions.cancel(unitOfWork, request, party, who, correlation);
            }

            @Override
            public List<String> reasonTexts(Connection unitOfWork, UUID request) {
                return creditDecisions.reasonTexts(unitOfWork, request);
            }

            @Override
            public CreditOutcome decisionFor(Connection unitOfWork, UUID request, UUID party) {
                return creditDecisions.decisionFor(unitOfWork, request, party);
            }

            @Override
            public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
                return creditDecisions.requestOf(unitOfWork, decision);
            }

            @Override
            public boolean lockExposure(Connection unitOfWork, UUID party) {
                return creditDecisions.lockExposure(unitOfWork, party);
            }

            @Override
            public Consumption consume(Connection unitOfWork, UUID decision,
                    com.finapp.lending.LoanAgreementId agreement) {
                return creditDecisions.consume(unitOfWork, decision, agreement);
            }
        };
        assertThatThrownBy(() -> inRawTransaction(uow -> applications(crashAfterOpen)
                        .submit(uow, customer.party(), submission(), actor, CorrelationId.generate(ids))))
                .hasMessageContaining("died after credit");
        assertNothingWritten(customer);
        assertThatThrownBy(() -> inRawTransaction(uow -> {
                    applications(creditDecisions).submit(uow, customer.party(), submission(), actor,
                            CorrelationId.generate(ids));
                    throw new IllegalStateException("the instance died before the commit");
                }))
                .hasMessageContaining("before the commit");
        assertNothingWritten(customer);
        LoanApplication committed = inRawTransaction(uow -> applications(creditDecisions)
                .submit(uow, customer.party(), submission(), actor, CorrelationId.generate(ids)));
        assertThat(committed.status().name()).isEqualTo("AWAITING_DECISION");
        assertOne(customer);
    }

    // ------------------------------------------------------------------ the withdrawal

    @Test
    @Order(8)
    @DisplayName("a withdrawal cancels credit's request in the same commit: WITHDRAWN, CANCELLED, the closed events, the key"
            + " replayed, another key 409 lending.ApplicationNotWithdrawable - and a new application accepted")
    void withdrawalCancelsTheCreditRequest() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> submitted = client.submit(customer.token(), loan("10000.00", 36), key());
        String id = field(submitted.body(), "applicationId");
        String request = field(submitted.body(), "decisionRequestId");
        String withdrawalKey = key();
        HttpResponse<String> withdrawn = client.withdraw(customer.token(), id, withdrawalKey);
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.body()).contains("\"closedAt\":\"");
        assertThat(client.withdraw(customer.token(), id, withdrawalKey).body()).as("the same key replays")
                .isEqualTo(withdrawn.body());
        HttpResponse<String> again = client.withdraw(customer.token(), id, key());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("lending.ApplicationNotWithdrawable");
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", UUID.fromString(request)))
                .isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'lending.LoanApplicationClosed'"
                + " AND aggregate_id = ?", UUID.fromString(id))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequestClosed'"
                + " AND aggregate_id = ?", UUID.fromString(request))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_application_event WHERE application_id = ? AND to_status ="
                + " 'WITHDRAWN' AND reason = 'CUSTOMER_WITHDREW'", UUID.fromString(id))).isEqualTo(1);
        assertThat(client.submit(customer.token(), loan("10000.00", 36), key()).statusCode())
                .as("a withdrawn application frees the product").isEqualTo(202);
    }

    @Test
    @Order(9)
    @DisplayName("a withdrawal after credit's evaluation is 409 lending.ApplicationNotWithdrawable - the application still"
            + " awaiting its decision, credit's request still EVALUATED")
    void withdrawalAfterEvaluationRefused() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> submitted = client.submit(customer.token(), loan("10000.00", 36), key());
        String id = field(submitted.body(), "applicationId");
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.stepUntil(request, "EVALUATED")).isEqualTo("EVALUATED");
        HttpResponse<String> refused = client.withdraw(customer.token(), id, key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("lending.ApplicationNotWithdrawable", "decision will arrive");
        assertThat(scalar("SELECT status FROM lending.loan_application WHERE id = ?", UUID.fromString(id)))
                .isEqualTo("AWAITING_DECISION");
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", request)).isEqualTo("EVALUATED");
    }

    @Test
    @Order(10)
    @DisplayName("a withdrawal racing credit's evaluation leaves exactly one of WITHDRAWN-and-CANCELLED and"
            + " awaiting-and-EVALUATED - credit's request row lock the arbiter")
    void withdrawalRacingEvaluationLeavesExactlyOne() throws Exception {
        termsInForce();
        for (int round = 0; round < 3; round++) {
            CreditFixtures.Customer customer = customers.consentingCustomer();
            HttpResponse<String> submitted = client.submit(customer.token(), loan("10000.00", 36), key());
            String id = field(submitted.body(), "applicationId");
            UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
            assertThat(CreditFixtures.stepUntil(request, "READY")).isEqualTo("READY");
            String key = key();
            race(2, () -> {
                if (Thread.currentThread().getName().endsWith("-1")) {
                    return client.withdraw(customer.token(), id, key).statusCode();
                }
                CreditFixtures.stepOnce(request);
                return 0;
            });
            String application = scalar("SELECT status FROM lending.loan_application WHERE id = ?", UUID.fromString(id));
            String credit = scalar("SELECT status FROM credit.decision_request WHERE id = ?", request);
            assertThat(application + "|" + credit).as("round " + round)
                    .isIn("WITHDRAWN|CANCELLED", "AWAITING_DECISION|EVALUATED");
        }
    }

    @Test
    @Order(11)
    @DisplayName("another party's application is 404 lending.NotFound to read and to withdraw - the answer an unknown or"
            + " malformed id gets - and the stranger moves nothing")
    void anotherPartysApplicationIsNotFound() throws Exception {
        termsInForce();
        CreditFixtures.Customer owner = customers.consentingCustomer();
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        String id = field(client.submit(owner.token(), loan("10000.00", 36), key()).body(), "applicationId");
        for (String path : List.of(id, ids.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> read = client.read(stranger.token(), path);
            assertThat(read.statusCode()).as(path).isEqualTo(404);
            assertThat(read.body()).contains("lending.NotFound").doesNotContain(owner.party().toString());
            HttpResponse<String> withdraw = client.withdraw(stranger.token(), path, key());
            assertThat(withdraw.statusCode()).as(path).isEqualTo(404);
            assertThat(withdraw.body()).contains("lending.NotFound");
        }
        assertThat(field(client.read(owner.token(), id).body(), "status")).isEqualTo("AWAITING_DECISION");
    }

    // ------------------------------------------------------------------ the machine, for every writer

    @Test
    @Order(12)
    @DisplayName("raw SQL cannot skip an edge for any role: born SUBMITTED on an ACTIVE version and an offered day, never"
            + " committed SUBMITTED, only the machine's edges, terminal final, choices frozen, never deleted or truncated")
    void rawSqlCannotSkipAnEdgeForAnyRole() throws Exception {
        termsInForce();
        String version = scalar("SELECT id::text FROM lending.loan_terms_version WHERE product = 'PERSONAL_LOAN'"
                + " AND status = 'ACTIVE'");
        // Birth: SUBMITTED only, on the ACTIVE version, on a day it offers.
        for (Connection role : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (role) {
                assertRefused(role, insert(UUID.randomUUID(), version, "AWAITING_DECISION", 5), "P0001");
                assertRefused(role, insert(UUID.randomUUID(), version, "SUBMITTED", 11), "P0001");
                assertRefused(role, insert(UUID.randomUUID(), UUID.randomUUID().toString(), "SUBMITTED", 5), "P0001");
                assertRefused(role, insert(UUID.randomUUID(), version, "SUBMITTED", 5)
                        .replace("'PERSONAL_LOAN'", "'CREDIT_LINE'"), "P0001");
            }
        }
        // Never committed SUBMITTED - the deferred rank, at COMMIT, for every role.
        for (Connection role : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (role) {
                role.setAutoCommit(false);
                try (Statement statement = role.createStatement()) {
                    statement.executeUpdate(insert(UUID.randomUUID(), version, "SUBMITTED", 5));
                }
                assertThatThrownBy(role::commit).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
                role.rollback();
            }
        }
        // The edges: SUBMITTED is never skipped; AWAITING_DECISION's four - OFFERED only beside its offer
        // (P11-TSK-011, whose LoanOriginationDatabaseTest holds that edge and the offer's machine); terminal final.
        // A status-unchanged update is P11-TSK-011's permit-only move, admitted only if it moves the permit alone.
        UUID awaiting = awaiting(version);
        for (String target : List.of("SUBMITTED", "AWAITING_DECISION", "OFFERED")) {
            refusedUpdate(awaiting, "status = '" + target + "'", "P0001");
        }
        acceptedUpdate(awaiting, "next_step_at = next_step_at + interval '1 minute'");
        for (String terminal : List.of("DECLINED", "CLOSED_UNDECIDED", "WITHDRAWN")) {
            UUID application = awaiting(version);
            acceptedUpdate(application, "status = '" + terminal + "'");
            assertThat(scalar("SELECT closed_at IS NOT NULL FROM lending.loan_application WHERE id = ?", application))
                    .as("the database stamps the closure").isEqualTo("t");
            for (String target : List.of("SUBMITTED", "AWAITING_DECISION", "OFFERED", "DECLINED", "CLOSED_UNDECIDED",
                    "WITHDRAWN")) {
                refusedUpdate(application, "status = '" + target + "'", "P0001");
            }
        }
        // The choices and identity: no privilege for the application role, frozen by the trigger for the owner.
        for (String change : List.of("repayment_day = 10", "payout_preference = 'EXTERNAL_ACCOUNT'",
                "decision_request_id = gen_random_uuid()", "party_id = gen_random_uuid()", "terms_version_id = '"
                        + version + "'", "submitted_at = now()", "closed_at = now()", "product = 'CREDIT_LINE'")) {
            try (Connection app = DatabaseRoles.application()) {
                assertRefused(app, "UPDATE lending.loan_application SET " + change + " WHERE id = '" + awaiting + "'",
                        "42501");
            }
            try (Connection owner = DatabaseRoles.migrator()) {
                assertRefused(owner, "UPDATE lending.loan_application SET " + change + " WHERE id = '" + awaiting + "'",
                        "P0001");
            }
        }
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            // A legal history row, so the refusals below meet a row.
            statement.executeUpdate("INSERT INTO lending.loan_application_event (id, application_id, from_status,"
                    + " to_status, reason, actor, actor_type, correlation_id) VALUES (gen_random_uuid(), '" + awaiting
                    + "', NULL, 'SUBMITTED', 'APPLICATION_SUBMITTED', 'x', 'CUSTOMER', 'c')");
            assertRefused(app, "DELETE FROM lending.loan_application WHERE id = '" + awaiting + "'", "42501");
            assertRefused(app, "UPDATE lending.loan_application_event SET reason = 'X_Y_Z' WHERE application_id = '"
                    + awaiting + "'", "42501");
            assertRefused(app, "INSERT INTO lending.loan_application_event (id, application_id, from_status, to_status,"
                    + " reason, actor, actor_type, correlation_id) VALUES (gen_random_uuid(), '" + awaiting
                    + "', 'SUBMITTED', 'OFFERED', 'SKIPPED', 'x', 'CUSTOMER', 'c')", "23514");
            assertRefused(app, "INSERT INTO lending.loan_application_event (id, application_id, from_status, to_status,"
                    + " reason, actor, actor_type, correlation_id) VALUES (gen_random_uuid(), '" + awaiting
                    + "', 'AWAITING_DECISION', 'WITHDRAWN', 'my card is 4111 1111', 'x', 'CUSTOMER', 'c')", "23514");
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            assertRefused(owner, "DELETE FROM lending.loan_application WHERE id = '" + awaiting + "'", "P0001");
            assertRefused(owner, "DELETE FROM lending.loan_application_event WHERE application_id = '" + awaiting + "'",
                    "P0001");
            assertRefused(owner, "TRUNCATE lending.loan_application_event", "P0001");
            assertRefused(owner, "TRUNCATE lending.loan_application CASCADE", "P0001");
        }
        // At most one OPEN application per party and product - the backstop, for every writer.
        String party = scalar("SELECT party_id::text FROM lending.loan_application WHERE id = ?", awaiting);
        try (Connection owner = DatabaseRoles.migrator()) {
            assertRefused(owner, insert(UUID.randomUUID(), version, "SUBMITTED", 5)
                    .replaceFirst("'[0-9a-f-]{36}', 'PERSONAL_LOAN'", "'" + party + "', 'PERSONAL_LOAN'"), "23505");
        }
    }

    // ------------------------------------------------------------------ the keyed withdrawal (P11-DOC-001)

    @Test
    @Order(13)
    @DisplayName("ten withdrawals under one key are one (INV-IDEM-01, the gate's idempotency clause): every answer the"
            + " one receipt or 409 api.IdempotencyInProgress - one WITHDRAWN event, one closed event each side, credit's"
            + " request CANCELLED once")
    void tenWithdrawalsUnderOneKeyAreOne() throws Exception {
        termsInForce();
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> submitted = client.submit(customer.token(), loan("9000.00", 36), key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String id = field(submitted.body(), "applicationId");
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        String withdrawalKey = key();
        HttpResponse<String> withdrawn = OneKeyRace.tenUnderOneKey(200, () -> client.withdraw(customer.token(), id,
                withdrawalKey));
        assertThat(field(withdrawn.body(), "status")).isEqualTo("WITHDRAWN");
        assertThat(count("SELECT count(*) FROM lending.loan_application_event WHERE application_id = ? AND to_status ="
                + " 'WITHDRAWN'", UUID.fromString(id))).as("withdrawn once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'lending.LoanApplicationClosed'"
                + " AND aggregate_id = ?", UUID.fromString(id))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequestClosed'"
                + " AND aggregate_id = ?", request)).isEqualTo(1);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", request)).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ plumbing

    /** Both products' terms in force, activated by two persons, unless already. */
    private void termsInForce() {
        for (LoanProduct product : LoanProduct.values()) {
            lendingTransactions.inTransaction(uow -> {
                if (termsStore.active(uow, product).isEmpty()) {
                    var proposed = termsAdministration.propose(uow, terms(product), "the suite's terms",
                            new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), CorrelationId.generate(ids));
                    termsAdministration.approve(uow, proposed.id(), new Actor(UUID.randomUUID().toString(),
                            ActorType.EMPLOYEE), "reviewed", CorrelationId.generate(ids));
                }
                return null;
            });
        }
    }

    static LoanTerms terms(LoanProduct product) {
        boolean loan = product == LoanProduct.PERSONAL_LOAN;
        return LoanTerms.builder()
                .product(product)
                .nominalAnnualRate(new BigDecimal("0.0899"))
                .rateType(RateType.FIXED)
                .originationFee(LoanTerms.OriginationFee.none(EUR))
                .originationFeeTreatment(OriginationFeeTreatment.DEDUCTED)
                .lateFee(Money.ofMinorUnits(15_00, EUR))
                .lateFeeDay(5)
                .lateFeeCap(Money.ofMinorUnits(60_00, EUR))
                .graceDays(3)
                .delinquencyBounds(List.of(1, 30, 60, 90))
                .defaultThresholdDays(90)
                .allocationSequence(AllocationSequence.OLDEST_DUE_FIRST)
                .allocationOrder(List.of(AllocationComponent.FEES, AllocationComponent.INTEREST,
                        AllocationComponent.PRINCIPAL))
                .overpaymentTreatment(loan ? OverpaymentTreatment.HOLD_AS_CREDIT : OverpaymentTreatment.PAY_DOWN_PRINCIPAL)
                // Off by default (P11-TSK-019): the suites bill against a wallet holding the disbursed principal and
                // expect the bill to stay due; LoanAccountDatabaseTest's collection case activates terms with it on.
                .autoCollection(false)
                .partialCollection(true)
                .instalmentRounding(RoundingMode.UP)
                .interestRounding(RoundingMode.HALF_EVEN)
                .dayCount(DayCountConvention.ACT_365F)
                .servicingZone("UTC")
                .allowedDays(List.of(1, 5, 10, 15, 20, 25, 28))
                .offerValidityDays(14)
                .payoffHorizonDays(30)
                .accrualEngine(EngineVersion.ACCRUAL_ENGINE_V1)
                .allocationEngine(EngineVersion.ALLOCATION_ENGINE_V1)
                .instalment(loan ? Optional.of(new LoanTerms.InstalmentTerms(15, 24, EngineVersion.SCHEDULE_ENGINE_V1))
                        : Optional.empty())
                .revolving(loan ? Optional.empty() : Optional.of(new LoanTerms.RevolvingTerms(
                        Money.ofMinorUnits(25_00, EUR), 300, 25, EngineVersion.STATEMENT_ENGINE_V1)))
                .agreementTemplate(loan ? AgreementTemplate.PERSONAL_LOAN_AGREEMENT_V1
                        : AgreementTemplate.CREDIT_LINE_AGREEMENT_V1)
                .build();
    }

    private LoanApplications applications(LoanCreditDecisions credit) {
        return new LoanApplications(applicationStore, termsAdministration, partyStanding, credit, outboxWriter, ids, clock);
    }

    private static LoanApplications.Submission submission() {
        return new LoanApplications.Submission(LoanProduct.PERSONAL_LOAN, Money.ofMinorUnits(10_000_00, EUR),
                Optional.of(36), Optional.empty(), Optional.empty(), 5, RepaymentMethod.WALLET, PayoutPreference.WALLET);
    }

    /** {@code work} in one transaction on a raw application-role connection - committed, or rolled back on a throw. */
    private static <R> R inRawTransaction(java.util.function.Function<Connection, R> work) throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String insert(UUID id, String version, String status, int day) {
        return "INSERT INTO lending.loan_application (id, party_id, product, status, decision_request_id, terms_version_id,"
                + " repayment_day, repayment_method, payout_preference, submitted_by, submitted_by_type, correlation_id)"
                + " VALUES ('" + id + "', '" + UUID.randomUUID() + "', 'PERSONAL_LOAN', '" + status + "', '"
                + UUID.randomUUID() + "', '" + version + "', " + day + ", 'WALLET', 'WALLET', 'x', 'CUSTOMER', 'c')";
    }

    /** A committed AWAITING_DECISION application for a fresh party, by raw SQL as the application role. */
    private static UUID awaiting(String version) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (Statement statement = app.createStatement()) {
                statement.executeUpdate(insert(id, version, "SUBMITTED", 5));
                statement.executeUpdate("UPDATE lending.loan_application SET status = 'AWAITING_DECISION' WHERE id = '"
                        + id + "'");
            }
            app.commit();
        }
        return id;
    }

    private static void acceptedUpdate(UUID id, String assignment) throws SQLException {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThat(statement.executeUpdate("UPDATE lending.loan_application SET " + assignment + " WHERE id = '" + id
                    + "'")).as(assignment).isEqualTo(1);
        }
    }

    private static void refusedUpdate(UUID id, String assignment, String sqlState) throws SQLException {
        for (Connection role : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (role) {
                assertRefused(role, "UPDATE lending.loan_application SET " + assignment + " WHERE id = '" + id + "'",
                        sqlState);
            }
        }
    }

    private static void assertRefused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    private static void assertNothingWritten(CreditFixtures.Customer customer) throws Exception {
        assertThat(count("SELECT count(*) FROM lending.loan_application WHERE party_id = ?", customer.party())).isZero();
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isZero();
    }

    private static void assertOne(CreditFixtures.Customer customer) throws Exception {
        assertThat(count("SELECT count(*) FROM lending.loan_application WHERE party_id = ?", customer.party())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_application a JOIN credit.decision_request r"
                + " ON r.id = a.decision_request_id WHERE a.party_id = ? AND r.party_id = a.party_id", customer.party()))
                .as("the application names its own request").isEqualTo(1);
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
