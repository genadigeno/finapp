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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Every record written under an authenticated request names the person (`P1-TSK-022`,
 * {@code INV-AUD-01}).
 *
 * <h2>The property this task exists for, asserted over the ROWS rather than per action</h2>
 *
 * <p>Each preceding task asserted its own record — {@code SESSION_REVOKED} names the person,
 * {@code AUTHORIZATION_DENIED} names the person, a success is attributed to the proven identity.
 * <strong>Nothing asserted that no record written under an authenticated request names the
 * platform.</strong> That is a different claim from any of them: it is about the trail, not about
 * one operation, and it is the one that fails when somebody adds an audit call in a hurry.
 *
 * <p>ADR-0021's argument for why it matters: a record naming the platform for something a customer
 * did is <em>complete, plausible, about the wrong party, and permanent</em> ({@code INV-HIST-03}).
 * Nothing fails. Nobody notices until an investigation needs it.
 *
 * <h2>Scoped by correlation, so it can actually fail</h2>
 *
 * <p>The assertions run against the rows produced by <em>these</em> requests, found by their
 * correlation identifiers. A query scoped to the whole table would be dominated by other tests'
 * rows; a query scoped to nothing passes vacuously, which {@code P1-TSK-012} established is
 * indistinguishable from a query that is simply wrong.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("audit records name the actor (P1-TSK-022)")
class AuditNamesTheActorDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    // -----------------------------------------------------------------

    @Test
    @DisplayName("no record from an authenticated request is attributed to the platform")
    void authenticatedRequestsNameThePerson() throws Exception {
        IdentityId identity = givenAnIdentity();
        List<String> correlations = whenTheyUseTheirAccount(identity);

        List<Row> written = auditRowsFor(correlations);

        // Non-vacuity FIRST. Without it every assertion below passes over an empty result - the
        // "green while checking nothing" failure this repository has met repeatedly, and the exact
        // shape P1-TSK-012's gate found in a negative assertion whose query selected nothing.
        assertThat(written)
                .as("the flows must actually have produced audit records, or nothing below is a"
                        + " claim about anything")
                .isNotEmpty();

        assertThat(written)
                .as("a record naming the platform for something a person did is complete,"
                        + " plausible, about the wrong party, and permanent (INV-HIST-03, ADR-0021)")
                .allSatisfy(
                        row -> {
                            assertThat(row.actorId())
                                    .as("actor of " + row.operation())
                                    .isEqualTo(identity.value().toString());
                            assertThat(row.actorType()).isEqualTo("CUSTOMER");
                        });
    }

    @Test
    @DisplayName("every record carries the INV-AUD-01 fields, and joins to the request that caused it")
    void everyRecordCarriesTheAuditFields() throws Exception {
        IdentityId identity = givenAnIdentity();
        List<String> correlations = whenTheyUseTheirAccount(identity);

        List<Row> written = auditRowsFor(correlations);
        assertThat(written).isNotEmpty();

        // WHICH OF THESE IS LOAD-BEARING, established by probing rather than claimed.
        //
        // The three isNotBlank assertions are DEFENCE IN DEPTH, not the control. AuditRecord.bounded
        // refuses a blank at construction, so a blank target cannot reach the table at all: a
        // mutation blanking it was caught by theSweepIsBroad, because the record was never written.
        // They are what would catch a writer that stopped going through the domain type, and saying
        // so is better than letting them read as the thing that stops a meaningless target.
        //
        // The CORRELATION assertion is the one nothing else makes. A record carrying an identifier
        // that belongs to no flow satisfies every NOT NULL and every CHECK, and it is precisely the
        // record an investigator cannot use: INV-AUD-01 asks for correlation so the trail joins to
        // the request, and an identifier joining to nothing is worse than absent because a search
        // returns one row and stops.
        //
        // REASON IS DELIBERATELY NOT ASSERTED HERE, and the first version of this test claimed it
        // was - the display name said "and reason where the registry needs it" while nothing looked
        // at the column. The sixth occurrence of that pattern this phase, found by the completion
        // gate. Two things make the claim unassertable over this sweep rather than merely missing:
        // AuditRecord's constructor REFUSES a record whose action requires a reason and has none, so
        // one cannot reach the table (AuditRecordTest covers that); and the only two actions that
        // require one - IdentitySuspended and RoleAssigned - have no production caller in Phase 1,
        // the first because P1-TSK-028 owns its endpoint and the second because nothing calls
        // Authorization.assign outside tests. Saying so is better than an assertion that would pass
        // over an empty set.
        assertThat(written)
                .allSatisfy(
                        row -> {
                            assertThat(row.operation()).as("operation").isNotBlank();
                            assertThat(row.targetType()).as("targetType").isNotBlank();
                            assertThat(row.targetId()).as("targetId").isNotBlank();
                            assertThat(row.outcome()).as("outcome").isNotBlank();
                            assertThat(row.correlationId())
                                    .as("the record must be joinable to the request that caused it")
                                    .isIn(correlations);
                        });
    }

    @Test
    @DisplayName("the sweep covers more than one operation, so it is not one action in disguise")
    void theSweepIsBroad() throws Exception {
        IdentityId identity = givenAnIdentity();
        List<String> correlations = whenTheyUseTheirAccount(identity);

        TreeSet<String> operations = new TreeSet<>();
        auditRowsFor(correlations).forEach(row -> operations.add(row.operation()));

        // A sweep that happens to exercise one action would pass the assertions above while saying
        // nothing about the trail. Named rather than counted, because a count is satisfied by any
        // two rows - and these are the authenticated privileged actions Phase 1 actually has.
        assertThat(operations)
                .as("the sweep must span more than one aggregate, or it proves the property for the"
                        + " code that happened to be written most carefully")
                .contains("identity.SessionRevoked", "identity.MfaEnrolmentStarted");
    }

    // -----------------------------------------------------------------

    /**
     * Drives the authenticated surface and returns the correlation identifier of each request.
     *
     * <p><strong>Two modules and two aggregates</strong>, deliberately: a sweep confined to sessions
     * would prove the property for the code that happens to have been written most carefully.
     *
     * <p>{@code GET /v1/sessions} is <em>not</em> used, and that is worth stating rather than
     * leaving as an omission — listing your own sessions is not a privileged action and
     * {@code SessionQueries} records the decision not to audit it, because a record per read would
     * bury real decisions under traffic. Asserting over an endpoint that writes nothing would make
     * this sweep quietly smaller than it looks.
     */
    private List<String> whenTheyUseTheirAccount(IdentityId identity) throws Exception {
        Issued token = givenASessionFor(identity);
        Issued doomed = givenASessionFor(identity);
        List<String> correlations = new ArrayList<>();

        // A privileged action on a session: ending one of their own.
        correlations.add(
                correlationOf(
                        send(
                                HttpRequest.newBuilder(uri("/v1/sessions/" + doomed.id()))
                                        .DELETE(),
                                token.token())));

        // And one on a different aggregate, in a different flow: starting a second factor.
        correlations.add(
                correlationOf(
                        send(
                                HttpRequest.newBuilder(uri("/v1/me/mfa"))
                                        .POST(HttpRequest.BodyPublishers.noBody()),
                                token.token())));

        return correlations.stream().filter(java.util.Objects::nonNull).toList();
    }

    private record Row(
            String actorId,
            String actorType,
            String operation,
            String targetType,
            String targetId,
            String outcome,
            String correlationId) {}

    private static List<Row> auditRowsFor(List<String> correlations) throws SQLException {
        if (correlations.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(correlations.size(), "?"));
        List<Row> rows = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT actor_id, actor_type, operation, target_type, target_id,"
                                        + " outcome, correlation_id FROM platform.audit_record"
                                        + " WHERE correlation_id IN (" + placeholders + ")")) {
            for (int i = 0; i < correlations.size(); i++) {
                select.setString(i + 1, correlations.get(i));
            }
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    rows.add(
                            new Row(
                                    found.getString("actor_id"),
                                    found.getString("actor_type"),
                                    found.getString("operation"),
                                    found.getString("target_type"),
                                    found.getString("target_id"),
                                    found.getString("outcome"),
                                    found.getString("correlation_id")));
                }
            }
        }
        return rows;
    }

    // -----------------------------------------------------------------

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
        return http.send(
                request.header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String correlationOf(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElse(null);
    }

    /**
     * A session, and the identifier of the row it wrote.
     *
     * <p>The identifier comes from the aggregate this test just issued rather than from a lookup by
     * token hash. The first version did the lookup and found nothing, because how a token is hashed
     * is {@code JdbcSessionStore}'s business - a test reaching for that couples itself to an
     * implementation detail it has no reason to know.
     */
    private record Issued(String token, java.util.UUID id) {}

    private Issued givenASessionFor(IdentityId identity) throws SQLException {
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
        return new Issued(plaintext, session.id().value());
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
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
