package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The account endpoints, over real HTTP (`P3-TSK-013`).
 *
 * <p>The milestone's acceptance minus closing: a verified customer opens, lists, reads a balance
 * that says which numbers it carries, and sees it change after a real posting. {@code DOD-SEC}'s
 * negatives: three 401s, the refusal for an unverified caller writing nothing, and ownership —
 * the list is exactly the caller's, and unknown, not-yours and malformed balance identifiers are
 * one {@code 404}, asserted as an <strong>equality between the causes</strong>.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the account endpoints (P3-TSK-013)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 17);

    private static final String OPEN_USD = "{\"productType\":\"WALLET\",\"currency\":\"USD\"}";

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("the acceptance: open, list, read a named balance, see it change after a posting")
    void theAcceptanceEndToEnd() throws Exception {
        Person person = givenAPerson("ACTIVE");

        HttpResponse<String> opened = post("/me/accounts", person.session(), OPEN_USD, key());
        assertThat(opened.statusCode()).isEqualTo(201);
        String accountId = field(opened.body(), "id");
        assertThat(opened.body()).contains("\"productType\":\"WALLET\"", "\"status\":\"ACTIVE\"");

        HttpResponse<String> listed = get("/me/accounts", person.session());
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(accountId);

        // The balance names its numbers and its nature, and an unposted account answers zero
        // in its own currency - "0.00", a scaled amount, never a bare 0 (INV-MON-02).
        HttpResponse<String> empty = get("/me/accounts/" + accountId + "/balance", person.session());
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.body())
                .contains("\"kind\":\"PROJECTION\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"settled\":\"0.00\"")
                .contains("\"holds\":\"0.00\"")
                .contains("\"available\":\"0.00\"");

        // A real posting credits the wallet - the platform's clearing account debited, the
        // customer's stored value credited - and the display follows in the same commit
        // (ADR-0041: the projection is transactional, so there is nothing to wait for).
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet =
                    ledgerAccounts
                            .findOwned(
                                    app,
                                    UUID.fromString(accountId),
                                    AccountPurpose.CUSTOMER_WALLET,
                                    USD)
                            .orElseThrow();
            LedgerAccount clearing =
                    ledgerAccounts
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                            .orElseThrow();
            postingService()
                    .post(
                            app,
                            new PostingCommand(
                                    "account-endpoint-" + IDS.next(),
                                    DATE,
                                    DATE,
                                    "acceptance-credit",
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(),
                                                    Direction.DEBIT,
                                                    Money.ofMinorUnits(1250, USD)),
                                            new JournalLine(
                                                    wallet.id(),
                                                    Direction.CREDIT,
                                                    Money.ofMinorUnits(1250, USD)))));
            app.commit();
        }

        HttpResponse<String> credited =
                get("/me/accounts/" + accountId + "/balance", person.session());
        assertThat(credited.statusCode()).isEqualTo(200);
        assertThat(credited.body())
                .contains("\"settled\":\"12.50\"")
                .contains("\"holds\":\"0.00\"")
                .contains("\"available\":\"12.50\"");
    }

    @Test
    @DisplayName("each caller reaches exactly their own; unknown, not-yours and malformed are one 404")
    void ownershipIsExactlyTheCallers() throws Exception {
        Person a = givenAPerson("ACTIVE");
        Person b = givenAPerson("ACTIVE");
        String aAccount = field(post("/me/accounts", a.session(), OPEN_USD, key()).body(), "id");
        String bAccount = field(post("/me/accounts", b.session(), OPEN_USD, key()).body(), "id");

        assertThat(get("/me/accounts", a.session()).body()).contains(aAccount).doesNotContain(bAccount);
        assertThat(get("/me/accounts", b.session()).body()).contains(bAccount).doesNotContain(aAccount);

        HttpResponse<String> notYours =
                get("/me/accounts/" + bAccount + "/balance", a.session());
        HttpResponse<String> unknown =
                get("/me/accounts/" + IDS.next() + "/balance", a.session());
        HttpResponse<String> malformed =
                get("/me/accounts/not-an-identifier/balance", a.session());
        assertThat(notYours.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        // The equality between the causes is what proves indistinguishability: a distinct
        // answer for "not yours" would confirm the identifier belongs to somebody.
        assertThat(withoutCorrelation(notYours.body()))
                .isEqualTo(withoutCorrelation(unknown.body()))
                .isEqualTo(withoutCorrelation(malformed.body()));
    }

    @Test
    @DisplayName("an unverified caller is refused with nothing written; a bad currency is a 422")
    void anUnverifiedCallerIsRefused() throws Exception {
        Person pending = givenAPerson("PENDING");
        HttpResponse<String> refused = post("/me/accounts", pending.session(), OPEN_USD, key());
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("accounts.AccountOpeningRefused");
        assertThat(accountRowsFor(pending.customer())).isZero();

        Person active = givenAPerson("ACTIVE");
        HttpResponse<String> chf =
                post(
                        "/me/accounts",
                        active.session(),
                        "{\"productType\":\"WALLET\",\"currency\":\"CHF\"}",
                        key());
        assertThat(chf.statusCode()).isEqualTo(422);
        assertThat(chf.body()).contains("accounts.UnsupportedCurrency");
        assertThat(accountRowsFor(active.customer())).isZero();
    }

    @Test
    @DisplayName("the open is idempotent: a retry replays, a reused key with a new request conflicts")
    void theOpenIsIdempotentOverHttp() throws Exception {
        Person person = givenAPerson("ACTIVE");
        String sameKey = key();

        HttpResponse<String> first = post("/me/accounts", person.session(), OPEN_USD, sameKey);
        HttpResponse<String> retry = post("/me/accounts", person.session(), OPEN_USD, sameKey);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(201);
        // The replay is the ORIGINAL outcome, byte for byte (INV-IDEM-01) - not a fresh
        // rendering that happens to agree.
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(accountRowsFor(person.customer())).isEqualTo(1);

        // The same key with a materially different request is a distinct conflict, never a
        // silent replay and never a second effect (INV-IDEM-03).
        HttpResponse<String> conflicting =
                post(
                        "/me/accounts",
                        person.session(),
                        "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                        sameKey);
        assertThat(conflicting.statusCode()).isEqualTo(409);
        assertThat(accountRowsFor(person.customer())).isEqualTo(1);

        // And the header is mandatory: the interceptor refuses before the handler is entered.
        HttpResponse<String> keyless = post("/me/accounts", person.session(), OPEN_USD, null);
        assertThat(keyless.statusCode()).isEqualTo(422);
        assertThat(keyless.body()).contains("api.IdempotencyKeyRequired");
    }

    @Test
    @DisplayName("no request shape yields a 500")
    void noRequestShapeYieldsA500() throws Exception {
        Person person = givenAPerson("ACTIVE");
        List<String> shapes =
                List.of(
                        "not json at all",
                        "{}",
                        "{\"productType\":null,\"currency\":\"USD\"}",
                        "{\"productType\":\"SAVINGS\",\"currency\":\"USD\"}",
                        "{\"productType\":12345,\"currency\":\"USD\"}",
                        "{\"productType\":\"WALLET\"}",
                        "{\"productType\":\"WALLET\",\"currency\":\"usd\"}",
                        "{\"productType\":\"WALLET\",\"currency\":{\"code\":\"USD\"}}");
        for (String shape : shapes) {
            HttpResponse<String> answer = post("/me/accounts", person.session(), shape, key());
            assertThat(answer.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isLessThan(500)
                    .isGreaterThanOrEqualTo(400);
        }
        assertThat(accountRowsFor(person.customer())).isZero();
    }

    @Test
    @DisplayName("all three endpoints refuse an unauthenticated caller")
    void unauthenticatedIsRefused() throws Exception {
        assertThat(post("/me/accounts", null, OPEN_USD, key()).statusCode()).isEqualTo(401);
        assertThat(get("/me/accounts", null).statusCode()).isEqualTo(401);
        assertThat(get("/me/accounts/" + IDS.next() + "/balance", null).statusCode())
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Person(UUID party, UUID customer, IdentityId identity, String session) {}

    /** A person with a customer in {@code status}, an active identity and a live session. */
    private Person givenAPerson(String customerStatus) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        UUID identity = IDS.next();
        String login = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Account Endpoint Person',"
                            + " now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, '" + customerStatus
                            + "', now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
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
        return new Person(
                party, customer, IdentityId.of(identity), givenASessionFor(IdentityId.of(identity)));
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

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK);
    }

    private static long accountRowsFor(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM accounts.customer_account"
                                        + " WHERE customer_id = ?")) {
            count.setObject(1, customerId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
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

    // -----------------------------------------------------------------
    // HTTP

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** The value of a top-level string member of a small JSON body. */
    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        assertThat(start).as("member %s in %s", name, body).isNotNegative();
        int from = start + marker.length();
        return body.substring(from, body.indexOf('"', from));
    }

    /**
     * Strips the per-request members — the correlation identifier and {@code instance}, which
     * echoes the caller's own URI — so three refusals can be compared to each other. What must
     * be identical is everything the platform CHOSE to say; what necessarily differs is what
     * the caller themselves sent.
     */
    private static String withoutCorrelation(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"-\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"-\"");
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
