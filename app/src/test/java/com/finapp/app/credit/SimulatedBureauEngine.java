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
import java.time.Instant;
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
 * reference header was seen before answers the first report - byte for byte - and is not
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
 * <p><strong>Three wires, one engine</strong> (`P10-TSK-007`, `P10-TSK-021`): {@link #start()} serves
 * {@code bureau-sim-a}'s report wire, {@link #startFinancialData()} {@code findata-sim-a}'s summary wire and
 * {@link #startSecondBureau()} {@code bureau-sim-b}'s consumer-file wire - the same dedupe, determinism and faults,
 * each provider's own path, headers, statuses and fields. The two bureaus derive a person's figures one way, so one
 * subject's report and file carry the same facts in two vocabularies.
 *
 * <p>Test scope deliberately: simulators are harnesses, never production beans (ADR-0008).
 */
final class SimulatedBureauEngine implements AutoCloseable {

    /**
     * One provider's wire: its path, the headers our reference and its key travel in, the request's subject and
     * currency fields, its malformed and unknown-status bodies, and its renderer.
     */
    private record Wire(String path, String referenceHeader, String keyHeader, Pattern name, Pattern dateOfBirth,
            Pattern country, Pattern currency, String malformed, String unknownStatus, Renderer renderer) {}

    @FunctionalInterface
    private interface Renderer {
        /** Renders the answer for {@code subject} (its identifying facts, joined) in {@code currency}. */
        String render(String subject, String currency, Fault fault, Instant retrievedAt);
    }

    private static final Wire BUREAU = new Wire(
            SimulatedBureauAdapter.REPORTS_PATH, SimulatedBureauAdapter.IDEMPOTENCY_KEY_HEADER, "Authorization",
            text("name"), text("dateOfBirth"), text("country"), text("currency"),
            "{\"status\":\"report_complete\",\"externalScore\":\"7",
            "{\"status\":\"report_pending_review\",\"retrievedAt\":\"2026-10-07T09:00:00Z\"}",
            SimulatedBureauEngine::render);

    private static final Wire FINANCIAL_DATA = new Wire(
            SimulatedFinancialDataAdapter.SUMMARIES_PATH, SimulatedFinancialDataAdapter.IDEMPOTENCY_KEY_HEADER,
            "Authorization", text("name"), text("dateOfBirth"), text("country"), text("currency"),
            "{\"status\":\"summary_complete\",\"verifiedMonthlyIncome\":\"3",
            "{\"status\":\"summary_pending_consent\",\"retrievedAt\":\"2026-10-07T09:00:00Z\"}",
            SimulatedBureauEngine::renderSummary);

    /** {@code bureau-sim-b}'s consumer-file wire (`P10-TSK-021`) - none of its words is {@code bureau-sim-a}'s. */
    private static final Wire SECOND_BUREAU = new Wire(
            SimulatedSecondBureauAdapter.FILES_PATH, SimulatedSecondBureauAdapter.REFERENCE_HEADER,
            SimulatedSecondBureauAdapter.KEY_HEADER,
            text("full_name"), text("birth_date"), text("residence"), text("reporting_currency"),
            "{\"file_status\":\"FILE_FULL\",\"risk_grade_score\":\"7",
            "{\"file_status\":\"FILE_LOCKED\",\"generated_at\":\"1791363600\"}",
            SimulatedBureauEngine::renderFile);

    /** What the next novel pull does instead of answering a clean report. */
    enum Fault { NONE, PARTIAL, FOREIGN_CURRENCY, MALFORMED, UNKNOWN_STATUS, UNAVAILABLE, SILENT, SLOW, LOSE_RESPONSE }

    private final HttpServer server;
    private final ExecutorService handlers = Executors.newFixedThreadPool(16);
    private final Map<String, String> reports = new ConcurrentHashMap<>();
    private final AtomicInteger pulls = new AtomicInteger();
    private final AtomicReference<Fault> armed = new AtomicReference<>(Fault.NONE);
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile Duration slowness = Duration.ofSeconds(3);
    private volatile String note = "";
    private volatile Instant retrievedAt = Instant.parse("2026-10-07T09:00:00Z");

    private final Wire wire;

    private SimulatedBureauEngine(Wire wire) throws IOException {
        this.wire = wire;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(wire.path(), this::report);
        server.setExecutor(handlers);
        server.start();
    }

    /** The bureau's report wire. */
    static SimulatedBureauEngine start() throws IOException {
        return new SimulatedBureauEngine(BUREAU);
    }

    /** The financial-data provider's summary wire (`P10-TSK-007`). */
    static SimulatedBureauEngine startFinancialData() throws IOException {
        return new SimulatedBureauEngine(FINANCIAL_DATA);
    }

    /**
     * The second bureau's consumer-file wire (`P10-TSK-021`): the same person's file carries the same figures as
     * {@link #start()}'s report - one derivation, two vocabularies - so the two adapters must normalise it alike.
     */
    static SimulatedBureauEngine startSecondBureau() throws IOException {
        return new SimulatedBureauEngine(SECOND_BUREAU);
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

    /** A note every later report carries in a field the adapter does not read - the storm's needle (`P10-TSK-006`). */
    void note(String value) {
        note = value;
    }

    /**
     * The retrieval instant every later report states - fixed by default; a case whose records must pass a policy's
     * freshness judgement on the database's clock stamps a recent one (`P10-TSK-021`).
     */
    void retrievedAt(Instant value) {
        retrievedAt = value;
    }

    /** Reports really produced - one per reference, however often it is asked. */
    int pulls() {
        return pulls.get();
    }

    /** Every reference header received, in order. */
    List<String> idempotencyKeys() {
        return List.copyOf(idempotencyKeys);
    }

    /** Every key header received, in order. */
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
        String reference = exchange.getRequestHeaders().getFirst(wire.referenceHeader());
        idempotencyKeys.add(String.valueOf(reference));
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst(wire.keyHeader())));
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
            case MALFORMED -> respond(exchange, 200, wire.malformed());
            case UNKNOWN_STATUS -> respond(exchange, 200, wire.unknownStatus());
            case UNAVAILABLE -> respond(exchange, 503, "{\"error\":\"provider unavailable\"}");
            case SILENT -> {
                // Holds the connection past any client's wait and produces nothing.
                pause(slowness);
                exchange.close();
            }
            default -> {
                String carried = note;
                String subject = find(wire.name(), body) + "|" + find(wire.dateOfBirth(), body) + "|"
                        + find(wire.country(), body);
                String report = reports.computeIfAbsent(reference, key -> {
                    pulls.incrementAndGet();
                    String rendered = wire.renderer().render(subject, find(wire.currency(), body), fault, retrievedAt);
                    return carried.isEmpty() ? rendered
                            : rendered.substring(0, rendered.length() - 1) + ",\"bureauNote\":\"" + carried + "\"}";
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

    /** A person's bureau figures, derived from a hash of their identifying facts - one derivation for both bureaus. */
    private record Figures(int score, int accounts, int delinquencies, int defaults, boolean insolvency,
            long obligationsMinor, long balanceMinor, int fileNumber) {
        static Figures of(String subject) {
            byte[] hash = sha256(subject);
            return new Figures(300 + Math.floorMod(word(hash, 0), 551), Math.floorMod(word(hash, 4), 10),
                    Math.floorMod(word(hash, 8), 3), Math.floorMod(word(hash, 12), 2),
                    Math.floorMod(word(hash, 16), 50) == 0, Math.floorMod(word(hash, 20), 150_000),
                    Math.floorMod(word(hash, 24), 2_000_000), Math.floorMod(word(hash, 28), 1_000_000));
        }
    }

    /** The subject's report on {@code bureau-sim-a}'s wire - deterministic. */
    private static String render(String subject, String currency, Fault fault, Instant retrievedAt) {
        Figures figures = Figures.of(subject);
        StringBuilder report = new StringBuilder("{\"status\":\"")
                .append(fault == Fault.PARTIAL ? "report_partial" : "report_complete")
                .append("\",\"reportRef\":\"BR-").append(figures.fileNumber())
                .append("\",\"retrievedAt\":\"").append(retrievedAt).append('"')
                .append(",\"externalScore\":\"").append(figures.score()).append('"')
                .append(",\"activeAccounts\":\"").append(figures.accounts()).append('"')
                .append(",\"delinquencies24m\":\"").append(figures.delinquencies()).append('"');
        if (fault != Fault.PARTIAL) {
            report.append(",\"defaults72m\":\"").append(figures.defaults()).append('"');
        }
        report.append(",\"insolvencyFlag\":\"").append(figures.insolvency()).append('"')
                .append(",\"monthlyObligations\":\"").append(decimal(figures.obligationsMinor())).append('"')
                .append(",\"monthlyObligationsCurrency\":\"").append(currency).append('"');
        if (fault != Fault.PARTIAL) {
            report.append(",\"totalBalance\":\"").append(decimal(figures.balanceMinor())).append('"')
                    .append(",\"totalBalanceCurrency\":\"")
                    .append(fault == Fault.FOREIGN_CURRENCY ? "USD" : currency).append('"');
        }
        return report.append('}').toString();
    }

    /**
     * The subject's consumer file on {@code bureau-sim-b}'s wire (`P10-TSK-021`) - the same figures as {@link #render},
     * in its own words: snake_case, epoch seconds, a {@code Y}/{@code N} marker, amounts as minor units with their code.
     * A thin file omits the same two figures a partial report does.
     */
    private static String renderFile(String subject, String currency, Fault fault, Instant retrievedAt) {
        Figures figures = Figures.of(subject);
        StringBuilder file = new StringBuilder("{\"file_status\":\"")
                .append(fault == Fault.PARTIAL ? "FILE_THIN" : "FILE_FULL")
                .append("\",\"file_id\":\"CF-").append(figures.fileNumber())
                .append("\",\"generated_at\":\"").append(retrievedAt.getEpochSecond()).append('"')
                .append(",\"risk_grade_score\":\"").append(figures.score()).append('"')
                .append(",\"open_tradelines\":\"").append(figures.accounts()).append('"')
                .append(",\"late_payments_24m\":\"").append(figures.delinquencies()).append('"');
        if (fault != Fault.PARTIAL) {
            file.append(",\"charge_offs_72m\":\"").append(figures.defaults()).append('"');
        }
        file.append(",\"bankruptcy_marker\":\"").append(figures.insolvency() ? "Y" : "N").append('"')
                .append(",\"monthly_payments\":{\"amount_minor\":\"").append(figures.obligationsMinor())
                .append("\",\"currency_code\":\"").append(currency).append("\"}");
        if (fault != Fault.PARTIAL) {
            file.append(",\"outstanding_debt\":{\"amount_minor\":\"").append(figures.balanceMinor())
                    .append("\",\"currency_code\":\"").append(fault == Fault.FOREIGN_CURRENCY ? "USD" : currency)
                    .append("\"}");
        }
        return file.append('}').toString();
    }

    /** The subject's financial-data summary, derived from a hash of their identifying facts - deterministic. */
    private static String renderSummary(String subject, String currency, Fault fault, Instant retrievedAt) {
        byte[] hash = sha256("findata|" + subject);
        long incomeMinor = 150_000 + Math.floorMod(word(hash, 0), 650_000);
        long expenditureMinor = 50_000 + Math.floorMod(word(hash, 4), 300_000);
        StringBuilder summary = new StringBuilder("{\"status\":\"")
                .append(fault == Fault.PARTIAL ? "summary_partial" : "summary_complete")
                .append("\",\"summaryRef\":\"FS-").append(Math.floorMod(word(hash, 8), 1_000_000))
                .append("\",\"retrievedAt\":\"").append(retrievedAt).append('"')
                .append(",\"verifiedMonthlyIncome\":\"").append(decimal(incomeMinor)).append('"')
                .append(",\"verifiedMonthlyIncomeCurrency\":\"").append(currency).append('"');
        if (fault != Fault.PARTIAL) {
            summary.append(",\"committedMonthlyExpenditure\":\"").append(decimal(expenditureMinor)).append('"')
                    .append(",\"committedMonthlyExpenditureCurrency\":\"")
                    .append(fault == Fault.FOREIGN_CURRENCY ? "USD" : currency).append('"');
        }
        return summary.append('}').toString();
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

    private static Pattern text(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"");
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
