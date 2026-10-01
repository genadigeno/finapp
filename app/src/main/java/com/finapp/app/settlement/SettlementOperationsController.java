package com.finapp.app.settlement;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * `/v1/operator/settlement` (`P8-TSK-003`, ADR-0066): the upload door, the second person's
 * attestation and the investigator's reads — the phase's first routes, opened only now that
 * the door behind them is proven (`P8-TSK-002`).
 *
 * <p><strong>Two permissions, one desk</strong>: introducing and attesting evidence is
 * {@link PermissionName#SETTLEMENT_INGEST}; every read — and the one content path, reasoned
 * and audited per read ({@code INV-REC-10}) — is
 * {@link PermissionName#RECONCILIATION_INVESTIGATE}. The second-person control is inside the
 * ingest permission, by actor distinctness at two ranks ({@code INV-SET-07}), which is why an
 * uploader holding the permission is still refused on their own file.
 *
 * <p>Handler names are deliberately distinctive (the springdoc {@code operationId} rule,
 * {@code OpenApiContractTest}).
 */
@RestController
@RequestMapping(path = "/operator/settlement", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SettlementOperationsController {

    @NonNull private final SettlementOperations settlement;
    @NonNull private final SettlementFetch fetch;

    /**
     * Introduces evidence. A {@code 202}, deliberately: reception is synchronous but the
     * file's judgement is not — parse and acceptance arrive with later legs, and the honest
     * contract is "received, status readable by GET". The same key replays the same answer;
     * the same bytes from anybody converge on {@code duplicateOf}.
     */
    @PostMapping(path = "/files", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.SETTLEMENT_INGEST)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SettlementOperations.UploadAnswer uploadSettlementFile(
            @Valid @RequestBody SettlementFileUploadRequest body,
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey) {
        return settlement.upload(idempotencyKey, body);
    }

    /**
     * Pulls a source's report now (`P8-TSK-021`, ADR-0066 §1): the schedule's own pull over the
     * source's confined credential, its permit renewed rather than windowed. A {@code 200} with
     * what it came to — received, a duplicate of a standing file, refused at the door, not yet
     * published, failed, or not pullable — because the request was served either way. No
     * idempotency key: the content address makes a repeated fetch one file.
     */
    @PostMapping(path = "/sources/{code}/fetch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.SETTLEMENT_INGEST)
    public SettlementFetch.FetchAnswer fetchSettlementReport(
            @PathVariable String code, @Valid @RequestBody SettlementFetchRequest body) {
        return fetch.fetch(code, body);
    }

    /**
     * Attests an upload — the second person's act (`INV-SET-07`). No idempotency key: the
     * {@code NULL → value} fact is the idempotency ({@code INV-IDEM-01} through state), the
     * same attester converges, and anybody else is refused.
     */
    @PostMapping("/files/{id}/attestation")
    @RequiresPermission(PermissionName.SETTLEMENT_INGEST)
    public SettlementOperations.AttestationView attestSettlementFile(
            @PathVariable("id") String fileId) {
        return settlement.attest(fileId);
    }

    /**
     * Declines a file — a person's reasoned judgement (`P8-TSK-008`, moved from `-003`). No
     * idempotency key: the machine is the record, a terminal file answers {@code 409}, and
     * each door refuses before it converges (the `P8-TSK-006` rule).
     */
    @PostMapping(path = "/files/{id}/decline", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.SETTLEMENT_INGEST)
    public SettlementOperations.DeclineView declineSettlementFile(
            @PathVariable("id") String fileId,
            @Valid @RequestBody SettlementFileDeclineRequest body) {
        return settlement.decline(fileId, body);
    }

    /**
     * One batch: identity, the trailer's declared words and the folded totals — what an
     * attester reads before attesting a {@code PARSED} upload (`P8-TSK-008`).
     */
    @GetMapping("/batches/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public SettlementOperations.BatchView viewSettlementBatch(
            @PathVariable("id") String batchId) {
        return settlement.viewBatch(batchId);
    }

    /** The declared sources with their seeded operational state — compiled data plus one flag. */
    @GetMapping("/sources")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public SettlementOperations.SourceList listSettlementSources() {
        return settlement.listSources();
    }

    /** The newest files, bounded at 100 with {@code truncated} — metadata, never content. */
    @GetMapping("/files")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public SettlementOperations.FileList listSettlementFiles() {
        return settlement.listFiles();
    }

    /** One file's metadata; unknown and malformed ids are one {@code 404}. */
    @GetMapping("/files/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public SettlementOperations.FileView viewSettlementFile(@PathVariable("id") String fileId) {
        return settlement.viewFile(fileId);
    }

    /** The newest refused deliveries — the reason and position, never the value. */
    @GetMapping("/refused-deliveries")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public SettlementOperations.RefusalList listRefusedSettlementDeliveries() {
        return settlement.listRefusedDeliveries();
    }

    /**
     * Readmits a file our own validation rejected (or a declined one, or a conflicting batch's
     * whose conflict is gone) - a controller's keyed, reasoned recovery (`P8-TSK-022`,
     * ADR-0066 §8). It inherits a pulled or attested original's authentication; otherwise it
     * awaits its own attestation by a person distinct from every earlier submitter.
     */
    @PostMapping(
            path = "/files/{id}/readmission",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_ADMINISTER)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SettlementOperations.ReadmissionAnswer readmitSettlementFile(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("id") String fileId,
            @Valid @RequestBody SettlementReadmissionRequest body) {
        return settlement.readmit(idempotencyKey, fileId, body);
    }

    /**
     * Re-verifies a stored file's normalisation under its recorded format version (`P8-TSK-022`,
     * ADR-0066 §9): reasoned and audited per access, it reads the content and replaces nothing.
     */
    @PostMapping(
            path = "/files/{id}/verification",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public SettlementOperations.VerificationAnswer verifySettlementFile(
            @PathVariable("id") String fileId,
            @Valid @RequestBody SettlementVerificationRequest body) {
        return settlement.verify(fileId, body);
    }

    /**
     * The ONE content path (`INV-REC-10`): reasoned, one audit record per read committed
     * before a byte is served, the checksum verified — a failed verification serves nothing
     * and is on the record as a failed read.
     */
    @PostMapping(
            path = "/files/{id}/content-reads",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public SettlementOperations.ContentView readSettlementFileContent(
            @PathVariable("id") String fileId,
            @Valid @RequestBody SettlementContentReadRequest body) {
        return settlement.readContent(fileId, body);
    }
}
