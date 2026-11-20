package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The end-to-end battery's tools ({@code P17-TST-001}; {@code infra/scripts/kind-rehearsal/Battery.java}, which the battery scope of
 * {@code kind-rehearsal.sh} runs): the proxy between the console's browser and the operator edge, the journal's judgements of each
 * scenario, and the reader of the console's Playwright report. Compiled once here and run as processes, as the harness runs them.
 *
 * <p>The proxy runs in its loopback mode - plain HTTP on 127.0.0.1 - against an upstream this test serves: a development machine's
 * antivirus re-signs every TLS connection, loopback included ({@code CURRENT_STATE.md}, Local Environment Prerequisites), so a TLS
 * handshake here would test the antivirus. The TLS material the run's proxy uses is held by {@code tls-check}, which the harness runs
 * before every proxy, and by a keystore made here with the JDK's keytool. The cluster run itself is owed to the owner.
 *
 * <p>Hermetic: a JDK, files and loopback sockets.
 */
@Tag("architecture")
@DisplayName("the battery's proxy drops one answer after the backend committed, journals without secrets, and its judgements and report"
        + " reader refuse what is not a pass (P17-TST-001)")
class BatteryProxyTest {

    private static final String TOOL = "infra/scripts/kind-rehearsal/Battery.java";
    private static final String TOKEN = "tok-" + "a1b2c3d4".repeat(6);
    private static final String APPROVAL = "^/v1/operator/refund-proposals/[^/]+/approval$";
    private static final String CSP = "default-src 'none'; script-src 'self'; frame-ancestors 'none'";

    private static Path classes;
    private static HttpServer upstream;
    /** Every request the upstream saw: method, path, and the headers this test asks about. */
    private static final List<String> seen = new CopyOnWriteArrayList<>();
    /** The refund approvals the upstream executed, by key: one effect per key, its answer stored and replayed. */
    private static final Map<String, String> executed = new ConcurrentHashMap<>();
    private static final AtomicInteger refunds = new AtomicInteger();

    @TempDir
    Path work;

    private Process proxy;
    private int port;

    private record Run(int status, String out) {}

    @BeforeAll
    static void compileTheToolAndServeAnUpstream(@TempDir Path compiled) throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        classes = compiled;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK runs the tests").isNotNull();
        assertThat(compiler.run(null, null, null, "--release", "21", "-d", classes.toString(), root.resolve(TOOL).toString()))
                .as("Battery.java compiles").isZero();
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        upstream.createContext("/", BatteryProxyTest::serve);
        upstream.start();
    }

    @AfterAll
    static void stopTheUpstream() {
        upstream.stop(0);
    }

    @AfterEach
    void stopTheProxy() throws InterruptedException {
        if (proxy != null) {
            proxy.destroyForcibly();
            proxy.waitFor(30, TimeUnit.SECONDS);
        }
    }

    /** The upstream: a sign-in, the refund approval's door with its idempotency, a refused approval, the console's page, a read. */
    private static void serve(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        seen.add(method + " " + path + " key=" + key + " accept-encoding=" + exchange.getRequestHeaders().getFirst("Accept-Encoding")
                + " authorization=" + (exchange.getRequestHeaders().containsKey("Authorization") ? "present" : "absent"));
        int status = 200;
        String body;
        if ("POST".equals(method) && path.equals("/v1/authentications")) {
            status = 201;
            body = "{\"sessionToken\":\"" + TOKEN + "\",\"assurance\":\"PASSWORD\",\"expiresAt\":\"2026-11-20T18:00:00Z\"}";
        } else if ("POST".equals(method) && path.equals("/v1/operator/refund-proposals/p-refused/approval")) {
            status = 409;
            body = "{\"code\":\"payments.RefundProposalAlreadyDecided\"}";
        } else if ("POST".equals(method) && path.matches(APPROVAL)) {
            status = 201;
            // One effect per key: the first time the refund is made; a retry under the key answers the stored outcome.
            body = executed.computeIfAbsent(path + "|" + key, k -> "{\"refundId\":\"r-" + refunds.incrementAndGet() + "\",\"status\":\"COMPLETED\"}");
        } else if (path.startsWith("/console")) {
            exchange.getResponseHeaders().add("Content-Security-Policy", CSP);
            body = "<!doctype html><title>console</title>";
        } else {
            body = "{\"ok\":true}";
        }
        exchange.getResponseHeaders().add("X-Correlation-Id", "corr-" + seen.size());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------------------------------------- the proxy

    @Test
    @DisplayName("a request is forwarded and answered as the edge answered it - the console's policy once and unchanged - with Connection:"
            + " close, the connection then closed, Accept-Encoding not forwarded, and one journal line")
    void aRequestIsForwardedUnchangedAndTheConnectionClosed() throws Exception {
        start();
        String answer = raw("GET /console/ HTTP/1.1\r\nHost: operator.finapp.test\r\nAccept-Encoding: gzip\r\n\r\n");
        assertThat(answer).startsWith("HTTP/1.1 200");
        assertThat(headerLines(answer, "content-security-policy")).as("the edge's policy, once, byte for byte").containsExactly(CSP);
        assertThat(headerLines(answer, "connection")).containsExactly("close");
        assertThat(answer).endsWith("<!doctype html><title>console</title>");
        assertThat(seen).anyMatch(line -> line.startsWith("GET /console/ ") && line.contains("accept-encoding=null"));
        List<String[]> journal = journal(1);
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0)).containsSequence("GET", "/console/", "-", "-", "200", "delivered");
    }

    @Test
    @DisplayName("the armed request is forwarded, the backend executes it, and the client hears nothing; the retry under the same key gets"
            + " the original answer, the backend's one effect replayed; the journal's drop judgement says so")
    void theArmedAnswerIsDroppedAfterTheBackendExecuted() throws Exception {
        start("drop", "POST", APPROVAL);
        int before = refunds.get();
        String lost = raw(post("/v1/operator/refund-proposals/p-1/approval", "k-drop-1"));
        assertThat(lost).as("not one byte of the answer reaches the client").isEmpty();
        assertThat(refunds.get()).as("but the backend executed it").isEqualTo(before + 1);
        String retried = raw(post("/v1/operator/refund-proposals/p-1/approval", "k-drop-1"));
        assertThat(retried).startsWith("HTTP/1.1 201").endsWith("{\"refundId\":\"r-" + (before + 1) + "\",\"status\":\"COMPLETED\"}");
        assertThat(refunds.get()).as("exactly one refund").isEqualTo(before + 1);
        List<String[]> journal = journal(2);
        assertThat(journal).extracting(line -> line[7]).containsExactly("dropped", "delivered");
        assertThat(journal.get(0)[8]).as("the same body both times").isEqualTo(journal.get(1)[8]).hasSize(16);
        Run judged = tool("journal", "drop", work.resolve("journal.tsv").toString(), "POST", APPROVAL);
        assertThat(judged.status()).as(judged.out()).isZero();
        assertThat(judged.out()).contains("JOURNAL drop path=/v1/operator/refund-proposals/p-1/approval key=k-drop-1 status=201 retries=1");
    }

    @Test
    @DisplayName("a refusal is delivered and the rule stays armed for an answer that executed; after its one drop the proxy delivers all")
    void onlyAnExecutedAnswerIsDroppedAndOnlyOnce() throws Exception {
        start("drop", "POST", APPROVAL);
        assertThat(raw(post("/v1/operator/refund-proposals/p-refused/approval", "k-refused"))).startsWith("HTTP/1.1 409");
        assertThat(raw(post("/v1/operator/refund-proposals/p-2/approval", "k-2"))).isEmpty();
        assertThat(raw(post("/v1/operator/refund-proposals/p-3/approval", "k-3"))).startsWith("HTTP/1.1 201");
        assertThat(journal(3)).extracting(line -> line[6] + " " + line[7])
                .containsExactly("409 delivered", "201 dropped", "201 delivered");
        Run judged = tool("journal", "drop", work.resolve("journal.tsv").toString(), "POST", APPROVAL);
        assertThat(judged.status()).as("p-2's key never asked again: no retry, no pass").isEqualTo(1);
        assertThat(judged.out()).contains("REFUSED the dropped request's key k-2 was never asked again");
    }

    @Test
    @DisplayName("a session token learnt from a sign-in answer or a bearer header is flagged in a URL or a cookie, and never written: the"
            + " path is withheld and the journal's tokens judgement refuses")
    void aTokenInAUrlOrACookieIsFlaggedAndNeverWritten() throws Exception {
        start();
        assertThat(raw("POST /v1/authentications HTTP/1.1\r\nHost: operator.finapp.test\r\nContent-Length: 2\r\n\r\n{}"))
                .startsWith("HTTP/1.1 201").contains(TOKEN);
        raw("GET /v1/operator/me HTTP/1.1\r\nHost: operator.finapp.test\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\n");
        journal(2);
        Run clean = tool("journal", "tokens", work.resolve("journal.tsv").toString());
        assertThat(clean.status()).as(clean.out()).isZero();
        assertThat(clean.out()).contains("JOURNAL tokens lines=2 token-in-url=0 token-in-cookie=0");
        raw("GET /v1/operator/" + TOKEN + "/x?session=" + TOKEN + " HTTP/1.1\r\nHost: operator.finapp.test\r\n\r\n");
        raw("GET /v1/operator/me HTTP/1.1\r\nHost: operator.finapp.test\r\nCookie: s=" + TOKEN + "\r\n\r\n");
        List<String[]> journal = journal(4);
        assertThat(journal.get(2)).containsSequence("GET", "<withheld>", "present");
        assertThat(journal.get(2)[10]).isEqualTo("yes");
        assertThat(journal.get(3)[11]).isEqualTo("yes");
        String text = Files.readString(work.resolve("journal.tsv"), StandardCharsets.UTF_8);
        assertThat(text).as("no token, no bearer, no cookie value, no body").doesNotContain(TOKEN).doesNotContain("Bearer")
                .doesNotContain("s=").doesNotContain("sessionToken");
        assertThat(seen).anyMatch(line -> line.startsWith("GET /v1/operator/me ") && line.endsWith("authorization=present"));
        Run refused = tool("journal", "tokens", work.resolve("journal.tsv").toString());
        assertThat(refused.status()).isEqualTo(1);
        assertThat(refused.out()).contains("REFUSED 1 requests carried a session token in their URL and 1 in a cookie");
    }

    @Test
    @DisplayName("an upstream that cannot be reached is answered 502 and journaled upstream-failed, never silently dropped")
    void anUnreachableUpstreamIsAnswered() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        startAgainst("http://127.0.0.1:" + closed);
        assertThat(raw("GET /v1/operator/me HTTP/1.1\r\nHost: operator.finapp.test\r\n\r\n")).startsWith("HTTP/1.1 502");
        assertThat(journal(1).get(0)).containsSequence("-", "upstream-failed");
    }

    @Test
    @DisplayName("the plain mode is the loopback's alone: without it an http upstream is refused, and with it an https one or another host")
    void thePlainModeIsLoopbackOnly() throws Exception {
        String origin = "http://127.0.0.1:" + upstream.getAddress().getPort();
        Run noMode = proxyRun(Map.of("BATTERY_PROXY_KEYSTORE_PASSWORD", "x"), origin);
        assertThat(noMode.status()).isNotZero();
        assertThat(noMode.out()).contains("the upstream is an https origin with no path, not " + origin);
        for (String upstreamOrigin : List.of("https://127.0.0.1:1", "http://localhost:1", "http://127.0.0.1:1/v1")) {
            Run refused = proxyRun(Map.of("BATTERY_PROXY_PLAIN", "loopback"), upstreamOrigin);
            assertThat(refused.status()).as(upstreamOrigin).isNotZero();
            assertThat(refused.out()).as(upstreamOrigin).contains("the upstream is an http origin with no path on 127.0.0.1");
        }
    }

    @Test
    @DisplayName("tls-check accepts the run's material - one private key naming the operator host, a CA - and refuses the wrong host, the"
            + " wrong password and a CA file with no certificate")
    void theTlsMaterialIsCheckedBeforeTheProxy() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assertThat(exec(List.of(keytool.toString(), "-genkeypair", "-alias", "edge", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=operator.finapp.test", "-ext", "SAN=dns:operator.finapp.test", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", work.resolve("proxy.p12").toString(), "-storepass", "battery-test", "-keypass", "battery-test"), Map.of())
                .status()).isZero();
        Run exported = exec(List.of(keytool.toString(), "-exportcert", "-rfc", "-alias", "edge", "-keystore",
                work.resolve("proxy.p12").toString(), "-storepass", "battery-test"), Map.of());
        Files.writeString(work.resolve("edge-ca.crt"), exported.out().substring(exported.out().indexOf("-----BEGIN")), StandardCharsets.UTF_8);
        Run good = tls(Map.of("BATTERY_PROXY_KEYSTORE_PASSWORD", "battery-test"), "operator.finapp.test");
        assertThat(good.status()).as(good.out()).isZero();
        assertThat(good.out()).contains("TLS key=EC names=operator.finapp.test not-after=");
        assertThat(tls(Map.of("BATTERY_PROXY_KEYSTORE_PASSWORD", "battery-test"), "public.finapp.test").out())
                .contains("REFUSED the keystore's certificate names [operator.finapp.test], not public.finapp.test");
        assertThat(tls(Map.of("BATTERY_PROXY_KEYSTORE_PASSWORD", "wrong"), "operator.finapp.test").out()).contains("REFUSED");
        assertThat(tls(Map.of(), "operator.finapp.test").out()).contains("REFUSED BATTERY_PROXY_KEYSTORE_PASSWORD is unset");
        Files.writeString(work.resolve("edge-ca.crt"), "", StandardCharsets.UTF_8);
        Run noCa = tls(Map.of("BATTERY_PROXY_KEYSTORE_PASSWORD", "battery-test"), "operator.finapp.test");
        assertThat(noCa.status()).isEqualTo(1);
        assertThat(noCa.out()).contains("REFUSED no certificate in");
    }

    // ------------------------------------------------------------------------------------------- the journal's judgements

    @Test
    @DisplayName("revoked: served before, refused 403 after, nothing served after the refusal; never refused, never served first, or"
            + " served again refused")
    void theRevokedJudgement() throws Exception {
        String events = "^/v1/operator/events/.+$";
        String read = "/v1/operator/events/abandoned";
        Path journal = journalOf(line(1, "GET", read, "-", "200", "delivered"), line(2, "GET", read, "-", "403", "delivered"),
                line(3, "GET", "/v1/operator/me", "-", "200", "delivered"));
        assertThat(tool("journal", "revoked", journal.toString(), "ANY", events).out())
                .contains("JOURNAL revoked served-before=1 refused=1 first-refused=GET " + read);
        assertThat(tool("journal", "revoked", journalOf(line(1, "GET", read, "-", "200", "delivered")).toString(), "ANY", events).out())
                .contains("REFUSED no ANY " + events + " was refused 403");
        assertThat(tool("journal", "revoked", journalOf(line(1, "GET", read, "-", "403", "delivered")).toString(), "ANY", events).out())
                .contains("was served before the first 403");
        assertThat(tool("journal", "revoked", journalOf(line(1, "GET", read, "-", "200", "delivered"), line(2, "GET", read, "-", "403",
                "delivered"), line(3, "POST", "/v1/operator/events/replays", "k1", "202", "delivered")).toString(), "ANY", events).out())
                .contains("REFUSED served after the refusal: [POST /v1/operator/events/replays]");
    }

    @Test
    @DisplayName("conflict: one 2xx and the rest 409 on one case; two winners, a 500 or two cases refused")
    void theConflictJudgement() throws Exception {
        String acts = "^/v1/operator/risk/cases/[^/]+/actions$";
        assertThat(judge("conflict", acts, line(1, "POST", "/v1/operator/risk/cases/c1/actions", "-", "200", "delivered"),
                line(2, "POST", "/v1/operator/risk/cases/c1/actions", "-", "409", "delivered")).out())
                .contains("JOURNAL conflict path=/v1/operator/risk/cases/c1/actions won=1 refused=1");
        assertThat(judge("conflict", acts, line(1, "POST", "/v1/operator/risk/cases/c1/actions", "-", "200", "delivered"),
                line(2, "POST", "/v1/operator/risk/cases/c1/actions", "-", "200", "delivered")).out()).contains("REFUSED 2 acts");
        assertThat(judge("conflict", acts, line(1, "POST", "/v1/operator/risk/cases/c1/actions", "-", "200", "delivered"),
                line(2, "POST", "/v1/operator/risk/cases/c1/actions", "-", "500", "delivered")).out()).contains("REFUSED");
        assertThat(judge("conflict", acts, line(1, "POST", "/v1/operator/risk/cases/c1/actions", "-", "200", "delivered"),
                line(2, "POST", "/v1/operator/risk/cases/c2/actions", "-", "409", "delivered")).out()).contains("named 2 subjects");
        assertThat(judge("conflict", acts, line(1, "POST", "/v1/operator/risk/cases/c1/actions", "-", "200", "delivered")).out())
                .contains("REFUSED 1 POST");
    }

    @Test
    @DisplayName("stale: a 409 first and one 2xx last on one record; a 2xx first, no 2xx, or two 2xx refused")
    void theStaleJudgement() throws Exception {
        String approval = "^/v1/operator/reconciliation/resolutions/[^/]+/approval$";
        String path = "/v1/operator/reconciliation/resolutions/r1/approval";
        assertThat(judge("stale", approval, line(1, "POST", path, "-", "409", "delivered"), line(2, "POST", path, "-", "200", "delivered"))
                .out()).contains("JOURNAL stale path=" + path + " stale=1 confirmed=1");
        assertThat(judge("stale", approval, line(1, "POST", path, "-", "200", "delivered"), line(2, "POST", path, "-", "409", "delivered"))
                .out()).contains("REFUSED");
        assertThat(judge("stale", approval, line(1, "POST", path, "-", "409", "delivered"), line(2, "POST", path, "-", "409", "delivered"))
                .out()).contains("REFUSED");
        assertThat(judge("stale", approval, line(1, "POST", path, "-", "409", "delivered"), line(2, "POST", path, "-", "200", "delivered"),
                line(3, "POST", path, "-", "200", "delivered")).out()).contains("REFUSED");
    }

    @Test
    @DisplayName("duplicate: every 2xx under one key with one body, the rest 409; two keys answered 2xx, or two bodies, refused")
    void theDuplicateJudgement() throws Exception {
        String approval = "^/v1/operator/events/discards/[^/]+/approval$";
        String path = "/v1/operator/events/discards/d1/approval";
        assertThat(judge("duplicate", approval, line(1, "POST", path, "k1", "200", "delivered"), line(2, "POST", path, "k1", "200", "delivered"),
                line(3, "POST", path, "k2", "409", "delivered")).out())
                .contains("JOURNAL duplicate path=" + path + " approvals=3 replayed=1 refused=1");
        assertThat(judge("duplicate", approval, line(1, "POST", path, "k1", "200", "delivered"), line(2, "POST", path, "k2", "200", "delivered"))
                .out()).contains("REFUSED the 2xx approvals on " + path + " carried 2 keys or bodies");
        assertThat(judge("duplicate", approval, line(1, "POST", path, "k1", "200", "delivered"),
                line(2, "POST", path, "k1", "200", "delivered").replace("0123456789abcdef", "fedcba9876543210")).out())
                .contains("carried 2 keys or bodies");
        assertThat(judge("duplicate", approval, line(1, "POST", path, "k1", "409", "delivered"), line(2, "POST", path, "k2", "409", "delivered"))
                .out()).contains("REFUSED no approval");
    }

    @Test
    @DisplayName("outage: a 5xx met, a read answered after it, no key twice, one page load; no failure, no recovery, a command retried on"
            + " its own or a reload refused")
    void theOutageJudgement() throws Exception {
        String page = line(1, "GET", "/console/", "-", "200", "delivered");
        String asset = line(2, "GET", "/console/assets/index-abc123.js", "-", "200", "delivered");
        String away = line(3, "GET", "/v1/operator/events/abandoned", "-", "503", "delivered");
        String command = line(4, "POST", "/v1/operator/events/abandoned/e1/retry", "k1", "503", "delivered");
        String back = line(5, "GET", "/v1/operator/events/abandoned", "-", "200", "delivered");
        assertThat(judgeOutage(page, asset, away, command, back).out()).contains("JOURNAL outage failed=2 recovered=1 commands=1 pages=1");
        assertThat(judgeOutage(page, asset, back).out()).contains("REFUSED no request met the backend away");
        assertThat(judgeOutage(page, away).out()).contains("REFUSED no operator read answered 2xx after the last failure");
        assertThat(judgeOutage(page, away, command, line(5, "POST", "/v1/operator/events/abandoned/e1/retry", "k1", "200", "delivered"), back)
                .out()).contains("REFUSED a command was sent again under its key [k1]");
        assertThat(judgeOutage(page, away, line(4, "GET", "/console/platform/dead-letters", "-", "200", "delivered"), back).out())
                .contains("REFUSED the console's page was loaded 2 times, not once: a reload");
    }

    @Test
    @DisplayName("answered: the act asked and refused with the status named every time; never asked, or answered otherwise, refused")
    void theAnsweredJudgement() throws Exception {
        String approval = "^/v1/ledger/adjustments/[^/]+/approval$";
        Path journal = journalOf(line(1, "POST", "/v1/ledger/adjustments/a1/approval", "k1", "403", "delivered"));
        assertThat(tool("journal", "answered", journal.toString(), "POST", approval, "403").out())
                .contains("JOURNAL answered asked=1 status=403");
        assertThat(tool("journal", "answered", journal.toString(), "POST", approval, "200").out()).contains("REFUSED");
        assertThat(tool("journal", "answered", journal.toString(), "POST", "^/v1/nothing$", "403").out()).contains("REFUSED no POST");
        Files.writeString(journal, "not the header\n", StandardCharsets.UTF_8);
        assertThat(tool("journal", "tokens", journal.toString()).out()).contains("REFUSED the journal");
    }

    // ------------------------------------------------------------------------------------------- the report

    @Test
    @DisplayName("the report: one test of the tag, in the kind project, passed first time, with its violation count and no token at rest")
    void aPassedTestIsReported() throws Exception {
        Run accepted = report(spec("S4 a refund whose answer was lost @battery-s4", "expected", "passed", "0", "none"), "@battery-s4");
        assertThat(accepted.status()).as(accepted.out()).isZero();
        assertThat(accepted.out()).contains("REPORT tag=@battery-s4 status=passed csp-violations=0 session-token-at-rest=none");
        assertThat(report(spec("S1 a forbidden act @battery-s1", "expected", "passed", "3", "none"), "@battery-s1").out())
                .as("a count is reported as it is; the harness sums and judges it").contains("csp-violations=3");
    }

    @Test
    @DisplayName("the report refuses a failed, skipped or flaky test, a missing or doubled one, another project, a missing annotation and a"
            + " token at rest")
    void whatIsNotAPassIsRefused() throws Exception {
        String tag = "@battery-s2";
        String title = "S2 revoked mid-session " + tag;
        assertThat(report(spec(title, "unexpected", "failed", "0", "none"), tag).out()).contains("REFUSED " + tag + " ended unexpected");
        assertThat(report(spec(title, "skipped", "skipped", "0", "none"), tag).out()).contains("ended skipped");
        assertThat(report(spec(title, "flaky", "passed", "0", "none"), tag).out()).contains("ended flaky");
        assertThat(report(spec("S3 another @battery-s3", "expected", "passed", "0", "none"), tag).out())
                .contains("REFUSED 0 tests carry " + tag);
        String doubled = "{\"suites\":[{\"title\":\"b\",\"specs\":[" + specOnly(title, "expected", "passed", "0", "none") + ","
                + specOnly(title, "expected", "passed", "0", "none") + "]}]}";
        assertThat(report(doubled, tag).out()).contains("REFUSED 2 tests carry " + tag);
        assertThat(report(spec(title, "expected", "passed", "0", "none").replace("\"projectName\":\"kind\"", "\"projectName\":\"local\""), tag)
                .out()).contains("not kind");
        assertThat(report(spec(title, "expected", "passed", "0", "none").replace("csp-violations", "csp"), tag).out())
                .contains("states 0 values of csp-violations");
        assertThat(report(spec(title, "expected", "passed", "0", "localStorage"), tag).out())
                .contains("REFUSED " + tag + " found a session token at rest: localStorage");
        assertThat(report(spec(title, "expected", "passed", "-1", "none"), tag).out()).contains("not a whole number");
        assertThat(report("{\"suites\":[]}", "@battery-s8").out()).contains("REFUSED the tag is @battery-s1 to @battery-s7");
    }

    // ------------------------------------------------------------------------------------------- helpers

    private void start(String... drop) throws Exception {
        startAgainst("http://127.0.0.1:" + upstream.getAddress().getPort(), drop);
    }

    private void startAgainst(String origin, String... drop) throws Exception {
        List<String> line = new ArrayList<>(List.of(java(), "-cp", classes.toString(), "Battery", "proxy", work.toString(), origin));
        line.addAll(List.of(drop));
        ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
        builder.environment().put("BATTERY_PROXY_PLAIN", "loopback");
        builder.environment().put("BATTERY_PROXY_PORT", "0");
        proxy = builder.start();
        proxy.getOutputStream().close();
        BufferedReader out = new BufferedReader(new InputStreamReader(proxy.getInputStream(), StandardCharsets.UTF_8));
        String first = out.readLine();
        assertThat(first).as("the proxy says where it listens").isNotNull().startsWith("LISTENING ");
        port = Integer.parseInt(first.split(" ")[1]);
        Thread drain = new Thread(() -> {
            try {
                while (out.readLine() != null) {
                    // the proxy's own log, read so it never blocks
                }
            } catch (IOException ignored) {
                // the process ended
            }
        });
        drain.setDaemon(true);
        drain.start();
    }

    private Run proxyRun(Map<String, String> environment, String origin) throws Exception {
        Map<String, String> env = new java.util.HashMap<>(environment);
        env.put("BATTERY_PROXY_PORT", "0");
        return exec(List.of(java(), "-cp", classes.toString(), "Battery", "proxy", work.toString(), origin), env);
    }

    private Run tls(Map<String, String> environment, String host) throws Exception {
        return exec(List.of(java(), "-cp", classes.toString(), "Battery", "tls-check", work.toString(), host), environment);
    }

    private static String post(String path, String key) {
        return "POST " + path + " HTTP/1.1\r\nHost: operator.finapp.test\r\nIdempotency-Key: " + key
                + "\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}";
    }

    /** One request on its own connection, read to the end: the proxy closes after every answer, so the read ends. */
    private String raw(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> headerLines(String answer, String name) {
        String head = answer.substring(0, answer.indexOf("\r\n\r\n"));
        List<String> values = new ArrayList<>();
        for (String header : head.split("\r\n")) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase(name)) {
                values.add(header.substring(colon + 1).trim());
            }
        }
        return values;
    }

    /**
     * The journal once it holds {@code expected} lines: the proxy writes a line after the client has its answer, so a client can read
     * the file a moment before its own line lands - as the harness, which reads it after stopping the proxy, never does.
     */
    private List<String[]> journal(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> lines = Files.readAllLines(work.resolve("journal.tsv"), StandardCharsets.UTF_8);
        while (lines.size() - 1 < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            lines = Files.readAllLines(work.resolve("journal.tsv"), StandardCharsets.UTF_8);
        }
        assertThat(lines.get(0)).startsWith("seq\treceived\tmethod\tpath");
        assertThat(lines).as("the journal's lines").hasSize(expected + 1);
        return lines.subList(1, lines.size()).stream().map(text -> text.split("\t", -1)).toList();
    }

    private static final String HEADER =
            "seq\treceived\tmethod\tpath\tquery\tkey\tstatus\toutcome\tdigest\tcorrelation\ttoken-in-url\ttoken-in-cookie\tms";

    private static String line(int seq, String method, String path, String key, String status, String outcome) {
        return String.join("\t", Integer.toString(seq), "2026-11-20T12:00:0" + seq + "Z", method, path, "-", key, status, outcome,
                "0123456789abcdef", "corr-" + seq, "no", "no", "5");
    }

    private Path journalOf(String... lines) throws IOException {
        Path journal = Files.createTempFile(work, "journal", ".tsv");
        Files.writeString(journal, HEADER + "\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return journal;
    }

    private Run judge(String rule, String pattern, String... lines) throws Exception {
        return tool("journal", rule, journalOf(lines).toString(), "POST", pattern);
    }

    private Run judgeOutage(String... lines) throws Exception {
        return tool("journal", "outage", journalOf(lines).toString());
    }

    private static String specOnly(String title, String status, String result, String violations, String atRest) {
        return "{\"title\":\"" + title + "\",\"tags\":[],\"tests\":[{\"projectName\":\"kind\",\"status\":\"" + status
                + "\",\"annotations\":[{\"type\":\"csp-violations\",\"description\":\"" + violations
                + "\"},{\"type\":\"session-token-at-rest\",\"description\":\"" + atRest + "\"}],\"results\":[{\"status\":\"" + result
                + "\",\"annotations\":[]}]}]}";
    }

    private static String spec(String title, String status, String result, String violations, String atRest) {
        return "{\"config\":{},\"suites\":[{\"title\":\"battery.spec.ts\",\"specs\":[],\"suites\":[{\"title\":\"the battery\",\"specs\":["
                + specOnly(title, status, result, violations, atRest) + "]}]}],\"stats\":{\"expected\":1}}";
    }

    private Run report(String json, String tag) throws Exception {
        Path file = Files.createTempFile(work, "report", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return tool("report", file.toString(), tag);
    }

    private Run tool(String... args) throws Exception {
        List<String> line = new ArrayList<>(List.of(java(), "-cp", classes.toString(), "Battery"));
        line.addAll(List.of(args));
        return exec(line, Map.of());
    }

    private static Run exec(List<String> line, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
        builder.environment().remove("BATTERY_PROXY_PLAIN");
        builder.environment().remove("BATTERY_PROXY_KEYSTORE_PASSWORD");
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the tool finished").isTrue();
        return new Run(process.exitValue(), out);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
