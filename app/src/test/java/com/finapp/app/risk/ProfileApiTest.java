package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.CustomerRiskProfileVersion;
import com.finapp.risk.JdbcCustomerRiskProfileStore;
import com.finapp.risk.ProfileCause;
import com.finapp.risk.RiskRating;
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
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
 * The analyst's customer risk profile door, end to end (`P13-TSK-007`; {@code PHASE_13_PLAN.md} sections 9, 11;
 * {@code RiskMonitoringEndpointDatabaseTest}'s shape): a party's history {@code 200} and recorded
 * {@code risk.ProfileRead}; a party with no profile, and a malformed id, the uniform {@code 404 risk.NotFound}; the
 * analyst's own party {@code 403 risk.SelfDealingRefused}, recorded {@code FAILED} and never naming the party; another
 * desk {@code 403}; no session {@code 401}.
 *
 * <p>Written and owed under C5 (the standing criterion-7 deviation): the database tier is not run per task.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the customer risk profile door (P13-TSK-007)")
class ProfileApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final JdbcCustomerRiskProfileStore STORE = new JdbcCustomerRiskProfileStore();

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("an analyst reads a party's history: 200 with the current rating and every version, recorded"
            + " risk.ProfileRead once - the summary a count, never a rating")
    void anAnalystReadsTheHistory() throws Exception {
        Session analyst = sessionWith(RoleName.RISK_ANALYST);
        UUID party = IDS.next();
        stand(party, RiskRating.HIGH, RiskRating.MEDIUM);

        HttpResponse<String> read = get(door(party), analyst.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"partyId\":\"" + party + "\"")
                .contains("\"current\":{\"version\":2,\"rating\":\"MEDIUM\"}")
                .contains("\"rating\":\"HIGH\"").contains("\"cause\":\"DISPOSITION\"");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ProfileRead'"
                + " AND outcome = 'SUCCEEDED' AND target_type = 'customer_risk_profile' AND target_id = '" + party + "'"
                + " AND change_summary = 'versions=2'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the refusals: no profile and a malformed id 404 risk.NotFound; the analyst's own party 403"
            + " risk.SelfDealingRefused recorded FAILED; an officer 403; no session 401 - none served")
    void theRefusals() throws Exception {
        Session analyst = sessionWith(RoleName.RISK_ANALYST);
        Session officer = sessionWith(RoleName.RISK_OFFICER);

        HttpResponse<String> none = get(door(IDS.next()), analyst.token());
        assertThat(none.statusCode()).as(none.body()).isEqualTo(404);
        assertThat(none.body()).contains("risk.NotFound");
        HttpResponse<String> malformed = get("/v1/operator/risk/parties/not-a-party/profile", analyst.token());
        assertThat(malformed.statusCode()).isEqualTo(404);
        assertThat(malformed.body()).contains("risk.NotFound");

        stand(analyst.party(), RiskRating.LOW);
        HttpResponse<String> own = get(door(analyst.party()), analyst.token());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfDealingRefused").doesNotContain("\"rating\"");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfDealingRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + analyst.party() + "'"
                + " AND change_summary NOT LIKE '%" + analyst.party() + "%'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ProfileRead'"
                + " AND target_id = '" + analyst.party() + "'")).as("nothing served").isZero();

        UUID party = IDS.next();
        stand(party, RiskRating.LOW);
        assertThat(get(door(party), officer.token()).statusCode()).as("another desk").isEqualTo(403);
        assertThat(get(door(party), null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ fixtures

    private record Session(String token, UUID party) {}

    private static String door(UUID party) {
        return "/v1/operator/risk/parties/" + party + "/profile";
    }

    /** {@code party}'s profile standing at {@code ratings}, one disposition version each - `-016`'s shape, by the store. */
    private static void stand(UUID party, RiskRating... ratings) throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            int version = STORE.lockOrCreate(app, party).currentVersion();
            // P13-TSK-016 (risk V012): each disposition version names a real disposition - its proposal's action.
            List<UUID> dispositions = com.finapp.risk.testing.RiskCaseFixtures.dispositions(app,
                    new com.finapp.sharedkernel.id.IdGenerator(java.time.Clock.systemUTC(),
                            new java.security.SecureRandom()), party, ratings.length);
            int index = 0;
            for (RiskRating rating : ratings) {
                version++;
                STORE.insertVersion(app, new CustomerRiskProfileVersion(party, version, rating, ProfileCause.DISPOSITION,
                        List.of(), Optional.empty(), Optional.of(dispositions.get(index++)), "profile-api",
                        Optional.empty()));
                STORE.advance(app, party, version - 1, version, rating, Duration.ofDays(1));
            }
            app.commit();
        }
    }

    private Session sessionWith(RoleName role) throws Exception {
        String login = "rp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}").statusCode())
                .isEqualTo(201);
        UUID identity;
        UUID party;
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(
                "SELECT id, party_id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
                party = row.getObject("party_id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        return new Session(field(session.body(), "sessionToken"), party);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }
}
