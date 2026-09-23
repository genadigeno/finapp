package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.payments.WebhookSignature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The payments webhook door over real HTTP (`P5-TSK-012`, ADR-0047 §1–§3 and §5): the forgery
 * surface's negative tests — each cause refused with <strong>nothing written</strong>
 * ({@code INV-PAY-01}) — and the acceptance's triple delivery landing one dedupe record with
 * three evidence decisions.
 *
 * <p>The concurrency proof is the inbox's and is <strong>cited, not repeated</strong>
 * (`P0-TSK-021`: the primary key on {@code (consumer, dedupe_key)} admits one handler run;
 * the contended racer's 409-unacknowledged contract has its own suite). What only this suite
 * can prove is the door: verification before parsing, the freshness window at the wire, the
 * evidence-and-dedupe transaction, attribution by our minted reference, and the anti-stall
 * acknowledgments.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the payments webhook door (P5-TSK-012)")
class PaymentWebhookDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final com.finapp.sharedkernel.id.IdGenerator IDS =
            new com.finapp.sharedkernel.id.IdGenerator(CLOCK, new SecureRandom());

    /** The signing key: configured through the property, used raw by the test's own sender. */
    private static final byte[] WEBHOOK_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // The door is conditional on the provider being configured (the KYC reasoning); the
        // webhook key arrives through the property exactly as a deployment would supply it.
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add(
                "finapp.payments.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    // -----------------------------------------------------------------
    // The acceptance: attribution, and the triple delivery
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a signed fresh delivery is 204: one evidence row attributed to the attempt by"
            + " our minted reference, one dedupe record, both committed")
    void aSignedFreshDeliveryIsRecordedAndAttributed() throws Exception {
        Seeded seeded = seededAttempt();
        String eventId = "evt_" + UUID.randomUUID();
        String body = webhookBody(eventId, seeded.authReference(), "captured");

        int status =
                provider.deliverTimestampSignedCallback(
                        door(), body, WEBHOOK_KEY, nowSeconds(), 1);

        assertThat(status).isEqualTo(204);
        assertThat(evidenceCountFor(seeded.attemptId()))
                .as("the evidence row carries the attempt as its subject (V005)")
                .isEqualTo(1);
        assertThat(inboxCountFor(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a triple delivery lands ONE dedupe record and THREE evidence decisions"
            + " (ADR-0047 §2) - every delivery a 2xx")
    void aTripleDeliveryLandsOneDedupeRecordAndThreeEvidenceRows() throws Exception {
        Seeded seeded = seededAttempt();
        String eventId = "evt_" + UUID.randomUUID();
        String body = webhookBody(eventId, seeded.authReference(), "captured");

        int last =
                provider.deliverTimestampSignedCallback(
                        door(), body, WEBHOOK_KEY, nowSeconds(), 3);

        assertThat(last).as("the duplicate is acknowledged, not refused").isEqualTo(204);
        assertThat(inboxCountFor(eventId)).as("one dedupe record (INV-IDEM-04)").isEqualTo(1);
        assertThat(evidenceCountFor(seeded.attemptId()))
                .as("three evidence decisions: the duplicate's statement is as genuine as the"
                        + " first's (INV-HIST-02)")
                .isEqualTo(3);
    }

    // -----------------------------------------------------------------
    // The forgery surface: every cause refused with nothing written
    // -----------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated and unfresh deliveries are ONE uniform 401 with NOTHING"
            + " written - missing, wrong and tampered signatures, missing, stale and"
            + " future timestamps, and the KYC scheme replayed")
    void unauthenticatedAndUnfreshWriteNothing() throws Exception {
        Seeded seeded = seededAttempt();
        String body = webhookBody("evt_" + UUID.randomUUID(), seeded.authReference(), "captured");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String fresh = Long.toString(nowSeconds());
        String stale = Long.toString(nowSeconds() - Duration.ofMinutes(6).toSeconds());
        String future = Long.toString(nowSeconds() + Duration.ofMinutes(6).toSeconds());

        long evidenceBefore = totalEvidenceCount();
        long inboxBefore = totalInboxCount();

        record Attempted(String cause, String timestamp, String signature) {}
        List<Attempted> forgeries =
                List.of(
                        new Attempted("missing signature", fresh, null),
                        new Attempted("wrong signature", fresh, "00ff00ff"),
                        new Attempted(
                                "tampered body",
                                fresh,
                                hmacHex(fresh + ".{\"eventId\":\"other\"}")),
                        new Attempted("missing timestamp", null, hmacHex(fresh + "." + body)),
                        new Attempted(
                                "stale, legitimately signed", stale, hmacHex(stale + "." + body)),
                        new Attempted(
                                "future-skewed, legitimately signed",
                                future,
                                hmacHex(future + "." + body)),
                        new Attempted(
                                "the KYC scheme replayed: valid HMAC over the body alone",
                                fresh,
                                hmacHex(body)));
        // The named mutation's catcher, run against the UNPARSEABLE shape too: verification
        // moved after parsing would route unsigned garbage into the anti-stall acknowledgment
        // (204, evidence written) - exactly the inversion INV-PAY-01 forbids.
        HttpResponse<String> unsignedGarbage =
                deliver("not json at all".getBytes(StandardCharsets.UTF_8), fresh, "00ff00ff");
        assertThat(unsignedGarbage.statusCode())
                .as("unsigned garbage dies at the door, never at the parser")
                .isEqualTo(401);
        String reference = null;
        for (Attempted forgery : forgeries) {
            HttpResponse<String> refused = deliver(bytes, forgery.timestamp(), forgery.signature());
            assertThat(refused.statusCode()).as(forgery.cause()).isEqualTo(401);
            // One refusal across every cause: which way a forgery failed is not information.
            if (reference == null) {
                reference = normalized(refused.body());
            } else {
                assertThat(normalized(refused.body())).as(forgery.cause()).isEqualTo(reference);
            }
        }

        assertThat(totalEvidenceCount())
                .as("an unauthenticated stranger grows no table (INV-PAY-01)")
                .isEqualTo(evidenceBefore);
        assertThat(totalInboxCount()).isEqualTo(inboxBefore);
    }

    @Test
    @DisplayName("an empty and an oversized body are 413 with nothing written - the evidence"
            + " bound, told at the boundary")
    void theEvidenceBoundRefusesAtTheBoundary() throws Exception {
        long evidenceBefore = totalEvidenceCount();

        byte[] empty = new byte[0];
        String ts = Long.toString(nowSeconds());
        HttpResponse<String> emptyRefused = deliver(empty, ts, hmacHex(ts + "."));
        assertThat(emptyRefused.statusCode()).isEqualTo(413);

        byte[] oversized = new byte[1_048_576 + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        String overTs = Long.toString(nowSeconds());
        HttpResponse<String> overRefused =
                deliver(oversized, overTs, hmacHexOf(overTs, oversized));
        assertThat(overRefused.statusCode()).isEqualTo(413);

        assertThat(totalEvidenceCount()).isEqualTo(evidenceBefore);
    }

    // -----------------------------------------------------------------
    // The anti-stall inversion: authentic-but-unusable is acknowledged with evidence
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an authentic but unparseable body is 204 with its evidence retained"
            + " unattributed and no dedupe record (ADR-0047 §5)")
    void authenticButUnparseableIsAcknowledgedWithEvidence() throws Exception {
        long unattributedBefore = unattributedEvidenceCount();
        long inboxBefore = totalInboxCount();
        String garbage = "not json at all";

        int status =
                provider.deliverTimestampSignedCallback(
                        door(), garbage, WEBHOOK_KEY, nowSeconds(), 1);

        assertThat(status).as("anti-stall: the bytes are held, redelivery adds nothing")
                .isEqualTo(204);
        assertThat(unattributedEvidenceCount()).isEqualTo(unattributedBefore + 1);
        assertThat(totalInboxCount()).as("no event id, no dedupe possible").isEqualTo(inboxBefore);
    }

    @Test
    @DisplayName("an authentic webhook naming no operation we minted is 204: evidence retained"
            + " unattributed, dedupe record landed")
    void authenticButUnmappableIsAcknowledgedWithEvidenceAndDeduped() throws Exception {
        long unattributedBefore = unattributedEvidenceCount();
        String eventId = "evt_" + UUID.randomUUID();
        // A reference shaped like ours that we never minted, and one not even shaped like ours.
        for (String operation : List.of("never-minted-" + UUID.randomUUID(), "shape!!invalid")) {
            String body = webhookBody(eventId + operation.length(), operation, "captured");
            int status =
                    provider.deliverTimestampSignedCallback(
                            door(), body, WEBHOOK_KEY, nowSeconds(), 1);
            assertThat(status).as(operation).isEqualTo(204);
        }
        assertThat(unattributedEvidenceCount()).isEqualTo(unattributedBefore + 2);
        assertThat(inboxCountFor(eventId + "shape!!invalid".length())).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private record Seeded(UUID attemptId, String authReference) {}

    /** An intent and its AUTH_DISPATCHED attempt with a known minted reference, raw SQL. */
    private Seeded seededAttempt() throws SQLException {
        UUID intent = IDS.next();
        UUID attempt = IDS.next();
        String reference = "whk-" + UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO payments.payment_intent (id, party_id, customer_id,"
                            + " payment_method_id, wallet_account_id, amount_minor, currency,"
                            + " scale, status, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, 500, 'USD', 2, 'PROCESSING', now())",
                    intent, IDS.next(), IDS.next(), IDS.next(), IDS.next());
            execute(
                    app,
                    "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference,"
                            + " status, created_at)"
                            + " VALUES (?, ?, ?, 'AUTH_DISPATCHED', now())",
                    attempt, intent, reference);
        }
        return new Seeded(attempt, reference);
    }

    private static String webhookBody(String eventId, String operation, String status) {
        return "{\"eventId\":\"" + eventId + "\",\"operation\":\"" + operation
                + "\",\"status\":\"" + status + "\"}";
    }

    private URI door() {
        return URI.create("http://localhost:" + port + "/v1/providers/payments/webhooks");
    }

    private static long nowSeconds() {
        return Instant.now(CLOCK).getEpochSecond();
    }

    /** The scheme's HMAC, computed independently of the class under test. */
    private static String hmacHex(String signedPayload) {
        return hmacHexOf(null, signedPayload.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHexOf(String timestamp, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
            if (timestamp != null) {
                mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) '.');
            }
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    /** A raw delivery with full control of the headers — the forgery bench. */
    private HttpResponse<String> deliver(byte[] body, String timestamp, String signature)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(door())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (timestamp != null) {
            request.header(WebhookSignature.TIMESTAMP_HEADER, timestamp);
        }
        if (signature != null) {
            request.header(WebhookSignature.SIGNATURE_HEADER, signature);
        }
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
    }

    // -----------------------------------------------------------------
    // Counters
    // -----------------------------------------------------------------

    private static long evidenceCountFor(UUID attemptId) throws SQLException {
        return count(
                "SELECT count(*) FROM payments.provider_evidence WHERE attempt_id = ?",
                attemptId);
    }

    private static long unattributedEvidenceCount() throws SQLException {
        return count(
                "SELECT count(*) FROM payments.provider_evidence"
                        + " WHERE attempt_id IS NULL AND refund_id IS NULL");
    }

    private static long totalEvidenceCount() throws SQLException {
        return count("SELECT count(*) FROM payments.provider_evidence");
    }

    private static long inboxCountFor(String eventId) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.inbox_message WHERE consumer = ?"
                        + " AND dedupe_key = ?",
                PaymentWebhookService.CONSUMER,
                "simulated-card:" + eventId);
    }

    private static long totalInboxCount() throws SQLException {
        return count(
                "SELECT count(*) FROM platform.inbox_message WHERE consumer = ?",
                PaymentWebhookService.CONSUMER);
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
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
