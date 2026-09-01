package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ApiErrorHandlerTest.FailingController.class, ApiErrorHandlerTest.CorrelationScopingFilter.class})
@SuppressWarnings("try") // A correlation Scope is used for its close side effect.
class ApiErrorHandlerTest {

    /** A string that must never appear in a response, whatever the path. */
    private static final String SECRET = "account=ACC-99812 token=sk_live_2f8a";

    @LocalServerPort private int port;

    @SpringBootApplication
    static class TestApplication {}

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
        assertThat(response.body()).contains("\"instance\":\"/probe/api-exception\"");
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
    @DisplayName("the response carries the flow's correlation identifier when a filter scopes the request")
    void correlationReachesTheClient() throws Exception {
        // The only correlation sink a customer ever sees, and a finding in its own right: the
        // scope must be established by a FILTER, not inside the controller.
        //
        // Written first with the controller entering the scope around its own failure, this
        // failed - because a try-with-resources closes before the exception reaches an
        // @ExceptionHandler, so the renderer ran with no correlation in scope. Exactly the shape
        // of the relay defect P0-TSK-020's review found, where a catch attached to a
        // try-with-resources ran after the resource closed.
        //
        // So it is a requirement on whoever adds the production ingress filter: the scope has to
        // wrap error handling as well as the handler, or the identifier reaches the log and not
        // the client - and the client's copy is the one a person can quote.
        HttpResponse<String> response = postJson("/probe/unexpected", "{}");

        assertContract(response, 500, "api.InternalError");
        assertThat(response.body()).contains("api-flow-9");
    }

    /**
     * Establishes a correlation scope around the whole request, as the ingress filter will.
     *
     * <p>Registered with the highest precedence so it wraps the dispatcher, and therefore the
     * error handling too. A filter ordered after the dispatcher would reproduce the failure this
     * test exists to prevent.
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class CorrelationScopingFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            try (CorrelationContext.Scope ignored =
                    CorrelationContext.enter(
                            Correlation.startingWith(CorrelationId.of("api-flow-9")))) {
                chain.doFilter(request, response);
            }
        }
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static void assertContract(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .as("RFC 9457 media type, not application/json")
                .startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }
}
