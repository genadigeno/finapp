package com.finapp.app.surface;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ApiVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every operator response carries {@code Cache-Control: no-store}, and the public surface is left as it was (`P17-TSK-101`; gap
 * G-05; finapp-admin-panel ADR-C131 decision 5).
 *
 * <p><strong>A real server, not MockMvc</strong> - {@code ApiErrorHandlerTest}'s reason: MockMvc does not run the container's error
 * dispatch, and the answers nobody writes a handler for (an unknown route, an unsupported method, a body that never parsed, a
 * request over the size bound) are exactly the ones a filter set only on the handler's path would miss. Each is asked here on the
 * operator surface, where it must carry {@code no-store}, and on the public surface, where nothing changes.
 *
 * <p>The context is the real {@code FinappApplication}. Where it runs here no edge network is named and the database is on
 * loopback, so a request without the edge's marker is {@link Surface#SINGLE_PORT}, which this filter treats as the operator
 * surface it also is. {@link Surface#UNMARKED} needs a deployed instance and is held by {@link #onlyTheOperatorSurfaces()}.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OperatorResponsesAreNotStoredTest.Probe.class)
@DisplayName("operator responses are never stored: Cache-Control no-store on every answer of the operator surface (P17-TSK-101)")
class OperatorResponsesAreNotStoredTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort private int port;

    @Autowired private ApplicationContext context;

    /** Exists to answer and to fail. Not production code. */
    @com.finapp.app.session.Unauthenticated
    @RestController
    static class Probe {

        @GetMapping("/probe/no-store/ok")
        String ok() {
            return "{\"status\":\"ok\"}";
        }

        @PostMapping(path = "/probe/no-store/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody NoStorePayload payload) {
            return "ok";
        }

        @PostMapping(path = "/probe/no-store/unexpected", consumes = MediaType.APPLICATION_JSON_VALUE)
        String unexpected(@RequestBody String body) {
            throw new IllegalStateException("an accident");
        }

        /**
         * A response cleared and sent to the container's error page, as a library or the container may do on a failure the
         * application never saw: every header set so far is gone, and only the error dispatch can put the directive back.
         */
        @GetMapping("/probe/no-store/reset")
        void reset(jakarta.servlet.http.HttpServletResponse response) throws IOException {
            response.reset();
            response.sendError(503);
        }

        /** Named apart from every other probe's record: {@code ApiSchemaNamesAreUniqueTest} reads test controllers too. */
        record NoStorePayload(String name, int amount) {}
    }

    /** Each kind of answer, by the request that produces it: a handler's, the interceptor's, and the ones nobody wrote. */
    private Map<String, HttpRequest.Builder> answers() {
        Map<String, HttpRequest.Builder> answers = new TreeMap<>();
        answers.put("200 a handler's answer", request("/probe/no-store/ok").GET());
        answers.put("401 the session interceptor's refusal on a real operator route",
                request("/operator/me").GET());
        answers.put("404 an unknown route", request("/operator/no-such-route").GET());
        answers.put("405 an unsupported method", request("/probe/no-store/ok").DELETE());
        answers.put("400 a body that never parsed", json("/probe/no-store/body", "{ not json"));
        answers.put("415 a media type nobody reads",
                request("/probe/no-store/body").header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("x")));
        answers.put("500 an unexpected failure", json("/probe/no-store/unexpected", "{}"));
        answers.put("413 a request over the size bound", json("/probe/no-store/body", "\"" + "x".repeat(1_048_577) + "\""));
        answers.put("503 a response reset and sent to the container's error page", request("/probe/no-store/reset").GET());
        return answers;
    }

    @Test
    @DisplayName("on the operator surface every kind of answer - success, refusal, and each one nobody wrote a handler for - is no-store")
    void everyOperatorAnswerIsNoStore() {
        answers().forEach((kind, request) -> {
            HttpResponse<String> response = send(request.header(RequestSurfaces.HEADER, "operator"));
            assertThat(response.statusCode()).as(kind).isEqualTo(Integer.parseInt(kind.substring(0, 3)));
            assertThat(response.headers().allValues(OperatorResponsesAreNotStored.HEADER)).as(kind).containsExactly("no-store");
        });
    }

    @Test
    @DisplayName("where nothing is deployed the one port is the operator surface too, so an unmarked answer is no-store here")
    void theSinglePortIsNoStore() {
        answers().forEach((kind, request) -> assertThat(send(request).headers().allValues(OperatorResponsesAreNotStored.HEADER))
                .as(kind).containsExactly("no-store"));
    }

    @Test
    @DisplayName("on the public surface nothing changes: no answer carries a Cache-Control this task set")
    void thePublicSurfaceIsUnchanged() {
        answers().forEach((kind, request) -> {
            HttpResponse<String> response = send(request.header(RequestSurfaces.HEADER, "public"));
            assertThat(response.statusCode()).as(kind).isEqualTo(Integer.parseInt(kind.substring(0, 3)));
            assertThat(response.headers().firstValue(OperatorResponsesAreNotStored.HEADER)).as(kind).isEmpty();
        });
    }

    @Test
    @DisplayName("the operator edge's word and the single port are the surfaces never stored; the public and unmarked are not")
    void onlyTheOperatorSurfaces() {
        assertThat(Stream.of(Surface.values()).filter(OperatorResponsesAreNotStored::isOperatorSurface))
                .containsExactlyInAnyOrder(Surface.OPERATOR, Surface.SINGLE_PORT);
    }

    @Test
    @DisplayName("it runs before every other filter that may answer, and on every dispatch, the error dispatch included")
    void itRunsFirstAndOnEveryDispatch() {
        // The application's own filters, each ordered by @Order: the ones that may answer before the chain reaches a handler (the
        // size limit's 413 among them). Spring's own are encoding and context filters, which answer nothing.
        Map<String, Integer> orders = new TreeMap<>();
        context.getBeansOfType(jakarta.servlet.Filter.class).values().stream()
                .filter(filter -> filter.getClass().getName().startsWith("com.finapp."))
                .forEach(filter -> orders.put(filter.getClass().getSimpleName(),
                        org.springframework.core.annotation.OrderUtils.getOrder(filter.getClass(), Integer.MAX_VALUE)));
        assertThat(orders).as("the rule read the application's filters").containsKeys("CorrelationFilter", "RequestSizeLimitFilter");
        int ours = orders.get(OperatorResponsesAreNotStored.class.getSimpleName());
        orders.forEach((name, order) -> {
            if (!name.equals("CorrelationFilter") && !name.equals(OperatorResponsesAreNotStored.class.getSimpleName())) {
                assertThat(order).as("%s answers before the header is set", name).isGreaterThan(ours);
            }
        });
        assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                .as("registered by Spring Boot's default for a OncePerRequestFilter - every dispatcher type - not narrowed by hand")
                .noneMatch(registration -> registration.getFilter() instanceof OperatorResponsesAreNotStored);
    }

    @Test
    @DisplayName("nothing else in the application sets Cache-Control or resets a response, so nothing can undo the header")
    void nothingElseSetsItOrResetsAResponse() throws IOException {
        Pattern writes = Pattern.compile("Cache-Control|CACHE_CONTROL|cacheControl\\(|CacheControl\\.|\\.reset\\(\\)|resetBuffer\\(\\)");
        List<Path> mainSources;
        try (Stream<Path> modules = Files.list(repositoryRoot())) {
            mainSources = modules.map(module -> module.resolve("src/main/java")).filter(Files::isDirectory).toList();
        }
        assertThat(mainSources).as("the rule read the modules' main code, not nothing").hasSizeGreaterThan(10);
        List<String> offenders = new ArrayList<>();
        for (Path main : mainSources) {
            try (Stream<Path> sources = Files.walk(main)) {
                sources.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("OperatorResponsesAreNotStored.java"))
                        .filter(path -> writes.matcher(read(path)).find())
                        .forEach(path -> offenders.add(repositoryRoot().relativize(path).toString()));
            }
        }
        assertThat(offenders).as("a Cache-Control written elsewhere, or a reset that drops headers").isEmpty();
    }

    // -----------------------------------------------------------------

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ApiVersion.CURRENT_PREFIX + path))
                .timeout(Duration.ofSeconds(20));
    }

    private HttpRequest.Builder json(String path, String body) {
        return request(path).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
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
