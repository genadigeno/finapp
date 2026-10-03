package com.finapp.settlement;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The failures this module reports to a client (`P8-TSK-003`, ADR-0066).
 *
 * <p>Namespaced {@code settlement.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against
 * these strings, so one is deprecated rather than renamed.
 *
 * <p><strong>{@code settlement.FileNotFound} is a deliberate departure</strong> from the
 * payments rule that an unknown identifier is the anonymous {@code api.NotFound}: every route
 * here sits behind an operator permission, so the surface is no oracle over anyone else's
 * resources — and an investigator chasing a break is told plainly that the id is wrong rather
 * than left to wonder which of three anonymous causes fired. Unknown and malformed ids are
 * still ONE answer, and a guessed id records nothing.
 */
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {

    /**
     * The named source is not in the compiled register ({@code INV-SET-05}): the caller
     * mis-spoke, nothing was written. A {@code 422} — a body field the caller can correct.
     */
    SOURCE_UNKNOWN(
            "settlement.SourceUnknown",
            422,
            "No declared settlement source has this code."),

    /**
     * The source is declared but retired: its identity stands, its door is shut. A {@code 409}
     * — the request is well-formed and the source's operational state refuses it.
     */
    SOURCE_RETIRED(
            "settlement.SourceRetired",
            409,
            "This settlement source is retired and accepts no deliveries."),

    /**
     * The decoded content is over 8 MiB or 50,000 records (ADR-0066 §4): refused at the door,
     * nothing stored but the audit record. A {@code 413} — the bounds exist because parse and
     * acceptance are each one transaction.
     */
    FILE_TOO_LARGE(
            "settlement.FileTooLarge",
            413,
            "The delivery exceeds the settlement file bounds."),

    /**
     * The door screen found instrument data — a card-number or account-identifier shape — in
     * the delivered bytes (ADR-0066 §3–§4): a metadata row and an audit record were written,
     * the value nowhere. A {@code 422}; the detail names the line and field, never the value,
     * and recovery is re-presentation of a clean file.
     */
    DELIVERY_REFUSED(
            "settlement.DeliveryRefused",
            422,
            "The delivery was refused by the door screen; only metadata was recorded."),

    /** No settlement file has this id — unknown and malformed alike, recording nothing. */
    FILE_NOT_FOUND(
            "settlement.FileNotFound",
            404,
            "No settlement file has this identifier."),

    /**
     * The file cannot be attested: not an upload (a pull is authenticated by its source's
     * credential), already attested by somebody else, or terminal. A {@code 409} — the caller
     * holds the permission; this file's own facts refuse the act.
     */
    FILE_NOT_ATTESTABLE(
            "settlement.FileNotAttestable",
            409,
            "This settlement file cannot be attested."),

    /**
     * The attester is the uploader ({@code INV-SET-07}, {@code INV-AUD-04}): the whole point
     * of attestation is a second person, refused at the domain and again by the database
     * {@code CHECK}. A {@code 409}.
     */
    ATTESTATION_BY_SUBMITTER(
            "settlement.AttestationBySubmitter",
            409,
            "The uploader cannot attest their own file; a second person must."),

    /**
     * No settlement batch has this id (`P8-TSK-008`) — unknown and malformed alike,
     * recording nothing; the {@code FileNotFound} departure's reasoning, at the batch.
     */
    BATCH_NOT_FOUND(
            "settlement.BatchNotFound",
            404,
            "No settlement batch has this identifier."),

    /**
     * The file is not a readmissible rejection (`P8-TSK-022`, ADR-0066 §8): readmission
     * recovers a file OUR validation rejected, a {@code DECLINED} one, or a
     * {@code CONFLICTING_BATCH} one — never a file still in its machine, never one rejected
     * {@code SOURCE_RETIRED}. A {@code 409}: the file's own facts refuse the act.
     */
    FILE_NOT_REJECTED(
            "settlement.FileNotRejected",
            409,
            "This settlement file is not a rejection that can be readmitted."),

    /**
     * The original was rejected {@code CONFLICTING_BATCH}, and a live batch still holds the
     * identity its bytes declare (`P8-TSK-022`): readmitting it now would only be rejected
     * again. A {@code 409}; it becomes readmissible once the standing batch is declined or
     * repudiated.
     */
    CONFLICTING_BATCH_STANDS(
            "settlement.ConflictingBatchStands",
            409,
            "A live settlement batch still holds this file's batch identity."),

    /**
     * The original is already readmitted (`P8-TSK-022`): a file is readmitted once —
     * {@code UNIQUE (readmits_file_id)} for every writer. A {@code 409}; a later rejection of
     * the readmission is itself readmissible.
     */
    FILE_ALREADY_READMITTED(
            "settlement.FileAlreadyReadmitted",
            409,
            "This settlement file has already been readmitted.");

    private final String code;
    private final int status;
    private final String title;

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
