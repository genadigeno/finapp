import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The providers of the kind rehearsal (P15-TSK-012), one test-only pod in the namespace finapp-rehearsal: the card PSP, card
 * tokenisation and the payout rail, over TLS from the rehearsal's local CA. A single source file run by the JDK image, so nothing is
 * built or published for it.
 *
 * <p>It speaks only the words the adapters' code defines (SimulatedCardPspAdapter, SimulatedTokenisationAdapter,
 * SimulatedPayoutProvider) and it always succeeds: the rehearsal is about a deployment, and a provider that fails on demand is the
 * test harness's job. It behaves as a real provider must in one respect, because the census reads it: <strong>one effect per
 * Idempotency-Key</strong>. A key seen before gets the answer it got the first time, and {@code /tmp/counts.txt}, rewritten every two
 * seconds, says how many requests arrived and how many distinct keys, per path, so "one effect per key" is counted on the provider's
 * side as well as the ledger's. The rehearsal reads it with {@code kubectl exec}; no port serves it.
 *
 * <p>While {@code /tmp/hold-payouts} exists, a payout's answer is held after its effect is taken, for at most twenty seconds, and the
 * caller's address and key are written to {@code /tmp/held.txt}. That is the worst moment for a replica to die, and the kill needs one
 * it can name: the provider has moved the money and the platform has not heard.
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
        send(exchange, 200, body);
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
