package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ApiVersion;
import com.finapp.platform.api.PlatformErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every error path returns the contract shape, and nothing internal escapes through any of them.
 *
 * <p><strong>The paths worth testing are the ones nobody writes a handler for.</strong> An
 * unknown route, an unsupported method, a body that will not parse, a media type nobody reads —
 * the framework rejects all of these before a line of our code runs, and by default answers in
 * its own shape. A client then sees two different error formats depending on how far into the
 * request it got, which is invisible in a normal test suite because each response looks
 * reasonable on its own.
 *
 * <p><strong>A real server, not MockMvc.</strong> MockMvc does not run the servlet container's
 * error dispatch, and that dispatch is exactly where the framework's own error body comes from —
 * a slice test can therefore report a clean contract for a path that would return Spring's
 * {@code /error} body in production. The tests below start the application on a real port and
 * speak HTTP to it with the JDK's client, which needs no dependency and cannot flatter us.
 *
 * <p>The controller below exists only to be failed at. It is a fixture; the production code
 * under test is {@link ApiErrorHandler} and the contract it renders.
 *
 * <p>No nested {@code @SpringBootApplication}: the context is the real {@code FinappApplication},
 * found by searching up from this package. A local one would scan only {@code com.finapp.app.api}
 * and quietly miss the composition root - which is how this class first failed once the
 * correlation filter needed a bean from it.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiErrorHandlerTest.FailingController.class)
class ApiErrorHandlerTest {

    /** A string that must never appear in a response, whatever the path. */
    private static final String SECRET = "account=ACC-99812 token=sk_live_2f8a";

    @LocalServerPort private int port;

    /** Exists to fail. Not production code. */
    @RestController
    static class FailingController {

        @PostMapping(path = "/probe/api-exception", consumes = MediaType.APPLICATION_JSON_VALUE)
        String apiException(@RequestBody String body) {
            throw new ApiException(
                    PlatformErrorCode.CONFLICT, "conflict for " + SECRET, "Already submitted.");
        }

        @PostMapping(path = "/probe/unexpected", consumes = MediaType.APPLICATION_JSON_VALUE)
        String unexpected(@RequestBody String body) {
            // The shape of a real accident: a message written for a log, naming things a client
            // must never see.
            throw new IllegalStateException("ledger posting failed for " + SECRET);
        }

        @PostMapping(path = "/probe/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody Payload payload) {
            return "ok";
        }

        record Payload(String name, int amount) {}

        @GetMapping("/probe/needs-param")
        String needsParam(@RequestParam String required) {
            return required;
        }

        @GetMapping("/probe/number/{value}")
        String number(@PathVariable int value) {
            return String.valueOf(value);
        }
    }

    // -----------------------------------------------------------------
    // Errors we raise
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a deliberate API error renders the contract, with its code and authored detail")
    void anApiExceptionRendersTheContract() throws Exception {
        HttpResponse<String> response = postJson("/probe/api-exception", "{}");

        assertContract(response, 409, "api.Conflict");
        assertThat(response.body()).contains("Already submitted.");
        assertThat(response.body())
                .contains("\"instance\":\"" + ApiVersion.CURRENT_PREFIX + "/probe/api-exception\"");
        assertThat(response.body()).doesNotContain(SECRET);
    }

    // -----------------------------------------------------------------
    // Errors the framework raises before our code runs
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an unknown route returns the contract, not the framework's own shape")
    void anUnknownRouteRendersTheContract() throws Exception {
        assertContract(get("/probe/does-not-exist"), 404, "api.NotFound");
    }

    @Test
    @DisplayName("an unsupported method returns the contract")
    void anUnsupportedMethodRendersTheContract() throws Exception {
        assertContract(get("/probe/api-exception"), 405, "api.MethodNotAllowed");
    }

    @Test
    @DisplayName("an unsupported media type returns the contract")
    void anUnsupportedMediaTypeRendersTheContract() throws Exception {
        assertContract(post("/probe/body", "hello", "text/plain"), 415, "api.UnsupportedMediaType");
    }

    @Test
    @DisplayName("a body that will not parse returns the contract, and does not echo the body back")
    void aMalformedBodyRendersTheContract() throws Exception {
        // Jackson's parse errors quote the offending input. Helpful in a log; in a response it is
        // an echo of bytes the caller controls.
        HttpResponse<String> response =
                postJson("/probe/body", "{\"name\": \"" + SECRET + "\", \"amount\": }");

        assertContract(response, 400, "api.MalformedRequest");
        assertThat(response.body())
                .as("the response must not echo what the caller sent")
                .doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a client mistake the framework catches is a 4xx, never a 500")
    void frameworkClientErrorsAreNotReportedAsOurFailure() throws Exception {
        // Both of these returned 500 api.InternalError until a review probed them. That is not a
        // cosmetic mislabel: a client may retry a 500 forever on a request that can never
        // succeed, and a spike of malformed requests is indistinguishable from an outage on
        // every error-rate dashboard. Reporting someone else's mistake as our failure is the
        // specific defect these two assertions exist to prevent.
        assertContract(get("/probe/needs-param"), 400, "api.MalformedRequest");
        assertContract(get("/probe/number/not-a-number"), 400, "api.MalformedRequest");
    }

    // -----------------------------------------------------------------
    // The one that matters most
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an unexpected exception leaks neither its message, its type, nor a stack frame")
    void anUnexpectedExceptionLeaksNothing() throws Exception {
        HttpResponse<String> response = postJson("/probe/unexpected", "{}");

        assertContract(response, 500, "api.InternalError");
        assertThat(response.body()).as("the exception's message").doesNotContain(SECRET);
        assertThat(response.body()).as("its type").doesNotContain("IllegalStateException");
        assertThat(response.body()).as("any stack frame").doesNotContain("com.finapp");
        assertThat(response.body()).doesNotContain("\"trace\"").doesNotContain("\"exception\"");
    }

    @Test
    @DisplayName("no error response carries any member beyond the contract")
    void theShapeIsExactlyTheContract() throws Exception {
        // Spring's default error body carries "timestamp", "error", "path" and sometimes "trace".
        // If any appears, the framework answered rather than the contract - the failure this
        // class exists to catch, and one that looks like a perfectly good error response.
        String body = get("/probe/does-not-exist").body();

        assertThat(body)
                .doesNotContain("\"timestamp\"")
                .doesNotContain("\"error\"")
                .doesNotContain("\"trace\"");
        // Absent members are absent, not null. RFC 9457 members are optional, and "detail":null
        // tells a client there was something to say and then does not say it.
        assertThat(body).doesNotContain("null");
        assertThat(body)
                .contains("\"type\"")
                .contains("\"title\"")
                .contains("\"status\"")
                .contains("\"code\"");
    }

    @Test
    @DisplayName("the response carries a correlation identifier, in the body and in the header")
    void correlationReachesTheClient() throws Exception {
        // The only correlation sink a customer ever sees. This used a stand-in filter until
        // P0-TSK-025 built the real one; it now exercises production code, and the assertion is
        // that the body and the header agree rather than that either matches a value the test
        // chose - which is what a client actually needs when quoting one to support.
        HttpResponse<String> response = postJson("/probe/unexpected", "{}");

        assertContract(response, 500, "api.InternalError");
        String header = response.headers().firstValue(CorrelationFilter.HEADER).orElseThrow();
        assertThat(header).isNotBlank();
        assertThat(response.body()).contains(header);
    }

    // -----------------------------------------------------------------

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return post(path, body, MediaType.APPLICATION_JSON_VALUE);
    }

    private HttpResponse<String> post(String path, String body, String contentType) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Every probe path below is written unversioned and reached under {@link
     * ApiVersion#CURRENT_PREFIX}, because that is where the application actually serves it
     * (P0-TSK-026). Building the URI here rather than in each test means the contract's surface
     * moves in one place when the version does.
     */
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + ApiVersion.CURRENT_PREFIX + path);
    }

    private static void assertContract(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .as("RFC 9457 media type, not application/json")
                .startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }
}
