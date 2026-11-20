package com.finapp.credit;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The bytes a provider actually sent, verbatim (`P10-TSK-005`): the evidence a credit record keeps
 * (`-006`, encrypted) so an answer can be explained and a dispute answered from what arrived
 * ({@code INV-HIST-02}). The {@code FxProvider.Evidence} shape. Since `P17-TSK-116` it names its media type too - the
 * {@code Content-Type} the adapter received with the bytes, as it arrived ({@link #mediaTypeOf}), or
 * {@value #OCTET_STREAM} when none usable came - so an investigator renders the evidence as what it is.
 *
 * <p>{@code toString} renders the length and the media type alone - a credit report never reaches a log line.
 */
public record CreditEvidence(byte[] bytes, String mediaType) {

    /** The media type of bytes of unknown kind (RFC 2046) - every evidence row before credit V027 reads it. */
    public static final String OCTET_STREAM = "application/octet-stream";

    /** The longest media type kept: credit V027's bound. */
    public static final int MAX_MEDIA_TYPE_LENGTH = 255;

    /** {@code type/subtype}, parameters allowed - credit V027's {@code CHECK}, exactly. */
    private static final Pattern MEDIA_TYPE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*( *;[ -~]*)?");

    public CreditEvidence {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(mediaType, "mediaType");
        if (!isMediaType(mediaType)) {
            throw new IllegalArgumentException("a media type is type/subtype, at most " + MAX_MEDIA_TYPE_LENGTH
                    + " characters");
        }
        bytes = bytes.clone();
    }

    /** Bytes of unknown kind - {@value #OCTET_STREAM}. */
    public CreditEvidence(byte[] bytes) {
        this(bytes, OCTET_STREAM);
    }

    /**
     * The media type a provider's {@code Content-Type} header names, as it arrived, or {@value #OCTET_STREAM} when the header
     * is absent, blank, unbounded or not a media type's shape - never refused: the evidence is kept whatever it is called.
     */
    public static String mediaTypeOf(Optional<String> contentType) {
        Objects.requireNonNull(contentType, "contentType");
        return contentType.map(String::strip).filter(CreditEvidence::isMediaType).orElse(OCTET_STREAM);
    }

    /** These bytes, named {@code mediaType} ({@link #mediaTypeOf}'s rule). */
    public CreditEvidence withMediaType(Optional<String> contentType) {
        return new CreditEvidence(bytes, mediaTypeOf(contentType));
    }

    private static boolean isMediaType(String candidate) {
        return candidate.length() >= 3 && candidate.length() <= MAX_MEDIA_TYPE_LENGTH
                && MEDIA_TYPE.matcher(candidate).matches();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CreditEvidence that && Arrays.equals(bytes, that.bytes) && mediaType.equals(that.mediaType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + mediaType.hashCode();
    }

    @Override
    public String toString() {
        return "CreditEvidence[" + mediaType + ", " + bytes.length + " bytes]";
    }
}
