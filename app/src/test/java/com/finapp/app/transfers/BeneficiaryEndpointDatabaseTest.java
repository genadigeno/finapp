package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.identity.Authenticator;
import com.finapp.identity.TotpParameters;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The beneficiary surface over real HTTP (`P4-TSK-007`): the conditional step-up at creation,
 * the convergence idiom on both writes, the one-404 removal, and the audit trail naming the
 * person.
 *
 * <p>The step-up's positive control drives the <strong>whole</strong> MFA flow over HTTP —
 * register, log in, enrol, confirm, challenge — rather than seeding an elevated session,
 * because the acceptance clause is "succeeds at {@code MULTI_FACTOR}" and a seeded session
 * would prove a fixture (the {@code MfaCannotBeBypassedDatabaseTest} idiom, and the
 * {@code P1-TSK-027} lesson that two green halves compose only when something drives them
 * together).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the beneficiary endpoints and the step-up point (P4-TSK-007)")
class BeneficiaryEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("an enrolled identity is refused at PASSWORD with nothing written, and"
            + " succeeds at MULTI_FACTOR")
    void theStepUpGateHoldsAtCreation() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String passwordToken = tokenFrom(authenticate(login).body());
        Sensitive<String> secret = enrolAndConfirm(passwordToken);
        UUID party = partyOf(login);
        UUID destination = destinationProduct();

        HttpResponse<String> refused =
                create(passwordToken, "Aunt Vera", destination.toString());
        assertThat(refused.statusCode())
                .as("an MFA-enrolled identity must present a MULTI_FACTOR session")
                .isEqualTo(403);
        assertThat(refused.body())
                .as("actionable and distinct: step up and retry (P1-TSK-018)")
                .contains("identity.AssuranceRequired");
        assertThat(beneficiaryRowsOf(party)).as("the refusal writes nothing").isEmpty();

        // The positive control: prove the factor, retry, created - and audited as the PERSON.
        String elevated =
                tokenFrom(
                        post(
                                        "/v1/authentications/mfa",
                                        "{\"code\":\"" + codeNow(secret) + "\"}",
                                        passwordToken)
                                .body());
        HttpResponse<String> created = create(elevated, "Aunt Vera", destination.toString());
        assertThat(created.statusCode()).isEqualTo(201);
        String beneficiaryId = field(created.body(), "id");
        assertThat(beneficiaryRowsOf(party)).containsExactly("ACTIVE");

        List<AuditRow> added = auditRecords("transfers.BeneficiaryAdded", beneficiaryId);
        assertThat(added).hasSize(1);
        assertThat(added.getFirst().actorId())
                .as("the person's own act, never the platform's")
                .isEqualTo(identityOf(login).toString());
        assertThat(added.getFirst().changeSummary())
                .as("identifiers only - the display name is RESTRICTED-PII (INV-AUD-02)")
                .contains("destination=" + destination)
                .doesNotContain("Aunt Vera");
    }

    @Test
    @DisplayName("an unenrolled identity creates at PASSWORD, and a retry converges onto the"
            + " existing row and name")
    void anUnenrolledIdentityCreatesAtPasswordAndConverges() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);
        UUID destination = destinationProduct();

        HttpResponse<String> first = create(token, "Aunt Vera", destination.toString());
        assertThat(first.statusCode()).isEqualTo(201);
        String beneficiaryId = field(first.body(), "id");

        // The retry - a lost response, a double tap - converges: 201, the SAME row, and the
        // EXISTING name even though the retry spelled a different one (renaming is
        // remove-and-recreate, P4-TSK-006's recorded consequence).
        HttpResponse<String> retried = create(token, "Auntie V", destination.toString());
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(field(retried.body(), "id")).isEqualTo(beneficiaryId);
        assertThat(field(retried.body(), "displayName")).isEqualTo("Aunt Vera");

        assertThat(beneficiaryRowsOf(party)).containsExactly("ACTIVE");
        assertThat(auditRecords("transfers.BeneficiaryAdded", beneficiaryId))
                .as("only the creating call is an act: the converged retry records nothing")
                .hasSize(1);
    }

    @Test
    @DisplayName("a stranger's beneficiary id, an unknown one and a malformed one are one 404"
            + " on DELETE")
    void aStrangersBeneficiaryIdIsOne404OnDelete() throws Exception {
        String owner = someLogin();
        String stranger = someLogin();
        assertThat(register(owner).statusCode()).isEqualTo(201);
        assertThat(register(stranger).statusCode()).isEqualTo(201);
        String ownerToken = tokenFrom(authenticate(owner).body());
        String strangerToken = tokenFrom(authenticate(stranger).body());
        UUID destination = destinationProduct();
        String beneficiaryId =
                field(create(ownerToken, "Aunt Vera", destination.toString()).body(), "id");

        HttpResponse<String> notYours = delete(strangerToken, beneficiaryId);
        HttpResponse<String> unknown = delete(strangerToken, IDS.next().toString());
        HttpResponse<String> malformed = delete(strangerToken, "not-a-uuid");
        assertThat(notYours.statusCode()).isEqualTo(404);
        // The equality between the causes (the P1-TSK-016 idiom): none is readable from the
        // response, so the endpoint is no oracle over other people's saved destinations.
        assertThat(normalized(notYours.body()))
                .isEqualTo(normalized(unknown.body()))
                .isEqualTo(normalized(malformed.body()));

        assertThat(statusOf(beneficiaryId))
                .as("a stranger's attempt moves nothing")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the owner's removal converges on 204 and is audited exactly once")
    void ownRemovalConvergesAndIsAudited() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID destination = destinationProduct();
        String beneficiaryId =
                field(create(token, "Aunt Vera", destination.toString()).body(), "id");

        assertThat(delete(token, beneficiaryId).statusCode()).isEqualTo(204);
        assertThat(statusOf(beneficiaryId)).isEqualTo("REMOVED");
        // The retry of a lost DELETE response converges - and is not a second act.
        assertThat(delete(token, beneficiaryId).statusCode()).isEqualTo(204);
        assertThat(auditRecords("transfers.BeneficiaryRemoved", beneficiaryId)).hasSize(1);
        assertThat(
                        auditRecords("transfers.BeneficiaryRemoved", beneficiaryId)
                                .getFirst()
                                .actorId())
                .isEqualTo(identityOf(login).toString());
    }

    @Test
    @DisplayName("the list shows only the caller's live beneficiaries")
    void theListShowsOnlyLiveOwnBeneficiaries() throws Exception {
        String a = someLogin();
        String b = someLogin();
        assertThat(register(a).statusCode()).isEqualTo(201);
        assertThat(register(b).statusCode()).isEqualTo(201);
        String aToken = tokenFrom(authenticate(a).body());
        String bToken = tokenFrom(authenticate(b).body());
        UUID kept = destinationProduct();
        UUID removed = destinationProduct();
        UUID theirs = destinationProduct();

        String keptId = field(create(aToken, "Kept", kept.toString()).body(), "id");
        String removedId = field(create(aToken, "Removed", removed.toString()).body(), "id");
        assertThat(delete(aToken, removedId).statusCode()).isEqualTo(204);
        String theirsId = field(create(bToken, "Theirs", theirs.toString()).body(), "id");

        String aList = get("/v1/beneficiaries", aToken).body();
        assertThat(aList).contains(keptId).doesNotContain(removedId).doesNotContain(theirsId);
        String bList = get("/v1/beneficiaries", bToken).body();
        assertThat(bList).contains(theirsId).doesNotContain(keptId);
    }

    @Test
    @DisplayName("an unknown destination and a malformed one are one 422 with nothing written")
    void anUnknownDestinationIsRefusedWithNothingWritten() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID party = partyOf(login);

        HttpResponse<String> unknown =
                create(token, "Aunt Vera", UUID.randomUUID().toString());
        HttpResponse<String> malformed = create(token, "Aunt Vera", "GB29NWBK60161331926819");
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("transfers.UnknownDestination");
        // Malformed-equals-absent for a third party's identifier: byte-identical, so neither
        // cause is readable from the response (TransfersErrorCode's reasoning).
        assertThat(normalized(malformed.body())).isEqualTo(normalized(unknown.body()));
        assertThat(beneficiaryRowsOf(party)).isEmpty();
    }

    @Test
    @DisplayName("no body shape is a 500")
    void noBodyShapeIsA500() throws Exception {
        String login = someLogin();
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());
        UUID destination = destinationProduct();

        // The domain's own name rule, mapped at the boundary: a control character is the
        // caller's 422 naming the field (never the value), not our 500 from three layers down.
        // The character travels as the JSON escape  - a raw control byte would be the
        // JSON parser's 400 and never reach the rule under test - and the source holds only
        // escapes (the P1-TSK-016 invisible-character lesson).
        HttpResponse<String> controlCharacter =
                post(
                        "/v1/beneficiaries",
                        "{\"displayName\":\"John\\u0007Doe\",\"destinationAccountId\":\""
                                + destination + "\"}",
                        token);
        assertThat(controlCharacter.statusCode()).isEqualTo(422);
        assertThat(controlCharacter.body()).contains("displayName").doesNotContain("John");

        List<String> shapes =
                List.of(
                        "{}",
                        "{\"displayName\":null,\"destinationAccountId\":\"x\"}",
                        "{\"displayName\":\"   \",\"destinationAccountId\":\"x\"}",
                        "{\"displayName\":\"" + "x".repeat(201) + "\",\"destinationAccountId\":\"x\"}",
                        "{\"displayName\":12345,\"destinationAccountId\":\"garbage\"}",
                        "{\"displayName\":{\"a\":1},\"destinationAccountId\":\"x\"}",
                        "{\"displayName\":\"Vera\"}",
                        "[1,2,3]",
                        "not json at all");
        for (String shape : shapes) {
            HttpResponse<String> response = post("/v1/beneficiaries", shape, token);
            assertThat(response.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isGreaterThanOrEqualTo(400)
                    .isLessThan(500);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /**
     * A destination product: somebody else's party, ACTIVE customer and wallet-bearing product
     * — the {@code TransferExecutionDatabaseTest} holder fixture, minimally.
     */
    private UUID destinationProduct() throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Destination Holder',"
                            + " now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            UUID product =
                    new AccountOpening(
                                    new JdbcCustomerAccountStore(),
                                    new JdbcLedgerAccountStore(),
                                    new VerifiedAccountHolder(new JdbcPartyStore()),
                                    new JdbcAuditWriter(),
                                    new JdbcOutboxWriter(),
                                    IDS,
                                    CLOCK)
                            .open(app, party, ProductType.WALLET, CurrencyCode.of("USD"))
                            .account()
                            .id()
                            .value();
            app.commit();
            return product;
        }
    }

    /** Enrols and confirms a TOTP factor over the real endpoints; returns the secret. */
    private Sensitive<String> enrolAndConfirm(String sessionToken) throws Exception {
        Sensitive<String> secret =
                Sensitive.of(secretFrom(post("/v1/me/mfa", null, sessionToken).body()));
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
                true);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null);
    }

    private HttpResponse<String> create(String token, String name, String destination)
            throws Exception {
        return post(
                "/v1/beneficiaries",
                "{\"displayName\":\"" + name + "\",\"destinationAccountId\":\"" + destination
                        + "\"}",
                token);
    }

    private HttpResponse<String> delete(String token, String id) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/beneficiaries/" + id))
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
        return post(path, body, token, false);
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
            request.header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString());
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

    /**
     * The correlation identifier differs per request by design, and {@code instance} is the
     * caller's own request path echoed back — different across the three 404 causes by the
     * nature of the probe, and a disclosure of nothing the caller did not type. Everything
     * else must be byte-identical.
     */
    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
    }

    private static UUID partyOf(String login) throws SQLException {
        return oneUuid("SELECT party_id FROM identity.identity WHERE login_identifier = ?", login);
    }

    private static UUID identityOf(String login) throws SQLException {
        return oneUuid("SELECT id FROM identity.identity WHERE login_identifier = ?", login);
    }

    private static UUID oneUuid(String sql, String argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            read.setString(1, argument);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("the fixture row must exist").isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static List<String> beneficiaryRowsOf(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM transfers.beneficiary"
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

    private static String statusOf(String beneficiaryId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM transfers.beneficiary WHERE id = ?")) {
            read.setObject(1, UUID.fromString(beneficiaryId));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private record AuditRow(String actorId, String changeSummary) {}

    private static List<AuditRow> auditRecords(String operation, String targetId)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT actor_id, change_summary FROM platform.audit_record"
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

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String someLogin() {
        return "vera." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
