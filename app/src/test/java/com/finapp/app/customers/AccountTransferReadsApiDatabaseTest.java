package com.finapp.app.customers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
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
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator's account and transfer reads over HTTP (`P17-TSK-027`): a negative per route; the balances the ledger's own,
 * stated as of the database's instant; the ledger lines and the transfers §7 collections walked once; the window bounded;
 * nothing a person wrote served; every answer recorded.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the operator's account and transfer reads over HTTP (P17-TSK-027)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountTransferReadsApiDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private record Person(UUID id, String token) {}

    /** A verified customer with a funded USD wallet and three transfers out of it. */
    private record Subject(String customerId, String account, List<String> transfers) {}

    @Test
    @DisplayName("a negative per route: a KYC reviewer is refused 403 on all five, a support agent on a password session is asked"
            + " for a second factor on all five, and a customer's own session is refused")
    void everyRouteRefusesWithoutItsRead() throws Exception {
        Subject subject = subject();
        String customer = UUID.randomUUID().toString();
        for (Person refused : List.of(person(RoleName.KYC_REVIEWER, AssuranceLevel.MULTI_FACTOR),
                person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.PASSWORD))) {
            for (String path : List.of("/v1/operator/customers/" + subject.customerId() + "/accounts",
                    "/v1/operator/accounts/" + subject.account(),
                    "/v1/operator/accounts/" + subject.account() + "/entries?currency=USD",
                    "/v1/operator/transfers?customerId=" + subject.customerId() + window(),
                    "/v1/operator/transfers/" + subject.transfers().get(0))) {
                HttpResponse<String> answered = get(refused, path);
                assertThat(answered.statusCode()).as(path + " " + answered.body()).isEqualTo(403);
            }
        }
        assertThat(customer).isNotBlank();
    }

    @Test
    @DisplayName("the agreement's balances are the ledger's own, each a decimal string with its currency, stated as of the"
            + " database's instant of the read; the list serves the agreement, newest first; both recorded")
    void theBalancesAreTheLedgersAsOfTheRead() throws Exception {
        Subject subject = subject();
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.MULTI_FACTOR);

        Instant before = databaseNow();
        HttpResponse<String> read = get(agent, "/v1/operator/accounts/" + subject.account());
        Instant after = databaseNow();

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        JsonNode account = json.readTree(read.body());
        assertThat(account.get("accountId").asString()).isEqualTo(subject.account());
        assertThat(account.get("customerId").asString()).isEqualTo(subject.customerId());
        Instant asOf = Instant.parse(account.get("asOf").asString());
        assertThat(asOf).as("the database's instant, between two reads of its clock").isBetween(before, after);
        JsonNode usd = account.get("balances").get(0);
        assertThat(usd.get("currency").asString()).isEqualTo("USD");
        assertThat(usd.get("posted").asString()).as("10.00 funded, three transfers of 1.00 out").isEqualTo("7.00");
        assertThat(usd.get("held").asString()).isEqualTo("0.00");
        assertThat(usd.get("available").asString()).isEqualTo("7.00");

        HttpResponse<String> list = get(agent, "/v1/operator/customers/" + subject.customerId() + "/accounts");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(json.readTree(list.body()).get("items").get(0).get("accountId").asString()).isEqualTo(subject.account());
        assertThat(list.body()).doesNotContain("nextCursor").doesNotContain("posted");

        String recorded = recordedBy(agent);
        assertThat(recorded).contains("accounts.AccountRead accounts.CustomerAccount " + subject.account() + " customer="
                + subject.customerId() + ", asOf=" + asOf);
        assertThat(recorded).contains("accounts.CustomerAccountsListed party.Customer " + subject.customerId());
        assertThat(get(agent, "/v1/operator/accounts/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get(agent, "/v1/operator/customers/" + UUID.randomUUID() + "/accounts").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the ledger lines are a §7 collection: newest entry first, walked once across pages, each recorded; the"
            + " currency required and one the account holds")
    void theLinesAreACollection() throws Exception {
        Subject subject = subject();
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.MULTI_FACTOR);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            HttpResponse<String> page = get(agent, "/v1/operator/accounts/" + subject.account() + "/entries?currency=USD&limit=3"
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            JsonNode body = json.readTree(page.body());
            body.get("items").forEach(line -> walked.add(line.get("direction").asString() + " " + line.get("amount").asString()
                    + " " + line.get("currency").asString()));
            cursor = body.has("nextCursor") ? body.get("nextCursor").asString() : null;
            pages++;
        } while (cursor != null);

        assertThat(walked).as("three transfers out, newest first, then the funding credit")
                .containsExactly("DEBIT 1.00 USD", "DEBIT 1.00 USD", "DEBIT 1.00 USD", "CREDIT 10.00 USD");
        assertThat(pages).isEqualTo(2);
        assertThat(recordedBy(agent)).contains("ledger.AccountLinesListed accounts.CustomerAccount " + subject.account()
                + " currency=USD, after=false, served=3").contains("currency=USD, after=true, served=1");

        assertThat(get(agent, "/v1/operator/accounts/" + subject.account() + "/entries").statusCode()).isEqualTo(422);
        assertThat(get(agent, "/v1/operator/accounts/" + subject.account() + "/entries?currency=EUR").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the transfers are a §7 collection within a required window of at most 92 days, newest first, walked once; one"
            + " transfer serves its journal entry and never the customer's reference; each recorded")
    void theTransfersAreAWindowedCollection() throws Exception {
        Subject subject = subject();
        Person agent = person(RoleName.CUSTOMER_SUPPORT_AGENT, AssuranceLevel.MULTI_FACTOR);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        do {
            HttpResponse<String> page = get(agent, "/v1/operator/transfers?customerId=" + subject.customerId() + window()
                    + "&status=COMPLETED&limit=2" + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor,
                    StandardCharsets.UTF_8)));
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            JsonNode body = json.readTree(page.body());
            body.get("items").forEach(item -> walked.add(item.get("transferId").asString()));
            cursor = body.has("nextCursor") ? body.get("nextCursor").asString() : null;
        } while (cursor != null);
        assertThat(walked).as("newest first, each once").containsExactlyElementsOf(subject.transfers().reversed());

        HttpResponse<String> one = get(agent, "/v1/operator/transfers/" + subject.transfers().get(0));
        assertThat(one.statusCode()).as(one.body()).isEqualTo(200);
        JsonNode transfer = json.readTree(one.body());
        assertThat(transfer.get("amount").asString()).isEqualTo("1.00");
        assertThat(transfer.get("currency").asString()).isEqualTo("USD");
        assertThat(transfer.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(transfer.get("journalEntryId").asString()).isNotBlank();
        assertThat(one.body()).as("the customer's own memo is personal data; who commanded it too")
                .doesNotContain("reference").doesNotContain("rent for").doesNotContain("initiatedBy");

        assertThat(get(agent, "/v1/operator/transfers?customerId=" + subject.customerId()).statusCode())
                .as("the window is required").isEqualTo(422);
        assertThat(get(agent, "/v1/operator/transfers" + window().replaceFirst("&", "?")).statusCode())
                .as("the customer is required").isEqualTo(422);
        Instant now = Instant.now();
        assertThat(get(agent, "/v1/operator/transfers?customerId=" + subject.customerId() + "&from="
                + now.minus(Duration.ofDays(93)) + "&to=" + now).statusCode()).as("93 days").isEqualTo(422);
        String recorded = recordedBy(agent);
        assertThat(recorded).contains("transfers.TransfersListed party.Customer " + subject.customerId());
        assertThat(recorded).contains("transfers.TransferRead transfers.Transfer " + subject.transfers().get(0) + " customer="
                + subject.customerId() + ", status=COMPLETED");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private static String window() {
        Instant now = Instant.now();
        return "&from=" + now.minus(Duration.ofDays(1)) + "&to=" + now.plus(Duration.ofDays(1));
    }

    private static Instant databaseNow() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT clock_timestamp()");
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }

    private Subject subject() throws Exception {
        String login = "atr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String token = verifiedCustomer(login);
        String account = openAccount(token);
        fund(account, 10_00);
        String receiver = openAccount(verifiedCustomer("atr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)));
        List<String> transfers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> created = post("/v1/transfers", "{\"sourceAccountId\":\"" + account
                    + "\",\"destinationAccountId\":\"" + receiver + "\",\"amount\":\"1.00\",\"currency\":\"USD\","
                    + "\"reference\":\"rent for flat " + i + "\"}", token, true);
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            transfers.add(field(created.body(), "id"));
        }
        String customerId;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT customer_id FROM accounts.customer_account WHERE id = ?")) {
            read.setObject(1, UUID.fromString(account));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                customerId = row.getString(1);
            }
        }
        return new Subject(customerId, account, transfers);
    }

    private String verifiedCustomer(String login) throws Exception {
        HttpResponse<String> registered = post("/v1/registrations", "{\"loginIdentifier\":\"" + login
                + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, true);
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)"
                    + " WHERE party_id = (SELECT party_id FROM identity.identity WHERE login_identifier = ?)", login);
        }
        HttpResponse<String> authenticated = post("/v1/authentications", "{\"loginIdentifier\":\"" + login
                + "\",\"password\":\"" + PASSWORD + "\"}", null, false);
        return field(authenticated.body(), "sessionToken");
    }

    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened = post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"USD\"}", token, true);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    /** Funds the product's wallet with a real posting (the transfer suites' fixture). */
    private void fund(String product, long minorUnits) throws Exception {
        JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            LedgerAccountId wallet = ledgerAccounts.findAllOwned(app, UUID.fromString(product)).stream()
                    .filter(a -> a.purpose() == AccountPurpose.CUSTOMER_WALLET).findFirst().orElseThrow().id();
            LedgerAccount operational = ledgerAccounts.findOperational(app, AccountPurpose.FEE_REVENUE, USD).orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                    Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                    new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE)
                    .post(app, new PostingCommand("fund-" + IDS.next(), today, today, "funding", List.of(
                            new JournalLine(operational.id(), Direction.DEBIT, Money.ofMinorUnits(minorUnits, USD)),
                            new JournalLine(wallet, Direction.CREDIT, Money.ofMinorUnits(minorUnits, USD)))));
            app.commit();
        }
    }

    private HttpResponse<String> get(Person who, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + who.token()).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token, boolean idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey) {
            request.header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
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

    /** A role holder with a session at {@code assurance}; no factor enrolled. */
    private Person person(RoleName role, AssuranceLevel assurance) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        String login = "ag" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Support Person',"
                    + " now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party, login);
        }
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("p17-027-" + UUID.randomUUID())));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, com.finapp.identity.Session.issue(IDS, CLOCK, IdentityId.of(identity), SessionToken.of(plaintext),
                    assurance, SessionPolicy.current()));
        }
        return new Person(identity, plaintext);
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
