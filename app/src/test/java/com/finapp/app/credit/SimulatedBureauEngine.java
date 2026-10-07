package com.finapp.app.credit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated credit bureau {@code bureau-sim-a}, served over loopback HTTP (`P10-TSK-005`) -
 * honest, stateful, deterministic per subject and fault-injectable: the bureau's side of the
 * contract the adapter speaks.
 *
 * <p><strong>The contract that makes a retry safe: dedupe on our reference.</strong> A pull whose
 * {@code Idempotency-Key} was seen before answers the first report - byte for byte - and is not
 * counted again; {@link #pulls()} counts reports really produced, the number every "one pull"
 * assertion reads. The dedupe is one atomic {@code computeIfAbsent}, so ten concurrent callers under
 * one reference produce one report.
 *
 * <p><strong>Deterministic per subject</strong>: a report's figures are derived from a hash of the
 * subject's identifying facts, so the same person reads the same report under any reference.
 *
 * <p>Faults are armed for the next NOVEL pull and consumed once: a partial report, a report stating
 * its balance in a foreign currency, a malformed body, an unknown status, a 503, silence past any
 * client's wait, an answer produced but slower than any client waits, and a report produced then its response lost (the connection closes).
 *
 * <p>Test scope deliberately: simulators are harnesses, never production beans (ADR-0008).
 */
final class SimulatedBureauEngine implements AutoCloseable {

    /** What the next novel pull does instead of answering a clean report. */
    enum Fault { NONE, PARTIAL, FOREIGN_CURRENCY, MALFORMED, UNKNOWN_STATUS, UNAVAILABLE, SILENT, SLOW, LOSE_RESPONSE }

    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"\\\\]*)\"");
    private static final Pattern DATE_OF_BIRTH = Pattern.compile("\"dateOfBirth\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern COUNTRY = Pattern.compile("\"country\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CURRENCY = Pattern.compile("\"currency\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpServer server;
    private final ExecutorService handlers = Executors.newFixedThreadPool(16);
    private final Map<String, String> reports = new ConcurrentHashMap<>();
    private final AtomicInteger pulls = new AtomicInteger();
    private final AtomicReference<Fault> armed = new AtomicReference<>(Fault.NONE);
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile Duration slowness = Duration.ofSeconds(3);

    private SimulatedBureauEngine() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(SimulatedBureauAdapter.REPORTS_PATH, this::report);
        server.setExecutor(handlers);
        server.start();
    }

    static SimulatedBureauEngine start() throws IOException {
        return new SimulatedBureauEngine();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    // ------------------------------------------------------------- the test's levers

    /** Arms the next novel pull's fault; consumed once. */
    void arm(Fault fault) {
        armed.set(fault);
    }

    /** How long a SLOW answer takes - longer than the client's timeout. */
    void slowness(Duration value) {
        slowness = value;
    }

    /** Reports really produced - one per reference, however often it is asked. */
    int pulls() {
        return pulls.get();
    }

    List<String> idempotencyKeys() {
        return List.copyOf(idempotencyKeys);
    }

    List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
    }

    // ------------------------------------------------------------- the bureau

    private void report(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String reference = exchange.getRequestHeaders().getFirst(SimulatedBureauAdapter.IDEMPOTENCY_KEY_HEADER);
        idempotencyKeys.add(String.valueOf(reference));
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        requestBodies.add(body);
        if (reference == null) {
            respond(exchange, 400, "{\"error\":\"missing key\"}");
            return;
        }
        String seen = reports.get(reference);
        if (seen != null) {
            // A repeat under a seen reference answers the first report and costs nothing.
            respond(exchange, 200, seen);
            return;
        }
        Fault fault = armed.getAndSet(Fault.NONE);
        switch (fault) {
            case MALFORMED -> respond(exchange, 200, "{\"status\":\"report_complete\",\"externalScore\":\"7");
            case UNKNOWN_STATUS -> respond(exchange, 200,
                    "{\"status\":\"report_pending_review\",\"retrievedAt\":\"2026-10-07T09:00:00Z\"}");
            case UNAVAILABLE -> respond(exchange, 503, "{\"error\":\"bureau unavailable\"}");
            case SILENT -> {
                // Holds the connection past any client's wait and produces nothing.
                pause(slowness);
                exchange.close();
            }
            default -> {
                String report = reports.computeIfAbsent(reference, key -> {
                    pulls.incrementAndGet();
                    return render(body, fault);
                });
                if (fault == Fault.SLOW) {
                    pause(slowness);
                }
                if (fault == Fault.LOSE_RESPONSE) {
                    // Produced and counted, then the connection closes without an answer.
                    exchange.close();
                    return;
                }
                respond(exchange, 200, report);
            }
        }
    }

    /** The subject's report, derived from a hash of their identifying facts - deterministic. */
    private static String render(String body, Fault fault) {
        String subject = find(NAME, body) + "|" + find(DATE_OF_BIRTH, body) + "|" + find(COUNTRY, body);
        byte[] hash = sha256(subject);
        String currency = find(CURRENCY, body);
        int score = 300 + Math.floorMod(word(hash, 0), 551);
        int accounts = Math.floorMod(word(hash, 4), 10);
        int delinquencies = Math.floorMod(word(hash, 8), 3);
        int defaults = Math.floorMod(word(hash, 12), 2);
        boolean insolvency = Math.floorMod(word(hash, 16), 50) == 0;
        long obligationsMinor = Math.floorMod(word(hash, 20), 150_000);
        long balanceMinor = Math.floorMod(word(hash, 24), 2_000_000);
        StringBuilder report = new StringBuilder("{\"status\":\"")
                .append(fault == Fault.PARTIAL ? "report_partial" : "report_complete")
                .append("\",\"reportRef\":\"BR-").append(Math.floorMod(word(hash, 28), 1_000_000))
                .append("\",\"retrievedAt\":\"2026-10-07T09:00:00Z\"")
                .append(",\"externalScore\":\"").append(score).append('"')
                .append(",\"activeAccounts\":\"").append(accounts).append('"')
                .append(",\"delinquencies24m\":\"").append(delinquencies).append('"');
        if (fault != Fault.PARTIAL) {
            report.append(",\"defaults72m\":\"").append(defaults).append('"');
        }
        report.append(",\"insolvencyFlag\":\"").append(insolvency).append('"')
                .append(",\"monthlyObligations\":\"").append(decimal(obligationsMinor)).append('"')
                .append(",\"monthlyObligationsCurrency\":\"").append(currency).append('"');
        if (fault != Fault.PARTIAL) {
            report.append(",\"totalBalance\":\"").append(decimal(balanceMinor)).append('"')
                    .append(",\"totalBalanceCurrency\":\"")
                    .append(fault == Fault.FOREIGN_CURRENCY ? "USD" : currency).append('"');
        }
        return report.append('}').toString();
    }

    private static String decimal(long minor) {
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    private static int word(byte[] hash, int offset) {
        return ((hash[offset] & 0xff) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
