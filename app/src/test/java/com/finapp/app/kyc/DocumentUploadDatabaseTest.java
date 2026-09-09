package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.kyc.DocumentAccess;
import com.finapp.kyc.DocumentBytes;
import com.finapp.kyc.DocumentCipher;
import com.finapp.kyc.DocumentId;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycStorageException;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code POST /v1/me/kyc/documents} and the audited read path, over real HTTP and a real
 * database (`P2-TSK-008`, {@code INV-KYC-06}, {@code INV-HIST-02}).
 *
 * <h2>The case is seeded through the production store, not the consumer</h2>
 *
 * <p>In production a registration's case arrives through Kafka (`P2-TSK-007`) — a worker this
 * tier deliberately does not run. The fixture opens the case with {@code JdbcKycCaseStore}
 * itself, so the row is exactly what production writes; the chain that produces it is the kafka
 * tier's proven subject, not this test's.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("document upload and the audited read path (P2-TSK-008)")
@SuppressWarnings("try") // actor and correlation scopes are used for their close side effect
class DocumentUploadDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private DocumentAccess documentAccess;
    @Autowired private DocumentCipher documentCipher;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    @Test
    @DisplayName("an upload stores ciphertext and a checksum, and the plaintext is in no column")
    void anUploadStoresEncryptedChecksummedEvidence() throws Exception {
        Person ada = givenAPersonWithAnOpenCase();
        byte[] content = uniqueContent("a passport scan, deliberately unique per run");

        HttpResponse<String> response = upload(ada.session(), body(content));

        assertThat(response.statusCode()).isEqualTo(201);
        UUID documentId = documentIdOf(response);

        // Every column of the stored row, derived from information_schema rather than listed -
        // the P1-TSK-007 idiom: the obvious test checks the column its author was thinking of
        // and passes against an implementation that wrote the plaintext somewhere else as well.
        String plaintextBase64 = Base64.getEncoder().encodeToString(content);
        String plaintextHex = HexFormat.of().formatHex(content);
        try (Connection app = DatabaseRoles.application()) {
            for (String column : columnsOf(app)) {
                Object value = columnValue(app, column, documentId);
                if (value instanceof byte[] bytes) {
                    assertThat(bytes)
                            .as("column %s must not hold the plaintext", column)
                            .isNotEqualTo(content);
                    assertThat(indexOf(bytes, content))
                            .as("column %s must not contain the plaintext", column)
                            .isEqualTo(-1);
                } else if (value != null) {
                    assertThat(value.toString())
                            .as("column %s must not carry the content in any rendering", column)
                            .doesNotContain(plaintextBase64)
                            .doesNotContain(plaintextHex);
                }
            }
            assertThat((byte[]) columnValue(app, "checksum_sha256", documentId))
                    .as("INV-HIST-02: the checksum of the bytes received")
                    .isEqualTo(MessageDigest.getInstance("SHA-256").digest(content));
        }
    }

    @Test
    @DisplayName("a retry converges: same bytes, same document, one row")
    void aRetryConvergesOnOneDocument() throws Exception {
        Person ada = givenAPersonWithAnOpenCase();
        String body = body(uniqueContent("uploaded twice"));

        HttpResponse<String> first = upload(ada.session(), body);
        HttpResponse<String> second = upload(ada.session(), body);

        // A retry after a lost response and a first upload are the same intent, so both are 201
        // and the identifier is the same - openOrConverge's semantics at the contract.
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(documentIdOf(second)).isEqualTo(documentIdOf(first));
        assertThat(documentCountFor(ada.caseId())).isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances uploading the same bytes produce one document")
    void tenConcurrentIdenticalUploadsProduceOneDocument() throws Exception {
        Person ada = givenAPersonWithAnOpenCase();
        String body = body(uniqueContent("raced by ten"));

        List<Callable<HttpResponse<String>>> uploads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            uploads.add(() -> upload(ada.session(), body));
        }
        ExecutorService racers = Executors.newFixedThreadPool(10);
        try {
            List<Future<HttpResponse<String>>> outcomes = racers.invokeAll(uploads);
            for (Future<HttpResponse<String>> outcome : outcomes) {
                assertThat(outcome.get().statusCode())
                        .as("every racer converges on the one document")
                        .isEqualTo(201);
            }
        } finally {
            racers.shutdown();
        }

        // The unique index on (case_id, checksum_sha256) is the arbiter; the savepoint is what
        // lets nine losers converge instead of aborting their transactions.
        assertThat(documentCountFor(ada.caseId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the read path decrypts, verifies the checksum, and audits who looked")
    void theReadPathDecryptsVerifiesAndAudits() throws Exception {
        Person ada = givenAPersonWithAnOpenCase();
        byte[] content = uniqueContent("read back through the one audited path");
        UUID documentId = documentIdOf(upload(ada.session(), body(content)));
        Actor reviewer = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);

        try (SecurityContext.Scope actor = SecurityContext.enter(reviewer);
                CorrelationContext.Scope flow = correlationScope();
                Connection app = DatabaseRoles.application()) {
            // The audit record needs the caller's transaction (ADR-0010), as in production.
            app.setAutoCommit(false);
            assertThat(
                            documentAccess
                                    .read(app, DocumentId.of(documentId))
                                    .orElseThrow()
                                    .content())
                    .as("the bytes received are the bytes returned")
                    .isEqualTo(content);
            app.commit();
        }

        // INV-KYC-06: the trail of who looked is the control. The record names the reader and
        // the document; the change summary names the case, so an investigator reads one row.
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT actor_id, change_summary FROM platform.audit_record"
                                        + " WHERE operation = 'kyc.DocumentContentRead'"
                                        + " AND target_id = ?")) {
            select.setString(1, documentId.toString());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("every content read writes a record").isTrue();
                assertThat(rows.getString("actor_id")).isEqualTo(reviewer.id());
                assertThat(rows.getString("change_summary")).contains(ada.caseId().toString());
                assertThat(rows.next()).as("one read, one record").isFalse();
            }
        }
    }

    @Test
    @DisplayName("a read of a document that does not exist writes no audit record")
    void aMissingDocumentWritesNoRecord() throws Exception {
        DocumentId guessed = DocumentId.next(IDS);

        try (SecurityContext.Scope actor =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = correlationScope();
                Connection app = DatabaseRoles.application()) {
            assertThat(documentAccess.read(app, guessed)).isEmpty();
        }

        // A trail entry for a guessed identifier would put identifiers that were never real into
        // the permanent record (P1-TSK-014's reasoning for revocations that ended nothing).
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'kyc.DocumentContentRead'"
                                        + " AND target_id = ?")) {
            select.setString(1, guessed.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("a tampered ciphertext is refused, never returned")
    void aTamperedCiphertextIsRefused() throws Exception {
        Person ada = givenAPersonWithAnOpenCase();
        UUID documentId = documentIdOf(upload(ada.session(), body(uniqueContent("to tamper"))));

        // The application role holds no UPDATE on evidence - the privilege model helping the
        // test - so corruption is injected as the schema owner, which is exactly the write-access
        // attacker GCM's authentication exists to refuse.
        try (Connection bootstrap = DatabaseRoles.bootstrap();
                PreparedStatement flip =
                        bootstrap.prepareStatement(
                                "UPDATE kyc.kyc_document SET content_ciphertext ="
                                        + " set_byte(content_ciphertext, 0,"
                                        + " get_byte(content_ciphertext, 0) # 1)"
                                        + " WHERE id = ?")) {
            flip.setObject(1, documentId);
            assertThat(flip.executeUpdate()).isEqualTo(1);
        }

        try (SecurityContext.Scope actor =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = correlationScope();
                Connection app = DatabaseRoles.application()) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> documentAccess.read(app, DocumentId.of(documentId)));
        }
    }

    @Test
    @DisplayName("substituted content that decrypts cleanly still fails checksum verification")
    void swappedContentFailsChecksumVerification() throws Exception {
        Person ada = givenAPersonWithAnOpenCase();
        byte[] original = uniqueContent("the bytes the checksum records");
        UUID documentId = documentIdOf(upload(ada.session(), body(original)));

        // GCM says "this ciphertext is one this key wrote"; the checksum says "these bytes are
        // the ones received at capture". To separate the two controls, substitute a ciphertext
        // the SAME key genuinely wrote - same length, so every CHECK constraint passes - and
        // only the checksum can notice.
        byte[] substitute = original.clone();
        substitute[0] ^= 0x7f;
        DocumentCipher.Encrypted forged = documentCipher.encrypt(DocumentBytes.of(substitute));
        try (Connection bootstrap = DatabaseRoles.bootstrap();
                PreparedStatement swap =
                        bootstrap.prepareStatement(
                                "UPDATE kyc.kyc_document SET content_ciphertext = ?,"
                                        + " content_nonce = ?, key_version = ? WHERE id = ?")) {
            swap.setBytes(1, forged.ciphertext());
            swap.setBytes(2, forged.nonce());
            swap.setInt(3, forged.keyVersion());
            swap.setObject(4, documentId);
            assertThat(swap.executeUpdate()).isEqualTo(1);
        }

        try (SecurityContext.Scope actor =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE));
                CorrelationContext.Scope flow = correlationScope();
                Connection app = DatabaseRoles.application()) {
            assertThatExceptionOfType(KycStorageException.class)
                    .isThrownBy(() -> documentAccess.read(app, DocumentId.of(documentId)))
                    .withMessageContaining("checksum");
        }
    }

    @Test
    @DisplayName("no open case is a 409 naming kyc.NoOpenCase")
    void noOpenCaseIsAConflict() throws Exception {
        Person ada = givenAPerson();

        HttpResponse<String> response = upload(ada.session(), body(uniqueContent("homeless")));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("kyc.NoOpenCase");
    }

    @Test
    @DisplayName("no request shape is our fault, and refused shapes store nothing")
    void boundaryRefusalsAreNeverOurFault() throws Exception {
        Person ada = givenAPersonWithAnOpenCase();

        String oversized =
                Base64.getEncoder().encodeToString(new byte[DocumentBytes.MAX_BYTES + 1]);
        for (String body :
                new String[] {
                    // Oversized by one byte: passes the base64 @Size bound (they are the same
                    // arithmetic), refused by DocumentBytes after decoding.
                    "{\"documentType\":\"PASSPORT\",\"contentType\":\"JPEG\",\"content\":\""
                            + oversized
                            + "\"}",
                    "{\"documentType\":\"PASSPORT\",\"contentType\":\"JPEG\",\"content\":\"not base64!!\"}",
                    "{\"documentType\":\"UTILITY_BILL\",\"contentType\":\"JPEG\",\"content\":\"aGk=\"}",
                    "{\"documentType\":\"PASSPORT\",\"contentType\":\"image/jpeg\",\"content\":\"aGk=\"}",
                    "{\"documentType\":\"PASSPORT\",\"contentType\":\"JPEG\"}",
                    "{\"documentType\":\"PASSPORT\",\"contentType\":\"JPEG\",\"content\":\"\"}",
                    "{}",
                    "",
                    "not json"
                }) {
            assertThat(upload(ada.session(), body).statusCode())
                    .as("body %s", body.substring(0, Math.min(80, body.length())))
                    .isIn(400, 422);
        }
        assertThat(documentCountFor(ada.caseId())).as("nothing was stored by any of them").isZero();

        assertThat(upload(null, body(uniqueContent("anonymous"))).statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------

    private record Person(UUID party, UUID customer, UUID caseId, String session) {}

    private HttpResponse<String> upload(String token, String body) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/v1/me/kyc/documents"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String body(byte[] content) {
        return "{\"documentType\":\"PASSPORT\",\"contentType\":\"JPEG\",\"content\":\""
                + Base64.getEncoder().encodeToString(content)
                + "\"}";
    }

    /** Unique per run, so convergence cannot cross tests and the sweep target is this row's. */
    private static byte[] uniqueContent(String label) {
        return (label + " " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private static UUID documentIdOf(HttpResponse<String> response) {
        String body = response.body();
        int start = body.indexOf("\"documentId\":\"") + "\"documentId\":\"".length();
        return UUID.fromString(body.substring(start, body.indexOf('"', start)));
    }

    private static CorrelationContext.Scope correlationScope() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    private static long documentCountFor(UUID caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.kyc_document WHERE case_id = ?")) {
            select.setObject(1, caseId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static List<String> columnsOf(Connection app) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = 'kyc' AND table_name = 'kyc_document'")) {
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        assertThat(columns).as("the sweep must actually see the table").hasSizeGreaterThan(5);
        return columns;
    }

    private static Object columnValue(Connection app, String column, UUID documentId)
            throws SQLException {
        // The column name comes from information_schema, not from input.
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT " + column + " FROM kyc.kyc_document WHERE id = ?")) {
            select.setObject(1, documentId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getObject(1);
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private Person givenAPerson() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        UUID customer = IDS.next();
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
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
            // PENDING is the KYC-in-progress state: the relationship exists and the decision has
            // not been taken. Insert-only, back-dating unnecessary (single statement).
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at)"
                            + " VALUES (?, ?, 'PENDING', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    customer,
                    party);
        }
        return new Person(party, customer, null, givenASessionFor(IdentityId.of(identity)));
    }

    private Person givenAPersonWithAnOpenCase() throws SQLException {
        Person person = givenAPerson();
        try (Connection app = DatabaseRoles.application()) {
            // The store's savepoint needs a real transaction, exactly as production gives it one.
            app.setAutoCommit(false);
            KycCase opened =
                    cases.openOrConverge(app, KycCase.open(IDS, CLOCK, person.customer()))
                            .kycCase();
            app.commit();
            return new Person(
                    person.party(), person.customer(), opened.id().value(), person.session());
        }
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
