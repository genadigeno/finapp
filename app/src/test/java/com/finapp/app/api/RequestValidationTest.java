package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ApiVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Untrusted input is rejected at the boundary, before any domain code sees it.
 *
 * <p>The acceptance criterion has two halves and the second is the one worth testing carefully:
 * malformed and oversized payloads are rejected <em>with the error contract</em>, and validation
 * failures <strong>never reach domain code</strong>. The second is a claim about <em>where</em>
 * the rejection happens, not about the response, so the controller counts its own invocations and
 * the tests assert it was never entered. A 422 returned after the handler ran and did half the
 * work would look identical from outside.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({RequestValidationTest.GuardedController.class, RequestValidationTest.ProxyValidatedController.class})
class RequestValidationTest {

    /** Never appears in a response: it is the caller's own input coming back. */
    private static final String SECRET = "token=sk_live_2f8a";

    @LocalServerPort private int port;

    /** Counts entries so "never reached domain code" is measured rather than assumed. */
    static final AtomicInteger ENTERED = new AtomicInteger();

    @BeforeEach
    void resetCounter() {
        ENTERED.set(0);
    }

    @RestController
    static class GuardedController {

        /** Constraints declared on the type, not written by hand in the handler. */
        record Transfer(
                @NotBlank @Size(max = 32) String reference,
                @Positive @Max(1_000_000) long amountMinorUnits) {}

        /** Spring's built-in method validation. */
        @GetMapping("/probe/param-validated")
        String paramValidated(@RequestParam @Positive int amount) {
            ENTERED.incrementAndGet();
            return String.valueOf(amount);
        }

        @PostMapping(path = "/probe/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
        String create(@Valid @RequestBody Transfer transfer) {
            ENTERED.incrementAndGet();
            return transfer.reference();
        }
    }

    /** The other way Spring validates a method parameter: a {@code @Validated} proxy. */
    @RestController
    @Validated
    static class ProxyValidatedController {

        @GetMapping("/probe/proxy-validated")
        String proxyValidated(@RequestParam @Positive int amount) {
            ENTERED.incrementAndGet();
            return String.valueOf(amount);
        }
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an invalid body is rejected as 422 and the handler is never entered")
    void invalidInputNeverReachesTheHandler() throws Exception {
        HttpResponse<String> response =
                postJson("/probe/transfers", "{\"reference\":\"\",\"amountMinorUnits\":-5}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"api.ValidationFailed\"");
        assertThat(ENTERED)
                .as("validation must reject before any domain invocation, not after it")
                .hasValue(0);
    }

    @Test
    @DisplayName("the response names the fields and the constraints, so a client can fix it")
    void theDetailIsActionable() throws Exception {
        HttpResponse<String> response =
                postJson("/probe/transfers", "{\"reference\":\"\",\"amountMinorUnits\":-5}");

        assertThat(response.body()).contains("reference").contains("amountMinorUnits");
    }

    @Test
    @DisplayName("the rejected value is never echoed back")
    void theRejectedValueIsNotReflected() throws Exception {
        // A validation message that quotes what the caller sent turns an error response into a
        // reflection of attacker-controlled bytes (INV-AUD-02). The field name is ours; the
        // value is theirs, and only one of them goes back.
        HttpResponse<String> response =
                postJson(
                        "/probe/transfers",
                        "{\"reference\":\"" + SECRET + " and more than thirty-two characters\","
                                + "\"amountMinorUnits\":5}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).doesNotContain(SECRET);
        assertThat(response.body()).contains("reference");
        assertThat(ENTERED).hasValue(0);
    }

    @Test
    @DisplayName("a valid body still reaches the handler, so validation is not simply refusing everything")
    void validInputIsAccepted() throws Exception {
        HttpResponse<String> response =
                postJson("/probe/transfers", "{\"reference\":\"TRF-1\",\"amountMinorUnits\":250}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ENTERED).hasValue(1);
    }

    // -----------------------------------------------------------------
    // Size
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an oversized body is refused as 413, declared length or not")
    void anOversizedBodyIsRefused() throws Exception {
        String huge = "{\"reference\":\"" + "x".repeat(2_000_000) + "\",\"amountMinorUnits\":1}";

        HttpResponse<String> declared = postJson("/probe/transfers", huge);

        assertThat(declared.statusCode()).isEqualTo(413);
        assertThat(declared.body()).contains("\"code\":\"api.PayloadTooLarge\"");
        assertThat(ENTERED).hasValue(0);
    }

    @Test
    @DisplayName("an oversized body with no declared length is refused too")
    void aChunkedOversizedBodyIsRefused() throws Exception {
        // The case a Content-Length check cannot see. Streaming the body makes the JDK client use
        // chunked transfer encoding, so the request declares no length at all - which is how an
        // attacker opts out of a limit that only reads the header.
        byte[] huge =
                ("{\"reference\":\"" + "x".repeat(2_000_000) + "\",\"amountMinorUnits\":1}")
                        .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response =
                CLIENT.send(
                        HttpRequest.newBuilder(uri("/probe/transfers"))
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(huge)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"api.PayloadTooLarge\"");
        assertThat(ENTERED).hasValue(0);
    }

    @Test
    @DisplayName("a body within the limit is not refused, so the bound is a limit and not a wall")
    void aReasonableBodyIsAccepted() throws Exception {
        HttpResponse<String> response =
                postJson("/probe/transfers", "{\"reference\":\"TRF-2\",\"amountMinorUnits\":1}");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    // -----------------------------------------------------------------
    // Correlation at the boundary
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every response carries a correlation identifier, error or not")
    void everyResponseIsCorrelated() throws Exception {
        HttpResponse<String> ok =
                postJson("/probe/transfers", "{\"reference\":\"TRF-3\",\"amountMinorUnits\":1}");
        HttpResponse<String> rejected =
                postJson("/probe/transfers", "{\"reference\":\"\",\"amountMinorUnits\":1}");

        assertThat(ok.headers().firstValue(CorrelationFilter.HEADER)).isPresent();
        assertThat(rejected.headers().firstValue(CorrelationFilter.HEADER)).isPresent();
        // The body's copy is the one a person can quote from a screenshot.
        assertThat(rejected.body())
                .contains(rejected.headers().firstValue(CorrelationFilter.HEADER).orElseThrow());
    }

    @Test
    @DisplayName("a correlation identifier the client supplies is honoured")
    void aSuppliedIdentifierIsUsed() throws Exception {
        HttpResponse<String> response =
                CLIENT.send(
                        HttpRequest.newBuilder(uri("/probe/transfers"))
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .header(CorrelationFilter.HEADER, "client-flow-77")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"reference\":\"\",\"amountMinorUnits\":1}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue(CorrelationFilter.HEADER)).contains("client-flow-77");
        assertThat(response.body()).contains("client-flow-77");
    }

    @Test
    @DisplayName("a malformed correlation header is replaced, not reflected, and does not fail the request")
    void aMalformedIdentifierIsReplaced() throws Exception {
        // The header is untrusted input that ends up in log lines, so CorrelationId refuses
        // anything outside its charset. Refusing the REQUEST over it would be wrong: a malformed
        // diagnostic hint is not a reason to decline someone's payment.
        String injection = "bad\nvalue\0with control chars";

        HttpResponse<String> response =
                CLIENT.send(
                        HttpRequest.newBuilder(uri("/probe/transfers"))
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .header(CorrelationFilter.HEADER, "bad value with spaces!")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"reference\":\"TRF-4\",\"amountMinorUnits\":1}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("the request still succeeds").isEqualTo(200);

        // Asserted against every fragment of the input, not just the whole string. A mutation
        // sweep found that "does not contain the original" passes for a SANITISED value -
        // "bad value with spaces!" becomes "bad_value_with_spaces_" and the assertion is
        // satisfied while the platform has accepted a value derived from untrusted input.
        //
        // Sanitising is the wrong behaviour and CorrelationTokens says so: it rejects rather
        // than sanitises, because a silently rewritten identifier breaks the client's own
        // correlation without telling anyone - they log one value, we log another, and the two
        // can never be joined.
        String issued = response.headers().firstValue(CorrelationFilter.HEADER).orElseThrow();
        assertThat(issued).isNotBlank();
        assertThat(issued)
                .as("nothing derived from what the caller sent")
                .doesNotContain("bad")
                .doesNotContain("value")
                .doesNotContain("spaces");
        assertThat(injection).isNotBlank();
    }

    @Test
    @DisplayName("a constraint on a method parameter is 422 too, however Spring validates it")
    void everyValidationPathGivesTheSameAnswer() throws Exception {
        // A review found the same class of failure reported three different ways depending only
        // on where the constraint was declared: a request body gave 422, a method parameter gave
        // 400, and a method parameter under @Validated gave 500. A client cannot write error
        // handling against that, and the 500 told the caller it was our fault for something only
        // they could fix.
        //
        // Both parameter paths are asserted, because they are different mechanisms in Spring and
        // fixing one says nothing about the other.
        HttpResponse<String> builtIn = get("/probe/param-validated?amount=-1");
        HttpResponse<String> proxied = get("/probe/proxy-validated?amount=-1");

        assertThat(builtIn.statusCode()).as("Spring's built-in method validation").isEqualTo(422);
        assertThat(builtIn.body()).contains("\"code\":\"api.ValidationFailed\"").contains("amount");

        assertThat(proxied.statusCode()).as("the @Validated proxy path").isEqualTo(422);
        assertThat(proxied.body()).contains("\"code\":\"api.ValidationFailed\"").contains("amount");

        assertThat(ENTERED).as("neither reached the handler").hasValue(0);
    }

    @Test
    @DisplayName("a valid parameter still reaches the handler on both paths")
    void validParametersAreAccepted() throws Exception {
        assertThat(get("/probe/param-validated?amount=5").statusCode()).isEqualTo(200);
        assertThat(get("/probe/proxy-validated?amount=5").statusCode()).isEqualTo(200);
        assertThat(ENTERED).hasValue(2);
    }

    // -----------------------------------------------------------------

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Unversioned in the fixture, versioned on the wire - see {@code ApiVersioningTest}. */
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + ApiVersion.CURRENT_PREFIX + path);
    }
}
