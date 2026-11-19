package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.RoleName;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The end-to-end seed's access-review scenarios (`P17-TSK-108`), run as {@code infra/scripts/e2e-seed.sh} runs them: the very
 * {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application over
 * HTTP, and the very {@code infra/scripts/e2e-seed/held-role.sql}, read from the repository and run as the compose stack's migrator
 * with its three variables made literals as psql makes them. The run's administrator is README section 5e's break-glass step, asked
 * here as the script asks it.
 *
 * <p>Each scenario shows first that the grant door refuses the state - which is why the seed plants it - then that the review the
 * console reads names what was planted, and that the revocation door the console acts through takes it away. The holdings are
 * moved a century back before the review is read, as {@code IdentityAdministrationDatabaseTest} does: the reviews serve the oldest
 * 200, and this shared database holds every other suite's fixtures. The seed itself writes {@code now()}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the end-to-end seed's access-review scenarios: the door refuses, the plant holds, the review names (P17-TSK-108)")
class E2eSeedReviewsDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VARIABLE = Pattern.compile(":'([a-z]+)'");
    private static final String REVIEW_REASON = "{\"reason\":\"e2e seed review: a holding from before the rule, decided\"}";

    @LocalServerPort private int port;

    @TempDir private Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    @DisplayName("factorless-holding: refused at the door, planted, named by the factorless review, revoked through the door")
    void aFactorlessHoldingIsReviewedAndRevoked() throws Exception {
        String admin = administrator();
        String holder = idOf(registered("register-bare", "unenrolled"));
        assertThat(single("SELECT count(*) FROM identity.mfa_enrolment WHERE identity_id = '" + holder + "'"))
                .as("registered and nothing more: no factor at all").isEqualTo("0");
        assertThat(RoleName.LEDGER_OPERATOR.requiresEnrolledFactor()).as("the role needs one").isTrue();

        Run refused = run("grant", "admin", "LEDGER_OPERATOR", holder);
        assertThat(refused.exit()).as("the door refuses it today: why the seed plants it").isNotZero();
        assertThat(refused.error()).contains("409").contains("identity.FactorRequiredForRole");

        String holding = plant(holder, "LEDGER_OPERATOR", admin);
        assertThat(single("SELECT identity_id || '|' || role_name || '|' || assigned_by || '|' || (revoked_at IS NULL)"
                + " FROM identity.role_assignment WHERE id = '" + holding + "'"))
                .isEqualTo(holder + "|LEDGER_OPERATOR|" + admin + "|true");
        backDate(holder, 1);

        String session = elevated("admin");
        String review = get(session, "/v1/operator/access-review/factorless-holdings");
        assertThat(review).contains("{\"identityId\":\"" + holder + "\",\"role\":\"LEDGER_OPERATOR\",");

        assertThat(revoke(session, holder, "LEDGER_OPERATOR")).as("the console's decision, through the door").isEqualTo(204);
        assertThat(get(session, "/v1/operator/access-review/factorless-holdings")).doesNotContain(holder);
        assertThat(single("SELECT count(*) FROM identity.role_assignment WHERE identity_id = '" + holder + "' AND revoked_at IS NULL"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("sod-conflict: one half granted, the other refused at the door, planted, named by the SoD review, one half revoked")
    void aSodConflictIsReviewedAndOneHalfRevoked() throws Exception {
        String admin = administrator();
        String holder = idOf(registered("register", "conflicted"));
        seed("grant", "admin", "ACCOUNTANT", holder);

        Run refused = run("grant", "admin", "FINANCIAL_CONTROLLER", holder);
        assertThat(refused.exit()).as("the door refuses the pair's other half today: why the seed plants it").isNotZero();
        assertThat(refused.error()).contains("409").contains("identity.RoleConflict");

        plant(holder, "FINANCIAL_CONTROLLER", admin);
        backDate(holder, 2);

        String session = elevated("admin");
        assertThat(get(session, "/v1/operator/access-review/sod-conflicts"))
                .contains("{\"identityId\":\"" + holder + "\",\"roles\":[\"ACCOUNTANT\",\"FINANCIAL_CONTROLLER\"],");
        assertThat(get(session, "/v1/operator/access-review/factorless-holdings"))
                .as("the holder has a factor: the scenarios are apart").doesNotContain(holder);

        assertThat(revoke(session, holder, "FINANCIAL_CONTROLLER")).as("the console's decision, through the door").isEqualTo(204);
        assertThat(get(session, "/v1/operator/access-review/sod-conflicts")).doesNotContain(holder);
        assertThat(single("SELECT string_agg(role_name, ',') FROM identity.role_assignment WHERE identity_id = '" + holder
                + "' AND revoked_at IS NULL")).isEqualTo("ACCOUNTANT");
    }

    // ------------------------------------------------------------------------------------------- the script's steps

    /** The script's administrator: registered with a factor through the door, then README section 5e's break-glass step. */
    private String administrator() throws Exception {
        String login = registered("register", "admin");
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + login + "'");
        }
        return idOf(login);
    }

    /** The script's held_role: held-role.sql unchanged, its variables made literals as psql's {@code :'name'} makes them. */
    private static String plant(String identity, String role, String granter) throws Exception {
        String text = Files.readString(repositoryRoot().resolve("infra/scripts/e2e-seed/held-role.sql"), StandardCharsets.UTF_8);
        Properties variables = new Properties();
        variables.setProperty("identity", identity);
        variables.setProperty("role", role);
        variables.setProperty("granter", granter);
        Matcher variable = VARIABLE.matcher(text);
        StringBuilder statement = new StringBuilder();
        while (variable.find()) {
            String value = variables.getProperty(variable.group(1));
            assertThat(value).as("the script hands held-role.sql :'%s'", variable.group(1)).isNotNull();
            variable.appendReplacement(statement, Matcher.quoteReplacement("'" + value.replace("'", "''") + "'"));
        }
        variable.appendTail(statement);
        try (Connection migrator = DatabaseRoles.migrator(); Statement plant = migrator.createStatement();
                ResultSet rows = plant.executeQuery(statement.toString())) {
            assertThat(rows.next()).as("the plant returns its row").isTrue();
            return rows.getString(1);
        }
    }

    private String registered(String command, String who) throws Exception {
        String login = value("LOGIN", seed(command, who));
        assertThat(login).as("the shape the script checks before SQL").matches("e2e-seed\\.[a-z]+\\.[0-9a-f]{12}");
        return login;
    }

    private static String idOf(String login) throws SQLException {
        return single("SELECT id FROM identity.identity WHERE login_identifier = '" + login + "'");
    }

    /** The live holdings of {@code holder}, moved a century back, ahead of every fixture holding of the shared database. */
    private static void backDate(String holder, int live) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update = app.prepareStatement("UPDATE identity.role_assignment"
                        + " SET assigned_at = assigned_at - interval '100 years' WHERE identity_id = ?::uuid AND revoked_at IS NULL")) {
            update.setString(1, holder);
            assertThat(update.executeUpdate()).as("the live holdings").isEqualTo(live);
        }
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

    /** A stepped-up session for one of the seed's identities, from the run's own state, at a step its last step-up did not use. */
    private String elevated(String who) throws Exception {
        Properties people = new Properties();
        try (InputStream in = Files.newInputStream(state.resolve("people.properties"))) {
            people.load(in);
        }
        HttpResponse<String> signedIn = send("POST", null, "/v1/authentications", "{\"loginIdentifier\":\""
                + people.getProperty(who + ".login") + "\",\"password\":\"" + people.getProperty(who + ".password") + "\"}");
        assertThat(signedIn.statusCode()).as(signedIn.body()).isEqualTo(201);
        long last = Long.parseLong(people.getProperty(who + ".step"));
        long step = Instant.now().getEpochSecond() / 30;
        while (step <= last) {
            Thread.sleep(1000);
            step = Instant.now().getEpochSecond() / 30;
        }
        HttpResponse<String> stepped = send("POST", field(signedIn.body(), "sessionToken"), "/v1/authentications/mfa",
                "{\"code\":\"" + totp(people.getProperty(who + ".totp"), step) + "\"}");
        assertThat(stepped.statusCode()).as(stepped.body()).isEqualTo(200);
        return field(stepped.body(), "sessionToken");
    }

    private String get(String session, String path) throws Exception {
        HttpResponse<String> answer = send("GET", session, path, null);
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        return answer.body();
    }

    private int revoke(String session, String holder, String role) throws Exception {
        HttpResponse<String> answer = send("DELETE", session, "/v1/identities/" + holder + "/roles/" + role, REVIEW_REASON);
        return answer.statusCode();
    }

    private HttpResponse<String> send(String method, String bearer, String path, String body) throws Exception {
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
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
