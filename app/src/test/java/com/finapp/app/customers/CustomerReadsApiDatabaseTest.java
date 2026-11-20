package com.finapp.app.customers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator's customer reads over HTTP (`P17-TSK-026`): each route refused to a role without {@code CUSTOMER_READ} and to a
 * customer, each asking for a second factor; the lookup exact by each kind, its value in the body and in no URL, record or log
 * line; the profile identifier-first - no field of {@code DATA_CLASSIFICATION.md}'s personal data; the consent history a §7
 * collection walked once, newest first; and every answer recorded.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the operator's customer reads over HTTP (P17-TSK-026)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class CustomerReadsApiDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private record Person(UUID id, UUID party, String login, String token) {}

    /** A customer with a login, a KYC case, an account and a consent history. */
    private record Subject(UUID party, UUID customer, UUID identity, String login, UUID kycCase, UUID account) {}

    @Test
    @DisplayName("a negative per route: a KYC reviewer and a customer are refused 403 on the lookup, the profile and the consents")
    void everyRouteRefusesWithoutTheRead() throws Exception {
        Subject subject = subject();
        for (Person refused : List.of(person(RoleName.KYC_REVIEWER, AssuranceLevel.MULTI_FACTOR), customerSession())) {
            assertThat(lookup(refused, "CUSTOMER_ID", subject.customer().toString()).statusCode()).isEqualTo(403);
            assertThat(get(refused, "/v1/operator/customers/" + subject.customer()).statusCode()).isEqualTo(403);
            assertThat(get(refused, "/v1/operator/parties/" + subject.party() + "/consents").statusCode()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("a support agent on a password session is asked for a second factor on every route, before anything is read")
    void everyRouteAsksForASecondFactor() throws Exception {
        Subject subject = subject();
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.PASSWORD);
        for (HttpResponse<String> refused : List.of(lookup(agent, "CUSTOMER_ID", subject.customer().toString()),
                get(agent, "/v1/operator/customers/" + subject.customer()),
                get(agent, "/v1/operator/parties/" + subject.party() + "/consents"))) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
            assertThat(refused.body()).contains("identity.AssuranceRequired");
        }
        assertThat(recordedBy(agent)).doesNotContain("party.Customer").doesNotContain("consent.HistoryRead");
    }

    @Test
    @DisplayName("the lookup finds the customer by each kind, exactly; a near miss, an unknown and a malformed value are 404; the"
            + " value travels in the body and is in no URL, record or log line; every probe recorded")
    void theLookupIsExact(CapturedOutput output) throws Exception {
        Subject subject = subject();
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.MULTI_FACTOR);

        for (String[] asked : List.of(new String[] {"CUSTOMER_ID", subject.customer().toString()},
                new String[] {"PARTY_ID", subject.party().toString()},
                new String[] {"LOGIN_IDENTIFIER", subject.login()},
                new String[] {"ACCOUNT_ID", subject.account().toString()})) {
            HttpResponse<String> found = lookup(agent, asked[0], asked[1]);
            assertThat(found.statusCode()).as(asked[0] + " " + found.body()).isEqualTo(200);
            assertThat(found.body()).isEqualTo("{\"customerId\":\"" + subject.customer() + "\"}");
        }
        for (String[] missed : List.of(new String[] {"LOGIN_IDENTIFIER", subject.login().toUpperCase()},
                new String[] {"LOGIN_IDENTIFIER", subject.login().substring(0, subject.login().length() - 1)},
                new String[] {"LOGIN_IDENTIFIER", " " + subject.login()},
                new String[] {"CUSTOMER_ID", subject.customer().toString().toUpperCase()},
                new String[] {"CUSTOMER_ID", subject.party().toString()},
                new String[] {"PARTY_ID", subject.customer().toString()},
                new String[] {"ACCOUNT_ID", "not-an-id"},
                new String[] {"CUSTOMER_ID", UUID.randomUUID().toString()})) {
            HttpResponse<String> missing = lookup(agent, missed[0], missed[1]);
            assertThat(missing.statusCode()).as(missed[0] + " " + missed[1]).isEqualTo(404);
            assertThat(missing.body()).contains("api.NotFound").doesNotContain(subject.login());
        }
        HttpResponse<String> unknownKind = lookup(agent, "EMAIL", subject.login());
        assertThat(unknownKind.statusCode()).isEqualTo(422);

        String recorded = recordedBy(agent);
        assertThat(count(recorded, "party.CustomerLookedUp")).as("every probe, matched or not; the 422 none").isEqualTo(12);
        assertThat(recorded).contains("party.CustomerLookedUp party.Customer " + subject.customer() + " kind=LOGIN_IDENTIFIER,"
                + " matched=true").contains("party.CustomerLookedUp party.Customer no-match kind=CUSTOMER_ID, matched=false")
                .doesNotContain(subject.login());
        assertThat(output.getAll()).as("no log line carries the login identifier").doesNotContain(subject.login());
    }

    @Test
    @DisplayName("a party's identifier finds its live relationship before any that ended, and the newest ended one when none"
            + " lives")
    void aPartyFindsItsLiveRelationshipFirst() throws Exception {
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.MULTI_FACTOR);
        UUID party = IDS.next();
        UUID live = IDS.next();
        UUID closedLater = IDS.next();
        UUID rejectedOnly = IDS.next();
        UUID lonelyParty = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            for (UUID id : List.of(party, lonelyParty)) {
                execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Many"
                        + " Relationships', now())", id);
            }
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'ACTIVE', now() - interval '2 days', now() - interval '2 days')", live, party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'CLOSED', now() - interval '1 day', now())", closedLater, party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'REJECTED', now(), now())", rejectedOnly, lonelyParty);
        }

        assertThat(lookup(agent, "PARTY_ID", party.toString()).body())
                .as("the live one, though a closed one opened later").isEqualTo("{\"customerId\":\"" + live + "\"}");
        assertThat(lookup(agent, "PARTY_ID", lonelyParty.toString()).body())
                .as("none lives: the newest").isEqualTo("{\"customerId\":\"" + rejectedOnly + "\"}");
    }

    @Test
    @DisplayName("the profile is identifiers and statuses only - no name, login identifier or contact detail - and recorded")
    void theProfileIsIdentifierFirst() throws Exception {
        Subject subject = subject();
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.MULTI_FACTOR);

        HttpResponse<String> answered = get(agent, "/v1/operator/customers/" + subject.customer());

        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
        JsonNode profile = json.readTree(answered.body());
        assertThat(fieldNames(profile)).containsExactly("customerId", "status", "openedAt", "statusChangedAt", "partyId",
                "partyKind", "identities", "latestKycCase");
        assertThat(profile.get("customerId").asString()).isEqualTo(subject.customer().toString());
        assertThat(profile.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(profile.get("partyId").asString()).isEqualTo(subject.party().toString());
        assertThat(profile.get("partyKind").asString()).isEqualTo("PERSON");
        assertThat(profile.get("identities").get(0).get("identityId").asString()).isEqualTo(subject.identity().toString());
        assertThat(profile.get("identities").get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(fieldNames(profile.get("identities").get(0))).containsExactly("identityId", "status");
        assertThat(profile.get("latestKycCase").get("caseId").asString()).isEqualTo(subject.kycCase().toString());
        assertThat(profile.get("latestKycCase").get("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(answered.body()).as("DATA_CLASSIFICATION.md's personal data: party.display_name,"
                        + " identity.login_identifier, the contact channel's value")
                .doesNotContain("Customer Read Subject").doesNotContain(subject.login()).doesNotContain("displayName")
                .doesNotContain("login").doesNotContain("contact").doesNotContain("@");

        assertThat(recordedBy(agent)).contains("party.CustomerRead party.Customer " + subject.customer() + " party="
                + subject.party() + ", identities=1, kycCase=" + subject.kycCase());

        HttpResponse<String> unknown = get(agent, "/v1/operator/customers/" + UUID.randomUUID());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(get(agent, "/v1/operator/customers/not-an-id").statusCode()).isEqualTo(404);
        assertThat(count(recordedBy(agent), "party.CustomerRead")).as("a customer that does not exist is no read of anyone")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the consent history is a §7 collection: newest first, walked once across pages, each page recorded; an unknown"
            + " party is 404; another collection's cursor and an oversized limit are 422")
    void theConsentHistoryIsACollection() throws Exception {
        Subject subject = subject();
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.MULTI_FACTOR);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            HttpResponse<String> page = get(agent, "/v1/operator/parties/" + subject.party() + "/consents?limit=2"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            JsonNode body = json.readTree(page.body());
            body.get("items").forEach(item -> {
                assertThat(fieldNames(item)).containsExactly("consentRecordId", "purpose", "action", "textVersion", "recordedAt");
                walked.add(item.get("purpose").asString() + "/" + item.get("action").asString());
            });
            cursor = body.has("nextCursor") ? body.get("nextCursor").asString() : null;
            pages++;
        } while (cursor != null);

        assertThat(walked).as("newest first by the history's own order, each fact once")
                .containsExactly("KYC_PROCESSING/WITHDRAWAL", "SCREENING/GRANT", "KYC_PROCESSING/GRANT");
        assertThat(pages).isEqualTo(2);
        assertThat(count(recordedBy(agent), "consent.HistoryRead")).as("each page").isEqualTo(2);

        assertThat(get(agent, "/v1/operator/parties/" + UUID.randomUUID() + "/consents").statusCode()).isEqualTo(404);
        assertThat(get(agent, "/v1/operator/parties/" + subject.party() + "/consents?limit=201").statusCode()).isEqualTo(422);
        assertThat(get(agent, "/v1/operator/parties/" + subject.party() + "/consents?cursor=djF8aWRlbnRpdHkuZGlyZWN0b3J5fDE")
                .statusCode()).isEqualTo(422);
        assertThat(count(recordedBy(agent), "consent.HistoryRead")).as("refusals and unknowns record nothing").isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private HttpResponse<String> get(Person who, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + who.token()).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The lookup, as the console sends it: the value in the body of a POST to a fixed path. */
    private HttpResponse<String> lookup(Person who, String kind, String value) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("kind", kind, "value", value));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/operator/customers/lookup"))
                .header("Authorization", "Bearer " + who.token()).header("Accept", "application/json")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    /** Every audit record this person wrote, as text: operation, target type, target, summary. */
    private static String recordedBy(Person actor) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap();
                PreparedStatement select = root.prepareStatement("SELECT string_agg(operation || ' ' || target_type || ' '"
                        + " || target_id || ' ' || coalesce(change_summary, ''), E'\\n') FROM platform.audit_record"
                        + " WHERE actor_id = ?")) {
            select.setString(1, actor.id().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return String.valueOf(rows.getString(1));
            }
        }
    }

    /**
     * A customer: party, ACTIVE relationship, ACTIVE identity, a first KYC case APPROVED a month ago by its recorded decision
     * (so the projection sweep finds the ACTIVE status authorized), the latest case IN_REVIEW, a wallet and three consent facts.
     */
    private Subject subject() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID identity = IDS.next();
        UUID firstCase = IDS.next();
        UUID kycCase = IDS.next();
        UUID account = IDS.next();
        String login = "cr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON',"
                    + " 'Customer Read Subject', now())", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'ACTIVE', now(), now())", customer, party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party, login);
            execute(app, "INSERT INTO kyc.kyc_case (id, customer_id, case_kind, status, policy_version, opened_at,"
                    + " status_changed_at) VALUES (?, ?, 'KYC', 'APPROVED', 'p', now() - interval '30 days',"
                    + " now() - interval '30 days')", firstCase, customer);
            execute(app, "INSERT INTO kyc.kyc_decision (id, case_id, outcome, decision_basis, decided_by, reason, policy_version,"
                    + " decided_at) VALUES (?, ?, 'APPROVED', 'AUTOMATIC', NULL, 'fixture', 'p', now() - interval '30 days')",
                    IDS.next(), firstCase);
            execute(app, "INSERT INTO kyc.kyc_case (id, customer_id, case_kind, status, policy_version, opened_at,"
                    + " status_changed_at) VALUES (?, ?, 'KYC', 'IN_REVIEW', 'p', now(), now())", kycCase, customer);
            execute(app, "INSERT INTO accounts.customer_account (id, customer_id, product_type, status, opened_at,"
                    + " status_changed_at) VALUES (?, ?, 'WALLET', 'ACTIVE', now(), now())", account, customer);
            JdbcConsentStore consents = new JdbcConsentStore();
            consents.append(app, ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 1));
            consents.append(app, ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.SCREENING, 1));
            consents.append(app, ConsentRecord.withdrawal(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 1));
        }
        return new Subject(party, customer, identity, login, kycCase, account);
    }

    /** A role holder with a session at {@code assurance}; no factor enrolled. */
    private Person person(RoleName role, AssuranceLevel assurance) throws SQLException {
        Person person = loginWithSession(assurance);
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-026-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(person.id()), role, IdentityId.of(person.id()), "test fixture");
            app.commit();
        }
        return person;
    }

    /** A customer's session: an identity holding no role. */
    private Person customerSession() throws SQLException {
        return loginWithSession(AssuranceLevel.MULTI_FACTOR);
    }

    private Person loginWithSession(AssuranceLevel assurance) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        String login = "cs" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Support Person',"
                    + " now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party, login);
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, CLOCK, IdentityId.of(identity), SessionToken.of(plaintext),
                    assurance, SessionPolicy.current()));
        }
        return new Person(identity, party, login, plaintext);
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }
}
