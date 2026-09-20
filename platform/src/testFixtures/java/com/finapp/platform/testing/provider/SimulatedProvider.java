package com.finapp.platform.testing.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * An external provider that misbehaves on demand.
 *
 * <p>ADR-0008 requires every adapter to be "contract-tested against simulated failure: timeout,
 * 5xx, malformed response, delayed response, duplicate callback, unknown state", and
 * {@code CLAUDE.md} §Failure Engineering lists the conditions financial correctness has to survive.
 * Building that once is the point: an adapter author reaches for a named failure rather than
 * rebuilding a simulator, and every adapter then fails in the same reproducible ways.
 *
 * <h2>A provider is unreliable in both directions</h2>
 *
 * <p>The harness therefore has two halves, and a simulator with only the first cannot test the
 * failure modes that actually cost money:
 *
 * <ul>
 *   <li><strong>Outbound</strong> — the provider's API, which we call. This is a real HTTP server
 *       on loopback, so an adapter is exercised through its actual transport rather than through a
 *       mocked client. Timeout, unavailability, 5xx, malformed body, delay, unknown state and the
 *       retry sequence all live here.
 *   <li><strong>Inbound</strong> — the provider's callbacks, which it makes to us. A duplicated
 *       webhook (`INV-IDEM-04`) and a late settlement (`INV-SET-03`) are the provider acting on
 *       its own schedule, and no amount of stubbing its API reproduces them.
 * </ul>
 *
 * <h2>No WireMock type appears in this class's signature</h2>
 *
 * <p>Deliberate, and it is ADR-0008's own argument one layer down. An adapter that leaks provider
 * vocabulary couples the domain to a vendor; a test that reaches past this harness to raw stubbing
 * couples the suite to the simulator. What a caller sees is a list of ways a provider fails.
 *
 * <p>Not thread-safe, and not intended to be: one instance simulates one provider for one test.
 * It holds no static state, because {@code noStaticMutableState} sweeps test fixtures too — as
 * {@code P0-TSK-035} discovered the hard way.
 */
public final class SimulatedProvider implements AutoCloseable {

    /**
     * Long enough that a client with any sane timeout gives up first.
     *
     * <p>A hang is not the same failure as a refused connection: a refused connection tells the
     * adapter the request was never processed, and a hang tells it nothing at all. The second is
     * the one that matters, because it is indistinguishable from success in flight.
     */
    private static final Duration LONGER_THAN_ANY_CLIENT_WILL_WAIT = Duration.ofMinutes(5);

    private final WireMockServer server;
    private final HttpClient callbackClient;

    private SimulatedProvider(WireMockServer server, HttpClient callbackClient) {
        this.server = server;
        this.callbackClient = callbackClient;
    }

    /** Starts a provider on a random loopback port. */
    public static SimulatedProvider start() {
        WireMockServer server =
                new WireMockServer(
                        WireMockConfiguration.options()
                                // Port 0: the OS picks. A fixed port makes two tests running at
                                // once a flake that looks like a provider fault.
                                .dynamicPort()
                                .bindAddress("127.0.0.1"));
        server.start();
        return new SimulatedProvider(
                server,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** Where an adapter under test should point its base URL. */
    public String baseUrl() {
        return server.baseUrl();
    }

    // ------------------------------------------------------------------
    // Outbound: what the provider does when we call it
    // ------------------------------------------------------------------

    /** The provider works. The control case, so a failure test can prove it was the failure. */
    public void succeedsWith(String path, int status, String body) {
        server.stubFor(
                any(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /**
     * The provider never answers, so the caller times out.
     *
     * <p>{@code CLAUDE.md}: <em>the request times out</em>. The request <strong>is</strong>
     * received — {@link #requestCount} proves it — which is the entire difficulty: a timeout says
     * nothing about whether the provider acted, so treating it as failure is the most expensive
     * assumption in payments (`INV-LIFE-03`).
     */
    public void neverResponds(String path) {
        server.stubFor(
                any(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(millis(LONGER_THAN_ANY_CLIENT_WILL_WAIT))));
    }

    /**
     * The provider is down.
     *
     * <p>{@code CLAUDE.md}: <em>a provider is unavailable</em>. Distinct from a timeout: a 503 is
     * an answer, and it says the request was not processed.
     */
    public void isUnavailable(String path) {
        failsWith(path, 503);
    }

    /** The provider returns a server error. ADR-0008's "5xx". */
    public void failsWith(String path, int status) {
        server.stubFor(any(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
    }

    /**
     * The provider answers, slowly. ADR-0008's "delayed response".
     *
     * <p>Distinct from {@link #neverResponds} because it succeeds: it is the case where a timeout
     * that is too aggressive turns a working provider into a stream of indeterminate operations.
     */
    public void respondsAfter(String path, Duration delay, int status, String body) {
        server.stubFor(
                any(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)
                                        .withFixedDelay(millis(delay))));
    }

    /**
     * The provider answers 200 with a body that is not what it claims to be.
     *
     * <p>ADR-0008's "malformed response", at the application level: valid HTTP, truncated JSON.
     * This is the shape an adapter actually meets, and the one that turns into a parse exception
     * on a path that had already decided the call succeeded.
     */
    public void respondsWithMalformedBody(String path) {
        server.stubFor(
                any(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"status\": \"AUTH")));
    }

    /**
     * The provider answers with bytes that are not HTTP, then hangs up.
     *
     * <p>The transport-level cousin of {@link #respondsWithMalformedBody}, and a genuinely
     * different failure: an adapter that handles broken JSON can still die here, because the
     * failure happens in the HTTP client before any body exists to parse.
     */
    public void respondsWithGarbage(String path) {
        server.stubFor(
                any(urlEqualTo(path))
                        .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)));
    }

    /**
     * The provider acted, and we never learn the outcome.
     *
     * <p>{@code CLAUDE.md}: <em>the database commits but the response is lost</em>, in its
     * provider form. The request is received and recorded — assert it with {@link #requestCount} —
     * and the connection closes with no response at all. An adapter cannot distinguish this from a
     * request that never arrived, which is precisely why `INV-LIFE-03` requires an explicit
     * indeterminate state rather than a guess in either direction.
     */
    public void receivesTheRequestThenLosesTheResponse(String path) {
        server.stubFor(
                any(urlEqualTo(path)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
    }

    /**
     * The provider fails a number of times and then works.
     *
     * <p>{@code CLAUDE.md}: <em>the client retries</em>. The interesting property is not that a
     * retry eventually succeeds but that the provider saw every attempt — which is why a retry of
     * a non-idempotent operation is a second financial effect, not a second chance.
     */
    public void failsThenSucceeds(String path, int failures, int status, String body) {
        String scenario = "failsThenSucceeds " + path;
        String succeeding = "succeeding";

        for (int attempt = 0; attempt < failures; attempt++) {
            String from = attempt == 0 ? Scenario.STARTED : "failed " + attempt;
            String to = attempt == failures - 1 ? succeeding : "failed " + (attempt + 1);
            server.stubFor(
                    any(urlEqualTo(path))
                            .inScenario(scenario)
                            .whenScenarioStateIs(from)
                            .willReturn(aResponse().withStatus(503))
                            .willSetStateTo(to));
        }

        server.stubFor(
                any(urlEqualTo(path))
                        .inScenario(scenario)
                        .whenScenarioStateIs(failures == 0 ? Scenario.STARTED : succeeding)
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /**
     * The provider answers with a state nobody has mapped.
     *
     * <p>{@code CLAUDE.md}: <em>a provider returns an unknown state</em>. ADR-0008 requires an
     * unrecognised state to map to a modelled indeterminate state, never to success or failure by
     * assumption — and the only way to test that is to send one.
     */
    public void returnsUnknownState(String path, String state) {
        server.stubFor(
                any(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"status\":\"" + state + "\"}")));
    }

    /**
     * How many times the provider was called on this path, by any method.
     *
     * <p>The most important assertion the harness offers, and it is what separates two failures
     * that look identical from the caller's side: a request that never arrived, and a request
     * that arrived and was acted on before the answer was lost.
     */
    public int requestCount(String path) {
        return server.countRequestsMatching(anyRequestedFor(urlEqualTo(path)).build()).getCount();
    }

    /**
     * The values one header carried on every request to this path, in arrival order.
     *
     * <p>One element per request; a request that lacked the header contributes {@code null}, so
     * the list's length always equals {@link #requestCount}. Added by `P5-TSK-003`, because two
     * of the payment port's properties are facts about the wire that no assertion on the
     * caller's own records can see: that a re-dispatched operation presented the <em>same</em>
     * idempotency reference (`INV-PAY-04` lives in the provider's dedupe, which sees headers,
     * not our records), and that a credential is actually sent (a credential nothing sends is
     * decorative). Returns strings only - the no-WireMock-in-the-signature rule, as ever.
     */
    public java.util.List<String> headerValues(String path, String headerName) {
        return server.findAll(anyRequestedFor(urlEqualTo(path))).stream()
                .map(request -> request.getHeader(headerName))
                .collect(java.util.stream.Collectors.toList());
    }

    /** Forgets every stub and every recorded request. */
    public void reset() {
        server.resetAll();
    }

    // ------------------------------------------------------------------
    // Inbound: what the provider does to us
    // ------------------------------------------------------------------

    /**
     * The provider calls us back.
     *
     * <p>The other half of the harness, and the half a stubbed provider API cannot reach. A
     * webhook is the provider acting on its own schedule, so its failure modes — duplication,
     * lateness, arrival out of order — are all about <em>when and how often</em> it calls, not
     * about what it answers.
     *
     * @return the status the receiver returned for the last delivery
     */
    public int deliverCallback(URI target, String body) {
        return deliverCallback(target, body, 1);
    }

    /**
     * The provider calls us back {@code times} times with the identical payload.
     *
     * <p>{@code CLAUDE.md}: <em>a webhook is duplicated</em>. At-least-once is the norm, so this
     * is the expected case rather than an edge one, and `INV-IDEM-04` requires the second delivery
     * to produce no second effect.
     *
     * @return the status the receiver returned for the last delivery
     */
    public int deliverCallback(URI target, String body, int times) {
        int status = -1;
        for (int delivery = 0; delivery < times; delivery++) {
            status = send(target, body);
        }
        return status;
    }

    /**
     * The provider calls us back, signing the body — HMAC-SHA256, hex, in
     * {@value #SIGNATURE_HEADER}.
     *
     * <p>The scheme is <em>this</em> simulated provider's wire format (`P2-TSK-011`), computed
     * here with JDK primitives because the harness cannot depend on the module that verifies
     * it; the receiver's test reconciles the header name so the two cannot drift.
     *
     * @return the status the receiver returned for the last delivery
     */
    public int deliverSignedCallback(URI target, String body, byte[] signingSecret, int times) {
        int status = -1;
        for (int delivery = 0; delivery < times; delivery++) {
            status = send(target, body, signatureOf(body, signingSecret));
        }
        return status;
    }

    /** The header {@link #deliverSignedCallback} delivers its signature in. */
    public static final String SIGNATURE_HEADER = "X-Provider-Signature";

    private static String signatureOf(String body, byte[] signingSecret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(signingSecret, "HmacSHA256"));
            return java.util.HexFormat.of()
                    .formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException
                | java.security.InvalidKeyException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    /**
     * The provider calls us back later than we expected it to.
     *
     * <p>{@code CLAUDE.md}: <em>settlement arrives late</em>, and <em>an event is late or
     * missing</em> in its provider form. `INV-SET-03` requires late settlement to be processed
     * rather than discarded as stale, which is a property no stub of the provider's API can
     * exercise.
     */
    public int deliverCallbackAfter(URI target, String body, Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while delaying a provider callback", e);
        }
        return send(target, body);
    }

    /**
     * A duration as milliseconds, rejecting rather than wrapping.
     *
     * <p>WireMock's delay is an {@code int}, and a plain cast is silent: {@code Duration.ofDays(30)}
     * becomes {@code -1702967296}, so "delay this for a month" turns into a negative delay and the
     * stub does something nobody asked for. Same reasoning as {@code INV-MON-06} — overflow is
     * rejected, never wrapped — applied outside money, where the cost is a confusing test rather
     * than a wrong balance.
     */
    private static int millis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    private int send(URI target, String body) {
        return send(target, body, null);
    }

    private int send(URI target, String body, String signature) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(target)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            builder.header(SIGNATURE_HEADER, signature);
        }
        HttpRequest request = builder.build();
        try {
            return callbackClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new UncheckedIOException("Provider callback to " + target + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted delivering a provider callback", e);
        }
    }

    @Override
    public void close() {
        server.stop();
    }
}
