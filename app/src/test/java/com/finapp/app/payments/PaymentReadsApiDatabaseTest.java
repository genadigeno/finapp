package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.JdbcCaptureHoldStore;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentIntentId;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * The operator's payment reads over real HTTP (`P17-TSK-032`, finapp-admin-panel B7.2): the payment search walked once in its
 * order by party and by merchant, each page recorded; one payment whole and recorded; a party's methods masked and recorded; the
 * payments report and its record; the dispute queue in deadline order, an unstated deadline last; the held card payments, the
 * reader's own party withheld - each behind its permission in both directions, and payments `V042`'s indexes serving the search.
 * The routing versions' and the rails' reads and the switch's key are {@code RoutingPolicyDatabaseTest}'s.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the operator's payment reads (P17-TSK-032)")
class PaymentReadsApiDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the search: a party's payments and a merchant's walked once, newest first, every page recorded; filtered by"
            + " status and rail; one payment whole and recorded; behind PAYMENT_READ")
    void theSearchWalksOnceOnTheRecord() throws Exception {
        String support = sessionWith(RoleName.CUSTOMER_SUPPORT_AGENT).token();
        UUID party = IDS.next();
        UUID merchant = IDS.next();
        UUID payable = payable(merchant);
        List<Seeded> seeded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // Oldest first, a minute apart; the search serves them in the reverse order.
            seeded.add(0, payment(party, payable, i % 2 == 0 ? "CAPTURED" : "CAPTURE_UNKNOWN", 30 - i));
        }
        Instant from = Instant.now(CLOCK).minus(Duration.ofHours(1));
        Instant to = Instant.now(CLOCK).plus(Duration.ofMinutes(1));
        String window = "from=" + from + "&to=" + to;

        List<String> byParty = walk("/v1/operator/payments?" + window + "&partyId=" + party + "&limit=2", support,
                "paymentIntentId", 3);
        assertThat(byParty).as("newest first, each once").containsExactlyElementsOf(seeded.stream().map(Seeded::intent).toList());
        assertThat(audits("payments.PaymentsListed", "party:" + party)).as("every page on the record").isEqualTo(3);
        assertThat(walk("/v1/operator/payments?" + window + "&merchantId=" + merchant, support, "paymentIntentId", 1))
                .as("the merchant's payables are its payments' credited accounts")
                .containsExactlyElementsOf(byParty);
        assertThat(ids(get("/v1/operator/payments?" + window + "&partyId=" + party + "&status=SUCCEEDED", support).body(),
                        "paymentIntentId"))
                .hasSize(3);
        assertThat(ids(get("/v1/operator/payments?" + window + "&partyId=" + party + "&rail=card", support).body(),
                        "paymentIntentId"))
                .hasSize(5);
        assertThat(ids(get("/v1/operator/payments?" + window + "&merchantId=" + IDS.next(), support).body(),
                        "paymentIntentId"))
                .as("a merchant with no payables has no payments")
                .isEmpty();
        for (String refused : List.of("", window + "&partyId=" + party + "&merchantId=" + merchant, window + "&rail=sepa",
                window + "&status=CAPTURED", window + "&limit=201", window + "&cursor=not-a-cursor",
                "from=" + from + "&to=" + from.plus(Duration.ofDays(32)), "from=" + to + "&to=" + from)) {
            HttpResponse<String> response = get("/v1/operator/payments?" + refused, support);
            assertThat(response.statusCode()).as(refused).isEqualTo(422);
        }

        Seeded captured = seeded.get(0);
        HttpResponse<String> one = get("/v1/operator/payments/" + captured.intent(), support);
        assertThat(one.statusCode()).as(one.body()).isEqualTo(200);
        assertThat(one.body())
                .contains("\"paymentIntentId\":\"" + captured.intent() + "\"")
                .contains("\"attemptId\":\"" + captured.attempt() + "\"")
                .contains("\"captureProviderReference\":\"psp-cap-")
                .contains("\"refunds\":[]")
                .contains("\"disputes\":[]");
        assertThat(audits("payments.PaymentRead", captured.intent())).isEqualTo(1);
        HttpResponse<String> unknown = get("/v1/operator/payments/" + IDS.next(), support);
        HttpResponse<String> malformed = get("/v1/operator/payments/not-a-uuid", support);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(normalised(malformed.body())).isEqualTo(normalised(unknown.body()));

        long pages = audits("payments.PaymentsListed", "party:" + party);
        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR).token();
        assertThat(get("/v1/operator/payments?" + window + "&partyId=" + party, ledgerOperator).statusCode()).isEqualTo(403);
        assertThat(get("/v1/operator/payments/" + captured.intent(), ledgerOperator).statusCode()).isEqualTo(403);
        assertThat(audits("payments.PaymentsListed", "party:" + party)).as("a refused read is no read").isEqualTo(pages);
        assertThat(send(HttpRequest.newBuilder(uri("/v1/operator/payments?" + window)).GET().build()).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a party's methods are served masked and recorded; an unknown party is the 404; behind PAYMENT_READ")
    void aPartysMethodsAreMasked() throws Exception {
        String support = sessionWith(RoleName.CUSTOMER_SUPPORT_AGENT).token();
        UUID party = party();
        String token = "tok-ops-" + UUID.randomUUID();
        UUID method = IDS.next();
        raw("INSERT INTO paymentmethods.payment_method (id, party_id, kind, token_reference, brand, display_suffix,"
                        + " expiry_month, expiry_year, status, created_at) VALUES (?, ?, 'CARD_TOKEN', ?, 'Visa', '4242', 12,"
                        + " 2030, 'ACTIVE', now())",
                method, party, token);

        HttpResponse<String> methods = get("/v1/operator/parties/" + party + "/payment-methods", support);
        assertThat(methods.statusCode()).as(methods.body()).isEqualTo(200);
        assertThat(methods.body())
                .contains("\"id\":\"" + method + "\"")
                .contains("\"displaySuffix\":\"4242\"")
                .as("never the token reference")
                .doesNotContain(token);
        assertThat(audits("paymentmethods.PaymentMethodsRead", party.toString())).isEqualTo(1);
        assertThat(get("/v1/operator/parties/" + IDS.next() + "/payment-methods", support).statusCode()).isEqualTo(404);
        assertThat(get("/v1/operator/parties/not-a-uuid/payment-methods", support).statusCode()).isEqualTo(404);
        assertThat(get("/v1/operator/parties/" + party + "/payment-methods", sessionWith(RoleName.LEDGER_OPERATOR).token())
                        .statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the payments report counts the day's payments by rail and outcome and the unknown backlog, and is recorded")
    void theReportCountsTheDay() throws Exception {
        String support = sessionWith(RoleName.CUSTOMER_SUPPORT_AGENT).token();
        UUID party = IDS.next();
        UUID payable = payable(IDS.next());
        payment(party, payable, "CAPTURE_UNKNOWN", 1);
        String today = LocalDate.now(CLOCK).toString();

        HttpResponse<String> report = get("/v1/operator/reports/payments?date=" + today, support);
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        assertThat(report.body())
                .contains("\"date\":\"" + today + "\"")
                .contains("\"generatedAt\":")
                .contains("{\"rail\":\"card\",\"attemptStatus\":\"CAPTURE_UNKNOWN\",\"intentStatus\":\"PROCESSING\"")
                .contains("{\"kind\":\"ATTEMPT\",\"status\":\"CAPTURE_UNKNOWN\"");
        assertThat(audits("payments.PaymentsReportRead", today)).isGreaterThanOrEqualTo(1);
        for (String refused : List.of("", "?date=yesterday", "?date=1999-12-31")) {
            assertThat(get("/v1/operator/reports/payments" + refused, support).statusCode()).as(refused).isEqualTo(422);
        }
        assertThat(get("/v1/operator/reports/payments?date=" + today, sessionWith(RoleName.LEDGER_OPERATOR).token())
                        .statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the dispute queue: the deadline earliest first, an unstated one last, walked once, every page recorded;"
            + " behind DISPUTE_ADMINISTER")
    void theDisputeQueueIsInDeadlineOrder() throws Exception {
        String disputes = sessionWith(RoleName.LEDGER_OPERATOR).token();
        UUID party = IDS.next();
        UUID payable = payable(IDS.next());
        Instant base = Instant.now(CLOCK).plus(Duration.ofDays(400)).truncatedTo(ChronoUnit.SECONDS);
        List<String> dated = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            dated.add(dispute(payment(party, payable, "CAPTURED", 5).attempt(), "CHARGED_BACK", base.plus(Duration.ofHours(i))));
        }
        String unstated = dispute(payment(party, payable, "CAPTURED", 5).attempt(), "CHARGED_BACK", null);

        List<String> walked = walk("/v1/operator/disputes?stage=CHARGED_BACK&limit=2", disputes, "disputeId", -1);
        assertThat(walked).doesNotHaveDuplicates().containsSubsequence(dated.get(0), dated.get(1), dated.get(2), unstated);
        assertThat(walked.indexOf(unstated)).as("an unstated deadline sorts last").isGreaterThan(walked.indexOf(dated.get(2)));
        List<String> bounded = ids(get("/v1/operator/disputes?stage=CHARGED_BACK&deadlineBefore=" + base.plus(Duration.ofMinutes(90))
                + "&limit=200", disputes).body(), "disputeId");
        assertThat(bounded).contains(dated.get(0), dated.get(1)).doesNotContain(dated.get(2), unstated);
        assertThat(audits("payments.DisputeQueueRead", "CHARGED_BACK")).isGreaterThanOrEqualTo(2);
        for (String refused : List.of("?stage=OPEN", "?deadlineBefore=soon", "?limit=0", "?cursor=not-a-cursor")) {
            assertThat(get("/v1/operator/disputes" + refused, disputes).statusCode()).as(refused).isEqualTo(422);
        }
        assertThat(get("/v1/operator/disputes", sessionWith(RoleName.CUSTOMER_SUPPORT_AGENT).token()).statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the held card payments: the open holds, the deadline earliest first, the reader's own party's withheld,"
            + " every page recorded; behind RISK_INVESTIGATE")
    void theHeldPaymentsWithholdTheReadersOwn() throws Exception {
        Session analyst = sessionWith(RoleName.RISK_ANALYST);
        UUID own = partyOf(analyst.identity());
        UUID payable = payable(IDS.next());
        Seeded someone = payment(IDS.next(), payable, "AUTHORIZED", 3);
        Seeded mine = payment(own, payable, "AUTHORIZED", 3);
        hold(someone);
        hold(mine);
        long pages = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'payments.HeldPaymentsListed'"
                + " AND actor_id = ?", analyst.identity().toString());

        List<String> held = walk("/v1/operator/held-payments?limit=200", analyst.token(), "paymentIntentId", -1);
        assertThat(held).contains(someone.intent()).as("the reader's own party's held payment is withheld")
                .doesNotContain(mine.intent());
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'payments.HeldPaymentsListed'"
                        + " AND actor_id = ?", analyst.identity().toString()))
                .isGreaterThan(pages);
        assertThat(get("/v1/operator/held-payments?cursor=not-a-cursor", analyst.token()).statusCode()).isEqualTo(422);
        assertThat(get("/v1/operator/held-payments", sessionWith(RoleName.CUSTOMER_SUPPORT_AGENT).token()).statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("V042: the search is a backward range of its index - the window, a party's, a merchant's - with no sort")
    void theSearchWalksItsIndexes() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                try (PreparedStatement off = app.prepareStatement("SET LOCAL enable_seqscan = off")) {
                    off.execute();
                }
                String window = " i.created_at >= now() - interval '1 day' AND i.created_at < now()";
                String resume = " AND (i.created_at, i.id) < (now(), '" + IDS.next() + "'::uuid)";
                String order = " ORDER BY i.created_at DESC, i.id DESC LIMIT 51";
                for (String[] probe : List.of(
                        new String[] {"payment_intent_by_creation", window + resume},
                        new String[] {"payment_intent_by_party_and_creation",
                                window + " AND i.party_id = '" + IDS.next() + "'::uuid" + resume},
                        new String[] {"payment_intent_by_credit_account_and_creation",
                                window + " AND i.credit_account_id = '" + IDS.next() + "'::uuid" + resume})) {
                    String plan = plan(app, "EXPLAIN SELECT i.* FROM payments.payment_intent i WHERE" + probe[1] + order);
                    assertThat(plan).as(plan).contains("Scan Backward using " + probe[0]).doesNotContain("Sort");
                }
            } finally {
                app.rollback();
            }
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private record Seeded(String intent, String attempt) {}

    private record Session(String token, UUID identity) {}

    /** A card payment of {@code party}'s crediting {@code payable}, its attempt in {@code state}, made minutes ago - by row. */
    private static Seeded payment(UUID party, UUID payable, String state, int minutesAgo) throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        boolean captured = state.equals("CAPTURED");
        boolean authorized = !state.startsWith("AUTH_");
        raw("INSERT INTO payments.payment_intent (id, party_id, customer_id, payment_method_id, credit_account_id,"
                        + " amount_minor, currency, scale, status, created_at, capture_mode) VALUES (?, ?, ?, ?, ?, 1000,"
                        + " 'EUR', 2, ?, now() - CAST(? AS interval), ?)",
                intent, party, IDS.next(), IDS.next(), payable, captured ? "SUCCEEDED" : "PROCESSING",
                minutesAgo + " minutes", state.equals("AUTHORIZED") ? "MANUAL" : "AUTOMATIC");
        raw("INSERT INTO payments.payment_attempt (id, intent_id, auth_reference, capture_reference, auth_provider_reference,"
                        + " capture_provider_reference, authorized_amount_minor, authorized_currency, authorized_scale,"
                        + " captured_amount_minor, captured_currency, captured_scale, status, created_at, rail,"
                        + " interaction_model) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() - CAST(? AS interval),"
                        + " 'card', 'TWO_STEP')",
                attempt, intent, "auth-" + IDS.next(), state.equals("AUTHORIZED") ? null : "cap-" + IDS.next(),
                authorized ? "psp-auth-" + IDS.next() : null,
                captured ? "psp-cap-" + IDS.next() : null,
                authorized ? 1000L : null, authorized ? "EUR" : null, authorized ? (short) 2 : null,
                captured ? 1000L : null, captured ? "EUR" : null, captured ? (short) 2 : null,
                state, minutesAgo + " minutes");
        return new Seeded(intent.toString(), attempt.toString());
    }

    /** A dispute on {@code attempt} at {@code stage}, its network deadline {@code respondBy} or unstated - by row. */
    private static String dispute(String attempt, String stage, Instant respondBy) throws SQLException {
        UUID dispute = IDS.next();
        // The chargeback with its attribution (V021) - the shape MerchantTenancyBatteryDatabaseTest plants.
        raw("INSERT INTO payments.dispute (id, provider, provider_dispute_reference, attempt_id, reason, stage,"
                        + " chargeback_amount_minor, chargeback_currency, chargeback_scale, counterparty_share_amount_minor,"
                        + " counterparty_share_currency, counterparty_share_scale, parked_share_amount_minor,"
                        + " parked_share_currency, parked_share_scale, respond_by, opened_at) VALUES (?, 'simulated-card', ?,"
                        + " ?, 'FRAUD', ?, 1000, 'EUR', 2, 1000, 'EUR', 2, 0, 'EUR', 2, ?, now())",
                dispute, "dp_" + IDS.next().toString().replace("-", ""), UUID.fromString(attempt), stage,
                respondBy == null ? null : java.sql.Timestamp.from(respondBy));
        return dispute.toString();
    }

    /** An open capture hold on the payment, through its own store - the birth V034's trigger admits. */
    private static void hold(Seeded payment) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            new JdbcCaptureHoldStore().insert(app, PaymentIntentId.of(UUID.fromString(payment.intent())),
                    PaymentAttemptId.of(UUID.fromString(payment.attempt())), Duration.ofDays(3));
            app.commit();
        }
    }

    private static UUID party() throws SQLException {
        UUID party = IDS.next();
        raw("INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'A Customer',"
                + " now() - interval '1 hour')", party);
        return party;
    }

    private UUID payable(UUID merchant) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID id = ledgerAccountStore.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                    AccountPurpose.MERCHANT_PAYABLE, EUR, merchant)).account().id().value();
            app.commit();
            return id;
        }
    }

    private static UUID partyOf(UUID identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement("SELECT party_id FROM identity.identity WHERE id = ?")) {
            read.setObject(1, identity);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    /** A {@code MULTI_FACTOR} operator session holding {@code role}, and its identity. */
    private Session sessionWith(RoleName role) throws Exception {
        String login = "preads." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        return new Session(com.finapp.app.session.OperatorStepUp.elevated(token, this::post), identity);
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    /** Walks {@code first} to its last page - asserting the page count unless it is negative; every item's {@code idField}. */
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
            path = first + (first.contains("?") ? "&" : "?") + "cursor=" + URLEncoder.encode(next.group(1), StandardCharsets.UTF_8);
        }
        if (pages >= 0) {
            assertThat(served).as("pages walked").isEqualTo(pages);
        }
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

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
