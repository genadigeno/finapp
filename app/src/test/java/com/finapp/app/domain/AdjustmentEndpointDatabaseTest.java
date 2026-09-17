package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * `POST /v1/ledger/adjustments` over real HTTP (`P3-TSK-017`, {@code INV-REV-04},
 * {@code INV-AUD-03}): the highest-risk financial action, behind its permission, with its
 * reason, audited against the person — and no request shape our {@code 500}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the adjustment endpoint: reason, permission, audit (P3-TSK-017)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AdjustmentEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Pattern ENTRY_ID = Pattern.compile("\"entryId\"\\s*:\\s*\"([^\"]+)\"");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MeterRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("an operator posts a balanced adjustment: 201, ADJUSTMENT with its reason,"
            + " audited against the person, announced, projected")
    void anOperatorPostsAnAdjustment() throws Exception {
        IdentityId operator = givenAnIdentity();
        givenTheRole(operator, RoleName.LEDGER_OPERATOR);
        String token = givenASessionFor(operator);
        LedgerAccount wallet = givenAWallet();

        HttpResponse<String> response =
                post(body(wallet, "5.00", "correcting settlement break INC-2041"), token,
                        "adj-" + IDS.next());
        assertThat(response.statusCode()).isEqualTo(201);
        UUID entry = entryIdOf(response);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(entryColumn(app, entry, "entry_type")).isEqualTo("ADJUSTMENT");
            assertThat(entryColumn(app, entry, "reason"))
                    .isEqualTo("correcting settlement break INC-2041");
            assertThat(entryColumn(app, entry, "actor_id"))
                    .isEqualTo(operator.value().toString());

            // The registered action, with its reason, naming the PERSON (INV-REV-04,
            // INV-AUD-01) - not JOURNAL_ENTRY_POSTED, because the adjustment's regime is
            // its own.
            assertThat(adjustmentAuditRowsFor(app, entry)).isEqualTo(1);
            assertThat(auditReasonFor(app, entry))
                    .isEqualTo("correcting settlement break INC-2041");
            assertThat(auditActorFor(app, entry)).isEqualTo(operator.value().toString());
            assertThat(eventRowsFor(app, entry)).isEqualTo(1);
            assertThat(postedMinorOf(app, wallet)).isEqualTo(500);
        }
    }

    @Test
    @DisplayName("a session without the role is refused, and nothing is written"
            + " (INV-AUD-03's negative)")
    void aSessionWithoutTheRoleIsRefused() throws Exception {
        IdentityId person = givenAnIdentity();
        String token = givenASessionFor(person);
        LedgerAccount wallet = givenAWallet();

        long entriesBefore = adjustmentEntryCount();
        HttpResponse<String> response =
                post(body(wallet, "5.00", "attempted without the role"), token,
                        "adj-" + IDS.next());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(adjustmentEntryCount()).isEqualTo(entriesBefore);
    }

    @Test
    @DisplayName("a missing reason is a 422, and nothing is written (INV-REV-04)")
    void aMissingReasonIs422() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        long entriesBefore = adjustmentEntryCount();
        String withoutReason =
                "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                        + "\"reference\":\"adj-probe\",\"lines\":" + lines(wallet, "5.00") + "}";
        HttpResponse<String> response = post(withoutReason, operator.token(), "adj-" + IDS.next());
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed");
        assertThat(adjustmentEntryCount()).isEqualTo(entriesBefore);
    }

    @Test
    @DisplayName("an unbalanced adjustment is a 422 - and the key survives to carry the"
            + " corrected request (validate before claim)")
    void anUnbalancedAdjustmentLeavesItsKeyUsable() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();
        String key = "adj-" + IDS.next();

        String unbalanced =
                "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                        + "\"reference\":\"adj-probe\",\"reason\":\"unbalanced probe\","
                        + "\"lines\":[" + line(clearing(), "DEBIT", "5.00") + ","
                        + line(wallet, "CREDIT", "4.00") + "]}";
        HttpResponse<String> refused = post(unbalanced, operator.token(), key);
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("ledger.UnbalancedAdjustment");

        // The refusal never consumed the key: the corrected request under the SAME key is
        // the retry the design promises the operator (P3-TSK-006's validate-then-claim).
        HttpResponse<String> corrected =
                post(body(wallet, "5.00", "corrected probe"), operator.token(), key);
        assertThat(corrected.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("the write path feeds finapp.ledger.posting - posted, replayed, refused -"
            + " through the WIRED observer, so a bean measuring nothing cannot hide"
            + " (P3-TSK-020)")
    void theWritePathFeedsThePostingMeter() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();
        double posted = postingOutcome("posted");
        double replayed = postingOutcome("replayed");
        double refused = postingOutcome("refused");
        double timed = registry.get("finapp.ledger.posting.latency").timer().count();

        String key = "adj-" + IDS.next();
        assertThat(post(body(wallet, "5.00", "meter probe INC-1"), operator.token(), key)
                        .statusCode())
                .isEqualTo(201);
        assertThat(postingOutcome("posted")).isEqualTo(posted + 1);

        assertThat(post(body(wallet, "5.00", "meter probe INC-1"), operator.token(), key)
                        .statusCode())
                .isEqualTo(201);
        assertThat(postingOutcome("replayed"))
                .as("a replay is never posted throughput (P2-TSK-020's discipline)")
                .isEqualTo(replayed + 1);
        assertThat(postingOutcome("posted")).isEqualTo(posted + 1);

        String unbalanced =
                "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                        + "\"reference\":\"adj-probe\",\"reason\":\"meter refusal probe\","
                        + "\"lines\":[" + line(clearing(), "DEBIT", "5.00") + ","
                        + line(wallet, "CREDIT", "4.00") + "]}";
        assertThat(post(unbalanced, operator.token(), "adj-" + IDS.next()).statusCode())
                .isEqualTo(422);
        assertThat(postingOutcome("refused")).isEqualTo(refused + 1);

        // Every command is timed, whatever its outcome - the latency a caller experienced.
        assertThat((double) registry.get("finapp.ledger.posting.latency").timer().count())
                .isEqualTo(timed + 3);
    }

    private double postingOutcome(String outcome) {
        return registry.get("finapp.ledger.posting").tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("a retried key replays the original; another operator's replay conflicts")
    void aReplayIsTheOriginalAndAStrangersConflicts() throws Exception {
        Operator first = givenAnOperator();
        Operator second = givenAnOperator();
        LedgerAccount wallet = givenAWallet();
        String key = "adj-" + IDS.next();
        String body = body(wallet, "7.00", "replay probe");

        HttpResponse<String> original = post(body, first.token(), key);
        assertThat(original.statusCode()).isEqualTo(201);
        UUID entry = entryIdOf(original);

        HttpResponse<String> replay = post(body, first.token(), key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(entryIdOf(replay)).isEqualTo(entry);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(adjustmentAuditRowsFor(app, entry)).isEqualTo(1);
        }

        // The fingerprint binds the ACTOR (ADR-0004's owning principal): a key is not a
        // secret, and a second operator replaying it must conflict, never inherit the
        // first's adjustment.
        HttpResponse<String> stranger = post(body, second.token(), key);
        assertThat(stranger.statusCode()).isEqualTo(409);

        // And it binds the REASON (INV-IDEM-03): the justification is what makes an
        // adjustment defensible, so a key reused with a different one is a materially
        // different request - a conflict, never a silent collapse into one record.
        HttpResponse<String> differentReason =
                post(body(wallet, "7.00", "a different justification"), first.token(), key);
        assertThat(differentReason.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("a line naming an unknown account is a 422, and nothing is written")
    void anUnknownAccountIs422() throws Exception {
        Operator operator = givenAnOperator();

        long entriesBefore = adjustmentEntryCount();
        String unknownAccount =
                "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                        + "\"reference\":\"adj-probe\",\"reason\":\"unknown account probe\","
                        + "\"lines\":[" + line(clearing(), "DEBIT", "5.00") + ","
                        + "{\"accountId\":\"" + IDS.next() + "\",\"direction\":\"CREDIT\","
                        + "\"amount\":\"5.00\",\"currency\":\"USD\"}]}";
        HttpResponse<String> response = post(unknownAccount, operator.token(), "adj-" + IDS.next());
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("ledger.UnknownAccount");
        assertThat(adjustmentEntryCount()).isEqualTo(entriesBefore);
    }

    @Test
    @DisplayName("a keyless request is refused before the handler is entered")
    void aKeylessRequestIs422() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        HttpResponse<String> response = post(body(wallet, "5.00", "keyless probe"),
                operator.token(), null);
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.IdempotencyKeyRequired");
    }

    @Test
    @DisplayName("no request shape is our 500 - every refusal is the caller's 4xx")
    void noBodyShapeIsA500() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        String[] shapes = {
            // amount not a number
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "abc") + "," + line(wallet, "CREDIT", "abc") + "]}",
            // amount not representable at the currency's scale (INV-MON-03: never rounded)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "5.001") + "," + line(wallet, "CREDIT", "5.001")
                    + "]}",
            // zero amount (a line asserting nothing)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "0.00") + "," + line(wallet, "CREDIT", "0.00")
                    + "]}",
            // negative amount (a credit wearing a debit's clothes)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "-5.00") + "," + line(wallet, "CREDIT", "-5.00")
                    + "]}",
            // lowercase currency (the boundary's own pattern)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[{\"accountId\":\""
                    + clearing().id().value() + "\",\"direction\":\"DEBIT\","
                    + "\"amount\":\"5.00\",\"currency\":\"usd\"},"
                    + line(wallet, "CREDIT", "5.00") + "]}",
            // malformed account identifier
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[{\"accountId\":\"not-a-uuid\","
                    + "\"direction\":\"DEBIT\",\"amount\":\"5.00\",\"currency\":\"USD\"},"
                    + line(wallet, "CREDIT", "5.00") + "]}",
            // unknown direction (refused by the enum binding)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[{\"accountId\":\""
                    + clearing().id().value() + "\",\"direction\":\"SIDEWAYS\","
                    + "\"amount\":\"5.00\",\"currency\":\"USD\"},"
                    + line(wallet, "CREDIT", "5.00") + "]}",
            // no lines at all
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[]}",
            // broken JSON
            "{\"postingDate\":",
        };
        for (String shape : shapes) {
            HttpResponse<String> response = post(shape, operator.token(), "adj-" + IDS.next());
            assertThat(response.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isBetween(400, 499);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Operator(IdentityId identity, String token) {}

    private Operator givenAnOperator() throws SQLException {
        IdentityId identity = givenAnIdentity();
        givenTheRole(identity, RoleName.LEDGER_OPERATOR);
        return new Operator(identity, givenASessionFor(identity));
    }

    /** An owned USD wallet account — a legal target for an adjustment's customer side. */
    private LedgerAccount givenAWallet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount wallet =
                    accounts.createOrConverge(
                                    app,
                                    LedgerAccount.owned(
                                            IDS,
                                            CLOCK,
                                            AccountType.LIABILITY,
                                            AccountPurpose.CUSTOMER_WALLET,
                                            USD,
                                            IDS.next()))
                            .account();
            app.commit();
            return wallet;
        }
    }

    private LedgerAccount clearing() {
        try (Connection app = DatabaseRoles.application()) {
            return accounts
                    .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                    .orElseThrow();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String body(LedgerAccount wallet, String amount, String reason) {
        return "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                + "\"reference\":\"adj-probe\",\"reason\":\"" + reason + "\","
                + "\"lines\":" + lines(wallet, amount) + "}";
    }

    private String lines(LedgerAccount wallet, String amount) {
        return "[" + line(clearing(), "DEBIT", amount) + "," + line(wallet, "CREDIT", amount)
                + "]";
    }

    private static String line(LedgerAccount account, String direction, String amount) {
        return "{\"accountId\":\"" + account.id().value() + "\",\"direction\":\"" + direction
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    private HttpResponse<String> post(String body, String token, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port + "/v1/ledger/adjustments"))
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

    private static UUID entryIdOf(HttpResponse<String> response) {
        Matcher matcher = ENTRY_ID.matcher(response.body());
        assertThat(matcher.find()).as("the response carries the entry id").isTrue();
        return UUID.fromString(matcher.group(1));
    }

    // -----------------------------------------------------------------
    // Fixtures shared with DenyByDefaultDatabaseTest's idioms

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Adjusting Operator', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    "adj" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private void givenTheRole(IdentityId identity, RoleName role) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    // -----------------------------------------------------------------
    // Counters - in the tables, never inferred

    private static String entryColumn(Connection app, UUID entry, String column)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT " + column + " FROM ledger.journal_entry WHERE id = ?")) {
            read.setObject(1, entry);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long adjustmentAuditRowsFor(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = 'ledger.AdjustmentPosted'"
                                + " AND target_id = ?")) {
            count.setString(1, entry.toString());
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String auditReasonFor(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT reason FROM platform.audit_record"
                                + " WHERE operation = 'ledger.AdjustmentPosted'"
                                + " AND target_id = ?")) {
            read.setString(1, entry.toString());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static String auditActorFor(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT actor_id FROM platform.audit_record"
                                + " WHERE operation = 'ledger.AdjustmentPosted'"
                                + " AND target_id = ?")) {
            read.setString(1, entry.toString());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long eventRowsFor(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event"
                                + " WHERE event_type = 'ledger.JournalEntryPosted'"
                                + " AND aggregate_id = ?")) {
            count.setObject(1, entry);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long postedMinorOf(Connection app, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT posted_minor FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?")) {
            read.setObject(1, account.id().value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long adjustmentEntryCount() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM ledger.journal_entry"
                                        + " WHERE entry_type = 'ADJUSTMENT'")) {
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
}
