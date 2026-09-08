package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A login produces a session that actually works (`P1-TSK-027`, criterion 1).
 *
 * <h2>The property under test is <em>end to end</em>, and that is the whole point</h2>
 *
 * <p>The Phase 1 review failed exit criterion 1 - <em>"deliverables exercisable end to end"</em> -
 * on a specific finding: {@code Session.issue} had <strong>no production caller</strong>, so the
 * eight endpoints the plan marks <em>"Auth: session"</em> could not be reached by any real client.
 * Every test that exercised them inserted a session row directly, which is exactly why the gap
 * survived twenty-four tasks: <strong>the suite could reach what a customer could not.</strong>
 *
 * <p>So the headline assertion here does not check that a token was returned, nor that a row was
 * written. It <strong>uses the token on a protected endpoint over real HTTP</strong>. Nothing
 * short of that distinguishes "a session exists" from "a client can hold one", and the difference
 * between those two is precisely what the review found.
 *
 * <h2>What this suite deliberately does not repeat</h2>
 *
 * <p>{@code INV-IDN-07}'s failure-shape equality is
 * {@code AuthenticationEndpointDatabaseTest.everyFailureLooksTheSame}, and the equivalent-cost half
 * is {@code AuthenticationCostsTheSameDatabaseTest}. Both still pass unchanged, because
 * {@code P1-TSK-027} touched only the success path. A second copy of a working assertion is
 * duplication that drifts, not coverage ({@code P1-TSK-012}).
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(
        AuthenticationIssuesASessionDatabaseTest.HighValueOperation.class)
@DisplayName("authentication issues a working session (P1-TSK-027)")
class AuthenticationIssuesASessionDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Cheap: this suite is about sessions, not about how long deriving takes. */
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    private static final String PASSWORD = "correct horse battery staple";

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // -----------------------------------------------------------------
    // The criterion

    @Test
    @DisplayName("the token a login returns opens a protected endpoint")
    void aLoginProducesAUsableSession() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        Response login = authenticate(fixture.login(), PASSWORD, "Firefox/141.0");
        assertThat(login.status()).as("a session is created").isEqualTo(201);

        String token = jsonString(login.body(), "sessionToken");
        assertThat(token).as("precondition: a token was actually returned").isNotEmpty();

        // THE assertion. Not "a row exists" and not "a token was returned" - a protected endpoint,
        // over HTTP, with nothing inserted by the test. This is the step that was missing.
        Response listing = get("/v1/sessions", token);
        assertThat(listing.status())
                .as("`GET /v1/sessions` is @RequiresSession. Before `P1-TSK-027` no client could"
                        + " reach it at all, because nothing issued a first session")
                .isEqualTo(200);
        assertThat(listing.body())
                .as("and the person sees the session they just created, labelled with the device")
                .contains("Firefox/141.0");
    }

    @Test
    @DisplayName("an unknown token opens nothing, so the previous test cannot pass by accident")
    void aFabricatedTokenOpensNothing() throws Exception {
        // The negative control the headline assertion needs. Without it, an interceptor that
        // admitted everything would satisfy `aLoginProducesAUsableSession` perfectly.
        assertThat(get("/v1/sessions", "not-a-real-token-" + UUID.randomUUID()).status())
                .as("the protected endpoint is genuinely protected")
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // The level

    @Test
    @DisplayName("the session is PASSWORD, and a login can never ask for more")
    void aLoginEstablishesPasswordAssurance() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        Response login = authenticate(fixture.login(), PASSWORD, null);

        assertThat(jsonString(login.body(), "assurance"))
                .as("proving a password buys a PASSWORD session and nothing more; anything higher"
                        + " here is the MFA bypass INV-IDN-05 forbids")
                .isEqualTo("PASSWORD");
        assertThat(sessionAssurance(fixture.identityId()))
                .as("and the ROW says so too - a response that claimed PASSWORD over a row that"
                        + " said otherwise would be the disclosure, not the protection")
                .containsExactly("PASSWORD");
    }

    @Test
    @DisplayName("an operation requiring MULTI_FACTOR refuses the session a login produced")
    void aLoginSessionDoesNotSatisfyAMultiFactorRequirement() throws Exception {
        // The composition ADR-0030 exists for, asserted rather than reasoned about. A PASSWORD
        // session must be usable - it is the INPUT to step-up, since MfaChallenge.elevate takes a
        // current session - while satisfying nothing that requires a second factor.
        Fixture fixture = givenAnIdentityWithACredential();

        String token = jsonString(authenticate(fixture.login(), PASSWORD, null).body(), "sessionToken");

        assertThat(get("/v1/sessions", token).status())
                .as("precondition: the session works where PASSWORD is enough")
                .isEqualTo(200);
        assertThat(get(ASSURANCE_PROBE, token).status())
                .as("and is refused where MULTI_FACTOR is required, which is what makes issuing at"
                        + " PASSWORD safe rather than merely conservative")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a session is still issued when a second factor is enrolled")
    void anEnrolledSecondFactorDoesNotWithholdTheSession() throws Exception {
        // The counter-intuitive half, and the one an implementation gets wrong by being strict.
        // Withholding the session until MFA is done makes step-up UNREACHABLE - elevate takes a
        // current session - which is the same shape of gap the review found.
        Fixture fixture = givenAnIdentityWithACredential();
        givenAnActiveSecondFactor(fixture.identityId());

        Response login = authenticate(fixture.login(), PASSWORD, null);

        assertThat(login.status())
                .as("a person with MFA enrolled still gets a session; otherwise they could never"
                        + " reach the endpoint that elevates it")
                .isEqualTo(201);
        assertThat(jsonString(login.body(), "assurance"))
                .as("at PASSWORD, because the second factor has not been proven in THIS session -"
                        + " assurance is a property of a session, never of an identity")
                .isEqualTo("PASSWORD");
    }

    @Test
    @DisplayName("the response is the same shape whether or not MFA is enrolled (INV-IDN-07)")
    void theResponseDoesNotDiscloseWhetherMfaIsEnrolled() throws Exception {
        Fixture without = givenAnIdentityWithACredential();
        Fixture with = givenAnIdentityWithACredential();
        givenAnActiveSecondFactor(with.identityId());

        Response plain = authenticate(without.login(), PASSWORD, null);
        Response enrolled = authenticate(with.login(), PASSWORD, null);

        assertThat(enrolled.status()).isEqualTo(plain.status());
        assertThat(fieldNames(enrolled.body()))
                .as("the same members in the same order: a body that gained an `mfaRequired` flag"
                        + " would tell an attacker holding a stolen password whether the account has"
                        + " a second factor, which is what to attack next")
                .isEqualTo(fieldNames(plain.body()));
        assertThat(jsonString(enrolled.body(), "assurance"))
                .isEqualTo(jsonString(plain.body(), "assurance"));
    }

    // -----------------------------------------------------------------
    // Atomicity and the trail

    @Test
    @DisplayName("a refused login leaves no session at all")
    void aRefusedLoginIssuesNothing() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        assertThat(authenticate(fixture.login(), "not the password", null).status()).isEqualTo(401);

        assertThat(sessionRows(fixture.identityId()))
                .as("a session created by a failed login would be an authentication bypass with"
                        + " a 401 in front of it")
                .isZero();
    }

    @Test
    @DisplayName("a locked account gets no session even with the correct password")
    void aLockedAccountGetsNoSession() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        // Fail past the threshold, then present the RIGHT password. The lock must hold - a lock a
        // correct guess clears is a signal that the guess was right (`P1-TSK-011`).
        for (int attempt = 0; attempt < 12; attempt++) {
            authenticate(fixture.login(), "not the password", null);
        }

        assertThat(authenticate(fixture.login(), PASSWORD, null).status())
                .as("still refused")
                .isEqualTo(401);
        assertThat(sessionRows(fixture.identityId()))
                .as("and, decisively, no session - the lock must be enforced BEFORE issuance, not"
                        + " reported after it")
                .isZero();
    }

    @Test
    @DisplayName("the success audit record names the session the login produced")
    void theAuditRecordNamesTheSession() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        Response login = authenticate(fixture.login(), PASSWORD, null);
        assertThat(login.status()).isEqualTo(201);

        String sessionId = onlySessionIdOf(fixture.identityId());

        // Two assertions rather than one, and neither pins a RENDERING. The first version asserted
        // `contains("session=" + uuid)` and failed: EntityId.toString() renders `SessionId(uuid)`,
        // which is the platform's convention across all five existing change summaries.
        //
        // The test was wrong, not the code. The wrapped form loses nothing an investigator does -
        // they search a free-text summary by substring, never by equality - and it says which KIND
        // of identifier this is, which a bare UUID does not. So what is asserted is the property:
        // the record labels a session, and the identifier is findable in it.
        assertThat(changeSummary(fixture.login()))
                .as("one record, not two: an investigator reads 'this login produced session X'"
                        + " rather than joining two rows by timestamp")
                .contains("session=")
                .contains(sessionId);
    }

    @Test
    @DisplayName("the response never carries the token hash, only the token")
    void theResponseCarriesTheTokenAndNotItsHash() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        Response login = authenticate(fixture.login(), PASSWORD, null);
        String storedHash = onlyTokenHashOf(fixture.identityId());

        assertThat(storedHash).as("precondition: a hash really is stored").isNotEmpty();
        assertThat(login.body())
                .as("the hash is what remains after the secret was thrown away; publishing it would"
                        + " let a database leak be matched against captured traffic")
                .doesNotContain(storedHash);
        assertThat(jsonString(login.body(), "sessionToken"))
                .as("and the token itself is NOT what is stored")
                .isNotEqualTo(storedHash);
    }

    // -----------------------------------------------------------------
    // Concurrency

    @Test
    @DisplayName("ten concurrent logins produce ten distinct, usable sessions")
    void tenConcurrentLoginsProduceTenSessions() throws Exception {
        // Ten instances, one identity. Authentication is NOT a command to deduplicate: a person
        // holds several sessions on purpose, so ten logins are ten sessions - and every one of them
        // must work, because a token handed out that opens nothing is worse than a refusal.
        Fixture fixture = givenAnIdentityWithACredential();

        int racers = 10;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<Response>> results = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await(30, TimeUnit.SECONDS);
                                    return authenticate(fixture.login(), PASSWORD, null);
                                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Set<String> tokens = new HashSet<>();
            for (Future<Response> result : results) {
                Response response = result.get(60, TimeUnit.SECONDS);
                assertThat(response.status()).isEqualTo(201);
                tokens.add(jsonString(response.body(), "sessionToken"));
            }

            assertThat(tokens)
                    .as("ten DISTINCT tokens. A collision would mean two people could hold the same"
                            + " session, and SecureRandom is what prevents it")
                    .hasSize(racers);
            for (String token : tokens) {
                assertThat(get("/v1/sessions", token).status())
                        .as("and every one of them opens the protected endpoint")
                        .isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sessionRows(fixture.identityId()))
                .as("ten rows: nothing was lost and nothing was deduplicated")
                .isEqualTo(racers);
    }

    // -----------------------------------------------------------------
    // The device

    @Test
    @DisplayName("an absent User-Agent is not a reason to refuse a login")
    void aMissingDeviceIsNotAFailure() throws Exception {
        // A User-Agent is a header the person did not choose and cannot edit. Letting an unscored
        // convenience label refuse an authentication would invert its importance completely
        // (`P1-TSK-016`), so this is asserted rather than assumed.
        Fixture fixture = givenAnIdentityWithACredential();

        assertThat(authenticate(fixture.login(), PASSWORD, null).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("an over-long User-Agent is truncated, never a refusal")
    void anOverLongDeviceIsTruncatedRatherThanRefused() throws Exception {
        Fixture fixture = givenAnIdentityWithACredential();

        String enormous = "Mozilla/5.0 " + "x".repeat(4000);

        Response login = authenticate(fixture.login(), PASSWORD, enormous);

        assertThat(login.status())
                .as("a login must never fail because a browser sent something odd - a User-Agent is"
                        + " a header the person did not choose and cannot edit")
                .isEqualTo(201);
        assertThat(storedDeviceOf(fixture.identityId()).length())
                .as("and it is bounded, because `device` is a column with a CHECK on its length"
                        + " (V007) - an unbounded caller string reaching the last write is a"
                        + " failure at the end of the flow for a reason no message explains")
                .isLessThanOrEqualTo(com.finapp.identity.DeviceDescription.MAX_LENGTH);
    }

    @Test
    @DisplayName("the JDK client refuses to send a control character, which bounds what this can test")
    void theCharacterLevelRuleCannotBeDrivenOverHttp() {
        // Probed rather than assumed, and recorded because the limit is easy to mistake for
        // coverage. `DeviceDescription` strips five Unicode categories - a bidirectional override
        // in a session listing is a list that lies about which session is which, and a CR is a
        // forged log line - but NONE of it can be driven through this client: the JDK refuses any
        // header value outside printable ASCII.
        //
        // So the character-level rule is `DeviceDescriptionTest`'s subject and stays there, exactly
        // as `P1-TSK-017` recorded for CR/LF. Reaching it needs raw bytes on a socket, which a
        // hostile client is of course free to write - which is why the rule exists at the domain
        // type rather than at the boundary.
        String withAnOverride = "Mozilla/5.0 \u202egnitroper\u202c";

        assertThatThrownBy(() -> authenticate(new LoginIdentifier("irrelevant"), PASSWORD, withAnOverride))
                .as("if this ever stops throwing, the character-level assertions belong here too")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(com.finapp.identity.DeviceDescription.fromUserAgent(withAnOverride))
                .as("and the domain strips it, which is what protects the column when the bytes"
                        + " arrive by some other route")
                .hasValueSatisfying(
                        device ->
                                assertThat(device.value())
                                        .doesNotContain("\u202e")
                                        .doesNotContain("\u202c"));
    }

    // -----------------------------------------------------------------
    // Fixtures

    private static final String ASSURANCE_PROBE = "/v1/probe/multi-factor";

    /**
     * A handler requiring {@code MULTI_FACTOR}, so this suite can assert what a {@code PASSWORD}
     * session does <strong>not</strong> open.
     *
     * <p>Phase 1 has no production operation that requires a second factor - {@code PHASE_1_PLAN.md}
     * §66 says so, and {@code P1-TSK-018} declined to invent one because a security control chosen
     * to suit a test is the wrong way round. So the annotation gets a probe, and this is a second
     * copy of {@code MfaChallengeDatabaseTest}'s rather than a shared fixture: a probe registered by
     * {@code @Import} belongs to one test's context, and hoisting it into a shared configuration
     * would put it one careless {@code @ComponentScan} away from the published contract.
     */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.web.bind.annotation.RestController
    static class HighValueOperation {

        @com.finapp.app.session.RequiresAssurance(
                com.finapp.identity.AssuranceLevel.MULTI_FACTOR)
        @org.springframework.web.bind.annotation.GetMapping("/probe/multi-factor")
        String pretendToMoveMoney() {
            return "done";
        }
    }

    private record Response(int status, String body) {}

    private record Fixture(IdentityId identityId, LoginIdentifier login) {}

    private Response authenticate(LoginIdentifier login, String password, String userAgent)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/authentications"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\"%s\",\"password\":\"%s\"}"
                                                .formatted(login.value(), password)));
        if (userAgent != null) {
            request = request.header("User-Agent", userAgent);
        }
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private Response get(String path, String token) throws Exception {
        HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .header("Authorization", "Bearer " + token)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    /** Deliberately crude: a full parser would hide a body shaped differently from what is claimed. */
    private static String jsonString(String body, String member) {
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile("\"" + member + "\"\\s*:\\s*\"([^\"]*)\"")
                        .matcher(body);
        return match.find() ? match.group(1) : "";
    }

    private static List<String> fieldNames(String body) {
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher match =
                java.util.regex.Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:").matcher(body);
        while (match.find()) {
            names.add(match.group(1));
        }
        return names;
    }

    private Fixture givenAnIdentityWithACredential() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        LoginIdentifier login = new LoginIdentifier(someLogin());
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    login.value());
            new JdbcCredentialStore()
                    .insert(
                            app,
                            Credential.forPassword(
                                    IDS,
                                    CLOCK,
                                    IdentityId.of(identity),
                                    CredentialType.PASSWORD,
                                    new Argon2PasswordDeriver(WEAK),
                                    RawPassword.of(PASSWORD)));
        }
        return new Fixture(IdentityId.of(identity), login);
    }

    /**
     * An {@code ACTIVE} second factor, written directly.
     *
     * <p>The enrolment endpoint needs a session, and this fixture is for the tests about what
     * happens <em>before</em> one exists - so going through it would be circular.
     */
    private void givenAnActiveSecondFactor(IdentityId identityId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO identity.mfa_enrolment (id, identity_id, type, secret_ciphertext,"
                            + " secret_nonce, key_version, algorithm, digits, period_seconds,"
                            + " status, created_at, confirmed_at)"
                            + " VALUES (?, ?, 'TOTP', ?, ?, 1, 'SHA1', 6, 30, 'ACTIVE', now(), now())",
                    IDS.next(),
                    identityId.value(),
                    // Never decrypted by anything this suite drives - what is under test is whether
                    // an ENROLLED factor changes what a login returns, not whether a code verifies.
                    new byte[48],
                    new byte[12]);
        }
    }

    private static String someLogin() {
        return "ada" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private int sessionRows(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.session WHERE identity_id = ?", identityId.value());
    }

    private List<String> sessionAssurance(IdentityId identityId) throws SQLException {
        return strings(
                "SELECT assurance FROM identity.session WHERE identity_id = ?", identityId.value());
    }

    private String onlySessionIdOf(IdentityId identityId) throws SQLException {
        List<String> ids =
                strings(
                        "SELECT id::text FROM identity.session WHERE identity_id = ?",
                        identityId.value());
        assertThat(ids).as("precondition: exactly one session was issued").hasSize(1);
        return ids.get(0);
    }

    private String onlyTokenHashOf(IdentityId identityId) throws SQLException {
        List<String> hashes =
                strings(
                        "SELECT token_hash FROM identity.session WHERE identity_id = ?",
                        identityId.value());
        assertThat(hashes).as("precondition: exactly one session was issued").hasSize(1);
        return hashes.get(0);
    }

    private String storedDeviceOf(IdentityId identityId) throws SQLException {
        List<String> devices =
                strings(
                        "SELECT coalesce(device, '') FROM identity.session WHERE identity_id = ?",
                        identityId.value());
        assertThat(devices).as("precondition: exactly one session was issued").hasSize(1);
        return devices.get(0);
    }

    private String changeSummary(LoginIdentifier login) throws SQLException {
        List<String> summaries =
                strings(
                        "SELECT coalesce(change_summary, '') FROM platform.audit_record"
                                + " WHERE target_id = ? AND operation = 'identity.AuthenticationSucceeded'",
                        login.value());
        assertThat(summaries).as("precondition: the success was audited").hasSize(1);
        return summaries.get(0);
    }

    // -----------------------------------------------------------------

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            statement.executeUpdate();
        }
    }

    private static int count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static List<String> strings(String sql, Object... arguments) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private static void bind(PreparedStatement statement, Object... arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }
}
