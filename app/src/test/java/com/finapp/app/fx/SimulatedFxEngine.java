package com.finapp.app.fx;

import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The simulated FX provider {@code fx-sim-a}, served over loopback HTTP (`P9-TSK-006`, ADR-0077 §4)
 * - honest, stateful and fault-injectable, the provider's side of the contract the adapter speaks.
 *
 * <p><strong>The contract that makes the cover safe: dedupe on our reference BEFORE judging the
 * quote's validity.</strong> An execution request whose {@code T} was seen before answers the
 * original outcome - executed or rejected - whatever has happened to the quote since; only a
 * novel {@code T} is judged against the quote's lock. {@link #executions()} counts real
 * executions, the number every "exactly once" assertion reads.
 *
 * <p>Time is the test's ({@link #advance}), so lock expiry is deterministic. Faults are armed per
 * call and consumed once: lose the next execution's response AFTER executing, change the price,
 * deviate from the quote, answer an unknown status, an over-precise rate, a malformed body or a
 * 5xx. Every execution also emits a signed callback (HMAC-SHA256 over {@code timestamp.body}) to
 * {@link #callbacks()} - a hint for the door `-012` builds, never the outcome.
 *
 * <p>Test scope deliberately: simulators are harnesses and demo stand-ups, never production beans
 * (ADR-0008, the ADR-0049 precedent). Later tasks' database suites reuse it.
 */
final class SimulatedFxEngine implements AutoCloseable {

    /** One signed callback: the body, its timestamp, and the signature over both. */
    record SignedCallback(String body, long timestamp, String signature) {}

    private record Quote(
            String source,
            String destination,
            BigDecimal sold,
            BigDecimal bought,
            String rate,
            Instant validUntil) {}

    private static final Map<String, String> DEFAULT_RATES =
            Map.of("EUR/USD", "1.0850240000", "USD/EUR", "0.9216000000", "GBP/USD", "1.2700000000",
                    "USD/JPY", "149.5000000000", "EUR/JPY", "162.2100000000", "USD/BHD", "0.3760000000",
                    "EUR/BHD", "0.4080000000", "BHD/JPY", "397.5121000000", "EUR/GBP", "0.8543000000",
                    "GBP/EUR", "1.1705000000");

    private final HttpServer server;
    private final byte[] callbackKey;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<String, String> outcomes = new ConcurrentHashMap<>();
    private final Map<String, String> rates = new ConcurrentHashMap<>(DEFAULT_RATES);
    private final AtomicInteger executions = new AtomicInteger();
    private final Map<String, AtomicInteger> executionsByReference = new ConcurrentHashMap<>();
    private final AtomicInteger quoteRequests = new AtomicInteger();
    private final AtomicInteger quoteSequence = new AtomicInteger();
    private final AtomicInteger tradeSequence = new AtomicInteger();
    private final String instance = java.util.UUID.randomUUID().toString().substring(0, 8);
    private final List<SignedCallback> callbacks = new CopyOnWriteArrayList<>();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> armed = new AtomicReference<>("");
    private volatile Duration validFor = Duration.ofSeconds(60);
    private volatile long deviationMinor;

    private SimulatedFxEngine(byte[] callbackKey) throws IOException {
        this.callbackKey = callbackKey.clone();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(SimulatedFxProviderAdapter.QUOTES_PATH, this::quote);
        server.createContext(SimulatedFxProviderAdapter.EXECUTIONS_PATH, this::execution);
        server.start();
    }

    static SimulatedFxEngine start(byte[] callbackKey) throws IOException {
        return new SimulatedFxEngine(callbackKey);
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    // ------------------------------------------------------------- the test's levers

    void advance(Duration by) {
        now.updateAndGet(instant -> instant.plus(by));
    }

    void validFor(Duration value) {
        validFor = value;
    }

    void rate(String pair, String value) {
        rates.put(pair, value);
    }

    /** The next execution EXECUTES, then its response is lost (the connection closes). */
    void loseNextExecutionResponse() {
        armed.set("lose");
    }

    /** The next novel execution is refused: the provider moved its price. */
    void changePriceOnNextExecution() {
        armed.set("price");
    }

    /** The next novel execution buys {@code minorUnits} fewer than the quote stated. */
    void deviateNextExecution(long minorUnits) {
        deviationMinor = minorUnits;
        armed.set("deviate");
    }

    /** The next answer, on any path, carries {@code status} - a word the adapter does not know. */
    void answerUnknownStatusNext(String status) {
        armed.set("status:" + status);
    }

    /** The next quote carries an eleven-decimal rate. */
    void overPreciseNextQuote() {
        armed.set("overprecise");
    }

    /** The next answer is not JSON. */
    void malformedNext() {
        armed.set("malformed");
    }

    /** The next answer is a 500. */
    void serverErrorNext() {
        armed.set("500");
    }

    // ------------------------------------------------------------- what the test reads

    /** Every firm-quote request received - the RFQ count the quote suites assert (`P9-TSK-008`). */
    int quoteRequests() {
        return quoteRequests.get();
    }

    /** Real executions under one of our references - the per-cover "exactly once" count (P9-TSK-012). */
    int executionsOf(String reference) {
        AtomicInteger count = executionsByReference.get(reference);
        return count == null ? 0 : count.get();
    }

    int executions() {
        return executions.get();
    }

    List<SignedCallback> callbacks() {
        return List.copyOf(callbacks);
    }

    List<String> idempotencyKeys() {
        return List.copyOf(idempotencyKeys);
    }

    List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    /** Whether {@code callback} carries a signature made with {@code key} over its own bytes. */
    static boolean verifies(SignedCallback callback, byte[] key) {
        return sign(key, callback.timestamp(), callback.body()).equals(callback.signature());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------- the provider

    private void quote(HttpExchange exchange) throws IOException {
        quoteRequests.incrementAndGet();
        record(exchange);
        String body = read(exchange);
        String fault = armed.getAndSet("");
        if (answeredByFault(exchange, fault)) {
            return;
        }
        String source = field(body, "source").orElse("");
        String destination = field(body, "destination").orElse("");
        String pair = source + "/" + destination;
        if (!supported(source) || !supported(destination) || !rates.containsKey(pair)) {
            respond(exchange, 200, "{\"status\":\"declined\",\"reason\":\"pair_not_quoted\"}");
            return;
        }
        String rate = fault.equals("overprecise") ? "1.08502400001" : rates.get(pair);
        BigDecimal r = new BigDecimal(rates.get(pair));
        BigDecimal amount = new BigDecimal(field(body, "amount").orElse("0"));
        boolean fixedSource = "FIXED_SOURCE".equals(field(body, "fixedSide").orElse(""));
        BigDecimal sold;
        BigDecimal bought;
        if (fixedSource) {
            sold = amount;
            bought = amount.multiply(r).setScale(minorUnits(destination), RoundingMode.HALF_EVEN);
        } else {
            bought = amount;
            sold = amount.divide(r, minorUnits(source), RoundingMode.HALF_EVEN);
        }
        String quoteRef = "PQ-" + quoteSequence.incrementAndGet();
        quotes.put(quoteRef, new Quote(source, destination, sold, bought, rate, now.get().plus(validFor)));
        String counter = fixedSource ? bought.toPlainString() : sold.toPlainString();
        String counterCurrency = fixedSource ? destination : source;
        respond(
                exchange,
                200,
                "{\"status\":\"quoted\",\"quoteRef\":\"" + quoteRef + "\",\"rate\":\"" + rate
                        + "\",\"counter\":\"" + counter + "\",\"counterCurrency\":\""
                        + counterCurrency + "\",\"validForSeconds\":\"" + validFor.toSeconds()
                        + "\",\"valueDate\":\""
                        + now.get().atZone(ZoneOffset.UTC).toLocalDate().plusDays(2) + "\"}");
    }

    private void execution(HttpExchange exchange) throws IOException {
        record(exchange);
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(exchange.getRequestMethod())) {
            String reference = path.substring(SimulatedFxProviderAdapter.EXECUTIONS_PATH.length() + 1);
            String fault = armed.getAndSet("");
            if (answeredByFault(exchange, fault)) {
                return;
            }
            respond(exchange, 200, outcomes.getOrDefault(reference, "{\"status\":\"unrecognised\"}"));
            return;
        }
        String body = read(exchange);
        String reference = exchange.getRequestHeaders().getFirst(SimulatedFxProviderAdapter.IDEMPOTENCY_KEY_HEADER);
        String fault = armed.getAndSet("");
        if (answeredByFault(exchange, fault)) {
            return;
        }
        // THE CONTRACT: our reference first. A seen T answers its original outcome, whatever the
        // quote's lock says now - so a re-send after the lock lapsed returns the execution.
        String seen = reference == null ? null : outcomes.get(reference);
        if (seen != null) {
            respond(exchange, 200, seen);
            return;
        }
        String outcome;
        Quote quote = quotes.get(field(body, "quoteRef").orElse(""));
        if (reference == null) {
            outcome = "{\"status\":\"rejected\",\"reason\":\"limit\"}";
        } else if (quote == null || now.get().isAfter(quote.validUntil())) {
            outcome = "{\"status\":\"rejected\",\"reason\":\"quote_expired\"}";
        } else if (fault.equals("price")) {
            outcome = "{\"status\":\"rejected\",\"reason\":\"price_changed\"}";
        } else {
            BigDecimal bought = quote.bought();
            if (fault.equals("deviate")) {
                bought = bought.subtract(BigDecimal.valueOf(deviationMinor, minorUnits(quote.destination())));
            }
            // Unique across simulator instances, as a real provider's trade references are: the
            // suites share one database and fx.cover_execution holds (provider, trade ref) once.
            String tradeRef = "FT-" + instance + "-" + tradeSequence.incrementAndGet();
            outcome =
                    "{\"status\":\"executed\",\"tradeRef\":\"" + tradeRef + "\",\"sold\":\""
                            + quote.sold().toPlainString() + "\",\"soldCurrency\":\"" + quote.source()
                            + "\",\"bought\":\"" + bought.toPlainString() + "\",\"boughtCurrency\":\""
                            + quote.destination() + "\",\"rate\":\"" + quote.rate()
                            + "\",\"valueDate\":\""
                            + now.get().atZone(ZoneOffset.UTC).toLocalDate().plusDays(2) + "\"}";
            executions.incrementAndGet();
            executionsByReference.computeIfAbsent(reference, ignored -> new AtomicInteger()).incrementAndGet();
            long timestamp = now.get().getEpochSecond();
            // The callback names the provider's event and OUR reference - the door's inquiry subject
            // (P9-TSK-012) - beside the outcome it claims, which the door never trusts.
            String callback = "{\"eventId\":\"fxcb-" + tradeRef + "\",\"clientRef\":\"" + reference + "\","
                    + outcome.substring(1);
            callbacks.add(new SignedCallback(callback, timestamp, sign(callbackKey, timestamp, callback)));
        }
        if (reference != null) {
            outcomes.put(reference, outcome);
        }
        if (fault.equals("lose")) {
            // Executed and remembered - and the answer never arrives.
            exchange.close();
            return;
        }
        respond(exchange, 200, outcome);
    }

    private boolean answeredByFault(HttpExchange exchange, String fault) throws IOException {
        if (fault.startsWith("status:")) {
            respond(exchange, 200, "{\"status\":\"" + fault.substring(7) + "\"}");
            return true;
        }
        if (fault.equals("malformed")) {
            respond(exchange, 200, "<html>not an answer</html>");
            return true;
        }
        if (fault.equals("500")) {
            respond(exchange, 500, "{\"error\":\"internal\"}");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------- plumbing

    private void record(HttpExchange exchange) {
        Optional.ofNullable(exchange.getRequestHeaders().getFirst(SimulatedFxProviderAdapter.IDEMPOTENCY_KEY_HEADER))
                .ifPresent(idempotencyKeys::add);
        Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")).ifPresent(authorizations::add);
    }

    private static boolean supported(String code) {
        try {
            return SupportedCurrencies.ALL.contains(CurrencyCode.of(code));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static int minorUnits(String code) {
        return CurrencyCode.of(code).minorUnits();
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Optional<String> field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String sign(byte[] key, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
