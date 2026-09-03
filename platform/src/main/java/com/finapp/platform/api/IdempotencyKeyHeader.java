package com.finapp.platform.api;

import com.finapp.platform.idempotency.IdempotencyKey;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code Idempotency-Key} header: its name, and what a valid value looks like.
 *
 * <p>One definition, because the name is a published contract. It appears in
 * {@code API_CONVENTIONS.md}, in the OpenAPI document, in every client that sends it and in the
 * boundary check that requires it — and a second copy would drift while looking authoritative,
 * which is the argument {@code P0-DOC-003} already made for the correlation header.
 *
 * <p><strong>The bounds are {@link IdempotencyKey}'s, not new ones.</strong> Validating here
 * against a different limit than the store enforces would let a request pass the boundary and fail
 * three layers down against a {@code CHECK} constraint — which is exactly the shape of error
 * {@code IdempotencyKey} was written to prevent.
 *
 * <p><strong>The value is caller-supplied and is deliberately not sanitised.</strong> A key the
 * platform silently rewrote would not match the caller's retry, which defeats the entire purpose:
 * the client's second attempt would create a second financial effect. It is rejected or accepted,
 * never adjusted. That is the same decision {@code P0-TSK-025} made for an inbound correlation
 * identifier, reached from the opposite direction — there the value is *replaced* because nothing
 * depends on the caller's copy; here nothing may touch it because everything does.
 */
public final class IdempotencyKeyHeader {

    /** The header name, as clients send it. */
    public static final String NAME = "Idempotency-Key";

    /**
     * ASCII letters, digits and the punctuation a key generator actually produces.
     *
     * <p><strong>Default-deny, and the reason is log forging.</strong> {@link IdempotencyKey}
     * bounds length and blankness because those are the {@code CHECK} constraints on the table —
     * it deliberately mirrors the schema and the schema has no charset. That leaves a caller free
     * to send a key containing CR/LF, and the key is a value Phase 4 will log, put on an audit
     * record and store durably. A newline in it is a forged log line.
     *
     * <p>This is the same charset and the same default-deny reasoning as
     * {@code CorrelationTokens}, which validates the correlation identifier for exactly this
     * reason. A denylist would have to anticipate CR, LF, NUL, ANSI escapes, Unicode line
     * separators and bidirectional overrides, and is wrong the first time one is missed.
     *
     * <p><strong>The check is here and not on {@link IdempotencyKey}</strong> because this is the
     * untrusted boundary and that is the domain type: a key the platform generates itself needs no
     * charset check, and moving it down would make the type disagree with the constraints its own
     * javadoc says it mirrors. <strong>The limit that follows is real</strong>: a key arriving by
     * some future non-HTTP path — a message, a batch file — gets no charset check from here and
     * needs its own.
     */
    private static final Pattern PERMITTED = Pattern.compile("[A-Za-z0-9._:@/+=-]+");

    private IdempotencyKeyHeader() {}

    /**
     * Why this value is not a usable key, or empty if it is.
     *
     * <p>Returns a reason rather than throwing, because the boundary needs to put it in a
     * problem-detail body and an exception message is not a client-facing string
     * ({@code INV-AUD-02}, and {@code ApiException}'s split of log message from client detail).
     *
     * <p>The reason never quotes the value. It is the caller's own input, and echoing it into a
     * response is how a header becomes a reflection vector; the caller already knows what it sent.
     */
    public static Optional<String> rejectionReason(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of("must not be blank");
        }
        if (value.length() > IdempotencyKey.MAX_LENGTH) {
            return Optional.of("must be at most " + IdempotencyKey.MAX_LENGTH + " characters");
        }
        if (!PERMITTED.matcher(value).matches()) {
            return Optional.of("must contain only letters, digits and ._:@/+=-");
        }
        return Optional.empty();
    }

    /** Whether this value could be used as an idempotency key. */
    public static boolean isValid(String value) {
        return rejectionReason(value).isEmpty();
    }
}
