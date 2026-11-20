package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.app.crossborder.CorridorPolicyV1;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
 * The end-to-end seed's FX scenarios (`P17-TSK-119`), run as {@code infra/scripts/e2e-seed.sh} runs them: the very
 * {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application over
 * HTTP through the platform's own doors; the verdict plant ({@code customer-verdict.sql}) read unchanged from the repository; and
 * what the shell asks the database - a test identity's id, whether a version is ACTIVE, the version or enable request left pending -
 * asked here as the script asks it.
 *
 * <p><strong>The booked trade is kind's</strong>: it needs an FX provider and a reference feed, which only the kind rehearsal's FX
 * scopes configure (its simulator behind both). Here they are this build's own test doubles - {@link SimulatedFxEngine} as the
 * provider, the reference rates recorded fresh as the platform's fetch would ({@link FxTestClient#freshReferences()}) - and the card
 * PSP that funds the customer is {@link SimulatedProvider}, as {@code E2eSeedMerchantDatabaseTest} has it. No corridor rail or
 * verification provider is configured, as on every test stack, which is why no cross-border payment, and so no trace, is seeded.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end seed's FX scenarios: the console's acts on what the seed made (P17-TSK-119)")
class E2eSeedFxDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");
    private static final byte[] WEBHOOK_KEY = "e2e-seed-webhook-key-0123456789ab".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static SimulatedFxEngine engine;
    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private String controller;

    /** Kind's FX scopes' provider, and its card PSP: this build's doubles of both. */
    @DynamicPropertySource
    static void theProviders(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(engine, Map.of());
        }
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.fx.provider.url", () -> engine.baseUrl().toString());
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.webhook.key", () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    /** The script's fx_desks, step for step; the console's own two people; the card PSP's ordinary answers. */
    @BeforeAll
    void theDesks() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        controller = idOf(registered("fxcontroller"));
        seed("grant", "admin", "FX_CONTROLLER", controller, idOf(registered("fxapprover")));
        inForce("fx-pricing", "fx.pricing_policy_version");
        inForce("corridor-policy", "crossborder.corridor_policy_version");

        // The console's own people: an FX controller and a reconciliation operator the seed made nothing with.
        seed("grant", "admin", "FX_CONTROLLER", idOf(registered("fxconsole")));
        seed("grant", "admin", "RECONCILIATION_OPERATOR", idOf(registered("investigator")));

        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"tokenised\","
                + "\"token\":\"tok_{{jsonPath request.body '$.clientToken'}}\",\"brand\":\"Visa\",\"last4\":\"4242\","
                + "\"expiryMonth\":12,\"expiryYear\":2030}");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "psp_a");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
    }

    @AfterAll
    void stopTheProviders() {
        engine.close();
        engine = null;
        provider.close();
        provider = null;
    }

    @Test
    @Order(1)
    @DisplayName("the desks: one pricing policy and one corridor policy ACTIVE, and neither left PROPOSED")
    void theDesksLeaveAVersionInForce() throws Exception {
        assertThat(single("SELECT count(*) FROM fx.pricing_policy_version WHERE status = 'ACTIVE'")).isEqualTo("1");
        assertThat(single("SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'")).isEqualTo("1");
        assertThat(rows("SELECT id FROM fx.pricing_policy_version WHERE status = 'PROPOSED'")).isEmpty();
        assertThat(rows("SELECT id FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'")).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("proposed-fx-policy: the runbook's version 1 PROPOSED by the seed's controller - a second proposal refused while it pends,"
            + " a re-run withdrawing the run before's; refused to its proposer, approved by the console's, retiring the version in force")
    void theConsoleApprovesTheFxPolicy() throws Exception {
        String proposed = proposedTwice("fx-pricing", "fx.pricing_policy_version", "fx.ProposalPending");
        String inForce = single("SELECT id FROM fx.pricing_policy_version WHERE status = 'ACTIVE'");

        Answer own = post(elevated("fxcontroller"), "/v1/operator/fx/pricing-policies/" + proposed + "/approval",
                "{\"reason\":\"e2e: its proposer\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(409);
        assertThat(own.body()).contains("fx.SelfApprovalRefused");
        String console = elevated("fxconsole");
        Answer approved = post(console, "/v1/operator/fx/pricing-policies/" + proposed + "/approval",
                "{\"reason\":\"e2e: the console's FX controller approves\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(field(approved.body(), "retiredId")).isEqualTo(inForce);

        // The seed's version is the runbook's version 1, pair for pair: the console's controller proposes the runbook's own body, the
        // list door shows both, and it withdraws its own.
        Answer reference = post(console, "/v1/operator/fx/pricing-policies", PricingPolicyV1.json("e2e: the runbook's version 1"));
        assertThat(reference.status()).as(reference.body()).isEqualTo(201);
        String referenceId = field(reference.body(), "id");
        JsonNode versions = JSON.readTree(send("GET", console, "/v1/operator/fx/pricing-policies", null, false).body()).get("items");
        assertThat(terms(versions, proposed, "pairs")).hasSize(40).isEqualTo(terms(versions, referenceId, "pairs"));
        Answer withdrawn = post(console, "/v1/operator/fx/pricing-policies/" + referenceId + "/rejection",
                "{\"reason\":\"e2e: compared, withdrawn\"}");
        assertThat(withdrawn.status()).as(withdrawn.body()).isEqualTo(200);
    }

    @Test
    @Order(3)
    @DisplayName("proposed-corridor-policy: the runbook's version 1 PROPOSED by the seed's controller - refused to its proposer,"
            + " approved by the console's, retiring the version in force")
    void theConsoleApprovesTheCorridorPolicy() throws Exception {
        String proposed = proposedTwice("corridor-policy", "crossborder.corridor_policy_version", "crossborder.ProposalPending");
        String inForce = single("SELECT id FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'");

        Answer own = post(elevated("fxcontroller"), "/v1/operator/cross-border/corridor-policies/" + proposed + "/approval",
                "{\"reason\":\"e2e: its proposer\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(409);
        assertThat(own.body()).contains("crossborder.SelfApprovalRefused");
        String console = elevated("fxconsole");
        Answer approved = post(console, "/v1/operator/cross-border/corridor-policies/" + proposed + "/approval",
                "{\"reason\":\"e2e: the console's FX controller approves\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(field(approved.body(), "retiredId")).isEqualTo(inForce);

        Answer reference = post(console, "/v1/operator/cross-border/corridor-policies", CorridorPolicyV1.json("e2e: the runbook's version 1"));
        assertThat(reference.status()).as(reference.body()).isEqualTo(201);
        String referenceId = field(reference.body(), "id");
        JsonNode versions = JSON.readTree(send("GET", console, "/v1/operator/cross-border/corridor-policies", null, false).body())
                .get("versions");
        assertThat(terms(versions, proposed, "corridors")).hasSize(4).isEqualTo(terms(versions, referenceId, "corridors"));
        Answer withdrawn = post(console, "/v1/operator/cross-border/corridor-policies/" + referenceId + "/rejection",
                "{\"reason\":\"e2e: compared, withdrawn\"}");
        assertThat(withdrawn.status()).as(withdrawn.body()).isEqualTo(200);
    }

    @Test
    @Order(4)
    @DisplayName("fx-enable-request: BHD-JPY disabled and its enabling PROPOSED - a re-run withdrawing the run before's, its disabling"
            + " UNCHANGED; refused to its proposer, approved by the console's, the pair available again")
    void theConsoleEnablesThePair() throws Exception {
        String pending = "SELECT id FROM fx.availability_enable_request WHERE subject_kind = 'PAIR' AND subject = 'BHD-JPY'"
                + " AND status = 'PROPOSED'";
        String newest = "SELECT available FROM fx.pair_availability WHERE source_currency = 'BHD' AND destination_currency = 'JPY'"
                + " ORDER BY seq DESC LIMIT 1";
        String earlier = enableRequested("fx-pair", "BHD-JPY", pending);
        assertThat(single(newest)).as("disabled").isEqualTo("f");
        String requested = enableRequested("fx-pair", "BHD-JPY", pending);
        assertThat(lastOutcome).as("a re-run finds it disabled already").isEqualTo("UNCHANGED");
        assertThat(single("SELECT status FROM fx.availability_enable_request WHERE id = '" + earlier + "'")).isEqualTo("REJECTED");
        assertThat(single("SELECT status || '|' || proposed_by FROM fx.availability_enable_request WHERE id = '" + requested + "'"))
                .isEqualTo("PROPOSED|" + controller);

        Answer own = post(elevated("fxcontroller"), "/v1/operator/fx/enable-requests/" + requested + "/approval",
                "{\"reason\":\"e2e: its proposer\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(409);
        assertThat(own.body()).contains("fx.SelfApprovalRefused");
        Answer approved = post(elevated("fxconsole"), "/v1/operator/fx/enable-requests/" + requested + "/approval",
                "{\"reason\":\"e2e: the console's FX controller approves\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(single(newest)).as("available again").isEqualTo("t");
    }

    @Test
    @Order(5)
    @DisplayName("corridor-enable-request: GBP-USD-US disabled and its enabling PROPOSED - refused to its proposer, approved by the"
            + " console's, the corridor available again")
    void theConsoleEnablesTheCorridor() throws Exception {
        String pending = "SELECT id FROM crossborder.corridor_enable_request WHERE corridor = 'GBP-USD-US' AND status = 'PROPOSED'";
        String newest = "SELECT available FROM crossborder.corridor_availability WHERE corridor = 'GBP-USD-US' ORDER BY seq DESC LIMIT 1";
        String requested = enableRequested("corridor", "GBP-USD-US", pending);
        assertThat(single(newest)).as("disabled").isEqualTo("f");
        assertThat(single("SELECT status || '|' || proposed_by FROM crossborder.corridor_enable_request WHERE id = '" + requested + "'"))
                .isEqualTo("PROPOSED|" + controller);

        Answer own = post(elevated("fxcontroller"), "/v1/operator/cross-border/corridor-enable-requests/" + requested + "/approval",
                "{\"reason\":\"e2e: its proposer\"}");
        assertThat(own.status()).as(own.body()).isEqualTo(409);
        assertThat(own.body()).contains("crossborder.SelfApprovalRefused");
        Answer approved = post(elevated("fxconsole"), "/v1/operator/cross-border/corridor-enable-requests/" + requested + "/approval",
                "{\"reason\":\"e2e: the console's FX controller approves\"}");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(single(newest)).as("available again").isEqualTo("t");
    }

    @Test
    @Order(6)
    @DisplayName("booked-trade: a planted-ACTIVE customer funded by a card top-up converts 10.00 EUR to USD at the provider's quote - the"
            + " trade BOOKED under the policy in force; the console's investigator reads its provenance, its FX controller may not")
    void theInvestigatorReadsTheTradesProvenance() throws Exception {
        String login = value("LOGIN", seed("register-bare", "trader"));
        assertThat(login).as("the shape the script checks before SQL").matches("e2e-seed\\.[a-z]+\\.[0-9a-f]{12}");
        String trader = idOf(login);
        assertThat(plantRows("customer-verdict.sql", Map.of("identity", trader))).hasSize(1);
        String funded = value("PAYMENT_ID", seed("top-up", "trader"));
        assertThat(funded).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        // What kind's reference feed does there: the rates the platform fetched, within version 1's 120 seconds.
        FxTestClient.freshReferences();
        String trade = value("FX_TRADE_ID", seed("fx-trade", "trader"));
        assertThat(trade).as("the shape the script checks").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        String inForce = single("SELECT id FROM fx.pricing_policy_version WHERE status = 'ACTIVE'");
        assertThat(single("SELECT status || '|' || purpose || '|' || source_currency || '|' || destination_currency || '|' || fixed_side"
                + " || '|' || customer_source_minor || '|' || pricing_policy_version_id FROM fx.trade WHERE id = '" + trade + "'"))
                .as("10.00 EUR by its source side, priced under the version in force")
                .isEqualTo("BOOKED|CONVERSION|EUR|USD|FIXED_SOURCE|1000|" + inForce);

        Answer provenance = send("GET", signedIn("investigator"), "/v1/operator/fx/trades/" + trade + "/provenance", null, false);
        assertThat(provenance.status()).as(provenance.body()).isEqualTo(200);
        assertThat(field(provenance.body(), "tradeId")).isEqualTo(trade);
        assertThat(field(provenance.body(), "status")).isEqualTo("BOOKED");
        assertThat(field(provenance.body(), "pricingPolicyVersionId")).isEqualTo(inForce);
        Answer refused = send("GET", signedIn("fxconsole"), "/v1/operator/fx/trades/" + trade + "/provenance", null, false);
        assertThat(refused.status()).as("an FX controller administers prices, it does not investigate trades").isEqualTo(403);
    }

    // ------------------------------------------------------------------------------------------- the script's FX steps, as it takes them

    private String lastOutcome;

    /** The script's in_force: when no version is ACTIVE, the runbook's version 1 proposed by one controller, approved by the other. */
    private void inForce(String kind, String table) throws Exception {
        if (!"0".equals(single("SELECT count(*) FROM " + table + " WHERE status = 'ACTIVE'"))) {
            return;
        }
        withdrawVersion(kind, table);
        String proposed = value("VERSION_ID", seed("propose-version", "fxcontroller", kind, "seed"));
        seed("decide-version", "fxapprover", kind, proposed, "approval");
    }

    /** The script's withdraw_version: the one version left PROPOSED, rejected through the door by the seed's controller. */
    private void withdrawVersion(String kind, String table) throws Exception {
        for (String left : rows("SELECT id FROM " + table + " WHERE status = 'PROPOSED'")) {
            seed("decide-version", "fxcontroller", kind, left, "rejection");
        }
    }

    /**
     * The proposal scenario run, a second proposal refused by the door while it pends, and the scenario run again on the same stack,
     * which withdraws the first: returns the second run's version, PROPOSED by the seed's controller.
     */
    private String proposedTwice(String kind, String table, String pendingCode) throws Exception {
        withdrawVersion(kind, table);
        String earlier = value("VERSION_ID", seed("propose-version", "fxcontroller", kind, "proposal"));
        Run second = run("propose-version", "fxcontroller", kind, "proposal");
        assertThat(second.exit()).as("one PROPOSED at a time, platform-wide").isNotZero();
        assertThat(second.error()).contains(pendingCode);
        withdrawVersion(kind, table);
        String proposed = value("VERSION_ID", seed("propose-version", "fxcontroller", kind, "proposal"));
        assertThat(single("SELECT status FROM " + table + " WHERE id = '" + earlier + "'")).isEqualTo("REJECTED");
        assertThat(single("SELECT status || '|' || proposed_by FROM " + table + " WHERE id = '" + proposed + "'"))
                .isEqualTo("PROPOSED|" + controller);
        return proposed;
    }

    /** The script's enable_requested: the pending request withdrawn by the rejection door, the subject disabled, its enabling proposed. */
    private String enableRequested(String kind, String subject, String pendingQuery) throws Exception {
        for (String left : rows(pendingQuery)) {
            seed("decide-enable", "fxcontroller", kind, left, "rejection");
        }
        lastOutcome = value("OUTCOME", seed("disable", "fxcontroller", kind, subject));
        String requested = value("REQUEST_ID", seed("propose-enable", "fxcontroller", kind, subject));
        assertThat(requested).as("the shape the script checks").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        return requested;
    }

    /** A version's pairs or corridors as the list door shows them, as a set: their order is the store's, their terms the version's. */
    private static Set<JsonNode> terms(JsonNode versions, String id, String name) {
        for (JsonNode version : versions) {
            if (id.equals(version.get("id").asText())) {
                Set<JsonNode> held = new HashSet<>();
                version.get(name).forEach(held::add);
                return held;
            }
        }
        throw new AssertionError("version " + id + " is not on the list's first page");
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
