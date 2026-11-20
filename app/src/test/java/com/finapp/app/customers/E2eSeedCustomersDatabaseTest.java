package com.finapp.app.customers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The end-to-end seed's customer history (`P17-TSK-121`), run as {@code infra/scripts/e2e-seed.sh} runs it: the very
 * {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application over
 * HTTP through the platform's own doors - the registrations, the consent granted, withdrawn and granted again, the wallets, the
 * top-up and the transfers; the verdict plant ({@code customer-verdict.sql}) read unchanged from the repository and run as the
 * shell's psql runs it; and what the shell then asks the database, asked here as the script asks it.
 *
 * <p>Then the console's side, through the doors it reads with (`P17-TSK-026`, `P17-TSK-027`): its CUSTOMER_SUPPORT_AGENT, stepped
 * up, reads each customer's profile, consent history, accounts, ledger lines and transfers. <strong>The funded history is
 * kind's</strong>: its money is a card top-up, and only the kind rehearsal has a card PSP; here the PSP is {@link SimulatedProvider},
 * as {@code E2eSeedFxDatabaseTest} has it. No risk policy is active, so every transfer is judged under the platform's fail-safe
 * ALLOW, as on a fresh test stack.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the end-to-end seed's customer history: what the console reads of the customers the seed made (P17-TSK-121)")
class E2eSeedCustomersDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");
    private static final byte[] WEBHOOK_KEY = "e2e-seed-webhook-key-0123456789ab".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @TempDir static Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** Kind's card PSP and tokenisation provider: this build's double of both. */
    @DynamicPropertySource
    static void theCardPsp(DynamicPropertyRegistry registry) throws Exception {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.webhook.key", () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    /** The run's administrator, as the script's administrator() makes it; the console's support agent, granted through the door. */
    @BeforeAll
    void theConsolesSupportAgent() throws Exception {
        String admin = registered("admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        seed("grant", "admin", "CUSTOMER_SUPPORT_AGENT", idOf(registered("support")));
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
    @DisplayName("customer-history: a fresh customer made ACTIVE by its planted verdict; KYC_PROCESSING granted, withdrawn and granted"
            + " again through the consent doors; its EUR wallet through the accounts door; 100.00 EUR from the unfunded wallet judged"
            + " FAILED for INSUFFICIENT_FUNDS, nothing posted - and the console's support agent reads the profile, the three facts newest"
            + " first, the account at zero and the failed transfer; an administrator, who holds no CUSTOMER_READ, is refused")
    void theConsoleReadsAnUnfundedCustomersHistory() throws Exception {
        Holder holder = holder("holder");
        String account = value("ACCOUNT_ID", seed("wallet", "holder"));
        assertThat(account).as("the shape the script checks").matches("[0-9a-f-]{36}");
        String destination = payeeWallet("payee");
        String made = seed("transfer", "holder", destination, "100.00");
        assertThat(value("TRANSFER_STATUS", made)).isEqualTo("FAILED");
        assertThat(value("TRANSFER_FAILURE", made)).isEqualTo("INSUFFICIENT_FUNDS");
        String transfer = value("TRANSFER_ID", made);
        assertThat(single("SELECT status || '|' || failure_reason || '|' || (journal_entry_id IS NULL) || '|' || amount_minor || '|'"
                + " || currency FROM transfers.transfer WHERE id = '" + transfer + "'"))
                .as("judged and recorded, nothing posted").isEqualTo("FAILED|INSUFFICIENT_FUNDS|true|10000|EUR");

        String console = elevated("support");
        JsonNode profile = read(console, "/v1/operator/customers/" + holder.customer());
        assertThat(profile.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(profile.path("partyId").asText()).isEqualTo(holder.party());

        JsonNode consents = read(console, "/v1/operator/parties/" + holder.party() + "/consents").path("items");
        assertThat(consents).as("three facts, never an update").hasSize(3);
        List<String> facts = new ArrayList<>();
        consents.forEach(fact -> facts.add(fact.path("purpose").asText() + "|" + fact.path("action").asText() + "|"
                + fact.path("textVersion").asInt()));
        assertThat(facts).as("newest first").containsExactly("KYC_PROCESSING|GRANT|1", "KYC_PROCESSING|WITHDRAWAL|1",
                "KYC_PROCESSING|GRANT|1");

        JsonNode agreements = read(console, "/v1/operator/customers/" + holder.customer() + "/accounts").path("items");
        assertThat(agreements).hasSize(1);
        assertThat(agreements.get(0).path("accountId").asText()).isEqualTo(account);
        assertThat(agreements.get(0).path("productType").asText()).isEqualTo("WALLET");
        assertThat(balances(read(console, "/v1/operator/accounts/" + account))).as("nothing ever entered it")
                .containsExactly("EUR|0.00|0.00|0.00");
        assertThat(read(console, "/v1/operator/accounts/" + account + "/entries?currency=EUR").path("items")).as("no line").isEmpty();

        JsonNode transfers = read(console, "/v1/operator/transfers?customerId=" + holder.customer() + window()).path("items");
        assertThat(transfers).hasSize(1);
        assertThat(transfers.get(0).path("transferId").asText()).isEqualTo(transfer);
        assertThat(transfers.get(0).path("status").asText()).isEqualTo("FAILED");
        JsonNode detail = read(console, "/v1/operator/transfers/" + transfer);
        assertThat(detail.path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("amount").asText() + " " + detail.path("currency").asText()).isEqualTo("100.00 EUR");
        assertThat(detail.path("sourceAccountId").asText()).as("the holder's EUR wallet in the ledger").isEqualTo(wallet(account));
        assertThat(detail.path("destinationAccountId").asText()).as("the payee's").isEqualTo(wallet(destination));

        Answer refused = send("GET", elevated("admin"), "/v1/operator/customers/" + holder.customer(), null);
        assertThat(refused.status()).as("an administrator is no support agent: %s", refused.body()).isEqualTo(403);
    }

    @Test
    @Order(2)
    @DisplayName("posted-transfers: a fresh customer with the same consent history, funded by a 12.50 EUR card top-up, transfers 2.00"
            + " and 3.00 EUR to another fresh customer's wallet, each COMPLETED with its journal entry - and the console reads both,"
            + " the wallet's three lines and its 7.50 EUR left, and the payee's 5.00 EUR")
    void theConsoleReadsAFundedCustomersTransfers() throws Exception {
        Holder sender = holder("sender");
        String funded = value("PAYMENT_ID", seed("top-up", "sender"));
        assertThat(single("SELECT status FROM payments.payment_intent WHERE id = '" + funded + "'")).isEqualTo("SUCCEEDED");
        String account = value("ACCOUNT_ID", seed("account", "sender"));
        String destination = payeeWallet("recipient");
        List<String> posted = new ArrayList<>();
        for (String amount : List.of("2.00", "3.00")) {
            String made = seed("transfer", "sender", destination, amount);
            assertThat(value("TRANSFER_STATUS", made)).as("%s EUR", amount).isEqualTo("COMPLETED");
            assertThat(value("TRANSFER_FAILURE", made)).isEqualTo("NONE");
            posted.add(value("TRANSFER_ID", made));
        }
        for (String transfer : posted) {
            assertThat(single("SELECT status || '|' || (journal_entry_id IS NOT NULL) FROM transfers.transfer WHERE id = '" + transfer
                    + "'")).as("posted").isEqualTo("COMPLETED|true");
        }

        String console = elevated("support");
        JsonNode agreements = read(console, "/v1/operator/customers/" + sender.customer() + "/accounts").path("items");
        assertThat(agreements).hasSize(1);
        assertThat(agreements.get(0).path("accountId").asText()).as("the wallet the top-up opened").isEqualTo(account);
        assertThat(balances(read(console, "/v1/operator/accounts/" + account))).as("12.50 in, 5.00 out")
                .containsExactly("EUR|7.50|0.00|7.50");
        List<String> lines = new ArrayList<>();
        read(console, "/v1/operator/accounts/" + account + "/entries?currency=EUR").path("items")
                .forEach(line -> lines.add(line.path("direction").asText() + "|" + line.path("amount").asText()));
        assertThat(lines).as("the top-up's credit and each transfer's debit").containsExactlyInAnyOrder("CREDIT|12.50", "DEBIT|2.00",
                "DEBIT|3.00");

        List<String> transfers = new ArrayList<>();
        read(console, "/v1/operator/transfers?customerId=" + sender.customer() + window()).path("items")
                .forEach(row -> transfers.add(row.path("transferId").asText() + "|" + row.path("status").asText() + "|"
                        + row.path("amount").asText()));
        assertThat(transfers).containsExactlyInAnyOrder(posted.get(0) + "|COMPLETED|2.00", posted.get(1) + "|COMPLETED|3.00");
        for (String transfer : posted) {
            JsonNode detail = read(console, "/v1/operator/transfers/" + transfer);
            assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(detail.path("sourceAccountId").asText()).isEqualTo(wallet(account));
            assertThat(detail.path("destinationAccountId").asText()).isEqualTo(wallet(destination));
        }
        assertThat(balances(read(console, "/v1/operator/accounts/" + destination))).as("what the payee received")
                .containsExactly("EUR|5.00|0.00|5.00");
    }

    // ---------------------------------------------------------------------------------------------------- the script's steps

    private record Holder(String identity, String customer, String party) {}

    /** The script's history_holder, step for step: registered bare, its verdict planted, its consent's three facts, its party read. */
    private Holder holder(String who) throws Exception {
        String identity = idOf(value("LOGIN", seed("register-bare", who)));
        String customer = verdict(identity);
        assertThat(value("CONSENT_HISTORY", seed("consent-history", who))).isEqualTo("GRANTED,WITHDRAWN,GRANTED");
        String party = single("SELECT party_id FROM party.customer WHERE id = '" + customer + "'");
        return new Holder(identity, customer, party);
    }

    /** The script's payee_wallet: a fresh ACTIVE customer's EUR wallet, opened through the door. */
    private String payeeWallet(String who) throws Exception {
        verdict(idOf(value("LOGIN", seed("register-bare", who))));
        return value("ACCOUNT_ID", seed("wallet", who));
    }

    private static String verdict(String identity) throws Exception {
        List<String> planted = plantRows("customer-verdict.sql", Map.of("identity", identity));
        assertThat(planted).as("one PENDING customer made ACTIVE").hasSize(1);
        return planted.get(0);
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

    /** One of the console's reads, answered 200, parsed. */
    private JsonNode read(String session, String path) throws Exception {
        Answer answer = send("GET", session, path, null);
        assertThat(answer.status()).as("GET %s: %s", path, answer.body()).isEqualTo(200);
        return JSON.readTree(answer.body());
    }

    /** An account read's balances, as "currency|posted|held|available". */
    private static List<String> balances(JsonNode account) {
        List<String> read = new ArrayList<>();
        account.path("balances").forEach(balance -> read.add(balance.path("currency").asText() + "|" + balance.path("posted").asText()
                + "|" + balance.path("held").asText() + "|" + balance.path("available").asText()));
        return read;
    }

    /**
     * An agreement's EUR wallet in the ledger: a transfer names the ledger accounts it moves between, which the customer's door
     * resolved from the agreements it was given (one per owner, purpose and currency).
     */
    private static String wallet(String agreement) throws SQLException {
        return single("SELECT id FROM ledger.ledger_account WHERE owner_ref = '" + agreement
                + "' AND purpose = 'CUSTOMER_WALLET' AND currency = 'EUR'");
    }

    /** The transfer list's required window, a day either side of now - the seed's transfers were all initiated inside it. */
    private static String window() {
        Instant now = Instant.now();
        return "&from=" + now.minus(Duration.ofDays(1)) + "&to=" + now.plus(Duration.ofDays(1));
    }

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

    private Answer send(String method, String bearer, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/problem+json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
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
