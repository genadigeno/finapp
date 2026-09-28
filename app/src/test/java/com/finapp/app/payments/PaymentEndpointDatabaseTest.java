package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The payment surface over real HTTP (`P5-TSK-011`): the phase's acceptance chain — attach,
 * create, confirm, the simulated provider authorises and captures, the wallet balance moves,
 * the statement shows the entry, the chain walked by identifier.
 *
 * <p>The concurrency proofs are the commands' and are <strong>cited, not repeated</strong>
 * (the `P1-TSK-012` rule): the creation's claim, the confirmation's conditional dispatch and
 * the capture's counted ten-way race live in {@code PaymentAuthorizationDatabaseTest} and
 * {@code PaymentCaptureDatabaseTest}. What only this suite can prove is the surface: the
 * contract shape (honestly {@code PROCESSING} included), the disclosure folds, the
 * byte-for-byte replay <em>across a later confirmation</em>, and the chain — the surface is
 * the capture's chainer, and dropping the chain is this suite's named mutation.
 *
 * <p>One {@link SimulatedProvider} plays both externals — the tokenisation provider (attach)
 * and the card PSP (authorize/capture) — on different paths, exactly as one deployment
 * property set would name two endpoints.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the payment endpoints (P5-TSK-011)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PaymentEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @Autowired private com.finapp.identity.Authorization authorization;
    @Autowired private com.finapp.payments.PaymentConfirmation confirmation;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The acceptance chain
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the acceptance chain holds: attach, create, confirm - the provider authorises"
            + " and captures, the balance moves, the statement shows the entry, and the chain"
            + " is walked by identifier in both directions")
    void theAcceptanceChainHolds() throws Exception {
        providerAuthorises("psp_auth-chain");
        providerCaptures("psp_cap-chain");
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = attachInstrument(token);

        HttpResponse<String> created =
                payment(token, body(methodId, "5.00", "USD"), someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(field(created.body(), "status")).isEqualTo("REQUIRES_CONFIRMATION");
        assertThat(created.body()).contains("\"failureReason\":null");
        String paymentId = field(created.body(), "id");

        double authorizedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedCardPspAdapter.RAIL.id(), "payment", "authorized");
        double capturedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedCardPspAdapter.RAIL.id(), "payment", "captured");
        double legacyCapturedBefore = com.finapp.app.telemetry.RailOutcomeCounts.attempt(meterRegistry, "captured");

        // The confirm answers the REAL state: authorized, then captured by the chained
        // command - SUCCEEDED, in one customer-visible call.
        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("SUCCEEDED");
        // Both judgements counted where they were written - the synchronous Tx2 and the
        // chained capture's - once each, on the card rail and in the legacy series
        // (P7-TSK-015: the doors count nothing any more).
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedCardPspAdapter.RAIL.id(), "payment", "authorized")
                        - authorizedBefore)
                .isEqualTo(1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedCardPspAdapter.RAIL.id(), "payment", "captured")
                        - capturedBefore)
                .isEqualTo(1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.attempt(meterRegistry, "captured") - legacyCapturedBefore)
                .isEqualTo(1);

        // The wallet balance moves - the payment IS the wallet's funding here, so the whole
        // settled balance is the captured amount (INV-BAL-02's replay agreeing is the
        // capture suite's proof; what this surface proves is the customer can SEE it).
        assertThat(get("/v1/me/accounts/" + product + "/balance", token).body())
                .contains("\"settled\":\"5.00\"");

        // The chain walked by stored identifier, both directions (plan section 12): intent ->
        // attempt (intent_id) -> journal entry (reference = attempt id) -> statement line
        // (entryId), and the entry's reference points back at the attempt.
        String attemptId = attemptIdFor(paymentId);
        String entryId = entryIdByReference(attemptId);
        String period = "?from=" + LocalDate.now(CLOCK).minusDays(1)
                + "&to=" + LocalDate.now(CLOCK).plusDays(1);
        String statement =
                get("/v1/me/accounts/" + product + "/statement" + period, token).body();
        assertThat(statement).contains(entryId).contains(attemptId);
        assertThat(lineFor(statement, entryId))
                .contains("\"direction\":\"CREDIT\"")
                .contains("\"amount\":\"5.00\"");

        // GET answers the current state by identifier.
        String read = get("/v1/payments/" + paymentId, token).body();
        assertThat(field(read, "status")).isEqualTo("SUCCEEDED");
        assertThat(field(read, "paymentMethodId")).isEqualTo(methodId);
    }

    @Test
    @DisplayName("a retried key replays the original judgement byte for byte - even after the"
            + " payment succeeded; a changed request is the distinct 409; keyless is the"
            + " interceptor's 422")
    void theIdempotencyContractHolds() throws Exception {
        providerAuthorises("psp_auth-replay");
        providerCaptures("psp_cap-replay");
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);
        String key = someKey();
        String request = body(methodId, "2.50", "USD");

        HttpResponse<String> first = payment(token, request, key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(field(first.body(), "status")).isEqualTo("REQUIRES_CONFIRMATION");
        String paymentId = field(first.body(), "id");

        // Confirm - the world moves on: the intent is now SUCCEEDED.
        assertThat(field(confirm(token, paymentId).body(), "status")).isEqualTo("SUCCEEDED");

        // Byte for byte (INV-IDEM-01): the retry learns what its request DID - the original
        // REQUIRES_CONFIRMATION judgement - not what the world looks like now. Rendering from
        // a re-read of the row would leak SUCCEEDED here (the named mutation).
        HttpResponse<String> retried = payment(token, request, key);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(retried.body()).isEqualTo(first.body());

        // The same key with a different amount is a materially different request (INV-IDEM-03).
        HttpResponse<String> changed = payment(token, body(methodId, "2.51", "USD"), key);
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("api.Conflict");

        HttpResponse<String> keyless = payment(token, request, null);
        assertThat(keyless.statusCode()).isEqualTo(422);
        assertThat(keyless.body()).contains("api.IdempotencyKeyRequired");
    }

    // -----------------------------------------------------------------
    // The asynchronous-outcome contract shape
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a lost authorization response is honestly PROCESSING - a 200 whose body says"
            + " the platform does not know yet, never an HTTP error")
    void aLostAuthorizationResponseIsHonestlyProcessing() throws Exception {
        provider.receivesTheRequestThenLosesTheResponse(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = attachInstrument(token);
        String paymentId =
                field(payment(token, body(methodId, "4.00", "USD"), someKey()).body(), "id");

        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("PROCESSING");

        // The honest state beneath: AUTH_UNKNOWN (INV-LIFE-03) - and nothing chained, nothing
        // posted, no balance invented.
        assertThat(attemptStatusFor(paymentId)).isEqualTo("AUTH_UNKNOWN");
        assertThat(journalEntriesByReference(attemptIdFor(paymentId))).isZero();
        assertThat(get("/v1/me/accounts/" + product + "/balance", token).body())
                .contains("\"settled\":\"0.00\"");
        assertThat(field(get("/v1/payments/" + paymentId, token).body(), "status"))
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("a lost capture response is honestly PROCESSING with nothing posted - the"
            + " authorization stood, the capture is CAPTURE_UNKNOWN")
    void aLostCaptureResponseIsHonestlyProcessing() throws Exception {
        providerAuthorises("psp_auth-ambig");
        provider.receivesTheRequestThenLosesTheResponse(SimulatedCardPspAdapter.CAPTURES_PATH);
        String token = verifiedCustomer(someLogin());
        String product = openAccount(token);
        String methodId = attachInstrument(token);
        String paymentId =
                field(payment(token, body(methodId, "6.00", "USD"), someKey()).body(), "id");

        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("PROCESSING");

        // Ambiguity posts NOTHING (the capture suite's proof, visible at the surface): the
        // attempt holds CAPTURE_UNKNOWN, the journal holds no entry, the balance holds still.
        assertThat(attemptStatusFor(paymentId)).isEqualTo("CAPTURE_UNKNOWN");
        assertThat(journalEntriesByReference(attemptIdFor(paymentId))).isZero();
        assertThat(get("/v1/me/accounts/" + product + "/balance", token).body())
                .contains("\"settled\":\"0.00\"");
    }

    @Test
    @DisplayName("a declined authorization is a 200 whose body says FAILED with the MAPPED"
            + " reason - and the provider's own vocabulary appears nowhere (INV-PAY-03)")
    void aDeclineIsTheMappedReasonNeverTheProvidersCode() throws Exception {
        // The provider's own decline code is planted in the stub; the contract must never
        // surface it - it lives in the retained evidence and nowhere else (the needle).
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"declined\",\"code\":\"do_not_honor_51\"}");
        String token = verifiedCustomer(someLogin());
        String methodId = attachInstrument(token);
        openAccount(token);
        String paymentId =
                field(payment(token, body(methodId, "3.00", "USD"), someKey()).body(), "id");

        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("FAILED");
        assertThat(field(confirmed.body(), "failureReason")).isEqualTo("DECLINED");
        assertThat(confirmed.body()).doesNotContain("do_not_honor");

        String read = get("/v1/payments/" + paymentId, token).body();
        assertThat(field(read, "status")).isEqualTo("FAILED");
        assertThat(field(read, "failureReason")).isEqualTo("DECLINED");
        assertThat(read).doesNotContain("do_not_honor");
    }

    // -----------------------------------------------------------------
    // The cancellation window
    // -----------------------------------------------------------------

    @Test
    @DisplayName("routing pins the confirm: rail out is a 422 recorded and retryable, rail"
            + " back and the SAME payment succeeds with its decision, its step trail and"
            + " RailSelected (P7-TSK-003, INV-RAIL-02, INV-HIST-04)")
    void routingPinsTheConfirm() throws Exception {
        providerAuthorises("psp_auth-routed");
        providerCaptures("psp_cap-routed");
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);
        HttpResponse<String> created = payment(token, body(methodId, "6.00", "USD"), someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        String paymentId = field(created.body(), "id");

        // The operator's recorded fact takes the one rail out; the confirm is refused with
        // the refusal RECORDED - a decision with no chosen rail - and the intent untouched,
        // deliberately retryable (ADR-0060 section 3). Written as the application role: the
        // availability row is the one mutable routing fact, and its route is
        // RoutingPolicyDatabaseTest's subject.
        recordAvailability(false, "endpoint suite outage");
        try {
            HttpResponse<String> refused = confirm(token, paymentId);
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(refused.body()).contains("payments.NoEligibleRail");
            assertThat(routingCount(
                            "SELECT count(*) FROM payments.routing_decision"
                                    + " WHERE intent_id = ? AND chosen_rail IS NULL",
                            UUID.fromString(paymentId)))
                    .isEqualTo(1);
            assertThat(oneString(
                            "SELECT status FROM payments.payment_intent WHERE id = ?",
                            UUID.fromString(paymentId)))
                    .isEqualTo("REQUIRES_CONFIRMATION");
            assertThat(oneString(
                            "SELECT s.rejection FROM payments.routing_decision_step s"
                                    + " JOIN payments.routing_decision d"
                                    + " ON d.id = s.decision_id"
                                    + " WHERE d.intent_id = ?",
                            UUID.fromString(paymentId)))
                    .isEqualTo("UNAVAILABLE");
            // ...and the refusal is on the record as the person's act (ADR-0060 section 3;
            // the Phase 7 review found it asserted nowhere): one PaymentRoutingRefused naming
            // the refused decision.
            assertThat(oneString(
                            "SELECT change_summary FROM platform.audit_record"
                                    + " WHERE operation = 'payments.PaymentRoutingRefused'"
                                    + " AND target_id = ?",
                            paymentId))
                    .contains("decision=RoutingDecisionId(" + oneString(
                            "SELECT id::text FROM payments.routing_decision"
                                    + " WHERE intent_id = ? AND chosen_rail IS NULL",
                            UUID.fromString(paymentId)) + ")");
        } finally {
            recordAvailability(true, "endpoint suite restore");
        }

        // The SAME confirm now succeeds, and the choice is pinned beside the dispatch: one
        // CHOSEN decision, the seeded version, the availability observation it used, the
        // declared descriptor version, and the decided fact published.
        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(field(confirmed.body(), "status")).isEqualTo("SUCCEEDED");

        String decisionId = oneString(
                "SELECT id::text FROM payments.routing_decision"
                        + " WHERE intent_id = ? AND chosen_rail = 'card'",
                UUID.fromString(paymentId));
        assertThat(oneString(
                        "SELECT v.version::text FROM payments.routing_decision d"
                                + " JOIN payments.routing_policy_version v"
                                + " ON v.id = d.policy_version_id WHERE d.id = ?",
                        UUID.fromString(decisionId)))
                .as("the pin resolves to the seeded version in force (INV-HIST-04) - version 4"
                        + " since P7-TSK-011 carried the standing routes forward beside the"
                        + " wallet pay-in (V019's whole-version seed; the newest-effective"
                        + " resolution is the design, and rule 0 is byte-for-byte V013's)")
                .isEqualTo("4");
        assertThat(oneString(
                        "SELECT verdict || '|' || rail_available || '|' || descriptor_version"
                                + " FROM payments.routing_decision_step WHERE decision_id = ?",
                        UUID.fromString(decisionId)))
                .isEqualTo("CHOSEN|true|1");
        assertThat(routingCount(
                        "SELECT count(*) FROM platform.outbox_event"
                                + " WHERE event_type = 'payments.RailSelected'"
                                + " AND aggregate_id = ?",
                        UUID.fromString(paymentId)))
                .isEqualTo(1);
        assertThat(oneString(
                        "SELECT change_summary FROM platform.audit_record"
                                + " WHERE operation = 'payments.PaymentConfirmed'"
                                + " AND target_id = ?",
                        paymentId))
                .as("the confirmation names the pin it dispatched under")
                .contains("policyVersion=4") // the seeded version in force since V019 (P7-TSK-011)
                .contains("decision=");

        // And the decision is frozen for every writer, the migrator included.
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement tamper = migrator.prepareStatement(
                        "UPDATE payments.routing_decision SET chosen_rail = NULL"
                                + " WHERE id = ?")) {
            tamper.setObject(1, UUID.fromString(decisionId));
            assertThatThrownBy(tamper::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .extracting(failure -> ((SQLException) failure).getSQLState())
                    .isEqualTo("P0001");
        }

        // RECOMPUTED FROM WHAT WAS STORED (the gate's routing-explainability bullet,
        // INV-RAIL-02, INV-HIST-04; P7-DOC-001 - recomputation was proven only in memory): each
        // stored decision, the refusal and the choice, re-decided under ITS pinned version over
        // ITS stored inputs and the availability each of its steps recorded, reproduces the
        // rail chosen, the rule matched and every rejection.
        try (Connection app = DatabaseRoles.application();
                PreparedStatement decisions = app.prepareStatement(
                        "SELECT id FROM payments.routing_decision WHERE intent_id = ?")) {
            decisions.setObject(1, UUID.fromString(paymentId));
            int recomputed = 0;
            try (ResultSet row = decisions.executeQuery()) {
                while (row.next()) {
                    assertRecomputesFromStorage(app, row.getObject(1, UUID.class));
                    recomputed++;
                }
            }
            assertThat(recomputed).as("the refused decision and the chosen one").isEqualTo(2);
        }

        // The explanation's success path, and the read on the record (A5's find: only its 404
        // and 403 were ever exercised).
        String operator = operatorSession();
        long reads = routingCount(
                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'payments.PaymentRoutingExplanationRead' AND target_id = ?",
                paymentId);
        HttpResponse<String> explained =
                get("/v1/operator/payments/" + paymentId + "/routing", operator);
        assertThat(explained.statusCode()).as(explained.body()).isEqualTo(200);
        assertThat(explained.body()).contains(decisionId).contains("card");
        assertThat(routingCount(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'payments.PaymentRoutingExplanationRead' AND target_id = ?",
                        paymentId))
                .isEqualTo(reads + 1);
    }

    /**
     * Re-decides the stored decision {@code decision} under its pinned version from storage
     * alone - inputs from its columns, availability from its steps - and compares plan to row.
     */
    private void assertRecomputesFromStorage(Connection app, UUID decision) throws Exception {
        com.finapp.payments.RoutingInputs inputs;
        java.util.Optional<Integer> matched;
        java.util.Optional<com.finapp.payments.RailId> chosen;
        com.finapp.payments.RoutingPolicyVersionId versionId;
        try (PreparedStatement read = app.prepareStatement(
                "SELECT direction, instrument_kind, amount_minor, currency, scale,"
                        + " matched_rule_index, chosen_rail, policy_version_id"
                        + " FROM payments.routing_decision WHERE id = ?")) {
            read.setObject(1, decision);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                inputs = new com.finapp.payments.RoutingInputs(
                        com.finapp.payments.PaymentDirection.valueOf(row.getString(1)),
                        com.finapp.payments.InstrumentKind.valueOf(row.getString(2)),
                        com.finapp.sharedkernel.money.Money.ofPersisted(
                                row.getLong(3),
                                com.finapp.sharedkernel.money.CurrencyCode.of(row.getString(4)),
                                row.getShort(5)),
                        // A pay-in has no destination to reach: not stored, never judged.
                        java.util.Optional.empty());
                matched = row.getObject(6) == null
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(row.getInt(6));
                chosen = java.util.Optional.ofNullable(row.getString(7))
                        .map(com.finapp.payments.RailId::of);
                versionId = com.finapp.payments.RoutingPolicyVersionId.of(
                        row.getObject(8, UUID.class));
            }
        }
        java.util.List<String> storedSteps = new java.util.ArrayList<>();
        java.util.Map<com.finapp.payments.RailId, com.finapp.payments.RailAvailability>
                recorded = new java.util.HashMap<>();
        try (PreparedStatement read = app.prepareStatement(
                "SELECT rail, verdict, rejection, rail_available, descriptor_version"
                        + " FROM payments.routing_decision_step WHERE decision_id = ?"
                        + " ORDER BY step_index")) {
            read.setObject(1, decision);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    storedSteps.add(row.getString(1) + "|" + row.getString(2) + "|"
                            + row.getString(3) + "|" + row.getBoolean(4) + "|"
                            + row.getObject(5));
                    com.finapp.payments.RailId rail =
                            com.finapp.payments.RailId.of(row.getString(1));
                    recorded.put(rail, new com.finapp.payments.RailAvailability(
                            rail, row.getBoolean(4), "as the step recorded it",
                            "recomputation", java.time.Instant.now(CLOCK)));
                }
            }
        }
        com.finapp.payments.RoutingPolicyVersion pinned =
                routingStore.findVersionById(app, versionId).orElseThrow();
        com.finapp.payments.RoutingPlan plan = pinned.decide(inputs, paymentRails, recorded);

        assertThat(plan.chosen()).as("decision %s: the rail chosen", decision).isEqualTo(chosen);
        assertThat(plan.matchedRuleIndex()).as("decision %s: the rule matched", decision)
                .isEqualTo(matched);
        assertThat(plan.steps().stream()
                        .map(step -> step.rail().value() + "|" + step.verdict() + "|"
                                + step.rejection().map(Enum::name).orElse(null) + "|"
                                + step.railAvailable() + "|"
                                + step.descriptorVersion().orElse(null))
                        .toList())
                .as("decision %s: every step and every rejection", decision)
                .containsExactlyElementsOf(storedSteps);
    }

    @Autowired private com.finapp.payments.RoutingStore<Connection> routingStore;
    @Autowired private com.finapp.payments.PaymentRails paymentRails;

    @Test
    @DisplayName("cancellation wins only the confirmation window: cancel converges, a cancelled"
            + " payment refuses confirmation, and a succeeded one refuses cancellation")
    void theCancellationWindowHolds() throws Exception {
        providerAuthorises("psp_auth-cancel");
        providerCaptures("psp_cap-cancel");
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);

        // Inside the window: cancelled, and a retried cancel converges on CANCELLED.
        String cancellable =
                field(payment(token, body(methodId, "1.00", "USD"), someKey()).body(), "id");
        HttpResponse<String> cancelled = delete(token, "/v1/payments/" + cancellable);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(field(cancelled.body(), "status")).isEqualTo("CANCELLED");
        HttpResponse<String> converged = delete(token, "/v1/payments/" + cancellable);
        assertThat(converged.statusCode()).isEqualTo(200);
        assertThat(field(converged.body(), "status")).isEqualTo("CANCELLED");

        // A cancelled payment refuses confirmation: the machine's 409, named for the check.
        HttpResponse<String> refusedConfirm = confirm(token, cancellable);
        assertThat(refusedConfirm.statusCode()).isEqualTo(409);
        assertThat(refusedConfirm.body()).contains("payments.NotConfirmable");

        // Past the window: the provider may already have acted, so cancel is the 409.
        String succeeded =
                field(payment(token, body(methodId, "2.00", "USD"), someKey()).body(), "id");
        assertThat(field(confirm(token, succeeded).body(), "status")).isEqualTo("SUCCEEDED");
        HttpResponse<String> refusedCancel = delete(token, "/v1/payments/" + succeeded);
        assertThat(refusedCancel.statusCode()).isEqualTo(409);
        assertThat(refusedCancel.body()).contains("payments.NotCancellable");
    }

    /**
     * The instrument interleaving the Phase 6 -> 7 transition left unraced, paid by the Phase 7
     * review. The confirmation resolves the instrument at the act - one read inside its Tx1 -
     * and a detach is a prospective registry change with no rule about payments in flight, so
     * the two have exactly two serial orders and no arbiter between them. Both are forced: a
     * detach that lands BETWEEN the act's read and its commit (the intent row held, the confirm
     * observed waiting on it) leaves a payment confirmed on the instrument it resolved, charged
     * exactly once; a detach that lands first refuses the confirm with nothing written, the
     * intent still cancellable, and every later use of the method refused.
     */
    @Test
    @DisplayName("a detach racing a confirmation: judged at the act - landing mid-confirmation it"
            + " charges once on the instrument resolved, landing first it refuses with nothing"
            + " written")
    void aDetachRacingAConfirmationIsJudgedAtTheAct() throws Exception {
        providerAuthorises("psp_auth-detach");
        providerCaptures("psp_cap-detach");
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);
        String midway =
                field(payment(token, body(methodId, "3.00", "USD"), someKey()).body(), "id");
        String later =
                field(payment(token, body(methodId, "4.00", "USD"), someKey()).body(), "id");

        // MID-CONFIRMATION: the confirm has read the instrument ACTIVE and routed; the held
        // intent row stops it at the conditional transition while the detach commits.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement(
                            "SELECT id FROM payments.payment_intent WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(midway));
                lock.executeQuery().close();
            }
            java.util.concurrent.Future<HttpResponse<String>> confirming =
                    pool.submit(() -> confirm(token, midway));
            awaitLockWaitOn("payment_intent", "SET status");
            HttpResponse<String> detached = delete(token, "/v1/me/payment-methods/" + methodId);
            assertThat(detached.statusCode()).as(detached.body()).isEqualTo(204);
            holder.rollback();

            HttpResponse<String> confirmed =
                    confirming.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
            assertThat(field(confirmed.body(), "status")).isEqualTo("SUCCEEDED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH))
                .as("the one payment the act admitted, sent once")
                .isEqualTo(1);
        assertThat(routingCount(
                        "SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?",
                        UUID.fromString(midway)))
                .isEqualTo(1);
        assertThat(routingCount(
                        "SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                        "ledger.post:payment-capture:" + attemptIdFor(midway)))
                .as("the capture posted once")
                .isEqualTo(1);

        // DETACH FIRST: the confirm of an intent created while the method was live refuses
        // with nothing written - no attempt, no decision, nothing sent - and the intent still
        // awaits confirmation, so the customer can cancel it.
        HttpResponse<String> refused = confirm(token, later);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("payments.UnknownInstrument");
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH))
                .isEqualTo(1);
        assertThat(routingCount(
                        "SELECT count(*) FROM payments.payment_attempt WHERE intent_id = ?",
                        UUID.fromString(later)))
                .isZero();
        assertThat(routingCount(
                        "SELECT count(*) FROM payments.routing_decision WHERE intent_id = ?",
                        UUID.fromString(later)))
                .isZero();
        assertThat(oneString(
                        "SELECT status FROM payments.payment_intent WHERE id = ?",
                        UUID.fromString(later)))
                .isEqualTo("REQUIRES_CONFIRMATION");
        HttpResponse<String> cancelled = delete(token, "/v1/payments/" + later);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(field(cancelled.body(), "status")).isEqualTo("CANCELLED");

        // And the detached method opens no new payment.
        HttpResponse<String> reuse = payment(token, body(methodId, "5.00", "USD"), someKey());
        assertThat(reuse.statusCode()).as(reuse.body()).isEqualTo(422);
        assertThat(reuse.body()).contains("payments.UnknownInstrument");
    }

    /**
     * INV-PAY-02's Verify clause over the WHOLE schema set, Phase 7's tables included (the Phase
     * 7 review: the per-table sweep covered the payment-method row alone). A card payment runs
     * end to end - attach, route, authorize, capture - and then every text-like column of every
     * base table in every schema is read: the client's tokenisation grant appears in none, and
     * the provider's token in exactly one, the payment method's own reference. Encrypted
     * columns (bytea) are out of the sweep by construction: the retained provider answers and
     * dispute evidence are ciphertext under their own keys (INV-KYC-06's regime).
     */
    @Test
    @DisplayName("INV-PAY-02, swept over every column of every schema after a routed card payment:"
            + " the grant rests nowhere, the token only on its payment method")
    void instrumentInputRestsNowhereButItsReference() throws Exception {
        providerAuthorises("psp_auth-sweep");
        providerCaptures("psp_cap-sweep");
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String grant = "ctok_sweep" + suffix;
        String instrument = "tok_sweep" + suffix;
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"" + instrument + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached =
                post("/v1/me/payment-methods", "{\"clientToken\":\"" + grant + "\"}", token,
                        false);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        String methodId = field(attached.body(), "id");
        String paymentId =
                field(payment(token, body(methodId, "6.00", "USD"), someKey()).body(), "id");
        HttpResponse<String> confirmed = confirm(token, paymentId);
        assertThat(field(confirmed.body(), "status")).isEqualTo("SUCCEEDED");
        assertThat(attached.body() + confirmed.body()).doesNotContain(grant).doesNotContain(instrument);
        // The positive control: the token DID travel - to the provider, the one place it must.
        assertThat(provider.bodyValues(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH))
                .anySatisfy(sent -> assertThat(sent).contains(instrument));

        assertThat(columnsHolding(grant)).as("the grant is spent at the boundary").isEmpty();
        assertThat(columnsHolding(instrument))
                .as("the token rests on its payment method and nowhere else")
                .containsExactly("paymentmethods.payment_method.token_reference");
    }

    /** Every {@code schema.table.column} of a text-like type in a base table holding {@code needle}. */
    private static List<String> columnsHolding(String needle) throws SQLException {
        List<String> holding = new java.util.ArrayList<>();
        try (Connection root = DatabaseRoles.bootstrap()) {
            List<String[]> columns = new java.util.ArrayList<>();
            try (PreparedStatement query =
                    root.prepareStatement(
                            "SELECT c.table_schema, c.table_name, c.column_name"
                                    + " FROM information_schema.columns c"
                                    + " JOIN information_schema.tables t"
                                    + "   ON t.table_schema = c.table_schema"
                                    + "  AND t.table_name = c.table_name"
                                    + " WHERE t.table_type = 'BASE TABLE'"
                                    + "   AND c.table_schema NOT IN ('pg_catalog',"
                                    + "       'information_schema', 'pg_toast')"
                                    + "   AND c.data_type IN ('text', 'character varying',"
                                    + "       'character', 'json', 'jsonb')"
                                    + " ORDER BY 1, 2, 3");
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.add(new String[] {rows.getString(1), rows.getString(2),
                            rows.getString(3)});
                }
            }
            assertThat(columns).as("the derived sweep has subjects").hasSizeGreaterThan(100);
            for (String[] column : columns) {
                String qualified = column[0] + "." + column[1] + "." + column[2];
                try (PreparedStatement probe =
                        root.prepareStatement(
                                "SELECT EXISTS (SELECT 1 FROM \"" + column[0] + "\".\""
                                        + column[1] + "\" WHERE \"" + column[2]
                                        + "\"::text LIKE ?)")) {
                    probe.setString(1, "%" + needle + "%");
                    try (ResultSet found = probe.executeQuery()) {
                        found.next();
                        if (found.getBoolean(1)) {
                            holding.add(qualified);
                        }
                    }
                }
            }
        }
        return holding;
    }

    /** Until a backend waits on a lock for a statement touching {@code table} with {@code marker}. */
    private static void awaitLockWaitOn(String table, String marker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (Connection observer = DatabaseRoles.application();
                PreparedStatement select =
                        observer.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE wait_event_type = 'Lock'"
                                        + " AND query LIKE ? AND query LIKE ?")) {
            select.setString(1, "%" + table + "%");
            select.setString(2, "%" + marker + "%");
            while (System.nanoTime() < deadline) {
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= 1) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError(
                "no statement ever waited on " + table + " " + marker
                        + " - the interleaving this test forces never happened");
    }

    @Test
    @DisplayName("cancelling a resting AUTHORIZED payment voids it at the provider: the"
            + " promise released, the surface honest, the retry convergent (P7-TSK-004)")
    void cancellingAnAuthorizedPaymentReleasesThePromise() throws Exception {
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);
        String id = restingAuthorizedPayment(token, methodId);

        provider.succeedsWith(
                SimulatedCardPspAdapter.VOIDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_void-cancel\"}");
        double voidedBefore =
                com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedCardPspAdapter.RAIL.id(), "payment", "voided");
        double legacyVoidedBefore = com.finapp.app.telemetry.RailOutcomeCounts.attempt(meterRegistry, "voided");
        HttpResponse<String> cancelled = delete(token, "/v1/payments/" + id);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(field(cancelled.body(), "status")).isEqualTo("FAILED");
        // The void's judgement - counted NOWHERE until P7-TSK-015 - once.
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedCardPspAdapter.RAIL.id(), "payment", "voided")
                        - voidedBefore)
                .isEqualTo(1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.attempt(meterRegistry, "voided") - legacyVoidedBefore)
                .isEqualTo(1);

        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status, void_provider_reference FROM"
                                        + " payments.payment_attempt WHERE intent_id = ?")) {
            read.setObject(1, UUID.fromString(id));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("VOIDED");
                assertThat(row.getString("void_provider_reference"))
                        .isEqualTo("psp_void-cancel");
            }
        }

        // The retry converges on the released state - same 200, same truth, and nothing is
        // sent twice (INV-PAY-04: the machine is the idempotency, no key needed).
        HttpResponse<String> retried = delete(token, "/v1/payments/" + id);
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(field(retried.body(), "status")).isEqualTo("FAILED");
        assertThat(provider.requestCount(SimulatedCardPspAdapter.VOIDS_PATH)).isEqualTo(1);
        assertThat(com.finapp.app.telemetry.RailOutcomeCounts.railOutcome(meterRegistry, com.finapp.payments.SimulatedCardPspAdapter.RAIL.id(), "payment", "voided")
                        - voidedBefore)
                .as("the converged retry judged nothing, so counted nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the operator void surface: PAYMENT_REFUND's holder releases a resting"
            + " authorization with the reason recorded verbatim; the customer is 403;"
            + " unknown and malformed are one 404; a succeeded payment is the 409")
    void theOperatorVoidSurfaceHolds() throws Exception {
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);
        String id = restingAuthorizedPayment(token, methodId);
        String operator = operatorSession();

        // The customer holds no PAYMENT_REFUND: the wall answers before the machine.
        assertThat(
                        post("/v1/payments/" + id + "/void",
                                        "{\"reason\":\"sneaky\"}", token, false)
                                .statusCode())
                .isEqualTo(403);

        provider.succeedsWith(
                SimulatedCardPspAdapter.VOIDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_void-op\"}");
        String reason = "authorization stranded by a crashed capture chain";
        HttpResponse<String> voided =
                post("/v1/payments/" + id + "/void",
                        "{\"reason\":\"" + reason + "\"}", operator, false);
        assertThat(voided.statusCode()).as(voided.body()).isEqualTo(200);
        assertThat(field(voided.body(), "status")).isEqualTo("FAILED");

        // The operator's words, verbatim, in the regulator-grade record (INV-AUD-03).
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT reason FROM platform.audit_record"
                                        + " WHERE operation ="
                                        + " 'payments.PaymentVoidDispatched'"
                                        + " AND target_id = ?")) {
            read.setString(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("reason")).isEqualTo(reason);
            }
        }

        // Unknown and malformed are one 404 - nothing about existence leaks on this wall.
        assertThat(
                        post("/v1/payments/" + UUID.randomUUID() + "/void",
                                        "{\"reason\":\"gone\"}", operator, false)
                                .statusCode())
                .isEqualTo(404);
        assertThat(
                        post("/v1/payments/not-a-uuid/void",
                                        "{\"reason\":\"gone\"}", operator, false)
                                .statusCode())
                .isEqualTo(404);

        // A captured payment cannot be voided - the machine's 409; money that moved is the
        // refund's territory (INV-REV-03's two halves stay distinct).
        providerAuthorises("psp_auth-succeeded");
        providerCaptures("psp_cap-succeeded");
        String succeeded =
                field(payment(token, body(methodId, "2.00", "USD"), someKey()).body(), "id");
        assertThat(field(confirm(token, succeeded).body(), "status")).isEqualTo("SUCCEEDED");
        HttpResponse<String> refused =
                post("/v1/payments/" + succeeded + "/void",
                        "{\"reason\":\"too late\"}", operator, false);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("payments.NotCancellable");
    }

    // -----------------------------------------------------------------
    // Disclosure
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a stranger's payment id, an unknown one and a malformed one are one 404 -"
            + " on the GET, the confirmation and the cancellation alike")
    void aStrangersPaymentIdIsOne404() throws Exception {
        String owner = verifiedCustomer(someLogin());
        String stranger = verifiedCustomer(someLogin());
        openAccount(owner);
        String methodId = attachInstrument(owner);
        String paymentId =
                field(payment(owner, body(methodId, "1.00", "USD"), someKey()).body(), "id");

        for (String path :
                List.of(
                        "/v1/payments/" + paymentId,
                        "/v1/payments/" + UUID.randomUUID(),
                        "/v1/payments/not-a-uuid")) {
            HttpResponse<String> viaGet = get(path, stranger);
            HttpResponse<String> viaConfirm = post(path + "/confirmation", null, stranger, false);
            HttpResponse<String> viaCancel = delete(stranger, path);
            assertThat(viaGet.statusCode()).as(path).isEqualTo(404);
            assertThat(viaConfirm.statusCode()).as(path).isEqualTo(404);
            assertThat(viaCancel.statusCode()).as(path).isEqualTo(404);
            // The equality between the causes (the P1-TSK-016 idiom): party_id = ? in the
            // statement is the ownership check, and no cause is readable from the response.
            assertThat(normalized(viaGet.body()))
                    .isEqualTo(normalized(get("/v1/payments/" + UUID.randomUUID(), stranger)
                            .body()));
        }

        assertThat(get("/v1/payments/" + paymentId, owner).statusCode())
                .as("the positive control: the owner reads their own payment")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("every instrument refusal is one byte-identical 422 - unknown, a stranger's"
            + " and malformed alike - with nothing written; no-wallet and currency mismatch"
            + " are their own named refusals")
    void createRefusalsDiscloseNothing() throws Exception {
        String token = verifiedCustomer(someLogin());
        String stranger = verifiedCustomer(someLogin());
        openAccount(token);
        String strangersMethod = attachInstrument(stranger);

        long before = intentCount();
        String reference = null;
        for (String methodId :
                List.of(UUID.randomUUID().toString(), strangersMethod, "not-a-uuid")) {
            HttpResponse<String> refusal = payment(token, body(methodId, "1.00", "USD"),
                    someKey());
            assertThat(refusal.statusCode()).isEqualTo(422);
            assertThat(refusal.body()).contains("payments.UnknownInstrument");
            if (reference == null) {
                reference = normalized(refusal.body());
            } else {
                assertThat(normalized(refusal.body())).isEqualTo(reference);
            }
        }

        // The caller's own wallet in another currency: the named mismatch.
        String ownMethod = attachInstrument(token);
        HttpResponse<String> mismatch = payment(token, body(ownMethod, "1.00", "EUR"), someKey());
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(mismatch.body()).contains("payments.CurrencyMismatch");

        // No wallet at all: the caller's own standing, named.
        String walletless = verifiedCustomer(someLogin());
        String walletlessMethod = attachInstrument(walletless);
        HttpResponse<String> noWallet =
                payment(walletless, body(walletlessMethod, "1.00", "USD"), someKey());
        assertThat(noWallet.statusCode()).isEqualTo(422);
        assertThat(noWallet.body()).contains("payments.NoWallet");

        assertThat(intentCount()).as("a boundary refusal writes nothing").isEqualTo(before);
    }

    @Test
    @DisplayName("the list shows only the caller's payments, newest first")
    void theListShowsOnlyTheCallersPaymentsNewestFirst() throws Exception {
        String a = verifiedCustomer(someLogin());
        String b = verifiedCustomer(someLogin());
        openAccount(a);
        openAccount(b);
        String aMethod = attachInstrument(a);
        String bMethod = attachInstrument(b);

        String first = field(payment(a, body(aMethod, "1.00", "USD"), someKey()).body(), "id");
        String second = field(payment(a, body(aMethod, "2.00", "USD"), someKey()).body(), "id");
        String theirs = field(payment(b, body(bMethod, "1.00", "USD"), someKey()).body(), "id");

        String list = get("/v1/payments", a).body();
        assertThat(list).contains(first).contains(second).doesNotContain(theirs);
        assertThat(list.indexOf(second))
                .as("newest first (plan section 9)")
                .isLessThan(list.indexOf(first));
    }

    @Test
    @DisplayName("no body shape is a 500")
    void noBodyShapeIsA500() throws Exception {
        String token = verifiedCustomer(someLogin());
        openAccount(token);
        String methodId = attachInstrument(token);

        List<String> shapes =
                List.of(
                        "{}",
                        body(methodId, "abc", "USD"),
                        // Inexact at the currency's scale: refused, never rounded (INV-MON-03).
                        body(methodId, "1.234", "USD"),
                        body(methodId, "-5.00", "USD"),
                        body(methodId, "0", "USD"),
                        body(methodId, "1.00", "usd"),
                        // ISO but no minor unit: an amount cannot be expressed in XXX.
                        body(methodId, "1.00", "XXX"),
                        "{\"paymentMethodId\":12345,\"amount\":\"1.00\",\"currency\":\"USD\"}",
                        "{\"paymentMethodId\":{\"a\":1},\"amount\":\"1.00\","
                                + "\"currency\":\"USD\"}",
                        "[1,2,3]",
                        "not json at all");
        for (String shape : shapes) {
            HttpResponse<String> response = payment(token, shape, someKey());
            assertThat(response.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isGreaterThanOrEqualTo(400)
                    .isLessThan(500);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /** Registers over HTTP, promotes the customer to {@code ACTIVE}, returns a session token. */
    private String verifiedCustomer(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE party.customer SET status = 'ACTIVE',"
                            + " status_changed_at = GREATEST(now(), opened_at)"
                            + " WHERE party_id ="
                            + " (SELECT party_id FROM identity.identity"
                            + "   WHERE login_identifier = ?)",
                    login);
        }
        return tokenFrom(authenticate(login).body());
    }

    /** Opens a USD wallet over the real endpoint; returns the product identifier. */
    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened =
                post(
                        "/v1/me/accounts",
                        "{\"productType\":\"WALLET\",\"currency\":\"USD\"}",
                        token,
                        true);
        assertThat(opened.statusCode()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    /** Attaches an instrument through the real tokenisation flow; returns the method id. */
    private String attachInstrument(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix + "\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");
        HttpResponse<String> attached =
                post(
                        "/v1/me/payment-methods",
                        "{\"clientToken\":\"ctok_" + suffix + "\"}",
                        token,
                        false);
        assertThat(attached.statusCode()).isEqualTo(201);
        return field(attached.body(), "id");
    }

    private static void providerAuthorises(String pspReference) {
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
    }

    private static void providerCaptures(String pspReference) {
        provider.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private static String body(String methodId, String amount, String currency) {
        return "{\"paymentMethodId\":\"" + methodId + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"" + currency + "\"}";
    }

    private HttpResponse<String> payment(String token, String body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/payments"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
        }
        return send(request.build());
    }

    private HttpResponse<String> confirm(String token, String paymentId) throws Exception {
        return post("/v1/payments/" + paymentId + "/confirmation", null, token, false);
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                true);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                false);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return send(request);
    }

    private HttpResponse<String> delete(String token, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .DELETE()
                        .build();
        return send(request);
    }

    private HttpResponse<String> post(
            String path, String body, String token, boolean idempotencyKey) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey) {
            request.header(IdempotencyKeyHeader.NAME, someKey());
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // -----------------------------------------------------------------
    // Parsing and counters
    // -----------------------------------------------------------------

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    /** The statement line object carrying {@code entryId} — bounded by its braces. */
    private static String lineFor(String statementBody, String entryId) {
        int at = statementBody.indexOf(entryId);
        assertThat(at).as("the statement must carry entry %s", entryId).isNotNegative();
        int start = statementBody.lastIndexOf('{', at);
        int end = statementBody.indexOf('}', at);
        return statementBody.substring(start, end + 1);
    }

    /**
     * The correlation identifier differs per request by design, and {@code instance} is the
     * caller's own request path echoed back. Everything else must be byte-identical.
     */
    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
    }

    private static String attemptIdFor(String paymentId) throws SQLException {
        return oneString(
                "SELECT id::text FROM payments.payment_attempt WHERE intent_id = ?",
                UUID.fromString(paymentId));
    }

    private static String attemptStatusFor(String paymentId) throws SQLException {
        return oneString(
                "SELECT status FROM payments.payment_attempt WHERE intent_id = ?",
                UUID.fromString(paymentId));
    }

    private static String entryIdByReference(String reference) throws SQLException {
        return oneString(
                "SELECT id::text FROM ledger.journal_entry WHERE reference = ?", reference);
    }

    private static long journalEntriesByReference(String reference) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT count(*) FROM ledger.journal_entry"
                                        + " WHERE reference = ?")) {
            read.setString(1, reference);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long intentCount() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement("SELECT count(*) FROM payments.payment_intent")) {
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    /** The one mutable routing fact, written as the application role (`P7-TSK-003`). */
    private static void recordAvailability(boolean available, String reason)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement upsert = app.prepareStatement(
                        "INSERT INTO payments.rail_availability (rail, available, reason,"
                                + " changed_by, changed_at)"
                                + " VALUES ('card', ?, ?, 'endpoint-suite', now())"
                                + " ON CONFLICT (rail) DO UPDATE SET"
                                + " available = EXCLUDED.available,"
                                + " reason = EXCLUDED.reason,"
                                + " changed_by = EXCLUDED.changed_by,"
                                + " changed_at = EXCLUDED.changed_at")) {
            upsert.setBoolean(1, available);
            upsert.setString(2, reason);
            upsert.executeUpdate();
        }
    }

    private static long routingCount(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static String oneString(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("one row for: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    /**
     * The crash window, honestly produced: the confirmation COMMAND commits {@code
     * AUTHORIZED} and this "process" goes no further - the resting state the
     * stranded-authorization sweep leg exists for, and the only reachable one until
     * {@code CaptureMode.MANUAL}'s producer arrives (its javadoc records that). The
     * endpoint's own confirm would chain the capture past it.
     */
    private String restingAuthorizedPayment(String token, String methodId) throws Exception {
        // Unique per call: auth_provider_reference is UNIQUE platform-wide, and this
        // battery shares one database across suites and tests.
        providerAuthorises(
                "psp_auth-rest-" + UUID.randomUUID().toString().substring(0, 12));
        String id = field(payment(token, body(methodId, "3.00", "USD"), someKey()).body(), "id");
        UUID party;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT party_id FROM payments.payment_intent"
                                        + " WHERE id = ?")) {
            read.setObject(1, UUID.fromString(id));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                party = row.getObject("party_id", UUID.class);
            }
        }
        try (SecurityContext.Scope actor =
                        SecurityContext.enter(
                                new Actor(party.toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(
                                        CorrelationId.of("rest-" + UUID.randomUUID())))) {
            confirmation.confirm(party, PaymentIntentId.of(UUID.fromString(id)));
        }
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM payments.payment_attempt"
                                        + " WHERE intent_id = ?")) {
            read.setObject(1, UUID.fromString(id));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("AUTHORIZED");
            }
        }
        provider.reset();
        return id;
    }

    /** A LEDGER_OPERATOR session - the refund surface's holder (the routing suite's idiom). */
    private String operatorSession() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity"
                                        + " WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(
                                        CorrelationId.of("grant-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app,
                    com.finapp.identity.IdentityId.of(identity),
                    com.finapp.identity.RoleName.LEDGER_OPERATOR,
                    com.finapp.identity.IdentityId.of(identity),
                    "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    private static String someLogin() {
        return "payer." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
