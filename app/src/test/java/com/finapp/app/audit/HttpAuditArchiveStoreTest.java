package com.finapp.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.platform.audit.archive.AuditArchiveStore;
import com.finapp.platform.audit.archive.AuditArchiveStoreException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The archive store's adapter against a write-once store on loopback (`P16-TSK-010`): the S3 requests it sends, the answers it reads,
 * and its bounds in time and size. The store here is this test's: what a real bucket does with the same requests is not proven by it
 * (item 57 (a)).
 */
@DisplayName("the archive store's adapter: put-if-absent, read back, bounded, refused off loopback without TLS (P16-TSK-010)")
class HttpAuditArchiveStoreTest {

    private static final String KEY = "audit/v1/segments/000000000001.ndjson";

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private HttpServer server;
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int forced;
    private volatile boolean stall;
    /** A read's headers sent this long after its request; none when null. */
    private volatile Duration headersAfter;
    /** A read's body after its first byte sent this long after the headers; none when null. */
    private volatile Duration bodyAfter;

    @BeforeEach
    void startTheStore() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::serve);
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach
    void stopTheStore() {
        release.countDown();
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    @DisplayName("a write sends PUT with If-None-Match: * and the body's checksum to <endpoint>/<bucket>/<key>, and a read returns it")
    void aWriteThenARead() {
        HttpAuditArchiveStore store = store();
        byte[] bytes = "line\n".getBytes(StandardCharsets.UTF_8);

        assertThat(store.putIfAbsent(KEY, bytes)).isEqualTo(AuditArchiveStore.Put.WRITTEN);
        assertThat(store.get(KEY, bytes.length)).hasValueSatisfying(read -> assertThat(read).isEqualTo(bytes));
        assertThat(requests).containsExactly(
                "PUT /finapp-audit-archive/" + KEY + " if-none-match=* checksum=" + checksum(bytes),
                "GET /finapp-audit-archive/" + KEY);
    }

    @Test
    @DisplayName("a second write of the key finds the first (412) and leaves it; a key never written reads as absent (404)")
    void writeOnce() {
        HttpAuditArchiveStore store = store();
        store.putIfAbsent(KEY, "first\n".getBytes(StandardCharsets.UTF_8));

        assertThat(store.putIfAbsent(KEY, "second\n".getBytes(StandardCharsets.UTF_8))).isEqualTo(AuditArchiveStore.Put.ALREADY_PRESENT);
        assertThat(objects.get("/finapp-audit-archive/" + KEY)).asString(StandardCharsets.UTF_8).isEqualTo("first\n");
        assertThat(store.get("audit/v1/manifests/000000000009.json", 10)).isEmpty();
    }

    @Test
    @DisplayName("a read keeps one byte past what it expects and no more, so a longer object never compares equal")
    void aReadIsBoundedInSize() {
        HttpAuditArchiveStore store = store();
        store.putIfAbsent(KEY, "0123456789".getBytes(StandardCharsets.UTF_8));

        assertThat(store.get(KEY, 4)).hasValueSatisfying(read -> assertThat(read).asString(StandardCharsets.UTF_8).isEqualTo("01234"));
    }

    @Test
    @DisplayName("any other answer, a store that answers nothing, and one that stalls past the timeout are each the store unavailable")
    void unavailableIsAnException() {
        HttpAuditArchiveStore store = store();
        forced = 503;
        assertThatExceptionOfType(AuditArchiveStoreException.class).isThrownBy(() -> store.putIfAbsent(KEY, new byte[] {1}))
                .withMessageContaining("503");
        assertThatExceptionOfType(AuditArchiveStoreException.class).isThrownBy(() -> store.get(KEY, 1)).withMessageContaining("503");
        forced = 0;

        // An object to read, then its answer's headers sent and its body never: the exchange ends at the timeout.
        assertThat(store.putIfAbsent(KEY, new byte[] {1, 2})).isEqualTo(AuditArchiveStore.Put.WRITTEN);
        stall = true;
        long started = System.nanoTime();
        assertThatExceptionOfType(AuditArchiveStoreException.class).isThrownBy(() -> store.get(KEY, 1)).withMessageContaining("within");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("bounded by the timeout").isLessThan(TIMEOUT.multipliedBy(3));
        stall = false;

        release.countDown();
        server.stop(0);
        assertThatExceptionOfType(AuditArchiveStoreException.class).isThrownBy(() -> store.putIfAbsent(KEY, new byte[] {1}))
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("127.0.0.1").doesNotContain("localhost"))
                .satisfies(failure -> assertThat(failure.getCause()).isNull());
    }

    @Test
    @DisplayName("the headers have half the timeout: a store whose headers come later is unavailable, though its whole answer"
            + " would have come within the timeout")
    void theHeadersHaveHalfTheTimeout() {
        HttpAuditArchiveStore store = store();
        assertThat(store.putIfAbsent(KEY, new byte[] {1, 2})).isEqualTo(AuditArchiveStore.Put.WRITTEN);

        headersAfter = TIMEOUT.multipliedBy(9).dividedBy(10);
        long started = System.nanoTime();
        assertThatExceptionOfType(AuditArchiveStoreException.class).isThrownBy(() -> store.get(KEY, 2)).withMessageContaining("within");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("cut at the headers' half, before they came")
                .isLessThan(headersAfter);
    }

    @Test
    @DisplayName("the body has the other half, from the headers: a body slower than that is unavailable, and one within it is read")
    void theBodyHasTheOtherHalfFromTheHeaders() {
        HttpAuditArchiveStore store = store();
        assertThat(store.putIfAbsent(KEY, new byte[] {1, 2})).isEqualTo(AuditArchiveStore.Put.WRITTEN);

        bodyAfter = TIMEOUT.multipliedBy(9).dividedBy(10);
        assertThatExceptionOfType(AuditArchiveStoreException.class).isThrownBy(() -> store.get(KEY, 2)).withMessageContaining("within");

        bodyAfter = TIMEOUT.dividedBy(10);
        assertThat(store.get(KEY, 2)).hasValueSatisfying(read -> assertThat(read).containsExactly(1, 2));
    }

    @Test
    @DisplayName("plain http off loopback is refused, a bucket that is not a bucket's name is refused, a timeout too short to"
            + " halve is refused, and only the archive's keys are sent")
    void refusals() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new HttpAuditArchiveStore("http://archive.internal:8443", "finapp-audit-archive", TIMEOUT))
                .withMessageContaining("finapp.audit.archive.endpoint");
        new HttpAuditArchiveStore("https://archive.internal:8443", "finapp-audit-archive", TIMEOUT);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HttpAuditArchiveStore("https://archive.internal", "Bad_Bucket", TIMEOUT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HttpAuditArchiveStore("https://archive.internal", "finapp-audit-archive", Duration.ofMillis(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> store().get("../escape/000000000001.json", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> store().putIfAbsent("audit/v1/segments/1.ndjson?x=1", new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> store().get(KEY, Integer.MAX_VALUE));
    }

    // ------------------------------------------------------------------------------------------------------------------

    private HttpAuditArchiveStore store() {
        return new HttpAuditArchiveStore("http://127.0.0.1:" + server.getAddress().getPort(), "finapp-audit-archive", TIMEOUT);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (exchange.getRequestMethod().equals("PUT")) {
            requests.add("PUT " + path + " if-none-match=" + exchange.getRequestHeaders().getFirst("If-None-Match") + " checksum="
                    + exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256"));
        } else {
            requests.add(exchange.getRequestMethod() + " " + path);
        }
        if (forced != 0) {
            exchange.sendResponseHeaders(forced, -1);
            exchange.close();
            return;
        }
        if (exchange.getRequestMethod().equals("PUT")) {
            boolean written = objects.putIfAbsent(path, body) == null;
            exchange.sendResponseHeaders(written ? 200 : 412, -1);
            exchange.close();
            return;
        }
        byte[] stored = objects.get(path);
        if (stored == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        if (headersAfter != null) {
            pause(headersAfter);
        }
        exchange.sendResponseHeaders(200, stall ? 0 : stored.length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (bodyAfter != null && !stall) {
                // The first byte with the headers, the rest after the pause: the body's own time, measured from the headers.
                out.write(stored, 0, 1);
                out.flush();
                pause(bodyAfter);
                out.write(stored, 1, stored.length - 1);
                return;
            }
            if (stall) {
                // Headers sent, then nothing: the body never comes until the test ends.
                out.flush();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            out.write(stored);
        }
    }

    private static String checksum(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
