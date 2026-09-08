package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The recovery boundary, driven over real HTTP (`P1-TSK-023`).
 *
 * <h2>Why the completion gate added this</h2>
 *
 * <p>Every other test in this task goes through the <em>service</em>. That is the {@code P1-TSK-017}
 * gate finding verbatim, and it left four decisions unasserted, each of them invisible to a
 * service-level test:
 *
 * <ul>
 *   <li>whether the <strong>token appears in a response</strong> — which would make channel control
 *       prove nothing, because whoever asked would hold it;
 *   <li>whether initiation is genuinely <strong>202 for everybody</strong>, including an identifier
 *       naming nobody;
 *   <li>whether {@code @RequiresSession} is wired on the one channel endpoint that needs it;
 *   <li>whether a malformed request is a <strong>422 rather than a 500</strong> — our fault reported
 *       for their input, which {@code ERROR_CONTRACT.md} §3 forbids and which a client may retry for
 *       ever.
 * </ul>
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("recovery endpoints (P1-TSK-023)")
class RecoveryEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // -----------------------------------------------------------------

    @Test
    @DisplayName("initiation is 202 with no body, for an identifier naming nobody")
    void initiationSaysNothing() throws Exception {
        HttpResponse<String> response =
                post("/v1/recoveries", "{\"loginIdentifier\":\"nobody-at-all\"}", null);

        // INV-IDN-07 applied to the endpoint an attacker probes first, because it needs nothing to
        // call. A 404 here - or any body at all - would turn recovery into an account-existence
        // oracle for the whole platform.
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isBlank();
    }

    @Test
    @DisplayName("initiation for a real identity is byte-identical to one for nobody")
    void aRealIdentityLooksTheSame() throws Exception {
        IdentityId identity = givenAnIdentity();

        HttpResponse<String> real =
                post("/v1/recoveries", "{\"loginIdentifier\":\"" + loginOf(identity) + "\"}", null);
        HttpResponse<String> nobody =
                post("/v1/recoveries", "{\"loginIdentifier\":\"nobody-at-all\"}", null);

        // Asserted as an EQUALITY BETWEEN THE TWO rather than each against a remembered expectation,
        // the P1-TSK-010 idiom: the second form passes against an implementation returning two
        // different bodies that each happen to match what its author wrote down.
        assertThat(real.statusCode()).isEqualTo(nobody.statusCode());
        assertThat(real.body()).isEqualTo(nobody.body());
    }

    @Test
    @DisplayName("the counter distinguishes what the 202 deliberately hides")
    void theCounterSeesWhatTheResponseHides() throws Exception {
        // `P1-TSK-029`. The response is 202 for an identifier naming nobody AND for a real identity
        // with no verified channel - which is INV-IDN-07 working, and it is why a flood of probes
        // is invisible from outside. The counter is not visible to the caller, so it can and must
        // tell them apart: a rise in `refused` is somebody walking a list of identifiers.
        double refusedBefore = count("refused");
        double acceptedBefore = count("accepted");

        HttpResponse<String> nobody =
                post("/v1/recoveries", "{\"loginIdentifier\":\"nobody-at-all\"}", null);

        assertThat(nobody.statusCode()).as("the response says nothing, as it must").isEqualTo(202);
        assertThat(count("refused"))
                .as("and the counter says what happened, which is the ATO signal the plan names")
                .isEqualTo(refusedBefore + 1);
        assertThat(count("accepted"))
                .as("nothing was accepted, so nothing counted it")
                .isEqualTo(acceptedBefore);
    }

    /** One outcome of {@code finapp.identity.recovery.initiation}. */
    private double count(String outcome) {
        return meters.counter("finapp.identity.recovery.initiation", "outcome", outcome).count();
    }

    @Test
    @DisplayName("no recovery response carries a token")
    void noResponseCarriesAToken() throws Exception {
        IdentityId identity = givenAnIdentity();
        HttpResponse<String> initiation =
                post("/v1/recoveries", "{\"loginIdentifier\":\"" + loginOf(identity) + "\"}", null);

        // THE PROPERTY THE WHOLE DESIGN RESTS ON. If the token came back to whoever asked, proving
        // control of the channel would prove nothing at all - anybody able to type a login
        // identifier could reset the credential. Asserted against the DATABASE's copy rather than a
        // pattern, so it cannot pass because the token happened to look unlike what was searched for.
        assertThat(initiation.body()).isBlank();

        String storedHash = tokenHashOf(identity);
        assertThat(storedHash)
                .as("precondition: a request must actually exist, or this asserts nothing")
                .isNotNull();
        assertThat(initiation.body()).doesNotContain(storedHash);
    }

    @Test
    @DisplayName("adding a channel requires a session; verifying one does not")
    void theSessionRequirementIsWiredWhereItBelongs() throws Exception {
        IdentityId identity = givenAnIdentity();

        assertThat(post("/v1/me/channels", "{\"address\":\"ada@example.com\"}", null).statusCode())
                .as("registering a channel unauthenticated would let an attacker point recovery at"
                        + " their own mailbox holding nothing at all")
                .isEqualTo(401);

        assertThat(
                        post(
                                        "/v1/me/channels",
                                        "{\"address\":\"ada@example.com\"}",
                                        givenASessionFor(identity))
                                .statusCode())
                .isEqualTo(202);

        // Verification is deliberately unauthenticated: the token arrives in the mailbox and
        // whoever reads it may be in another browser. A refusal, not a 401 - holding the token IS
        // the proof, so an unknown one is simply wrong rather than unauthenticated.
        assertThat(
                        post(
                                        "/v1/me/channels/verification",
                                        "{\"token\":\"not-a-real-token\"}",
                                        null)
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("no request shape produces a 500")
    void noRequestShapeProducesAServerError() throws Exception {
        List<String[]> shapes =
                List.of(
                        new String[] {"/v1/recoveries", "{}"},
                        new String[] {"/v1/recoveries", "{\"loginIdentifier\":null}"},
                        new String[] {"/v1/recoveries", "{\"loginIdentifier\":\"\"}"},
                        new String[] {"/v1/recoveries", "{\"loginIdentifier\":\"a\"}"},
                        new String[] {"/v1/recoveries", "{\"loginIdentifier\":\"has spaces\"}"},
                        new String[] {"/v1/recoveries", "{\"loginIdentifier\":123}"},
                        new String[] {"/v1/recoveries", "{\"loginIdentifier\":{}}"},
                        new String[] {"/v1/me/channels/verification", "{}"},
                        new String[] {"/v1/me/channels/verification", "{\"token\":null}"},
                        new String[] {
                            "/v1/recoveries/not-a-uuid/completion",
                            "{\"token\":\"x\",\"password\":\"y\"}"
                        },
                        new String[] {
                            "/v1/recoveries/" + UUID.randomUUID() + "/completion",
                            "{\"token\":\"x\",\"password\":\"short\"}"
                        },
                        new String[] {"/v1/recoveries/" + UUID.randomUUID() + "/completion", "{}"});

        // A 500 says OUR side failed for something only the caller can fix, and a client may retry
        // it for ever. The P1-TSK-006 finding, where a NUL byte in a display name reached PostgreSQL
        // three layers down and surfaced as api.InternalError - found by probing shapes the code was
        // not designed against rather than by reading it.
        for (String[] shape : shapes) {
            assertThat(post(shape[0], shape[1], null).statusCode())
                    .as("POST %s with %s", shape[0], shape[1])
                    .isLessThan(500);
        }
    }

    @Test
    @DisplayName("a malformed email address is a 422 naming the field, not a 500")
    void aMalformedAddressIsTheCallersMistake() throws Exception {
        IdentityId identity = givenAnIdentity();
        String session = givenASessionFor(identity);

        HttpResponse<String> response =
                post("/v1/me/channels", "{\"address\":\"not-an-address\"}", session);

        assertThat(response.statusCode()).isEqualTo(422);

        // And the refusal must not echo the value: it is RESTRICTED-PII, and a problem detail
        // reaches a client's logs (INV-AUD-02).
        assertThat(response.body()).doesNotContain("not-an-address");
    }

    // -----------------------------------------------------------------

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private static String tokenHashOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT token_hash FROM identity.recovery_request"
                                        + " WHERE identity_id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static String loginOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT login_identifier FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** With a verified channel, so initiation actually produces a request to look for. */
    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        UUID channel = IDS.next();
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
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            execute(
                    app,
                    "INSERT INTO identity.contact_channel"
                            + " (id, identity_id, kind, address, verified_at, added_at)"
                            + " VALUES (?, ?, 'EMAIL', ?, now(), now())",
                    channel,
                    identity,
                    "ada"
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                            + "@example.com");
        }
        return IdentityId.of(identity);
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
}
