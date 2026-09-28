package com.finapp.merchant;

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
 * The simulated payout provider's adapter (`P6-TSK-012`, ADR-0051 §4) — the one class that
 * knows the payout wire's words.
 *
 * <p>The wire is ours and exists only here: {@code POST /payouts} with our reference as the
 * {@code Idempotency-Key} header and {@code {"destination":…,"amountMinor":…,"currency":…,
 * "scale":…}}, answered {@code {"status":"paid","reference":…}}, {@code {"status":"declined"}}
 * or anything else; {@code GET /payouts/{ourReference}} answers the same words plus
 * {@code {"status":"unrecognised"}}. Every other shape — a non-200, a pending word, garbage, a
 * reference that is not a reference — is indeterminate, never success (the total mapping,
 * {@code INV-PAY-03}'s discipline, {@code PspWireClient}'s shape).
 *
 * <p><strong>Transport, classified as the PSP's wire does.</strong> A refused connection on a
 * send is the one transport failure that is knowledge — nothing was transmitted — and answers
 * {@link PayoutAnswer.Verdict#NOTHING_SENT}; a timeout, a dropped connection or bytes that were
 * not HTTP are indeterminate, because the request may have arrived (ADR-0046 §2). A query is
 * read-only, so every transport failure there is simply no answer.
 *
 * <p><strong>The credential regime arrives here</strong>, with the provider that moves money:
 * {@code Authorization: Bearer} with the payout provider's own API key ({@code PayoutProviderKey}
 * in {@code app}, one key per concern). The destination reference is unwrapped exactly once,
 * onto this wire — the expose site is named in {@code SecretsAreUnwrappedInOnePlaceTest}.
 */
public final class SimulatedPayoutProvider implements PayoutProvider {

    public static final String PAYOUTS_PATH = "/payouts";

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");

    private static final Pattern REFERENCE =
            Pattern.compile("\"reference\"\\s*:\\s*\"([^\"]*)\"");

    private static final Pattern PROVIDER_REFERENCE =
            Pattern.compile(PayoutProviderReference.REGEX);

    private final URI baseUrl;
    private final Duration timeout;
    private final byte[] key;
    private final HttpClient http;

    /**
     * @param key the payout provider API key, decoded by the composition root's confinement
     */
    public SimulatedPayoutProvider(URI baseUrl, Duration timeout, byte[] key) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the payout provider timeout must be positive");
        }
        if (key.length == 0) {
            throw new IllegalArgumentException("the payout provider key must not be empty");
        }
        this.key = key.clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public PayoutAnswer dispatch(PayoutRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String body =
                "{\"destination\":\""
                        // The destination reference's production unwrap onto the wire, and the
                        // only one besides the store's column (SecretsAreUnwrappedInOnePlaceTest).
                        // Its charset is [A-Za-z0-9_-], so it needs no JSON escaping.
                        + request.destination().expose()
                        + "\",\"amountMinor\":\""
                        + request.amount().minorUnits()
                        + "\",\"currency\":\""
                        + request.amount().currency().code()
                        + "\",\"scale\":"
                        + request.amount().scale()
                        + "}";
        HttpRequest send =
                HttpRequest.newBuilder(baseUrl.resolve(PAYOUTS_PATH))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .header(IDEMPOTENCY_KEY_HEADER, request.reference().value())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(send, HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException refused) {
            // The one transport failure that is knowledge: nothing was transmitted.
            return PayoutAnswer.nothingSent();
        } catch (IOException ambiguous) {
            // Sent, or possibly sent, and no answer. Silence is ambiguity (INV-LIFE-03).
            return PayoutAnswer.indeterminate();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PayoutAnswer.indeterminate();
        }
        byte[] received = response.body();
        if (received.length == 0 || received.length > PayoutEvidenceStore.MAX_PAYLOAD_BYTES) {
            return PayoutAnswer.indeterminate();
        }
        if (response.statusCode() != 200) {
            return PayoutAnswer.indeterminate(received);
        }
        String text = new String(received, StandardCharsets.UTF_8);
        return switch (word(STATUS, text).orElse("")) {
            case "paid" ->
                    providerReference(text)
                            .map(theirs -> PayoutAnswer.accepted(theirs, received))
                            // An acceptance nobody can reconcile against is not knowledge the
                            // books can use: without the provider's reference it stays open.
                            .orElseGet(() -> PayoutAnswer.indeterminate(received));
            case "declined" -> PayoutAnswer.declined(received);
            // The total mapping's default branch: indeterminate, never success.
            default -> PayoutAnswer.indeterminate(received);
        };
    }

    @Override
    public PayoutQueryAnswer query(PayoutReference ourReference) {
        Objects.requireNonNull(ourReference, "ourReference must not be null");
        HttpRequest ask =
                HttpRequest.newBuilder(
                                baseUrl.resolve(PAYOUTS_PATH + "/" + ourReference.value()))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + Base64.getEncoder().encodeToString(key))
                        .GET()
                        .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(ask, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException nothingUsable) {
            // Read-only: a refused connection here says nothing about the payout.
            return PayoutQueryAnswer.indeterminate();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PayoutQueryAnswer.indeterminate();
        }
        byte[] received = response.body();
        if (received.length == 0 || received.length > PayoutEvidenceStore.MAX_PAYLOAD_BYTES) {
            return PayoutQueryAnswer.indeterminate();
        }
        if (response.statusCode() != 200) {
            // A bodied 404 included: only the explicit word says "no record" - a 404 can be a
            // proxy, a misroute, a provider in maintenance (PspWireClient's recorded rule).
            return PayoutQueryAnswer.indeterminate(received);
        }
        String text = new String(received, StandardCharsets.UTF_8);
        return switch (word(STATUS, text).orElse("")) {
            case "paid" ->
                    providerReference(text)
                            .map(theirs -> PayoutQueryAnswer.accepted(theirs, received))
                            .orElseGet(() -> PayoutQueryAnswer.indeterminate(received));
            case "declined" -> PayoutQueryAnswer.declined(received);
            case "unrecognised" -> PayoutQueryAnswer.unrecognised(received);
            default -> PayoutQueryAnswer.indeterminate(received);
        };
    }

    private static Optional<PayoutProviderReference> providerReference(String text) {
        return word(REFERENCE, text)
                .filter(candidate -> PROVIDER_REFERENCE.matcher(candidate).matches())
                .map(PayoutProviderReference::new);
    }

    private static Optional<String> word(Pattern field, String text) {
        Matcher found = field.matcher(text);
        return found.find() ? Optional.of(found.group(1)) : Optional.empty();
    }
}
