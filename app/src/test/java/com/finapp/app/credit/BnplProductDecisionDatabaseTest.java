package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.IDS;
import static com.finapp.app.credit.CreditWorld.TRANSACTIONS;
import static com.finapp.app.credit.CreditWorld.count;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionMaking;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.DecisionRequest;
import com.finapp.credit.DecisionRequestId;
import com.finapp.credit.DecisionRequests;
import com.finapp.credit.JdbcCreditProfiles;
import com.finapp.credit.JdbcDecisionRequestStore;
import com.finapp.credit.RequestOrigin;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Credit decides a BNPL purchase against the simulators (`P12-TSK-001`; PHASE_12_PLAN.md section 12.1, ADR-0102 point 6;
 * {@code INV-CRD-02}, {@code INV-CRD-09}, {@code INV-CRD-12}, {@code INV-IDEM-01}, {@code INV-HIST-01}): a request opened
 * only from the BNPL application, its term shape held at the enum, the domain and the {@code CHECK}, its affordability
 * judged by the fixed plan's arm (pay-in-4) or the annuity at the stress rate (pay-in-N), its one-hour request and one-day
 * decision validities on the database clock, and ten racers per product leaving one open request.
 *
 * <p>credit's real progress, collection, freeze, assessment, evaluation and deciding transaction ({@link CreditWorld})
 * over the real simulated bureau and financial-data adapters ({@link LendingStormCredit}), each party's identifying facts
 * chosen so the simulators' own derivation gives it the file a case needs. Separate connections stand for separate
 * instances; every count is read from the rows.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings a BNPL policy per product into force
 * - proposed by one person, activated by another (no migration does) - which no shared suite may meet.
 */
@Tag("database")
@Tag("own-container")
@SuppressWarnings("try")
@DisplayName("credit decides a BNPL purchase (P12-TSK-001)")
class BnplProductDecisionDatabaseTest {

    private static final int INSTANCES = 10;
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(10);
    private static final LendingStormCredit SIMULATORS = LendingStormCredit.start("bnpl-decision-needle", 12_001L);

    private static final DecisionRequests REQUESTS = new DecisionRequests(new JdbcDecisionRequestStore(),
            new JdbcCreditProfiles(IDS), CreditWorld.POLICIES, CreditWorld.STANDING, CreditWorld.GATE,
            new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CreditWorld.CLOCK);

    @BeforeAll
    static void bnplInForce() {
        CreditWorld.seedsInForce();
        for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
            CreditWorld.bnplInForce(product);
        }
        SIMULATORS.retrievedAt(Instant.now().minus(Duration.ofHours(1)));
    }

    @AfterAll
    static void stopTheSimulators() {
        SIMULATORS.close();
    }

    // ------------------------------------------------------------------ deciding against the simulators

    @Test
    @DisplayName("a pay-in-4 request is approved for a good file and hard-declined for an insolvent one, against the"
            + " simulators: no term, the approval exactly the purchase, valid one day on the database clock, its request one"
            + " hour; the affordability its largest instalment times three")
    void aPayIn4RequestIsApprovedAndDeclinedAgainstTheSimulators() throws Exception {
        UUID good = party(LendingStormCredit.Profile.GOOD);
        UUID request = submit(good, CreditProduct.BNPL_PAY_IN_4, eur(600_03), Optional.empty());
        assertThat(scalar("SELECT (expires_at = submitted_at + interval '1 hour')::text || ':' || origin"
                + " FROM credit.decision_request WHERE id = ?", request)).isEqualTo("true:BNPL_APPLICATION");
        decide(request);
        assertThat(scalar("SELECT outcome || ':' || approved_minor || ':' || coalesce(term_months::text, '-') || ':'"
                + " || (decision_validity = interval '1 day' AND valid_until = decided_at + interval '1 day')::text"
                + " FROM credit.credit_decision WHERE decision_request_id = ?", request))
                .as("approved exactly the purchase, no term, valid a day").isEqualTo("APPROVED:60003:-:true");
        // 600.03: instalments 2 to 4 of floor(60003 / 4) = 15000, the first 60003 - 45000 = 15003; times three.
        assertThat(count("SELECT repayment_minor FROM credit.credit_assessment WHERE decision_request_id = ?", request))
                .as("the fixed plan's arm: 150.03 x 3").isEqualTo(450_09);
        assertThat(count("SELECT count(*) FROM credit.underwriting_case WHERE decision_request_id = ?", request)).isZero();

        UUID insolvent = party(LendingStormCredit.Profile.INSOLVENT);
        UUID declined = submit(insolvent, CreditProduct.BNPL_PAY_IN_4, eur(600_00), Optional.empty());
        decide(declined);
        assertThat(scalar("SELECT d.outcome || ':' || coalesce(d.approved_minor::text, '-') || ':'"
                + " || string_agg(r.reason_code, ',' ORDER BY r.ordinal) FROM credit.credit_decision d"
                + " JOIN credit.credit_decision_reason r ON r.decision_id = d.id WHERE d.decision_request_id = ?"
                + " GROUP BY d.outcome, d.approved_minor", declined))
                .as("decided automatically - never referred").startsWith("DECLINED:-:CRD-INSOLVENCY");
        assertThat(count("SELECT count(*) FROM credit.underwriting_case WHERE decision_request_id = ?", declined)).isZero();
    }

    @Test
    @DisplayName("a pay-in-N request is judged by the annuity at the policy's stress rate (18%) over its own term, and its"
            + " approval carries that term")
    void aPayInNRequestIsJudgedAtTheStressRate() throws Exception {
        for (int term : List.of(3, 6, 12)) {
            UUID good = party(LendingStormCredit.Profile.GOOD);
            UUID request = submit(good, CreditProduct.BNPL_PAY_IN_N, eur(2_400_00), Optional.of(term));
            decide(request);
            assertThat(scalar("SELECT outcome || ':' || approved_minor || ':' || term_months"
                    + " FROM credit.credit_decision WHERE decision_request_id = ?", request))
                    .isEqualTo("APPROVED:240000:" + term);
            assertThat(count("SELECT repayment_minor FROM credit.credit_assessment WHERE decision_request_id = ?", request))
                    .as("%d months at 18%%", term).isEqualTo(annuity(2_400_00, term, "0.18"));
        }
    }

    // ------------------------------------------------------------------ the counted race

    @Test
    @DisplayName("ten BNPL applications racing for one party and product, provably released together: one open request,"
            + " nine refused credit.DecisionRequestOpen naming it - for each BNPL product")
    void tenRequestsForOnePartyAndProductOpenOne() throws Exception {
        for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
            UUID party = UUID.randomUUID();
            Optional<Integer> term = product.termShape() == CreditProduct.TermShape.MONTHS ? Optional.of(6) : Optional.empty();
            ExecutorService instances = Executors.newFixedThreadPool(INSTANCES);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> pending = new ArrayList<>();
            try {
                for (int i = 0; i < INSTANCES; i++) {
                    pending.add(instances.submit(() -> {
                        start.await();
                        try {
                            return TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, party,
                                    terms(product, eur(300_00), term), RequestOrigin.BNPL_APPLICATION, application(),
                                    CorrelationId.generate(IDS)));
                        } catch (DecisionRequests.DecisionRequestOpen open) {
                            return open;
                        }
                    }));
                }
                start.countDown();
                List<Object> answers = new ArrayList<>();
                for (Future<Object> each : pending) {
                    answers.add(each.get(2, TimeUnit.MINUTES));
                }
                List<DecisionRequest> born = answers.stream().filter(DecisionRequest.class::isInstance)
                        .map(DecisionRequest.class::cast).toList();
                assertThat(born).as(product.name()).hasSize(1);
                assertThat(answers).filteredOn(DecisionRequests.DecisionRequestOpen.class::isInstance)
                        .hasSize(INSTANCES - 1)
                        .allSatisfy(answer -> assertThat(((DecisionRequests.DecisionRequestOpen) answer).open())
                                .isEqualTo(born.get(0).id()));
            } finally {
                instances.shutdownNow();
            }
            assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ? AND product = ?", party,
                    product.name())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditDecisionRequested'"
                    + " AND aggregate_id IN (SELECT id FROM credit.decision_request WHERE party_id = ?)", party))
                    .as("one event, the losers' rolled back").isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ the origin

    @Test
    @DisplayName("a BNPL request opens only with the BNPL origin: from the customer door credit.ProductNotOffered before"
            + " anything is written, and the CHECK refuses the row for any writer; a lending product is not offered from"
            + " the BNPL application; the origin is frozen; only the BNPL origin cancels it")
    void aBnplRequestOpensOnlyWithTheBnplOrigin() throws Exception {
        UUID party = UUID.randomUUID();
        for (CreditProduct product : CreditProduct.of(CreditProduct.Family.BNPL)) {
            Optional<Integer> term = product.termShape() == CreditProduct.TermShape.MONTHS ? Optional.of(3) : Optional.empty();
            assertThatExceptionOfType(DecisionRequests.ProductNotOffered.class).as(product.name())
                    .isThrownBy(() -> TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, party,
                            terms(product, eur(300_00), term), RequestOrigin.CUSTOMER_DOOR, application(),
                            CorrelationId.generate(IDS))))
                    .withMessageContaining("CUSTOMER_DOOR");
        }
        assertThatExceptionOfType(DecisionRequests.ProductNotOffered.class).as("a loan from the BNPL application")
                .isThrownBy(() -> TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, party,
                        terms(CreditProduct.PERSONAL_LOAN, eur(10_000_00), Optional.of(36)), RequestOrigin.BNPL_APPLICATION,
                        application(), CorrelationId.generate(IDS))));
        assertThat(count("SELECT count(*) FROM credit.credit_profile WHERE party_id = ?", party))
                .as("refused before the profile is even ensured").isZero();

        DecisionRequest born = TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, party,
                terms(CreditProduct.BNPL_PAY_IN_4, eur(300_00), Optional.empty()), RequestOrigin.BNPL_APPLICATION,
                application(), CorrelationId.generate(IDS)));
        assertThat(born.origin()).isEqualTo(RequestOrigin.BNPL_APPLICATION);
        String id = born.id().value().toString();
        String copy = "INSERT INTO credit.decision_request (id, party_id, profile_id, product, currency, requested_minor,"
                + " term_months, status, request_validity, submitted_at, expires_at, next_step_at, correlation_id, origin)"
                + " SELECT gen_random_uuid(), party_id, profile_id, '%s', currency, requested_minor, %s, 'SUBMITTED',"
                + " request_validity, now(), now(), now(), 'raw', '%s' FROM credit.decision_request WHERE id = '" + id + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedWith(owner, copy.formatted("BNPL_PAY_IN_N", "6", "CUSTOMER_DOOR"), "23514",
                    "decision_request_origin_per_product");
            refusedWith(owner, copy.formatted("PERSONAL_LOAN", "36", "BNPL_APPLICATION"), "23514",
                    "decision_request_origin_per_product");
            refusedWith(owner, copy.formatted("BNPL_PAY_IN_N", "6", "MERCHANT"), "23514", "decision_request_origin");
            // The origin is frozen by the machine trigger, for the owner too: alone, or with a product it would admit.
            refusedWith(owner, "UPDATE credit.decision_request SET origin = 'CUSTOMER_DOOR' WHERE id = '" + id + "'",
                    "P0001", "frozen from submission");
            refusedWith(owner, "UPDATE credit.decision_request SET origin = 'CUSTOMER_DOOR', product = 'CREDIT_LINE' WHERE"
                    + " id = '" + id + "'", "P0001", "frozen from submission");
        }
        try (Connection app = DatabaseRoles.application()) {
            refusedWith(app, "UPDATE credit.decision_request SET origin = 'CUSTOMER_DOOR' WHERE id = '" + id + "'",
                    "P0001", "frozen from submission");
        }
        assertThatThrownBy(() -> TRANSACTIONS.inTransaction(uow -> REQUESTS.cancel(uow, born.id(), party,
                RequestOrigin.CUSTOMER_DOOR, customer(party), CorrelationId.generate(IDS))))
                .as("the customer door does not find the BNPL application's request")
                .isInstanceOf(DecisionRequests.DecisionRequestNotFound.class);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", born.id().value()))
                .isEqualTo("SUBMITTED");
        DecisionRequest cancelled = TRANSACTIONS.inTransaction(uow -> REQUESTS.cancel(uow, born.id(), party,
                RequestOrigin.BNPL_APPLICATION, application(), CorrelationId.generate(IDS)));
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ the term shape at three ranks

    @Test
    @DisplayName("the term shape is held by the database as by the domain: pay-in-4 with any term and pay-in-N without one"
            + " are refused rows for any writer; an approval carries a term exactly for pay-in-N; pay-in-N's 4 months the"
            + " domain refuses credit.AmountOutOfRange")
    void theTermShapeIsHeldByTheDatabase() throws Exception {
        UUID party = UUID.randomUUID();
        DecisionRequest born = TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, party,
                terms(CreditProduct.BNPL_PAY_IN_N, eur(300_00), Optional.of(12)), RequestOrigin.BNPL_APPLICATION,
                application(), CorrelationId.generate(IDS)));
        String copy = "INSERT INTO credit.decision_request (id, party_id, profile_id, product, currency, requested_minor,"
                + " term_months, status, request_validity, submitted_at, expires_at, next_step_at, correlation_id, origin)"
                + " SELECT gen_random_uuid(), gen_random_uuid(), profile_id, '%s', currency, requested_minor, %s,"
                + " 'SUBMITTED', request_validity, now(), now(), now(), 'raw', 'BNPL_APPLICATION'"
                + " FROM credit.decision_request WHERE id = '" + born.id().value() + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            for (String term : List.of("1", "4", "12")) {
                refusedWith(owner, copy.formatted("BNPL_PAY_IN_4", term), "23514", "decision_request_term_per_product");
            }
            refusedWith(owner, copy.formatted("BNPL_PAY_IN_N", "NULL"), "23514", "decision_request_term_per_product");
            refusedWith(owner, copy.formatted("BNPL_PAY_IN_N", "0"), "23514", "decision_request_term_per_product");
            refusedWith(owner, "UPDATE credit.decision_request SET term_months = 6 WHERE id = '" + born.id().value() + "'",
                    "P0001", "frozen from submission");
        }
        for (Optional<Integer> refused : List.of(Optional.of(4), Optional.of(1), Optional.of(24), Optional.<Integer>empty())) {
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("pay-in-N over %s", refused)
                    .isThrownBy(() -> TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, UUID.randomUUID(),
                            terms(CreditProduct.BNPL_PAY_IN_N, eur(300_00), refused), RequestOrigin.BNPL_APPLICATION,
                            application(), CorrelationId.generate(IDS))));
        }
        for (Optional<Integer> refused : List.of(Optional.of(4), Optional.of(3))) {
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("pay-in-4 over %s", refused)
                    .isThrownBy(() -> TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, UUID.randomUUID(),
                            terms(CreditProduct.BNPL_PAY_IN_4, eur(300_00), refused), RequestOrigin.BNPL_APPLICATION,
                            application(), CorrelationId.generate(IDS))));
        }
        // An approval's term per shape, for the owner too: a decided pay-in-4 copied with a term, a pay-in-N without.
        UUID p4 = submit(party(LendingStormCredit.Profile.GOOD), CreditProduct.BNPL_PAY_IN_4, eur(200_00), Optional.empty());
        decide(p4);
        String decisionCopy = "INSERT INTO credit.credit_decision SELECT gen_random_uuid(), decision_request_id, party_id,"
                + " profile_id, product, snapshot_id, snapshot_sha256, outcome, currency, requested_minor, approved_minor, %s,"
                + " decision_validity, decided_at, valid_until, decided_by, decided_by_type, policy_version_id,"
                + " model_version_id, engine_version FROM credit.credit_decision WHERE decision_request_id = '" + p4 + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedWith(owner, decisionCopy.formatted("4"), "23514", "credit_decision_approval_coherent");
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A party registered with the simulators as {@code profile}, its consents and standing the world's defaults. */
    private static UUID party(LendingStormCredit.Profile profile) {
        UUID party = UUID.randomUUID();
        SIMULATORS.register(party, profile);
        return party;
    }

    private static UUID submit(UUID party, CreditProduct product, Money amount, Optional<Integer> term) {
        return TRANSACTIONS.inTransaction(uow -> REQUESTS.submit(uow, party, terms(product, amount, term),
                RequestOrigin.BNPL_APPLICATION, application(), CorrelationId.generate(IDS))).id().value();
    }

    /** Steps the request through collection, assessment, evaluation and the deciding transaction until it is decided. */
    private static void decide(UUID request) throws Exception {
        CreditDataCollection collection = CreditWorld.collection(CreditDataCollection.Sources.of(
                SIMULATORS.bureau(PROVIDER_TIMEOUT), CreditWorld.TIMING, SIMULATORS.financialData(PROVIDER_TIMEOUT),
                CreditWorld.TIMING));
        DecisionMaking deciding = CreditWorld.deciding(CreditWorld.CLOCK, collection);
        DecisionProgress progress = CreditWorld.progress(CreditWorld.CLOCK, deciding, collection);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (!"DECIDED".equals(scalar("SELECT status FROM credit.decision_request WHERE id = ?", request))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the request stopped at "
                        + scalar("SELECT status FROM credit.decision_request WHERE id = ?", request));
            }
            CreditWorld.step(progress, request);
            Thread.sleep(50);
        }
    }

    private static DecisionRequests.Terms terms(CreditProduct product, Money amount, Optional<Integer> term) {
        return new DecisionRequests.Terms(product, amount, term, Optional.of(eur(450_000)), Optional.of(eur(100_000)));
    }

    /** The annuity the engine computes, rounded once half up - credit's own arithmetic, read back independently. */
    private static long annuity(long minor, int term, String rate) {
        BigDecimal amount = BigDecimal.valueOf(minor, 2);
        BigDecimal monthly = new BigDecimal(rate).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_EVEN);
        BigDecimal growth = BigDecimal.ONE.add(monthly).pow(term).setScale(10, RoundingMode.HALF_EVEN);
        BigDecimal discount = BigDecimal.ONE.divide(growth, 10, RoundingMode.HALF_EVEN);
        BigDecimal payment = amount.multiply(monthly).divide(BigDecimal.ONE.subtract(discount), 10, RoundingMode.HALF_EVEN);
        return Money.of(payment, CreditWorld.EUR, RoundingPolicy.HALF_UP).minorUnits();
    }

    /** The BNPL application acting - a system actor, as bnpl's origination will (`P12-TSK-010`). */
    private static Actor application() {
        return new Actor("bnpl-application", ActorType.SYSTEM);
    }

    private static Actor customer(UUID party) {
        return new Actor(party.toString(), ActorType.CUSTOMER);
    }

    private static void refusedWith(Connection connection, String sql, String sqlState, String message) {
        assertThatExceptionOfType(SQLException.class).as(sql).isThrownBy(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).matches(e -> sqlState.equals(e.getSQLState()) && e.getMessage().contains(message),
                "SQLState " + sqlState + " naming " + message);
    }
}
