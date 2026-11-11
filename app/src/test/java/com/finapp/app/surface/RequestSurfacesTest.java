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
    @DisplayName("the header's name is read through getHeaders, ignoring case and folding nothing else: here a mock request's map;"
            + " on a server Tomcat's MimeHeaders, which compares names the same way")
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
    @DisplayName("the switch: the single port only with no edge network named and the database on loopback; anywhere else, or unreadable, an edge must have marked the request")
    void theSwitchIsNoEdgeAndALoopbackDatabase() {
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
    @DisplayName("a database reached through a loopback sidecar behind the edge is a deployment: an edge network named refuses staff an unmarked request")
    void aLoopbackSidecarBehindTheEdgeIsADeployment() {
        SurfaceBeans beans = new SurfaceBeans();
        // As production's own file states it: the overlay's variable behind the property, blank by default.
        MockEnvironment behindTheEdge = database("jdbc:postgresql://127.0.0.1:6432/finapp")
                .withProperty(SurfaceBeans.EDGE_NETWORK, "${FINAPP_EDGE_PROXIES:}")
                .withProperty("FINAPP_EDGE_PROXIES", "10.10.0.0/16");
        RequestSurfaces surfaces = beans.requestSurfaces(behindTheEdge);
        assertThat(surfaces.nothingDeployed()).as("a cloud SQL proxy or a PgBouncer on 127.0.0.1, the edge network named").isFalse();
        assertThat(surfaces.of(new MockHttpServletRequest("POST", "/v1/authentications"))).isEqualTo(Surface.UNMARKED);

        MockEnvironment laptop = database("jdbc:postgresql://127.0.0.1:6432/finapp")
                .withProperty(SurfaceBeans.EDGE_NETWORK, "${FINAPP_EDGE_PROXIES:}");
        assertThat(beans.requestSurfaces(laptop).nothingDeployed()).as("the variable unset: the default is blank, as on a laptop").isTrue();
    }

    @Test
    @DisplayName("the refusal meter has its eighteen series before the first request, sign-in's split by whether the password was the identity's, and a surface admitting staff refuses nobody")
    void theMeterIsEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SurfaceRefusalMeters meters = new SurfaceRefusalMeters(registry);
        assertThat(registry.find("finapp.identity.surface.refusal").counters()).hasSize((SurfaceDoor.values().length - 1) * 2 + 2 * 2);
        meters.recordSignIn(Surface.UNMARKED, false);
        meters.recordSignIn(Surface.PUBLIC, true);
        meters.record(SurfaceDoor.MFA_CHALLENGE, Surface.PUBLIC);
        assertThat(count(registry, "authentication", "unmarked", "credential_unproven")).isEqualTo(1.0);
        assertThat(count(registry, "authentication", "public", "credential_proven")).as("the phished password's own series").isEqualTo(1.0);
        assertThat(count(registry, "authentication", "public", "credential_unproven")).isZero();
        assertThat(count(registry, "mfa_challenge", "public", "refused")).isEqualTo(1.0);
        assertThatThrownBy(() -> meters.record(SurfaceDoor.AUTHENTICATION, Surface.PUBLIC))
                .as("a sign-in refusal must say whether the password was right").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> meters.recordSignIn(Surface.OPERATOR, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> meters.record(SurfaceDoor.RECOVERY, Surface.SINGLE_PORT)).isInstanceOf(IllegalArgumentException.class);
    }

    private static double count(SimpleMeterRegistry registry, String type, String kind, String outcome) {
        return registry.get("finapp.identity.surface.refusal").tag("type", type).tag("kind", kind).tag("outcome", outcome).counter().count();
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
