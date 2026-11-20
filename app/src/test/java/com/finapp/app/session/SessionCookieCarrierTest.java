package com.finapp.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.authentication.AuthenticatedSession;
import com.finapp.app.mfa.ElevatedSession;
import com.finapp.app.surface.RequestSurfaces;
import com.finapp.app.surface.Surface;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.SessionToken;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("the customer session in an HttpOnly cookie on the public surface, and its CSRF defence (X-TSK-029, ADR-0156)")
class SessionCookieCarrierTest {

    private static final String PUBLIC_ORIGIN = "https://app.finapp.example";
    private static final String TOKEN = "tok-live-session";

    /** A deployed instance: an unmarked request is public, and the allow-list is the configured origin alone. */
    private static final SessionCookieCarrier CARRIER =
            new SessionCookieCarrier(new RequestSurfaces(false), List.of(PUBLIC_ORIGIN));

    private static MockHttpServletRequest request(String method, Surface surface) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/transfers");
        if (surface == Surface.PUBLIC || surface == Surface.OPERATOR) {
            request.addHeader(RequestSurfaces.HEADER, surface.tag());
        }
        return request;
    }

    private static MockHttpServletRequest withCookie(MockHttpServletRequest request) {
        request.setCookies(new Cookie(SessionCookieCarrier.COOKIE, TOKEN));
        return request;
    }

    private static MockHttpServletRequest sameSite(MockHttpServletRequest request) {
        request.addHeader(SessionCookieCarrier.CSRF_HEADER, "1");
        request.addHeader("Origin", PUBLIC_ORIGIN);
        return request;
    }

    private static void assertCsrfRejected(MockHttpServletRequest request) {
        assertThatThrownBy(() -> CARRIER.refuseCrossSite(request))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.CSRF_REJECTED));
    }

    // -----------------------------------------------------------------

    @Nested
    @DisplayName("the cookie itself (decision 1)")
    class TheCookie {

        @Test
        @DisplayName("is __Host- prefixed, host-only, Path=/, Secure, HttpOnly, SameSite=Strict, with no lifetime of its own")
        void isIssuedLockedDown() {
            String issued = SessionCookieCarrier.issued(TOKEN);
            assertThat(issued)
                    .startsWith("__Host-fa_session=" + TOKEN + ";")
                    .contains("; Path=/;", "; Secure", "; HttpOnly", "; SameSite=Strict")
                    .doesNotContainIgnoringCase("Domain")
                    .doesNotContainIgnoringCase("Max-Age")
                    .doesNotContainIgnoringCase("Expires");
        }

        @Test
        @DisplayName("is cleared with Max-Age=0 and the same attributes, so the browser replaces the one it holds")
        void isClearedInPlace() {
            assertThat(SessionCookieCarrier.cleared())
                    .startsWith("__Host-fa_session=;")
                    .contains("; Path=/;", "; Max-Age=0", "; Secure", "; HttpOnly", "; SameSite=Strict");
        }
    }

    @Nested
    @DisplayName("the CSRF check, before any handler (decision 5)")
    class TheCsrfCheck {

        @Test
        @DisplayName("a cross-site form post riding the cookie is refused: no custom header, a foreign Origin")
        void aCrossSiteFormPostIsRefused() {
            MockHttpServletRequest forged = withCookie(request("POST", Surface.PUBLIC));
            forged.addHeader("Origin", "https://evil.example");
            assertCsrfRejected(forged);
        }

        @Test
        @DisplayName("a cross-origin fetch without the custom header is refused, even from the right Origin")
        void aMissingHeaderIsRefused() {
            MockHttpServletRequest request = withCookie(request("POST", Surface.PUBLIC));
            request.addHeader("Origin", PUBLIC_ORIGIN);
            assertCsrfRejected(request);
        }

        @Test
        @DisplayName("a foreign Origin is refused, even with the custom header")
        void aForeignOriginIsRefused() {
            MockHttpServletRequest request = withCookie(request("DELETE", Surface.PUBLIC));
            request.addHeader(SessionCookieCarrier.CSRF_HEADER, "1");
            request.addHeader("Origin", "https://app.finapp.example.evil.example");
            assertCsrfRejected(request);
        }

        @Test
        @DisplayName("a missing Origin is refused")
        void aMissingOriginIsRefused() {
            MockHttpServletRequest request = withCookie(request("PATCH", Surface.PUBLIC));
            request.addHeader(SessionCookieCarrier.CSRF_HEADER, "1");
            assertCsrfRejected(request);
        }

        @Test
        @DisplayName("a wrong value of the custom header is refused")
        void aWrongHeaderValueIsRefused() {
            MockHttpServletRequest request = withCookie(request("PUT", Surface.PUBLIC));
            request.addHeader(SessionCookieCarrier.CSRF_HEADER, "true");
            request.addHeader("Origin", PUBLIC_ORIGIN);
            assertCsrfRejected(request);
        }

        @Test
        @DisplayName("an opted-in sign-in from a foreign Origin is refused: no login CSRF")
        void aForcedSignInIsRefused() {
            MockHttpServletRequest request = request("POST", Surface.PUBLIC);
            request.addHeader(SessionCookieCarrier.CARRIER_HEADER, "cookie");
            request.addHeader(SessionCookieCarrier.CSRF_HEADER, "1");
            request.addHeader("Origin", "https://evil.example");
            assertCsrfRejected(request);
        }

        @Test
        @DisplayName("the application's own request passes: the header, and its Origin in any case and with a trailing slash")
        void theOwnApplicationPasses() {
            assertThatCode(() -> CARRIER.refuseCrossSite(sameSite(withCookie(request("POST", Surface.PUBLIC)))))
                    .doesNotThrowAnyException();
            MockHttpServletRequest shouted = withCookie(request("POST", Surface.PUBLIC));
            shouted.addHeader(SessionCookieCarrier.CSRF_HEADER, "1");
            shouted.addHeader("Origin", "HTTPS://App.Finapp.Example/");
            assertThatCode(() -> CARRIER.refuseCrossSite(shouted)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a safe method is not checked: SameSite=Strict already keeps the cookie off a cross-site read")
        void aSafeMethodIsNotChecked() {
            assertThatCode(() -> CARRIER.refuseCrossSite(withCookie(request("GET", Surface.PUBLIC))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a bearer request with neither cookie nor opt-in is untouched: the console and native clients")
        void aBearerRequestIsUntouched() {
            MockHttpServletRequest request = request("POST", Surface.PUBLIC);
            request.addHeader("Authorization", "Bearer " + TOKEN);
            assertThatCode(() -> CARRIER.refuseCrossSite(request)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("off the public surface the cookie is not read, so it is not checked either")
        void theOperatorSurfaceIsNotChecked() {
            assertThatCode(() -> CARRIER.refuseCrossSite(withCookie(request("POST", Surface.OPERATOR))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a deployed instance with no allow-list refuses every cookie request: fail closed (decision 6)")
        void noAllowListRefusesEverything() {
            SessionCookieCarrier unconfigured =
                    new SessionCookieCarrier(new RequestSurfaces(false), SessionCookieCarrier.allowedOrigins("", false));
            assertThatThrownBy(() -> unconfigured.refuseCrossSite(sameSite(withCookie(request("POST", Surface.PUBLIC)))))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("the allow-list (decision 6)")
    class TheAllowList {

        @Test
        @DisplayName("configured: exactly the named origins, comma-separated")
        void configuredIsExact() {
            assertThat(SessionCookieCarrier.allowedOrigins("https://a.example, https://b.example", false))
                    .containsExactly("https://a.example", "https://b.example");
        }

        @Test
        @DisplayName("unset where nothing is deployed: the customer application's local origins; deployed: none")
        void unsetDependsOnTheDeployment() {
            assertThat(SessionCookieCarrier.allowedOrigins(null, true))
                    .containsExactly("http://localhost:5180", "http://localhost:4180");
            assertThat(SessionCookieCarrier.allowedOrigins("  ", false)).isEmpty();
        }
    }

    @Nested
    @DisplayName("which carrier a request presents (decisions 2 and 7)")
    class ThePresentedToken {

        @Test
        @DisplayName("the cookie is read on the public surface")
        void theCookieIsReadOnPublic() {
            assertThat(hashed(SessionAuthenticationInterceptor.presentedToken(withCookie(request("GET", Surface.PUBLIC)), Surface.PUBLIC)))
                    .contains(SessionToken.of(TOKEN).hash().expose());
        }

        @Test
        @DisplayName("the cookie is ignored on the operator surface, an unmarked request and the single port")
        void theCookieIsIgnoredElsewhere() {
            for (Surface surface : List.of(Surface.OPERATOR, Surface.UNMARKED, Surface.SINGLE_PORT)) {
                assertThat(hashed(SessionAuthenticationInterceptor.presentedToken(withCookie(request("GET", surface)), surface)))
                        .as("the cookie on %s", surface)
                        .isEqualTo(Optional.empty());
            }
        }

        @Test
        @DisplayName("on the operator surface the bearer token alone authenticates, a cookie beside it notwithstanding")
        void theBearerActsOnTheOperatorSurface() {
            MockHttpServletRequest request = withCookie(request("GET", Surface.OPERATOR));
            request.addHeader("Authorization", "Bearer operator-token");
            assertThat(hashed(SessionAuthenticationInterceptor.presentedToken(request, Surface.OPERATOR)))
                    .contains(SessionToken.of("operator-token").hash().expose());
        }

        @Test
        @DisplayName("both carriers at once on the public surface are refused 401: which session acts is never a guess")
        void bothCarriersAreRefused() {
            MockHttpServletRequest request = withCookie(request("GET", Surface.PUBLIC));
            request.addHeader("Authorization", "Bearer another-token");
            assertThatThrownBy(() -> SessionAuthenticationInterceptor.presentedToken(request, Surface.PUBLIC))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.UNAUTHENTICATED));
        }

        @Test
        @DisplayName("a bearer token alone is today's behaviour, on every surface")
        void aBearerAloneIsUnchanged() {
            for (Surface surface : Surface.values()) {
                MockHttpServletRequest request = request("GET", surface);
                request.addHeader("Authorization", "Bearer " + TOKEN);
                assertThat(hashed(SessionAuthenticationInterceptor.presentedToken(request, surface)))
                        .contains(SessionToken.of(TOKEN).hash().expose());
            }
        }

        /** A token has no value equality (it wraps a {@code Sensitive}); its hash is what the store looks up. */
        private static Optional<String> hashed(Optional<SessionToken> token) {
            return token.map(presented -> presented.hash().expose());
        }
    }

    @Nested
    @DisplayName("issuing the cookie on sign-in, step-up and password change (decisions 3 and 4)")
    class Issuing {

        private final SessionCookieAdvice advice = new SessionCookieAdvice(CARRIER);

        private Object answer(MockHttpServletRequest request, MockHttpServletResponse response, Object body) {
            // The server response's headers write through to the servlet response: read them there, once.
            return advice.beforeBodyWrite(
                    body, null, null, null, new ServletServerHttpRequest(request), new ServletServerHttpResponse(response));
        }

        private MockHttpServletRequest optedIn(Surface surface) {
            MockHttpServletRequest request = request("POST", surface);
            request.addHeader(SessionCookieCarrier.CARRIER_HEADER, "cookie");
            return request;
        }

        @Test
        @DisplayName("an opted-in sign-in on the public surface gets the cookie, and a body without the token")
        void anOptedInSignInGetsTheCookie() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            Object body =
                    answer(optedIn(Surface.PUBLIC), response,
                            new AuthenticatedSession(TOKEN, "PASSWORD", Instant.parse("2026-11-21T10:00:00Z")));
            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).containsExactly(SessionCookieCarrier.issued(TOKEN));
            assertThat(body).isEqualTo(new AuthenticatedSession(null, "PASSWORD", Instant.parse("2026-11-21T10:00:00Z")));
        }

        @Test
        @DisplayName("a step-up's rotated token replaces the cookie the same way")
        void aStepUpRotatesTheCookie() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            Object body = answer(optedIn(Surface.PUBLIC), response, new ElevatedSession("rotated", "MULTI_FACTOR", null));
            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).containsExactly(SessionCookieCarrier.issued("rotated"));
            assertThat(((ElevatedSession) body).sessionToken()).isNull();
        }

        @Test
        @DisplayName("without the opt-in the answer is today's: the token in the body, no cookie")
        void withoutTheOptInNothingChanges() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AuthenticatedSession issued = new AuthenticatedSession(TOKEN, "PASSWORD", null);
            assertThat(answer(request("POST", Surface.PUBLIC), response, issued)).isSameAs(issued);
            assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        }

        @Test
        @DisplayName("the opt-in is ignored on the operator surface and the single port: staff never receive the cookie")
        void staffNeverReceiveTheCookie() {
            SessionCookieCarrier local = new SessionCookieCarrier(new RequestSurfaces(true), SessionCookieCarrier.LOCAL_ORIGINS);
            SessionCookieAdvice localAdvice = new SessionCookieAdvice(local);
            for (Surface surface : List.of(Surface.OPERATOR, Surface.SINGLE_PORT)) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                ElevatedSession issued = new ElevatedSession(TOKEN, "MULTI_FACTOR", null);
                ServletServerHttpResponse server = new ServletServerHttpResponse(response);
                Object written = localAdvice.beforeBodyWrite(
                        issued, null, null, null, new ServletServerHttpRequest(optedIn(surface)), server);
                assertThat(written).as("on %s", surface).isSameAs(issued);
                assertThat(server.getHeaders().get(HttpHeaders.SET_COOKIE)).as("on %s", surface).isNull();
            }
        }
    }
}
