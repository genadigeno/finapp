package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The {@code Idempotency-Key} boundary contract (`P0-TSK-017`).
 *
 * <p>Driven over real HTTP rather than MockMvc, for the reason {@code P0-TSK-024} established: an
 * interceptor and the error rendering that follows it are dispatcher behaviour, and MockMvc does
 * not run the container's error dispatch. A contract asserted against a mock is a contract nobody
 * has seen a client receive.
 *
 * <p>The probe controllers are the endpoints Phase 0 does not have. `API_CONVENTIONS.md` §6 says
 * every money-moving command requires the header, and Phase 0 has no money-moving command — so the
 * mechanism is proven against declared endpoints, exactly as {@code P0-TSK-024} and
 * {@code P0-TSK-025} proved theirs.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({IdempotencyKeyHeaderTest.Probes.class, IdempotencyKeyHeaderTest.Open.class})
@DisplayName("Idempotency-Key header (P0-TSK-017)")
class IdempotencyKeyHeaderTest {

    private static final String A_KEY = "3f6d1a2e-9c47-4b18-8a0f-2b7c5d9e1104";

    @LocalServerPort private int port;
    @Autowired private Probes probes;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void forgetPreviousEntries() {
        probes.reset();
    }

    // ------------------------------------------------------------------
    // The criterion: a declared endpoint rejects a request without the header
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a declared endpoint refuses a request with no key, before entering the handler")
    void aDeclaredEndpointRefusesARequestWithNoKey() throws Exception {
        HttpResponse<String> response = post("/v1/probe/commands", null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.IdempotencyKeyRequired");

        // Counted rather than inferred. A rejection issued AFTER the handler ran and did half the
        // work looks identical from outside, and for a money-moving command that half is the part
        // that matters (RequestValidationTest makes the same assertion for the same reason).
        assertThat(probes.commandEntries())
                .as("the handler must never be entered")
                .isZero();
    }

    @Test
    @DisplayName("a declared endpoint accepts a request that carries a key")
    void aDeclaredEndpointAcceptsARequestWithAKey() throws Exception {
        HttpResponse<String> response = post("/v1/probe/commands", A_KEY);

        // The positive control. Without it, every rejection above could be the endpoint being
        // broken rather than the check working.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(A_KEY);
        assertThat(probes.commandEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName("an endpoint that does not declare the requirement is unaffected")
    void anUndeclaredEndpointIsUnaffected() throws Exception {
        HttpResponse<String> response = post("/v1/probe/open", null);

        // The requirement is opt-in. An interceptor that demanded the header everywhere would
        // force it onto operations where it means nothing, and clients would learn to send a
        // value nobody reads.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(probes.openEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName("a safe method on a declared controller does not need a key")
    void aSafeMethodDoesNotNeedAKey() throws Exception {
        HttpResponse<String> response = get("/v1/probe/commands");

        // The annotation is on the CONTROLLER here, so without the safe-method exemption this GET
        // would demand a key. A read moves no money and a key on it means nothing.
        assertThat(response.statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // The criterion: key format validated
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank key is refused as invalid, not as missing")
    void aBlankKeyIsRefusedAsInvalid() throws Exception {
        HttpResponse<String> response = post("/v1/probe/commands", "   ");

        assertThat(response.statusCode()).isEqualTo(422);
        // Deliberately a different code from the missing case: the client supplied a key and must
        // fix it, which is a different remediation from "generate one".
        assertThat(response.body()).contains("api.ValidationFailed");
        assertThat(probes.commandEntries()).isZero();
    }

    @Test
    @DisplayName("an over-long key is refused, at the bound the store enforces")
    void anOverLongKeyIsRefused() throws Exception {
        String tooLong = "k".repeat(IdempotencyKey.MAX_LENGTH + 1);

        HttpResponse<String> response = post("/v1/probe/commands", tooLong);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed");
    }

    @Test
    @DisplayName("a key outside the permitted charset is refused")
    void aKeyOutsideTheCharsetIsRefused() throws Exception {
        // Found by following DATA_CLASSIFICATION.md section 5, which classifies this column as a
        // caller-supplied identifier. IdempotencyKey bounds length and blankness because those are
        // the CHECK constraints on the table - it carries no charset, so before this the header
        // accepted anything printable. The key is a value Phase 4 will log and put on an audit
        // record; CorrelationId has the same charset for the same reason (P0-TSK-014).
        //
        // A SPACE rather than CR/LF here, and that is worth recording: the JDK HttpClient refuses
        // to SEND a header value containing CR/LF at all - "invalid header value" - so the
        // log-forging case cannot be driven over this transport. It is unit-tested next to the
        // charset in IdempotencyKeyHeaderCharsetTest instead. A hostile client writing raw bytes
        // to a socket is not bound by the JDK's politeness, which is why the boundary check exists
        // rather than relying on the client.
        HttpResponse<String> response = post("/v1/probe/commands", "not a valid key");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed");
        assertThat(probes.commandEntries()).isZero();
    }

    @Test
    @DisplayName("a key at exactly the bound is accepted")
    void aKeyAtTheBoundIsAccepted() throws Exception {
        String atLimit = "k".repeat(IdempotencyKey.MAX_LENGTH);

        // The off-by-one, which the P0-TSK-018 review found untested for a different bound: a
        // `>=` would silently reject a legal key, and only the boundary case shows it.
        assertThat(post("/v1/probe/commands", atLimit).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // The criterion: never logged as sensitive data
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the key is not treated as a secret, and the response never echoes a bad one")
    void theKeyIsNotASecretAndABadOneIsNotEchoed() throws Exception {
        // Not redacted: an idempotency key is not a secret, and `secretsAreWrapped` deliberately
        // excludes `key` from its vocabulary (P0-TSK-030) - a rule with false positives is a rule
        // somebody turns off. The accepted key comes back intact.
        assertThat(post("/v1/probe/commands", A_KEY).body()).isEqualTo(A_KEY);

        // But a REJECTED value is never reflected. It is the caller's own input, and echoing it
        // into a response body is how a header becomes a reflection vector (INV-AUD-02).
        String hostile = "<script>alert(1)</script>" + "x".repeat(400);
        HttpResponse<String> response = post("/v1/probe/commands", hostile);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .as("the rejected value must not appear in the problem detail")
                .doesNotContain("<script>");
    }

    // ------------------------------------------------------------------

    private HttpResponse<String> post(String path, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The endpoints Phase 0 does not have.
     *
     * <p>Registered by {@code @Import} rather than component scan, so they exist only for this
     * context and cannot reach the published contract — the separation
     * {@code OpenApiContractTest} asserts, after the {@code P0-TSK-026} review found nothing was
     * checking it.
     */
    @com.finapp.app.session.Unauthenticated
    @Controller
    @RequiresIdempotencyKey
    static class Probes {

        private final AtomicInteger commandEntries = new AtomicInteger();
        private final Open open;

        Probes(Open open) {
            this.open = open;
        }

        void reset() {
            commandEntries.set(0);
            open.entries.set(0);
        }

        int commandEntries() {
            return commandEntries.get();
        }

        int openEntries() {
            return open.entries.get();
        }

        /** Declares the requirement, by way of the annotation on this class. */
        @PostMapping("/probe/commands")
        @ResponseBody
        String command(@RequestHeader(IdempotencyKeyHeader.NAME) String key) {
            commandEntries.incrementAndGet();
            return key;
        }

        /** Same controller, safe method: exempt. */
        @GetMapping("/probe/commands")
        @ResponseBody
        String read() {
            return "read";
        }
    }

    /**
     * A controller that declares nothing — the control for "the requirement is opt-in".
     *
     * <p>It has to be a separate class: the annotation on {@link Probes} covers every handler in
     * it, which is the point of allowing it at type level.
     */
    @com.finapp.app.session.Unauthenticated
    @Controller
    static class Open {

        private final AtomicInteger entries = new AtomicInteger();

        @PostMapping("/probe/open")
        @ResponseBody
        String open() {
            entries.incrementAndGet();
            return "open";
        }
    }
}
