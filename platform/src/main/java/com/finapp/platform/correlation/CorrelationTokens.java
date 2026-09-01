package com.finapp.platform.correlation;

import java.util.Objects;

/**
 * Validation shared by {@link CorrelationId} and {@link CausationId}.
 *
 * <p>Both are opaque tokens that may arrive from outside the platform and both end up in log
 * lines, event envelopes and audit rows, so both need the same guarantee: what goes in is
 * printable, bounded, and cannot forge a log entry.
 */
final class CorrelationTokens {

    /**
     * ASCII letters, digits and the punctuation tracing systems actually use: UUIDs, W3C
     * trace-context values, and provider references all fit.
     *
     * <p>Everything else is refused, rather than the more usual approach of listing forbidden
     * characters. A denylist has to anticipate every dangerous character — CR, LF, NUL, ANSI
     * escapes, Unicode line separators, bidirectional overrides — and is wrong the first time
     * one is missed. This is the same default-deny reasoning as the no-floating-point rule.
     */
    private static final String ALLOWED = "^[A-Za-z0-9._:@/+=-]+$";

    private CorrelationTokens() {
        // Static validation helper; not instantiable.
    }

    static String validate(String value, int maxLength, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (value.length() > maxLength) {
            // Rejected, not truncated. A truncated identifier no longer matches the caller's
            // own trace, so it would break correlation while appearing to succeed.
            throw new IllegalArgumentException(
                    what + " must be at most " + maxLength + " characters but was " + value.length());
        }
        if (!value.matches(ALLOWED)) {
            // The value is not echoed back. A rejection message containing the offending
            // input would put the very control characters this check exists to stop into the
            // log line reporting the rejection.
            throw new IllegalArgumentException(
                    what + " contains characters outside the permitted token charset");
        }
        return value;
    }
}
