package com.finapp.settlement;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The settlement module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning — the {@code PaymentsAuditAction} rule. The door's
 * own actions arrived with the door (`P8-TSK-003`); the pull's arrive with the pull
 * (`P8-TSK-021`); readmission's and the re-parse verification's with them (`P8-TSK-022`); the
 * repudiation's with it (`P8-TSK-023`).
 */
@RequiredArgsConstructor
public enum SettlementAuditAction implements AuditableAction {

    /**
     * The door refused a delivery — instrument data in screened text, or a delivery over the
     * size or line bound (ADR-0066 §4). Written in the refusal's transaction; the change
     * summary names the source, the reason, the checksum and the shape's position, and
     * <strong>never the value</strong>. For a content refusal the
     * {@code settlement.refused_delivery} metadata row stands beside it; an over-bound
     * delivery leaves this record alone.
     */
    SETTLEMENT_DELIVERY_REFUSED(
            "settlement.SettlementDeliveryRefused",
            "The settlement door refused a delivery; the record names the source, the reason"
                    + " and the content address, never a value from the file.",
            false),

    /**
     * A person introduced settlement evidence over the upload door (`P8-TSK-003`, ADR-0066
     * §1). Written in the reception's one transaction; the change summary names the source,
     * the channel and the content address — never a value or the business date's claim. A
     * duplicate delivery writes its receipt and no second record.
     */
    SETTLEMENT_FILE_UPLOADED(
            "settlement.SettlementFileUploaded",
            "An operator uploaded a settlement file; the record names the source, the channel"
                    + " and the content address, never the content.",
            false),

    /**
     * A second person attested an uploaded file (`INV-SET-07`, `INV-AUD-04`): the
     * {@code NULL → value} fact that lets the accept leg (`P8-TSK-009`) act on it. Written in
     * the attestation's transaction; distinctness from the uploader is refused at the domain
     * and by {@code CHECK} before this record can exist.
     */
    SETTLEMENT_FILE_ATTESTED(
            "settlement.SettlementFileAttested",
            "A second person attested an uploaded settlement file, making it eligible for"
                    + " acceptance once parsed.",
            false),

    /**
     * Somebody read a settlement file's raw content (`INV-REC-10`): one record per read, the
     * reason required, committed with the read before a byte is served. A verification
     * failure — tamper, transplant, truncation — is the same record with outcome
     * {@code FAILED}, and nothing is served.
     */
    SETTLEMENT_FILE_CONTENT_READ(
            "settlement.SettlementFileContentRead",
            "A settlement file's raw content was read (or a read failed verification), with"
                    + " the investigator's recorded reason.",
            true),

    /**
     * The parse leg rejected a file whole (`P8-TSK-008`, ADR-0066 §9): the platform's own
     * verdict, acting-only, written in the rejecting transaction beside the error rows, the
     * history and the published event. The change summary names the source, the code and the
     * error count — never a value. The parse leg's decryption is the platform's processing,
     * recorded by the file's history; {@code SettlementFileContentRead} stays a person's
     * read.
     */
    SETTLEMENT_FILE_REJECTED(
            "settlement.SettlementFileRejected",
            "The parse leg rejected a settlement file whole; the record names the source and"
                    + " the rejection code, never a value from the file.",
            false),

    /**
     * A person declined a settlement file (`P8-TSK-008`, moved from `P8-TSK-003`): a
     * reasoned judgement, never our validation — {@code RECEIVED | PARSED → REJECTED}, a
     * parsed file's batch rejected in the same transaction, the live key freed. Requires a
     * reason.
     */
    SETTLEMENT_FILE_DECLINED(
            "settlement.SettlementFileDeclined",
            "An operator declined a settlement file, with the recorded reason; a parsed"
                    + " file's batch was rejected with it and its live key freed.",
            true),

    /**
     * The accept leg recognised a batch (`P8-TSK-009`, ADR-0065 §2): the platform's own act,
     * acting-only — a losing racer records nothing — written in the acceptance transaction
     * beside the run, the items, the remittance expectation and the recognition posting. The
     * change summary carries identifiers and counts only (the
     * {@code UnmatchedConfirmations} precedent): source, sequence, items, whether a
     * remittance opened, whether the posting was omitted — never an amount.
     */
    SETTLEMENT_BATCH_ACCEPTED(
            "settlement.SettlementBatchAccepted",
            "The accept leg recognised a settlement batch: sequenced gaplessly, its fees"
                    + " posted (or honestly omitted at zero), its run and items handed to"
                    + " reconciliation - identifiers and counts only.",
            false),

    /**
     * The platform received a settlement file by pulling it (`P8-TSK-021`, ADR-0066 §1) - the
     * door's audit word for the {@code PULL} channel, written in the reception's one
     * transaction, acting-only: a duplicate pull writes its receipt and no second record. The
     * source's own confined credential is the authentication ({@code INV-SET-07}).
     */
    SETTLEMENT_FILE_RECEIVED_BY_PULL(
            "settlement.SettlementFileReceivedByPull",
            "The platform pulled a settlement file over the source's own credential; the record"
                    + " names the source, the channel and the content address, never the"
                    + " content.",
            false),

    /**
     * An operator's fetch took its permit (the Phase 8 -> 9 transition, SEC-08): written in the
     * permit's own transaction - the fetch's first effect - so a fetch that dies past it, before
     * {@link #SETTLEMENT_FETCH_REQUESTED} records what it came to, still leaves who asked for
     * which report. Names the source and the business key, never a byte of the report. A
     * source unknown, retired or not pulled takes no permit and writes none.
     */
    SETTLEMENT_FETCH_STARTED(
            "settlement.SettlementFetchStarted",
            "An operator's requested pull of a source's report took its permit; the record"
                    + " names the source and the business key.",
            false),

    /**
     * An operator asked for a source's report to be pulled now (`P8-TSK-021`,
     * {@code POST /v1/operator/settlement/sources/{code}/fetch}): the request and what it came
     * to - the source, the business key and the outcome, never a byte of the report.
     */
    SETTLEMENT_FETCH_REQUESTED(
            "settlement.SettlementFetchRequested",
            "An operator requested a pull of a source's report; the record names the source,"
                    + " the business key and the outcome.",
            false),

    /**
     * A person readmitted a rejected settlement file (`P8-TSK-022`, ADR-0066 §8): a new file
     * row naming its original, its bytes the original's — verified, screened under the
     * source's current format version, re-encrypted under the readmission's own id. Written
     * in the readmission's one transaction; requires a reason. The change summary names the
     * original, the readmission, the channel and the content address — never the content.
     */
    SETTLEMENT_FILE_READMITTED(
            "settlement.SettlementFileReadmitted",
            "An operator readmitted a rejected settlement file as a new file naming its"
                    + " original, with the recorded reason; the record names both files and the"
                    + " content address, never the content.",
            true),

    /**
     * A person re-parsed a stored file under its RECORDED format version and compared the
     * result with its stored lines (`P8-TSK-022`, ADR-0066 §8): the bytes are decrypted, so
     * the access is audited per verification with a reason, like a content read. It never
     * writes a line. The change summary names the file, the verdict and the lines compared.
     */
    SETTLEMENT_FILE_VERIFIED(
            "settlement.SettlementFileVerified",
            "An operator verified a stored settlement file by re-parsing it under its recorded"
                    + " format version, with the recorded reason; the record names the verdict,"
                    + " never a value from the file.",
            true),

    /**
     * An accepted batch moved {@code ACCEPTED → REPUDIATED} by an approved
     * {@code REPUDIATE_BATCH} resolution (`P8-TSK-023`, ADR-0065 §10): written in the
     * approval's transaction, after its postings, the approver the actor. No reason at this
     * rank - the reasoned, four-eyes act is the reconciliation resolution, which this record
     * names. The change summary carries identifiers only: the source, the file, the resolution
     * and the reversal entry when one was posted - never an amount.
     */
    SETTLEMENT_BATCH_REPUDIATED(
            "settlement.SettlementBatchRepudiated",
            "An approved REPUDIATE_BATCH resolution repudiated an accepted settlement batch;"
                    + " the record names the resolution and the reversal entry - identifiers"
                    + " only.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
