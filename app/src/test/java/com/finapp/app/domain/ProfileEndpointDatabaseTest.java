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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code GET /v1/me} and {@code PATCH /v1/me}, over real HTTP (`P1-TSK-030`).
 *
 * <h2>What the ownership test can and cannot be</h2>
 *
 * <p>Neither endpoint takes a path variable, a query parameter or a body field naming a party, so
 * <strong>an attacker cannot name a victim</strong> — the API gives them nothing to name one with.
 * That is the strongest form of ADR-0031's rule and it makes the usual negative test impossible to
 * write: there is no request to refuse.
 *
 * <p>So {@link #eachPersonSeesAndChangesOnlyTheirOwn} proves the <em>resolution chain</em> instead —
 * two identities, each reading and writing exactly their own party — and that is a weaker shape of
 * test for a stronger shape of control. Saying so is better than implying the two are the same.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the profile endpoints (P1-TSK-030)")
class ProfileEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @Test
    @DisplayName("each person sees and changes only their own profile")
    void eachPersonSeesAndChangesOnlyTheirOwn() throws Exception {
        Person ada = givenAPerson("Ada Lovelace");
        Person grace = givenAPerson("Grace Hopper");

        assertThat(get("/me", ada.session()).body())
                .contains("Ada Lovelace")
                .contains(ada.login())
                .doesNotContain("Grace Hopper");

        assertThat(patch("/me", ada.session(), "{\"displayName\":\"Ada King\"}").statusCode())
                .isEqualTo(200);

        assertThat(displayNameOf(ada.party())).isEqualTo("Ada King");
        assertThat(displayNameOf(grace.party()))
                .as("one person's rename must not reach another's party")
                .isEqualTo("Grace Hopper");
        assertThat(get("/me", grace.session()).body()).contains("Grace Hopper");
    }

    @Test
    @DisplayName("a change is audited against the person, naming the field and not the name")
    void aChangeIsAudited() throws Exception {
        Person ada = givenAPerson("Ada Lovelace");

        assertThat(patch("/me", ada.session(), "{\"displayName\":\"Ada King\"}").statusCode())
                .isEqualTo(200);

        String summary = auditSummaryFor(ada.party(), ada.identity());
        assertThat(summary).as("one record, naming the actor and the target").isNotEmpty();

        // party.display_name is RESTRICTED-PII and audit_record.change_summary is
        // RESTRICTED-FINANCIAL. Those are peers, not a hierarchy: a name written there would sit
        // outside the PII rules, and ADR-0022 forbids reclassifying a column that holds data. The
        // catalogue description promised the before and after values until this task corrected it.
        assertThat(summary)
                .as("the record says WHICH field changed, never what it was or became")
                .contains("field=displayName")
                .doesNotContain("Ada Lovelace")
                .doesNotContain("Ada King");
    }

    @Test
    @DisplayName("a rename to the name already held succeeds and writes no audit record")
    void aNoOpRenameWritesNothing() throws Exception {
        Person ada = givenAPerson("Ada Lovelace");

        assertThat(patch("/me", ada.session(), "{\"displayName\":\"Ada Lovelace\"}").statusCode())
                .as("the caller asked for the profile to hold that name, and it does")
                .isEqualTo(200);

        // An entry reading "changed from Ada to Ada" is noise, and worse, it would let anybody pad
        // the trail at will. The conditional in the UPDATE is what prevents it.
        assertThat(auditCountFor(ada.party())).isZero();
    }

    @Test
    @DisplayName("a control character is a 422 at the boundary, and nothing is stored")
    void aControlCharacterIsRefused() throws Exception {
        Person ada = givenAPerson("Ada Lovelace");

        // P1-TSK-006's finding, at a NEW boundary onto the same column: a NUL produced a 500 and
        // control characters reached a RESTRICTED-PII column. It was closed in three places, and an
        // endpoint that skipped the constraint would reopen one of them.
        HttpResponse<String> response =
                patch("/me", ada.session(), "{\"displayName\":\"Ada\\u0000King\"}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed").contains("displayName");
        assertThat(displayNameOf(ada.party())).isEqualTo("Ada Lovelace");
    }

    /**
     * No body shape turns the caller's mistake into ours.
     *
     * <p>{@code P1-TSK-010}'s gate established this probe and it earns its place here for a second
     * reason: it is where the <strong>absent versus explicit null</strong> decision is actually
     * checked. A record cannot distinguish them, and {@code ProfileUpdateRequest} documents that as
     * costing nothing because {@code display_name} is {@code NOT NULL} and can never be cleared —
     * so both must be the same {@code 422}, and here they are asserted to be.
     *
     * <p>One test rather than eight, deliberately: the property is <em>no shape is a 500</em>, and
     * splitting it per shape would multiply the assertions without adding a claim.
     */
    @Test
    @DisplayName("no PATCH body shape produces a 500, and absent means the same as null")
    void noBodyShapeIsOurFault() throws Exception {
        Person ada = givenAPerson("Ada Lovelace");

        for (String body :
                new String[] {
                    "{}",
                    "{\"displayName\":null}",
                    "{\"displayName\":\"\"}",
                    "{\"displayName\":\"   \"}",
                    "{\"displayName\":{\"a\":1}}",
                    "{\"displayName\":[1,2]}",
                    "",
                    "not json"
                }) {
            assertThat(patch("/me", ada.session(), body).statusCode())
                    .as("body %s", body)
                    .isIn(400, 422);
        }

        // Absent and explicit null are the same answer - the claim ProfileUpdateRequest makes.
        assertThat(patch("/me", ada.session(), "{}").statusCode())
                .isEqualTo(patch("/me", ada.session(), "{\"displayName\":null}").statusCode());

        assertThat(displayNameOf(ada.party()))
                .as("nothing was stored by any of them")
                .isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("both endpoints refuse without a session")
    void bothRefuseWithoutASession() throws Exception {
        assertThat(get("/me", null).statusCode()).isEqualTo(401);
        assertThat(patch("/me", null, "{\"displayName\":\"Anyone\"}").statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------

    private record Person(UUID party, IdentityId identity, String login, String session) {}

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String displayNameOf(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT display_name FROM party.party WHERE id = ?")) {
            select.setObject(1, party);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static String auditSummaryFor(UUID party, IdentityId actor) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT coalesce(string_agg(change_summary, ' '), '')"
                                        + " FROM platform.audit_record"
                                        + " WHERE operation = 'party.ProfileChanged'"
                                        + " AND target_id = ? AND actor_id = ?")) {
            select.setString(1, party.toString());
            select.setString(2, actor.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static long auditCountFor(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'party.ProfileChanged'"
                                        + " AND target_id = ?")) {
            select.setString(1, party.toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private Person givenAPerson(String displayName) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', ?, now())",
                    party,
                    displayName);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    identity,
                    party,
                    login);
        }
        return new Person(party, IdentityId.of(identity), login, givenASessionFor(IdentityId.of(identity)));
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
