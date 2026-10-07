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
import com.finapp.fx.CrossBorderCompletionBooking;
import com.finapp.fx.FxBooksProof;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxQuoteId;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.ledger.PostingService;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.OutboundCreditResolution;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Unwinding covers (`P9-TSK-021`, PHASE_9_PLAN.md sections 12.4(h) and 12.5; scenario 8's unwind half; ADR-0077
 * sections 7 and 8; {@code INV-FX-06}, {@code INV-FX-08}, {@code INV-XB-01}'s failure half): a cross-border payment
 * that fails after - or before - its cover executed leaves exactly one executed unwind, buying back what the cover
 * sold from the same provider at a fresh firm quote; its entry closes the cover's position legs with one realised
 * line; an UNKNOWN cover waits for knowledge; ten abandoners make one unwind; at rest the FX books prove, the
 * quote's position is zero and the customer's wallet delta is zero.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("unwinding covers (P9-TSK-021)")
class UnwindRetryDatabaseTest {

    private static final String PAYMENTS = "/v1/me/cross-border/payments";
    private static final String QUOTES = "/v1/me/cross-border/quotes";
    private static final String BENEFICIARIES = "/v1/me/cross-border/beneficiaries";
    private static final String POLICIES = "/v1/operator/cross-border/corridor-policies";
    private static final SimulatedCorridorEngine CORRIDOR = corridor();
    private static SimulatedFxEngine fxEngine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private Authorization authorization;
    @Autowired private PostingService postings;
    @Autowired private OutboundCreditResolution resolution;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private CrossBorderCompletionBooking booking;
    @Autowired private FxBooksProof booksProof;
    @Autowired private RuleSetAdministration ruleSets;
    @Autowired private com.finapp.fx.TransactionRunner fxTransactionRunner;
    @Autowired private com.finapp.reconciliation.RuleSets reconciliationRuleSets;
    @Autowired private javax.sql.DataSource dataSource;

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
            // The unwind's direction, JPY -> EUR: a rate within the band of the reference EUR/JPY 162.21.
            fxEngine.rate("JPY/EUR", "0.0061640000");
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
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        activateCorridorRuleSet();
        FxCoverFixtures.ensureFxSourceActive(ruleSets, reconciliationRuleSets, dataSource);
        awaitRoutingVersionInForce();
    }

    // ------------------------------------------------------------------ the two race orders

    @Test
    @DisplayName("cover first: the payment fails NEVER_RECEIVED after its cover executed - the abandonment writer creates"
            + " the unwind, which prices at a fresh firm quote, executes and posts 12.4(h) exactly; at rest the quote's"
            + " position is zero, the books prove and the customer's wallet delta is zero")
    void aCoverExecutedBeforeTheFailureIsUnwound() throws Exception {
        Paid paid = paid(CORRIDOR::serverErrorNext);
        UUID cover = coverOf(paid, "COVER");
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(statusOf(cover)).isEqualTo("EXECUTED");

        failNeverReceived(paid);
        UUID unwind = coverOf(paid, "UNWIND");
        assertThat(scalar("SELECT status || ' ' || attempts || ' ' || source_currency || '>' || destination_currency"
                        + " || ' ' || fixed_side || ' ' || fixed_amount_minor FROM fx.cover WHERE id = ?", unwind))
                .as("the cover's mirror: the currencies swapped, the fixed side opposite, the fixed amount bought back")
                .isEqualTo("DISPATCHED 1 JPY>EUR FIXED_DESTINATION "
                        + scalar("SELECT fixed_amount_minor::text FROM fx.cover WHERE id = ?", cover));
        assertThat(count("SELECT count(*) FROM fx.cover_attempt WHERE cover_id = ?", unwind))
                .as("unpriced until its first dispatch").isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.CoverUnwound' AND target_id = ?",
                unwind.toString())).isEqualTo(1);

        int quotes = fxEngine.quoteRequests();
        dispatch.dispatchNow(unwind, Actor.SYSTEM);
        assertThat(fxEngine.quoteRequests() - quotes).as("one fresh firm quote").isEqualTo(1);
        assertThat(statusOf(unwind)).isEqualTo("EXECUTED");
        assertUnwindEntryExact(paid, cover, unwind);
        assertAtRest(paid);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxCoverExecuted' AND aggregate_id = ?"
                + " AND convert_from(payload, 'UTF8') LIKE '%\"kind\":\"UNWIND\"%'", unwind)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?"
                + " AND kind IN ('FX_SELL_LEG', 'FX_BUY_LEG')", unwind.toString())).as("its legs open like any cover's").isEqualTo(2);
    }

    @Test
    @DisplayName("abandon first: the payment is rejected while its cover is still in flight - no unwind yet; the cover's"
            + " execution creates it in the applier's own transaction")
    void aCoverExecutedAfterTheFailureIsUnwoundByItsApplier() throws Exception {
        Paid paid = paid(() -> CORRIDOR.rejectNextSend("beneficiary_closed"));
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", paid.quote())).isEqualTo("ABANDONED");
        UUID cover = coverOf(paid, "COVER");
        assertThat(statusOf(cover)).isEqualTo("DISPATCHED");
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ? AND kind = 'UNWIND'", paid.quote())).isZero();

        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(statusOf(cover)).isEqualTo("EXECUTED");
        UUID unwind = coverOf(paid, "UNWIND");
        dispatch.dispatchNow(unwind, Actor.SYSTEM);
        assertThat(statusOf(unwind)).isEqualTo("EXECUTED");
        assertUnwindEntryExact(paid, cover, unwind);
        assertAtRest(paid);
    }

    @Test
    @DisplayName("an UNKNOWN cover waits for knowledge: no unwind while its answer is lost; the inquiry that finds it"
            + " executed creates the unwind")
    void anUnknownCoverWaits() throws Exception {
        Paid paid = paid(() -> CORRIDOR.rejectNextSend("beneficiary_closed"));
        UUID cover = coverOf(paid, "COVER");
        fxEngine.loseNextExecutionResponse();
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(statusOf(cover)).isEqualTo("UNKNOWN");
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ? AND kind = 'UNWIND'", paid.quote())).isZero();
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(statusOf(cover)).isEqualTo("EXECUTED");
        UUID unwind = coverOf(paid, "UNWIND");
        dispatch.dispatchNow(unwind, Actor.SYSTEM);
        assertAtRest(paid);
        assertThat(statusOf(unwind)).isEqualTo("EXECUTED");
    }

    @Test
    @DisplayName("scenario 8 (unwind half): ten abandoners evaluate the wanted position at once - one ABANDONED edge,"
            + " one unwind (UNIQUE (quote_id, kind)); the failure then converges and the books rest at zero")
    void tenAbandonersMakeOneUnwind() throws Exception {
        Paid paid = paid(CORRIDOR::serverErrorNext);
        UUID cover = coverOf(paid, "COVER");
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> abandoned = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            abandoned.add(pool.submit(() -> {
                start.await();
                return fxTransactionRunner.inTransaction(uow -> booking.abandon(uow, FxQuoteId.of(paid.quote()),
                        "PAYMENT_FAILED_TEST", Actor.SYSTEM, CorrelationId.generate(FxTestClient.IDS)));
            }));
        }
        start.countDown();
        int acting = 0;
        for (Future<Boolean> one : abandoned) {
            acting += one.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(acting).as("one ABANDONED edge").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.quote_event WHERE quote_id = ? AND to_status = 'ABANDONED'", paid.quote()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ? AND kind = 'UNWIND'", paid.quote())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.CoverUnwound'"
                + " AND change_summary LIKE ?", "%" + cover + "%"))
                .isEqualTo(1);

        // The failure converges: the payment fails, its abandonment finds the quote abandoned, the unwind already there.
        failNeverReceived(paid);
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ? AND kind = 'UNWIND'", paid.quote())).isEqualTo(1);
        dispatch.dispatchNow(coverOf(paid, "UNWIND"), Actor.SYSTEM);
        assertAtRest(paid);
    }

    // ------------------------------------------------------------------ assertions

    /**
     * The unwind's entry (PHASE_9_PLAN.md section 12.4(h)), against its own execution row and the quote's plan: the
     * destination position sold back onto the provider's clearing, the source position bought back from it, the
     * difference realised in each leg's currency - and the cover's position legs closed by it, to zero.
     */
    private static void assertUnwindEntryExact(Paid paid, UUID cover, UUID unwind) throws Exception {
        long positionSource = Long.parseLong(scalar("SELECT position_source_minor::text FROM fx.quote WHERE id = ?", paid.quote()));
        long positionDestination = Long.parseLong(scalar("SELECT position_destination_minor::text FROM fx.quote WHERE id = ?",
                paid.quote()));
        long sold = Long.parseLong(scalar("SELECT sold_minor::text FROM fx.cover_execution WHERE cover_id = ?", unwind));
        long bought = Long.parseLong(scalar("SELECT bought_minor::text FROM fx.cover_execution WHERE cover_id = ?", unwind));
        assertThat(scalar("SELECT sold_currency || '>' || bought_currency || ' ' || plan_sold_minor || ' ' || plan_bought_minor"
                + " FROM fx.cover_execution WHERE cover_id = ?", unwind))
                .isEqualTo("JPY>EUR " + positionDestination + " " + positionSource);
        assertThat(bought).as("the fixed leg bought back exactly").isEqualTo(positionSource);
        List<String> expected = new ArrayList<>(List.of(
                "JPY FX_POSITION DEBIT " + positionDestination,
                "JPY FX_PROVIDER_CLEARING CREDIT " + sold,
                "EUR FX_PROVIDER_CLEARING DEBIT " + bought,
                "EUR FX_POSITION CREDIT " + positionSource));
        long realised = positionDestination - sold;
        if (realised > 0) {
            expected.add("JPY FX_REALISED_GAINS CREDIT " + realised);
        } else if (realised < 0) {
            expected.add("JPY FX_REALISED_LOSSES DEBIT " + -realised);
        }
        assertThat(linesOf("fx-cover:" + unwind)).containsExactlyInAnyOrderElementsOf(expected);
        // The quote's FX_POSITION across its cover and its unwind: zero in both currencies.
        for (String currency : List.of("EUR", "JPY")) {
            assertThat(count("SELECT COALESCE(SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                            + " FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id = l.entry_id"
                            + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                            + " WHERE a.purpose = 'FX_POSITION' AND l.currency = ? AND e.idempotency_scope IN (?, ?)",
                    currency, PostingService.IDEMPOTENCY_SCOPE + ":fx-cover:" + cover,
                    PostingService.IDEMPOTENCY_SCOPE + ":fx-cover:" + unwind))
                    .as("the quote's FX_POSITION in %s at rest", currency).isZero();
        }
    }

    /** At rest: the payment FAILED, its hold released, no customer line ever posted, the FX books proving. */
    private void assertAtRest(Paid paid) throws Exception {
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("FAILED");
        assertThat(count("SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                        + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                        + " WHERE a.owner_ref = ? AND a.purpose = 'CUSTOMER_WALLET'", paid.product()))
                .as("the customer's wallet delta over the failed payment's life is zero").isEqualTo(100_000);
        assertThat(count("SELECT count(*) FROM ledger.hold h JOIN ledger.ledger_account a ON a.id = h.ledger_account_id"
                + " WHERE a.owner_ref = ? AND h.status = 'ACTIVE'", paid.product())).isZero();
        try (Connection app = DatabaseRoles.application()) {
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            app.setAutoCommit(false);
            FxBooksProof.Report report = booksProof.prove(app);
            app.rollback();
            assertThat(report.clean()).as("the FX books prove: %s", report.lines()).isTrue();
        }
    }

    private static List<String> linesOf(String postingKey) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT l.currency, a.purpose, l.direction, l.amount_minor"
                        + " FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.id = l.entry_id"
                        + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE e.idempotency_scope = ?")) {
            read.setString(1, PostingService.IDEMPOTENCY_SCOPE + ":" + postingKey);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    lines.add(row.getString(1).trim() + " " + row.getString(2) + " " + row.getString(3) + " " + row.getLong(4));
                }
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ plumbing

    record Paid(FxTestClient.Customer customer, UUID product, UUID quote, UUID payment, UUID credit, String reference) {}

    /** Pays a fresh EUR -> JPY offer, {@code arm} run just before the payment's POST - the engine's next request its send. */
    private Paid paid(Runnable arm) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("1000.00", "EUR"));
        String quote = offer(customer, beneficiary(customer));
        arm.run();
        HttpResponse<String> paid = client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", customer.token(), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?", payment));
        String reference = scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?", credit);
        return new Paid(customer, product, UUID.fromString(quote), payment, credit, reference);
    }

    private static UUID coverOf(Paid paid, String kind) throws Exception {
        return UUID.fromString(scalar("SELECT id::text FROM fx.cover WHERE quote_id = ? AND kind = ?", paid.quote(), kind));
    }

    private static String statusOf(UUID cover) throws Exception {
        return scalar("SELECT status FROM fx.cover WHERE id = ?", cover);
    }

    /** The UNKNOWN credit aged past its deadline and resolved: FAILED(NEVER_RECEIVED), the quote abandoned. */
    private void failNeverReceived(Paid paid) throws Exception {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (PreparedStatement off = owner.prepareStatement(
                            "ALTER TABLE payments.outbound_credit DISABLE TRIGGER USER");
                    PreparedStatement age = owner.prepareStatement("UPDATE payments.outbound_credit SET last_dispatched_at ="
                            + " last_dispatched_at - interval '20 minutes' WHERE id = ?");
                    PreparedStatement on = owner.prepareStatement(
                            "ALTER TABLE payments.outbound_credit ENABLE TRIGGER USER")) {
                off.execute();
                age.setObject(1, paid.credit());
                assertThat(age.executeUpdate()).isEqualTo(1);
                on.execute();
            }
            owner.commit();
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            resolution.resolve(new EndToEndReference(paid.reference()));
        }
        assertThat(scalar("SELECT status || ' ' || failure_reason FROM payments.outbound_credit WHERE id = ?", paid.credit()))
                .isEqualTo("FAILED NEVER_RECEIVED");
    }

    private String offer(FxTestClient.Customer customer, String beneficiary) throws Exception {
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"100.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        return field(offered.body(), "id");
    }

    private String beneficiary(FxTestClient.Customer customer) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("JP", "JPY", "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"JP\",\"currency\":\"JPY\",\"grant\":\""
                + grant + "\",\"name\":\"Clear Person\",\"nickname\":\"Osaka\",\"entityType\":\"INDIVIDUAL\"}",
                customer.token(), FxTestClient.key());
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    private void activateCorridorRuleSet() throws Exception {
        if (count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND status = 'ACTIVE'",
                CorridorRuleSetV1.SOURCE) > 0) {
            return;
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = ruleSets.propose(app, CorridorRuleSetV1.proposal("The corridor source's first version"),
                    new Actor("op-corridor-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSets.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE), "Reviewed against O7",
                    Instant.now(), CorrelationId.generate(FxTestClient.IDS));
            app.commit();
        }
    }

    private static void awaitRoutingVersionInForce() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (count("SELECT count(*) FROM payments.routing_policy_version WHERE version = 5 AND effective_from <= ?",
                java.sql.Timestamp.from(Instant.now())) == 0) {
            assertThat(System.nanoTime()).as("routing version 5 is not yet in force by the host's clock (database now %s)",
                    scalar("SELECT now()::text")).isLessThan(deadline);
            Thread.sleep(500);
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
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the unwind suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                        "{\"reason\":\"checked\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "xu." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        CounterpartyScreeningProvider clearUnwindScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
