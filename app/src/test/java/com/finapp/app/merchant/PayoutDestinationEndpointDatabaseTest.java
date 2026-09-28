package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.merchant.SimulatedPayoutDestinationTokenisation;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * The payout destination's operator surface over real HTTP (`P6-TSK-011`, ADR-0056): the whole
 * four-eyes flow driven by two real operator sessions, the self-approval refused {@code 409} and
 * audited {@code DENIED}, the step-up on both sides, bank details refused before any exchange,
 * the tokenisation refusals, one open change at a time, the permission boundary in both
 * directions and the one {@code 404}. Nothing is seeded where the flow could be driven except
 * the merchant row itself, whose onboarding is {@code MerchantEndpointDatabaseTest}'s subject.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the payout destination endpoints (P6-TSK-011)")
class PayoutDestinationEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String PATH = SimulatedPayoutDestinationTokenisation.TOKENISATIONS_PATH;

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

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
        registry.add("finapp.merchant.payout-destination.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.merchant.payout-destination.tokenisation.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("propose, replay, self-approval refused and audited, second-operator approval, withdrawal")
    void theFourEyesFlowOverHttp() throws Exception {
        String merchant = merchant();
        String proposer = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String approver = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        providerTokenises("pdr_flow-1");

        String key = someKey();
        HttpResponse<String> proposed = propose(merchant, proposer, "pdg_flow1", key);
        assertThat(proposed.statusCode()).isEqualTo(201);
        assertThat(proposed.body())
                .contains("\"status\":\"PROPOSED\"")
                .contains("\"displaySuffix\":\"3000\"")
                .as("the provider reference never leaves the platform")
                .doesNotContain("pdr_flow-1");
        String destination = field(proposed.body(), "id");

        // The lost-response retry replays the recorded proposal; a reused key for another
        // request - here, another reason - is the distinct 409 (INV-IDEM-03).
        HttpResponse<String> replayed = propose(merchant, proposer, "pdg_flow1", key);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "id")).isEqualTo(destination);
        assertThat(propose(merchant, proposer, "pdg_flow1", key, "a different reason").statusCode())
                .isEqualTo(409);
        assertThat(auditCount("merchant.PayoutDestinationProposed", destination)).isEqualTo(1);

        // The proposer cannot approve: 409, and the refusal is on the record.
        HttpResponse<String> selfApproved = decide(merchant, destination, "approval", proposer);
        assertThat(selfApproved.statusCode()).isEqualTo(409);
        assertThat(selfApproved.body()).contains("merchant.SelfApprovalRefused");
        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                + " 'merchant.PayoutDestinationApprovalRefused' AND target_id = ?"
                                + " AND outcome = 'DENIED'",
                        destination))
                .isEqualTo(1);
        assertThat(statusOf(destination)).isEqualTo("PROPOSED");

        // A second operator approves; their retry converges on the same approval.
        HttpResponse<String> approved = decide(merchant, destination, "approval", approver);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"").contains("coolingOffUntil");
        assertThat(decide(merchant, destination, "approval", approver).statusCode()).isEqualTo(200);
        assertThat(auditCount("merchant.PayoutDestinationApproved", destination)).isEqualTo(1);

        HttpResponse<String> listed = get(base(merchant), proposer);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body())
                .contains("\"effective\":null")
                .contains("\"id\":\"" + destination + "\"")
                .doesNotContain("pdr_flow-1");

        // The cooling-off's teeth: withdrawn before it takes effect.
        HttpResponse<String> withdrawn = decide(merchant, destination, "withdrawal", proposer);
        assertThat(withdrawn.statusCode()).isEqualTo(200);
        assertThat(withdrawn.body()).contains("\"status\":\"WITHDRAWN\"");
        assertThat(auditCount("merchant.PayoutDestinationWithdrawn", destination)).isEqualTo(1);
    }

    @Test
    @DisplayName("step-up on both sides: an enrolled operator on a PASSWORD session is refused, nothing written")
    void stepUpOnBothSides() throws Exception {
        String merchant = merchant();
        providerTokenises("pdr_stepup-1");

        String proposerLogin = operatorLogin(RoleName.MERCHANT_ADMINISTRATOR);
        String proposerPassword = tokenFrom(authenticate(proposerLogin).body());
        Sensitive<String> proposerSecret = enrolAndConfirm(proposerPassword);

        HttpResponse<String> refused = propose(merchant, proposerPassword, "pdg_stepup1", someKey());
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(provider.requestCount(PATH)).as("no exchange for a refused caller").isZero();
        assertThat(rowsOf(merchant)).isZero();

        String proposerElevated = elevate(proposerPassword, proposerSecret);
        HttpResponse<String> proposed = propose(merchant, proposerElevated, "pdg_stepup1", someKey());
        assertThat(proposed.statusCode()).isEqualTo(201);
        String destination = field(proposed.body(), "id");

        String approverLogin = operatorLogin(RoleName.MERCHANT_ADMINISTRATOR);
        String approverPassword = tokenFrom(authenticate(approverLogin).body());
        Sensitive<String> approverSecret = enrolAndConfirm(approverPassword);
        HttpResponse<String> approvalRefused =
                decide(merchant, destination, "approval", approverPassword);
        assertThat(approvalRefused.statusCode()).isEqualTo(403);
        assertThat(approvalRefused.body()).contains("identity.AssuranceRequired");
        assertThat(statusOf(destination)).isEqualTo("PROPOSED");
        assertThat(auditCount("merchant.PayoutDestinationApproved", destination)).isZero();

        String approverElevated = elevate(approverPassword, approverSecret);
        assertThat(decide(merchant, destination, "approval", approverElevated).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("bank details are refused before any exchange, and never echoed")
    void bankDetailsNeverEnter() throws Exception {
        String merchant = merchant();
        String operator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        for (String detail : new String[] {"DE89370400440532013000", "12345678"}) {
            HttpResponse<String> refused = propose(merchant, operator, detail, someKey());
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(refused.body()).contains("destinationToken").doesNotContain(detail);
        }
        assertThat(provider.requestCount(PATH)).isZero();
        assertThat(rowsOf(merchant)).isZero();
    }

    @Test
    @DisplayName("a refused grant is 422 and an unusable provider 503, each writing nothing")
    void tokenisationRefusalsWriteNothing() throws Exception {
        String merchant = merchant();
        String operator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);

        provider.succeedsWith(PATH, 200, "{\"status\":\"refused\"}");
        HttpResponse<String> refused = propose(merchant, operator, "pdg_refused1", someKey());
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("merchant.DestinationNotTokenised");

        provider.respondsWithGarbage(PATH);
        HttpResponse<String> garbage = propose(merchant, operator, "pdg_garbage1", someKey());
        assertThat(garbage.statusCode()).isEqualTo(503);
        assertThat(garbage.body()).contains("merchant.DestinationTokenisationUnavailable");

        // An answer carrying an account-shaped reference is not a destination either.
        provider.succeedsWith(
                PATH,
                200,
                "{\"status\":\"tokenised\",\"reference\":\"DE89370400440532013000\","
                        + "\"last4\":\"3000\"}");
        assertThat(propose(merchant, operator, "pdg_unstorable1", someKey()).statusCode())
                .isEqualTo(503);
        assertThat(rowsOf(merchant)).isZero();
    }

    @Test
    @DisplayName("one open change at a time; a rejected change is terminal")
    void oneOpenChangeAndTerminalRejection() throws Exception {
        String merchant = merchant();
        String proposer = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String approver = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        providerTokenises("pdr_open-1");

        String destination = field(propose(merchant, proposer, "pdg_open1", someKey()).body(), "id");
        HttpResponse<String> second = propose(merchant, proposer, "pdg_open2", someKey());
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("merchant.DestinationChangePending");

        HttpResponse<String> rejected = decide(merchant, destination, "rejection", approver);
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("\"status\":\"REJECTED\"");
        HttpResponse<String> late = decide(merchant, destination, "approval", approver);
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("merchant.DestinationChangeNotOpen");
        // The slot is free again once the change is closed.
        assertThat(propose(merchant, proposer, "pdg_open3", someKey()).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("the permission boundary: no role, the wrong population, and no session at all")
    void thePermissionBoundary() throws Exception {
        String merchant = merchant();
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        providerTokenises("pdr_perm-1");
        String destination =
                field(propose(merchant, administrator, "pdg_perm1", someKey()).body(), "id");

        String nobody = tokenFrom(authenticate(registered()).body());
        assertThat(propose(merchant, nobody, "pdg_perm2", someKey()).statusCode()).isEqualTo(403);
        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(decide(merchant, destination, "approval", ledgerOperator).statusCode())
                .as("operating money is not approving where merchant money goes")
                .isEqualTo(403);
        assertThat(post(base(merchant), "{\"destinationToken\":\"pdg_perm3\",\"reason\":\"r\"}", null, someKey())
                        .statusCode())
                .isEqualTo(401);
        // The merchant's own machine credential reaches none of this (ADR-0056 section 1): a
        // leaked server key must not be one click from redirecting the merchant's money.
        HttpResponse<String> issued =
                post("/v1/operator/merchants/" + merchant + "/api-keys", null, administrator, someKey());
        assertThat(issued.statusCode()).isEqualTo(201);
        String merchantKey = field(issued.body(), "keyId") + "." + field(issued.body(), "secret");
        assertThat(propose(merchant, merchantKey, "pdg_perm4", someKey()).statusCode())
                .isEqualTo(401);
        assertThat(get(base(merchant), merchantKey).statusCode()).isEqualTo(401);
        assertThat(statusOf(destination)).isEqualTo("PROPOSED");
        assertThat(rowsOf(merchant)).isEqualTo(1);
    }

    @Test
    @DisplayName("another merchant's, unknown and malformed identifiers are one 404")
    void oneNotFound() throws Exception {
        String merchant = merchant();
        String other = merchant();
        String operator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        providerTokenises("pdr_tenancy-1");
        String destination = field(propose(merchant, operator, "pdg_ten1", someKey()).body(), "id");

        HttpResponse<String> foreign = decide(other, destination, "withdrawal", operator);
        HttpResponse<String> unknown = decide(merchant, IDS.next().toString(), "withdrawal", operator);
        HttpResponse<String> malformed = decide(merchant, "not-a-uuid", "withdrawal", operator);
        HttpResponse<String> noMerchant = get(base(IDS.next().toString()), operator);
        for (HttpResponse<String> response : java.util.List.of(foreign, unknown, malformed, noMerchant)) {
            assertThat(response.statusCode()).isEqualTo(404);
        }
        assertThat(normalised(foreign.body()))
                .as("the pairing is judged in the statement: another merchant's is unknown")
                .isEqualTo(normalised(unknown.body()))
                .isEqualTo(normalised(malformed.body()));
        assertThat(statusOf(destination)).isEqualTo("PROPOSED");
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private static void providerTokenises(String reference) {
        provider.succeedsWith(
                PATH,
                200,
                "{\"status\":\"tokenised\",\"reference\":\"" + reference + "\",\"last4\":\"3000\"}");
    }

    private static String merchant() throws SQLException {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert =
                        app.prepareStatement(
                                "INSERT INTO merchant.merchant (id, party_ref, legal_name,"
                                        + " display_name, settlement_currency, status, created_at,"
                                        + " status_changed_at) VALUES (?, ?, 'Acme GmbH', 'Acme',"
                                        + " 'EUR', 'ACTIVE', now(), now())")) {
            insert.setObject(1, id);
            insert.setObject(2, UUID.randomUUID());
            insert.executeUpdate();
        }
        return id.toString();
    }

    private String sessionWith(RoleName role) throws Exception {
        return tokenFrom(authenticate(operatorLogin(role)).body());
    }

    /** Registers an identity and grants it {@code role}; returns the login. */
    private String operatorLogin(RoleName role) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return login;
    }

    private String registered() throws Exception {
        String login = "destop." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration =
                post(
                        "/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null,
                        someKey());
        assertThat(registration.statusCode()).isEqualTo(201);
        return login;
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    /** Enrols and confirms a TOTP factor over the real endpoints; returns the secret. */
    private Sensitive<String> enrolAndConfirm(String sessionToken) throws Exception {
        Sensitive<String> secret =
                Sensitive.of(secretFrom(post("/v1/me/mfa", null, sessionToken, null).body()));
        // The PREVIOUS step's code: confirmation consumes its step (P1-TSK-018), and the
        // challenge that follows uses the current one.
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
                                        sessionToken,
                                        null)
                                .statusCode())
                .isEqualTo(204);
        return secret;
    }

    /** Proves the factor on {@code passwordToken}'s session; returns the elevated session. */
    private String elevate(String passwordToken, Sensitive<String> secret) throws Exception {
        return tokenFrom(
                post(
                                "/v1/authentications/mfa",
                                "{\"code\":\""
                                        + Authenticator.codeNow(secret, TotpParameters.current(), CLOCK)
                                        + "\"}",
                                passwordToken,
                                null)
                        .body());
    }

    private static long currentStep() {
        return Instant.now(CLOCK).getEpochSecond() / TotpParameters.current().periodSeconds();
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private static String base(String merchant) {
        return "/v1/operator/merchants/" + merchant + "/payout-destinations";
    }

    private HttpResponse<String> propose(String merchant, String token, String grant, String key)
            throws Exception {
        return propose(merchant, token, grant, key, "the merchant's new account");
    }

    private HttpResponse<String> propose(
            String merchant, String token, String grant, String key, String reason)
            throws Exception {
        return post(
                base(merchant),
                "{\"destinationToken\":\"" + grant + "\",\"reason\":\"" + reason + "\"}",
                token,
                key);
    }

    private HttpResponse<String> decide(
            String merchant, String destination, String decision, String token) throws Exception {
        return post(
                base(merchant) + "/" + destination + "/" + decision,
                "{\"reason\":\"checked against the merchant's bank letter\"}",
                token,
                null);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key)
            throws Exception {
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
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
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
    // Reads
    // -----------------------------------------------------------------

    private static long auditCount(String operation, String target) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?",
                operation,
                target);
    }

    private static long rowsOf(String merchant) throws SQLException {
        return count(
                "SELECT count(*) FROM merchant.payout_destination WHERE merchant_id = ?",
                UUID.fromString(merchant));
    }

    private static String statusOf(String destination) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM merchant.payout_destination WHERE id = ?")) {
            read.setObject(1, UUID.fromString(destination));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    /**
     * The correlation identifier differs per request by design, and {@code instance} is the
     * caller's own request path echoed back — different across the causes by the nature of the
     * probe, and a disclosure of nothing the caller did not type.
     */
    private static String normalised(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"*\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"*\"");
    }

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

    private static String secretFrom(String body) {
        Matcher matcher = Pattern.compile("secret=([A-Z2-7]+)").matcher(body);
        assertThat(matcher.find()).as("the response must carry a base32 secret").isTrue();
        return matcher.group(1);
    }
}
