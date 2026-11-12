import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The providers of the kind rehearsal (P15-TSK-012), one test-only pod in the namespace finapp-rehearsal: the card PSP, card
 * tokenisation and the payout rail, over TLS from the rehearsal's local CA. A single source file run by the JDK image, so nothing is
 * built or published for it.
 *
 * <p>It speaks only the words the adapters' code defines (SimulatedCardPspAdapter, SimulatedTokenisationAdapter,
 * SimulatedPayoutProvider) and it succeeds, but for the two holds below: the rehearsal is about a deployment, and a provider that fails
 * on demand is the test harness's job. It behaves as a real provider must in one respect, because the census reads it: <strong>one effect per
 * Idempotency-Key</strong>. A key seen before gets the answer it got the first time, and {@code /tmp/counts.txt}, rewritten every two
 * seconds, says how many requests arrived and how many distinct keys, per path, so "one effect per key" is counted on the provider's
 * side as well as the ledger's. The rehearsal reads it with {@code kubectl exec}; no port serves it.
 *
 * <p>While {@code /tmp/hold-payouts} exists, a payout's answer is held after its effect is taken, for at most twenty seconds, and the
 * caller's address and key are written to {@code /tmp/held.txt}. That is the worst moment for a replica to die, and the kill needs one
 * it can name: the provider has moved the money and the platform has not heard.
 *
 * <p>The payout rail's inquiry ({@code GET /payouts/{reference}}, SimulatedPayoutProvider's query, which the payout resolution sweep sends
 * for a payout it holds UNKNOWN) answers what this provider knows of that reference: the answer its creation got, or
 * {@code {"status":"unrecognised"}} for a reference it never saw. It takes no effect and holds no key.
 *
 * <p>While {@code /tmp/hold-inquiries} exists (P15-TSK-032: the {@code MerchantPayoutUnknownAged} page, runbook 11.22), the rail is a
 * provider that has gone quiet: a payout's creation takes its effect and is then left unanswered for as long as the file exists, at most
 * two minutes, past the application's payout timeout, so the platform holds the payout UNKNOWN; and every inquiry is answered {@code 503},
 * which the adapter reads as no answer, so the sweep cannot conclude it. Removing the file lets the next inquiry find the payout paid.
 *
 * <p>FX (P15-TSK-032: runbook sections 2 and 4 need a price and a cover). The reference source, in HttpReferenceRateSource's words:
 * {@code GET /fx/reference/rates} answers one {@code BASE/QUOTE,RATE,OBSERVED_AT} line for each of the ten canonical pairs
 * (ReferenceSourceDeclaration), stamped at the poll. The provider {@code fx-sim-a}, in SimulatedFxProviderAdapter's words and with
 * SimulatedFxEngine's contract: {@code POST /fx/quotes} quotes every ordered pair of EUR, GBP, USD, JPY and BHD at the reference mid
 * (so a quote sits at the centre of pricing policy v1's band), its counter rounded half-even to the counter currency's minor units
 * (ConversionPlan's coherence rule), held ninety seconds; {@code POST /fx/executions} is deduplicated on our reference BEFORE the quote's
 * lock is judged, one effect per Idempotency-Key as everywhere here, an executed or a rejected outcome alike; {@code GET
 * /fx/executions/{reference}} answers the recorded outcome, or {@code unrecognised}. It sends no callback: a callback is a hint, and
 * the cover sweep's inquiry is the path. The mid rates are FxTestClient's.
 *
 * <p>No port serves plain HTTP. It holds no credential and verifies none: a bearer key is the adapter's confinement rule to meet, and a
 * simulator that checked it would prove only that the rehearsal's generator and the application agree.
 */
public final class Simulator {

    private static final Map<String, String> ANSWERED = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> KEYS = new ConcurrentHashMap<>();
    private static final Path HOLD = Path.of("/tmp/hold-payouts");
    private static final Path HELD = Path.of("/tmp/held.txt");
    private static final Path HOLD_INQUIRIES = Path.of("/tmp/hold-inquiries");
    private static final String PAYOUTS = "/payouts";

    private static final String FX_REFERENCE = "/fx/reference/rates";
    private static final String FX_QUOTES = "/fx/quotes";
    private static final String FX_EXECUTIONS = "/fx/executions";
    private static final Duration FX_QUOTE_HELD = Duration.ofSeconds(90);
    private static final String FX_INSTANCE = UUID.randomUUID().toString().substring(0, 8);
    private static final AtomicLong FX_SEQUENCE = new AtomicLong();
    private static final Map<String, FxQuote> FX_QUOTED = new ConcurrentHashMap<>();

    /** The reference mids of the ten canonical pairs, market direction (FxTestClient.MIDS, ReferenceSourceDeclaration's order). */
    private static final Map<String, String> FX_MIDS = new LinkedHashMap<>();

    /** The provider's rate for every ordered pair: the mid, and its inverse at the ten decimals an exchange rate admits. */
    private static final Map<String, BigDecimal> FX_RATES = new ConcurrentHashMap<>();

    static {
        String[][] mids = {{"EUR/GBP", "0.8543"}, {"EUR/USD", "1.0850"}, {"EUR/JPY", "162.21"}, {"EUR/BHD", "0.4080"}, {"GBP/USD", "1.2700"},
            {"GBP/JPY", "189.87"}, {"GBP/BHD", "0.4776"}, {"USD/JPY", "149.50"}, {"USD/BHD", "0.3760"}, {"BHD/JPY", "397.5121"}};
        for (String[] mid : mids) {
            FX_MIDS.put(mid[0], mid[1]);
            String[] pair = mid[0].split("/");
            BigDecimal forward = new BigDecimal(mid[1]).setScale(10, RoundingMode.UNNECESSARY);
            FX_RATES.put(pair[0] + "/" + pair[1], forward);
            FX_RATES.put(pair[1] + "/" + pair[0], BigDecimal.ONE.divide(forward, 10, RoundingMode.HALF_EVEN));
        }
    }

    private record FxQuote(String source, String destination, BigDecimal sold, BigDecimal bought, String rate, Instant heldUntil) {}

    private Simulator() {}

    public static void main(String[] args) throws Exception {
        Path keystore = Path.of(args[0]);
        char[] empty = Files.readString(Path.of(args[1])).trim().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, empty);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, empty);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(8443), 128);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        register(server);
        server.start();
        Thread.ofVirtual().start(Simulator::writeCounts);
        System.out.println("simulator listening on 8443");
    }

    private static void writeCounts() {
        Path file = Path.of("/tmp/counts.txt");
        while (true) {
            Map<String, String> lines = new TreeMap<>();
            REQUESTS.forEach((path, count) -> lines.put(path, count.get() + " requests, "
                    + KEYS.getOrDefault(path, new AtomicLong()).get() + " keys"));
            StringBuilder out = new StringBuilder();
            lines.forEach((path, line) -> out.append(path).append(": ").append(line).append('\n'));
            try {
                Files.writeString(file, out.toString());
                Thread.sleep(2000);
            } catch (IOException | InterruptedException stop) {
                return;
            }
        }
    }

    private static void register(HttpServer server) {
        server.createContext("/tokenisations", exchange -> answer(exchange, "/tokenisations", Simulator::tokenised));
        server.createContext("/payout-destinations/tokenisations", exchange -> answer(exchange, "/payout-destinations/tokenisations",
                key -> "{\"status\":\"tokenised\",\"reference\":\"pdr_" + shortId() + "\",\"last4\":\"3000\"}"));
        server.createContext("/authorizations", exchange -> answer(exchange, "/authorizations",
                key -> "{\"status\":\"approved\",\"reference\":\"psp_auth-" + UUID.randomUUID() + "\"}"));
        server.createContext("/captures", exchange -> answer(exchange, "/captures",
                key -> "{\"status\":\"approved\",\"reference\":\"psp_cap-" + UUID.randomUUID() + "\"}"));
        server.createContext("/refunds", exchange -> answer(exchange, "/refunds",
                key -> "{\"status\":\"approved\",\"reference\":\"psp_ref-" + UUID.randomUUID() + "\"}"));
        server.createContext("/voids", exchange -> answer(exchange, "/voids",
                key -> "{\"status\":\"approved\",\"reference\":\"psp_void-" + UUID.randomUUID() + "\"}"));
        server.createContext("/payouts", exchange -> answer(exchange, "/payouts",
                key -> "{\"status\":\"paid\",\"reference\":\"po-" + key + "\"}"));
        server.createContext(FX_REFERENCE, Simulator::referenceRates);
        server.createContext(FX_QUOTES, Simulator::fxQuote);
        server.createContext(FX_EXECUTIONS, Simulator::fxExecution);
    }

    // ---------------------------------------------------------------------------------------------- FX: the reference and fx-sim-a

    private static String readBody(HttpExchange exchange, String path) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        REQUESTS.computeIfAbsent(path, ignored -> new AtomicLong()).incrementAndGet();
        return body;
    }

    /** {@code GET /fx/reference/rates}: every canonical pair's mid, observed now. */
    private static void referenceRates(HttpExchange exchange) throws IOException {
        readBody(exchange, FX_REFERENCE);
        String observed = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        StringBuilder lines = new StringBuilder();
        FX_MIDS.forEach((pair, mid) -> lines.append(pair).append(',').append(mid).append(',').append(observed).append('\n'));
        send(exchange, 200, lines.toString(), "text/plain");
    }

    /** {@code POST /fx/quotes}: a firm quote, one per Idempotency-Key (our quote request's reference). */
    private static void fxQuote(HttpExchange exchange) throws IOException {
        String request = readBody(exchange, FX_QUOTES);
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String body = key == null ? quoted(request) : ANSWERED.computeIfAbsent(FX_QUOTES + "|" + key, ignored -> {
            KEYS.computeIfAbsent(FX_QUOTES, none -> new AtomicLong()).incrementAndGet();
            return quoted(request);
        });
        send(exchange, 200, body);
    }

    private static String quoted(String request) {
        String source = word(request, "source");
        String destination = word(request, "destination");
        BigDecimal rate = FX_RATES.get(source + "/" + destination);
        if (rate == null) {
            return "{\"status\":\"declined\",\"reason\":\"pair_not_quoted\"}";
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(word(request, "amount"));
        } catch (NumberFormatException notAnAmount) {
            return "{\"status\":\"declined\",\"reason\":\"amount_out_of_range\"}";
        }
        if (amount.signum() <= 0) {
            return "{\"status\":\"declined\",\"reason\":\"amount_out_of_range\"}";
        }
        boolean fixedSource = "FIXED_SOURCE".equals(word(request, "fixedSide"));
        BigDecimal sold = fixedSource ? amount : amount.divide(rate, minorUnits(source), RoundingMode.HALF_EVEN);
        BigDecimal bought = fixedSource ? amount.multiply(rate).setScale(minorUnits(destination), RoundingMode.HALF_EVEN) : amount;
        String quoteRef = "PQ-" + FX_INSTANCE + "-" + FX_SEQUENCE.incrementAndGet();
        FX_QUOTED.put(quoteRef, new FxQuote(source, destination, sold, bought, rate.toPlainString(), Instant.now().plus(FX_QUOTE_HELD)));
        return "{\"status\":\"quoted\",\"quoteRef\":\"" + quoteRef + "\",\"rate\":\"" + rate.toPlainString() + "\",\"counter\":\""
                + (fixedSource ? bought : sold).toPlainString() + "\",\"counterCurrency\":\"" + (fixedSource ? destination : source)
                + "\",\"validForSeconds\":\"" + FX_QUOTE_HELD.toSeconds() + "\",\"valueDate\":\"" + valueDate() + "\"}";
    }

    /**
     * {@code POST /fx/executions}: our reference first - a seen reference answers its first outcome, whatever the quote's lock says now -
     * and only a novel one is judged against the quote. {@code GET /fx/executions/{reference}}: the recorded outcome, or unrecognised.
     */
    private static void fxExecution(HttpExchange exchange) throws IOException {
        String request = readBody(exchange, FX_EXECUTIONS);
        if ("GET".equals(exchange.getRequestMethod())) {
            String requested = exchange.getRequestURI().getPath();
            String reference = requested.length() > FX_EXECUTIONS.length() + 1 ? requested.substring(FX_EXECUTIONS.length() + 1) : "";
            String known = reference.isEmpty() ? null : ANSWERED.get(FX_EXECUTIONS + "|" + reference);
            send(exchange, 200, known != null ? known : "{\"status\":\"unrecognised\"}");
            return;
        }
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null) {
            send(exchange, 200, "{\"status\":\"rejected\",\"reason\":\"limit\"}");
            return;
        }
        String body = ANSWERED.computeIfAbsent(FX_EXECUTIONS + "|" + key, ignored -> {
            KEYS.computeIfAbsent(FX_EXECUTIONS, none -> new AtomicLong()).incrementAndGet();
            return executed(request);
        });
        send(exchange, 200, body);
    }

    private static String executed(String request) {
        FxQuote quote = FX_QUOTED.get(word(request, "quoteRef"));
        if (quote == null || Instant.now().isAfter(quote.heldUntil())) {
            return "{\"status\":\"rejected\",\"reason\":\"quote_expired\"}";
        }
        String tradeRef = "FT-" + FX_INSTANCE + "-" + FX_SEQUENCE.incrementAndGet();
        return "{\"status\":\"executed\",\"tradeRef\":\"" + tradeRef + "\",\"sold\":\"" + quote.sold().toPlainString() + "\",\"soldCurrency\":\""
                + quote.source() + "\",\"bought\":\"" + quote.bought().toPlainString() + "\",\"boughtCurrency\":\"" + quote.destination()
                + "\",\"rate\":\"" + quote.rate() + "\",\"valueDate\":\"" + valueDate() + "\"}";
    }

    private static String valueDate() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(2).toString();
    }

    /** A currency's minor units as the platform holds them: JPY 0, BHD 3, the others 2. Never the JDK's table. */
    private static int minorUnits(String currency) {
        return switch (currency) {
            case "JPY" -> 0;
            case "BHD" -> 3;
            default -> 2;
        };
    }

    /** A string field of a request body, as the adapter writes them; empty when absent. */
    private static String word(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String tokenised(String key) {
        return "{\"status\":\"tokenised\",\"token\":\"tok_" + shortId() + "\",\"brand\":\"Visa\","
                + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @FunctionalInterface
    private interface Answer {
        String of(String key);
    }

    private static void answer(HttpExchange exchange, String path, Answer answer) throws IOException {
        exchange.getRequestBody().readAllBytes();
        REQUESTS.computeIfAbsent(path, ignored -> new AtomicLong()).incrementAndGet();
        if (PAYOUTS.equals(path) && "GET".equals(exchange.getRequestMethod())) {
            inquiry(exchange);
            return;
        }
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String body;
        if (key == null) {
            body = answer.of(UUID.randomUUID().toString());
        } else {
            body = ANSWERED.computeIfAbsent(path + "|" + key, ignored -> {
                KEYS.computeIfAbsent(path, none -> new AtomicLong()).incrementAndGet();
                return answer.of(key);
            });
        }
        if ("/payouts".equals(path) && key != null && Files.exists(HOLD)) {
            hold(exchange, key);
        }
        if (PAYOUTS.equals(path) && key != null && Files.exists(HOLD_INQUIRIES)) {
            // The effect is taken; the answer waits past the platform's timeout, which has stopped listening by then.
            waitWhile(HOLD_INQUIRIES, 1200);
            try {
                send(exchange, 200, body);
            } catch (IOException gone) {
                // The caller gave up: what the hold is for.
            }
            return;
        }
        send(exchange, 200, body);
    }

    /** {@code GET /payouts/{reference}}: the provider's own record of the reference, or no answer at all while inquiries are held. */
    private static void inquiry(HttpExchange exchange) throws IOException {
        if (Files.exists(HOLD_INQUIRIES)) {
            send(exchange, 503, "{\"status\":\"unavailable\"}");
            return;
        }
        String requested = exchange.getRequestURI().getPath();
        String reference = requested.length() > PAYOUTS.length() + 1 ? requested.substring(PAYOUTS.length() + 1) : "";
        String known = reference.isEmpty() ? null : ANSWERED.get(PAYOUTS + "|" + reference);
        send(exchange, 200, known != null ? known : "{\"status\":\"unrecognised\"}");
    }

    private static void waitWhile(Path file, int tenthsOfASecond) {
        try {
            for (int waited = 0; waited < tenthsOfASecond && Files.exists(file); waited++) {
                Thread.sleep(100);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    private static void hold(HttpExchange exchange, String key) throws IOException {
        Files.writeString(HELD, exchange.getRemoteAddress().getAddress().getHostAddress() + " " + key + "\n");
        try {
            for (int waited = 0; waited < 200 && Files.exists(HOLD); waited++) {
                Thread.sleep(100);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body, "application/json");
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
