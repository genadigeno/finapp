package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.KycPolicyVersion;
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
 * The end-to-end seed's KYC scenarios (`P17-TSK-113`), run as {@code infra/scripts/e2e-seed.sh} runs them: the very
 * {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application over
 * HTTP through the platform's own doors - the customers' registrations and the applicant's consent; the two plants
 * ({@code kyc-review.sql}, {@code counterparty-screening.sql}) read unchanged from the repository and run as the shell's psql runs
 * them, as the migrator; and what the shell then asks the database, asked here as the script asks it.
 *
 * <p>Then the console's side, through the doors it will use: its KYC_REVIEWER reads the case, resolves the task and decides the case,
 * and releases one screening and blocks another. No provider is configured or called - as on every test stack, which is why both
 * review states are planted.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end seed's KYC scenarios: the console's acts on what the seed planted (P17-TSK-113)")
class E2eSeedKycDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");
    private static final String POLICY = KycPolicyVersion.CURRENT.value();

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private String reviewer;

    /** The run's administrator, as the script's administrator() makes it; the console's own reviewer, granted through the door. */
    @BeforeAll
    void theConsolesReviewer() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        reviewer = idOf(registered("reviewer"));
        seed("grant", "admin", "KYC_REVIEWER", reviewer);
    }

    @Test
    @Order(1)
    @DisplayName("kyc-review: a fresh customer consents through the door; its case planted IN_REVIEW on a sanctions HIT with the check's"
            + " OPEN task, under the platform's policy version - planting again for it writes nothing; the console's reviewer reads it,"
            + " is refused a decision while the task is open, resolves the task once and decides the case, and the customer is ACTIVE;"
            + " an administrator, who holds no KYC_REVIEW, is refused")
    void theConsoleResolvesTheTaskAndDecidesTheCase() throws Exception {
        String applicant = idOf(value("LOGIN", seed("register-bare", "applicant")));
        assertThat(value("CONSENTED", seed("kyc-consent", "applicant"))).isEqualTo("KYC_PROCESSING");
        Answer consents = send("GET", signedIn("applicant"), "/v1/me/consents", null, false);
        assertThat(consents.status()).isEqualTo(200);
        assertThat(consents.body()).contains("\"purpose\":\"KYC_PROCESSING\",\"granted\":true");

        List<String> planted = plantRows("kyc-review.sql", Map.of("identity", applicant));
        assertThat(planted).as("one task, the plant's whole output").hasSize(1);
        String task = planted.get(0);
        assertThat(plantRows("kyc-review.sql", Map.of("identity", applicant))).as("a customer with an open case gets no second").isEmpty();
        // What the shell then asks, as it asks it.
        String reviewed = single("SELECT case_id FROM kyc.review_task WHERE id = '" + task + "'");
        String hit = single("SELECT check_id FROM kyc.review_task WHERE id = '" + task + "'");
        String customer = single("SELECT customer_id FROM kyc.kyc_case WHERE id = '" + reviewed + "'");
        assertThat(single("SELECT c.id || '|' || c.status FROM party.customer c JOIN identity.identity i ON i.party_id = c.party_id"
                + " WHERE i.id = '" + applicant + "'")).isEqualTo(customer + "|PENDING");
        assertThat(single("SELECT status || '|' || policy_version || '|' || case_kind FROM kyc.kyc_case WHERE id = '" + reviewed + "'"))
                .isEqualTo("IN_REVIEW|" + POLICY + "|KYC");
        assertThat(rows("SELECT check_type || '|' || status || '|' || purpose FROM kyc.verification_check WHERE case_id = '" + reviewed
                + "'")).containsExactly("SANCTIONS|HIT|ONBOARDING");
        assertThat(rows("SELECT id || '|' || check_id || '|' || status FROM kyc.review_task WHERE case_id = '" + reviewed + "'"))
                .containsExactly(task + "|" + hit + "|OPEN");

        String console = elevated("reviewer");
        Answer file = send("GET", console, "/v1/kyc/cases/" + reviewed, null, false);
        assertThat(file.status()).as(file.body()).isEqualTo(200);
        assertThat(field(file.body(), "status")).isEqualTo("IN_REVIEW");
        assertThat(file.body()).contains("\"id\":\"" + task + "\",\"checkId\":\"" + hit + "\",\"status\":\"OPEN\"")
                .contains("\"type\":\"SANCTIONS\",\"status\":\"HIT\"");

        Answer early = post(console, "/v1/kyc/cases/" + reviewed + "/decision",
                "{\"outcome\":\"APPROVED\",\"reason\":\"e2e seed: decided before the hit was cleared\"}");
        assertThat(early.status()).as("no decision while the task is open: %s", early.body()).isEqualTo(409);

        Answer refused = post(elevated("admin"), "/v1/kyc/cases/" + reviewed + "/reviews/" + task + "/resolution",
                "{\"reason\":\"e2e seed: an administrator is no reviewer\"}");
        assertThat(refused.status()).as(refused.body()).isEqualTo(403);
        assertThat(single("SELECT status FROM kyc.review_task WHERE id = '" + task + "'")).isEqualTo("OPEN");

        String resolution = "/v1/kyc/cases/" + reviewed + "/reviews/" + task + "/resolution";
        Answer resolved = post(console, resolution, "{\"reason\":\"e2e seed: the console's reviewer found the hit a false positive\"}");
        assertThat(resolved.status()).as(resolved.body()).isEqualTo(204);
        assertThat(single("SELECT status || '|' || resolved_by FROM kyc.review_task WHERE id = '" + task + "'"))
                .isEqualTo("RESOLVED|" + reviewer);
        assertThat(single("SELECT status FROM kyc.kyc_case WHERE id = '" + reviewed + "'")).isEqualTo("READY_FOR_DECISION");
        assertThat(post(console, resolution, "{\"reason\":\"e2e seed: resolved twice\"}").status()).as("resolved once").isEqualTo(409);

        Answer decided = post(console, "/v1/kyc/cases/" + reviewed + "/decision",
                "{\"outcome\":\"APPROVED\",\"reason\":\"e2e seed: the console's reviewer approved the case\"}");
        assertThat(decided.status()).as(decided.body()).isEqualTo(204);
        assertThat(single("SELECT status FROM kyc.kyc_case WHERE id = '" + reviewed + "'")).isEqualTo("APPROVED");
        assertThat(single("SELECT outcome || '|' || decision_basis || '|' || decided_by FROM kyc.kyc_decision WHERE case_id = '"
                + reviewed + "'")).isEqualTo("APPROVED|REVIEWER|" + reviewer);
        assertThat(single("SELECT status FROM party.customer WHERE id = '" + customer + "'")).isEqualTo("ACTIVE");
    }

    @Test
    @Order(2)
    @DisplayName("screening-review: a screening planted IN_REVIEW on a HIT, decided AUTOMATIC under the platform's policy version, asked"
            + " once, requested by a fresh customer under the seed's own reference - each plant a new one; the console's reviewer"
            + " releases one, the key's replay the same receipt, and blocks the other; an administrator is refused")
    void theConsoleReleasesAndBlocksAScreening() throws Exception {
        String remitter = idOf(value("LOGIN", seed("register-bare", "remitter")));
        List<String> first = plantRows("counterparty-screening.sql", Map.of("requester", remitter));
        List<String> second = plantRows("counterparty-screening.sql", Map.of("requester", remitter));
        assertThat(first).hasSize(1);
        assertThat(second).as("a re-run plants a new screening beside the first").hasSize(1).doesNotContainAnyElementsOf(first);
        String released = first.get(0);
        String blocked = second.get(0);
        assertThat(single("SELECT status || '|' || review_reason || '|' || decision_basis || '|' || policy_version || '|' || attempts"
                + " || '|' || requested_by || '|' || (request_reference = 'e2e-seed-' || id) || '|' || (next_attempt_at IS NULL)"
                + " FROM kyc.counterparty_screening WHERE id = '" + released + "'"))
                .isEqualTo("IN_REVIEW|HIT|AUTOMATIC|" + POLICY + "|1|" + remitter + "|true|true");
        assertThat(rows("SELECT attempt || '|' || verdict FROM kyc.counterparty_screening_attempt WHERE screening_id = '" + released
                + "'")).containsExactly("1|HIT");

        String path = "/v1/operator/kyc/counterparty-screenings/";
        String release = "{\"decision\":\"RELEASE\",\"reasonCode\":\"FALSE_POSITIVE\","
                + "\"narrative\":\"e2e seed: the console's reviewer found the hit a false positive\"}";
        Answer refused = post(elevated("admin"), path + released + "/decision", release);
        assertThat(refused.status()).as(refused.body()).isEqualTo(403);

        String console = elevated("reviewer");
        String key = UUID.randomUUID().toString();
        Answer releasedAnswer = send("POST", console, path + released + "/decision", release, key);
        assertThat(releasedAnswer.status()).as(releasedAnswer.body()).isEqualTo(200);
        assertThat(field(releasedAnswer.body(), "status")).isEqualTo("RELEASED");
        Answer replayed = send("POST", console, path + released + "/decision", release, key);
        assertThat(replayed.status()).as(replayed.body()).isEqualTo(200);
        assertThat(field(replayed.body(), "status")).isEqualTo("RELEASED");
        assertThat(single("SELECT status || '|' || decision_basis || '|' || decided_by || '|' || decision_reason_code"
                + " FROM kyc.counterparty_screening WHERE id = '" + released + "'"))
                .isEqualTo("RELEASED|REVIEWER|" + reviewer + "|FALSE_POSITIVE");

        Answer blockedAnswer = post(console, path + blocked + "/decision", "{\"decision\":\"BLOCK\",\"reasonCode\":\"TRUE_MATCH\","
                + "\"narrative\":\"e2e seed: the console's reviewer confirmed the match\"}");
        assertThat(blockedAnswer.status()).as(blockedAnswer.body()).isEqualTo(200);
        assertThat(single("SELECT status || '|' || decided_by FROM kyc.counterparty_screening WHERE id = '" + blocked + "'"))
                .isEqualTo("BLOCKED|" + reviewer);
    }

    // ------------------------------------------------------------------------------------------------- the script's side, as it runs

    private static List<String> plantRows(String file, Map<String, String> variables) throws Exception {
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
            List<String> first = new ArrayList<>();
            while (rows.next()) {
                first.add(rows.getString(1));
            }
            return first;
        }
    }

    private String registered(String who) throws Exception {
        String login = value("LOGIN", seed("register", who));
        assertThat(login).as("the shape the script checks before SQL").matches("e2e-seed\\.[a-z]+\\.[0-9a-f]{12}");
        return login;
    }

    private static String idOf(String login) throws SQLException {
        assertThat(login).as("the shape the script checks before SQL").matches("e2e-seed\\.[a-z]+\\.[0-9a-f]{12}");
        return single("SELECT id FROM identity.identity WHERE login_identifier = '" + login + "'");
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
        assertThat(process.waitFor(10, TimeUnit.MINUTES)).as("Seed.java %s finished", String.join(" ", command)).isTrue();
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

    private String signedIn(String who) throws Exception {
        Properties people = people();
        Answer signedIn = send("POST", null, "/v1/authentications", "{\"loginIdentifier\":\"" + people.getProperty(who + ".login")
                + "\",\"password\":\"" + people.getProperty(who + ".password") + "\"}", false);
        assertThat(signedIn.status()).as(signedIn.body()).isEqualTo(201);
        return field(signedIn.body(), "sessionToken");
    }

    /** A stepped-up session for one of the seed's identities, at a step its last step-up did not use; the step recorded. */
    private String elevated(String who) throws Exception {
        String session = signedIn(who);
        Properties people = people();
        long last = Long.parseLong(people.getProperty(who + ".step", "0"));
        long step = Instant.now().getEpochSecond() / 30;
        while (step <= last) {
            Thread.sleep(1000);
            step = Instant.now().getEpochSecond() / 30;
        }
        Answer stepped = send("POST", session, "/v1/authentications/mfa",
                "{\"code\":\"" + totp(people.getProperty(who + ".totp"), step) + "\"}", false);
        assertThat(stepped.status()).as(stepped.body()).isEqualTo(200);
        people.setProperty(who + ".step", Long.toString(step));
        try (var out = Files.newOutputStream(state.resolve("people.properties"))) {
            people.store(out, "this run's test identities");
        }
        return field(stepped.body(), "sessionToken");
    }

    /**
     * Keyed, always: the screening door requires a key, and the review doors are to require one (finapp-admin-panel's B6.4, the console
     * already prompting for it) - a header the review doors ignore until then.
     */
    private Answer post(String session, String path, String body) throws Exception {
        return send("POST", session, path, body, UUID.randomUUID().toString());
    }

    private Answer send(String method, String bearer, String path, String body, boolean keyed) throws Exception {
        return send(method, bearer, path, body, keyed ? UUID.randomUUID().toString() : null);
    }

    private Answer send(String method, String bearer, String path, String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/problem+json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
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
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String single(String sql) throws SQLException {
        List<String> read = rows(sql);
        assertThat(read).as("one row for %s", sql).hasSize(1);
        return read.get(0);
    }

    private static List<String> rows(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            List<String> read = new ArrayList<>();
            while (rows.next()) {
                read.add(rows.getString(1));
            }
            return read;
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
