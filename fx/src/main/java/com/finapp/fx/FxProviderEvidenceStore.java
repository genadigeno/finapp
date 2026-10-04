package com.finapp.fx;

import java.time.Instant;
import java.util.List;

/**
 * Verbatim FX provider payloads (`P9-TSK-006`, {@code INV-HIST-02}): every request, response,
 * inquiry result and callback, byte for byte, encrypted under {@code FINAPP_FX_EVIDENCE_KEY} with
 * the plaintext's SHA-256 recorded at capture and verified on read. Append-only for every writer.
 *
 * <p><strong>Keyed by our reference</strong> ({@code INV-PAY-04}): the quote request's {@code QR}
 * or the execution's {@code T}, with the provider's code - the quote and cover rows that will
 * name these references do not exist yet (`P9-TSK-008`, `-012`), and our reference is already
 * the one key every leg of the exchange shares. Inserted on the caller's unit of work.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface FxProviderEvidenceStore<T> {

    /** The wire's retention bound - a body past it is not retained (and is indeterminate). */
    int MAX_PAYLOAD_BYTES = 1_048_576;

    /** What kind of bytes these are. */
    enum Kind {
        REQUEST,
        RESPONSE,
        INQUIRY_RESULT,
        CALLBACK
    }

    void append(
            T unitOfWork,
            String providerCode,
            String clientReference,
            Kind kind,
            byte[] payload,
            Instant recordedAt);

    /**
     * The retained payloads for one provider and reference, oldest first, each decrypted and its
     * checksum verified - a mismatch is loud, never content.
     */
    List<byte[]> payloadsFor(T unitOfWork, String providerCode, String clientReference);
}
