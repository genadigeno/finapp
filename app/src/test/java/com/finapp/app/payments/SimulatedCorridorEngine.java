package com.finapp.app.payments;

import com.finapp.payments.SimulatedCorridorAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * The simulated corridor provider {@code corridor-sim-a}, served over loopback HTTP (`P9-TSK-014`,
 * ADR-0080) - honest, stateful and fault-injectable: the provider's side of the contract
 * {@code SimulatedCorridorAdapter} speaks.
 *
 * <p><strong>The contract that makes the outbound credit safe: dedupe on our {@code E}.</strong> A
 * credit whose {@code E} was seen before answers the credit's CURRENT state - received or accepted
 * - and never creates a second credit; {@link #credits()} and {@link #creditsOf(String)} count real
 * credits, the numbers every "exactly once" assertion reads. A recall is idempotent on {@code E}:
 * recalled while received, too late once accepted.
 *
 * <p>Grants are single-use: {@link #issueGrant} mints one for a beneficiary the provider holds, the
 * first exchange answers the opaque reference and the attested attributes, a second
 * {@code grant_used}, an unknown one {@code grant_invalid}. Time is the test's ({@link #advance}).
 * The credit's later facts are the test's levers: {@link #accept}, {@link #deliver},
 * {@link #returnCredit}; or {@link #acceptOnReceipt} answers a send accepted at once. Faults are
 * armed per call and consumed once. Every state change emits a signed callback (HMAC-SHA256 over
 * {@code timestamp.body}) to {@link #callbacks()} - a hint for the door `P9-TSK-022` builds, never
 * the outcome (ADR-0083).
 *
 * <p>Test scope deliberately: simulators are harnesses, never production beans (ADR-0008).
 */
public final class SimulatedCorridorEngine implements AutoCloseable {

    /** One signed callback: the body, its timestamp, and the signature over both. */
    record SignedCallback(String body, long timestamp, String signature) {}

    /** What the provider holds behind a grant - the attested attributes and its payee-check word. */
    public record Beneficiary(String country, String currency, String entityType, String payeeCheck) {}

    private static final class Credit {
        final String reference;
        final String destination;
        final BigDecimal amount;
        final String currency;
        volatile String status = "received";
        volatile String providerRef;
        volatile Instant acceptedAt;
        volatile Instant deliveredAt;
        volatile String returnRef;
        volatile Instant returnedAt;
        volatile String reason;

        Credit(String reference, String destination, BigDecimal amount, String currency) {
            this.reference = reference;
            this.destination = destination;
            this.amount = amount;
            this.currency = currency;
        }
    }

    private final HttpServer server;
    private final byte[] callbackKey;
    private final String instance = java.util.UUID.randomUUID().toString().substring(0, 8);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T10:00:00Z"));
    private final Map<String, Beneficiary> grants = new ConcurrentHashMap<>();
    private final Map<String, String> exchanges = new ConcurrentHashMap<>();
    private final java.util.Set<String> usedGrants = ConcurrentHashMap.newKeySet();
    private final Map<String, Beneficiary> destinations = new ConcurrentHashMap<>();
    private final Map<String, Credit> credits = new ConcurrentHashMap<>();
    private final AtomicInteger creditCount = new AtomicInteger();
    private final Map<String, AtomicInteger> creditsByReference = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<SignedCallback> callbacks = new CopyOnWriteArrayList<>();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> armed = new AtomicReference<>("");
    private volatile boolean acceptOnReceipt;

    private SimulatedCorridorEngine(byte[] callbackKey) throws IOException {
        this.callbackKey = callbackKey.clone();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(SimulatedCorridorAdapter.BENEFICIARIES_PATH, this::exchange);
        server.createContext(SimulatedCorridorAdapter.CREDITS_PATH, this::credit);
        server.start();
    }

    public static SimulatedCorridorEngine start(byte[] callbackKey) throws IOException {
        return new SimulatedCorridorEngine(callbackKey);
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    // ------------------------------------------------------------- the test's levers

    void advance(Duration by) {
        now.updateAndGet(instant -> instant.plus(by));
    }

    /** A single-use grant for a beneficiary the provider holds. */
    public String issueGrant(Beneficiary beneficiary) {
        String grant = "grant-" + instance + "-" + sequence.incrementAndGet();
        grants.put(grant, beneficiary);
        return grant;
    }

    /** Sends answer accepted at once instead of received. */
    void acceptOnReceipt(boolean value) {
        acceptOnReceipt = value;
    }

    /** The received credit {@code reference} is accepted (the provider commits). */
    void accept(String reference) {
        Credit credit = credits.get(reference);
        if (credit.status.equals("received")) {
            accepted(credit);
        }
    }

    void deliver(String reference) {
        Credit credit = credits.get(reference);
        credit.deliveredAt = now.get();
        callback(credit);
    }

    /** The beneficiary's bank sends the whole credit back. */
    void returnCredit(String reference) {
        Credit credit = credits.get(reference);
        credit.returnRef = "XR-" + instance + "-" + sequence.incrementAndGet();
        credit.returnedAt = now.get();
        callback(credit);
    }

    /** The next novel send is rejected with {@code reason}. */
    void rejectNextSend(String reason) {
        armed.set("reject:" + reason);
    }

    /** The next send ACTS, then its response is lost (the connection closes). */
    public void loseNextResponse() {
        armed.set("lose");
    }

    /** The next answer, on any path, carries {@code status} - a word the adapter does not know. */
    void answerUnknownStatusNext(String status) {
        armed.set("status:" + status);
    }

    void malformedNext() {
        armed.set("malformed");
    }

    void serverErrorNext() {
        armed.set("500");
    }

    /** The next inquiry reports its return amount one digit past the currency's scale. */
    void overPreciseNextReturn() {
        armed.set("overprecise");
    }

    // ------------------------------------------------------------- what the test reads

    /** Real credits created - the "exactly once" count. */
    public int credits() {
        return creditCount.get();
    }

    public int creditsOf(String reference) {
        AtomicInteger count = creditsByReference.get(reference);
        return count == null ? 0 : count.get();
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

    private void exchange(HttpExchange exchange) throws IOException {
        record(exchange);
        String body = read(exchange);
        String fault = armed.getAndSet("");
        if (answeredByFault(exchange, fault)) {
            return;
        }
        String reference = field(body, "reference").orElse("");
        String seen = exchanges.get(reference);
        if (seen != null) {
            respond(exchange, 200, seen);
            return;
        }
        String grant = field(body, "grant").orElse("");
        Beneficiary beneficiary = grants.remove(grant);
        String answer;
        if (beneficiary == null) {
            answer = usedGrants.contains(grant)
                    ? "{\"status\":\"refused\",\"reason\":\"grant_used\"}"
                    : "{\"status\":\"refused\",\"reason\":\"grant_invalid\"}";
        } else {
            String destination = "XD-" + instance + "-" + sequence.incrementAndGet();
            destinations.put(destination, beneficiary);
            answer = "{\"status\":\"exchanged\",\"destinationRef\":\"" + destination + "\",\"suffix\":\""
                    + String.format("%04d", sequence.get() % 10_000) + "\",\"payeeCheck\":\""
                    + beneficiary.payeeCheck() + "\",\"country\":\"" + beneficiary.country()
                    + "\",\"currency\":\"" + beneficiary.currency() + "\",\"entityType\":\""
                    + beneficiary.entityType() + "\"}";
            usedGrants.add(grant);
        }
        exchanges.put(reference, answer);
        respond(exchange, 200, answer);
    }

    private void credit(HttpExchange exchange) throws IOException {
        record(exchange);
        String path = exchange.getRequestURI().getPath();
        String rest = path.substring(SimulatedCorridorAdapter.CREDITS_PATH.length());
        String fault = armed.getAndSet("");
        if ("GET".equals(exchange.getRequestMethod())) {
            if (answeredByFault(exchange, fault)) {
                return;
            }
            Credit credit = credits.get(rest.substring(1));
            respond(exchange, 200, credit == null ? "{\"status\":\"unrecognised\"}" : state(credit, fault));
            return;
        }
        if (rest.endsWith(SimulatedCorridorAdapter.RECALL_SUFFIX)) {
            if (answeredByFault(exchange, fault)) {
                return;
            }
            String reference = rest.substring(1, rest.length() - SimulatedCorridorAdapter.RECALL_SUFFIX.length());
            Credit credit = credits.get(reference);
            String answer;
            if (credit == null) {
                answer = "{\"status\":\"unrecognised\"}";
            } else if (credit.status.equals("received") || credit.status.equals("recalled")) {
                credit.status = "recalled";
                answer = "{\"status\":\"recalled\"}";
            } else {
                answer = "{\"status\":\"too_late\"}";
            }
            respond(exchange, 200, answer);
            return;
        }
        String body = read(exchange);
        if (answeredByFault(exchange, fault)) {
            return;
        }
        String reference = exchange.getRequestHeaders().getFirst(SimulatedCorridorAdapter.IDEMPOTENCY_KEY_HEADER);
        // THE CONTRACT: our E first. A seen E answers the credit's current state - never a second credit.
        Credit seen = reference == null ? null : credits.get(reference);
        if (seen != null) {
            respond(exchange, 200, sendAnswer(seen));
            return;
        }
        Credit credit = new Credit(
                reference,
                field(body, "destinationRef").orElse(""),
                new BigDecimal(field(body, "amount").orElse("0")),
                field(body, "currency").orElse(""));
        Beneficiary beneficiary = destinations.get(credit.destination);
        if (reference == null) {
            respond(exchange, 200, "{\"status\":\"rejected\",\"reason\":\"limit\"}");
            return;
        }
        if (fault.startsWith("reject:")) {
            credit.status = "rejected";
            credit.reason = fault.substring(7);
        } else if (beneficiary == null) {
            credit.status = "rejected";
            credit.reason = "beneficiary_closed";
        } else if (!beneficiary.currency().equals(credit.currency)) {
            credit.status = "rejected";
            credit.reason = "currency_not_carried";
        }
        credits.put(reference, credit);
        if (!credit.status.equals("rejected")) {
            creditCount.incrementAndGet();
            creditsByReference.computeIfAbsent(reference, ignored -> new AtomicInteger()).incrementAndGet();
            if (acceptOnReceipt) {
                accepted(credit);
            } else {
                callback(credit);
            }
        }
        if (fault.equals("lose")) {
            exchange.close();
            return;
        }
        respond(exchange, 200, sendAnswer(credit));
    }

    private void accepted(Credit credit) {
        credit.status = "accepted";
        credit.providerRef = "XP-" + instance + "-" + sequence.incrementAndGet();
        credit.acceptedAt = now.get();
        callback(credit);
    }

    private static String sendAnswer(Credit credit) {
        return switch (credit.status) {
            case "accepted" -> "{\"status\":\"accepted\",\"providerRef\":\"" + credit.providerRef
                    + "\",\"acceptedAt\":\"" + credit.acceptedAt + "\"}";
            case "rejected" -> "{\"status\":\"rejected\",\"reason\":\"" + credit.reason + "\"}";
            default -> "{\"status\":\"received\"}";
        };
    }

    private static String state(Credit credit, String fault) {
        if (!credit.status.equals("accepted")) {
            return credit.status.equals("rejected")
                    ? "{\"status\":\"rejected\",\"reason\":\"" + credit.reason + "\"}"
                    : "{\"status\":\"" + credit.status + "\"}";
        }
        StringBuilder answer = new StringBuilder("{\"status\":\"accepted\",\"providerRef\":\"")
                .append(credit.providerRef).append("\",\"acceptedAt\":\"").append(credit.acceptedAt)
                .append("\",\"currency\":\"").append(credit.currency).append('"');
        if (credit.deliveredAt != null) {
            answer.append(",\"deliveredAt\":\"").append(credit.deliveredAt).append('"');
        }
        if (credit.returnRef != null) {
            String amount = fault.equals("overprecise")
                    ? credit.amount.toPlainString() + "1"
                    : credit.amount.toPlainString();
            answer.append(",\"returnRef\":\"").append(credit.returnRef)
                    .append("\",\"returnAmount\":\"").append(amount)
                    .append("\",\"returnedAt\":\"").append(credit.returnedAt).append('"');
        }
        return answer.append('}').toString();
    }

    private void callback(Credit credit) {
        long timestamp = now.get().getEpochSecond();
        String body = "{\"eventId\":\"xcb-" + instance + "-" + sequence.incrementAndGet()
                + "\",\"endToEndRef\":\"" + credit.reference + "\"," + state(credit, "").substring(1);
        callbacks.add(new SignedCallback(body, timestamp, sign(callbackKey, timestamp, body)));
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
        Optional.ofNullable(exchange.getRequestHeaders().getFirst(SimulatedCorridorAdapter.IDEMPOTENCY_KEY_HEADER))
                .ifPresent(idempotencyKeys::add);
        Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")).ifPresent(authorizations::add);
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
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
