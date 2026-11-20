package com.finapp.app.merchant;

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
import java.util.Base64;
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
 * The end-to-end seed's merchant scenarios and its dispute (`P17-TSK-115`), run as {@code infra/scripts/e2e-seed.sh} runs them: the
 * very {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application
 * over HTTP through the platform's own doors; the plants ({@code merchant-organisation.sql}, {@code payout-destination.sql},
 * {@code customer-verdict.sql}) read unchanged from the repository; and what the shell asks the database - a test identity's id, the
 * top-up's capture reference, the dispute the platform opened - asked here as the script asks it.
 *
 * <p><strong>The card PSP is the test harness's simulated provider</strong>, standing where kind's rehearsal simulator stands: it
 * tokenises the card, approves the authorization and the capture, approves a dispute answer, and answers the platform's dispute
 * inquiry with <em>the statement the seed wrote</em> - the file the shell copies into the simulator's {@code /tmp/disputes}, served
 * here verbatim - so the statement's words are proven against the card adapter that reads them. The signed hint is the seed's own,
 * under the payment webhook key the shell reads from the stack, written to the run's state as the setting's base64 text.
 *
 * <p>The payout destination's tokenisation is deliberately <em>not</em> configured, as on every test stack: the proposal door answers
 * 503, which is why the destination is planted; the console's administrator then approves it through the real door, and its
 * proposer cannot.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end seed's merchant scenarios and its dispute: the console's acts on what the seed made (P17-TSK-115)")
class E2eSeedMerchantDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");
    private static final byte[] WEBHOOK_KEY = "e2e-seed-webhook-key-0123456789ab".getBytes(StandardCharsets.UTF_8);

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private String onboarder;

    @DynamicPropertySource
    static void theCardPsp(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.webhook.key", () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    /** The script's merchant_desks, step for step; the console's own two people; the card PSP's ordinary answers. */
    @BeforeAll
    void theDesks() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        onboarder = idOf(registered("onboarder"));
        seed("grant", "admin", "MERCHANT_ADMINISTRATOR", onboarder);

        // The console's own people: an administrator and a ledger operator the seed made nothing with.
        seed("grant", "admin", "MERCHANT_ADMINISTRATOR", idOf(registered("approver")));
        seed("grant", "admin", "LEDGER_OPERATOR", idOf(registered("disputer")));

        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"tokenised\","
                + "\"token\":\"tok_{{jsonPath request.body '$.clientToken'}}\",\"brand\":\"Visa\",\"last4\":\"4242\","
                + "\"expiryMonth\":12,\"expiryYear\":2030}");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "psp_a");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.DISPUTE_RESPONSES_PATH, "psp_dr");
    }

    @AfterAll
    void stopTheProvider() {
        provider.close();
        provider = null;
    }

    @Test
    @Order(1)
    @DisplayName("onboardable-organisation: the planted organisation is KYB-verified to the onboarding door - the console's administrator"
            + " onboards it, ACTIVE")
    void theConsoleOnboardsTheOrganisation() throws Exception {
        String party = plantRows("merchant-organisation.sql", Map.of()).get(0);
        assertThat(single("SELECT p.kind || '|' || c.status FROM party.party p JOIN party.customer c ON c.party_id = p.id"
                + " WHERE p.id = '" + party + "'")).isEqualTo("ORGANISATION|ACTIVE");

        Answer onboarded = post(elevated("approver"), "/v1/operator/merchants", "{\"partyId\":\"" + party
                + "\",\"legalName\":\"Console Onboarded GmbH\",\"displayName\":\"Console Onboarded\",\"settlementCurrency\":\"EUR\"}");
        assertThat(onboarded.status()).as(onboarded.body()).isEqualTo(201);
        assertThat(single("SELECT status FROM merchant.merchant WHERE id = '" + field(onboarded.body(), "merchantId") + "'"))
                .isEqualTo("ACTIVE");
    }

    @Test
    @Order(2)
    @DisplayName("active-merchant: onboarded and keyed by the seed's administrator - the console sees the key and suspends the merchant")
    void theConsoleSuspendsTheMerchant() throws Exception {
        String[] made = merchant();
        String merchant = made[0];
        Answer keys = get(elevated("approver"), "/v1/operator/merchants/" + merchant + "/api-keys");
        assertThat(keys.status()).as(keys.body()).isEqualTo(200);
        assertThat(keys.body()).contains(made[1]);

        Answer suspended = post(elevated("approver"), "/v1/operator/merchants/" + merchant + "/suspension",
                "{\"reason\":\"e2e: the console's suspension\"}");
        assertThat(suspended.status()).as(suspended.body()).isBetween(200, 204);
        assertThat(single("SELECT status FROM merchant.merchant WHERE id = '" + merchant + "'")).isEqualTo("SUSPENDED");
    }

    @Test
    @Order(3)
    @DisplayName("proposed-destination: the proposal door is closed where no destination tokenisation is configured; the planted"
            + " proposal is refused to its proposer and approved by the console's administrator")
    void theConsoleApprovesTheDestination() throws Exception {
        String merchant = merchant()[0];
        Answer closed = post(elevated("onboarder"), "/v1/operator/merchants/" + merchant + "/payout-destinations",
                "{\"destinationToken\":\"pdg_" + letters(16) + "\",\"reason\":\"e2e: the door, unconfigured\"}");
        assertThat(closed.status()).as("why the destination is planted: " + closed.body()).isEqualTo(503);

        String reference = "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String destination = plantRows("payout-destination.sql", Map.of("merchant", merchant, "proposer", onboarder,
                "reference", reference)).get(0);
        assertThat(single("SELECT status || '|' || proposed_by FROM merchant.payout_destination WHERE id = '" + destination + "'"))
                .isEqualTo("PROPOSED|" + onboarder);

        String approval = "/v1/operator/merchants/" + merchant + "/payout-destinations/" + destination + "/approval";
        Answer ownApproval = post(elevated("onboarder"), approval, "{\"reason\":\"e2e: my own proposal\"}");
        assertThat(ownApproval.status()).as("its proposer cannot approve it: " + ownApproval.body()).isEqualTo(409);
        Answer approved = post(elevated("approver"), approval, "{\"reason\":\"e2e: the console's approval\"}");
        assertThat(approved.status()).as(approved.body()).isBetween(200, 204);
        assertThat(single("SELECT status || '|' || approved_by FROM merchant.payout_destination WHERE id = '" + destination + "'"))
                .isEqualTo("APPROVED|" + idOf(people().getProperty("approver.login")));
    }

    @Test
    @Order(4)
    @DisplayName("open-dispute: a card top-up by a planted-ACTIVE customer, the seed's statement and its signed hint - the platform's"
            + " inquiry opens the dispute CHARGED_BACK once, however often hinted, and the console's ledger operator represents it")
    void theConsoleRepresentsTheDispute() throws Exception {
        String payerLogin = value("LOGIN", seed("register-bare", "payer"));
        String payer = idOf(payerLogin);
        assertThat(plantRows("customer-verdict.sql", Map.of("identity", payer))).hasSize(1);
        assertThat(rowsOf("customer-verdict.sql", Map.of("identity", payer))).as("a second verdict changes nothing").isEmpty();

        String intent = value("PAYMENT_ID", seed("top-up", "payer"));
        String operation = single("SELECT capture_reference FROM payments.payment_attempt WHERE intent_id = '" + intent
                + "' AND status = 'CAPTURED'");
        assertThat(operation).as("the shape the script checks before its next SQL").matches("[A-Za-z0-9-]{1,64}");

        String dispute = "dp_e2e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        seed("dispute-statement", operation, dispute, "1250");
        // What the shell copies into the simulator's /tmp/disputes, served to the platform's inquiry verbatim.
        String statement = Files.readString(state.resolve("dispute-" + dispute + ".json"));
        provider.succeedsWith(SimulatedCardPspAdapter.DISPUTES_PATH + dispute, 200, statement);
        Files.writeString(state.resolve("provider-key"), Base64.getEncoder().encodeToString(WEBHOOK_KEY));

        seed("dispute-hint", operation, dispute);
        seed("dispute-hint", operation, dispute);
        List<String> opened = rows("SELECT id || '|' || stage || '|' || reason FROM payments.dispute"
                + " WHERE provider_dispute_reference = '" + dispute + "'");
        assertThat(opened).as("one dispute, however often hinted").hasSize(1);
        assertThat(opened.get(0)).endsWith("|CHARGED_BACK|FRAUD");
        String disputeId = opened.get(0).substring(0, opened.get(0).indexOf('|'));

        String disputer = elevated("disputer");
        Answer read = get(disputer, "/v1/operator/disputes/" + disputeId);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"stage\":\"CHARGED_BACK\"").contains(intent);
        Answer evidence = send("POST", disputer, "/v1/operator/disputes/" + disputeId + "/evidence", "{\"kind\":\"RECEIPT\","
                + "\"contentType\":\"PDF\",\"content\":\"" + Base64.getEncoder().encodeToString("e2e: the top-up's receipt"
                        .getBytes(StandardCharsets.UTF_8)) + "\",\"reason\":\"e2e: the customer's own top-up\"}", false);
        assertThat(evidence.status()).as(evidence.body()).isBetween(200, 201);
        Answer represented = post(disputer, "/v1/operator/disputes/" + disputeId + "/representment",
                "{\"reason\":\"e2e: the console's representment\"}");
        assertThat(represented.status()).as(represented.body()).isBetween(200, 202);
        assertThat(provider.requestCount(SimulatedCardPspAdapter.DISPUTE_RESPONSES_PATH)).as("dispatched to the PSP").isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------- the script's steps

    /** The script's merchant(): a planted organisation, onboarded and keyed by the seed's administrator. Its id and its key's id. */
    private String[] merchant() throws Exception {
        String party = plantRows("merchant-organisation.sql", Map.of()).get(0);
        String merchant = value("MERCHANT_ID", seed("onboard-merchant", "onboarder", party));
        String key = value("MERCHANT_KEY_ID", seed("issue-key", "onboarder", merchant));
        assertThat(single("SELECT status FROM merchant.merchant WHERE id = '" + merchant + "'")).isEqualTo("ACTIVE");
        return new String[] {merchant, key};
    }

    /** A plant run unchanged, its variables made literals as psql's {@code :'name'} makes them; each row's first column. */
    private static List<String> plantRows(String file, Map<String, String> variables) throws Exception {
        List<String> first = rowsOf(file, variables);
        assertThat(first).as("%s returns its rows", file).isNotEmpty();
        return first;
    }

    private static List<String> rowsOf(String file, Map<String, String> variables) throws Exception {
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

    private static String letters(int count) {
        StringBuilder out = new StringBuilder(count);
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < count; i++) {
            out.append((char) ('a' + random.nextInt(26)));
        }
        return out.toString();
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

    private Answer get(String session, String path) throws Exception {
        return send("GET", session, path, null, false);
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
