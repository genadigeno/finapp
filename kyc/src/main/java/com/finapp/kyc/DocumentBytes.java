package com.finapp.kyc;

import java.util.Objects;

/**
 * The plaintext bytes of a captured document, bounded at construction.
 *
 * <h2>Why 512 KiB</h2>
 *
 * <p>The bound is chosen against the platform's global request cap ({@code finapp.api.max-request-bytes},
 * 1 MiB): the bytes arrive base64-encoded inside a JSON body, base64 inflates by 4/3, so 512 KiB
 * of document rides comfortably inside an ordinary bounded request and <strong>no request-limit
 * machinery changes for this endpoint</strong>. ADR-0036's own premise — simulated documents
 * measured in kilobytes — makes the bound generous; raising it is a decision that must also
 * revisit the request cap, which is why both numbers are named here.
 *
 * <p>The same bound is a {@code CHECK} constraint on {@code kyc_document.content_length}, so the
 * boundary and the schema cannot quietly disagree ({@code KycDocumentMigrationTest} reconciles).
 *
 * <p>Cloned in and out: a caller mutating its array afterwards must not mutate the value.
 */
public final class DocumentBytes {

    public static final int MAX_BYTES = 512 * 1024;

    private final byte[] value;

    private DocumentBytes(byte[] value) {
        this.value = value;
    }

    public static DocumentBytes of(byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length == 0) {
            throw new IllegalArgumentException("a document must not be empty");
        }
        if (value.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "a document must be at most " + MAX_BYTES + " bytes");
        }
        return new DocumentBytes(value.clone());
    }

    public byte[] value() {
        return value.clone();
    }

    public int length() {
        return value.length;
    }

    /** Never the content. The rendering of a document is a disclosure ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "DocumentBytes[" + value.length + " bytes]";
    }
}
