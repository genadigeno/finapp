package com.finapp.app.audit;

import com.finapp.app.security.ProviderTransportGuard;
import com.finapp.platform.audit.archive.AuditArchiveStore;
import com.finapp.platform.audit.archive.AuditArchiveStoreException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The audit archive's store over the S3 object API's subset the archive needs (`P16-TSK-010`; ADR-0135): {@code PUT} with
 * {@code If-None-Match: *}, which writes only where no object is, and {@code GET}. Path-style: {@code <endpoint>/<bucket>/<key>}.
 *
 * <p><strong>Write once.</strong> A write that finds an object answers {@code 412}, read here as
 * {@link AuditArchiveStore.Put#ALREADY_PRESENT}; the archival reads every object back and compares it, so the header is a saving and
 * never the check. The request carries {@code x-amz-checksum-sha256}, so a store that checks it refuses a body damaged in flight.
 * Nothing here deletes.
 *
 * <p><strong>Bounded in time and in size.</strong> Each exchange, its answer's body included, ends within the configured timeout or
 * is cancelled: a store that sends headers and then stalls holds the archive's thread no longer than one that never answers. A read
 * keeps at most one byte past what it expects and cancels the rest. The client is the caller's, built with a connect timeout.
 *
 * <p><strong>What is not built.</strong> Requests are unsigned: the test-only store on {@code kind} verifies no credential, as the
 * provider simulator verifies none. Signing (SigV4) and the pod role's credential that a real bucket needs are
 * {@code THREAT_MODEL.md} section 6 item 57 (a). {@link ProviderTransportGuard} refuses a plain {@code http} endpoint off loopback at
 * start, and this class refuses it again.
 */
public final class HttpAuditArchiveStore implements AuditArchiveStore {

    /** S3's rule for a bucket's name: 3 to 63 lower-case letters, digits, dots and hyphens, a letter or digit at each end. */
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

    /** The archive's own keys: segment and manifest paths, nothing a caller could steer. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:/[a-z0-9]+)*/[0-9]{12}\\.(?:ndjson|json)");

    private final HttpClient client;
    private final URI bucket;
    private final Duration timeout;

    /**
     * @param client built with a connect timeout; it verifies the store's certificate and host name, as the JDK's client does
     * @param endpoint the store's base URL: {@code https}, or {@code http} to loopback
     * @param bucket the bucket's name
     * @param timeout each exchange's bound, its answer's body included
     */
    public HttpAuditArchiveStore(HttpClient client, String endpoint, String bucket, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(bucket, "bucket");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("the archive store's timeout must be positive: " + timeout);
        }
        ProviderTransportGuard.verify("finapp.audit.archive.endpoint", Optional.of(endpoint));
        if (!BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("not a bucket's name: " + bucket);
        }
        String base = endpoint.trim();
        this.bucket = URI.create((base.endsWith("/") ? base : base + "/") + bucket + "/");
    }

    @Override
    public Put putIfAbsent(String key, byte[] bytes) {
        HttpRequest request = HttpRequest.newBuilder(object(key))
                .timeout(timeout)
                .header("If-None-Match", "*")
                .header("Content-Type", "application/octet-stream")
                .header("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(sha256(bytes)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<Void> response = exchange(request, HttpResponse.BodyHandlers.discarding(), "write");
        return switch (response.statusCode()) {
            case 200, 201, 204 -> Put.WRITTEN;
            case 412 -> Put.ALREADY_PRESENT;
            default -> throw new AuditArchiveStoreException("the archive store answered " + response.statusCode() + " to a write");
        };
    }

    @Override
    public Optional<byte[]> get(String key, int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative: " + maxBytes);
        }
        HttpRequest request = HttpRequest.newBuilder(object(key)).timeout(timeout).GET().build();
        HttpResponse<byte[]> response = exchange(request,
                answer -> answer.statusCode() == 200 ? new Bounded(maxBytes + 1) : HttpResponse.BodySubscribers.replacing(new byte[0]),
                "read");
        return switch (response.statusCode()) {
            case 200 -> Optional.of(response.body());
            case 404 -> Optional.empty();
            default -> throw new AuditArchiveStoreException("the archive store answered " + response.statusCode() + " to a read");
        };
    }

    private URI object(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("not one of the archive's keys: " + key);
        }
        return bucket.resolve(key);
    }

    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler, String what) {
        CompletableFuture<HttpResponse<T>> exchange = client.sendAsync(request, handler);
        try {
            return exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            exchange.cancel(true);
            throw new AuditArchiveStoreException("the archive store did not finish a " + what + " within " + timeout);
        } catch (ExecutionException failed) {
            // The class alone: a client's message names the store's address.
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new AuditArchiveStoreException("the archive store did not answer a " + what + ": " + cause.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new AuditArchiveStoreException("a " + what + " to the archive store was interrupted");
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every JDK provides SHA-256", absent);
        }
    }

    /** A body kept up to {@code limit} bytes; the rest of the stream is cancelled, never buffered. */
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        Bounded(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int take = Math.min(buffer.remaining(), limit - kept.size());
                byte[] chunk = new byte[take];
                buffer.get(chunk);
                kept.write(chunk, 0, take);
                if (kept.size() >= limit) {
                    body.complete(kept.toByteArray());
                    subscription.cancel();
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(kept.toByteArray());
        }
    }
}
