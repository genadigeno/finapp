package com.finapp.app.credit;

import static com.finapp.app.credit.CreditTestClient.count;
import static com.finapp.app.credit.CreditTestClient.field;
import static com.finapp.app.credit.CreditTestClient.key;
import static com.finapp.app.credit.CreditTestClient.line;
import static com.finapp.app.credit.CreditTestClient.loan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.consent.ConsentPurpose;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditPolicyStatus;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.CreditPolicyVersionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The credit decision request against a real database, over HTTP (`P10-TSK-014`; CREDIT_DECISIONING_LIFECYCLES.md
 * section 3.1, {@code INV-CRD-03}, {@code INV-CRD-06}, {@code INV-CRD-12}, {@code INV-IDEM-01}, {@code INV-LIFE-01}):
 * every race counted from the rows, every refusal writing nothing, a customer never learning of another's request, and
 * every machine edge judged by the trigger for a raw-SQL writer.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}, X-TSK-016): the suite brings each product's seeded
 * policy into force - a person activating the migration's proposal - which the policy suites expect to find still
 * proposed in the shared container.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the credit decision request (P10-TSK-014)")
class DecisionRequestDatabaseTest {

    private static final String SCORECARD_SEED = "0190a1b2-5c0e-7000-8000-00000000c001";

    @LocalServerPort private int port;
    @Autowired private CreditPolicyAdministration administration;
    @Autowired private CreditPolicyStore policies;
    @Autowired private TransactionRunner creditTransactionRunner;

    private CreditTestClient client;

    @BeforeEach
    void policiesInForce() {
        client = new CreditTestClient(port);
        for (CreditProduct product : CreditProduct.values()) {
            CreditPolicyVersionId seed = CreditPolicyVersionId.of(UUID.fromString(
                    product == CreditProduct.PERSONAL_LOAN ? "0190a1b2-5c0e-7000-8000-00000000d001"
                            : "0190a1b2-5c0e-7000-8000-00000000d002"));
            creditTransactionRunner.inTransaction(uow -> {
                if (policies.policy(uow, seed).orElseThrow().row().status() == CreditPolicyStatus.PROPOSED) {
                    administration.approve(uow, seed, new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                            "v1 reviewed", CorrelationId.generate(CreditTestClient.IDS));
                }
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ the counted races

    @Test
    @DisplayName("ten submissions under one key leave one request; every answer is that request's 202, or 409 while in"
            + " progress - one history row, one event")
    void tenSubmissionsUnderOneKeyLeaveOneRequest() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        String key = key();
        List<HttpResponse<String>> answers = race(10, () -> client.submit(customer, loan("10000.00", 36), key));
        List<HttpResponse<String>> accepted = answers.stream().filter(answer -> answer.statusCode() == 202).toList();
        assertThat(accepted).isNotEmpty();
        assertThat(accepted.stream().map(HttpResponse::body).distinct()).as("one response, replayed").hasSize(1);
        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isIn(202, 409));
        answers.stream().filter(answer -> answer.statusCode() == 409)
                .forEach(answer -> assertThat(answer.body()).contains("api.IdempotencyInProgress"));
        String id = field(accepted.get(0).body(), "requestId");
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.decision_request_event WHERE decision_request_id = ?",
                UUID.fromString(id))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequested'"
                + " AND aggregate_id = ?", UUID.fromString(id))).isEqualTo(1);
        HttpResponse<String> later = client.submit(customer, loan("10000.00", 36), key);
        assertThat(later.statusCode()).isEqualTo(202);
        assertThat(later.body()).as("a lost 202 is replayed byte for byte").isEqualTo(accepted.get(0).body());
    }

    @Test
    @DisplayName("ten keys for one party and product leave one open request; the nine others are 409"
            + " credit.DecisionRequestOpen naming it")
    void twoKeysForOnePartyAndProductLeaveOneOpen() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        List<HttpResponse<String>> answers = race(10, () -> client.submit(customer, loan("10000.00", 36), key()));
        List<HttpResponse<String>> accepted = answers.stream().filter(answer -> answer.statusCode() == 202).toList();
        assertThat(accepted).hasSize(1);
        String id = field(accepted.get(0).body(), "requestId");
        List<HttpResponse<String>> refused = answers.stream().filter(answer -> answer.statusCode() != 202).toList();
        assertThat(refused).hasSize(9).allSatisfy(answer -> {
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
            assertThat(answer.body()).contains("credit.DecisionRequestOpen").contains(id);
        });
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isEqualTo(1);
    }

    @Test
    @DisplayName("one party may hold an open request for each product at once")
    void twoProductsForOnePartyMayBothBeOpen() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        HttpResponse<String> loan = client.submit(customer, loan("10000.00", 36), key());
        HttpResponse<String> line = client.submit(customer, line("2000.00"), key());
        assertThat(loan.statusCode()).as(loan.body()).isEqualTo(202);
        assertThat(line.statusCode()).as(line.body()).isEqualTo(202);
        assertThat(line.body()).contains("\"product\":\"CREDIT_LINE\"").doesNotContain("termMonths\":3");
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ? AND status = 'SUBMITTED'",
                customer.party())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    @Test
    @DisplayName("consent absent for any source kind the policy reads is refused before any provider is asked - the"
            + " purpose named, nothing written, no data request")
    void consentAbsentIsRefusedBeforeAnyProviderIsAsked() throws Exception {
        CreditTestClient.Customer none = client.customer(true);
        HttpResponse<String> refused = client.submit(none, loan("10000.00", 36), key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("consent.ConsentRequired").contains("CREDIT_BUREAU_ACCESS");
        CreditTestClient.Customer bureauOnly = client.customer(true);
        CreditTestClient.consent(bureauOnly.party(), ConsentPurpose.CREDIT_BUREAU_ACCESS);
        HttpResponse<String> partial = client.submit(bureauOnly, loan("10000.00", 36), key());
        assertThat(partial.statusCode()).isEqualTo(409);
        assertThat(partial.body()).contains("consent.ConsentRequired").contains("FINANCIAL_DATA_ACCESS");
        CreditTestClient.Customer withdrawn = client.consentingCustomer();
        CreditTestClient.withdraw(withdrawn.party(), ConsentPurpose.CREDIT_BUREAU_ACCESS);
        assertThat(client.submit(withdrawn, loan("10000.00", 36), key()).body()).contains("consent.ConsentRequired");
        for (CreditTestClient.Customer customer : List.of(none, bureauOnly, withdrawn)) {
            assertNothingWritten(customer);
        }
    }

    @Test
    @DisplayName("a party KYC has not approved is 409 credit.ApplicantNotEligible, nothing written")
    void anUnverifiedPartyIsRefused() throws Exception {
        CreditTestClient.Customer pending = client.customer(false);
        CreditTestClient.consent(pending.party(), ConsentPurpose.CREDIT_BUREAU_ACCESS);
        CreditTestClient.consent(pending.party(), ConsentPurpose.FINANCIAL_DATA_ACCESS);
        HttpResponse<String> refused = client.submit(pending, loan("10000.00", 36), key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("credit.ApplicantNotEligible");
        assertNothingWritten(pending);
        CreditTestClient.Customer suspended = client.consentingCustomer();
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.executeUpdate("UPDATE party.customer SET status = 'SUSPENDED', status_changed_at = GREATEST(now(),"
                    + " status_changed_at) WHERE party_id = '" + suspended.party() + "'");
        }
        assertThat(client.submit(suspended, loan("10000.00", 36), key()).body()).contains("credit.ApplicantNotEligible");
        assertNothingWritten(suspended);
    }

    @Test
    @DisplayName("the bounds, for both products: the minimum and maximum accepted, one minor unit beyond refused, the"
            + " term within its months, a line with a term and another currency refused")
    void amountBoundsAtMinMaxAndOneMinorUnitBeyond() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        for (String accepted : List.of(loan("500.00", 6), loan("25000.00", 60), line("250.00"), line("5000.00"))) {
            HttpResponse<String> submitted = client.submit(customer, accepted, key());
            assertThat(submitted.statusCode()).as(accepted + " " + submitted.body()).isEqualTo(202);
            assertThat(client.cancel(customer, field(submitted.body(), "requestId"), key()).statusCode()).isEqualTo(200);
        }
        for (String refused : List.of(loan("499.99", 36), loan("25000.01", 36), loan("10000.00", 5),
                loan("10000.00", 61), line("249.99"), line("5000.01"),
                "{\"product\":\"CREDIT_LINE\",\"amount\":\"1000.00\",\"currency\":\"EUR\",\"termMonths\":12}",
                "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"1000.00\",\"currency\":\"EUR\"}",
                "{\"product\":\"CREDIT_LINE\",\"amount\":\"1000.00\",\"currency\":\"USD\"}")) {
            HttpResponse<String> answer = client.submit(customer, refused, key());
            assertThat(answer.statusCode()).as(refused + " " + answer.body()).isEqualTo(422);
            assertThat(answer.body()).as(refused).contains("credit.AmountOutOfRange");
        }
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ? AND status <> 'CANCELLED'",
                customer.party())).isZero();
    }

    // ------------------------------------------------------------------ the cancellation and the read

    @Test
    @DisplayName("a cancelled request frees the slot: CANCELLED, audited once, the closed event, the key replayed - and a"
            + " new request accepted")
    void aClosedRequestFreesTheSlot() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        String first = field(client.submit(customer, loan("10000.00", 36), key()).body(), "requestId");
        String cancelKey = key();
        HttpResponse<String> cancelled = client.cancel(customer, first, cancelKey);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(field(cancelled.body(), "status")).isEqualTo("CANCELLED");
        assertThat(client.cancel(customer, first, cancelKey).body()).as("the same key replays").isEqualTo(cancelled.body());
        HttpResponse<String> again = client.cancel(customer, first, key());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("credit.RequestNotCancellable");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionRequestCancelled'"
                + " AND target_id = ?", first)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequestClosed'"
                + " AND aggregate_id = ?", UUID.fromString(first))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.decision_request_event WHERE decision_request_id = ?",
                UUID.fromString(first))).as("the birth and the cancellation").isEqualTo(2);
        HttpResponse<String> second = client.submit(customer, loan("10000.00", 36), key());
        assertThat(second.statusCode()).as(second.body()).isEqualTo(202);
        assertThat(field(second.body(), "requestId")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("an abandoned request is past cancellation; the read shows it with its reason")
    void anAbandonedRequestIsNotCancellable() throws Exception {
        CreditTestClient.Customer customer = client.consentingCustomer();
        String id = field(client.submit(customer, loan("10000.00", 36), key()).body(), "requestId");
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.executeUpdate("UPDATE credit.decision_request SET status = 'ABANDONED', closure_reason ="
                    + " 'STANDING_LOST' WHERE id = '" + id + "'");
        }
        HttpResponse<String> refused = client.cancel(customer, id, key());
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("credit.RequestNotCancellable");
        HttpResponse<String> read = client.read(customer, id);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("ABANDONED");
        assertThat(field(read.body(), "closureReason")).isEqualTo("STANDING_LOST");
        assertThat(read.body()).doesNotContain("3200").doesNotContain("1400").as("never a declared figure");
    }

    @Test
    @DisplayName("another party's request is 404 to read and to cancel - the same answer as an unknown or malformed id")
    void anotherPartysRequestIsNotFound() throws Exception {
        CreditTestClient.Customer owner = client.consentingCustomer();
        CreditTestClient.Customer stranger = client.consentingCustomer();
        String id = field(client.submit(owner, loan("10000.00", 36), key()).body(), "requestId");
        assertThat(client.read(owner, id).statusCode()).isEqualTo(200);
        for (String path : List.of(id, CreditTestClient.IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> read = client.read(stranger, path);
            assertThat(read.statusCode()).as(path).isEqualTo(404);
            assertThat(read.body()).contains("credit.NotFound").doesNotContain(owner.party().toString());
            HttpResponse<String> cancel = client.cancel(stranger, path, key());
            assertThat(cancel.statusCode()).as(path).isEqualTo(404);
        }
        assertThat(field(client.read(owner, id).body(), "status")).as("the stranger moved nothing").isEqualTo("SUBMITTED");
    }

    // ------------------------------------------------------------------ the machine, for every writer

    @Test
    @DisplayName("every edge of the lifecycle by raw SQL: the legal ones taken, every invalid one refused - collection"
            + " never skipped, no evaluation without its row, terminal states final, the clock conditionals, the pins once")
    void everyMachineEdgeByRawSql() throws Exception {
        String pins = ", pinned_policy_version_id = '0190a1b2-5c0e-7000-8000-00000000d001', pinned_model_version_id = '"
                + SCORECARD_SEED + "', pinned_engine_version = 1";
        // Collection is never skipped, and needs its pins.
        for (String target : List.of("READY", "EVALUATED", "IN_REVIEW", "DECIDED")) {
            refused(fresh(), "status = '" + target + "'", "P0001");
        }
        refused(fresh(), "status = 'COLLECTING'", "23514");
        String collecting = fresh();
        accepted(collecting, "status = 'COLLECTING'" + pins);
        for (String target : List.of("EVALUATED", "IN_REVIEW", "DECIDED", "SUBMITTED")) {
            refused(collecting, "status = '" + target + "'", "P0001");
        }
        refused(collecting, "pinned_engine_version = 2", "P0001");
        accepted(collecting, "status = 'READY'");
        refused(collecting, "status = 'EVALUATED'", "P0001"); // no evaluation, no EVALUATED
        refused(collecting, "status = 'IN_REVIEW'", "P0001");
        refused(collecting, "status = 'DECIDED'", "P0001");
        accepted(collecting, "status = 'COLLECTING'"); // the one backward edge
        accepted(collecting, "status = 'READY'");
        // The clock: an unexpired request does not expire.
        refused(collecting, "status = 'EXPIRED'", "P0001");
        // Closures: ABANDONED always says why, and only with a reason of the two.
        refused(collecting, "status = 'ABANDONED'", "23514");
        refused(collecting, "status = 'ABANDONED', closure_reason = 'BORED'", "23514");
        refused(collecting, "closure_reason = 'STANDING_LOST'", "23514");
        accepted(collecting, "status = 'CANCELLED'");
        for (String target : List.of("SUBMITTED", "COLLECTING", "READY", "ABANDONED", "EXPIRED", "DECIDED")) {
            refused(collecting, "status = '" + target + "'", "P0001");
        }
        refused(collecting, "next_step_at = now()", "P0001"); // a terminal request never changes
        String abandoned = fresh();
        accepted(abandoned, "status = 'ABANDONED', closure_reason = 'CONSENT_WITHDRAWN'");
        refused(abandoned, "status = 'CANCELLED', closure_reason = NULL", "P0001");
        // The terms and the windows are frozen from birth; a permit may move.
        String open = fresh();
        accepted(open, "next_step_at = now() + interval '1 minute'");
        for (String change : List.of("requested_minor = 1", "product = 'CREDIT_LINE', term_months = NULL",
                "expires_at = now() + interval '1 year'", "submitted_at = now()", "party_id = gen_random_uuid()",
                "declared_income_minor = 1", "correlation_id = 'x'", "request_validity = interval '1 year'")) {
            refused(open, change, "P0001");
        }
        // Birth: SUBMITTED, open and unpinned, the windows the database's.
        try (Connection app = DatabaseRoles.application()) {
            assertRefused(app, "INSERT INTO credit.decision_request (id, party_id, profile_id, product, currency,"
                    + " requested_minor, term_months, status, request_validity, submitted_at, expires_at, next_step_at,"
                    + " correlation_id) SELECT gen_random_uuid(), party_id, profile_id, product, currency, requested_minor,"
                    + " term_months, 'READY', request_validity, now(), now(), now(), 'x' FROM credit.decision_request"
                    + " WHERE id = '" + open + "'", "P0001");
            assertRefused(app, "DELETE FROM credit.decision_request WHERE id = '" + open + "'", "42501");
            assertRefused(app, "UPDATE credit.decision_request_event SET reason = 'x' WHERE decision_request_id = '"
                    + open + "'", "42501");
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            assertRefused(owner, "DELETE FROM credit.decision_request WHERE id = '" + open + "'", "P0001");
            assertRefused(owner, "UPDATE credit.decision_request_event SET reason = 'x' WHERE decision_request_id = '"
                    + open + "'", "P0001");
            assertRefused(owner, "TRUNCATE credit.decision_request_event", "P0001");
        }
        // The data request and the snapshot reference the request - of the same party.
        try (Connection app = DatabaseRoles.application()) {
            assertRefused(app, "INSERT INTO credit.data_request (id, decision_request_id, party_id, product, source_kind,"
                    + " provider_code, request_reference, status, attempts, retry_cadence, collection_window,"
                    + " next_attempt_at, requested_at, deadline_at, unavailable_reported) VALUES (gen_random_uuid(), '"
                    + open + "', gen_random_uuid(), 'PERSONAL_LOAN', 'BUREAU', 'bureau-sim-a', 'ref-" + UUID.randomUUID()
                    + "', 'REQUESTED', 0, interval '1 minute', interval '1 hour', now(), now(), now(), false)", "23503");
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A fresh SUBMITTED request for a fresh party, through the store. */
    private String fresh() {
        UUID party = UUID.randomUUID();
        return creditTransactionRunner.inTransaction(uow -> DecisionRequestRows.submitted(uow, party,
                CreditProduct.PERSONAL_LOAN)).toString();
    }

    private static void accepted(String id, String assignment) throws SQLException {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThat(statement.executeUpdate("UPDATE credit.decision_request SET " + assignment + " WHERE id = '" + id
                    + "'")).as(assignment).isEqualTo(1);
        }
    }

    private static void refused(String id, String assignment, String sqlState) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            assertRefused(app, "UPDATE credit.decision_request SET " + assignment + " WHERE id = '" + id + "'", sqlState);
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

    private static void assertNothingWritten(CreditTestClient.Customer customer) throws Exception {
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isZero();
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE party_id = ?", customer.party())).isZero();
        assertThat(count("SELECT count(*) FROM credit.credit_profile WHERE party_id = ?", customer.party())).isZero();
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
