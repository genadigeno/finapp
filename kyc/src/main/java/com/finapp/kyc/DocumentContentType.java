package com.finapp.kyc;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

/**
 * What the captured bytes are — the closed set of formats the platform accepts (ADR-0036's
 * "size- and type-constrained at the boundary").
 *
 * <p>Closed deliberately: a document store that accepts any declared media type accepts
 * {@code text/html} and {@code image/svg+xml}, and the first component that ever renders a
 * document to a reviewer then executes whatever a customer uploaded. The set is the three
 * formats an identity document arrives in, and widening it is a decision with a diff rather
 * than a value nobody vetted.
 *
 * <p><strong>The declaration is not verified against the bytes</strong>, and that is recorded
 * rather than implied: content sniffing is a rabbit hole of its own (polyglot files defeat
 * naive magic-number checks), the bytes are never executed or rendered by this platform, and
 * the component that eventually renders them treats every document as untrusted regardless of
 * this column. The declared type is routing metadata for the verification check, not a safety
 * proof.
 */
@RequiredArgsConstructor
public enum DocumentContentType {
    JPEG("image/jpeg"),
    PNG("image/png"),
    PDF("application/pdf");

    private final String mediaType;

    public String mediaType() {
        return mediaType;
    }

    /** The quoted, comma-separated value list the migration's {@code CHECK} constraint uses. */
    public static String sqlValueList() {
        return Stream.of(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
