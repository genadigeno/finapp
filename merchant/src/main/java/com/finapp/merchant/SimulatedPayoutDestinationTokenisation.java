package com.finapp.merchant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated payout provider's destination exchange (`P6-TSK-011`, ADR-0049's
 * simulated-everything stance) — the {@code SimulatedTokenisationAdapter} shape, for bank data.
 *
 * <p>The wire is ours and exists only here: {@code POST /payout-destinations/tokenisations} with
 * the grant, answered {@code {"status":"tokenised","reference":…,"last4":…}} or
 * {@code {"status":"refused"}}. Everything else — a non-200, a timeout, garbage, an unmapped
 * status, a reference {@link PayoutDestinationReference} refuses, a suffix
 * {@link PayoutDestinationTokenisation.TokenisedDestination} refuses — is {@code UNAVAILABLE},
 * and the proposal fails clean (the total mapping's default is never success).
 *
 * <p><strong>No credential on this exchange, deliberately</strong> — the tokenisation adapter's
 * precedent: the simulated endpoint is whatever a test or demo stands up, and the credential
 * regime arrives with the provider that moves money (`P6-TSK-012`). The grant is unwrapped
 * exactly once, onto this wire — the expose site is named in
 * {@code SecretsAreUnwrappedInOnePlaceTest}.
 */
public final class SimulatedPayoutDestinationTokenisation implements PayoutDestinationTokenisation {

    /** The simulated wire path — published for tests that stub the provider. */
    public static final String TOKENISATIONS_PATH = "/payout-destinations/tokenisations";

    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern REFERENCE_FIELD =
            Pattern.compile("\"reference\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LAST4_FIELD = Pattern.compile("\"last4\"\\s*:\\s*\"([^\"]*)\"");

    /** Bounded like every provider read; an exchange answer is small JSON. */
    private static final int MAX_BODY_BYTES = 65_536;

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;

    public SimulatedPayoutDestinationTokenisation(URI baseUrl, Duration timeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the tokenisation timeout must be positive");
        }
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Exchange exchange(PayoutDestinationGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");
        HttpRequest request =
                HttpRequest.newBuilder(baseUrl.resolve(TOKENISATIONS_PATH))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        // The grant's one unwrap, onto the wire it exists for. The charset is
                        // PayoutDestinationGrant's own, so the body needs no escaping machinery.
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"clientToken\":\"" + grant.expose() + "\"}"))
                        .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException nothingUsable) {
            return Exchange.unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Exchange.unavailable();
        }
        byte[] received = response.body();
        if (response.statusCode() != 200
                || received.length == 0
                || received.length > MAX_BODY_BYTES) {
            return Exchange.unavailable();
        }
        String text = new String(received, StandardCharsets.UTF_8);
        return switch (firstOf(STATUS_FIELD, text).orElse("")) {
            case "tokenised" -> parsedDestination(text).map(Exchange::tokenised)
                    // An answer we cannot store is not a destination: unavailable, never a
                    // fallback to anything rawer.
                    .orElseGet(Exchange::unavailable);
            case "refused" -> Exchange.refused();
            // The total mapping's default branch: unavailable, never success.
            default -> Exchange.unavailable();
        };
    }

    private static Optional<TokenisedDestination> parsedDestination(String text) {
        Optional<String> reference = firstOf(REFERENCE_FIELD, text);
        Optional<String> last4 = firstOf(LAST4_FIELD, text);
        if (reference.isEmpty() || last4.isEmpty()) {
            return Optional.empty();
        }
        try {
            // The reference's own rule judges it (a bank-detail-shaped or malformed value is no
            // reference); the suffix's shape is TokenisedDestination's - one definition each.
            return Optional.of(
                    new TokenisedDestination(
                            PayoutDestinationReference.of(reference.get()), last4.get()));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    private static Optional<String> firstOf(Pattern field, String text) {
        Matcher matcher = field.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
