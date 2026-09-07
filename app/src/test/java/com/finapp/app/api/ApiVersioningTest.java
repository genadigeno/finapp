package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ApiVersion;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Every route this platform publishes is under {@code /v1}, and the generated document says so.
 *
 * <p>The controller below declares no version at all - which is the property being tested. If a
 * controller had to write {@code /v1} into its own mapping, the first one that forgot would publish
 * an unversioned route, and an unversioned route can never be changed because there is no second
 * version to move its clients to.
 *
 * <p>This is also what stops {@link OpenApiDocument} being vacuous. With no business endpoints the
 * real document has an empty {@code paths}, so nothing else here proves that path generation works
 * at all; mapping one probe controller and finding it in the document proves both that springdoc
 * sees real mappings and that what it sees carries the prefix.
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiVersioningTest.VersionedController.class)
class ApiVersioningTest {

    /** Mapped without a version. The application supplies it. */
    @com.finapp.app.session.Unauthenticated
    @RestController
    static class VersionedController {

        static final String MAPPING = "/probe/versioned";

        @GetMapping(MAPPING)
        String versioned() {
            return "ok";
        }
    }

    private static final String UNVERSIONED = VersionedController.MAPPING;
    private static final String VERSIONED = ApiVersion.CURRENT_PREFIX + VersionedController.MAPPING;

    @LocalServerPort private int port;

    @Test
    @DisplayName("a controller that declares no version is served under the current one")
    void routesAreVersionedWithoutTheControllerSayingSo() throws Exception {
        assertThat(get(VERSIONED).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the unprefixed path is not also served")
    void theUnversionedPathIsNotAnAlias() throws Exception {
        // The half that matters. A prefix that merely ADDS a route leaves the unversioned one in
        // place, so a client can keep calling a path that carries no contract - and every argument
        // for versioning evaporates while all the tests still pass.
        HttpResponse<String> response = get(UNVERSIONED);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"api.NotFound\"");
    }

    @Test
    @DisplayName("the generated document publishes the versioned path, and only that one")
    void theDocumentCarriesTheVersionedPath() throws Exception {
        JsonNode paths = document().path("paths");

        assertThat(paths.propertyNames())
                .as("springdoc must report the path as it is actually served")
                .contains(VERSIONED)
                .doesNotContain(UNVERSIONED);
    }

    @Test
    @DisplayName("the version applies to our handlers only, not to whatever else is on the classpath")
    void frameworkSuppliedEndpointsAreNotVersioned() throws Exception {
        // springdoc's own endpoint is the case at hand, and it stands for the general one: a
        // library that contributes a controller has not thereby joined this platform's contract.
        // If the prefix were applied to every handler, this call would 404 and the document could
        // not be fetched at all.
        assertThat(get(OpenApiDocument.SPRINGDOC_PATH).statusCode()).isEqualTo(200);
        assertThat(get(ApiVersion.CURRENT_PREFIX + OpenApiDocument.SPRINGDOC_PATH).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("the prefix and the version number are one definition")
    void thePrefixIsDerivedFromTheVersion() {
        // Three constants that must agree. Deriving two of them from the first means a version bump
        // is one edit, rather than one edit and two things to remember.
        assertThat(ApiVersion.CURRENT_PREFIX).isEqualTo("/v" + ApiVersion.CURRENT);
        assertThat(ApiVersion.CURRENT_SEGMENT).isEqualTo("v" + ApiVersion.CURRENT);
    }

    // -----------------------------------------------------------------

    private JsonNode document() throws Exception {
        return OpenApiDocument.parse(OpenApiDocument.publish(get(OpenApiDocument.SPRINGDOC_PATH).body()));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
