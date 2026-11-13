import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The audit archive's store on kind (P16-TSK-010; OPERATIONS_RUNBOOK.md section 11.50): one test-only pod in the namespace
 * finapp-rehearsal, over TLS from the rehearsal's local CA, standing in for the real bucket the archive would write to. A single source
 * file run by the JDK image, as the provider simulator is, so nothing is built or published for it. The experiments scope alone deploys
 * it (experiments.sh, experiments_archive_store), and names it to the application in that scope's own rendered copy alone.
 *
 * <p>It speaks the words HttpAuditArchiveStore sends, S3's object API in path style, {@code /<bucket>/<key>}, and it is <strong>write
 * once</strong> as the real bucket is under Object Lock: a {@code PUT} where an object is answers {@code 412}, with or without
 * {@code If-None-Match: *}, and leaves it; a {@code DELETE} answers {@code 405}; nothing else writes. An object is created by a hard link
 * from a file written aside, so two writers of one key cannot both succeed, and a crash leaves no half object. A body whose
 * {@code x-amz-checksum-sha256} does not match is refused {@code 400}. Objects live on the pod's volume, a PersistentVolumeClaim, so the
 * store scaled to zero and back (experiment E44) keeps them.
 *
 * <p>It holds no credential and verifies none, as the simulator verifies none: request signing is the real bucket's, and is
 * THREAT_MODEL.md section 6 item 57 (a). Its encryption at rest is the volume's, and a kind volume is not encrypted: the data is the
 * run's own, throwaway.
 *
 * <p>While {@code /tmp/hold-reads} exists (experiment E45), every {@code GET} waits up to twenty seconds before it answers, and the
 * caller's address and the key go to {@code /tmp/held.txt} first: the archive's holder is then between its copy and its mark, the
 * moment a kill must find. Every request is appended to {@code /tmp/requests.tsv} (time, method, key, status, caller), which the
 * experiments read with {@code kubectl exec}; no port serves it.
 */
public final class ArchiveStore {

    private static final Path HOLD_READS = Path.of("/tmp/hold-reads");
    private static final Path HELD = Path.of("/tmp/held.txt");
    private static final Path REQUESTS = Path.of("/tmp/requests.tsv");

    /** The bucket and the key: S3's bucket rule, and the archive's own key shapes only. */
    private static final Pattern OBJECT =
            Pattern.compile("/([a-z0-9][a-z0-9.-]{1,61}[a-z0-9])/([a-z0-9]+(?:/[a-z0-9]+)*/[0-9]{12}\\.(?:ndjson|json))");

    /** The largest object it takes: a segment of ten thousand rows of the audit table's bounded columns. */
    private static final long MAX_OBJECT_BYTES = 64L * 1024 * 1024;

    private static Path root;

    private ArchiveStore() {}

    public static void main(String[] args) throws Exception {
        Path keystore = Path.of(args[0]);
        char[] phrase = Files.readString(Path.of(args[1])).trim().toCharArray();
        root = Path.of(args[2]);
        Files.createDirectories(root.resolve(".incoming"));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, phrase);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, phrase);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(8443), 128);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ArchiveStore::serve);
        server.start();
        System.out.println("archive store listening on 8443, objects under " + root);
    }

    private static void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        var object = OBJECT.matcher(path);
        int status;
        if (!object.matches() || exchange.getRequestURI().getRawQuery() != null) {
            status = 400;
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1);
        } else if (method.equals("PUT")) {
            status = put(exchange, root.resolve(object.group(1)).resolve(object.group(2)));
            exchange.sendResponseHeaders(status, -1);
        } else if (method.equals("GET")) {
            Path target = root.resolve(object.group(1)).resolve(object.group(2));
            holdIfAsked(exchange, path);
            if (Files.exists(target)) {
                byte[] bytes = Files.readAllBytes(target);
                status = 200;
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                status = 404;
                exchange.sendResponseHeaders(status, -1);
            }
        } else {
            // DELETE and every other method: write once means nothing is ever removed or replaced.
            status = 405;
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1);
        }
        exchange.close();
        log(method, path, status, exchange);
    }

    private static int put(HttpExchange exchange, Path target) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes((int) MAX_OBJECT_BYTES + 1);
        if (body.length > MAX_OBJECT_BYTES) {
            return 413;
        }
        String checksum = exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256");
        if (checksum != null && !checksum.equals(Base64.getEncoder().encodeToString(sha256(body)))) {
            return 400;
        }
        if (Files.exists(target)) {
            return 412;
        }
        Files.createDirectories(target.getParent());
        Path aside = Files.createTempFile(root.resolve(".incoming"), "object-", ".part");
        try {
            Files.write(aside, body, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            try {
                // A hard link is created whole or not at all, and refused where a name exists: one writer of a key wins.
                Files.createLink(target, aside);
                return 200;
            } catch (FileAlreadyExistsException taken) {
                return 412;
            }
        } finally {
            Files.deleteIfExists(aside);
        }
    }

    private static void holdIfAsked(HttpExchange exchange, String path) throws IOException {
        if (!Files.exists(HOLD_READS)) {
            return;
        }
        Files.writeString(HELD, exchange.getRemoteAddress().getAddress().getHostAddress() + "\t" + path + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        for (int waited = 0; waited < 200 && Files.exists(HOLD_READS); waited++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void log(String method, String path, int status, HttpExchange exchange) {
        String line = Instant.now() + "\t" + method + "\t" + path + "\t" + status + "\t"
                + exchange.getRemoteAddress().getAddress().getHostAddress() + "\n";
        try {
            Files.writeString(REQUESTS, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            System.err.println("the request log could not be written: " + ignored.getClass().getSimpleName());
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
