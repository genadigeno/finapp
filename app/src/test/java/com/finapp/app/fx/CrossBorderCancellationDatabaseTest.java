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
import com.finapp.crossborder.CrossBorderExecution;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.ledger.PostingService;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.OutboundCreditOutcomes;
import com.finapp.payments.OutboundCreditResolution;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Cancellation by recall (`P9-TSK-024`, ADR-0079 point 5, D22; PHASE_9_PLAN.md section 14 scenarios 29-31;
 * {@code INV-XB-01}, {@code INV-LIFE-03}, {@code INV-PAY-04}): a customer's cancellation is a recall REQUEST, born
 * once per payment, honoured only on the provider's definitive word - a recall that wins fails the payment
 * {@code RECALLED} (the hold released, the quote abandoned, the executed cover unwound; shown {@code CANCELLED}, never
 * debited); one that comes too late changes nothing (the payment completes, shown {@code SENT}); one racing the
 * acceptance ends in exactly one of the two. Ten requests, one fact; no re-send after a request; a lost recall answer
 * re-asked; each transaction atomic under an injected fault; the needle.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("cancellation by recall (P9-TSK-024)")
class CrossBorderCancellationDatabaseTest {

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
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private OutboundCreditResolution resolution;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private CrossBorderExecution execution;
    @Autowired private MeterRegistry meters;
    @Autowired private com.finapp.payments.OutboundCreditStore outboundCreditStore;

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
            FxTestClient.rates(fxEngine, Map.of("EUR/USD", "1.0850240000"));
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
        // The provider acknowledges and has not committed: the window in which a recall can win.
        CORRIDOR.acceptOnReceipt(false);
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        if (count("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'") == 0) {
            activateCorridorPolicy();
        }
        activateCorridorRuleSet();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        awaitRoutingVersionInForce();
    }

    // ------------------------------------------------------------------ scenario 29: the recall wins

    @Test
    @DisplayName("scenario 29, the recall wins: 202 PROCESSING with cancellationRequested; the provider's RECALLED fails"
            + " the payment RECALLED - the hold released, nothing posted, the quote ABANDONED, the executed cover unwound;"
            + " the customer sees CANCELLED and was never debited")
    void aRecallThatWinsCancels() throws Exception {
        Paid paid = paid("Clear Person");
        assertThat(credit(paid, "status")).isEqualTo("RECEIVED");
        UUID cover = UUID.fromString(scalar("SELECT id::text FROM fx.cover WHERE quote_id = ? AND kind = 'COVER'", paid.quote()));
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(scalar("SELECT status FROM fx.cover WHERE id = ?", cover)).isEqualTo("EXECUTED");
        double recalled = counted("recalled");

        HttpResponse<String> requested = cancel(paid, FxTestClient.key());
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(202);
        assertThat(field(requested.body(), "status")).isEqualTo("PROCESSING");
        assertThat(requested.body()).contains("\"cancellationRequested\":true");
        assertThat(count("SELECT count(*) FROM crossborder.cancellation_request WHERE payment_id = ?", paid.payment())).isEqualTo(1);
        assertThat(scalar("SELECT (recall_requested_at IS NOT NULL)::text FROM payments.outbound_credit WHERE id = ?",
                paid.credit())).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderCancellationRequested'"
                + " AND aggregate_id = ?", paid.payment())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'crossborder.CrossBorderCancellationRequested'"
                + " AND target_id = ?", paid.payment().toString())).isEqualTo(1);
        // The schedule's due read names the credit at once, whatever its permit's age: the recall is asked by the sweep.
        try (Connection app = DatabaseRoles.application()) {
            java.time.Duration day = java.time.Duration.ofDays(1);
            assertThat(outboundCreditStore.findDue(app, day, day, day, day, 100_000))
                    .extracting(row -> row.id().value()).contains(paid.credit());
        }

        assertThat(resolve(paid)).hasValueSatisfying(applied -> assertThat(applied.acting()).isTrue());
        assertThat(credit(paid, "status || ':' || failure_reason || ':' || recall_outcome")).isEqualTo("FAILED:RECALLED:RECALLED");
        assertThat(scalar("SELECT status || ':' || failure_reason FROM crossborder.payment WHERE id = ?", paid.payment()))
                .isEqualTo("FAILED:RECALLED");
        assertThat(holdStatus(paid)).isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:" + paid.credit())).as("nothing posted").isZero();
        assertThat(walletBalance(paid)).as("never debited").isEqualTo(110_000);
        assertThat(scalar("SELECT status FROM fx.quote WHERE id = ?", paid.quote())).isEqualTo("ABANDONED");
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = ? AND kind = 'UNWIND'", paid.quote()))
                .as("the executed cover unwound - the platform bears its result").isEqualTo(1);
        assertThat(field(read(paid).body(), "status")).isEqualTo("CANCELLED");
        assertThat(counted("recalled") - recalled).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", paid.credit().toString()))
                .as("a recalled payment opens no expectation").isZero();

        // Duplicates: a second pass and a second request change nothing.
        resolve(paid);
        assertThat(cancel(paid, FxTestClient.key()).body()).contains("crossborder.NotCancellable");
        assertThat(count("SELECT count(*) FROM crossborder.cancellation_request WHERE payment_id = ?", paid.payment())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ scenario 30: too late

    @Test
    @DisplayName("scenario 30, the recall comes too late: the provider had committed - REFUSED is recorded, nothing is"
            + " concluded by it, the same pass completes the credit; the payment is SENT and never shown CANCELLED")
    void aRecallTooLateCompletes() throws Exception {
        Paid paid = paid("Clear Person");
        CORRIDOR.accept(paid.reference());
        double tooLate = counted("too_late");
        HttpResponse<String> requested = cancel(paid, FxTestClient.key());
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(202);

        resolve(paid);
        assertThat(credit(paid, "status || ':' || recall_outcome")).isEqualTo("COMPLETED:REFUSED");
        assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("IN_TRANSIT");
        HttpResponse<String> shown = read(paid);
        assertThat(field(shown.body(), "status")).isEqualTo("SENT");
        assertThat(shown.body()).contains("\"cancellationRequested\":true");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:" + paid.credit())).isEqualTo(1);
        assertThat(counted("too_late") - tooLate).isEqualTo(1);
    }

    // ------------------------------------------------------------------ scenario 31: the race

    @Test
    @DisplayName("scenario 31, a recall racing the acceptance: ten movers - the provider accepting, cancellations and"
            + " inquiries - end in exactly one coherent outcome, COMPLETED with its one entry or FAILED(RECALLED) with"
            + " none, never both; at most one request")
    void aRecallRacingTheAcceptanceIsCoherent() throws Exception {
        Paid paid = paid("Clear Person");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> movers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int role = i % 3;
                movers.add(pool.submit(() -> {
                    start.await();
                    if (role == 0) {
                        CORRIDOR.accept(paid.reference());
                        return resolve(paid);
                    }
                    return role == 1 ? cancel(paid, FxTestClient.key()) : resolve(paid);
                }));
            }
            start.countDown();
            for (Future<?> mover : movers) {
                mover.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        resolve(paid);
        resolve(paid);

        String status = credit(paid, "status");
        long entries = count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":outbound-credit:" + paid.credit());
        assertThat(status).isIn("COMPLETED", "FAILED");
        if (status.equals("COMPLETED")) {
            assertThat(entries).isEqualTo(1);
            assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isIn("IN_TRANSIT", "DELIVERED");
            assertThat(field(read(paid).body(), "status")).isNotEqualTo("CANCELLED");
        } else {
            assertThat(credit(paid, "failure_reason || ':' || recall_outcome")).isEqualTo("RECALLED:RECALLED");
            assertThat(entries).isZero();
            assertThat(holdStatus(paid)).isEqualTo("RELEASED");
            assertThat(field(read(paid).body(), "status")).isEqualTo("CANCELLED");
        }
        assertThat(count("SELECT count(*) FROM crossborder.cancellation_request WHERE payment_id = ?", paid.payment()))
                .isLessThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ duplicates and refusals

    @Test
    @DisplayName("ten concurrent cancellation requests under ten keys are one request: one row, one event, one audit"
            + " record, the credit marked once; a key replayed answers the same")
    void tenRequestsAreOneRequest() throws Exception {
        Paid paid = paid("Clear Person");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<HttpResponse<String>>> requests = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                requests.add(pool.submit(() -> {
                    start.await();
                    return cancel(paid, FxTestClient.key());
                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> request : requests) {
                statuses.add(request.get(2, TimeUnit.MINUTES).statusCode());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsOnly(202);
        assertThat(count("SELECT count(*) FROM crossborder.cancellation_request WHERE payment_id = ?", paid.payment())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderCancellationRequested'"
                + " AND aggregate_id = ?", paid.payment())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'crossborder.CrossBorderCancellationRequested'"
                + " AND target_id = ?", paid.payment().toString())).isEqualTo(1);

        String key = FxTestClient.key();
        HttpResponse<String> first = cancel(paid, key);
        HttpResponse<String> replay = cancel(paid, key);
        assertThat(replay.statusCode()).isEqualTo(first.statusCode()).isEqualTo(202);
        assertThat(field(replay.body(), "paymentId")).isEqualTo(paid.payment().toString());
    }

    @Test
    @DisplayName("refusals write nothing: a payment the provider accepted is 409 NotCancellable; a stranger's is 404; an"
            + " MFA-enrolled customer on a password-only session is 403 AssuranceRequired")
    void refusalsWriteNothing() throws Exception {
        CORRIDOR.acceptOnReceipt(true);
        Paid sent = paid("Clear Person");
        HttpResponse<String> late = cancel(sent, FxTestClient.key());
        assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
        assertThat(late.body()).contains("crossborder.NotCancellable");
        assertNothingRequested(sent);

        CORRIDOR.acceptOnReceipt(false);
        Paid owned = paid("Clear Person");
        FxTestClient.Customer stranger = client().verifiedCustomer();
        HttpResponse<String> foreign = client().post(PAYMENTS + "/" + owned.payment() + "/cancellation", null,
                stranger.token(), FxTestClient.key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.body()).contains("crossborder.PaymentNotFound");
        assertNothingRequested(owned);

        enrolAndConfirm(owned.customer().token());
        HttpResponse<String> stepUp = cancel(owned, FxTestClient.key());
        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");
        assertNothingRequested(owned);
    }

    // ------------------------------------------------------------------ no re-send, lost answers, atomicity

    @Test
    @DisplayName("no re-send after a request: the takeover's permit renewal refuses, the permit unmoved and the provider"
            + " holding one credit; a lost recall answer concludes nothing and is asked again")
    void nothingIsResentAndALostAnswerIsAskedAgain() throws Exception {
        Paid paid = paid("Clear Person");
        assertThat(cancel(paid, FxTestClient.key()).statusCode()).isEqualTo(202);
        String permit = credit(paid, "last_dispatched_at::text");
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            CrossBorderExecution.Dispatched dispatched = execution.dispatched(app, paid.payment()).orElseThrow();
            assertThat(execution.renewPermit(app, dispatched)).as("an instruction with a recall requested is never re-sent")
                    .isFalse();
            app.commit();
        }
        assertThat(credit(paid, "last_dispatched_at::text")).isEqualTo(permit);
        assertThat(CORRIDOR.creditsOf(paid.reference())).isEqualTo(1);

        CORRIDOR.serverErrorNext();
        resolve(paid);
        assertThat(credit(paid, "status || ':' || coalesce(recall_outcome, '-')")).as("no answer is not an answer")
                .isEqualTo("RECEIVED:-");
        resolve(paid);
        assertThat(credit(paid, "status || ':' || recall_outcome")).isEqualTo("FAILED:RECALLED");
        assertThat(CORRIDOR.creditsOf(paid.reference())).isEqualTo(1);
    }

    @Test
    @DisplayName("atomicity: an injected fault under the request leaves the credit unmarked and no request; one under the"
            + " conclusion leaves the credit RECEIVED, the hold standing and no outcome; each lifted, each completes once")
    void eachTransactionIsAtomic() throws Exception {
        Paid paid = paid("Clear Person");
        try (AutoCloseable fault = refuse("crossborder.cancellation_request", "true")) {
            HttpResponse<String> refused = cancel(paid, FxTestClient.key());
            assertThat(refused.statusCode()).as(refused.body()).isGreaterThanOrEqualTo(500);
            assertNothingRequested(paid);
        }
        assertThat(cancel(paid, FxTestClient.key()).statusCode()).isEqualTo(202);

        try (AutoCloseable fault = refuse("crossborder.payment_event", "NEW.payment_id = '" + paid.payment() + "'::uuid")) {
            try {
                resolve(paid);
            } catch (RuntimeException expected) {
                // the conclusion's transaction rolled back whole
            }
            assertThat(credit(paid, "status || ':' || coalesce(recall_outcome, '-')")).isEqualTo("RECEIVED:-");
            assertThat(holdStatus(paid)).isEqualTo("ACTIVE");
            assertThat(scalar("SELECT status FROM crossborder.payment WHERE id = ?", paid.payment())).isEqualTo("SUBMITTED");
        }
        resolve(paid);
        assertThat(credit(paid, "status || ':' || recall_outcome")).isEqualTo("FAILED:RECALLED");
        assertThat(holdStatus(paid)).isEqualTo("RELEASED");
    }

    // ------------------------------------------------------------------ the needle

    @Test
    @DisplayName("the needle (recall leg): the beneficiary's name is in no table, event, audit record or response the"
            + " cancellation and its recall touched")
    void theNameReachesNoCancellation() throws Exception {
        String name = "Leocadia Needlewick " + UUID.randomUUID().toString().substring(0, 6);
        Paid paid = paid(name);
        HttpResponse<String> requested = cancel(paid, FxTestClient.key());
        assertThat(requested.body()).doesNotContain(name);
        resolve(paid);
        assertThat(read(paid).body()).doesNotContain(name);
        for (String table : List.of("crossborder.cancellation_request", "crossborder.payment_event", "payments.outbound_credit",
                "payments.provider_evidence")) {
            assertThat(count("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?", "%" + name + "%")).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE convert_from(payload, 'UTF8') LIKE ?",
                "%" + name + "%")).isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record t WHERE t::text LIKE ?", "%" + name + "%")).isZero();
    }

    // ------------------------------------------------------------------ plumbing

    record Paid(FxTestClient.Customer customer, UUID product, UUID quote, UUID payment, UUID credit, String reference) {}

    /** Pays section 12.4(g)'s offer (1,000.00 EUR fixed source to a US beneficiary) from a wallet funded 1,100.00. */
    private Paid paid(String beneficiaryName) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("1100.00", "EUR"));
        String beneficiary = beneficiary(customer, beneficiaryName);
        HttpResponse<String> offered = client().post(QUOTES, "{\"beneficiaryId\":\"" + beneficiary + "\",\"sourceCurrency\":"
                + "\"EUR\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"1000.00\"}", customer.token(), FxTestClient.key());
        assertThat(offered.statusCode()).as(offered.body()).isEqualTo(201);
        String quote = field(offered.body(), "id");
        HttpResponse<String> paid = client().post(PAYMENTS, "{\"quoteId\":\"" + quote + "\"}", customer.token(), FxTestClient.key());
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(202);
        assertThat(paid.body()).doesNotContain(beneficiaryName);
        UUID payment = UUID.fromString(field(paid.body(), "paymentId"));
        UUID credit = UUID.fromString(scalar("SELECT id::text FROM payments.outbound_credit WHERE subject_id = ?", payment));
        return new Paid(customer, product, UUID.fromString(quote), payment, credit,
                scalar("SELECT end_to_end_reference FROM payments.outbound_credit WHERE id = ?", credit));
    }

    private HttpResponse<String> cancel(Paid paid, String key) throws Exception {
        return client().post(PAYMENTS + "/" + paid.payment() + "/cancellation", null, paid.customer().token(), key);
    }

    private HttpResponse<String> read(Paid paid) throws Exception {
        return client().get(PAYMENTS + "/" + paid.payment(), paid.customer().token());
    }

    private Optional<OutboundCreditOutcomes.Applied> resolve(Paid paid) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return resolution.resolve(new EndToEndReference(paid.reference()));
        }
    }

    private double counted(String outcome) {
        Counter counter = meters.find("finapp.crossborder.cancellation").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String credit(Paid paid, String expression) throws Exception {
        return scalar("SELECT " + expression + " FROM payments.outbound_credit WHERE id = ?", paid.credit());
    }

    private static String holdStatus(Paid paid) throws Exception {
        return scalar("SELECT h.status FROM ledger.hold h JOIN payments.outbound_credit c ON c.hold_id = h.id WHERE c.id = ?",
                paid.credit());
    }

    private static long walletBalance(Paid paid) throws Exception {
        return count("SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " WHERE a.owner_ref::text = ? AND a.purpose = 'CUSTOMER_WALLET' AND a.currency = 'EUR'", paid.product().toString());
    }

    private static void assertNothingRequested(Paid paid) throws Exception {
        assertThat(count("SELECT count(*) FROM crossborder.cancellation_request WHERE payment_id = ?", paid.payment())).isZero();
        assertThat(credit(paid, "(recall_requested_at IS NULL)::text")).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'crossborder.CrossBorderCancellationRequested'"
                + " AND aggregate_id = ?", paid.payment())).isZero();
    }

    /** A fault injected before every insert into {@code table} matching {@code condition}; closing it lifts the fault. */
    private static AutoCloseable refuse(String table, String condition) throws Exception {
        String name = "probe_refuse_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String schema = table.substring(0, table.indexOf('.'));
        try (Connection owner = DatabaseRoles.migrator(); java.sql.Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION " + schema + "." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF " + condition + " THEN RAISE EXCEPTION 'injected fault'; END IF; RETURN NEW; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION "
                    + schema + "." + name + "()");
        }
        return () -> {
            try (Connection owner = DatabaseRoles.migrator(); java.sql.Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON " + table);
                ddl.execute("DROP FUNCTION " + schema + "." + name + "()");
            }
        };
    }

    /** Enrols and confirms a TOTP factor on the session's identity; the session itself stays password-only. */
    private void enrolAndConfirm(String sessionToken) throws Exception {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(client().post("/v1/me/mfa", null, sessionToken, null).body());
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> secret = Sensitive.of(matcher.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        String confirming = Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond((step - 1) * TotpParameters.current().periodSeconds()));
        assertThat(client().post("/v1/me/mfa/confirmation", "{\"code\":\"" + confirming + "\"}", sessionToken, null).statusCode())
                .isEqualTo(204);
    }

    private String beneficiary(FxTestClient.Customer customer, String name) throws Exception {
        String grant = CORRIDOR.issueGrant(new SimulatedCorridorEngine.Beneficiary("US", "USD", "individual", "match"));
        HttpResponse<String> registered = client().post(BENEFICIARIES, "{\"country\":\"US\",\"currency\":\"USD\",\"grant\":\""
                + grant + "\",\"name\":\"" + name + "\",\"nickname\":\"Payee\",\"entityType\":\"INDIVIDUAL\"}",
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
            UUID ruleSet = ruleSetAdministration.propose(app, CorridorRuleSetV1.proposal("The corridor source's first version"),
                    new Actor("op-corridor-controller-a", ActorType.EMPLOYEE), Instant.now(),
                    CorrelationId.generate(FxTestClient.IDS)).ruleSetId();
            ruleSetAdministration.approve(app, ruleSet, new Actor("op-corridor-controller-b", ActorType.EMPLOYEE),
                    "Reviewed against O7", Instant.now(), CorrelationId.generate(FxTestClient.IDS));
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
        HttpResponse<String> proposed = client().post(POLICIES, CorridorPolicyV1.json("v1 for the corridor cancellation suite"), first,
                FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(client().post(POLICIES + "/" + field(proposed.body(), "id") + "/approval",
                        "{\"reason\":\"checked\"}", second, null).statusCode())
                .isEqualTo(200);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "xc." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        CounterpartyScreeningProvider clearCorridorCancellationScreeningProvider() {
            byte[] evidence = "{\"status\":\"scripted\"}".getBytes(StandardCharsets.UTF_8);
            return (screening, subject) -> CounterpartyScreeningProvider.Answer.of(Verdict.CLEAR, evidence);
        }
    }
}
