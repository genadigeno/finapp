package com.finapp.payments;

import java.util.Objects;

/**
 * The plaintext bytes of one dispute evidence document, bounded at construction (`P7-TSK-014`).
 *
 * <h2>Why 512 KiB, and why at most {@value #MAX_PER_DISPUTE} per dispute</h2>
 *
 * <p>{@code DocumentBytes}' bound and reasoning, restated: the bytes arrive base64-encoded in a
 * JSON body under the platform's 1 MiB request cap, and 512 KiB inflated by 4/3 rides inside it
 * with no request-limit machinery changed. The per-dispute count bounds the OTHER direction:
 * a representment carries its whole evidence set inline in one outbound request, so five
 * documents bound what one submission can put on the wire. The size bound is also `V022`'s
 * {@code CHECK} on {@code dispute_evidence.content_length}, so the boundary and the schema
 * cannot quietly disagree.
 *
 * <p>Cloned in and out: a caller mutating its array afterwards must not mutate the value.
 */
public final class DisputeEvidenceContent {

    public static final int MAX_BYTES = 512 * 1024;

    /** The documents one dispute may carry — judged under the dispute's row lock. */
    public static final int MAX_PER_DISPUTE = 5;

    private final byte[] value;

    private DisputeEvidenceContent(byte[] value) {
        this.value = value;
    }

    public static DisputeEvidenceContent of(byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length == 0) {
            throw new IllegalArgumentException("an evidence document must not be empty");
        }
        if (value.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "an evidence document must be at most " + MAX_BYTES + " bytes");
        }
        return new DisputeEvidenceContent(value.clone());
    }

    public byte[] value() {
        return value.clone();
    }

    public int length() {
        return value.length;
    }

    /** Never the content: rendering a document is a disclosure ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "DisputeEvidenceContent[" + value.length + " bytes]";
    }
}
