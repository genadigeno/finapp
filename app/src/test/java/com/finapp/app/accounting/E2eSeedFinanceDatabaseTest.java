package com.finapp.app.accounting;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The end-to-end seed's finance scenarios (`P17-TSK-112`), run as {@code infra/scripts/e2e-seed.sh} runs them: the very
 * {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application over
 * HTTP through the platform's own doors, and the plants ({@code fx-rates.sql}, {@code accounting-break.sql}) read unchanged from the
 * repository; the two answers only the database gives - a test identity's id and the run's first administrator - asked here as the
 * script asks them.
 *
 * <p><strong>A fresh database whose books open two months back</strong> (ADR-0152, {@code P17-TSK-120}): the schedule's open leg
 * opens the month two before today's, the one before and today's, as a local stack started with
 * {@code FINAPP_ACCOUNTING_PERIOD_BOOKSOPENMONTHSBACK=2} does. The close's real schedule runs, and the intake's and the matcher's, at
 * a one-second poll: the nil reports are parsed, accepted and matched, and the close run's nine checks concluded, by the same legs as
 * on the stack, never by a call of this test. That is why this suite has a JVM and a database of its own ({@code own-container}).
 *
 * <p>The books are built by the script's {@code finance_books} steps; then each finance scenario is seeded and the console's act on
 * it performed by people the seed did not make its proposers: the closable month closed by four eyes after its set's approval, the
 * prior-period adjustment approved, the mapping version activated, the return approved, the break acknowledged.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finapp.accounting.period.books-open-months-back=2",
            "finapp.accounting.sweeper.enabled=true",
            "finapp.accounting.close.checks-poll=PT1S",
            "finapp.accounting.close.deferral=PT1S",
            "finapp.settlement.intake.sweeper.enabled=true",
            "finapp.settlement.intake.poll=PT1S",
            "finapp.reconciliation.matching.sweeper.enabled=true",
            "finapp.reconciliation.matching.poll=PT1S"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end seed's finance scenarios: the books closed by the seed, and the console's four-eyes acts on what it"
        + " seeded (P17-TSK-112)")
class E2eSeedFinanceDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");
    private static final Pattern CHECK = Pattern.compile("\\{\"check\":\"([A-Z_]+)\",\"attempts\":[0-9]+,\"status\":\"([A-Z_]+)\","
            + "\"history\":\\[\\{\"attempt\":([0-9]+)");

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private String accountant;
    private String controller;
    private String books;
    private String closingId;
    private String closableId;

    /** The script's finance_desks and finance_books, step for step; and the console's own two people. */
    @BeforeAll
    void theBooks() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        accountant = idOf(registered("accountant"));
        controller = idOf(registered("controller"));
        seed("grant", "admin", "ACCOUNTANT", accountant);
        seed("grant", "admin", "REGULATORY_REPORTER", accountant);
        seed("grant", "admin", "FINANCIAL_CONTROLLER", controller);
        books = openedBooks();
        closingId = value("CLOSING_ID", books);
        closableId = value("CLOSABLE_ID", books);
        assertThat(value("CLOSING_STATUS", books)).isEqualTo("OPEN");
        assertThat(value("CLOSABLE_STATUS", books)).isEqualTo("OPEN");

        String booker = idOf(registered("booker"));
        String checker = idOf(registered("checker"));
        seed("grant", "admin", "LEDGER_OPERATOR", booker, checker);
        String submitter = idOf(registered("submitter"));
        String attester = idOf(registered("attester"));
        seed("grant", "admin", "RECONCILIATION_OPERATOR", submitter, attester);
        String mapping = value("MAPPING_VERSION_ID", seed("propose-mapping", "accountant", value("CLOSING_MONTH", books),
                repositoryRoot().resolve("infra/scripts/e2e-seed/gl-mapping.json").toString()));
        seed("activate-mapping", "controller", mapping);
        seed("adjust", "booker", "checker", value("CLOSING_MONTH", books) + "-15");
        assertThat(rows("SELECT statement_sequence FROM settlement.batch b JOIN settlement.source s ON s.id = b.source_id"
                + " WHERE s.code = 'simulated-bank.statement'")).as("a fresh stack's chain starts at one").isEmpty();
        seed("nil-reports", "submitter", "attester", value("CLOSING_MONTH", books) + "-15", "1", "0.00");
        seed("nil-reports", "submitter", "attester", value("CLOSABLE_MONTH", books) + "-15", "2", "0.00");
        assertThat(plantRows("fx-rates.sql", Map.of("observed", value("CLOSING_RATES_AT", books)))).hasSize(4);
        assertThat(plantRows("fx-rates.sql", Map.of("observed", value("CLOSABLE_RATES_AT", books)))).hasSize(4);
        seed("close-month", "accountant", "controller", closingId);

        // The console's own people: an accountant and a controller the seed made no proposer.
        seed("grant", "admin", "ACCOUNTANT", idOf(registered("bookkeeper")));
        seed("grant", "admin", "FINANCIAL_CONTROLLER", idOf(registered("reviewer")));
    }

    @Test
    @Order(1)
    @DisplayName("the books: the month two before today's CLOSED by the seed's accountant and controller under the chart in force from"
            + " it, its four sources' runs COMPLETED; the month before today's OPEN")
    void theBooksAreClosed() throws Exception {
        assertThat(single("SELECT status FROM accounting.accounting_period WHERE id = '" + closingId + "'")).isEqualTo("CLOSED");
        assertThat(single("SELECT status FROM accounting.accounting_period WHERE id = '" + closableId + "'")).isEqualTo("OPEN");
        assertThat(single("SELECT proposed_by || '|' || decided_by FROM accounting.period_close WHERE period_id = '" + closingId
                + "' AND status = 'APPROVED'")).isEqualTo(accountant + "|" + controller);
        // The close itself proves each required source's run COMPLETED in the month (RECONCILIATION_DISCLOSURE); what the seed made
        // for it is each source's nil batch, ACCEPTED, in both months.
        assertThat(rows("SELECT s.code FROM settlement.batch b JOIN settlement.source s ON s.id = b.source_id"
                + " WHERE b.status = 'ACCEPTED' ORDER BY s.code")).containsExactlyInAnyOrder("simulated-bank.statement",
                "simulated-bank.statement", "simulated-payout.settlement", "simulated-payout.settlement", "simulated-psp.settlement",
                "simulated-psp.settlement", "simulated-scheme.cycle-report", "simulated-scheme.cycle-report");
    }

    @Test
    @Order(2)
    @DisplayName("closable-period: the month before today's LOCKED with a captured set; the console's controller approves the set, the"
            + " run completes, its accountant proposes the close and its controller approves it - CLOSED by four eyes")
    void theConsoleClosesTheMonth() throws Exception {
        String set = value("RATE_SET_ID", seed("lock-and-capture", "accountant", closableId));
        assertThat(single("SELECT status || '|' || captured_by FROM accounting.valuation_rate_set WHERE id = '" + set + "'"))
                .isEqualTo("PROPOSED|" + accountant);

        Answer approved = post(elevated("reviewer"), "/v1/operator/accounting/valuation-rate-sets/" + set + "/approval",
                "{\"reason\":\"the month-end rates, reviewed\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        String run = completedRun(closableId);
        List<String> disclosures = new ArrayList<>();
        Matcher check = CHECK.matcher(run);
        while (check.find()) {
            if ("DISCLOSED".equals(check.group(2))) {
                disclosures.add("{\"check\":\"" + check.group(1) + "\",\"attempt\":" + check.group(3) + ",\"reason\":\"disclosed\"}");
            }
        }
        Answer proposed = post(elevated("bookkeeper"), "/v1/operator/accounting/periods/" + closableId + "/close-proposal",
                "{\"closeRunId\":\"" + field(run, "runId") + "\",\"reason\":\"the month, closed\",\"disclosures\":["
                        + String.join(",", disclosures) + "]}");
        assertThat(proposed.status()).as(proposed.body()).isIn(200, 201);
        Answer closed = post(elevated("reviewer"), "/v1/operator/accounting/periods/" + closableId + "/close-approval",
                "{\"proposalId\":\"" + field(proposed.body(), "proposalId") + "\",\"reason\":\"the close, reviewed\"}");
        assertThat(closed.status()).as(closed.body()).isEqualTo(200);
        assertThat(single("SELECT status FROM accounting.accounting_period WHERE id = '" + closableId + "'")).isEqualTo("CLOSED");
    }

    @Test
    @Order(3)
    @DisplayName("prior-period-adjustment: a correction of the CLOSED month proposed by the seed's accountant, which the console's"
            + " controller approves")
    void theConsoleApprovesTheCorrection() throws Exception {
        String revision = value("CLOSING_REVISION", seed("books", "accountant"));
        String ppa = value("PPA_ID", seed("propose-ppa", "accountant", closingId, revision));
        assertThat(single("SELECT status || '|' || proposed_by FROM accounting.prior_period_adjustment WHERE id = '" + ppa + "'"))
                .isEqualTo("PROPOSED|" + accountant);

        String reviewer = elevated("reviewer");
        Answer read = get(reviewer, "/v1/operator/accounting/prior-period-adjustments/" + ppa);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        Answer approved = post(reviewer, "/v1/operator/accounting/prior-period-adjustments/" + ppa + "/approval",
                "{\"postingDate\":\"" + field(read.body(), "postingDate") + "\",\"correctedRevision\":" + revision
                        + ",\"postedCorrections\":[],\"reason\":\"the correction, reviewed\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(single("SELECT status FROM accounting.prior_period_adjustment WHERE id = '" + ppa + "'")).isNotEqualTo("PROPOSED");
    }

    @Test
    @Order(4)
    @DisplayName("proposed-mapping: a version PROPOSED from next month by the seed's accountant, which the console's controller"
            + " activates")
    void theConsoleActivatesTheMapping() throws Exception {
        String pending = value("MAPPING_VERSION_ID", seed("propose-mapping", "accountant", value("NEXT_MONTH", books),
                repositoryRoot().resolve("infra/scripts/e2e-seed/gl-mapping.json").toString()));
        assertThat(single("SELECT status || '|' || proposed_by FROM accounting.gl_mapping_version WHERE id = '" + pending + "'"))
                .isEqualTo("PROPOSED|" + accountant);
        Answer activated = post(elevated("reviewer"), "/v1/operator/accounting/mapping-versions/" + pending + "/activation",
                "{\"reason\":\"next month's chart, reviewed\"}");
        assertThat(activated.status()).as(activated.body()).isEqualTo(200);
        assertThat(activated.body()).contains("\"status\":\"ACTIVE\"");
    }

    @Test
    @Order(5)
    @DisplayName("draft-submission: a reference return of the CLOSED month, VALIDATED under an ACTIVE obligation of a fresh form, which"
            + " the console's controller approves")
    void theConsoleApprovesTheReturn() throws Exception {
        String obligation = value("OBLIGATION_ID", seed("obligation", "accountant", "controller"));
        String submission = value("SUBMISSION_ID", seed("draft-submission", "accountant", obligation, closingId));
        assertThat(single("SELECT status || '|' || prepared_by FROM accounting.regulatory_submission WHERE id = '" + submission + "'"))
                .isEqualTo("VALIDATED|" + accountant);

        String reviewer = elevated("reviewer");
        Answer attestation = get(reviewer, "/v1/operator/regulatory/submissions/" + submission + "/attestation");
        assertThat(attestation.status()).as(attestation.body()).isEqualTo(200);
        Matcher revision = Pattern.compile("\"periodRevision\"\\s*:\\s*(\\d+)").matcher(attestation.body());
        assertThat(revision.find()).isTrue();
        Answer approved = post(reviewer, "/v1/operator/regulatory/submissions/" + submission + "/approval",
                "{\"contentSha256\":\"" + field(attestation.body(), "contentSha256") + "\",\"periodRevision\":" + revision.group(1)
                        + ",\"reason\":\"the return, reviewed\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(single("SELECT status FROM accounting.regulatory_submission WHERE id = '" + submission + "'")).isEqualTo("APPROVED");
    }

    @Test
    @Order(6)
    @DisplayName("accounting-break: an OPEN break planted in today's month, which the console's accountant proposes to acknowledge and"
            + " its controller acknowledges")
    void theConsoleAcknowledgesTheBreak() throws Exception {
        String subject = "e2e-seed:" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String raised = plantRows("accounting-break.sql", Map.of("month", value("CURRENT_MONTH", books) + "-01", "subject", subject))
                .get(0);
        assertThat(single("SELECT status || '|' || period_start FROM accounting.accounting_break WHERE id = '" + raised + "'"))
                .isEqualTo("OPEN|" + LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1));

        Answer proposed = post(elevated("bookkeeper"), "/v1/operator/accounting/breaks/" + raised + "/acknowledgement-proposal",
                "{\"disclosure\":\"a planted discrepancy, disclosed\"}");
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(200);
        Answer acknowledged = post(elevated("reviewer"), "/v1/operator/accounting/breaks/" + raised + "/acknowledgement-approval",
                "{\"reason\":\"the disclosure, reviewed\"}");
        assertThat(acknowledged.status()).as(acknowledged.body()).isEqualTo(200);
        assertThat(single("SELECT status FROM accounting.accounting_break WHERE id = '" + raised + "'")).isEqualTo("ACKNOWLEDGED");
    }

    // ------------------------------------------------------------------------------------------- the script's steps

    /** The books once the schedule's open leg has run - at the application's start, on its own thread. */
    private String openedBooks() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        String read = seed("books", "accountant");
        while (!read.contains("CLOSABLE_ID=") && Instant.now().isBefore(deadline)) {
            Thread.sleep(2000);
            read = seed("books", "accountant");
        }
        return read;
    }

    /** The close run of {@code periodId} once complete, read as the console's accountant reads it. */
    private String completedRun(String periodId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            Answer read = get(signedIn("bookkeeper"), "/v1/operator/accounting/periods/" + periodId + "/close-run");
            body = read.body();
            if (read.status() == 200 && body.contains("\"complete\":true")) {
                return body;
            }
            assertThat(body).as("no check of the run FAILED").doesNotContain("\"status\":\"FAILED\"");
            Thread.sleep(2000);
        }
        throw new AssertionError("the close run of " + periodId + " did not complete: " + body);
    }

    /** A plant run unchanged, its variables made literals as psql's {@code :'name'} makes them; each row's first column. */
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
            assertThat(first).as("%s returns its rows", file).isNotEmpty();
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
        assertThat(process.waitFor(15, TimeUnit.MINUTES)).as("Seed.java %s finished", String.join(" ", command)).isTrue();
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
                + "\",\"password\":\"" + people.getProperty(who + ".password") + "\"}");
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
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String single(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            return rows.getString(1);
        }
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
