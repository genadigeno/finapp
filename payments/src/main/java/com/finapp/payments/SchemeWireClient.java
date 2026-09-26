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
 * The HTTP core of the simulated instant-scheme adapter (`P7-TSK-006`, ADR-0062 §1).
 *
 * <h2>A second wire, its own class — deliberately</h2>
 *
 * <p>{@code PspWireClient}'s javadoc says the card wire "is the only place it exists", and
 * that property is worth keeping symmetric: the scheme's paths, JSON fields and status words
 * live here and nowhere else, so neither wire can leak into the other's adapter
 * ({@code INV-PAY-03} is per adapter). The <em>classification doctrine</em> is the card
 * client's, restated because it is the platform's, not the card's: <strong>only
 * {@link ConnectException} is knowledge</strong> that nothing happened ({@code NOTHING_SENT});
 * every other transport failure, every non-200, every unmapped or truncated body is
 * {@code INDETERMINATE} ({@code INV-LIFE-03}) — and an acceptance missing the scheme
 * reference reconciliation must key is unactionable, so it is {@code INDETERMINATE} too.
 *
 * <h2>What every dispatch carries</h2>
 *
 * <p>Our end-to-end reference — as the {@code Idempotency-Key} header <em>and</em> the
 * {@code endToEndReference} body field (the scheme-natural spelling), asserted at the wire by
 * the contract tests: the scheme deduplicates on it, which is what makes any instance's
 * re-send idempotent (`INV-PAY-04` on the push wire). The API credential travels as
 * {@code Authorization: Bearer <base64(key)>}, decoded and confined by the composition root
 * ({@code InstantSchemeKey}); this class sees key bytes only.
 */
final class SchemeWireClient {

    static final int MAX_EVIDENCE_BYTES = ProviderEvidenceStore.MAX_PAYLOAD_BYTES;

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern REFERENCE_FIELD =
            Pattern.compile("\"reference\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CYCLE_FIELD = Pattern.compile("\"cycle\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DESTINATION_FIELD =
            Pattern.compile("\"destination\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SUFFIX_FIELD = Pattern.compile("\"suffix\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PAYEE_FIELD = Pattern.compile("\"payee\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern HANDLE_FIELD = Pattern.compile("\"handle\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;

    // The raw credential, under the one field name ADR-0019 keeps outside the
    // secretsAreWrapped vocabulary (the PspWireClient idiom): the header value is built per
    // request and stored nowhere.
    private final byte[] key;

    SchemeWireClient(URI baseUrl, Duration timeout, byte[] key) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the scheme timeout must be positive");
        }
        this.key = Objects.requireNonNull(key, "key must not be null").clone();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** POSTs a send; total — every misbehaviour is a {@link PushAnswer}. */
    PushAnswer send(String path, EndToEndReference reference, String body) {
        Received received = post(path, reference, body);
        if (received.refusedConnection()) {
            return PushAnswer.nothingSent();
        }
        return received.usable()
                ? switch (received.status()) {
                    case "accepted" ->
                            received.reference()
                                    .map(scheme ->
                                            PushAnswer.accepted(
                                                    scheme, received.cycle(),
                                                    received.bytes()))
                                    .orElseGet(() -> PushAnswer.indeterminate(received.bytes()));
                    case "rejected" -> PushAnswer.rejected(received.bytes());
                    default -> PushAnswer.indeterminate(received.bytes());
                }
                : received.hasBytes()
                        ? PushAnswer.indeterminate(received.bytes())
                        : PushAnswer.indeterminate();
    }

    /** POSTs the grant exchange; the three stored values or nothing (ADR-0062 §2). */
    ExchangeAnswer exchange(String path, EndToEndReference reference, String body) {
        Received received = post(path, reference, body);
        if (received.refusedConnection()) {
            return ExchangeAnswer.nothingSent();
        }
        if (!received.usable()) {
            return received.hasBytes()
                    ? ExchangeAnswer.indeterminate(received.bytes())
                    : ExchangeAnswer.indeterminate();
        }
        return switch (received.status()) {
            case "exchanged" -> exchangedOf(received);
            case "refused" -> ExchangeAnswer.refused(received.bytes());
            default -> ExchangeAnswer.indeterminate(received.bytes());
        };
    }

    /** POSTs an initiation; the payer's authorization handle or nothing. */
    InitiationAnswer initiate(String path, EndToEndReference reference, String body) {
        Received received = post(path, reference, body);
        if (received.refusedConnection()) {
            return InitiationAnswer.nothingSent();
        }
        if (!received.usable()) {
            return received.hasBytes()
                    ? InitiationAnswer.indeterminate(received.bytes())
                    : InitiationAnswer.indeterminate();
        }
        return switch (received.status()) {
            case "initiated" ->
                    fieldOf(received.text(), HANDLE_FIELD)
                            .filter(handle ->
                                    !handle.isBlank()
                                            && handle.length()
                                                    <= InitiationAnswer.MAX_HANDLE_LENGTH)
                            .map(handle ->
                                    InitiationAnswer.initiated(handle, received.bytes()))
                            .orElseGet(() ->
                                    InitiationAnswer.indeterminate(received.bytes()));
            case "refused" -> InitiationAnswer.refused(received.bytes());
            default -> InitiationAnswer.indeterminate(received.bytes());
        };
    }

    /** GETs the scheme's view of the operation our reference names; same totality. */
    PushInquiryAnswer inquire(String path) {
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
            // No NOTHING_SENT for an inquiry: one that never arrived is simply asked again.
            return PushInquiryAnswer.indeterminate();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PushInquiryAnswer.indeterminate();
        }
        byte[] received = response.body();
        if (received.length == 0 || received.length > MAX_EVIDENCE_BYTES) {
            return PushInquiryAnswer.indeterminate();
        }
        if (response.statusCode() != 200) {
            // A 404 included: a status code is not an answer (the card query's rule).
            return PushInquiryAnswer.indeterminate(received);
        }
        String text = new String(received, StandardCharsets.UTF_8);
        return switch (statusOf(text)) {
            case "accepted" ->
                    referenceOf(text)
                            .map(scheme ->
                                    PushInquiryAnswer.accepted(
                                            scheme, fieldOf(text, CYCLE_FIELD)
                                                    .filter(SchemeWireClient::cycleFits),
                                            received))
                            .orElseGet(() -> PushInquiryAnswer.indeterminate(received));
            case "rejected" -> PushInquiryAnswer.rejected(received);
            // Explicit, parsed: the word that, past the declared deadline, licenses
            // "never executed" (ADR-0062 §3) - only an answer in so many words earns it.
            case "unrecognised" -> PushInquiryAnswer.unrecognised(received);
            default -> PushInquiryAnswer.indeterminate(received);
        };
    }

    // ------------------------------------------------------------------

    private record Received(
            boolean refusedConnection, int statusCode, byte[] bytes, String text) {

        boolean usable() {
            return !refusedConnection
                    && statusCode == 200
                    && bytes != null
                    && bytes.length > 0
                    && bytes.length <= MAX_EVIDENCE_BYTES;
        }

        boolean hasBytes() {
            return bytes != null && bytes.length > 0 && bytes.length <= MAX_EVIDENCE_BYTES;
        }

        String status() {
            return statusOf(text);
        }

        Optional<ProviderReference> reference() {
            return referenceOf(text);
        }

        Optional<String> cycle() {
            return fieldOf(text, CYCLE_FIELD).filter(SchemeWireClient::cycleFits);
        }
    }

    private Received post(String path, EndToEndReference reference, String body) {
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
            return new Received(true, 0, null, "");
        } catch (IOException ambiguous) {
            // Sent, or possibly sent, and no answer: silence is ambiguity, never failure.
            return new Received(false, 0, null, "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Received(false, 0, null, "");
        }
        byte[] bytes = response.body();
        String text =
                bytes.length > 0 && bytes.length <= MAX_EVIDENCE_BYTES
                        ? new String(bytes, StandardCharsets.UTF_8)
                        : "";
        return new Received(false, response.statusCode(), bytes, text);
    }

    private static ExchangeAnswer exchangedOf(Received received) {
        Optional<ProviderReference> destination =
                fieldOf(received.text(), DESTINATION_FIELD)
                        .flatMap(SchemeWireClient::storableReference);
        Optional<String> suffix =
                fieldOf(received.text(), SUFFIX_FIELD)
                        .filter(s -> s.length() == ExchangeAnswer.DISPLAY_SUFFIX_LENGTH);
        Optional<ExchangeAnswer.ConfirmationOfPayee> payee =
                fieldOf(received.text(), PAYEE_FIELD).flatMap(SchemeWireClient::payeeOf);
        if (destination.isEmpty() || suffix.isEmpty() || payee.isEmpty()) {
            // An exchange missing any of the three stored values is unactionable - not
            // knowledge (the approved-without-reference rule, at the grant).
            return ExchangeAnswer.indeterminate(received.bytes());
        }
        return ExchangeAnswer.exchanged(
                destination.get(), suffix.get(), payee.get(), received.bytes());
    }

    private static Optional<ExchangeAnswer.ConfirmationOfPayee> payeeOf(String value) {
        return switch (value) {
            case "match" -> Optional.of(ExchangeAnswer.ConfirmationOfPayee.MATCH);
            case "close_match" -> Optional.of(ExchangeAnswer.ConfirmationOfPayee.CLOSE_MATCH);
            case "no_match" -> Optional.of(ExchangeAnswer.ConfirmationOfPayee.NO_MATCH);
            case "unavailable" -> Optional.of(ExchangeAnswer.ConfirmationOfPayee.UNAVAILABLE);
            default -> Optional.empty();
        };
    }

    private static boolean cycleFits(String cycle) {
        return !cycle.isBlank() && cycle.length() <= PushAnswer.MAX_CYCLE_LENGTH;
    }

    private static String statusOf(String text) {
        Matcher status = STATUS_FIELD.matcher(text);
        return status.find() ? status.group(1) : "";
    }

    private static Optional<String> fieldOf(String text, Pattern field) {
        Matcher matcher = field.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<ProviderReference> referenceOf(String text) {
        return fieldOf(text, REFERENCE_FIELD).flatMap(SchemeWireClient::storableReference);
    }

    private static Optional<ProviderReference> storableReference(String value) {
        try {
            return Optional.of(new ProviderReference(value));
        } catch (IllegalArgumentException unusable) {
            // A reference we cannot store or re-present is no reference.
            return Optional.empty();
        }
    }
}
