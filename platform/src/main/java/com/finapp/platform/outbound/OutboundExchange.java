package com.finapp.platform.outbound;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * One HTTP exchange with a provider, bounded end to end (`P16-TSK-018`; {@code PHASE_16_PLAN.md} section 8;
 * {@code docs/architecture/TIMEOUTS_AND_RETRIES.md} section 2).
 *
 * <h2>Why the request's own timeout is not enough</h2>
 *
 * <p>Every adapter sets {@code HttpClient.Builder.connectTimeout} and {@code HttpRequest.Builder.timeout}. The JDK's request
 * timeout runs from the start of an attempt until the response's <em>headers</em> arrive, and no further: in JDK 21
 * {@code MultiExchange} cancels its response timer as soon as the {@code Response} is received, and the body is then read
 * with no timer at all. A provider that answers its headers and then stops sending the body holds the calling thread until
 * the connection dies, which on a connection that stays open is never: a request thread, a schedule's only thread, or an
 * inquiry permit of a bulkhead, held for good. {@link #send} adds the third bound, on the body: from the headers' arrival to
 * the body's last byte. With the JDK's two, every phase of the exchange is bounded, so the exchange is bounded overall.
 *
 * <h2>Why the third bound starts at the headers, and not at the call</h2>
 *
 * <p>Before the headers, the JDK's own handling stands untouched: its connect timeout, its request timeout per attempt, and
 * the one attempt it makes again by itself when a connection is refused (nothing was transmitted). A bound measured from the
 * call would cut that second attempt short and turn a refused connection - knowledge - into a timeout; the instant scheme
 * adapter's own test found exactly that on the first form of this class. Measured from the headers, the bound cuts only what
 * nothing else bounds.
 *
 * <h2>What a failure looks like is unchanged</h2>
 *
 * <p>Past the body's bound the body is failed with an {@link HttpTimeoutException} and its subscription cancelled - the JDK
 * closes the connection: the {@code IOException} every adapter already reads as a timeout, so a dispatch still becomes
 * {@code INDETERMINATE} and is inquired, never resent ({@code INV-LIFE-03}, ADR-0046). Any other failure is rethrown as the
 * exception the JDK completed the exchange with, so a refused connection is still a {@link java.net.ConnectException}, never
 * widened into ambiguity, nor an ambiguity narrowed into it. An interrupt cancels the exchange and is rethrown, as the JDK's
 * {@code send} does.
 *
 * <p>Nothing here retries, and nothing here reads a provider's vocabulary: the classification stays each adapter's. The body's
 * timer runs on {@link CompletableFuture#delayedExecutor}'s shared scheduler, one task per exchange: no thread is made here.
 */
public final class OutboundExchange {

    private OutboundExchange() {}

    /**
     * Sends {@code request} and waits for its whole response; its body may take at most {@code bodyBound} after the headers.
     *
     * @param client the adapter's client, built with its connect timeout
     * @param request the request, built with its own timeout (the bound to the headers)
     * @param handler a handler that reads the whole body before it completes (never a streaming one: a stream returned at
     *     the headers would be read outside this bound)
     * @param bodyBound the bound on the body, from the headers' arrival to its last byte
     * @return the response
     * @throws HttpTimeoutException when the body passed its bound (the exchange cancelled), or the JDK's own bounds passed
     * @throws IOException the exchange's own failure, as the JDK raised it
     * @throws InterruptedException when the calling thread was interrupted, the exchange cancelled
     */
    public static <T> HttpResponse<T> send(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, Duration bodyBound)
            throws IOException, InterruptedException {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(bodyBound, "bodyBound must not be null");
        if (bodyBound.isZero() || bodyBound.isNegative()) {
            throw new IllegalArgumentException("the body's bound must be positive");
        }
        if (request.timeout().isEmpty()) {
            throw new IllegalArgumentException("a request without its own timeout has no bound to its headers");
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        CompletableFuture<HttpResponse<T>> exchange =
                client.sendAsync(request, responseInfo -> new BoundedBody<>(handler.apply(responseInfo), bodyBound));
        try {
            return exchange.get();
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            throw rethrown(failed.getCause());
        }
    }

    /** The exchange's own failure, as the JDK's {@code send} would have thrown it: an IOException keeps its class. */
    private static IOException rethrown(Throwable cause) {
        Throwable failure = cause;
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof IOException io) {
            return io;
        }
        if (failure instanceof RuntimeException unchecked) {
            throw unchecked;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IOException("the exchange failed", failure);
    }

    /**
     * The body's bound: the handler's own subscriber, its body failed with an {@link HttpTimeoutException} and its subscription
     * cancelled once {@code bound} has passed since the headers, unless the body completed first.
     */
    private static final class BoundedBody<T> implements HttpResponse.BodySubscriber<T> {

        private final HttpResponse.BodySubscriber<T> delegate;
        private final CompletableFuture<T> body = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;

        private BoundedBody(HttpResponse.BodySubscriber<T> delegate, Duration bound) {
            this.delegate = Objects.requireNonNull(delegate, "the handler gave no subscriber");
            delegate.getBody().whenComplete((value, failure) -> {
                if (failure == null) {
                    body.complete(value);
                } else {
                    body.completeExceptionally(failure);
                }
            });
            CompletableFuture.delayedExecutor(bound.toNanos(), TimeUnit.NANOSECONDS).execute(() -> {
                if (body.completeExceptionally(new HttpTimeoutException("the response body passed its bound of " + bound))) {
                    Flow.Subscription taken = subscription;
                    if (taken != null) {
                        taken.cancel();
                    }
                }
            });
        }

        @Override
        public CompletionStage<T> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
            if (body.isCompletedExceptionally()) {
                subscription.cancel();
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            delegate.onError(failure);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    /**
     * A body handler that keeps at most {@code limit} bytes of the body and stops reading there: past the limit the
     * subscription is cancelled (the JDK closes the connection) and the first {@code limit} bytes are the body. A caller that
     * must refuse an over-long body asks for one byte more than it accepts and refuses an answer that long. Unlike
     * {@code BodyHandlers.ofInputStream()}, it completes only when it has stopped reading, so {@link #send}'s body bound covers it.
     *
     * @param limit the most bytes kept, at least one
     * @return the handler
     */
    public static HttpResponse.BodyHandler<byte[]> limitedBytes(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("the limit must be at least one byte");
        }
        return responseInfo -> new LimitedBytes(limit);
    }

    /** The subscriber behind {@link #limitedBytes}. */
    private static final class LimitedBytes implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final int limit;
        private byte[] kept = new byte[0];
        private Flow.Subscription subscription;

        private LimitedBytes(int limit) {
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
                int room = limit - kept.length;
                int taken = Math.min(room, buffer.remaining());
                int from = kept.length;
                kept = Arrays.copyOf(kept, from + taken);
                buffer.get(kept, from, taken);
                if (buffer.hasRemaining()) {
                    subscription.cancel();
                    body.complete(kept);
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
            body.complete(kept);
        }
    }
}
