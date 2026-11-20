package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The operator's merchant collections over real HTTP (`P17-TSK-033`, finapp-admin-panel B7.3): the merchants, a merchant's
 * payouts and one payout, and a merchant's transactions - each walked once in its declared order to its last page with no row
 * served twice, behind {@code MERCHANT_ADMINISTER} in both directions, each money read on the record and the merchants'
 * list not, the one 404 for an unknown merchant or another merchant's payout, every refused parameter {@code 422} - and the
 * payouts' page walking merchant `V018`'s index.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the operator's merchant collections (P17-TSK-033)")
class MerchantListsApiDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private PostingService postings;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the merchants: newest onboarding first, walked once, by standing and by an exact name - and unrecorded")
    void theMerchantsWalkOnce() throws Exception {
        String operator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String marker = "Lists " + UUID.randomUUID().toString().substring(0, 8);
        List<String> onboarded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // Oldest first, a minute apart: the list serves them in the reverse order.
            onboarded.add(0, merchant(marker, "Legal " + marker + " " + i, i == 1 ? "SUSPENDED" : "ACTIVE", 10 - i));
        }
        long records = count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?", onboarded.get(0));

        List<String> walked = walk("/v1/operator/merchants?name=" + encoded(marker) + "&limit=2", operator, "merchantId", 3);
        assertThat(walked).as("newest onboarding first, every one once").containsExactlyElementsOf(onboarded);

        String suspended = get("/v1/operator/merchants?status=SUSPENDED&name=" + encoded(marker), operator).body();
        assertThat(ids(suspended, "merchantId")).containsExactly(onboarded.get(3));
        assertThat(suspended).doesNotContain("nextCursor");
        String byLegalName = get("/v1/operator/merchants?name=" + encoded("Legal " + marker + " 4"), operator).body();
        assertThat(ids(byLegalName, "merchantId")).containsExactly(onboarded.get(0));
        assertThat(ids(get("/v1/operator/merchants?name=" + encoded(marker.substring(0, 7)), operator).body(), "merchantId"))
                .as("an exact match, never a prefix")
                .isEmpty();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?", onboarded.get(0)))
                .as("unrecorded, as one merchant's read is")
                .isEqualTo(records);

        for (String refused : List.of("?limit=201", "?limit=0", "?status=active", "?cursor=not-a-cursor", "?name=")) {
            HttpResponse<String> response = get("/v1/operator/merchants" + refused, operator);
            assertThat(response.statusCode()).as(refused).isEqualTo(422);
            assertThat(response.body()).contains("api.ValidationFailed");
        }
        assertThat(get("/v1/operator/merchants", sessionWith(RoleName.LEDGER_OPERATOR)).statusCode()).isEqualTo(403);
        assertThat(send(HttpRequest.newBuilder(uri("/v1/operator/merchants")).GET().build()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a merchant's payouts: newest first, walked once, each page and each payout on the record; another merchant's is"
            + " the one 404")
    void aMerchantsPayoutsWalkOnceOnTheRecord() throws Exception {
        String operator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant("Payouts " + UUID.randomUUID(), "Payouts GmbH", "ACTIVE", 1);
        UUID destination = effectiveDestination(merchant);
        String other = merchant("Other " + UUID.randomUUID(), "Other GmbH", "ACTIVE", 1);
        UUID otherDestination = effectiveDestination(other);
        List<String> payouts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            payouts.add(0, payout(merchant, destination, 100 + i, 50 - i));
        }
        String foreign = payout(other, otherDestination, 999, 1);

        List<String> walked = walk("/v1/operator/merchants/" + merchant + "/payouts?limit=2", operator, "id", 3);
        assertThat(walked).as("newest first, every one once, none of another merchant's").containsExactlyElementsOf(payouts);
        assertThat(get("/v1/operator/merchants/" + merchant + "/payouts", operator).body())
                .contains("\"destinationSuffix\":\"3000\"")
                .contains("\"amount\":\"1.04\"")
                .doesNotContain("pdr_");
        assertThat(audits("merchant.PayoutsListed", merchant)).as("each page served, each on the record").isEqualTo(4);

        HttpResponse<String> one = get("/v1/operator/merchants/" + merchant + "/payouts/" + payouts.get(0), operator);
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(field(one.body(), "status")).isEqualTo("DISPATCHED");
        assertThat(audits("merchant.PayoutRead", payouts.get(0))).isEqualTo(1);

        HttpResponse<String> notTheirs = get("/v1/operator/merchants/" + merchant + "/payouts/" + foreign, operator);
        HttpResponse<String> unknown = get("/v1/operator/merchants/" + merchant + "/payouts/" + IDS.next(), operator);
        HttpResponse<String> malformed = get("/v1/operator/merchants/" + merchant + "/payouts/not-a-uuid", operator);
        HttpResponse<String> noMerchant = get("/v1/operator/merchants/" + IDS.next() + "/payouts", operator);
        for (HttpResponse<String> response : List.of(notTheirs, unknown, malformed, noMerchant)) {
            assertThat(response.statusCode()).isEqualTo(404);
        }
        assertThat(normalised(notTheirs.body())).isEqualTo(normalised(unknown.body()));
        assertThat(audits("merchant.PayoutRead", foreign)).as("a refused read is no read").isZero();

        long pages = audits("merchant.PayoutsListed", merchant);
        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(get("/v1/operator/merchants/" + merchant + "/payouts", ledgerOperator).statusCode()).isEqualTo(403);
        assertThat(get("/v1/operator/merchants/" + merchant + "/payouts/" + payouts.get(0), ledgerOperator).statusCode())
                .isEqualTo(403);
        assertThat(audits("merchant.PayoutsListed", merchant)).isEqualTo(pages);
        assertThat(get("/v1/operator/merchants/" + merchant + "/payouts?cursor="
                        + encoded(field(get("/v1/operator/merchants?limit=1", operator).body(), "nextCursor")), operator)
                        .statusCode())
                .as("one collection's cursor is refused by another")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("a merchant's transactions: newest first in a bounded window, walked once, each page on the record")
    void aMerchantsTransactionsWalkOnceOnTheRecord() throws Exception {
        String operator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant("Transactions " + UUID.randomUUID(), "Transactions GmbH", "ACTIVE", 1);
        LedgerAccountId payable = payable(merchant);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(credit(payable, "1" + i + ".00"));
        }
        List<String> walked = walk("/v1/operator/merchants/" + merchant + "/transactions?limit=2", operator, "entryId", 3);
        assertThat(walked).as("every movement once, newest first").containsExactlyInAnyOrderElementsOf(entries);
        assertThat(walked).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        String page = get("/v1/operator/merchants/" + merchant + "/transactions", operator).body();
        assertThat(page).contains("\"currency\":\"EUR\"").contains("\"kind\":\"OTHER\"").contains("\"net\":\"14.00\"");
        assertThat(audits("merchant.TransactionsListed", merchant)).isEqualTo(4);

        String today = LocalDate.now(CLOCK).toString();
        assertThat(get("/v1/operator/merchants/" + merchant + "/transactions?from=" + LocalDate.now(CLOCK).minusDays(31)
                        + "&to=" + today, operator).statusCode())
                .as("thirty-one days at most")
                .isEqualTo(422);
        assertThat(get("/v1/operator/merchants/" + merchant + "/transactions?from=" + LocalDate.now(CLOCK).minusDays(1),
                        operator).body())
                .contains("\"net\":\"10.00\"");
        assertThat(get("/v1/operator/merchants/" + IDS.next() + "/transactions", operator).statusCode()).isEqualTo(404);
        long pages = audits("merchant.TransactionsListed", merchant);
        assertThat(get("/v1/operator/merchants/" + merchant + "/transactions", sessionWith(RoleName.LEDGER_OPERATOR))
                        .statusCode())
                .isEqualTo(403);
        assertThat(audits("merchant.TransactionsListed", merchant)).isEqualTo(pages);
    }

    @Test
    @DisplayName("V018: a merchant's payouts page is a backward range of merchant_payout_by_merchant - no sort, first or continued")
    void thePayoutsPageWalksItsIndex() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                try (PreparedStatement off = app.prepareStatement("SET LOCAL enable_seqscan = off")) {
                    off.execute();
                }
                // Constants, not gen_random_uuid(): a volatile call is never an index condition.
                String someone = "'" + IDS.next() + "'::uuid";
                for (String resume : List.of("", " AND (created_at, id) < (now(), '" + IDS.next() + "'::uuid)")) {
                    // The page's own statement - every column, as JdbcMerchantCollections reads it.
                    String plan = plan(app, "EXPLAIN SELECT * FROM merchant.merchant_payout WHERE merchant_id = " + someone
                            + resume + " ORDER BY created_at DESC, id DESC LIMIT 51");
                    assertThat(plan).as(plan).contains("Scan Backward using merchant_payout_by_merchant")
                            .doesNotContain("Sort");
                }
            } finally {
                app.rollback();
            }
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /** A merchant onboarded {@code minutesAgo} minutes ago, by row - onboarding itself is another suite's subject. */
    private static String merchant(String displayName, String legalName, String status, int minutesAgo) throws SQLException {
        UUID id = IDS.next();
        raw("INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name, settlement_currency, status, created_at,"
                        + " status_changed_at) VALUES (?, ?, ?, ?, 'EUR', ?, now() - CAST(? AS interval),"
                        + " now() - CAST(? AS interval))",
                id, UUID.randomUUID(), legalName, displayName, status, minutesAgo + " minutes", minutesAgo + " minutes");
        return id.toString();
    }

    private static UUID effectiveDestination(String merchant) throws SQLException {
        UUID id = IDS.next();
        raw("INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference, display_suffix, status,"
                        + " proposed_by, proposed_at, proposal_reason, approved_by, approved_at, cooling_off_until,"
                        + " effective_at) VALUES (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '4 days',"
                        + " 'fixture', 'fixture-b', now() - interval '4 days', now() - interval '1 day',"
                        + " now() - interval '1 hour')",
                id, UUID.fromString(merchant), "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return id;
    }

    /** A dispatched payout of {@code minor} cents, made {@code minutesAgo} minutes ago - by row, as the metrics suite plants one. */
    private static String payout(String merchant, UUID destination, long minor, int minutesAgo) throws SQLException {
        UUID id = IDS.next();
        raw("INSERT INTO merchant.merchant_payout (id, merchant_id, amount_minor, currency, scale, destination_id,"
                        + " hold_reference, provider_idempotency_reference, provider_reference, status, failure_reason,"
                        + " dispatch_key, requested_by, requested_by_type, reason, created_at, last_dispatched_at) VALUES"
                        + " (?, ?, ?, 'EUR', 2, ?, ?, ?, NULL, 'DISPATCHED', NULL, ?, 'fixture', 'MERCHANT', NULL,"
                        + " now() - CAST(? AS interval), now() - CAST(? AS interval))",
                id, UUID.fromString(merchant), minor, destination, IDS.next(), // a hold's id is UUIDv7, as HoldId reads it
                "ref-" + UUID.randomUUID().toString().substring(0, 13), "key-" + id,
                minutesAgo + " minutes", minutesAgo + " minutes");
        return id.toString();
    }

    private LedgerAccountId payable(String merchant) throws Exception {
        return asSomeone(uow -> ledgerAccountStore.createOrConverge(uow, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                AccountPurpose.MERCHANT_PAYABLE, EUR, UUID.fromString(merchant))).account().id());
    }

    /** A book-rail sale crediting the payable today - DR a payer's wallet / CR payable; returns the entry. */
    private String credit(LedgerAccountId payable, String amount) throws Exception {
        return asSomeone(uow -> {
            LedgerAccount payer = ledgerAccountStore.createOrConverge(uow, LedgerAccount.owned(IDS, CLOCK,
                    AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR, UUID.randomUUID())).account();
            LocalDate today = LocalDate.now(CLOCK);
            UUID reference = UUID.randomUUID();
            Money funds = Money.of(new BigDecimal(amount), EUR);
            return postings.post(uow, new PostingCommand("merchant-lists-fixture:" + reference, today, today,
                            reference.toString(), List.of(new JournalLine(payer.id(), Direction.DEBIT, funds),
                                    new JournalLine(payable, Direction.CREDIT, funds))))
                    .entryId().value().toString();
        });
    }

    private <R> R asSomeone(Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            R result = work.apply(app);
            app.commit();
            return result;
        }
    }

    /** A {@code MULTI_FACTOR} operator session holding {@code role}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "mlists." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> registration = post("/v1/registrations", "{\"loginIdentifier\":\"" + login
                + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        assertThat(registration.statusCode()).isEqualTo(201);
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        String token = field(post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD
                + "\"}", null, someKey()).body(), "sessionToken");
        return com.finapp.app.session.OperatorStepUp.elevated(token, this::post);
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    /** Walks {@code first} to its last page, asserting the page count; the {@code idField} of every item, in order. */
    private List<String> walk(String first, String token, String idField, int pages) throws Exception {
        List<String> walked = new ArrayList<>();
        String path = first;
        int served = 0;
        while (true) {
            HttpResponse<String> page = get(path, token);
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            served++;
            walked.addAll(ids(page.body(), idField));
            Matcher next = Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(page.body());
            if (!next.find()) {
                break;
            }
            path = first + "&cursor=" + encoded(next.group(1));
        }
        assertThat(served).as("pages walked").isEqualTo(pages);
        assertThat(walked).doesNotHaveDuplicates();
        return walked;
    }

    private static List<String> ids(String body, String idField) {
        List<String> ids = new ArrayList<>();
        Matcher found = Pattern.compile("\"" + Pattern.quote(idField) + "\":\"([^\"]+)\"").matcher(body);
        while (found.find()) {
            ids.add(found.group(1));
        }
        return ids;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build());
    }

    private HttpResponse<String> post(String path, String body, String token, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    private static long audits(String operation, String target) throws SQLException {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND target_id = ?", operation, target);
    }

    private static String plan(Connection app, String explain) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement statement = app.prepareStatement(explain); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private static void raw(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static String normalised(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"*\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"*\"");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
