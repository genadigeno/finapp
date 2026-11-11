package com.finapp.app.surface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Which surface served a request, read from the edge's marker, and what a request without a usable one is (`P15-TSK-042`;
 * {@code THREAT_MODEL.md} T3, T31).
 *
 * <p>The proxy's half - that a client's value never survives the edge - is {@code infra/scripts/edge-check.sh}'s, on the pinned image.
 * This is the application's half: what it believes, and that nothing but the edge's exact word is believed.
 */
@DisplayName("the surface a request arrived on, read from the edge's marker and nothing else (P15-TSK-042)")
class RequestSurfacesTest {

    private static final RequestSurfaces DEPLOYED = new RequestSurfaces(false);
    private static final RequestSurfaces NOTHING_DEPLOYED = new RequestSurfaces(true);

    @Test
    @DisplayName("the edge's two words, exactly, are the two surfaces wherever the application runs")
    void theEdgesWordsAreTheSurfaces() {
        for (RequestSurfaces surfaces : List.of(DEPLOYED, NOTHING_DEPLOYED)) {
            assertThat(surfaces.of(marked("public"))).isEqualTo(Surface.PUBLIC);
            assertThat(surfaces.of(marked("operator"))).isEqualTo(Surface.OPERATOR);
        }
    }

    @Test
    @DisplayName("behind the edge, a request with no marker is unmarked, and staff are refused on it as on the public surface")
    void aMissingMarkerFailsClosedBehindTheEdge() {
        assertThat(DEPLOYED.of(new MockHttpServletRequest("POST", "/v1/authentications"))).isEqualTo(Surface.UNMARKED);
        assertThat(Surface.UNMARKED.admitsRoleHolders()).as("a missing marker admits no staff").isFalse();
    }

    @Test
    @DisplayName("where nothing is deployed the one port is both surfaces, as before the edge, and a request needs no marker")
    void whereNothingIsDeployedTheOnePortIsBoth() {
        assertThat(NOTHING_DEPLOYED.of(new MockHttpServletRequest("POST", "/v1/authentications"))).isEqualTo(Surface.SINGLE_PORT);
        assertThat(Surface.SINGLE_PORT.admitsRoleHolders()).isTrue();
    }

    @Test
    @DisplayName("anything but one exact word is no marker: other cases, both words, a list, padding, empty, two values, an underscore spelling")
    void anUnusableMarkerIsNoMarker() {
        List<MockHttpServletRequest> unusable = List.of(
                marked("Operator"),
                marked("OPERATOR"),
                marked("operator, public"),
                marked("operator,public"),
                marked(" operator"),
                marked("operator "),
                marked(""),
                marked("internal"),
                marked("operator", "operator"),
                marked("public", "operator"),
                underscored("operator"));
        for (MockHttpServletRequest request : unusable) {
            String described = describe(request);
            assertThat(DEPLOYED.of(request)).as("behind the edge: %s", described).isEqualTo(Surface.UNMARKED);
            assertThat(NOTHING_DEPLOYED.of(request)).as("where nothing is deployed: %s", described).isEqualTo(Surface.SINGLE_PORT);
        }
    }

    @Test
    @DisplayName("the header's name is matched as Tomcat matches every name, ignoring case and nothing else")
    void theNameIsCaseInsensitive() {
        MockHttpServletRequest lower = new MockHttpServletRequest("POST", "/v1/authentications");
        lower.addHeader("x-finapp-surface", "operator");
        assertThat(DEPLOYED.of(lower)).isEqualTo(Surface.OPERATOR);
    }

    @Test
    @DisplayName("only the operator surface and the single port admit staff: any surface added later is refused until decided")
    void onlyTwoSurfacesAdmitStaff() {
        assertThat(Arrays.stream(Surface.values()).filter(Surface::admitsRoleHolders))
                .containsExactlyInAnyOrder(Surface.OPERATOR, Surface.SINGLE_PORT);
    }

    @Test
    @DisplayName("the switch is the database's address: on loopback nothing is deployed, anywhere else or unreadable an edge must have marked the request")
    void theSwitchIsTheDatabasesAddress() {
        SurfaceBeans beans = new SurfaceBeans();
        assertThat(beans.requestSurfaces(database("jdbc:postgresql://127.0.0.1:5432/finapp")).nothingDeployed()).isTrue();
        assertThat(beans.requestSurfaces(database("jdbc:postgresql://localhost:15432/finapp")).nothingDeployed()).isTrue();
        assertThat(beans.requestSurfaces(database("jdbc:postgresql://finapp-postgres.finapp.svc:5432/finapp")).nothingDeployed()).isFalse();
        assertThat(beans.requestSurfaces(database("jdbc:postgresql://localhost:5432,10.0.0.7:5432/finapp")).nothingDeployed())
                .as("one host off loopback is a deployment").isFalse();
        assertThat(beans.requestSurfaces(database("not a url")).nothingDeployed()).as("unreadable is not loopback").isFalse();
        assertThat(beans.requestSurfaces(new MockEnvironment()).nothingDeployed()).as("no address at all").isFalse();
    }

    @Test
    @DisplayName("the refusal meter has its sixteen series before the first request, and a surface admitting staff refuses nobody")
    void theMeterIsEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SurfaceRefusalMeters meters = new SurfaceRefusalMeters(registry);
        assertThat(registry.find("finapp.identity.surface.refusal").counters()).hasSize(SurfaceDoor.values().length * 2);
        meters.record(SurfaceDoor.AUTHENTICATION, Surface.UNMARKED);
        assertThat(registry.get("finapp.identity.surface.refusal").tag("type", "authentication").tag("kind", "unmarked").counter().count())
                .isEqualTo(1.0);
        assertThatThrownBy(() -> meters.record(SurfaceDoor.AUTHENTICATION, Surface.OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> meters.record(SurfaceDoor.AUTHENTICATION, Surface.SINGLE_PORT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest marked(String... values) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/authentications");
        for (String value : values) {
            request.addHeader(RequestSurfaces.HEADER, value);
        }
        return request;
    }

    private static MockHttpServletRequest underscored(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/authentications");
        request.addHeader("X_Finapp_Surface", value);
        return request;
    }

    private static String describe(MockHttpServletRequest request) {
        StringBuilder described = new StringBuilder();
        for (String name : java.util.Collections.list(request.getHeaderNames())) {
            described.append(name).append('=').append(java.util.Collections.list(request.getHeaders(name))).append(' ');
        }
        return described.toString().trim();
    }

    private static MockEnvironment database(String url) {
        return new MockEnvironment().withProperty("spring.datasource.url", url);
    }
}
