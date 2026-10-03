package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.finapp.settlement.FileParsing;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Every person-written reason door over real HTTP (the Phase 8 → 9 transition: the audit's
 * {@code SEC-04}, {@code SEC-02}'s read and the recorded debt row "Person-written reasons reach
 * the database unscreened"; {@code INV-PAY-02}, {@code INV-RAIL-03}, {@code INV-AUD-02}): the
 * settlement decline, readmission, verification and content read, the reconciliation reprocess
 * and requeue, and the opening position each answer a card number written with spaces, one
 * written with dashes, and an account identifier in its printed groups with
 * {@code 422 api.ValidationFailed} - before any claim, nothing written, the value in no body, no
 * captured log line, no audit record and, by the every-column sweep this suite adds for
 * {@code reconciliation} beside {@code settlement} and {@code platform}, in no column of any
 * row. And a stored batch reference that is not a reference shape is withheld from the batch
 * read rather than served verbatim.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
@DisplayName("every person-written reason door refuses instrument shapes, over HTTP (SEC-04)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class PersonWrittenReasonsAreScreenedDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final String SOURCE = "simulated-psp.settlement";
    private static final String SETTLEMENT = "/v1/operator/settlement";
    private static final String RECONCILIATION = "/v1/operator/reconciliation";

    /**
     * The needles, as they would sit in a reason; each is searched for verbatim. SEC-04's
     * three, and NEW-SEC-2's machine-separated card numbers (the re-gate, 2026-10-03) —
     * refused by the domain rank alone: the V012/V019 twins keep the printed forms.
     */
    private static final List<String> NEEDLES =
            List.of("4111 1111 1111 1111", "4111-1111-1111-1111", "GB82 WEST 1234 5698 7654 32",
                    "4111:1111:1111:1111", "4111_1111_1111_1111");

    /** A batch reference the door admits (14 digits, failing Luhn) but no reference shape. */
    private static final String NOT_A_REFERENCE_DIGITS = "20260925000017";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private FileParsing fileParsing;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @Test
    @DisplayName("the decline, readmission, verification, content read, reprocess, requeue and"
            + " opening-position reasons each refuse a spaced card number, a dashed one and a"
            + " printed account identifier with 422 - no claim, no write, no echo in the body, the"
            + " logs, the audit trail or any column of settlement, reconciliation or platform")
    void everyReasonDoorRefusesTheNeedles(CapturedOutput output) throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        String fileId = uploaded(operator, "PSPB-RSN-" + letters(10));
        List<String> keys = new ArrayList<>();

        for (String needle : NEEDLES) {
            String body = "{\"reason\":\"the caller read " + needle + " aloud\"}";
            refused(post(operator, SETTLEMENT + "/files/" + fileId + "/decline", null, body));
            refused(post(operator, SETTLEMENT + "/files/" + fileId + "/content-reads", null,
                    body));
            refused(post(operator, SETTLEMENT + "/files/" + fileId + "/verification", null,
                    body));
            String reprocessKey = "rsn-reprocess-" + UUID.randomUUID();
            keys.add(reprocessKey);
            refused(post(controller, RECONCILIATION + "/sources/" + SOURCE + "/reprocessing",
                    reprocessKey, body));
            refused(post(controller, RECONCILIATION + "/runs/" + UUID.randomUUID()
                    + "/requeue", null, body));
            String openingKey = "rsn-opening-" + UUID.randomUUID();
            keys.add(openingKey);
            refused(post(controller, RECONCILIATION + "/opening-position", openingKey, body));
        }
        assertThat(field(get(operator, SETTLEMENT + "/files/" + fileId), "status"))
                .as("no decline landed")
                .isEqualTo("RECEIVED");

        // The readmission needs a rejection to recover: a clean decline, then the needles.
        assertThat(post(operator, SETTLEMENT + "/files/" + fileId + "/decline", null,
                        "{\"reason\":\"wrong day's report, re-issue expected\"}")
                        .statusCode())
                .isEqualTo(200);
        for (String needle : NEEDLES) {
            String key = "rsn-readmit-" + UUID.randomUUID();
            keys.add(key);
            refused(post(controller, SETTLEMENT + "/files/" + fileId + "/readmission", key,
                    "{\"reason\":\"recover: the caller read " + needle + " aloud\"}"));
        }

        assertThat(count("SELECT count(*) FROM settlement.file WHERE readmits_file_id = ?::uuid",
                        fileId))
                .as("no readmission")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.reconciliation_batch WHERE kind ="
                        + " 'REPROCESS' AND requested_by IS NOT NULL AND reason LIKE '%caller%'"))
                .as("no reprocessing run")
                .isZero();
        for (String key : keys) {
            assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE"
                            + " idempotency_key = ?", key))
                    .as("the screen runs before the claim: not even a claim for %s", key)
                    .isZero();
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                        + " operation IN ('settlement.SettlementFileContentRead',"
                        + " 'settlement.SettlementFileVerified')", fileId))
                .as("no refused read or verification was audited as an access")
                .isZero();
        for (String needle : NEEDLES) {
            assertThat(output.getAll()).as("[%s] reaches no log line", needle)
                    .doesNotContain(needle);
            assertThat(count("SELECT count(*) FROM platform.audit_record WHERE reason LIKE ? OR"
                            + " change_summary LIKE ?", "%" + needle + "%", "%" + needle + "%"))
                    .as("[%s] rests in no audit record", needle)
                    .isZero();
            assertThat(needleInEveryColumn(needle))
                    .as("[%s] rests in no column of settlement, reconciliation or platform",
                            needle)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("SEC-02's read: a stored batch reference that is not a reference shape - here"
            + " fourteen digits the door admits as clean free text - is withheld from"
            + " GET /batches/{id}, never served verbatim")
    void aBatchReferenceThatIsNotAReferenceShapeIsWithheld() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String fileId = uploaded(operator, "PSPB-" + NOT_A_REFERENCE_DIGITS);
        for (int sweep = 0; sweep < 50
                && !"PARSED".equals(field(get(operator, SETTLEMENT + "/files/" + fileId),
                        "status"));
                sweep++) {
            fileParsing.sweep();
        }
        assertThat(field(get(operator, SETTLEMENT + "/files/" + fileId), "status"))
                .isEqualTo("PARSED");
        String batchId =
                (String) one("SELECT id::text FROM settlement.batch WHERE file_id = ?::uuid",
                        fileId);
        assertThat(one("SELECT external_batch_ref FROM settlement.batch WHERE id = ?::uuid",
                        batchId))
                .as("the parse's class admits it and it rests as the batch identity")
                .isEqualTo("PSPB-" + NOT_A_REFERENCE_DIGITS);

        HttpResponse<String> batch = get(operator, SETTLEMENT + "/batches/" + batchId);
        assertThat(batch.statusCode()).isEqualTo(200);
        assertThat(batch.body())
                .as("the read serves the batch and withholds the reference")
                .contains("\"batchId\":\"" + batchId + "\"")
                .doesNotContain(NOT_A_REFERENCE_DIGITS);

        assertThat(post(operator, SETTLEMENT + "/files/" + fileId + "/decline", null,
                        "{\"reason\":\"test fixture, not for acceptance\"}")
                        .statusCode())
                .isEqualTo(200);
    }

    /** {@code 422 api.ValidationFailed}, and the needle's digits nowhere in the body. */
    private static void refused(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("refused as invalid: %s", response.body())
                .isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed")
                .doesNotContain("4111").doesNotContain("1111 1111").doesNotContain("1111-1111")
                .doesNotContain("WEST");
    }

    // ----------------------------------------------------------------- fixtures

    /** A clean PSP report, letters-only references, uploaded through the real door. */
    private String uploaded(String operator, String batchRef) throws Exception {
        String marker = letters(12);
        String csv =
                "H,SIM_PSP_CSV,1," + batchRef + ",EUR,2026-10-01\n"
                        + "D,1,SALE,100.00,1.75,EUR,2026-10-01,,,PSP-CAP-" + marker
                        + ",,,,Reason screen fixture\n"
                        + "T,1,98.25,PSP-REM-" + (100000 + RANDOMNESS.nextInt(899999)) + "\n";
        HttpResponse<String> landed =
                post(operator, SETTLEMENT + "/files", "rsn-upload-" + UUID.randomUUID(),
                        "{\"sourceCode\":\"" + SOURCE + "\",\"businessDate\":\"2026-10-01\","
                                + "\"content\":\"" + Base64.getEncoder().encodeToString(
                                        csv.getBytes(StandardCharsets.UTF_8)) + "\"}");
        assertThat(landed.statusCode()).as(landed.body()).isEqualTo(202);
        return field(landed, "fileId");
    }

    private static String letters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ('A' + RANDOMNESS.nextInt(26)));
        }
        return letters.toString();
    }

    /** Every text-like column of every table in the three schemas, for one needle. */
    private static List<String> needleInEveryColumn(String needle) throws SQLException {
        List<String> hits = new ArrayList<>();
        List<String[]> columns = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT c.table_schema, c.table_name, c.column_name, c.data_type"
                                        + " FROM information_schema.columns c"
                                        + " JOIN information_schema.tables t"
                                        + " ON t.table_schema = c.table_schema"
                                        + " AND t.table_name = c.table_name"
                                        + " WHERE c.table_schema IN ('settlement',"
                                        + " 'reconciliation', 'platform')"
                                        + " AND t.table_type = 'BASE TABLE'"
                                        + " AND c.table_name <> 'flyway_schema_history'"
                                        + " AND c.data_type IN ('text', 'character varying',"
                                        + " 'character', 'bytea', 'jsonb', 'json')");
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                columns.add(new String[] {
                    rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)
                });
            }
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            for (String[] column : columns) {
                String rendered =
                        "bytea".equals(column[3])
                                ? "encode(\"" + column[2] + "\", 'escape')"
                                : "\"" + column[2] + "\"::text";
                try (PreparedStatement probe =
                        owner.prepareStatement(
                                "SELECT count(*) FROM \"" + column[0] + "\".\"" + column[1]
                                        + "\" WHERE " + rendered + " LIKE ?")) {
                    probe.setString(1, "%" + needle + "%");
                    try (ResultSet row = probe.executeQuery()) {
                        row.next();
                        if (row.getLong(1) > 0) {
                            hits.add(column[0] + "." + column[1] + "." + column[2]);
                        }
                    }
                }
            }
        }
        assertThat(columns.size())
                .as("the sweep reads the reconciliation reason columns among the rest")
                .isGreaterThan(50);
        return hits;
    }

    private String sessionWith(RoleName role) throws SQLException {
        IdentityId identity = givenAnIdentity();
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        }
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, identity, SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8p9-rsn-" + UUID.randomUUID()))
                .causing(CausationId.of("p8p9-rsn-cause"));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Reason Desk', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "rs" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    // ----------------------------------------------------------------- plumbing

    private HttpResponse<String> post(String token, String path, String key, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String field(HttpResponse<String> response, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"")
                        .matcher(response.body());
        assertThat(matcher.find()).as("the body carries %s: %s", name, response.body())
                .isTrue();
        return matcher.group(1);
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }
}
