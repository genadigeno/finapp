package com.finapp.payments;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP core of the simulated card PSP adapter (`P5-TSK-003`, ADR-0049).
 *
 * <h2>The wire protocol is ours, and this class is the only place it exists</h2>
 *
 * <p>ADR-0049 simulates the provider, so the "provider vocabulary" `INV-PAY-03` confines is the
 * one defined here: the paths, the request JSON, the {@code status} field with its
 * {@code approved}/{@code declined}/{@code unrecognised} values, and the {@code reference}
 * field. None of it crosses the {@link PaymentProvider} port — the port speaks
 * {@link ProviderAnswer}/{@link QueryAnswer} — so a second provider shape is an adapter change
 * and nothing else (the {@code SimulatedProviderClient} precedent, `P2-TSK-009`).
 *
 * <h2>Classification — where the money's one distinction is drawn</h2>
 *
 * <p><strong>Only {@link ConnectException} is knowledge.</strong> A refused connection means an
 * RST arrived and nothing was transmitted, so the operation cannot have happened:
 * {@code NOTHING_SENT}, which the caller commits as {@code FAILED(PROVIDER_UNAVAILABLE)}
 * ({@code PAYMENT_LIFECYCLES.md} §3). Every other transport failure — a request timeout, a
 * connect <em>timeout</em> (silence, not refusal), bytes that were not HTTP — is
 * {@code INDETERMINATE}, and so is every HTTP answer that is not a parsed 200 body: a 5xx, a
 * 404, an unmapped state, a truncated body, an approval missing the reference the next
 * operation must present. Erring toward {@code INDETERMINATE} costs one sweeper query; erring
 * toward failure is the double-effect direction, and erring toward success credits money nobody
 * approved.
 *
 * <h2>What every dispatch carries</h2>
 *
 * <p>The platform-minted idempotency reference as the {@code Idempotency-Key} header — the wire
 * fact `INV-PAY-04` is about, asserted at the wire by the contract tests — and the API
 * credential as {@code Authorization: Bearer <base64(key)>}, decoded and confined by the
 * composition root through {@code ProviderApiKey} (`P5-TSK-002`'s mechanism; this class never
 * sees the configured value, only key bytes).
 *
 * <h2>Bounds</h2>
 *
 * <p>The wait is bounded by the constructor's timeout. The retained body is bounded by
 * {@link #MAX_EVIDENCE_BYTES}: verbatim retention of an unbounded stream is not a property
 * anyone can keep, so an over-limit body is treated as garbage — indeterminate, no evidence —
 * with the limit stated here rather than discovered at `P5-TSK-008`'s evidence insert, which
 * must reconcile against it.
 */
final class PspWireClient {

    /** The bound verbatim retention can honestly promise (`INV-HIST-02`); a PSP answer is KBs. */
    static final int MAX_EVIDENCE_BYTES = 1_048_576;

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern REFERENCE_FIELD =
            Pattern.compile("\"reference\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;

    // The raw credential, under the one field name ADR-0019 deliberately keeps outside the
    // secretsAreWrapped vocabulary (the CallbackSignature/DocumentCipher idiom): the header
    // value it becomes is built per request and stored nowhere, because a field holding it
    // would be a bearer credential sitting in a String for the adapter's lifetime.
    private final byte[] key;

    PspWireClient(URI baseUrl, Duration timeout, byte[] key) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the provider timeout must be positive");
        }
        this.key = Objects.requireNonNull(key, "key must not be null").clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** POSTs a money-moving operation; total — every misbehaviour is a {@link ProviderAnswer}. */
    ProviderAnswer dispatch(String path, ProviderIdempotencyReference reference, String body) {
        HttpRequest request =
                HttpRequest.newBuilder(baseUrl.resolve(path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .header(IDEMPOTENCY_KEY_HEADER, reference.value())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException refused) {
            // The one transport failure that is knowledge: nothing was transmitted.
            return ProviderAnswer.nothingSent();
        } catch (IOException ambiguous) {
            // Sent, or possibly sent, and no answer: a timeout, a connection that died, bytes
            // that were not HTTP. Silence is ambiguity, never failure (INV-LIFE-03).
            return ProviderAnswer.indeterminate();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProviderAnswer.indeterminate();
        }

        byte[] received = response.body();
        if (received.length == 0 || received.length > MAX_EVIDENCE_BYTES) {
            return ProviderAnswer.indeterminate();
        }
        if (response.statusCode() != 200) {
            return ProviderAnswer.indeterminate(received);
        }
        String text = new String(received, StandardCharsets.UTF_8);
        return switch (statusOf(text)) {
            case "approved" ->
                    referenceOf(text)
                            .map(psp -> ProviderAnswer.approved(psp, received))
                            // An approval the next operation cannot act on is not knowledge we
                            // can use: the capture needs the authorization's id.
                            .orElseGet(() -> ProviderAnswer.indeterminate(received));
            case "declined" -> ProviderAnswer.declined(received);
            // The total mapping's default branch: indeterminate, never success (INV-PAY-03).
            default -> ProviderAnswer.indeterminate(received);
        };
    }

    /** GETs the provider's view of the operation our reference names; same totality. */
    QueryAnswer query(String path) {
        HttpRequest request =
                HttpRequest.newBuilder(baseUrl.resolve(path))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .GET()
                        .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException nothingUsable) {
            // No NOTHING_SENT for a query: a query that never reached the provider changes
            // nothing and is simply asked again; the distinction only pays where a caller acts
            // on it.
            return QueryAnswer.indeterminate();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return QueryAnswer.indeterminate();
        }

        byte[] received = response.body();
        if (received.length == 0 || received.length > MAX_EVIDENCE_BYTES) {
            return QueryAnswer.indeterminate();
        }
        if (response.statusCode() != 200) {
            // A 404 included, deliberately: a status code is not an answer, and a misrouted
            // load balancer's 404 must not resolve a live operation to FAILED.
            return QueryAnswer.indeterminate(received);
        }
        String text = new String(received, StandardCharsets.UTF_8);
        return switch (statusOf(text)) {
            case "approved" ->
                    referenceOf(text)
                            .map(psp -> QueryAnswer.approved(psp, received))
                            .orElseGet(() -> QueryAnswer.indeterminate(received));
            case "declined" -> QueryAnswer.declined(received);
            // Explicit, parsed, and the sweeper's licence to resolve to FAILED - only an
            // answer in so many words earns it.
            case "unrecognised" -> QueryAnswer.unrecognised(received);
            default -> QueryAnswer.indeterminate(received);
        };
    }

    private static String statusOf(String text) {
        Matcher status = STATUS_FIELD.matcher(text);
        return status.find() ? status.group(1) : "";
    }

    private static Optional<ProviderReference> referenceOf(String text) {
        Matcher reference = REFERENCE_FIELD.matcher(text);
        if (!reference.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProviderReference(reference.group(1)));
        } catch (IllegalArgumentException unusable) {
            // A reference we cannot store or re-present is no reference (ProviderReference's
            // recorded bound); the whole answer is then unactionable.
            return Optional.empty();
        }
    }
}
