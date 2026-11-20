package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.OperatorCollection;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The reviewers' queues over HTTP on PostgreSQL (`P17-TSK-029`, gaps G-60, G-64, G-65): the open KYC cases, a customer's latest
 * case and the counterparty screenings awaiting a person - each §7 collection walked a page at a time over a tie planted at one
 * instant, every row served once and in its order; the filters and the request rules refused {@code 422}; each answer
 * recorded; a screening row three fields; and the doors the reviewer's alone, on a second factor.
 *
 * <p>The suites share one database, so each walk starts at a cursor placed just before its own tie - an instant no other suite
 * writes - and asserts only what it planted. The cursor is built here as the desk builds it: a test may, a client may not.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the KYC reviewers' queues over HTTP (P17-TSK-029)")
class KycQueueApiDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String NIL = "00000000-0000-0000-0000-000000000000";
    private static final Pattern CASE_ID = Pattern.compile("\"caseId\":\"([0-9a-f-]{36})\"");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z_]+)\"");
    private static final Pattern SCREENING_ID = Pattern.compile("\"screeningId\":\"([0-9a-f-]{36})\"");
    private static final Pattern SCREENING_ROW =
            Pattern.compile("\\{\"screeningId\":\"[0-9a-f-]{36}\",\"status\":\"IN_REVIEW\",\"raisedAt\":\"[^\"]+\"}");
    private static final Pattern NEXT = Pattern.compile("\"nextCursor\":\"([^\"]+)\"");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    // ------------------------------------------------------------------ the review queue

    @Test
    @DisplayName("the queue walks a tie at one instant once, in id order, a page at a time - two pages of one row are the first"
            + " page of two - every row open, every page recorded")
    void theQueueWalksItsTieOnce() throws Exception {
        Instant tie = aPlantingInstant();
        List<String> planted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            planted.add(givenAnOpenCaseAt(tie).id().value().toString());
        }
        planted.sort(Comparator.naturalOrder());
        IdentityId reviewer = givenAReviewer();
        String session = givenASessionFor(reviewer, AssuranceLevel.MULTI_FACTOR);
        String far = OperatorCollection.cursor(KycQueueDesk.QUEUE, micros(tie), NIL);

        HttpResponse<String> first = get("/operator/kyc/cases?limit=2&cursor=" + encoded(far), session);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(all(CASE_ID, first.body())).containsExactly(planted.get(0), planted.get(1));
        HttpResponse<String> second = get("/operator/kyc/cases?limit=2&cursor=" + encoded(next(first.body())), session);
        assertThat(all(CASE_ID, second.body())).as("resumed strictly after the last served, the tie broken by id")
                .first().isEqualTo(planted.get(2));

        List<String> inPieces = new ArrayList<>();
        String cursor = far;
        for (int page = 0; page < 3; page++) {
            HttpResponse<String> one = get("/operator/kyc/cases?limit=1&cursor=" + encoded(cursor), session);
            inPieces.addAll(all(CASE_ID, one.body()));
            if (page < 2) {
                cursor = next(one.body());
            }
        }
        assertThat(inPieces).as("a row at a time, the same rows in the same order").containsExactlyElementsOf(planted);
        assertThat(all(STATUS, first.body() + second.body())).as("the queue is open cases")
                .allMatch(status -> KycQueueDesk.OPEN.contains(KycCaseStatus.valueOf(status)));
        assertThat(auditCountByActor("kyc.ReviewQueueRead", reviewer)).as("one record a page, never a row").isEqualTo(5);
    }

    @Test
    @DisplayName("a status serves that open status alone; a decided or unknown status, a limit past 200 and another list's cursor"
            + " are 422, recording nothing")
    void theStatusAndTheRequestRules() throws Exception {
        Instant tie = aPlantingInstant();
        KycCase open = givenAnOpenCaseAt(tie);
        KycCase inReview = givenAnOpenCaseAt(tie);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            cases.moveStatus(app, inReview.id(), KycCaseStatus.OPEN, KycCaseStatus.CHECKS_IN_PROGRESS, Instant.now());
            cases.moveStatus(app, inReview.id(), KycCaseStatus.CHECKS_IN_PROGRESS, KycCaseStatus.IN_REVIEW, Instant.now());
            app.commit();
        }
        IdentityId reviewer = givenAReviewer();
        String session = givenASessionFor(reviewer, AssuranceLevel.MULTI_FACTOR);
        String far = encoded(OperatorCollection.cursor(KycQueueDesk.QUEUE, micros(tie), NIL));

        HttpResponse<String> filtered = get("/operator/kyc/cases?status=IN_REVIEW&limit=200&cursor=" + far, session);
        assertThat(filtered.statusCode()).as(filtered.body()).isEqualTo(200);
        assertThat(all(CASE_ID, filtered.body())).contains(inReview.id().value().toString())
                .doesNotContain(open.id().value().toString());
        assertThat(all(STATUS, filtered.body())).containsOnly("IN_REVIEW");

        String backlogCursor = encoded(OperatorCollection.cursor(KycQueueDesk.BACKLOG, micros(tie), NIL));
        for (String refused : List.of("/operator/kyc/cases?status=APPROVED", "/operator/kyc/cases?status=SOMETHING",
                "/operator/kyc/cases?limit=201", "/operator/kyc/cases?cursor=" + backlogCursor,
                "/operator/kyc/counterparty-screenings?status=RELEASED", "/operator/kyc/counterparty-screenings?limit=201")) {
            HttpResponse<String> answer = get(refused, session);
            assertThat(answer.statusCode()).as(refused + " " + answer.body()).isEqualTo(422);
            assertThat(answer.body()).contains("api.ValidationFailed");
        }
        assertThat(auditCountByActor("kyc.ReviewQueueRead", reviewer) + auditCountByActor("kyc.ScreeningBacklogRead", reviewer))
                .as("the one page served is the one recorded")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ a customer's latest case

    @Test
    @DisplayName("a customer's latest case is served and recorded kyc.CaseRead; an unknown customer is 404, recording nothing")
    void aCustomersLatestCase() throws Exception {
        KycCase opened = givenAnOpenCaseAt(Instant.now().minusSeconds(60));
        IdentityId reviewer = givenAReviewer();
        String session = givenASessionFor(reviewer, AssuranceLevel.MULTI_FACTOR);

        HttpResponse<String> latest = get("/operator/customers/" + opened.customerId() + "/kyc-case", session);

        assertThat(latest.statusCode()).as(latest.body()).isEqualTo(200);
        assertThat(all(CASE_ID, latest.body())).containsExactly(opened.id().value().toString());
        assertThat(latest.body()).contains("\"customerId\":\"" + opened.customerId() + "\"").contains("\"status\":\"OPEN\"");
        assertThat(auditSummaryOf("kyc.CaseRead", opened.id().value().toString(), reviewer))
                .isEqualTo("customer=" + opened.customerId() + ", latest-of-customer");

        HttpResponse<String> unknown = get("/operator/customers/" + IDS.next() + "/kyc-case", session);
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
        assertThat(auditCountByActor("kyc.CaseRead", reviewer)).as("the miss served nothing and recorded nothing").isEqualTo(1);
    }

    // ------------------------------------------------------------------ the screening backlog

    @Test
    @DisplayName("the backlog walks a tie once, oldest raised first, a screening row its id, status and raised instant alone;"
            + " a screening already decided is no row of it")
    void theBacklogWalksItsTieOnce() throws Exception {
        Instant tie = aPlantingInstant();
        List<String> planted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            planted.add(givenAScreening(tie, "IN_REVIEW").toString());
        }
        planted.sort(Comparator.naturalOrder());
        String clear = givenAScreening(tie, "CLEAR").toString();
        IdentityId reviewer = givenAReviewer();
        String session = givenASessionFor(reviewer, AssuranceLevel.MULTI_FACTOR);
        String far = OperatorCollection.cursor(KycQueueDesk.BACKLOG, micros(tie), NIL);

        HttpResponse<String> first = get("/operator/kyc/counterparty-screenings?status=IN_REVIEW&limit=2&cursor="
                + encoded(far), session);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        HttpResponse<String> second = get("/operator/kyc/counterparty-screenings?limit=2&cursor="
                + encoded(next(first.body())), session);

        List<String> walked = new ArrayList<>(all(SCREENING_ID, first.body()));
        walked.addAll(all(SCREENING_ID, second.body()));
        assertThat(walked.subList(0, 3)).as("the tie, once, in id order").containsExactlyElementsOf(planted);
        assertThat(walked).doesNotContain(clear);
        for (String body : List.of(first.body(), second.body())) {
            assertThat(SCREENING_ROW.matcher(body).results().count())
                    .as("tipping-off: every row is its id, status and raised instant, and nothing else")
                    .isEqualTo(all(SCREENING_ID, body).size());
        }
        assertThat(first.body()).contains("\"raisedAt\":\"" + tie + "\"");
        assertThat(auditCountByActor("kyc.ScreeningBacklogRead", reviewer)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the doors

    @Test
    @DisplayName("the three reads are the reviewer's alone, on a second factor: no role 403, a password session refused, no"
            + " session 401 - recording nothing")
    void theDoorsAreTheReviewersOnASecondFactor() throws Exception {
        KycCase opened = givenAnOpenCaseAt(Instant.now().minusSeconds(60));
        String roleless = givenASessionFor(givenAnIdentity(), AssuranceLevel.MULTI_FACTOR);
        IdentityId reviewer = givenAReviewer();
        String password = givenASessionFor(reviewer, AssuranceLevel.PASSWORD);

        for (String route : List.of("/operator/kyc/cases", "/operator/customers/" + opened.customerId() + "/kyc-case",
                "/operator/kyc/counterparty-screenings")) {
            assertThat(get(route, roleless).statusCode()).as(route + " without the permission").isEqualTo(403);
            HttpResponse<String> stepUp = get(route, password);
            assertThat(stepUp.statusCode()).as(route + " on a password").isIn(401, 403);
            assertThat(stepUp.body()).contains("identity.AssuranceRequired");
            assertThat(get(route, null).statusCode()).as(route + " without a session").isEqualTo(401);
        }
        assertThat(auditCountByActor("kyc.ReviewQueueRead", reviewer) + auditCountByActor("kyc.CaseRead", reviewer)
                + auditCountByActor("kyc.ScreeningBacklogRead", reviewer)).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    /** An instant no other suite writes: a whole second in 2001, drawn per case. */
    private static Instant aPlantingInstant() {
        return Instant.parse("2001-01-01T00:00:00Z").plusSeconds(RANDOMNESS.nextInt(300_000_000));
    }

    private KycCase givenAnOpenCaseAt(Instant openedAt) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'PENDING', now() - interval '1 hour', now() - interval '1 hour')", customer, party);
            app.setAutoCommit(false);
            KycCase opened = cases.openOrConverge(app,
                    KycCase.open(IDS, Clock.fixed(openedAt, ZoneOffset.UTC), customer, KycCaseKind.KYC)).kycCase();
            app.commit();
            return opened;
        }
    }

    /** A screening decided at {@code at} by the automatic outcome: {@code IN_REVIEW} awaiting a person, or {@code CLEAR}. */
    private static UUID givenAScreening(Instant at, String status) throws SQLException {
        UUID id = IDS.next();
        byte[] ciphertext = new byte[24];
        RANDOMNESS.nextBytes(ciphertext);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO kyc.counterparty_screening (id, request_reference, subject_ciphertext, subject_nonce,"
                    + " subject_key_version, country, entity_type, payee_verdict, status, review_reason, decision_basis,"
                    + " policy_version, decided_at, attempts, requested_at)"
                    + " VALUES (?, ?, ?, ?, 1, 'US', 'INDIVIDUAL', 'MATCH', ?, ?, 'AUTOMATIC', 'kyc-2026-09', ?, 1, ?)",
                    id, "queue-" + id, ciphertext, new byte[12], status, "IN_REVIEW".equals(status) ? "HIT" : null,
                    Timestamp.from(at), Timestamp.from(at));
        }
        return id;
    }

    private IdentityId givenAReviewer() throws SQLException {
        IdentityId reviewer = givenAnIdentity();
        try (CorrelationContext.Scope correlation =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, reviewer, RoleName.KYC_REVIEWER, reviewer, "fixture");
            app.commit();
        }
        return reviewer;
    }

    private String givenASessionFor(IdentityId identity, AssuranceLevel assurance) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session.Draft session = Session.issue(IDS, Clock.systemUTC(), identity, SessionToken.of(plaintext), assurance,
                SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            // Back-dated: the P1-TSK-031 remedy for the container clock's backwards corrections.
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '1 hour', now() - interval '1 hour')",
                    identity, party, "q" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    // ------------------------------------------------------------------ plumbing

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> all(Pattern pattern, String body) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String next(String body) {
        Matcher matcher = NEXT.matcher(body);
        assertThat(matcher.find()).as("a next page is named: %s", body).isTrue();
        return matcher.group(1);
    }

    private static String encoded(String cursor) {
        return URLEncoder.encode(cursor, StandardCharsets.UTF_8);
    }

    private static String micros(Instant instant) {
        return Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, instant));
    }

    private static long auditCountByActor(String operation, IdentityId actor) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record WHERE operation = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, actor.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String auditSummaryOf(String operation, String targetId, IdentityId actor) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT change_summary FROM platform.audit_record"
                        + " WHERE operation = ? AND target_id = ? AND actor_id = ?")) {
            select.setString(1, operation);
            select.setString(2, targetId);
            select.setString(3, actor.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("recorded").isTrue();
                return rows.getString(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
