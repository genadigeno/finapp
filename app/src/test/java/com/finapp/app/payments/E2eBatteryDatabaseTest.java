package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
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
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The end-to-end battery's readings on a real PostgreSQL ({@code P17-TST-001}): every reading of
 * {@code infra/scripts/kind-rehearsal/battery/} run unchanged against the migrated schema, as the battery's psql runs it; and scenario 4
 * at the door with the seed's own payment - {@code e2e-seed.sh refundable-payment}'s steps, its customer made ACTIVE by the planted
 * verdict and its 1250.00 EUR top-up captured by the card PSP, then the console's two people's acts through the operator doors: the
 * refund proposed above the four-eyes threshold, approved by a financial controller, the approval asked again under its key - the answer
 * the proxy drops and the retry that must replay it - and the reading of the records holding one refund, COMPLETED, whole.
 *
 * <p><strong>The card PSP is the test harness's simulated provider</strong>, standing where kind's rehearsal simulator stands, as in
 * {@code E2eSeedMerchantDatabaseTest}. The browser, the edge, the proxy and the three replicas are the owed run's; what is held here is
 * that the battery's subject is real and its reading names real columns.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end battery's readings run on the schema, and scenario 4's refund is one, whole and replayed (P17-TST-001)")
class E2eBatteryDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z_]+)'");
    private static final String READINGS = "infra/scripts/kind-rehearsal/battery/";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private String refunder;
    private String controller;

    @DynamicPropertySource
    static void theCardPsp(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
    }

    /** The run's administrator, as the script makes it; the battery's refunder and controller, granted through the door. */
    @BeforeAll
    void thePeople() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        refunder = idOf(registered("refunder"));
        seed("grant", "admin", "LEDGER_OPERATOR", refunder);
        controller = idOf(registered("controller"));
        seed("grant", "admin", "FINANCIAL_CONTROLLER", controller);

        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"tokenised\","
                + "\"token\":\"tok_{{jsonPath request.body '$.clientToken'}}\",\"brand\":\"Visa\",\"last4\":\"4242\","
                + "\"expiryMonth\":12,\"expiryYear\":2030}");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "psp_a");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
    }

    @AfterAll
    void stopTheProvider() {
        provider.close();
        provider = null;
    }

    @Test
    @Order(1)
    @DisplayName("every reading runs unchanged on the migrated schema, one verdict a statement in the census's form; a reading of subjects"
            + " that do not exist is never a pass")
    void everyReadingRunsOnTheSchema() throws Exception {
        Map<String, String> nobody = Map.ofEntries(Map.entry("since", Instant.now().minusSeconds(60).toString()),
                Map.entry("viewer", uuid()), Map.entry("revoked", uuid()), Map.entry("admin", uuid()), Map.entry("case", uuid()),
                Map.entry("analysta", uuid()), Map.entry("analystb", uuid()), Map.entry("payment", uuid()),
                Map.entry("refunder", uuid()), Map.entry("controller", uuid()), Map.entry("amount_minor", "125000"),
                Map.entry("break", uuid()), Map.entry("reconcilera", uuid()), Map.entry("reconcilerb", uuid()),
                Map.entry("park", uuid()), Map.entry("eventsa", uuid()), Map.entry("eventsb", uuid()));
        List<String> files;
        try (var listed = Files.list(repositoryRoot().resolve(READINGS))) {
            files = listed.map(file -> file.getFileName().toString()).sorted().toList();
        }
        assertThat(files).containsExactly("s1-unauthorised.sql", "s2-revoked.sql", "s3-conflict.sql", "s4-refund.sql", "s5-stale.sql",
                "s6-duplicate.sql");
        for (String file : files) {
            List<String[]> verdicts = reading(file, nobody);
            assertThat(verdicts).as(file).allSatisfy(verdict -> {
                assertThat(verdict).as("%s: a verdict, a check, a detail", file).hasSize(3);
                assertThat(verdict[0]).isIn("PASS", "FAIL");
            });
            assertThat(verdicts).as("%s over nobody: never a pass", file).anyMatch(verdict -> verdict[0].equals("FAIL"));
        }
    }

    @Test
    @Order(2)
    @DisplayName("scenario 4: the seed's 1250.00 EUR top-up, refunded by the console's two people - proposed above the threshold,"
            + " approved, the approval asked again under its key and answered the same - is one refund, whole and COMPLETED, its"
            + " proposal the refunder's approved by the controller, the wallet back at nothing: s4-refund.sql passes")
    void scenarioFourIsOneRefund() throws Exception {
        String refundeeLogin = value("LOGIN", seed("register-bare", "refundee"));
        String refundee = idOf(refundeeLogin);
        assertThat(plantRows("customer-verdict.sql", Map.of("identity", refundee))).hasSize(1);
        String payment = value("PAYMENT_ID", seed("top-up", "refundee", "1250.00"));
        assertThat(single("SELECT status || '|' || amount_minor FROM payments.payment_intent WHERE id = '" + payment + "'"))
                .isEqualTo("SUCCEEDED|125000");
        String wallet = value("ACCOUNT_ID", seed("account", "refundee"));
        assertThat(balance(wallet)).as("the top-up in the wallet").contains("\"settled\":\"1250.00\"");

        Answer proposed = post(elevated("refunder"), "/v1/payments/" + payment + "/refund",
                "{\"amount\":\"1250.00\",\"currency\":\"EUR\",\"reason\":\"e2e battery: scenario 4\"}", UUID.randomUUID().toString());
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        assertThat(field(proposed.body(), "status")).as("above the four-eyes threshold").isEqualTo("PROPOSED");
        String proposal = field(proposed.body(), "id");

        provider.succeedsWith(SimulatedCardPspAdapter.REFUNDS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_bt-rfd-" + UUID.randomUUID().toString().substring(0, 8) + "\"}");
        String key = UUID.randomUUID().toString();
        String approval = "/v1/operator/refund-proposals/" + proposal + "/approval";
        String session = elevated("controller");
        Answer approved = post(session, approval, "{\"reason\":\"e2e battery: the controller agrees\"}", key);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("COMPLETED");
        Answer retried = post(session, approval, "{\"reason\":\"e2e battery: the controller agrees\"}", key);
        assertThat(retried.status()).as("the answer the proxy drops, asked again: the original outcome").isEqualTo(approved.status());
        assertThat(retried.body()).isEqualTo(approved.body());
        assertThat(provider.requestCount(SimulatedCardPspAdapter.REFUNDS_PATH)).as("sent to the PSP once").isEqualTo(1);

        List<String[]> verdicts = reading("s4-refund.sql", Map.of("payment", payment, "refunder", refunder, "controller", controller,
                "amount_minor", "125000"));
        assertThat(verdicts).hasSize(3).allSatisfy(verdict -> assertThat(verdict[0]).as(String.join("|", verdict)).isEqualTo("PASS"));
        assertThat(balance(wallet)).as("what the census counts: the top-up in, the refund out")
                .contains("\"settled\":\"0.00\"").contains("\"holds\":\"0.00\"");
    }

    // ------------------------------------------------------------------------------------------- the battery's psql, here

    /** A reading run unchanged, its variables made literals as psql's {@code :'name'} makes them; each statement's one row. */
    private static List<String[]> reading(String file, Map<String, String> variables) throws Exception {
        String text = Files.readString(repositoryRoot().resolve(READINGS + file), StandardCharsets.UTF_8).replaceAll("(?m)--.*$", "");
        Matcher variable = VARIABLE.matcher(text);
        StringBuilder sql = new StringBuilder();
        while (variable.find()) {
            String value = variables.get(variable.group(1));
            assertThat(value).as("the battery hands %s :'%s'", file, variable.group(1)).isNotNull();
            variable.appendReplacement(sql, Matcher.quoteReplacement("'" + value.replace("'", "''") + "'"));
        }
        variable.appendTail(sql);
        List<String[]> verdicts = new ArrayList<>();
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            for (String one : sql.toString().split(";\\s*(\\n|$)")) {
                if (one.isBlank()) {
                    continue;
                }
                try (ResultSet rows = statement.executeQuery(one)) {
                    assertThat(rows.next()).as("%s: a verdict from %s", file, one).isTrue();
                    int columns = rows.getMetaData().getColumnCount();
                    String[] verdict = new String[columns];
                    for (int i = 0; i < columns; i++) {
                        verdict[i] = rows.getString(i + 1);
                    }
                    assertThat(rows.next()).as("%s: one verdict a statement", file).isFalse();
                    verdicts.add(verdict);
                }
            }
        }
        assertThat(verdicts).as("%s: as many verdicts as checks", file)
                .hasSize((int) text.lines().filter(line -> line.startsWith("SELECT CASE")).count());
        return verdicts;
    }

    // ------------------------------------------------------------------------------------------- the seed's steps

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
                repositoryRoot().resolve("infra/scripts/e2e-seed/Seed.java").toString(), base(), base(), state.toString()));
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

    // ------------------------------------------------------------------------------------------- the console's two people

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
                + "\",\"password\":\"" + people.getProperty(who + ".password") + "\"}", null);
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
                "{\"code\":\"" + totp(people.getProperty(who + ".totp"), step) + "\"}", null);
        assertThat(stepped.status()).as(stepped.body()).isEqualTo(200);
        people.setProperty(who + ".step", Long.toString(step));
        try (var out = Files.newOutputStream(state.resolve("people.properties"))) {
            people.store(out, "this run's test identities");
        }
        return field(stepped.body(), "sessionToken");
    }

    /** The wallet's balance as its holder reads it. */
    private String balance(String account) throws Exception {
        Answer read = send("GET", signedIn("refundee"), "/v1/me/accounts/" + account + "/balance", null, null);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        return read.body();
    }

    private Answer post(String session, String path, String body, String key) throws Exception {
        return send("POST", session, path, body, key);
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

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String single(String sql) throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for %s", sql).isTrue();
            String value = rows.getString(1);
            assertThat(rows.next()).as("one row for %s", sql).isFalse();
            return value;
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
