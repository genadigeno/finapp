package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP fixtures of the operator read suites (`P17-TSK-035`; the {@code RiskOperatorSessions} shape, opened to every
 * module's suite): an identity registered over the real endpoint, assigned a role and signed in on a {@code MULTI_FACTOR}
 * session (`P17-TSK-102`'s {@link com.finapp.app.session.OperatorStepUp}); the reads, and a §7 collection walked page by page
 * through its {@code nextCursor}; the tables read as the application.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
public final class OperatorReadClient {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One operator: the session's token, the identity and its party. */
    public record Operator(String token, UUID identity, UUID party) {}

    /** A collection walked whole: every item in the order served, and how many pages served them. */
    public record Walk(List<JsonNode> items, int pages) {

        /** The {@code field} of every item, in order. */
        public List<String> each(String field) {
            return items.stream().map(item -> item.path(field).isNull() ? null : item.path(field).asString()).toList();
        }
    }

    private final int port;
    private final Authorization authorization;

    public OperatorReadClient(int port, Authorization authorization) {
        this.port = port;
        this.authorization = authorization;
    }

    /** A fresh identity holding {@code role} - each role a party of its own - on a {@code MULTI_FACTOR} session. */
    public Operator operator(RoleName role) throws Exception {
        String login = "rg." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(201);
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
        grant(identity, role);
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null);
        // The answer names the session's token, so a failure reports the status only, never the body.
        assertThat(session.statusCode()).as("the sign-in").isEqualTo(201);
        String token = JSON.readTree(session.body()).path("sessionToken").asString();
        return new Operator(com.finapp.app.session.OperatorStepUp.elevated(token, this::post), identity, party);
    }

    /** {@code role} granted to {@code identity} - a customer made an operator too, to read their own party's records. */
    public void grant(UUID identity, RoleName role) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
    }

    /** {@code path} - a collection's first page with its query - walked through every {@code nextCursor} to the end. */
    public Walk walk(String path, String bearer) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        int pages = 0;
        String cursor = null;
        do {
            String page = cursor == null ? path
                    : path + (path.contains("?") ? "&" : "?") + "cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            JsonNode body = json(get(page, bearer), 200);
            body.path("items").forEach(items::add);
            pages++;
            assertThat(pages).as("a walk ends").isLessThan(10_000);
            cursor = body.has("nextCursor") ? body.path("nextCursor").asString() : null;
            if (cursor != null) {
                assertThat(body.path("items").size()).as("a page with a next page is full").isPositive();
            }
        } while (cursor != null);
        return new Walk(items, pages);
    }

    public HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    public HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return post(path, body, bearer, UUID.randomUUID().toString());
    }

    public HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** {@code response}'s body, asserted to be answered {@code status}. */
    public static JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    /** The error code of a problem answer, asserted to be answered {@code status}. */
    public static String problem(HttpResponse<String> response, int status) {
        return json(response, status).path("code").asString();
    }

    /** A count over the application's role. */
    public static long count(String sql, Object... arguments) throws Exception {
        return column(sql, arguments).stream().map(Long::parseLong).findFirst().orElseThrow();
    }

    /** The first column of every row {@code sql} answers, as text, in order. */
    public static List<String> column(String sql, Object... arguments) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                select.setObject(i + 1, arguments[i]);
            }
            List<String> rows = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(row.getString(1));
                }
            }
            return rows;
        }
    }

    /** How many pages a recorded read wrote for {@code actor} as {@code operation}. */
    public static long recorded(String operation, UUID actor) throws Exception {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND actor_id = ?", operation,
                actor.toString());
    }
}
