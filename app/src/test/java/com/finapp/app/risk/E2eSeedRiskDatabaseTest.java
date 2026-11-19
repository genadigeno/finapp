package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The end-to-end seed's risk scenarios (`P17-TSK-110`), run as {@code infra/scripts/e2e-seed.sh} runs them: the very
 * {@code infra/scripts/e2e-seed/Seed.java} as a separate {@code java --source 21} process against this application over HTTP - its
 * officers proposing and activating the seed's TRANSFER version, its analyst opening and taking a case - and the very
 * {@code risk-alert.sql} and {@code sealed-assessment.sql}, read from the repository and run as the compose stack's migrator with
 * their variable made a literal as psql makes it. Then the console's four acceptances (B4.4) are shown possible on what was
 * seeded: an alert suppressed by two analysts, a case taken over and disposed by two, an assessment - its seal holding and its
 * replay IDENTICAL to the engine - overridden by two officers, and a proposed version activated by an officer other than its
 * proposer.
 *
 * <p><strong>Its own container</strong> ({@code RiskPolicyEndpointDatabaseTest}'s reason): it activates a TRANSFER version, and
 * every transfer in the database is then judged by it - another suite in a shared container would meet it, depending on the
 * order the suites ran in.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// The proposed version's activation retires the seed's own, so it runs last, as the console's run would leave it.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end seed's risk scenarios: planted or through the doors, and the console's four-eyes acts on them"
        + " (P17-TSK-110)")
class E2eSeedRiskDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER = Pattern.compile("\"revision\"\\s*:\\s*(\\d+)");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** The seed's ACTIVE TRANSFER version, and its officer - the script's risk_desks. */
    private String version;
    private String officer;
    private String approver;

    @BeforeAll
    void theRiskDesks() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        String analyst = idOf(registered("analyst"));
        officer = idOf(registered("officer"));
        approver = idOf(registered("approver"));
        seed("grant", "admin", "RISK_ANALYST", analyst);
        seed("grant", "admin", "RISK_OFFICER", officer, approver);
        version = value("VERSION_ID", seed("propose-policy", "officer", "seed"));
        seed("activate-policy", "approver", version);
        // The console's own people, for the acts the seed leaves to them.
        for (String who : List.of("suppressor", "decider", "investigator", "disposer")) {
            seed("grant", "admin", "RISK_ANALYST", idOf(registered(who)));
        }
        for (String who : List.of("overrider", "confirmer")) {
            seed("grant", "admin", "RISK_OFFICER", idOf(registered(who)));
        }
    }

    @Test
    @Order(1)
    @DisplayName("the seed's version is the ACTIVE TRANSFER one, proposed by one officer and activated by another")
    void theSeedsVersionRules() throws Exception {
        assertThat(single("SELECT status || '|' || operation || '|' || proposed_by || '|' || decided_by FROM"
                + " risk.risk_policy_version WHERE id = '" + version + "'"))
                .isEqualTo("ACTIVE|TRANSFER|" + officer + "|" + approver);
        Answer inForce = get(elevated("overrider"), "/v1/operator/risk/policies?operation=TRANSFER");
        assertThat(inForce.status()).as(inForce.body()).isEqualTo(200);
        assertThat(field(inForce.body(), "id")).isEqualTo(version);
    }

    @Test
    @Order(2)
    @DisplayName("open-alert: an OPEN alert under the seed's version, which two analysts suppress")
    void anOpenAlertTwoAnalystsSuppress() throws Exception {
        List<String> row = plant("risk-alert.sql", Map.of("version", version));
        String alert = row.get(0);
        assertThat(single("SELECT status || '|' || kind || '|' || policy_version_id FROM risk.risk_alert WHERE id = '" + alert
                + "'")).isEqualTo("OPEN|ACCOUNT_TAKEOVER|" + version);

        Answer proposed = post(elevated("suppressor"), "/v1/operator/risk/alerts/" + alert + "/suppression",
                "{\"reasonCode\":\"FALSE_POSITIVE\",\"reason\":\"the seeded alert, explained\"}");
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        Answer approved = post(elevated("decider"), "/v1/operator/risk/alerts/" + alert + "/suppression/approval",
                "{\"reason\":\"agreed\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"SUPPRESSED\"");
    }

    @Test
    @Order(3)
    @DisplayName("investigated-case: a case opened by hand on a planted alert and taken by the seed's analyst - taken over, and"
            + " disposed by two")
    void anInvestigatedCaseTwoDispose() throws Exception {
        String alert = plant("risk-alert.sql", Map.of("version", version)).get(0);
        String caseId = value("CASE_ID", seed("open-case", "analyst", alert));
        seed("take-case", "analyst", caseId);
        assertThat(single("SELECT c.status || '|' || a.status FROM risk.risk_case c JOIN risk.risk_alert a ON"
                + " a.attached_case_id = c.id WHERE c.id = '" + caseId + "' AND a.id = '" + alert + "'"))
                .isEqualTo("IN_INVESTIGATION|ATTACHED");

        String investigator = elevated("investigator");
        Answer takenOver = post(investigator, "/v1/operator/risk/cases/" + caseId + "/actions", "{\"act\":\"TAKE\",\"revision\":"
                + revisionOf(caseId) + "}");
        assertThat(takenOver.status()).as(takenOver.body()).isEqualTo(200);
        Answer proposed = post(investigator, "/v1/operator/risk/cases/" + caseId + "/dispositions", "{\"revision\":"
                + revision(takenOver.body()) + ",\"disposition\":\"CONFIRMED_FRAUD\",\"prohibit\":false,\"reason\":"
                + "\"the seeded case, confirmed\"}");
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        Answer approved = post(elevated("disposer"), "/v1/operator/risk/cases/" + caseId + "/dispositions/"
                + field(proposed.body(), "dispositionId") + "/approval", "{\"revision\":" + revision(proposed.body())
                + ",\"reason\":\"agreed by a second analyst\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
    }

    @Test
    @Order(4)
    @DisplayName("overridable-assessment: a sealed REVIEW whose seal holds and whose replay is IDENTICAL, overridden by two"
            + " officers")
    void anOverridableAssessmentTwoOfficersOverride() throws Exception {
        String assessment = plant("sealed-assessment.sql", Map.of("version", version)).get(0);

        String investigator = elevated("investigator");
        Answer read = get(investigator, "/v1/operator/risk/assessments/" + assessment);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"decision\":\"REVIEW\"").contains("\"score\":600");
        assertThat(single("SELECT change_summary FROM platform.audit_record WHERE operation = 'risk.AssessmentRead' AND"
                + " target_id = '" + assessment + "'")).as("the seal").contains("seal=HOLDS");
        Answer replay = post(investigator, "/v1/operator/risk/assessments/" + assessment + "/replay",
                "{\"reason\":\"the seeded assessment, replayed\"}");
        assertThat(replay.status()).as(replay.body()).isEqualTo(200);
        assertThat(replay.body()).as("the plant is the engine's verdict for the seed's version").contains("\"verdict\":\"IDENTICAL\"");

        Answer proposed = post(elevated("overrider"), "/v1/operator/risk/assessments/" + assessment + "/overrides",
                "{\"reasonCode\":\"CUSTOMER_VERIFIED\",\"reason\":\"the seeded transfer, verified\"}");
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"requiresSecondPerson\":true");
        Answer approved = post(elevated("confirmer"), "/v1/operator/risk/assessments/" + assessment + "/overrides/"
                + field(proposed.body(), "overrideId") + "/approval", "{\"reason\":\"verified by a second officer\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"");
    }

    @Test
    @Order(5)
    @DisplayName("proposed-policy: a PROPOSED version its proposer cannot activate, and another officer does")
    void aProposedVersionAnotherOfficerActivates() throws Exception {
        String pending = value("VERSION_ID", seed("propose-policy", "officer", "proposal"));
        assertThat(single("SELECT status || '|' || proposed_by FROM risk.risk_policy_version WHERE id = '" + pending + "'"))
                .isEqualTo("PROPOSED|" + officer);

        Answer own = post(elevated("officer"), "/v1/operator/risk/policies/" + pending + "/activation",
                "{\"reason\":\"my own\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfApprovalRefused");
        Answer activated = post(elevated("confirmer"), "/v1/operator/risk/policies/" + pending + "/activation",
                "{\"reason\":\"reviewed by an officer who did not propose it\"}");
        assertThat(activated.status()).as(activated.body()).isEqualTo(200);
        assertThat(activated.body()).contains("\"status\":\"ACTIVE\"");
    }

    // ------------------------------------------------------------------------------------------- the script's steps

    /** A plant run unchanged, its variables made literals as psql's {@code :'name'} makes them; the first row's columns. */
    private static List<String> plant(String file, Map<String, String> variables) throws Exception {
        String text = Files.readString(repositoryRoot().resolve("infra/scripts/e2e-seed/" + file), StandardCharsets.UTF_8);
        Matcher variable = VARIABLE.matcher(text);
        StringBuilder statement = new StringBuilder();
        while (variable.find()) {
            String value = variables.get(variable.group(1));
            assertThat(value).as("the script hands %s :'%s'", file, variable.group(1)).isNotNull();
            variable.appendReplacement(statement, Matcher.quoteReplacement("'" + value.replace("'", "''") + "'"));
        }
        variable.appendTail(statement);
        try (Connection migrator = DatabaseRoles.migrator(); Statement plant = migrator.createStatement();
                ResultSet rows = plant.executeQuery(statement.toString())) {
            assertThat(rows.next()).as("%s returns its row", file).isTrue();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                columns.add(rows.getString(i));
            }
            return columns;
        }
    }

    private String registered(String who) throws Exception {
        String login = value("LOGIN", seed("register", who));
        assertThat(login).as("the shape the script checks before SQL").matches("e2e-seed\\.[a-z]+\\.[0-9a-f]{12}");
        return login;
    }

    private static String idOf(String login) throws SQLException {
        return single("SELECT id FROM identity.identity WHERE login_identifier = '" + login + "'");
    }

    private static String revisionOf(String caseId) throws SQLException {
        return single("SELECT revision FROM risk.risk_case WHERE id = '" + caseId + "'");
    }

    private static int revision(String body) {
        Matcher matcher = NUMBER.matcher(body);
        assertThat(matcher.find()).as("a revision in %s", body).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private record Run(int exit, String output, String error) {}

    private String seed(String... command) throws Exception {
        Run run = run(command);
        assertThat(run.exit()).as("Seed.java %s: %s", String.join(" ", command), run.error()).isZero();
        return run.output();
    }

    /** Seed.java as the script runs it: a separate JDK process, the source file itself, no classpath of this build. */
    private Run run(String... command) throws Exception {
        List<String> line = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "--source", "21",
                repositoryRoot().resolve("infra/scripts/e2e-seed/Seed.java").toString(),
                base(), base(), state.toString()));
        line.addAll(List.of(command));
        Process process = new ProcessBuilder(line).start();
        CompletableFuture<String> output = drain(process.getInputStream());
        CompletableFuture<String> error = drain(process.getErrorStream());
        assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("Seed.java %s finished", String.join(" ", command)).isTrue();
        String printed = output.get();
        for (String printedLine : printed.lines().toList()) {
            assertThat(printedLine).as("Seed.java prints KEY=value lines and nothing else").matches("[A-Z_]+=\\S+");
        }
        return new Run(process.exitValue(), printed, error.get());
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                stream.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private static String value(String key, String output) {
        return output.lines().filter(line -> line.startsWith(key + "=")).map(line -> line.substring(key.length() + 1)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + key + " in: " + output));
    }

    // ------------------------------------------------------------------------------------------- the console's side, for the proof

    private record Answer(int status, String body) {}

    private Properties people() throws IOException {
        Properties people = new Properties();
        try (InputStream in = Files.newInputStream(state.resolve("people.properties"))) {
            people.load(in);
        }
        return people;
    }

    /** A stepped-up session for one of the seed's identities, at a step its last step-up did not use; the step recorded. */
    private String elevated(String who) throws Exception {
        Properties people = people();
        Answer signedIn = send("POST", null, "/v1/authentications", "{\"loginIdentifier\":\"" + people.getProperty(who + ".login")
                + "\",\"password\":\"" + people.getProperty(who + ".password") + "\"}");
        assertThat(signedIn.status()).as(signedIn.body()).isEqualTo(201);
        long last = Long.parseLong(people.getProperty(who + ".step"));
        long step = Instant.now().getEpochSecond() / 30;
        while (step <= last) {
            Thread.sleep(1000);
            step = Instant.now().getEpochSecond() / 30;
        }
        Answer stepped = send("POST", field(signedIn.body(), "sessionToken"), "/v1/authentications/mfa",
                "{\"code\":\"" + totp(people.getProperty(who + ".totp"), step) + "\"}");
        assertThat(stepped.status()).as(stepped.body()).isEqualTo(200);
        people.setProperty(who + ".step", Long.toString(step));
        try (var out = Files.newOutputStream(state.resolve("people.properties"))) {
            people.store(out, "this run's test identities");
        }
        return field(stepped.body(), "sessionToken");
    }

    private Answer get(String session, String path) throws Exception {
        return send("GET", session, path, null);
    }

    private Answer post(String session, String path, String body) throws Exception {
        return send("POST", session, path, body);
    }

    private Answer send(String method, String bearer, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/problem+json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if ("POST".equals(method)) {
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body());
    }

    private static String field(String body, String name) {
        Matcher matcher = FIELD.matcher(body);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        throw new AssertionError("no " + name + " in " + body);
    }

    private static String totp(String base32, long step) throws Exception {
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | (c >= 'A' && c <= 'Z' ? c - 'A' : c - '2' + 26);
            bits += 5;
            if (bits >= 8) {
                key.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.toByteArray(), "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 0xf;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String single(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            return rows.getString(1);
        }
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
