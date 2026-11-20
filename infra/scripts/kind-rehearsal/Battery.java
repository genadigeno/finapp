// TEST-ONLY (P17-TST-001). The end-to-end battery's proxy, its journal's judgements and the reader of the console's report.
//
//   java --source 21 Battery.java proxy <work> <upstream> [drop <METHOD> <path regex>]
//   java --source 21 Battery.java tls-check <work> <host>
//   java --source 21 Battery.java journal <rule> <journal.tsv> [<METHOD or ANY> <path regex> [<status>]]
//   java --source 21 Battery.java report <report.json> <tag>
//
// OPERATIONS_RUNBOOK.md section 11.69 is how a person runs and reads it; kind-rehearsal/battery.sh is what runs it.

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The battery's three tools, one file so the harness runs one program (P17-TST-001).
 *
 * <h2>proxy: between the browser and the operator edge</h2>
 *
 * <p>The console's browser reaches the operator edge through this proxy and through nothing else, for every scenario. It terminates
 * TLS with the operator edge's own certificate ({@code work/proxy.p12}, made by the harness from the cluster's
 * {@code finapp-edge-operator-tls} for the run, its password {@code BATTERY_PROXY_KEYSTORE_PASSWORD}), so a browser that trusts the
 * edge trusts it no differently; and it forwards every request to the real edge ({@code upstream}), verifying the edge against the
 * run's local CA ({@code work/edge-ca.crt}). It speaks HTTP/1.1 and answers every request with {@code Connection: close}: a browser
 * resends a request that a reused connection lost without an answer, so with no reuse the console, and nothing under it, is what
 * retries a dropped request.
 *
 * <p><strong>The drop</strong> (scenario 4, a timeout after the backend executed): given {@code drop <METHOD> <path regex>}, the first
 * request of that method whose path matches and whose upstream answer is a 2xx - the backend executed and committed - is forwarded,
 * its whole answer read, and then the client's connection is closed with no byte of it sent. A non-2xx answer is delivered and the
 * rule stays armed; after its one drop the proxy delivers everything.
 *
 * <p>Nothing is changed on the way but what a proxy must: hop-by-hop headers are not forwarded, and {@code Accept-Encoding} is not, so
 * every answer's digest is of its plain bytes and a replay's can be compared with its original's. Every other header, the console's
 * Content-Security-Policy among them, reaches the browser as the edge sent it.
 *
 * <p><strong>The journal</strong> ({@code work/journal.tsv}), one line per request: sequence, instant, method, path, whether a query
 * was present, the idempotency key, the upstream's status, the outcome ({@code delivered}, {@code dropped}, {@code upstream-failed},
 * {@code refused}), the first sixteen hex digits of the answer body's SHA-256, the answer's correlation id, whether a session token
 * was in the URL, whether one was in a cookie, and the milliseconds taken. Never a body, a header's value but the key's and the
 * correlation's, a query's value, or a token: the proxy learns the session tokens it sees - a sign-in or step-up answer's
 * {@code sessionToken}, an {@code Authorization: Bearer} header - in memory only, to say whether one went where criterion 4 says none
 * may. A path that carries one is written {@code <withheld>}.
 *
 * <h2>journal: the run's judgements of a scenario's journal</h2>
 *
 * <p>{@code tokens}, {@code answered}, {@code revoked}, {@code conflict}, {@code drop}, {@code stale}, {@code duplicate} and
 * {@code outage}: each prints
 * one {@code JOURNAL} line and exits 0, or one {@code REFUSED} line naming why and exits 1.
 *
 * <h2>report: the console's Playwright JSON report, for one scenario's tag</h2>
 *
 * <p>Exactly one test carries the tag, ran in the {@code kind} project and passed first time; it states {@code csp-violations} (a
 * whole number) and {@code session-token-at-rest} ({@code none}) as annotations. Prints one {@code REPORT} line and exits 0, or one
 * {@code REFUSED} line and exits 1. A missing, skipped, flaky or doubled test, or a missing annotation, is refused: never a pass.
 */
public final class Battery {

    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");
    /** What java.net.http refuses to set, beside the hop-by-hop ones. */
    private static final Set<String> NOT_FORWARDED = Set.of("content-length", "expect", "host", "accept-encoding");
    private static final Pattern SIGN_IN = Pattern.compile("^/v1/authentications(/mfa)?$");
    private static final Pattern SESSION_TOKEN = Pattern.compile("\"sessionToken\"\\s*:\\s*\"([^\"\\\\]{16,})\"");
    private static final Pattern BEARER = Pattern.compile("^Bearer\\s+(\\S{16,})$");
    private static final Pattern KEY_SHAPE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern CORRELATION_SHAPE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final String HEADER =
            "seq\treceived\tmethod\tpath\tquery\tkey\tstatus\toutcome\tdigest\tcorrelation\ttoken-in-url\ttoken-in-cookie\tms";

    private Battery() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
        }
        int status = switch (args[0]) {
            case "proxy" -> {
                proxy(args);
                yield 0;
            }
            case "journal" -> journal(args);
            case "report" -> report(args);
            case "tls-check" -> tlsCheck(args);
            default -> usage();
        };
        System.out.flush();
        System.exit(status);
    }

    private static int usage() {
        System.err.println("usage: Battery.java proxy <work> <upstream> [drop <METHOD> <path regex>] | tls-check <work> <host>"
                + " | journal <rule> <journal.tsv> [<METHOD> <path regex> [<status>]] | report <report.json> <tag>");
        System.exit(2);
        return 2;
    }

    // ============================================================================================ proxy

    private static void proxy(String[] args) throws Exception {
        if (args.length != 3 && args.length != 6) {
            usage();
        }
        Path work = Path.of(args[1]);
        URI upstream = URI.create(args[2]);
        // The loopback mode is the hermetic test's alone (BatteryProxyTest): plain HTTP, bound to 127.0.0.1, to an upstream on
        // 127.0.0.1 - because a development machine's antivirus re-signs every TLS connection, loopback included (CURRENT_STATE.md,
        // Local Environment Prerequisites). The harness never sets it (BatteryContractTest), and the TLS material it uses is proven
        // by the tls-check command, which the harness runs before every proxy.
        boolean loopback = "loopback".equals(System.getenv("BATTERY_PROXY_PLAIN"));
        String scheme = loopback ? "http" : "https";
        if (!scheme.equals(upstream.getScheme()) || !upstream.getRawPath().isEmpty() || upstream.getRawQuery() != null
                || loopback && !"127.0.0.1".equals(upstream.getHost())) {
            throw new IllegalArgumentException("the upstream is an " + scheme + " origin with no path" + (loopback ? " on 127.0.0.1" : "")
                    + ", not " + args[2]);
        }
        Drop drop = null;
        if (args.length == 6) {
            if (!"drop".equals(args[3]) || !args[4].matches("[A-Z]+")) {
                usage();
            }
            drop = new Drop(args[4], Pattern.compile(args[5]));
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("BATTERY_PROXY_PORT", "8443"));
        HttpServer server;
        HttpClient client;
        if (loopback) {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 128);
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10)).build();
        } else {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(port), 128);
            https.setHttpsConfigurator(new HttpsConfigurator(serverContext(work.resolve("proxy.p12"), keystorePassword())));
            server = https;
            client = HttpClient.newBuilder().sslContext(trusting(work.resolve("edge-ca.crt"))).version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        }
        Proxy proxy = new Proxy(upstream, drop, work.resolve("journal.tsv"), client);
        server.createContext("/", proxy::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("LISTENING " + server.getAddress().getPort() + (drop == null ? "" : " drop " + drop.method() + " " + drop.path()));
        System.out.flush();
        Thread.currentThread().join();
    }

    private static char[] keystorePassword() {
        String password = System.getenv("BATTERY_PROXY_KEYSTORE_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new Refused("BATTERY_PROXY_KEYSTORE_PASSWORD is unset");
        }
        return password.toCharArray();
    }

    /**
     * The proxy's TLS material, proven before the proxy starts: the keystore opens under its password and holds exactly one private
     * key, whose certificate names the operator host; the CA file holds at least one certificate. Both contexts are built as the proxy
     * builds them.
     */
    private static int tlsCheck(String[] args) {
        if (args.length != 3) {
            usage();
        }
        try {
            Path work = Path.of(args[1]);
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(work.resolve("proxy.p12"))) {
                keys.load(in, keystorePassword());
            }
            List<String> privateKeys = new ArrayList<>();
            for (String alias : Collections.list(keys.aliases())) {
                if (keys.isKeyEntry(alias)) {
                    privateKeys.add(alias);
                }
            }
            if (privateKeys.size() != 1) {
                throw new Refused("the keystore holds " + privateKeys.size() + " private keys, not one");
            }
            X509Certificate leaf = (X509Certificate) keys.getCertificate(privateKeys.get(0));
            List<String> names = new ArrayList<>();
            if (leaf.getSubjectAlternativeNames() != null) {
                leaf.getSubjectAlternativeNames().forEach(name -> names.add(String.valueOf(name.get(1))));
            }
            if (!names.contains(args[2])) {
                throw new Refused("the keystore's certificate names " + names + ", not " + args[2]);
            }
            serverContext(work.resolve("proxy.p12"), keystorePassword());
            trusting(work.resolve("edge-ca.crt"));
            System.out.println("TLS key=" + leaf.getPublicKey().getAlgorithm() + " names=" + String.join(",", names) + " not-after="
                    + leaf.getNotAfter().toInstant());
            return 0;
        } catch (Exception e) {
            System.out.println("REFUSED " + (e instanceof Refused ? e.getMessage() : e.toString()));
            return 1;
        }
    }

    private record Drop(String method, Pattern path) {}

    private static SSLContext serverContext(Path keystore, char[] password) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keys.load(in, password);
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext trusting(Path ca) throws Exception {
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        int i = 0;
        try (InputStream in = Files.newInputStream(ca)) {
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trusted.setCertificateEntry("ca-" + i++, certificate);
            }
        }
        if (i == 0) {
            throw new Refused("no certificate in " + ca);
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static final class Proxy {
        private final URI upstream;
        private final Drop drop;
        private final AtomicBoolean armed;
        private final Path journal;
        private final HttpClient client;
        private final AtomicLong sequence = new AtomicLong();
        /** The session tokens seen, in memory only: never written, never logged. */
        private final Set<String> tokens = ConcurrentHashMap.newKeySet();

        Proxy(URI upstream, Drop drop, Path journal, HttpClient client) throws IOException {
            this.upstream = upstream;
            this.drop = drop;
            this.armed = new AtomicBoolean(drop != null);
            this.journal = journal;
            this.client = client;
            Files.writeString(journal, HEADER + "\n", StandardCharsets.UTF_8);
        }

        void handle(HttpExchange exchange) {
            long seq = sequence.incrementAndGet();
            Instant received = Instant.now();
            long started = System.nanoTime();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String target = query == null ? path : path + "?" + query;
            Headers in = exchange.getRequestHeaders();
            String key = in.getFirst("Idempotency-Key");
            String journalKey = key == null ? "-" : KEY_SHAPE.matcher(key).matches() ? key : "invalid";
            String authorization = in.getFirst("Authorization");
            if (authorization != null) {
                Matcher bearer = BEARER.matcher(authorization.strip());
                if (bearer.matches()) {
                    tokens.add(bearer.group(1));
                }
            }
            boolean inUrl = carriesToken(target);
            boolean inCookie = in.getOrDefault("Cookie", List.of()).stream().anyMatch(this::carriesToken);
            Line line = new Line(seq, received, method, inUrl ? "<withheld>" : path, query == null ? "-" : "present", journalKey);
            try {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
                if (body.length > MAX_BODY) {
                    answerEmpty(exchange, 413);
                    write(line.done("413", "refused", "-", "-", inUrl, inCookie, started));
                    return;
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upstream + target)).timeout(Duration.ofSeconds(90))
                        .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                in.forEach((name, values) -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!HOP_BY_HOP.contains(lower) && !NOT_FORWARDED.contains(lower)) {
                        values.forEach(value -> request.header(name, value));
                    }
                });
                HttpResponse<byte[]> answer;
                try {
                    answer = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                } catch (IOException e) {
                    // The proxy's own log says why (the exception's class and message: never a header or a body).
                    System.err.println("upstream failed for request " + seq + ": " + e);
                    answerEmpty(exchange, 502);
                    write(line.done("-", "upstream-failed", "-", "-", inUrl, inCookie, started));
                    return;
                }
                int status = answer.statusCode();
                boolean success = status >= 200 && status < 300;
                if (success && "POST".equals(method) && SIGN_IN.matcher(path).matches()) {
                    Matcher issued = SESSION_TOKEN.matcher(new String(answer.body(), StandardCharsets.UTF_8));
                    while (issued.find()) {
                        tokens.add(issued.group(1));
                    }
                }
                inCookie |= answer.headers().allValues("Set-Cookie").stream().anyMatch(this::carriesToken);
                String digest = digest(answer.body());
                String correlation = answer.headers().firstValue("X-Correlation-Id")
                        .filter(value -> CORRELATION_SHAPE.matcher(value).matches()).orElse("-");
                if (success && drop != null && drop.method().equals(method) && drop.path().matcher(path).matches()
                        && armed.compareAndSet(true, false)) {
                    // The backend has answered, so it has committed: the client never hears it.
                    write(line.done(Integer.toString(status), "dropped", digest, correlation, inUrl, inCookie, started));
                    exchange.close();
                    return;
                }
                Headers out = exchange.getResponseHeaders();
                answer.headers().map().forEach((name, values) -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!HOP_BY_HOP.contains(lower) && !"content-length".equals(lower) && !lower.startsWith(":")) {
                        values.forEach(value -> out.add(name, value));
                    }
                });
                out.set("Connection", "close");
                byte[] bytes = answer.body();
                boolean bodiless = "HEAD".equals(method) || status == 204 || status == 304 || status < 200;
                exchange.sendResponseHeaders(status, bodiless || bytes.length == 0 ? -1 : bytes.length);
                if (!bodiless && bytes.length > 0) {
                    try (OutputStream sent = exchange.getResponseBody()) {
                        sent.write(bytes);
                    }
                }
                write(line.done(Integer.toString(status), "delivered", digest, correlation, inUrl, inCookie, started));
            } catch (Exception e) {
                System.err.println("request " + seq + " could not be carried: " + e);
                // Never a silent drop: a request the proxy could not carry is answered 502 when nothing was sent yet, and journaled.
                if (exchange.getResponseCode() == -1) {
                    try {
                        answerEmpty(exchange, 502);
                    } catch (IOException ignored) {
                        // the client has gone; the journal still says what happened
                    }
                }
                write(line.done("-", "upstream-failed", "-", "-", inUrl, inCookie, started));
            } finally {
                exchange.close();
            }
        }

        private boolean carriesToken(String text) {
            if (text == null) {
                return false;
            }
            for (String token : tokens) {
                if (text.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        private static void answerEmpty(HttpExchange exchange, int status) throws IOException {
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(status, -1);
        }

        private synchronized void write(String text) {
            try {
                Files.writeString(journal, text + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("the journal could not be written", e);
            }
        }
    }

    private record Line(long seq, Instant received, String method, String path, String query, String key) {
        String done(String status, String outcome, String digest, String correlation, boolean inUrl, boolean inCookie, long started) {
            return String.join("\t", Long.toString(seq), received.toString(), method, path, query, key, status, outcome, digest,
                    correlation, inUrl ? "yes" : "no", inCookie ? "yes" : "no",
                    Long.toString((System.nanoTime() - started) / 1_000_000));
        }
    }

    private static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ============================================================================================ journal

    /** One journal line, read back. */
    record Entry(long seq, Instant received, String method, String path, String query, String key, String status, String outcome,
            String digest, String correlation, boolean tokenInUrl, boolean tokenInCookie) {

        boolean success() {
            return status.length() == 3 && status.charAt(0) == '2';
        }

        boolean failedServer() {
            return "upstream-failed".equals(outcome) || status.equals("502") || status.equals("503") || status.equals("504");
        }

        /**
         * {@code m} is a method, or {@code ANY} for any - never {@code *}, which the java launcher on Windows expands into the
         * working directory's file names before the program sees it.
         */
        boolean matches(String m, Pattern p) {
            return ("ANY".equals(m) || method.equals(m)) && p.matcher(path).matches();
        }
    }

    static List<Entry> read(Path journal) throws IOException {
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new Refused("the journal " + journal + " does not start with the proxy's header");
        }
        List<Entry> entries = new ArrayList<>();
        for (String text : lines.subList(1, lines.size())) {
            String[] f = text.split("\t", -1);
            if (f.length != 13) {
                throw new Refused("a journal line has " + f.length + " fields, not 13: line " + (entries.size() + 2));
            }
            entries.add(new Entry(Long.parseLong(f[0]), Instant.parse(f[1]), f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9],
                    "yes".equals(f[10]), "yes".equals(f[11])));
        }
        entries.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        return entries;
    }

    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String why) {
            super(why);
        }
    }

    private static int journal(String[] args) {
        try {
            if (args.length < 3) {
                usage();
            }
            String rule = args[1];
            List<Entry> entries = read(Path.of(args[2]));
            String method = args.length > 3 ? args[3] : null;
            Pattern path = args.length > 4 ? Pattern.compile(args[4]) : null;
            String status = args.length > 5 ? args[5] : null;
            String verdict = switch (rule) {
                case "tokens" -> tokens(entries);
                case "answered" -> answered(entries, need(method), need(path), need(status));
                case "revoked" -> revoked(entries, need(method), need(path));
                case "conflict" -> conflict(entries, need(method), need(path));
                case "drop" -> drop(entries, need(method), need(path));
                case "stale" -> stale(entries, need(method), need(path));
                case "duplicate" -> duplicate(entries, need(method), need(path));
                case "outage" -> outage(entries);
                default -> throw new Refused("no rule " + rule + ": tokens, answered, revoked, conflict, drop, stale, duplicate or outage");
            };
            System.out.println("JOURNAL " + rule + " " + verdict);
            return 0;
        } catch (IOException | RuntimeException e) {
            System.out.println("REFUSED " + e.getMessage());
            return 1;
        }
    }

    private static <T> T need(T value) {
        if (value == null) {
            throw new Refused("this rule needs a method, a path pattern and, for answered, a status");
        }
        return value;
    }

    /** Criterion 4's URL and cookie halves: no session token the proxy saw went into a URL or a cookie, on any line. */
    static String tokens(List<Entry> entries) {
        if (entries.isEmpty()) {
            throw new Refused("the journal holds no request");
        }
        long inUrl = entries.stream().filter(Entry::tokenInUrl).count();
        long inCookie = entries.stream().filter(Entry::tokenInCookie).count();
        if (inUrl + inCookie > 0) {
            throw new Refused(inUrl + " requests carried a session token in their URL and " + inCookie + " in a cookie");
        }
        return "lines=" + entries.size() + " token-in-url=0 token-in-cookie=0";
    }

    /** Scenarios 1 and 2: the act was asked for, and every time it was asked the edge answered {@code status}. */
    static String answered(List<Entry> entries, String method, Pattern path, String status) {
        List<Entry> asked = entries.stream().filter(e -> e.matches(method, path)).toList();
        if (asked.isEmpty()) {
            throw new Refused("no " + method + " " + path + " in the journal");
        }
        List<String> others = asked.stream().map(Entry::status).filter(s -> !s.equals(status)).distinct().toList();
        if (!others.isEmpty()) {
            throw new Refused(method + " " + path + " answered " + others + " as well as " + status);
        }
        return "asked=" + asked.size() + " status=" + status;
    }

    /**
     * Scenario 2: the person's acts there were served before the revocation and refused after it - a 2xx, then a 403, and no 2xx after
     * the first 403. {@code method} may be {@code ANY}: the next act can be a read of the module or a command in it.
     */
    static String revoked(List<Entry> entries, String method, Pattern path) {
        List<Entry> asked = entries.stream().filter(e -> e.matches(method, path)).toList();
        Entry refused = asked.stream().filter(e -> e.status().equals("403")).findFirst()
                .orElseThrow(() -> new Refused("no " + method + " " + path + " was refused 403: the revocation never reached an act"));
        long before = asked.stream().filter(e -> e.seq() < refused.seq() && e.success()).count();
        if (before == 0) {
            throw new Refused("no " + method + " " + path + " was served before the first 403: nothing shows the role was held");
        }
        List<String> after = asked.stream().filter(e -> e.seq() > refused.seq() && e.success()).map(e -> e.method() + " " + e.path())
                .toList();
        if (!after.isEmpty()) {
            throw new Refused("served after the refusal: " + after);
        }
        return "served-before=" + before + " refused=" + asked.stream().filter(e -> e.status().equals("403")).count()
                + " first-refused=" + refused.method() + " " + refused.path();
    }

    /** Scenario 3: two people on one case, one answered 2xx, every other 409. */
    static String conflict(List<Entry> entries, String method, Pattern path) {
        List<Entry> asked = onePath(entries, method, path, 2);
        long won = asked.stream().filter(Entry::success).count();
        long refused = asked.stream().filter(e -> e.status().equals("409")).count();
        if (won != 1 || won + refused != asked.size()) {
            throw new Refused(asked.size() + " acts on " + asked.get(0).path() + ": " + won + " answered 2xx and " + refused
                    + " 409, not one and the rest 409");
        }
        return "path=" + asked.get(0).path() + " won=1 refused=" + refused;
    }

    /**
     * Scenario 4: exactly one request dropped - a 2xx the backend answered - and afterwards the same method, path and key delivered,
     * with the same status and the same body: the original outcome, replayed.
     */
    static String drop(List<Entry> entries, String method, Pattern path) {
        List<Entry> dropped = entries.stream().filter(e -> "dropped".equals(e.outcome())).toList();
        if (dropped.size() != 1) {
            throw new Refused(dropped.size() + " requests were dropped, not one");
        }
        Entry lost = dropped.get(0);
        if (!lost.matches(method, path) || !lost.success() || "-".equals(lost.key()) || "invalid".equals(lost.key())) {
            throw new Refused("the dropped request was " + lost.method() + " " + lost.path() + " answered " + lost.status() + " under key "
                    + lost.key() + ", not a keyed 2xx " + method + " " + path);
        }
        List<Entry> retries = entries.stream().filter(e -> e.seq() > lost.seq() && e.method().equals(lost.method())
                && e.path().equals(lost.path()) && e.key().equals(lost.key())).toList();
        if (retries.isEmpty()) {
            throw new Refused("the dropped request's key " + lost.key() + " was never asked again");
        }
        for (Entry retry : retries) {
            if (!"delivered".equals(retry.outcome()) || !retry.status().equals(lost.status()) || !retry.digest().equals(lost.digest())) {
                throw new Refused("a retry under " + lost.key() + " was " + retry.outcome() + " " + retry.status() + " (" + retry.digest()
                        + "), not the original " + lost.status() + " (" + lost.digest() + ")");
            }
        }
        long otherKeys = entries.stream().filter(e -> e.matches(method, path) && e.success() && !e.key().equals(lost.key())).count();
        if (otherKeys > 0) {
            throw new Refused(otherKeys + " other " + method + " " + path + " answered 2xx under another key: a second effect may exist");
        }
        return "path=" + lost.path() + " key=" + lost.key() + " status=" + lost.status() + " retries=" + retries.size();
    }

    /** Scenario 5: the stale act refused 409 first, then the re-confirmed act answered 2xx once, on one path. */
    static String stale(List<Entry> entries, String method, Pattern path) {
        List<Entry> asked = onePath(entries, method, path, 2);
        Entry first = asked.get(0);
        Entry last = asked.get(asked.size() - 1);
        long won = asked.stream().filter(Entry::success).count();
        if (!first.status().equals("409") || !last.success() || won != 1) {
            throw new Refused("on " + first.path() + " the first answer was " + first.status() + ", the last " + last.status() + ", and "
                    + won + " were 2xx: not a 409 and then one 2xx");
        }
        return "path=" + first.path() + " stale=" + (asked.size() - 1) + " confirmed=1";
    }

    /** Scenario 6: two or more approvals of one thing - every 2xx under one key with one body (a replay), every other 409. */
    static String duplicate(List<Entry> entries, String method, Pattern path) {
        List<Entry> asked = onePath(entries, method, path, 2);
        List<Entry> won = asked.stream().filter(Entry::success).toList();
        if (won.isEmpty()) {
            throw new Refused("no approval on " + asked.get(0).path() + " answered 2xx");
        }
        Set<String> outcomes = new LinkedHashSet<>();
        won.forEach(e -> outcomes.add(e.key() + "|" + e.digest()));
        if (outcomes.size() != 1) {
            throw new Refused("the 2xx approvals on " + asked.get(0).path() + " carried " + outcomes.size() + " keys or bodies: " + outcomes);
        }
        long refused = asked.stream().filter(e -> e.status().equals("409")).count();
        if (won.size() + refused != asked.size()) {
            throw new Refused("an approval on " + asked.get(0).path() + " answered neither the one outcome nor 409");
        }
        return "path=" + asked.get(0).path() + " approvals=" + asked.size() + " replayed=" + (won.size() - 1) + " refused=" + refused;
    }

    /**
     * Scenario 7: the backend unavailable - a server failure answered while it was away, an operator read answered 2xx after the
     * last one, no idempotency key asked twice (no command retried on its own), and the console's page loaded once (no reload).
     */
    static String outage(List<Entry> entries) {
        List<Entry> failed = entries.stream().filter(Entry::failedServer).toList();
        if (failed.isEmpty()) {
            throw new Refused("no request met the backend away: no 502, 503, 504 or failed upstream");
        }
        long lastFailure = failed.get(failed.size() - 1).seq();
        long recovered = entries.stream().filter(e -> e.seq() > lastFailure && "GET".equals(e.method())
                && e.path().startsWith("/v1/") && e.success()).count();
        if (recovered == 0) {
            throw new Refused("no operator read answered 2xx after the last failure: the console never recovered");
        }
        Map<String, Integer> keys = new HashMap<>();
        entries.stream().filter(e -> !"-".equals(e.key())).forEach(e -> keys.merge(e.key(), 1, Integer::sum));
        List<String> twice = keys.entrySet().stream().filter(k -> k.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!twice.isEmpty()) {
            throw new Refused("a command was sent again under its key " + twice + ": retried on its own");
        }
        long pages = entries.stream().filter(e -> "GET".equals(e.method()) && document(e.path())).count();
        if (pages != 1) {
            throw new Refused("the console's page was loaded " + pages + " times, not once: a reload");
        }
        return "failed=" + failed.size() + " recovered=" + recovered + " commands=" + keys.size() + " pages=1";
    }

    /** A request for the console's document: /console, /console/ or a deep link under it, never an asset (a last segment with a dot). */
    static boolean document(String path) {
        return path.equals("/console") || path.startsWith("/console/") && !path.startsWith("/console/assets/")
                && !path.substring(path.lastIndexOf('/') + 1).contains(".");
    }

    private static List<Entry> onePath(List<Entry> entries, String method, Pattern path, int least) {
        List<Entry> asked = entries.stream().filter(e -> e.matches(method, path)).toList();
        if (asked.size() < least) {
            throw new Refused(asked.size() + " " + method + " " + path + " in the journal, fewer than " + least);
        }
        Set<String> paths = new LinkedHashSet<>();
        asked.forEach(e -> paths.add(e.path()));
        if (paths.size() != 1) {
            throw new Refused(method + " " + path + " named " + paths.size() + " subjects, not one: " + paths);
        }
        return asked;
    }

    // ============================================================================================ report

    private static int report(String[] args) throws IOException {
        if (args.length != 3) {
            usage();
        }
        try {
            System.out.println("REPORT " + judgeReport(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8), args[2]));
            return 0;
        } catch (RuntimeException e) {
            System.out.println("REFUSED " + e.getMessage());
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    static String judgeReport(String text, String tag) {
        if (!tag.matches("@battery-s[1-7]")) {
            throw new Refused("the tag is @battery-s1 to @battery-s7, not " + tag);
        }
        Object document = new Json(text).document();
        if (!(document instanceof Map<?, ?> root)) {
            throw new Refused("the report is not a JSON object");
        }
        List<Map<String, Object>> specs = new ArrayList<>();
        collectSpecs((Map<String, Object>) root, specs);
        List<Map<String, Object>> tagged = specs.stream().filter(spec -> tagged(spec, tag)).toList();
        if (tagged.size() != 1) {
            throw new Refused(tagged.size() + " tests carry " + tag + ", not one");
        }
        Map<String, Object> spec = tagged.get(0);
        List<Object> tests = list(spec.get("tests"));
        if (tests.size() != 1) {
            throw new Refused(tag + " ran in " + tests.size() + " projects, not the kind project alone");
        }
        Map<String, Object> test = (Map<String, Object>) tests.get(0);
        if (!"kind".equals(test.get("projectName"))) {
            throw new Refused(tag + " ran in the project " + test.get("projectName") + ", not kind");
        }
        if (!"expected".equals(test.get("status"))) {
            throw new Refused(tag + " ended " + test.get("status") + ", not expected (passed first time)");
        }
        List<Object> results = list(test.get("results"));
        if (results.size() != 1 || !"passed".equals(((Map<String, Object>) results.get(0)).get("status"))) {
            throw new Refused(tag + " has " + results.size() + " results, not one passed");
        }
        Map<String, Set<String>> annotations = new LinkedHashMap<>();
        for (Object holder : List.of(test, results.get(0))) {
            for (Object a : list(((Map<String, Object>) holder).get("annotations"))) {
                Map<String, Object> annotation = (Map<String, Object>) a;
                annotations.computeIfAbsent(String.valueOf(annotation.get("type")), k -> new LinkedHashSet<>())
                        .add(String.valueOf(annotation.get("description")));
            }
        }
        String violations = single(annotations, "csp-violations", tag);
        if (!violations.matches("0|[1-9][0-9]{0,5}")) {
            throw new Refused(tag + "'s csp-violations is '" + violations + "', not a whole number");
        }
        String atRest = single(annotations, "session-token-at-rest", tag);
        if (!"none".equals(atRest)) {
            throw new Refused(tag + " found a session token at rest: " + atRest);
        }
        String title = String.valueOf(spec.get("title"));
        return "tag=" + tag + " status=passed csp-violations=" + violations + " session-token-at-rest=none title=" + title.replace(' ', '_');
    }

    private static String single(Map<String, Set<String>> annotations, String type, String tag) {
        Set<String> values = annotations.getOrDefault(type, Set.of());
        if (values.size() != 1) {
            throw new Refused(tag + " states " + values.size() + " values of " + type + ", not one");
        }
        return values.iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static void collectSpecs(Map<String, Object> suite, List<Map<String, Object>> into) {
        for (Object spec : list(suite.get("specs"))) {
            into.add((Map<String, Object>) spec);
        }
        for (Object child : list(suite.get("suites"))) {
            collectSpecs((Map<String, Object>) child, into);
        }
    }

    private static boolean tagged(Map<String, Object> spec, String tag) {
        if (list(spec.get("tags")).stream().map(String::valueOf).anyMatch(t -> t.equals(tag) || ("@" + t).equals(tag))) {
            return true;
        }
        return List.of(String.valueOf(spec.get("title")).split("\\s+")).contains(tag);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> items) {
            return (List<Object>) items;
        }
        throw new Refused("a list was expected, not " + value.getClass().getSimpleName());
    }

    /** JSON, enough of it to read a report: objects, arrays, strings with their escapes, numbers, true, false and null. */
    static final class Json {
        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object document() {
            Object value = value();
            space();
            if (at != text.length()) {
                throw new IllegalArgumentException("text after the document at " + at);
            }
            return value;
        }

        private Object value() {
            space();
            if (at >= text.length()) {
                throw new IllegalArgumentException("the document ends early");
            }
            char c = text.charAt(at);
            if (c == '{') {
                Map<String, Object> object = new LinkedHashMap<>();
                at++;
                space();
                if (peek('}')) {
                    return object;
                }
                do {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    object.put(key, value());
                    space();
                } while (peek(','));
                expect('}');
                return object;
            }
            if (c == '[') {
                List<Object> array = new ArrayList<>();
                at++;
                space();
                if (peek(']')) {
                    return array;
                }
                do {
                    array.add(value());
                    space();
                } while (peek(','));
                expect(']');
                return array;
            }
            if (c == '"') {
                return string();
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, at)) {
                    at += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            Matcher number = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matcher(text).region(at, text.length());
            if (number.lookingAt()) {
                at = number.end();
                return number.group();
            }
            throw new IllegalArgumentException("an unexpected character at " + at);
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("an unknown escape at " + at);
                }
            }
            throw new IllegalArgumentException("a string never closed");
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private boolean peek(char c) {
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("'" + c + "' expected at " + at);
            }
        }
    }
}
