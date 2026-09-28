package com.finapp.app.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.TotpParameters;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The bank-account register over real HTTP (`P7-TSK-007`, ADR-0062 §2): the grant exchange
 * against the REAL {@link SimulatedInstantSchemeAdapter} wired through the deployment's own
 * property onto a {@link SimulatedProvider} stub — the whole chain on the path
 * (controller → keyed service → PushRail → wire → store).
 *
 * <p>What this suite owns: the keyed door's replay <strong>without a second exchange</strong>
 * (the grant is single-use at the provider, so the claim IS the retry story — the card
 * suite's convergence-by-re-exchange deliberately cannot exist here), the {@code NO_MATCH}
 * consent gate refused on the record and satisfied with the audit reason, the step-up
 * refusal leaving the KEY unburned (Tx1's claim rolls back with the refusal), the honest
 * refusal/outage answers, and the {@code INV-RAIL-03} needle over every queryable sink —
 * responses, audit rows, event payloads (the renderings' masking is proven at the type
 * rank, {@code DestinationReferenceTest}).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the bank-account register and its keyed door (P7-TSK-007)")
class BankAccountRegistrationDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    private static SimulatedProvider scheme;

    @LocalServerPort private int port;

    @BeforeAll
    static void startScheme() {
        if (scheme == null) {
            scheme = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopScheme() {
        scheme.close();
    }

    @DynamicPropertySource
    static void schemeUrl(DynamicPropertyRegistry registry) {
        if (scheme == null) {
            scheme = SimulatedProvider.start();
        }
        // The deployment's own property; the key stays the marked-local default the bean
        // recognises (the ConfinedCredential local licence).
        registry.add("finapp.payments.instant.url", () -> scheme.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        scheme.reset();
    }

    /** Stubs the exchange to link onto {@code destination} with {@code payee}'s word. */
    private static void schemeExchanges(String destination, String payee) {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\""
                        + destination
                        + "\",\"suffix\":\"6819\",\"payee\":\""
                        + payee
                        + "\"}");
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the acceptance chain holds: registered 201 with scheme-sourced suffix and"
            + " payee word, listed beside nothing card-shaped, detached with evidence - and"
            + " the destination in NO queryable sink (INV-RAIL-03)")
    void theRegistrationChainHolds() throws Exception {
        String needle = "dest-needle-" + suffix();
        schemeExchanges(needle, "close_match");
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);

        HttpResponse<String> registered =
                registerBankAccount(token, "blg-chain-" + suffix(), false, UUID.randomUUID());
        assertThat(registered.statusCode()).isEqualTo(201);
        String methodId = field(registered.body(), "id");
        assertThat(field(registered.body(), "kind")).isEqualTo("BANK_ACCOUNT");
        assertThat(field(registered.body(), "displaySuffix")).isEqualTo("6819");
        assertThat(field(registered.body(), "payeeCheck")).isEqualTo("CLOSE_MATCH");
        // The card facts a bank account honestly lacks are null, never invented.
        assertThat(registered.body())
                .contains("\"brand\":null")
                .contains("\"expiryMonth\":null")
                .contains("\"expiryYear\":null");

        // THE NEEDLE (INV-RAIL-03): the destination is in no response, no audit row, no
        // event payload - only its column holds it.
        assertThat(registered.body()).doesNotContain(needle);
        HttpResponse<String> listed = get("/v1/me/payment-methods", token);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(methodId).doesNotContain(needle);
        assertThat(auditNeedleCount(needle)).isZero();
        for (String payload : eventPayloads("paymentmethods.PaymentMethodAttached", methodId)) {
            assertThat(payload).doesNotContain(needle);
            assertThat(payload)
                    .as("the payload carries the kind, enumerated (INV-AUD-02)")
                    .contains("\"kind\":\"BANK_ACCOUNT\"");
        }
        assertThat(destinationOf(methodId)).isEqualTo(needle);

        List<AuditRow> added = auditRecords("paymentmethods.PaymentMethodAttached", methodId);
        assertThat(added).hasSize(1);
        assertThat(added.getFirst().reason())
                .as("no consent was the gate here, so no reason rides the record")
                .isNull();

        // The shared detach door, kind-agnostic: 204, evidence survives.
        assertThat(delete(token, methodId).statusCode()).isEqualTo(204);
        assertThat(paymentMethodRowsOf(party)).containsExactly("DETACHED");
    }

    @Test
    @DisplayName("a retried key replays the recorded 201 without a second exchange - the"
            + " single-use grant's whole retry story (INV-IDEM-01)")
    void aRetriedKeyReplaysWithoutASecondExchange() throws Exception {
        schemeExchanges("dest-replay-" + suffix(), "match");
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);
        String grant = "blg-replay-" + suffix();
        UUID key = UUID.randomUUID();

        HttpResponse<String> first = registerBankAccount(token, grant, false, key);
        assertThat(first.statusCode()).isEqualTo(201);
        String methodId = field(first.body(), "id");

        HttpResponse<String> retried = registerBankAccount(token, grant, false, key);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(field(retried.body(), "id")).isEqualTo(methodId);
        assertThat(scheme.requestCount(SimulatedInstantSchemeAdapter.EXCHANGES_PATH))
                .as("the replay answers the claim; the spent grant is never re-exchanged")
                .isEqualTo(1);
        assertThat(paymentMethodRowsOf(party)).containsExactly("ACTIVE");
        assertThat(auditRecords("paymentmethods.PaymentMethodAttached", methodId)).hasSize(1);

        // A reused key for a DIFFERENT request is the distinct refusal (INV-IDEM-03), and
        // it neither exchanges nor writes.
        HttpResponse<String> conflicting =
                registerBankAccount(token, "blg-other-" + suffix(), false, key);
        assertThat(conflicting.statusCode()).isEqualTo(409);
        assertThat(scheme.requestCount(SimulatedInstantSchemeAdapter.EXCHANGES_PATH))
                .isEqualTo(1);
        assertThat(paymentMethodRowsOf(party)).containsExactly("ACTIVE");
    }

    @Test
    @DisplayName("an unacknowledged NO_MATCH is refused ON THE RECORD - 409, nothing stored,"
            + " the refusal replayed - and the acknowledged register carries the audit reason")
    void theNoMatchConsentGateHolds() throws Exception {
        String needle = "dest-nomatch-" + suffix();
        schemeExchanges(needle, "no_match");
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);
        UUID refusedKey = UUID.randomUUID();

        HttpResponse<String> refused =
                registerBankAccount(token, "blg-nm-" + suffix(), false, refusedKey);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("paymentmethods.PayeeCheckNoMatch");
        assertThat(paymentMethodRowsOf(party))
                .as("nothing of the mismatch is retained (INV-RAIL-03)")
                .isEmpty();
        assertThat(auditNeedleCount(needle)).isZero();

        // The refusal is a recorded outcome: the same key answers it again, wire untouched.
        HttpResponse<String> replayed =
                registerBankAccount(token, "blg-nm2-" + suffix(), false, refusedKey);
        assertThat(replayed.statusCode()).isIn(409, 422);
        assertThat(scheme.requestCount(SimulatedInstantSchemeAdapter.EXCHANGES_PATH))
                .as("one exchange, however often the refused key returns")
                .isEqualTo(1);

        // The customer saw the mismatch, linked again, and says yes anyway: a NEW request
        // under a NEW key with a fresh grant - the recorded consent flow.
        HttpResponse<String> acknowledged =
                registerBankAccount(token, "blg-nm3-" + suffix(), true, UUID.randomUUID());
        assertThat(acknowledged.statusCode()).isEqualTo(201);
        String methodId = field(acknowledged.body(), "id");
        assertThat(field(acknowledged.body(), "payeeCheck")).isEqualTo("NO_MATCH");
        assertThat(acknowledgedAtOf(methodId)).as("the consent instant is the row's").isNotNull();

        List<AuditRow> added = auditRecords("paymentmethods.PaymentMethodAttached", methodId);
        assertThat(added).hasSize(1);
        assertThat(added.getFirst().reason())
                .as("consent was the gate, and the trail says so - enumerated (INV-AUD-02)")
                .isEqualTo("PAYEE_CHECK_NO_MATCH_ACKNOWLEDGED");
    }

    @Test
    @DisplayName("a refused grant is the actionable 422; an outage the honest 503; each"
            + " recorded, each writing nothing")
    void refusalAndOutageAreHonestAndRecorded() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);

        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200, "{\"status\":\"refused\"}");
        HttpResponse<String> refused =
                registerBankAccount(token, "blg-spent-" + suffix(), false, UUID.randomUUID());
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("paymentmethods.GrantExchangeRefused");

        scheme.isUnavailable(SimulatedInstantSchemeAdapter.EXCHANGES_PATH);
        UUID outageKey = UUID.randomUUID();
        HttpResponse<String> outage =
                registerBankAccount(token, "blg-outage-" + suffix(), false, outageKey);
        assertThat(outage.statusCode()).isEqualTo(503);
        assertThat(outage.body()).contains("paymentmethods.GrantExchangeUnavailable");
        long exchangesSoFar =
                scheme.requestCount(SimulatedInstantSchemeAdapter.EXCHANGES_PATH);

        // The outage's outcome is recorded too: the retried key replays the 503 without
        // asking the wire whether the grant survived - a NEW key (and, if the first send
        // spent the grant, a fresh grant) is the recorded retry story.
        HttpResponse<String> replayedOutage =
                registerBankAccount(token, "blg-outage2-" + suffix(), false, outageKey);
        assertThat(replayedOutage.statusCode()).isIn(409, 503);
        assertThat(scheme.requestCount(SimulatedInstantSchemeAdapter.EXCHANGES_PATH))
                .isEqualTo(exchangesSoFar);
        assertThat(paymentMethodRowsOf(party)).isEmpty();
    }

    @Test
    @DisplayName("a grant that could steer the provider's request - a quote, a backslash, a"
            + " duplicate key - or that takes a bank identifier's shape is refused at the"
            + " boundary with NOTHING sent (the Phase 7 -> 8 transition, INV-RAIL-03)")
    void aHostileGrantNeverReachesTheProvider() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200, "{\"status\":\"refused\"}");

        // Each value is written as its JSON string literal - what the customer's client sends.
        for (String grantLiteral :
                java.util.List.of(
                        "\"g\\\",\\\"endToEndReference\\\":\\\"x\\\",\\\"scope\\\":\\\"all\"",
                        "\"blg-\\\\n-" + suffix() + "\"",
                        "\"blg- " + suffix() + "\"",
                        "\"DE89370400440532013000\"",
                        "\"12345678\"",
                        "\"" + "a".repeat(129) + "\"")) {
            HttpResponse<String> refused =
                    post(
                            "/v1/me/payment-methods/bank-accounts",
                            "{\"grant\":" + grantLiteral + ",\"acknowledgeNoMatch\":false}",
                            token,
                            UUID.randomUUID());
            assertThat(refused.statusCode()).as(grantLiteral).isEqualTo(422);
            assertThat(refused.body()).as(grantLiteral).contains("grant");
        }
        assertThat(scheme.requestCount(SimulatedInstantSchemeAdapter.EXCHANGES_PATH))
                .as("no hostile grant reached the provider's wire")
                .isZero();
        assertThat(paymentMethodRowsOf(party)).isEmpty();
    }

    @Test
    @DisplayName("an enrolled identity is refused at PASSWORD with the key UNBURNED and no"
            + " exchange; the same key succeeds at MULTI_FACTOR")
    void theStepUpGateLeavesTheKeyUnburned() throws Exception {
        schemeExchanges("dest-stepup-" + suffix(), "match");
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String passwordToken = tokenFrom(authenticate(login).body());
        Sensitive<String> secret = enrolAndConfirm(passwordToken);
        UUID party = partyOf(login);
        String grant = "blg-stepup-" + suffix();
        UUID key = UUID.randomUUID();

        HttpResponse<String> refused = registerBankAccount(passwordToken, grant, false, key);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(paymentMethodRowsOf(party)).isEmpty();
        assertThat(scheme.requestCount(SimulatedInstantSchemeAdapter.EXCHANGES_PATH))
                .as("the fail-fast runs before any exchange")
                .isZero();

        // THE CLAIM ROLLED BACK WITH THE REFUSAL: the same key, now elevated, executes as a
        // fresh command rather than replaying a refusal that was never recorded.
        String elevated =
                tokenFrom(
                        post(
                                        "/v1/authentications/mfa",
                                        "{\"code\":\"" + codeNow(secret) + "\"}",
                                        passwordToken)
                                .body());
        HttpResponse<String> created = registerBankAccount(elevated, grant, false, key);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(paymentMethodRowsOf(party)).containsExactly("ACTIVE");
    }

    // -----------------------------------------------------------------
    // MFA (the PaymentMethodEndpointDatabaseTest idiom)
    // -----------------------------------------------------------------

    private Sensitive<String> enrolAndConfirm(String sessionToken) throws Exception {
        Sensitive<String> secret =
                Sensitive.of(secretFrom(post("/v1/me/mfa", null, sessionToken).body()));
        String confirming =
                Authenticator.codeAt(
                        secret,
                        TotpParameters.current(),
                        Instant.ofEpochSecond(
                                (currentStep() - 1) * TotpParameters.current().periodSeconds()));
        assertThat(
                        post(
                                        "/v1/me/mfa/confirmation",
                                        "{\"code\":\"" + confirming + "\"}",
                                        sessionToken)
                                .statusCode())
                .isEqualTo(204);
        return secret;
    }

    private static String codeNow(Sensitive<String> secret) {
        return Authenticator.codeNow(secret, TotpParameters.current(), CLOCK);
    }

    private static long currentStep() {
        return Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                UUID.randomUUID());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null);
    }

    private HttpResponse<String> registerBankAccount(
            String token, String grant, boolean acknowledge, UUID idempotencyKey)
            throws Exception {
        return post(
                "/v1/me/payment-methods/bank-accounts",
                "{\"grant\":\"" + grant + "\",\"acknowledgeNoMatch\":" + acknowledge + "}",
                token,
                idempotencyKey);
    }

    private HttpResponse<String> delete(String token, String id) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/v1/me/payment-methods/"
                                                + id))
                        .header("Authorization", "Bearer " + token)
                        .DELETE()
                        .build();
        return send(request);
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

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return post(path, body, token, null);
    }

    private HttpResponse<String> post(
            String path, String body, String token, UUID idempotencyKey) throws Exception {
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
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey.toString());
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

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    private static String secretFrom(String body) {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(body);
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        return matcher.group(1);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String someLogin() {
        return "bank." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static UUID partyOf(String login) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT party_id FROM identity.identity"
                                        + " WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the fixture row must exist").isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static List<String> paymentMethodRowsOf(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM paymentmethods.payment_method"
                                        + " WHERE party_id = ?")) {
            read.setObject(1, party);
            try (ResultSet rows = read.executeQuery()) {
                List<String> statuses = new ArrayList<>();
                while (rows.next()) {
                    statuses.add(rows.getString(1));
                }
                return statuses;
            }
        }
    }

    private static String destinationOf(String methodId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT destination_reference"
                                        + " FROM paymentmethods.payment_method WHERE id = ?")) {
            read.setObject(1, UUID.fromString(methodId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static java.sql.Timestamp acknowledgedAtOf(String methodId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT no_match_acknowledged_at"
                                        + " FROM paymentmethods.payment_method WHERE id = ?")) {
            read.setObject(1, UUID.fromString(methodId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getTimestamp(1);
            }
        }
    }

    private record AuditRow(String actorId, String reason) {}

    private static List<AuditRow> auditRecords(String operation, String targetId)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT actor_id, reason FROM platform.audit_record"
                                        + " WHERE operation = ? AND target_id = ?")) {
            read.setString(1, operation);
            read.setString(2, targetId);
            try (ResultSet rows = read.executeQuery()) {
                List<AuditRow> records = new ArrayList<>();
                while (rows.next()) {
                    records.add(new AuditRow(rows.getString(1), rows.getString(2)));
                }
                return records;
            }
        }
    }

    /** How many audit rows anywhere carry the needle — the INV-RAIL-03 sink sweep. */
    private static int auditNeedleCount(String needle) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE target_id LIKE ? OR reason LIKE ?"
                                        + " OR change_summary LIKE ?")) {
            String like = "%" + needle + "%";
            read.setString(1, like);
            read.setString(2, like);
            read.setString(3, like);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private static List<String> eventPayloads(String eventType, String aggregateId)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT payload FROM platform.outbox_event"
                                        + " WHERE event_type = ? AND aggregate_id = ?")) {
            read.setString(1, eventType);
            read.setObject(2, UUID.fromString(aggregateId));
            try (ResultSet rows = read.executeQuery()) {
                List<String> payloads = new ArrayList<>();
                while (rows.next()) {
                    payloads.add(new String(rows.getBytes(1), StandardCharsets.UTF_8));
                }
                assertThat(payloads).as("the acting register announces exactly once").hasSize(1);
                return payloads;
            }
        }
    }
}
