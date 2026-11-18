package com.finapp.app.settlement;

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
import java.util.Properties;
import java.util.UUID;
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
 * The end-to-end seed's settlement half (`P17-TSK-106`), run as {@code infra/scripts/e2e-seed.sh} runs it: the very
 * {@code infra/scripts/e2e-seed/Seed.java}, launched as a separate {@code java --source 21} process against this application over
 * HTTP, through the platform's own doors only; the two answers only the database gives (a test identity's id, and the run's first
 * administrator by README section 5e's break-glass step) asked here as the script asks them.
 *
 * <p>The intake's and the matcher's real schedules run, at a one-second poll instead of fifteen: the file is parsed, accepted and
 * matched by the same scheduled legs as on the compose stack, never by a sweep this test calls. That is why this suite has a JVM and
 * a database of its own (`ownContainerSuites`): a context with live sweepers would tick under every later suite of a shared JVM.
 *
 * <p>Then what the console's acceptances need is shown possible on what was seeded: the parsed file refuses its own submitter's
 * attestation, and the FEE_MISMATCH is resolved by two persons - ACKNOWLEDGE proposed, the proposer's approval refused, an approval
 * echoing an operand the proposal does not name refused as stale, and the plain re-confirmation approved, the break RESOLVED.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finapp.settlement.intake.sweeper.enabled=true",
            "finapp.settlement.intake.poll=PT1S",
            "finapp.reconciliation.matching.sweeper.enabled=true",
            "finapp.reconciliation.matching.poll=PT1S"
        })
@DisplayName("the end-to-end seed's settlement scenarios, through the doors (P17-TSK-106)")
class E2eSeedDoorsDatabaseTest {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");

    @LocalServerPort private int port;

    @TempDir private Path state;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    @DisplayName("parsed-file: a report PARSED and unattested, which its own submitter cannot attest")
    void aParsedFileAwaitsASecondPerson() throws Exception {
        String submitter = operators();

        String file = value("FILE_ID", seed("upload", "submitter", "parsed"));
        seed("await-file", "submitter", file, "PARSED");

        assertThat(single("SELECT status || '|' || received_by || '|' || coalesce(attested_by, '-') FROM settlement.file WHERE id = '" + file + "'"))
                .as("parsed, received from the seed's submitter, attested by nobody")
                .isEqualTo("PARSED|" + submitter + "|-");
        Run own = run("attest", "submitter", file);
        assertThat(own.exit()).as("the submitter's own attestation is refused (INV-SET-07)").isNotZero();
        assertThat(own.error()).contains("409").contains("settlement.AttestationBySubmitter");
        Thread.sleep(3000);
        assertThat(single("SELECT status FROM settlement.file WHERE id = '" + file + "'"))
                .as("an unattested upload is never accepted: it waits for the console's second person")
                .isEqualTo("PARSED");
    }

    @Test
    @DisplayName("fee-break: an OPEN FEE_MISMATCH two persons resolve, a stale approval refused and re-confirmed")
    void aFeeBreakTwoPersonsResolve() throws Exception {
        operators();

        String file = value("FILE_ID", seed("upload", "submitter", "fee"));
        seed("await-file", "submitter", file, "PARSED");
        seed("attest", "attester", file);
        seed("await-file", "submitter", file, "ACCEPTED");
        String found = value("BREAK_ID", seed("await-break", "submitter", file));

        assertThat(single("SELECT type || '|' || cause || '|' || status || '|' || value_at_issue_minor || '|' || currency FROM reconciliation.break WHERE id = '" + found + "'"))
                .isEqualTo("FEE_MISMATCH|FEE_BEYOND_TOLERANCE|OPEN|175|EUR");

        // The console's -011 acceptance, possible on this break.
        String proposer = elevated("submitter");
        Answer proposed = post(proposer, "/v1/operator/reconciliation/breaks/" + found + "/resolutions",
                "{\"kind\":\"ACKNOWLEDGE\",\"reasonCode\":\"FEE_ACCEPTED_AS_CHARGED\",\"narrative\":\"the seeded fee is accepted as charged\"}",
                UUID.randomUUID().toString());
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        String resolution = field(proposed.body(), "resolutionId");
        Answer self = post(proposer, "/v1/operator/reconciliation/resolutions/" + resolution + "/approval", null, null);
        assertThat(self.status()).isEqualTo(409);
        assertThat(self.body()).contains("SelfApprovalRefused");

        String approver = elevated("attester");
        Answer stale = post(approver, "/v1/operator/reconciliation/resolutions/" + resolution + "/approval",
                "{\"targetAccountId\":\"" + UUID.randomUUID() + "\"}", null);
        assertThat(stale.status()).as(stale.body()).isEqualTo(409);
        assertThat(stale.body()).contains("ResolutionStale");
        Answer confirmed = post(approver, "/v1/operator/reconciliation/resolutions/" + resolution + "/approval", null, null);
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);

        assertThat(single("SELECT status FROM reconciliation.break WHERE id = '" + found + "'")).isEqualTo("RESOLVED");
    }

    // ------------------------------------------------------------------------------------------- the script's steps

    /** The script's settlement_operators: an administrator by break-glass, two operators granted through the door. Answers the submitter's id. */
    private String operators() throws Exception {
        String admin = registered("admin");
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            statement.executeUpdate("INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) "
                    + "SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i "
                    + "WHERE i.login_identifier = '" + admin + "'");
        }
        String submitter = idOf(registered("submitter"));
        String attester = idOf(registered("attester"));
        seed("grant", "admin", "RECONCILIATION_OPERATOR", submitter, attester);
        return submitter;
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

    /** A stepped-up session for one of the seed's identities, from the run's own state, at a step its last step-up did not use. */
    private String elevated(String who) throws Exception {
        Properties people = new Properties();
        try (InputStream in = Files.newInputStream(state.resolve("people.properties"))) {
            people.load(in);
        }
        Answer signedIn = post(null, "/v1/authentications", "{\"loginIdentifier\":\"" + people.getProperty(who + ".login")
                + "\",\"password\":\"" + people.getProperty(who + ".password") + "\"}", null);
        assertThat(signedIn.status()).as(signedIn.body()).isEqualTo(201);
        long last = Long.parseLong(people.getProperty(who + ".step"));
        long step = Instant.now().getEpochSecond() / 30;
        while (step <= last) {
            Thread.sleep(1000);
            step = Instant.now().getEpochSecond() / 30;
        }
        Answer stepped = post(field(signedIn.body(), "sessionToken"), "/v1/authentications/mfa",
                "{\"code\":\"" + totp(people.getProperty(who + ".totp"), step) + "\"}", null);
        assertThat(stepped.status()).as(stepped.body()).isEqualTo(200);
        return field(stepped.body(), "sessionToken");
    }

    private Answer post(String bearer, String path, String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/problem+json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.POST(HttpRequest.BodyPublishers.noBody());
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
