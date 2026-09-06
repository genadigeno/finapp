package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code POST /v1/registrations}, over real HTTP against a real PostgreSQL (`P1-TSK-006`).
 *
 * <p>Real HTTP rather than MockMvc, for the reason {@code P0-TSK-024} recorded: MockMvc does not
 * run the container's error dispatch, so it reports a clean contract for paths that would return
 * the framework's own body in production. Real PostgreSQL because every claim here - one commit or
 * none, one effect per key, a refusal that is indistinguishable - is a claim about a transaction.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationEndpointDatabaseTest {

    @LocalServerPort private int port;

    @Autowired private DataSource dataSource;

    @Autowired private MeterRegistry meters;

    @Test
    @DisplayName("a registration creates exactly one Party, one Customer and one Identity")
    void oneOfEach() throws Exception {
        String login = someLogin();

        HttpResponse<String> response = register(login, "Ada Lovelace", aKey());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).as("the body is empty by design; see the controller").isEmpty();

        UUID partyId = partyIdFor(login);
        assertThat(partyId).isNotNull();
        assertThat(countCustomersOf(partyId)).isEqualTo(1);
        assertThat(countIdentities(login)).isEqualTo(1);
    }

    @Test
    @DisplayName("the identity references a party that exists - the foreign key the schema does not have")
    void referentialIntegrityHolds() throws Exception {
        // ADR-0029 puts no FK across the schema boundary, so nothing in the database would notice
        // an orphan. The registration transaction is what makes the reference true, and this is the
        // assertion that stands in for the constraint.
        String login = someLogin();
        register(login, "Ada Lovelace", aKey());

        assertThat(identitiesWithAMissingParty(login))
                .as("an identity whose party_id names no party would be invisible to the schema")
                .isZero();
        assertThat(countIdentities(login)).as("and the identity is really there to check").isEqualTo(1);
    }

    @Test
    @DisplayName("everything commits together: audit records and outbox rows share the transaction")
    void auditAndOutboxCommitWithTheFacts() throws Exception {
        String login = someLogin();

        register(login, "Ada Lovelace", aKey());
        UUID partyId = partyIdFor(login);

        assertThat(auditRecordsFor(login))
                .as("one for the party-and-customer decision, one for the identity")
                .isEqualTo(2);
        assertThat(outboxEventsFor(partyId))
                .as("PartyRegistered, CustomerOpened and IdentityCreated")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a retry with the same key creates nothing more and replays the response")
    void aRetryIsIdempotent() throws Exception {
        String login = someLogin();
        String key = aKey();

        HttpResponse<String> first = register(login, "Ada Lovelace", key);
        HttpResponse<String> second = register(login, "Ada Lovelace", key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).as("the original outcome, replayed").isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());

        assertThat(countIdentities(login)).as("exactly one effect, however many attempts").isEqualTo(1);
        assertThat(countCustomersOf(partyIdFor(login))).isEqualTo(1);
        assertThat(auditRecordsFor(login)).as("no second set of audit records").isEqualTo(2);
        assertThat(outboxEventsFor(partyIdFor(login))).as("no second set of events").isEqualTo(3);
    }

    @Test
    @DisplayName("a replay is indistinguishable from the original, header for header")
    void aReplayIsNotAnnounced() throws Exception {
        // A header saying "this was a replay" would tell a stranger holding the idempotency key
        // that the login identifier exists, which is exactly the oracle INV-IDN-07 forbids.
        String login = someLogin();
        String key = aKey();

        HttpResponse<String> first = register(login, "Ada Lovelace", key);
        HttpResponse<String> second = register(login, "Ada Lovelace", key);

        assertThat(distinguishingHeaders(second))
                .as("nothing about the response distinguishes a replay from the first call")
                .isEqualTo(distinguishingHeaders(first));
        assertThat(second.statusCode()).isEqualTo(first.statusCode());
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("the same key with a different request is a conflict, and creates nothing")
    void aDifferentRequestUnderAKnownKeyIsRefused() throws Exception {
        String login = someLogin();
        String key = aKey();

        register(login, "Ada Lovelace", key);
        HttpResponse<String> different = register(login, "Someone Else", key);

        assertThat(different.statusCode()).isEqualTo(409);
        assertThat(different.body()).contains("api.Conflict");
        assertThat(different.body())
                .as("the key is the caller's own input and is never echoed back")
                .doesNotContain(key);
        assertThat(countIdentities(login)).as("no second effect").isEqualTo(1);
    }

    @Test
    @DisplayName("a taken login identifier is refused, and looks like any other refusal")
    void aCollisionIsEnumerationSafe() throws Exception {
        String login = someLogin();
        register(login, "Ada Lovelace", aKey());

        HttpResponse<String> collision = register(login, "Someone Else", aKey());

        assertThat(collision.statusCode()).isEqualTo(422);
        assertThat(collision.body()).contains("party.RegistrationRefused");
        assertThat(collision.body())
                .as("nothing may say why, or the endpoint becomes an account-existence oracle")
                .doesNotContain(login)
                .doesNotContainIgnoringCase("exist")
                .doesNotContainIgnoringCase("taken")
                .doesNotContainIgnoringCase("duplicate")
                .doesNotContainIgnoringCase("identifier");
    }

    @Test
    @DisplayName("a refused registration leaves no Party, no Customer, no Identity and no event")
    void aRefusalLeavesNothingBehind() throws Exception {
        String login = someLogin();
        register(login, "Ada Lovelace", aKey());
        UUID original = partyIdFor(login);

        int partiesBefore = countParties();
        register(login, "Someone Else", aKey());

        assertThat(countParties())
                .as("the savepoint rollback undid the party and the customer of the failed attempt")
                .isEqualTo(partiesBefore);
        assertThat(countIdentities(login)).isEqualTo(1);
        assertThat(outboxEventsFor(original)).as("no event announces something that did not happen").isEqualTo(3);
    }

    @Test
    @DisplayName("a refusal is audited, with the attempted identifier and outcome FAILED")
    void aRefusalIsAudited() throws Exception {
        String login = someLogin();
        register(login, "Ada Lovelace", aKey());

        register(login, "Someone Else", aKey());

        assertThat(auditRecordsWithOutcome(login, "FAILED"))
                .as("the audit trail is the one place an attempted identifier belongs")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a retry of a refusal replays the refusal rather than re-attempting it")
    void aRefusalIsReplayed() throws Exception {
        String login = someLogin();
        String key = aKey();
        register(login, "Ada Lovelace", aKey());

        HttpResponse<String> first = register(login, "Someone Else", key);
        HttpResponse<String> retry = register(login, "Someone Else", key);

        assertThat(first.statusCode()).isEqualTo(422);
        assertThat(retry.statusCode()).isEqualTo(422);
        assertThat(auditRecordsWithOutcome(login, "FAILED"))
                .as("recorded FAILED, so the retry replays it rather than running the command again")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("no idempotency key is refused before the handler runs, and creates nothing")
    void theKeyIsRequired() throws Exception {
        String login = someLogin();

        HttpResponse<String> response = post(body(login, "Ada Lovelace"), null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.IdempotencyKeyRequired");
        assertThat(countIdentities(login)).isZero();
    }

    @Test
    @DisplayName("an unusable idempotency key is a validation failure and is never echoed")
    void anUnusableKeyIsRefused() throws Exception {
        String login = someLogin();

        HttpResponse<String> response = post(body(login, "Ada Lovelace"), "a".repeat(300));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed");
        assertThat(countIdentities(login)).isZero();
    }

    @Test
    @DisplayName("an invalid body is rejected at the boundary, naming fields and never values")
    void anInvalidBodyIsRejected() throws Exception {
        HttpResponse<String> response = register("ada@example.com", "Ada Lovelace", aKey());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed").contains("loginIdentifier");
        assertThat(response.body())
                .as("the rejected value is the caller's own input (INV-AUD-02)")
                .doesNotContain("ada@example.com");
    }

    @Test
    @DisplayName("no response and no audit record carries the person's name")
    void theNameNeverLeaves() throws Exception {
        String login = someLogin();
        HttpResponse<String> response = register(login, "Ada Lovelace", aKey());

        assertThat(response.body()).doesNotContain("Lovelace");
        assertThat(auditTextFor(login))
                .as("an audit record names identifiers, never the person (INV-AUD-02)")
                .doesNotContain("Lovelace");
        assertThat(outboxPayloadsFor(partyIdFor(login)))
                .as("an event stream reaches systems with different access control")
                .doesNotContain("Lovelace")
                .doesNotContain(login);
    }

    @Test
    @DisplayName("every event carries the flow's correlation identifier and a cause")
    void eventsAreTraceable() throws Exception {
        String login = someLogin();
        HttpResponse<String> response = register(login, "Ada Lovelace", aKey());
        String correlationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();

        assertThat(outboxCorrelationsFor(partyIdFor(login)))
                .as("all three events belong to the request that produced them (INV-EVT-03)")
                .containsOnly(correlationId);
        assertThat(auditCorrelationsFor(login)).containsOnly(correlationId);
    }

    @Test
    @DisplayName("the outcome is counted, and the tag vocabulary is a fixed set")
    void theOutcomeIsCounted() throws Exception {
        // DOD-OBS asks for signals verified against a running instance rather than asserted in
        // code. This is that, at the cheapest honest scale: the counter is registered by use, so a
        // test that never registered would be asserting against an empty registry.
        String login = someLogin();
        register(login, "Ada Lovelace", aKey());
        register(login, "Someone Else", aKey());

        Counter created =
                meters.find(RegistrationService.REGISTRATION_COUNTER).tag("outcome", "created").counter();
        Counter refused =
                meters.find(RegistrationService.REGISTRATION_COUNTER).tag("outcome", "refused").counter();

        assertThat(created).as("a created registration is counted").isNotNull();
        assertThat(created.count()).isPositive();
        assertThat(refused).as("a refused one is counted separately").isNotNull();
        assertThat(refused.count()).isPositive();

        assertThat(
                        meters.find(RegistrationService.REGISTRATION_COUNTER).counters().stream()
                                .flatMap(counter -> counter.getId().getTags().stream())
                                .map(io.micrometer.core.instrument.Tag::getKey)
                                .distinct()
                                .toList())
                .as("no tag value may derive from a request (ADR-0018); `outcome` is a fixed enum")
                .containsExactly("outcome");
    }

    // -----------------------------------------------------------------

    private HttpResponse<String> register(String login, String displayName, String key)
            throws IOException, InterruptedException {
        return post(body(login, displayName), key);
    }

    private static String body(String login, String displayName) {
        return "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"" + displayName + "\"}";
    }

    private HttpResponse<String> post(String body, String key)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/registrations"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** A fresh identifier per test, so tests never contend for the same total unique index. */
    private static String someLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String aKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Every response header and value except the ones that legitimately differ per request.
     *
     * <p>Names <strong>and</strong> values. The first version of this compared names alone and a
     * mutation walked straight through it: a header set to {@code false} on the original and
     * {@code true} on the replay has the same name on both, and that is exactly the oracle
     * {@code INV-IDN-07} forbids. Comparing names was checking the shape of the disclosure rather
     * than the disclosure.
     *
     * <p>The correlation identifiers and {@code Date} are excluded because they are per-request by
     * design; everything else must be identical.
     */
    private static Map<String, List<String>> distinguishingHeaders(HttpResponse<String> response) {
        Set<String> perRequest =
                Set.of("x-correlation-id", "x-client-correlation-id", "date", "keep-alive");
        Map<String, List<String>> headers = new java.util.TreeMap<>();
        response.headers()
                .map()
                .forEach(
                        (name, values) -> {
                            if (!perRequest.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                                headers.put(name.toLowerCase(java.util.Locale.ROOT), values);
                            }
                        });
        return headers;
    }

    private UUID partyIdFor(String login) throws SQLException {
        return query(
                "SELECT party_id FROM identity.identity WHERE login_identifier = ?",
                rows -> rows.next() ? (UUID) rows.getObject(1) : null,
                login);
    }

    private int countIdentities(String login) throws SQLException {
        return query(
                "SELECT count(*) FROM identity.identity WHERE login_identifier = ?",
                this::firstInt,
                login);
    }

    private int countCustomersOf(UUID partyId) throws SQLException {
        return query(
                "SELECT count(*) FROM party.customer WHERE party_id = ?", this::firstInt, partyId);
    }

    private int countParties() throws SQLException {
        return query("SELECT count(*) FROM party.party", this::firstInt);
    }

    /**
     * Orphans belonging to <em>this</em> registration, not to the whole database.
     *
     * <p>Scoped deliberately, and the first version was not - it asked whether any orphan existed
     * anywhere, passed alone and failed in the full tier. {@code PartyAndIdentitySchemaDatabaseTest}
     * creates orphans <strong>on purpose</strong>, to prove ADR-0029's missing foreign key really is
     * missing. Both facts are true and they are about different things: the schema permits an
     * orphan, and the registration transaction does not produce one.
     */
    private int identitiesWithAMissingParty(String login) throws SQLException {
        return query(
                "SELECT count(*) FROM identity.identity i"
                        + " WHERE i.login_identifier = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM party.party p WHERE p.id = i.party_id)",
                this::firstInt,
                login);
    }

    private int auditRecordsFor(String login) throws SQLException {
        return query(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ?",
                this::firstInt,
                login);
    }

    private int auditRecordsWithOutcome(String login, String outcome) throws SQLException {
        return query(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND outcome = ?",
                this::firstInt,
                login,
                outcome);
    }

    private String auditTextFor(String login) throws SQLException {
        return query(
                "SELECT coalesce(string_agg(coalesce(reason, '') || ' '"
                        + " || coalesce(change_summary, ''), ' '), '')"
                        + " FROM platform.audit_record WHERE target_id = ?",
                rows -> rows.next() ? rows.getString(1) : "",
                login);
    }

    private List<String> auditCorrelationsFor(String login) throws SQLException {
        return query(
                "SELECT correlation_id FROM platform.audit_record WHERE target_id = ?",
                this::allStrings,
                login);
    }

    private int outboxEventsFor(UUID partyId) throws SQLException {
        return query(eventsOfOneRegistration("count(*)"), this::firstInt, partyId.toString());
    }

    private String outboxPayloadsFor(UUID partyId) throws SQLException {
        return query(
                eventsOfOneRegistration("coalesce(string_agg(convert_from(payload, 'UTF8'), ' '), '')"),
                rows -> rows.next() ? rows.getString(1) : "",
                partyId.toString());
    }

    private List<String> outboxCorrelationsFor(UUID partyId) throws SQLException {
        return query(
                eventsOfOneRegistration("correlation_id"), this::allStrings, partyId.toString());
    }

    /**
     * The three events of one registration.
     *
     * <p>They are about three different aggregates, so no single {@code aggregate_id} finds them
     * all. What joins them is the party identifier, which every one of the three payloads carries -
     * and having to reach into the payload to find them is itself a small confirmation that the
     * events really are separate facts about separate aggregates rather than one event in three
     * pieces.
     */
    private static String eventsOfOneRegistration(String projection) {
        return "SELECT "
                + projection
                + " FROM platform.outbox_event"
                + " WHERE convert_from(payload, 'UTF8') LIKE '%' || ? || '%'";
    }

    private int firstInt(ResultSet rows) throws SQLException {
        rows.next();
        return rows.getInt(1);
    }

    private List<String> allStrings(ResultSet rows) throws SQLException {
        List<String> values = new ArrayList<>();
        while (rows.next()) {
            values.add(rows.getString(1));
        }
        return values;
    }

    private <T> T query(String sql, RowMapper<T> mapper, Object... arguments) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return mapper.map(rows);
            }
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }
}
