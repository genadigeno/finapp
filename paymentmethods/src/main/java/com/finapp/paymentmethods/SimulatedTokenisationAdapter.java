package com.finapp.paymentmethods;

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
 * The simulated tokenisation provider (`P5-TSK-005`, ADR-0049's simulated-everything stance).
 *
 * <p>The wire is ours and exists only here (`INV-PAY-03`'s discipline at the tokenisation
 * boundary): {@code POST /tokenisations} with the grant, answered
 * {@code {"status":"tokenised","token":…,"brand":…,"last4":…,"expiryMonth":…,"expiryYear":…}}
 * or {@code {"status":"refused"}}. Everything else — a non-200, a timeout, garbage, an unmapped
 * status, a token {@link TokenReference} refuses — is {@code UNAVAILABLE}, and the attach fails
 * clean (the total mapping's default is never success).
 *
 * <p><strong>No credential on this exchange, deliberately</strong> — the Phase 2
 * verification-adapter precedent: the simulated endpoint is whatever a test or demo stands up,
 * and the credential regime arrives where money moves (`P5-TSK-003`'s provider). The grant is
 * unwrapped exactly once, onto this wire — the expose site is named in
 * {@code SecretsAreUnwrappedInOnePlaceTest}.
 */
public final class SimulatedTokenisationAdapter implements TokenisationProvider {

    /** The simulated wire path — published for tests that stub the provider. */
    public static final String TOKENISATIONS_PATH = "/tokenisations";

    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");
    // Extracts the wire's "token" member - the instrument reference. Named for what it
    // yields rather than for the member, because a Pattern cannot be a Sensitive<> and
    // the secretsAreWrapped vocabulary rightly refuses a bare "token" name (the
    // DIGITS_AND_SEPARATORS accurate-rename precedent, P5-TSK-004).
    private static final Pattern REFERENCE_FIELD = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BRAND_FIELD = Pattern.compile("\"brand\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LAST4_FIELD = Pattern.compile("\"last4\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EXPIRY_MONTH_FIELD =
            Pattern.compile("\"expiryMonth\"\\s*:\\s*([0-9]{1,4})");
    private static final Pattern EXPIRY_YEAR_FIELD =
            Pattern.compile("\"expiryYear\"\\s*:\\s*([0-9]{1,6})");

    /** Bounded like every provider read; a tokenisation answer is small JSON. */
    private static final int MAX_BODY_BYTES = 65_536;

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;

    public SimulatedTokenisationAdapter(URI baseUrl, Duration timeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the tokenisation timeout must be positive");
        }
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Exchange exchange(TokenisationGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");
        HttpRequest request =
                HttpRequest.newBuilder(baseUrl.resolve(TOKENISATIONS_PATH))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        // The grant's one unwrap, onto the wire it exists for. The charset is
                        // TokenisationGrant's own, so the body needs no escaping machinery.
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
            case "tokenised" -> parsedInstrument(text).map(Exchange::tokenised)
                    // An answer we cannot store is not an instrument: unavailable, never a
                    // fallback to anything rawer (INV-PAY-02's own sentence).
                    .orElseGet(Exchange::unavailable);
            case "refused" -> Exchange.refused();
            // The total mapping's default branch: unavailable, never success.
            default -> Exchange.unavailable();
        };
    }

    private static Optional<TokenisedInstrument> parsedInstrument(String text) {
        Optional<String> token = firstOf(REFERENCE_FIELD, text);
        Optional<String> brand = firstOf(BRAND_FIELD, text);
        Optional<String> last4 = firstOf(LAST4_FIELD, text);
        Optional<String> month = firstOf(EXPIRY_MONTH_FIELD, text);
        Optional<String> year = firstOf(EXPIRY_YEAR_FIELD, text);
        if (token.isEmpty()
                || brand.isEmpty()
                || last4.isEmpty()
                || month.isEmpty()
                || year.isEmpty()) {
            return Optional.empty();
        }
        try {
            // TokenReference's own rule judges the token (a PAN-shaped or malformed one is no
            // token); the display fields' shapes are judged by PaymentMethod's constructor at
            // the attach - one definition each, no restatement to drift.
            return Optional.of(
                    new TokenisedInstrument(
                            TokenReference.of(token.get()),
                            brand.get(),
                            last4.get(),
                            Integer.parseInt(month.get()),
                            Integer.parseInt(year.get())));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    private static Optional<String> firstOf(Pattern field, String text) {
        Matcher matcher = field.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
