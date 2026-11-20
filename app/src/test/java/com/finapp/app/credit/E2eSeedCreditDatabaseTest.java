package com.finapp.app.credit;

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
 * The end-to-end seed's credit scenarios (`P17-TSK-117`), run as {@code infra/scripts/e2e-seed.sh} runs them: the very
 * {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application over
 * HTTP through the platform's own doors; the verdict plant ({@code customer-verdict.sql}) read unchanged from the repository; and
 * what the shell asks the database - a test identity's id, whether a scorecard is ACTIVE, the version a product holds PROPOSED, the
 * review case the platform opened - asked here as the script asks it.
 *
 * <p><strong>No bureau and no financial-data provider are configured</strong>, as on every test stack (the platform refuses either's
 * URL: unresolved questions #13 and #14). So the seed's PERSONAL_LOAN policy, which reads no source and REFERs on the bureau's
 * absence, opens a review case; the console's underwriter can decline it, and an approval is refused - the exposure it needs is the
 * bureau's - which is why no loan, BNPL agreement, waiver, write-off or BNPL dispute is seeded.
 */
@Tag("database")
// The credit progress sweep runs on every stack (its default); this tier's properties switch it off, so it is switched back on, at its
// stack cadence.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "finapp.credit.progress.sweeper.enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end seed's credit scenarios: the console's acts on what the seed made (P17-TSK-117)")
class E2eSeedCreditDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");
    private static final String REFERENCE_SCORECARD = "0190a1b2-5c0e-7000-8000-00000000c001";

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private String seedPolicy;

    /** The script's credit_desks, step for step; and the console's own people, whom the seed made nothing with. */
    @BeforeAll
    void theDesks() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        seed("grant", "admin", "CREDIT_POLICY_OFFICER", idOf(registered("creditofficer")), idOf(registered("creditapprover")));
        if ("0".equals(single("SELECT count(*) FROM credit.scorecard_model_version WHERE family = 'RETAIL_SCORECARD'"
                + " AND status = 'ACTIVE'"))) {
            seed("approve-scorecard", "creditofficer", REFERENCE_SCORECARD);
        }
        withdraw("creditofficer", "credit-policy", "credit.credit_policy_version", "PERSONAL_LOAN");
        seedPolicy = value("VERSION_ID", seed("propose-version", "creditofficer", "credit-policy", "seed"));
        seed("decide-version", "creditapprover", "credit-policy", seedPolicy, "approval");

        // The console's own people.
        seed("grant", "admin", "UNDERWRITER", idOf(registered("underwriter")));
        seed("grant", "admin", "CREDIT_POLICY_OFFICER", idOf(registered("policyconsole")));
        seed("grant", "admin", "LENDING_OFFICER", idOf(registered("lendingconsole")));
        seed("grant", "admin", "BNPL_OFFICER", idOf(registered("bnplconsole")));
    }

    @Test
    @Order(1)
    @DisplayName("the desks: a RETAIL_SCORECARD is ACTIVE, the seed's PERSONAL_LOAN policy is ACTIVE - reading no source - and no"
            + " PERSONAL_LOAN version is left PROPOSED")
    void theDesksLeaveTheSeedsPolicyInForce() throws Exception {
        assertThat(single("SELECT count(*) FROM credit.scorecard_model_version WHERE family = 'RETAIL_SCORECARD' AND status = 'ACTIVE'"))
                .isEqualTo("1");
        assertThat(single("SELECT status || '|' || (max_data_age_bureau_seconds IS NULL) || '|' || (max_data_age_financial_data_seconds"
                + " IS NULL) || '|' || unavailable_fallback FROM credit.credit_policy_version WHERE id = '" + seedPolicy + "'"))
                .isEqualTo("ACTIVE|true|true|REFER");
        assertThat(rows("SELECT id FROM credit.credit_policy_version WHERE product = 'PERSONAL_LOAN' AND status = 'PROPOSED'"))
                .isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("review-case: a fresh customer's stepped-up request is REFERred and its case OPEN - the console's underwriter takes it;"
            + " an approval is refused for want of the bureau's exposure, and the decline is decided")
    void theConsoleDeclinesTheReviewCase() throws Exception {
        String borrower = idOf(registered("borrower"));
        assertThat(plantRows("customer-verdict.sql", Map.of("identity", borrower))).hasSize(1);
        String request = value("CREDIT_REQUEST_ID", seed("credit-request", "borrower"));
        assertThat(request).as("the shape the script checks before SQL").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        String opened = null;
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (opened == null && Instant.now().isBefore(deadline)) {
            List<String> found = rows("SELECT id FROM credit.underwriting_case WHERE decision_request_id = '" + request
                    + "' AND status = 'OPEN'");
            opened = found.isEmpty() ? null : found.get(0);
            if (opened == null) {
                Thread.sleep(1000);
            }
        }
        assertThat(opened).as("the platform's own sweep opens the request's review case").isNotNull();
        assertThat(single("SELECT product || '|' || requested_minor || '|' || currency FROM credit.underwriting_case WHERE id = '"
                + opened + "'")).isEqualTo("PERSONAL_LOAN|500000|EUR");

        Answer taken = post(elevated("underwriter"), "/v1/operator/credit/review-cases/" + opened + "/assignment", null);
        assertThat(taken.status()).as(taken.body()).isEqualTo(200);
        Answer approved = post(elevated("underwriter"), "/v1/operator/credit/review-cases/" + opened + "/decision",
                "{\"outcome\":\"APPROVED\",\"approvedAmount\":\"5000.00\",\"currency\":\"EUR\","
                        + "\"reasonCodes\":[\"CRD-SOURCE-UNAVAILABLE\"],"
                        + "\"reason\":\"e2e: the console tries an approval\"}");
        assertThat(approved.status()).as("an approval needs the bureau's exposure: " + approved.body()).isEqualTo(422);
        assertThat(approved.body()).contains("ExposureUnassessable");
        Answer declined = post(elevated("underwriter"), "/v1/operator/credit/review-cases/" + opened + "/decision",
                "{\"outcome\":\"DECLINED\",\"reasonCodes\":[\"CRD-INSUFFICIENT-CREDIT-HISTORY\"],"
                        + "\"reason\":\"e2e: the console's underwriter declines\"}");
        assertThat(declined.status()).as(declined.body()).isEqualTo(200);
        assertThat(single("SELECT status || '|' || first_outcome FROM credit.underwriting_case WHERE id = '" + opened + "'"))
                .endsWith("|DECLINED").doesNotStartWith("OPEN").doesNotStartWith("ASSIGNED");
    }

    @Test
    @Order(3)
    @DisplayName("proposed-credit-policy: PROPOSED by the seed's officer - refused to its proposer, a second proposal refused while it"
            + " pends, and approved by the console's officer, retiring the seed's version")
    void theConsoleApprovesTheCreditPolicy() throws Exception {
        String proposed = value("VERSION_ID", seed("propose-version", "creditofficer", "credit-policy", "proposal"));
        assertThat(single("SELECT status FROM credit.credit_policy_version WHERE id = '" + proposed + "'")).isEqualTo("PROPOSED");
        assertThat(run("propose-version", "creditofficer", "credit-policy", "proposal").exit())
                .as("one PROPOSED version per product - why the script withdraws a pending one first").isNotZero();

        Answer own = post(elevated("creditofficer"), "/v1/operator/credit/policies/" + proposed + "/approval",
                "{\"reason\":\"e2e: its proposer\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(403);
        Answer approved = post(elevated("policyconsole"), "/v1/operator/credit/policies/" + proposed + "/approval",
                "{\"reason\":\"e2e: the console's officer approves\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(single("SELECT status FROM credit.credit_policy_version WHERE id = '" + proposed + "'")).isEqualTo("ACTIVE");
        assertThat(single("SELECT status FROM credit.credit_policy_version WHERE id = '" + seedPolicy + "'")).isEqualTo("RETIRED");
    }

    @Test
    @Order(4)
    @DisplayName("proposed-lending-terms: a re-run withdraws the run before's pending terms through the rejection door and proposes its"
            + " own; the proposer is refused, the console's lending officer approves")
    void theConsoleApprovesTheLendingTerms() throws Exception {
        String lender = idOf(registered("lendingofficer"));
        seed("grant", "admin", "LENDING_OFFICER", lender);
        withdraw("lendingofficer", "lending-terms", "lending.loan_terms_version", "PERSONAL_LOAN");
        String earlier = value("VERSION_ID", seed("propose-version", "lendingofficer", "lending-terms", "proposal"));
        // The script run again on the same stack.
        withdraw("lendingofficer", "lending-terms", "lending.loan_terms_version", "PERSONAL_LOAN");
        String proposed = value("VERSION_ID", seed("propose-version", "lendingofficer", "lending-terms", "proposal"));
        assertThat(single("SELECT status FROM lending.loan_terms_version WHERE id = '" + earlier + "'")).isEqualTo("REJECTED");
        assertThat(single("SELECT status || '|' || proposed_by FROM lending.loan_terms_version WHERE id = '" + proposed + "'"))
                .isEqualTo("PROPOSED|" + lender);

        Answer own = post(elevated("lendingofficer"), "/v1/operator/lending/terms/" + proposed + "/approval",
                "{\"reason\":\"e2e: its proposer\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(403);
        Answer approved = post(elevated("lendingconsole"), "/v1/operator/lending/terms/" + proposed + "/approval",
                "{\"reason\":\"e2e: the console's lending officer approves\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(single("SELECT status FROM lending.loan_terms_version WHERE id = '" + proposed + "'")).isEqualTo("ACTIVE");
    }

    @Test
    @Order(5)
    @DisplayName("proposed-bnpl-terms: BNPL_PAY_IN_4 terms PROPOSED by the seed's BNPL officer - refused to their proposer, approved by"
            + " the console's")
    void theConsoleApprovesTheBnplTerms() throws Exception {
        String officer = idOf(registered("bnplofficer"));
        seed("grant", "admin", "BNPL_OFFICER", officer);
        withdraw("bnplofficer", "bnpl-terms", "bnpl.bnpl_terms_version", "BNPL_PAY_IN_4");
        String proposed = value("VERSION_ID", seed("propose-version", "bnplofficer", "bnpl-terms", "proposal"));
        assertThat(single("SELECT status || '|' || proposed_by FROM bnpl.bnpl_terms_version WHERE id = '" + proposed + "'"))
                .isEqualTo("PROPOSED|" + officer);

        Answer own = post(elevated("bnplofficer"), "/v1/operator/bnpl/terms/" + proposed + "/approval",
                "{\"reason\":\"e2e: their proposer\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(403);
        Answer approved = post(elevated("bnplconsole"), "/v1/operator/bnpl/terms/" + proposed + "/approval",
                "{\"reason\":\"e2e: the console's BNPL officer approves\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(single("SELECT status FROM bnpl.bnpl_terms_version WHERE id = '" + proposed + "'")).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------------------------------- the script's steps, as it takes them

    /** The script's withdraw: the product's version left PROPOSED, rejected through the door by the seed's officer. */
    private void withdraw(String who, String kind, String table, String product) throws Exception {
        for (String left : rows("SELECT id FROM " + table + " WHERE product = '" + product + "' AND status = 'PROPOSED'")) {
            seed("decide-version", who, kind, left, "rejection");
        }
    }

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

    private Answer post(String session, String path, String body) throws Exception {
        return send("POST", session, path, body, true);
    }

    private Answer send(String method, String bearer, String path, String body, boolean keyed) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/problem+json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (keyed) {
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
