package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.SettlementFileMetrics;
import com.finapp.identity.Authorization;
import com.finapp.settlement.FileParsing;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
 * The settlement surfaces over real HTTP (`P8-TSK-003`, ADR-0066): every accept clause driven
 * through the real door — the upload lands, replays and converges with {@code duplicateOf};
 * the bounds answer {@code 413} with nothing stored; a PAN answers {@code 422} naming its
 * line and never its value; self-attestation refuses and a second person's attestation lands;
 * content reads are reasoned, audited per read and byte-identical; negatives per route; and
 * the file gauges are published by this freshly started instance.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the settlement operator routes (P8-TSK-003)")
class SettlementRoutesDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String SOURCE = "simulated-psp.settlement";
    private static final String PAN = "4111111111111111";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MeterRegistry registry;
    @Autowired private FileParsing fileParsing;

    // -----------------------------------------------------------------
    // Negatives per route: no session, then a session whose role holds neither permission.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every route refuses an anonymous caller (401), a role-less session (403)"
            + " and a money-operating session that holds neither evidence permission (403)")
    void everyRouteRefusesTheWrongCaller() throws Exception {
        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        String noRoles = tokenFrom(authenticate(registered()).body());
        String someId = UUID.randomUUID().toString();
        record Route(String method, String path, String body, boolean keyed) {}
        List<Route> routes =
                List.of(
                        new Route("POST", "/v1/operator/settlement/files", uploadBody("abc\n"), true),
                        new Route("POST", "/v1/operator/settlement/files/" + someId + "/attestation", null, false),
                        new Route("GET", "/v1/operator/settlement/sources", null, false),
                        new Route("GET", "/v1/operator/settlement/files", null, false),
                        new Route("GET", "/v1/operator/settlement/files/" + someId, null, false),
                        new Route("GET", "/v1/operator/settlement/refused-deliveries", null, false),
                        new Route("POST", "/v1/operator/settlement/files/" + someId + "/content-reads",
                                "{\"reason\":\"probe\"}", true),
                        // P8-TSK-008: the decline and the batch read.
                        new Route("POST", "/v1/operator/settlement/files/" + someId + "/decline",
                                "{\"reason\":\"probe\"}", false),
                        new Route("GET", "/v1/operator/settlement/batches/" + someId, null, false));
        for (Route route : routes) {
            HttpResponse<String> anonymous =
                    exchange(route.method(), route.path(), route.body(), null,
                            route.keyed() ? someKey() : null);
            assertThat(anonymous.statusCode())
                    .as("%s %s without a session", route.method(), route.path())
                    .isEqualTo(401);
            HttpResponse<String> noPermission =
                    exchange(route.method(), route.path(), route.body(), noRoles,
                            route.keyed() ? someKey() : null);
            assertThat(noPermission.statusCode())
                    .as("%s %s with a session and no role at all", route.method(), route.path())
                    .isEqualTo(403);
            HttpResponse<String> wrongDesk =
                    exchange(route.method(), route.path(), route.body(), ledgerOperator,
                            route.keyed() ? someKey() : null);
            assertThat(wrongDesk.statusCode())
                    .as("%s %s as LEDGER_OPERATOR - the population this desk checks",
                            route.method(), route.path())
                    .isEqualTo(403);
        }
    }

    // -----------------------------------------------------------------
    // The upload: lands, replays under its key, conflicts on reuse, converges on content.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an upload lands 202; the same key replays byte-identically; the same key"
            + " with a different body conflicts; a second principal's same bytes converge"
            + " with duplicateOf and its own receipt")
    void uploadLandsReplaysAndConverges() throws Exception {
        String operatorOne = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String operatorTwo = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String content = "upload-" + suffix() + ",alpha\n";
        String key = someKey();

        HttpResponse<String> first =
                post("/v1/operator/settlement/files", uploadBody(content), operatorOne, key);
        assertThat(first.statusCode()).isEqualTo(202);
        String fileId = field(first.body(), "fileId");
        assertThat(field(first.body(), "status")).isEqualTo("RECEIVED");
        assertThat(first.body()).contains("\"duplicateOf\":null");

        HttpResponse<String> replay =
                post("/v1/operator/settlement/files", uploadBody(content), operatorOne, key);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.body())
                .as("the recorded outcome, replayed - never a re-read (INV-IDEM-01)")
                .isEqualTo(first.body());

        HttpResponse<String> reused =
                post(
                        "/v1/operator/settlement/files",
                        uploadBody("different-" + content),
                        operatorOne,
                        key);
        assertThat(reused.statusCode())
                .as("the same key with a materially different request (INV-IDEM-03)")
                .isEqualTo(409);

        // The SAME key string under ANOTHER principal is a fresh claim, not a collision and
        // not a replay of a stranger's outcome: the scope is per principal from birth
        // (settlement.upload:<actorType>:<actorId>, the X-TSK-003 disposition). A shared
        // scope would answer this 409 on the differing fingerprint - or worse, replay
        // operator one's response to operator two.
        HttpResponse<String> sameKeyOtherPrincipal =
                post(
                        "/v1/operator/settlement/files",
                        uploadBody("own-" + content),
                        operatorTwo,
                        key);
        assertThat(sameKeyOtherPrincipal.statusCode())
                .as("two principals' identical keys never collide (per-principal scope)")
                .isEqualTo(202);
        assertThat(field(sameKeyOtherPrincipal.body(), "fileId")).isNotEqualTo(fileId);

        HttpResponse<String> converged =
                post("/v1/operator/settlement/files", uploadBody(content), operatorTwo,
                        someKey());
        assertThat(converged.statusCode()).isEqualTo(202);
        assertThat(field(converged.body(), "fileId")).isEqualTo(fileId);
        assertThat(field(converged.body(), "duplicateOf")).isEqualTo(fileId);
        assertThat(count(
                        "SELECT count(*) FROM settlement.file_receipt WHERE file_id = ?",
                        UUID.fromString(fileId)))
                .as("the second principal's delivery leaves ITS OWN receipt - the"
                        + " per-principal scope's observable")
                .isEqualTo(2);

        HttpResponse<String> view =
                get("/v1/operator/settlement/files/" + fileId, operatorOne);
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(field(view.body(), "sourceCode")).isEqualTo(SOURCE);
        assertThat(field(view.body(), "status")).isEqualTo("RECEIVED");

        HttpResponse<String> sources = get("/v1/operator/settlement/sources", operatorOne);
        assertThat(sources.statusCode()).isEqualTo(200);
        assertThat(sources.body()).contains(SOURCE).contains("\"active\":true");

        HttpResponse<String> files = get("/v1/operator/settlement/files", operatorOne);
        assertThat(files.statusCode()).isEqualTo(200);
        assertThat(files.body()).contains(fileId);
    }

    @Test
    @DisplayName("8 MiB + 1 byte decoded answers 413 settlement.FileTooLarge with nothing"
            + " stored but the refusal's audit record")
    void oversizeIsRefusedByTheDomain() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        long filesBefore = count("SELECT count(*) FROM settlement.file");
        long refusalRowsBefore = count("SELECT count(*) FROM settlement.refused_delivery");

        byte[] oversize = new byte[8 * 1024 * 1024 + 1];
        for (int i = 0; i < oversize.length; i++) {
            oversize[i] = (byte) ('a' + (i % 26));
        }
        HttpResponse<String> refused =
                post(
                        "/v1/operator/settlement/files",
                        uploadBodyRaw(Base64.getEncoder().encodeToString(oversize)),
                        operator,
                        someKey());
        assertThat(refused.statusCode()).isEqualTo(413);
        assertThat(refused.body()).contains("settlement.FileTooLarge");
        assertThat(count("SELECT count(*) FROM settlement.file")).isEqualTo(filesBefore);
        assertThat(count("SELECT count(*) FROM settlement.refused_delivery"))
                .as("an over-bound delivery leaves the audit record ALONE (ADR-0066 §4)")
                .isEqualTo(refusalRowsBefore);
    }

    @Test
    @DisplayName("a PAN in free text answers 422 naming its line and field slot - the value"
            + " in no response, no row and no audit summary - and the refusal is listed")
    void aPanLeavesOnlyMetadata() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String dirty = "id,amount,reference\n1,100," + PAN + "\n";

        HttpResponse<String> refused =
                post("/v1/operator/settlement/files", uploadBody(dirty), operator, someKey());
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body())
                .contains("settlement.DeliveryRefused")
                .contains("line 2")
                .doesNotContain(PAN);

        HttpResponse<String> listed =
                get("/v1/operator/settlement/refused-deliveries", operator);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body())
                .contains("PRIMARY_ACCOUNT_NUMBER")
                .contains("\"lineNo\":2")
                .doesNotContain(PAN);

        assertThat(count(
                        "SELECT count(*) FROM platform.audit_record WHERE change_summary"
                                + " LIKE ?",
                        "%" + PAN + "%"))
                .as("the value is in no audit summary (INV-PAY-02)")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------
    // Attestation over HTTP: the second person lands it, both refusal ranks answer 409.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the uploader's own attestation answers 409 AttestationBySubmitter; a second"
            + " person's lands and shows on the file; a third answers 409 FileNotAttestable;"
            + " the same attester converges")
    void attestationOverHttp() throws Exception {
        String uploader = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String attester = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String third = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String content = "attest-" + suffix() + ",beta\n";
        HttpResponse<String> landed =
                post("/v1/operator/settlement/files", uploadBody(content), uploader, someKey());
        String fileId = field(landed.body(), "fileId");

        HttpResponse<String> self =
                post("/v1/operator/settlement/files/" + fileId + "/attestation", null,
                        uploader, null);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("settlement.AttestationBySubmitter");

        HttpResponse<String> second =
                post("/v1/operator/settlement/files/" + fileId + "/attestation", null,
                        attester, null);
        assertThat(second.statusCode()).isEqualTo(200);
        String attestedBy = field(second.body(), "attestedBy");

        HttpResponse<String> converge =
                post("/v1/operator/settlement/files/" + fileId + "/attestation", null,
                        attester, null);
        assertThat(converge.statusCode()).isEqualTo(200);
        assertThat(field(converge.body(), "attestedBy")).isEqualTo(attestedBy);

        HttpResponse<String> refused =
                post("/v1/operator/settlement/files/" + fileId + "/attestation", null,
                        third, null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("settlement.FileNotAttestable");

        HttpResponse<String> view =
                get("/v1/operator/settlement/files/" + fileId, uploader);
        assertThat(field(view.body(), "attestedBy")).isEqualTo(attestedBy);

        HttpResponse<String> unknown =
                post(
                        "/v1/operator/settlement/files/" + UUID.randomUUID()
                                + "/attestation",
                        null,
                        attester,
                        null);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("settlement.FileNotFound");
    }

    // -----------------------------------------------------------------
    // The one content path (INV-REC-10).
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a content read is reasoned, audited once per read and byte-identical; an"
            + " unreasoned one is refused; a guessed id is a 404 that records nothing")
    void contentReadsAreReasonedAuditedAndByteIdentical() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String content = "content-" + suffix() + ",gamma\n";
        String encoded =
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> landed =
                post("/v1/operator/settlement/files", uploadBodyRaw(encoded), operator,
                        someKey());
        String fileId = field(landed.body(), "fileId");
        long readsBefore = contentReadAudits();

        HttpResponse<String> first =
                post(
                        "/v1/operator/settlement/files/" + fileId + "/content-reads",
                        "{\"reason\":\"break B-42 under review\"}",
                        operator,
                        null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(field(first.body(), "content"))
                .as("byte-identical to the upload (INV-HIST-02)")
                .isEqualTo(encoded);

        HttpResponse<String> secondRead =
                post(
                        "/v1/operator/settlement/files/" + fileId + "/content-reads",
                        "{\"reason\":\"break B-42, second look\"}",
                        operator,
                        null);
        assertThat(secondRead.statusCode()).isEqualTo(200);
        assertThat(contentReadAudits())
                .as("one record per read, counted (INV-REC-10)")
                .isEqualTo(readsBefore + 2);

        HttpResponse<String> unreasoned =
                post(
                        "/v1/operator/settlement/files/" + fileId + "/content-reads",
                        "{\"reason\":\"  \"}",
                        operator,
                        null);
        assertThat(unreasoned.statusCode()).isEqualTo(422);

        long allAudits = contentReadAudits();
        HttpResponse<String> guessed =
                post(
                        "/v1/operator/settlement/files/" + UUID.randomUUID()
                                + "/content-reads",
                        "{\"reason\":\"a guess\"}",
                        operator,
                        null);
        assertThat(guessed.statusCode()).isEqualTo(404);
        assertThat(contentReadAudits())
                .as("a guessed identifier records nothing")
                .isEqualTo(allAudits);
    }

    // -----------------------------------------------------------------
    // The file gauges (the acceptance's "published by a fresh instance").
    // -----------------------------------------------------------------

    @Test
    @DisplayName("this freshly started instance publishes both file gauges per declared"
            + " source, and the pending gauge counts a landed upload")
    void theFileGaugesArePublished() throws Exception {
        for (String source :
                List.of(
                        "simulated-psp.settlement",
                        "simulated-scheme.cycle-report",
                        "simulated-payout.settlement",
                        "simulated-bank.statement")) {
            Gauge pending =
                    registry.find(SettlementFileMetrics.PENDING).tag("source", source).gauge();
            Gauge age = registry.find(SettlementFileMetrics.AGE).tag("source", source).gauge();
            assertThat(pending).as("pending gauge for %s", source).isNotNull();
            assertThat(age).as("age gauge for %s", source).isNotNull();
        }
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        post(
                "/v1/operator/settlement/files",
                uploadBody("gauge-" + suffix() + ",delta\n"),
                operator,
                someKey());
        double pending =
                registry.find(SettlementFileMetrics.PENDING).tag("source", SOURCE).gauge()
                        .value();
        assertThat(pending)
                .as("a landed upload is a pending file, and unreadable would be NaN")
                .isGreaterThanOrEqualTo(1.0);
    }

    // -----------------------------------------------------------------
    // The parse leg, the batch read and the decline (P8-TSK-008), over the real wiring:
    // the schedule is off in tests, so the sweep is driven through the REAL FileParsing
    // bean - the composed formats, transaction runner, outbox and committed meters.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an uploaded report parses through the composed leg; the batch read serves"
            + " the attester's totals; the decline rejects file and batch together and a"
            + " second decline answers 409; the intake meters count after commit")
    void theParseLegBatchReadAndDeclineOverHttp() throws Exception {
        String operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String batchRef = "PSPB-HTTP-" + suffix();
        // Rendered by the fixture the later legs feed from - what the simulated PSP did.
        String csv =
                new String(
                        new SimulatedSettlementReports(
                                        batchRef,
                                        "EUR",
                                        java.time.LocalDate.parse("2026-09-29"),
                                        "PSP-REM-20260929")
                                .with(SimulatedSettlementReports.Line.capture(
                                        "PSP-CAP-" + suffix(), "", "", "100.00", "1.75"))
                                .render(),
                        StandardCharsets.UTF_8);
        HttpResponse<String> landed =
                post("/v1/operator/settlement/files", uploadBody(csv), operator, someKey());
        assertThat(landed.statusCode()).isEqualTo(202);
        String fileId = field(landed.body(), "fileId");

        FileParsing.SweepResult swept = fileParsing.sweep();
        assertThat(swept.parsed()).isGreaterThanOrEqualTo(1);
        assertThat(field(get("/v1/operator/settlement/files/" + fileId, operator).body(),
                        "status"))
                .isEqualTo("PARSED");

        UUID batchId = batchIdOf(UUID.fromString(fileId));
        HttpResponse<String> batch =
                get("/v1/operator/settlement/batches/" + batchId, operator);
        assertThat(batch.statusCode()).isEqualTo(200);
        assertThat(field(batch.body(), "externalBatchRef")).isEqualTo(batchRef);
        assertThat(field(batch.body(), "status")).isEqualTo("PARSED");
        assertThat(field(batch.body(), "declaredNet")).isEqualTo("98.25");
        assertThat(batch.body())
                .as("the folded totals are the attester's reading - the split's two lines")
                .contains("\"lineType\":\"CAPTURE\"")
                .contains("\"lineType\":\"PROCESSING_FEE\"")
                .contains("\"amount\":\"100.00\"")
                .contains("\"amount\":\"1.75\"");

        HttpResponse<String> unknownBatch =
                get("/v1/operator/settlement/batches/" + UUID.randomUUID(), operator);
        assertThat(unknownBatch.statusCode()).isEqualTo(404);
        assertThat(unknownBatch.body()).contains("settlement.BatchNotFound");

        HttpResponse<String> reasonless =
                post("/v1/operator/settlement/files/" + fileId + "/decline",
                        "{\"reason\":\"\"}", operator, null);
        assertThat(reasonless.statusCode())
                .as("a decline is reasoned (INV-AUD-03)")
                .isBetween(400, 422);

        HttpResponse<String> declined =
                post("/v1/operator/settlement/files/" + fileId + "/decline",
                        "{\"reason\":\"wrong day's report\"}", operator, null);
        assertThat(declined.statusCode()).isEqualTo(200);
        assertThat(field(declined.body(), "rejectionCode")).isEqualTo("DECLINED");
        assertThat(field(get("/v1/operator/settlement/files/" + fileId, operator).body(),
                        "status"))
                .isEqualTo("REJECTED");
        assertThat(field(get("/v1/operator/settlement/batches/" + batchId, operator).body(),
                        "status"))
                .as("a parsed file's batch rejects WITH it - the live key freed (INV-SET-07)")
                .isEqualTo("REJECTED");

        HttpResponse<String> again =
                post("/v1/operator/settlement/files/" + fileId + "/decline",
                        "{\"reason\":\"again\"}", operator, null);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("settlement.FileNotAttestable");

        // The committed meters: the decline's rejection counted after ITS commit, and the
        // enabled gauge exists eagerly and honestly reads 0 - tests run the leg directly.
        assertThat(registry.find("finapp.settlement.file.rejected")
                        .tag("source", SOURCE)
                        .tag("outcome", "declined")
                        .counter())
                .isNotNull()
                .satisfies(counter -> assertThat(counter.count()).isGreaterThanOrEqualTo(1.0));
        assertThat(registry.find("finapp.settlement.ingestion.latency")
                        .tag("source", SOURCE)
                        .tag("stage", "parse")
                        .timer())
                .isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isGreaterThanOrEqualTo(1L));
        assertThat(registry.find("finapp.settlement.intake.sweeper.enabled").gauge())
                .isNotNull()
                .satisfies(gauge -> assertThat(gauge.value()).isEqualTo(0.0));
    }

    private static UUID batchIdOf(UUID fileId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM settlement.batch WHERE file_id = ?")) {
            read.setObject(1, fileId);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("a PARSED file has its batch").isTrue();
                return row.getObject("id", UUID.class);
            }
        }
    }

    // -----------------------------------------------------------------

    private static String uploadBody(String content) {
        return uploadBodyRaw(
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String uploadBodyRaw(String base64) {
        return "{\"sourceCode\":\"" + SOURCE + "\",\"businessDate\":\"2026-09-29\","
                + "\"content\":\"" + base64 + "\"}";
    }

    private static long contentReadAudits() throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                        + " 'settlement.SettlementFileContentRead'");
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    private String registered() throws Exception {
        String login = "setl." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        return login;
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> exchange(
            String method, String path, String body, String bearer, String key)
            throws Exception {
        return "GET".equals(method) ? get(path, bearer) : post(path, body, bearer, key);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
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

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }
}
