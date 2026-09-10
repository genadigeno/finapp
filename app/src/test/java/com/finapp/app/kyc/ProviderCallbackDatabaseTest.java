package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CallbackSignature;
import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The acceptance of `P2-TSK-011`, over real HTTP against a real database: a signed provider
 * callback completes a stranded {@code DISPATCHED} check <strong>once</strong> — however many
 * times and however concurrently it is delivered ({@code INV-KYC-03}, {@code INV-IDEM-04}) —
 * a late answer is evidence and never a transition ({@code INV-LIFE-04}, {@code INV-HIST-02}),
 * and an unsigned delivery is refused with <strong>nothing written</strong>, which is the
 * verification {@code OwnershipIsScopedTest}'s {@code SIGNED_CALLBACK} entry names.
 *
 * <p>The fixtures create {@code DISPATCHED} checks through the stores rather than through a
 * provider call, because that is exactly the state a crash mid-call leaves behind — the
 * stranded remainder `P2-TSK-009` recorded, whose healer this endpoint is.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("a signed provider callback completes a check exactly once (P2-TSK-011)")
class ProviderCallbackDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Shared with the application through {@code finapp.kyc.callback.key} below. */
    private static final byte[] KEY =
            "a-32-byte-signing-key-for-tests!".getBytes(StandardCharsets.UTF_8);

    private static SimulatedProvider provider;

    @Autowired private CheckStore<Connection> checkStore;
    @LocalServerPort private int port;

    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    @BeforeAll
    static void startProvider() {
        provider = SimulatedProvider.start();
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void callbackKey(DynamicPropertyRegistry registry) {
        registry.add(
                "finapp.kyc.callback.key", () -> Base64.getEncoder().encodeToString(KEY));
    }

    @Test
    @DisplayName("a clear callback completes the stranded check and the case reaches its decision")
    void aClearCallbackCompletesTheCheck() throws Exception {
        Fixture fixture = givenACaseAwaiting(CheckType.IDENTITY);
        String body = callback("dlv-" + UUID.randomUUID(), fixture.pendingCheck(), "clear");

        int status = provider.deliverSignedCallback(target(), body, KEY, 1);

        assertThat(status).isEqualTo(204);
        assertThat(checkStatusOf(fixture.pendingCheck())).isEqualTo("CLEAR");
        // INV-HIST-02 at the row: the evidence checksum is of the bytes the provider sent,
        // verbatim - the ciphertext is not them, the checksum proves they were them.
        assertThat(evidenceChecksumsOf(fixture.pendingCheck()))
                .containsExactly(
                        MessageDigest.getInstance("SHA-256")
                                .digest(body.getBytes(StandardCharsets.UTF_8)));
        // INV-AUD-01: the outcome is recorded through the one trail, whichever door it came by.
        assertThat(auditRecordCountFor(fixture.pendingCheck())).isEqualTo(1);
        // The callback answered the last outstanding question, so it must move the case - and
        // since P2-TSK-013 the automatic policy decides an all-clear case in the same
        // assessment, so the callback door drives the case all the way to its terminal.
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("a hit callback routes the case to a person, never to a terminal (INV-KYC-04)")
    void aHitCallbackRoutesToAPerson() throws Exception {
        Fixture fixture = givenACaseAwaiting(CheckType.SANCTIONS);
        String body = callback("dlv-" + UUID.randomUUID(), fixture.pendingCheck(), "hit");

        int status = provider.deliverSignedCallback(target(), body, KEY, 1);

        assertThat(status).isEqualTo(204);
        assertThat(checkStatusOf(fixture.pendingCheck())).isEqualTo("HIT");
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("IN_REVIEW");
        assertThat(openTaskCountFor(fixture.caseId(), fixture.pendingCheck())).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicated delivery produces one completion, one evidence row, one record")
    void aDuplicatedDeliveryHasOneEffect() throws Exception {
        Fixture fixture = givenACaseAwaiting(CheckType.PEP);
        String deliveryId = "dlv-" + UUID.randomUUID();
        String body = callback(deliveryId, fixture.pendingCheck(), "clear");

        int status = provider.deliverSignedCallback(target(), body, KEY, 3);

        assertThat(status).as("every delivery of a known fact is acknowledged").isEqualTo(204);
        assertThat(checkStatusOf(fixture.pendingCheck())).isEqualTo("CLEAR");
        assertThat(evidenceChecksumsOf(fixture.pendingCheck()))
                .as("the inbox absorbed the redeliveries before the effect")
                .hasSize(1);
        assertThat(auditRecordCountFor(fixture.pendingCheck())).isEqualTo(1);
        assertThat(inboxRowCountFor(deliveryId)).isEqualTo(1);
        // APPROVED since P2-TSK-013: the duplicate deliveries re-assess, and the decision's
        // own conditional converges - one terminal, however many times the fact arrived.
        assertThat(caseStatusOf(fixture.caseId())).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("ten instances delivered one callback concurrently produce one effect")
    void tenConcurrentDeliveriesProduceOneEffect() throws Exception {
        Fixture fixture = givenACaseAwaiting(CheckType.DOCUMENT);
        String deliveryId = "dlv-" + UUID.randomUUID();
        String body = callback(deliveryId, fixture.pendingCheck(), "clear");
        String signed = new CallbackSignature(KEY).sign(body.getBytes(StandardCharsets.UTF_8));

        List<Callable<Integer>> deliveries = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 10; i++) {
            deliveries.add(() -> post(client, body, signed));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> delivered : pool.invokeAll(deliveries)) {
                statuses.add(delivered.get());
            }
        } finally {
            pool.shutdown();
        }

        // 204 is processed-or-absorbed; 409 is the inbox's honest CONTENDED answer, telling
        // the provider to redeliver into the dedupe. Nothing else is acceptable.
        assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(204, 409));
        assertThat(statuses).contains(204);
        assertThat(checkStatusOf(fixture.pendingCheck())).isEqualTo("CLEAR");
        assertThat(evidenceChecksumsOf(fixture.pendingCheck())).hasSize(1);
        assertThat(auditRecordCountFor(fixture.pendingCheck())).isEqualTo(1);
        assertThat(inboxRowCountFor(deliveryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a late callback for a terminal check is evidence, never a transition")
    void aLateCallbackIsEvidenceOnly() throws Exception {
        // The provider answering after our timeout already went INDETERMINATE - plan §8
        // scenario 2. The fixture completes the check the way the timeout path does.
        Fixture fixture = givenACaseAwaiting(CheckType.ADVERSE_MEDIA);
        completeViaStore(fixture.pendingCheck(), CheckOutcome.INDETERMINATE);

        int status =
                provider.deliverSignedCallback(
                        target(),
                        callback("dlv-" + UUID.randomUUID(), fixture.pendingCheck(), "clear"),
                        KEY,
                        1);

        assertThat(status)
                .as("a refusal would make a correct provider retry a fact we will never accept")
                .isEqualTo(204);
        assertThat(checkStatusOf(fixture.pendingCheck()))
                .as("no transition out of a terminal (INV-LIFE-04); resolution is a NEW check")
                .isEqualTo("INDETERMINATE");
        assertThat(evidenceChecksumsOf(fixture.pendingCheck()))
                .as("the late answer is a genuine provider statement, retained (INV-HIST-02)")
                .hasSize(1);
        assertThat(auditRecordCountFor(fixture.pendingCheck()))
                .as("nothing was completed by this delivery, so nothing is recorded as completed")
                .isZero();
    }

    @Test
    @DisplayName("an unsigned or mis-signed delivery is a uniform 401 with NOTHING written")
    void anUnsignedDeliveryWritesNothing() throws Exception {
        Fixture fixture = givenACaseAwaiting(CheckType.IDENTITY);
        String deliveryId = "dlv-" + UUID.randomUUID();
        String body = callback(deliveryId, fixture.pendingCheck(), "clear");

        int unsigned = provider.deliverCallback(target(), body);
        int misSigned =
                provider.deliverSignedCallback(
                        target(), body, "the-wrong-secret".getBytes(StandardCharsets.UTF_8), 1);

        assertThat(unsigned).isEqualTo(401);
        assertThat(misSigned).as("missing and wrong are ONE refusal").isEqualTo(401);
        // The SIGNED_CALLBACK register entry's claim, proven: the refusal grows no table.
        assertThat(checkStatusOf(fixture.pendingCheck())).isEqualTo("DISPATCHED");
        assertThat(evidenceChecksumsOf(fixture.pendingCheck())).isEmpty();
        assertThat(auditRecordCountFor(fixture.pendingCheck())).isZero();
        assertThat(inboxRowCountFor(deliveryId)).isZero();
    }

    @Test
    @DisplayName("every malformed-but-signed shape is the caller's 4xx, never our 500")
    void everyMalformedShapeIsRefusedAsTheCallers() throws Exception {
        Fixture fixture = givenACaseAwaiting(CheckType.IDENTITY);
        String check = fixture.pendingCheck().value().toString();

        assertThat(signedDelivery("this is not JSON")).isEqualTo(400);
        assertThat(signedDelivery("{\"checkId\":\"" + check + "\",\"status\":\"clear\"}"))
                .as("a missing deliveryId is a broken integration, named")
                .isEqualTo(422);
        assertThat(signedDelivery(callbackRaw("  ", check, "clear"))).isEqualTo(422);
        assertThat(signedDelivery(callbackRaw("has a space", check, "clear")))
                .as("the dedupe key is bound for a durable column and for log lines")
                .isEqualTo(422);
        assertThat(signedDelivery(callbackRaw("x".repeat(201), check, "clear")))
                .as("bounded by InboxKey.MAX_LENGTH")
                .isEqualTo(422);
        assertThat(signedDelivery("{\"deliveryId\":\"dlv-1\",\"status\":\"clear\"}"))
                .isEqualTo(422);
        assertThat(signedDelivery(callbackRaw("dlv-2", "not-a-uuid", "clear"))).isEqualTo(422);
        assertThat(signedDelivery(callbackRaw("dlv-3", UUID.randomUUID().toString(), "clear")))
                .as("a v4 is malformed to EntityId - the recurring trap, answered as a 422")
                .isEqualTo(422);
        assertThat(signedDelivery(callbackRaw("dlv-4", IDS.next().toString(), "clear")))
                .as("well-formed but never dispatched by this platform")
                .isEqualTo(404);
        assertThat(signedDelivery("{\"deliveryId\":\"dlv-5\",\"checkId\":\"" + check + "\"}"))
                .isEqualTo(422);
        assertThat(signedDelivery("x".repeat(600_000)))
                .as("the evidence bound, enforced where the caller is told")
                .isEqualTo(413);

        // None of it touched the check.
        assertThat(checkStatusOf(fixture.pendingCheck())).isEqualTo("DISPATCHED");

        // The harness and the verifier agree on the header name, reconciled so the two
        // cannot drift into a suite that signs a header nobody reads.
        assertThat(SimulatedProvider.SIGNATURE_HEADER).isEqualTo(CallbackSignature.HEADER);
    }

    // -----------------------------------------------------------------

    private record Fixture(KycCaseId caseId, CheckId pendingCheck) {}

    /**
     * An open case moved to {@code CHECKS_IN_PROGRESS} with all five checks {@code DISPATCHED}
     * and every one but {@code pending} completed {@code CLEAR} — so the callback under test is
     * the last outstanding answer, and the assessment it triggers is observable on the case.
     */
    private Fixture givenACaseAwaiting(CheckType pending) throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at)"
                            + " VALUES (?, ?, 'PENDING', now() - interval '1 hour',"
                            + " now() - interval '1 hour')",
                    customer,
                    party);
            app.setAutoCommit(false);
            KycCase opened = cases.openOrConverge(app, KycCase.open(IDS, CLOCK, customer)).kycCase();
            CheckId pendingCheck = null;
            for (CheckType type : CheckType.values()) {
                VerificationCheck check =
                        checkStore
                                .requestOrConverge(
                                        app,
                                        VerificationCheck.request(IDS, CLOCK, opened.id(), type))
                                .check();
                checkStore.dispatch(app, check.id(), Instant.now(CLOCK));
                if (type == pending) {
                    pendingCheck = check.id();
                } else {
                    checkStore.complete(app, check.id(), CheckOutcome.CLEAR, Instant.now(CLOCK));
                }
            }
            cases.moveStatus(
                    app,
                    opened.id(),
                    KycCaseStatus.OPEN,
                    KycCaseStatus.CHECKS_IN_PROGRESS,
                    Instant.now(CLOCK));
            app.commit();
            return new Fixture(opened.id(), pendingCheck);
        }
    }

    private void completeViaStore(CheckId checkId, CheckOutcome outcome) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            checkStore.complete(app, checkId, outcome, Instant.now(CLOCK));
            app.commit();
        }
    }

    private URI target() {
        return URI.create("http://localhost:" + port + "/v1/providers/kyc/callbacks");
    }

    private static String callback(String deliveryId, CheckId checkId, String status) {
        return callbackRaw(deliveryId, checkId.value().toString(), status);
    }

    private static String callbackRaw(String deliveryId, String checkId, String status) {
        return "{\"deliveryId\":\"" + deliveryId + "\",\"checkId\":\"" + checkId
                + "\",\"status\":\"" + status + "\"}";
    }

    private int signedDelivery(String body) {
        return provider.deliverSignedCallback(target(), body, KEY, 1);
    }

    private int post(HttpClient client, String body, String signature) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(target())
                        .header("Content-Type", "application/json")
                        .header(CallbackSignature.HEADER, signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // -----------------------------------------------------------------

    private static String checkStatusOf(CheckId checkId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT status FROM kyc.verification_check WHERE id = ?")) {
            select.setObject(1, checkId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static String caseStatusOf(KycCaseId caseId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement("SELECT status FROM kyc.kyc_case WHERE id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private static List<byte[]> evidenceChecksumsOf(CheckId checkId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT checksum_sha256 FROM kyc.verification_evidence"
                                        + " WHERE check_id = ?")) {
            select.setObject(1, checkId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<byte[]> checksums = new ArrayList<>();
                while (rows.next()) {
                    checksums.add(rows.getBytes(1));
                }
                return checksums;
            }
        }
    }

    private static long auditRecordCountFor(CheckId checkId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.audit_record"
                                        + " WHERE operation = 'kyc.CheckCompleted'"
                                        + " AND target_id = ?")) {
            select.setString(1, checkId.value().toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long openTaskCountFor(KycCaseId caseId, CheckId checkId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.review_task"
                                        + " WHERE case_id = ? AND check_id = ?"
                                        + " AND status = 'OPEN'")) {
            select.setObject(1, caseId.value());
            select.setObject(2, checkId.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long inboxRowCountFor(String deliveryId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.inbox_message"
                                        + " WHERE consumer = 'kyc.provider-callback'"
                                        + " AND dedupe_key = ?")) {
            select.setString(1, deliveryId);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
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
