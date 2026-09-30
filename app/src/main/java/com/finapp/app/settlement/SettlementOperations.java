package com.finapp.app.settlement;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.EvidenceContentReads;
import com.finapp.settlement.FileAttestation;
import com.finapp.settlement.FileDecline;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.FileStatus;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementErrorCode;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.correlation.Correlation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's settlement surfaces (`P8-TSK-003`, ADR-0066 §1, §2, §7): the upload door,
 * the second person's attestation, the metadata reads, and the ONE content path — reasoned,
 * audited, checksum-verified ({@code INV-REC-10}).
 *
 * <h2>The upload is one transaction under a per-principal key</h2>
 *
 * <p>Claim, reception (row, chunks, receipt, birth event, audit) and the stored outcome
 * commit together ({@code IdempotentExecutor.execute}), so a crash leaves either everything
 * or nothing, and a lost response replays the recorded outcome byte for byte. The key's scope
 * is {@code settlement.upload:<actorType>:<actorId>} — per principal from birth (the
 * `X-TSK-003` disposition), so two operators' identical keys never collide, while the same
 * bytes from a second principal converge on the content address and are answered with
 * {@code duplicateOf}. <strong>A refusal is a recorded FAILED outcome, not an
 * exception</strong>: its metadata row and audit record must commit, and a retry must replay
 * the refusal rather than re-run the screen (ADR-0066 §4).
 */
@RequiredArgsConstructor
public class SettlementOperations {

    /** The listings' bound: the newest this many, and {@code truncated} says when more exist. */
    static final int LISTING_BOUND = 100;

    private static final String UPLOAD_SCOPE_PREFIX = "settlement.upload:";

    @NonNull private final SettlementSources sources;
    @NonNull private final FileReception<Connection> reception;
    @NonNull private final FileAttestation<Connection> attestation;
    @NonNull private final EvidenceContentReads<Connection> contentReads;
    @NonNull private final SettlementFileStore<Connection> store;
    @NonNull private final FileDecline fileDecline;
    @NonNull private final SettlementBatchStore<Connection> batches;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate settlementTransactions;
    @NonNull private final DataSource dataSource;

    // ----------------------------------------------------------------- the upload

    /** {@code POST /files}: introduce evidence — 202, asynchronous; status read by GET. */
    public UploadAnswer upload(String idempotencyKey, SettlementFileUploadRequest request) {
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        byte[] content = decode(request.content());
        IdempotencyKey key =
                new IdempotencyKey(
                        UPLOAD_SCOPE_PREFIX + actor.type().name() + ":" + actor.id(),
                        idempotencyKey);
        // The whole material request: a reused key with any other source, date or content is
        // a materially different command and must conflict, never replay (INV-IDEM-03).
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        (request.sourceCode()
                                        + "|" + request.businessDate()
                                        + "|" + HexFormat.of().formatHex(sha256(content)))
                                .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome =
                    inOneTransaction(
                            unitOfWork ->
                                    executor.execute(
                                            unitOfWork,
                                            key,
                                            fingerprint,
                                            uow -> received(uow, request, content, actor,
                                                    correlation)));
        } catch (FileReception.SettlementSourceUnknown unknown) {
            // Before anything was written, so the claim rolls back with the refusal: a
            // corrected retry under the same key is a fresh command.
            throw new ApiException(
                    SettlementErrorCode.SOURCE_UNKNOWN,
                    "A settlement upload named a source this build does not declare",
                    "no declared settlement source has this code.");
        } catch (FileReception.SettlementSourceRetired retired) {
            throw new ApiException(
                    SettlementErrorCode.SOURCE_RETIRED,
                    "A settlement upload addressed a retired source",
                    "this settlement source is retired and accepts no deliveries.");
        }
        byte[] body =
                outcome.body()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a recorded upload outcome always carries its"
                                                        + " body"));
        if (outcome.state() == IdempotencyState.FAILED) {
            throw refusalFrom(body);
        }
        return parseUpload(body);
    }

    /** The command inside the claim: the door's verdict, rendered as the stored outcome. */
    private CommandResult received(
            Connection unitOfWork,
            SettlementFileUploadRequest request,
            byte[] content,
            Actor actor,
            Correlation correlation) {
        FileReception.Result result =
                reception.receive(
                        unitOfWork,
                        new FileReception.Delivery(
                                request.sourceCode(),
                                DeliveryChannel.UPLOAD,
                                content,
                                Optional.of(request.businessDate()),
                                actor,
                                SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                                correlation));
        return switch (result) {
            case FileReception.Result.New landed ->
                    CommandResult.succeeded(
                            renderUpload(landed.fileId(), FileStatus.RECEIVED.name(), null));
            case FileReception.Result.Duplicate standing -> {
                // The standing file's CURRENT status: the honest answer for a re-delivery of
                // bytes another channel already landed.
                String status =
                        store.fileById(unitOfWork, standing.existingFileId())
                                .map(file -> file.status().name())
                                .orElse(FileStatus.RECEIVED.name());
                yield CommandResult.succeeded(
                        renderUpload(
                                standing.existingFileId(), status, standing.existingFileId()));
            }
            case FileReception.Result.Refused refused ->
                    // The refusal's rows are already written in THIS transaction; a FAILED
                    // outcome commits them and replays the same refusal to a retry.
                    CommandResult.failed(
                            StoredResponse.of(
                                    (refused.reason().name()
                                                    + "|"
                                                    + refused.lineNo()
                                                            .map(String::valueOf)
                                                            .orElse("")
                                                    + "|"
                                                    + refused.fieldName().orElse(""))
                                            .getBytes(StandardCharsets.UTF_8),
                                    "text/plain"));
        };
    }

    /**
     * The stored form of the original 202: three pipe-joined fields (the {@code
     * AccountService} shape) — internal bytes, rendered to the typed record by the web layer.
     */
    private static StoredResponse renderUpload(UUID fileId, String status, UUID duplicateOf) {
        String joined =
                fileId + "|" + status + "|" + (duplicateOf == null ? "" : duplicateOf);
        return StoredResponse.of(joined.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static UploadAnswer parseUpload(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", 3);
        if (fields.length != 3) {
            throw new IllegalStateException(
                    "a stored upload body always carries the answer's three fields");
        }
        return new UploadAnswer(
                fields[0], fields[1], fields[2].isEmpty() ? null : fields[2]);
    }

    /** The recorded refusal, replayed as the same refusal — the position, never the value. */
    private static ApiException refusalFrom(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", 3);
        if (fields.length != 3) {
            throw new IllegalStateException(
                    "a stored refusal body always carries reason, line and field");
        }
        return switch (fields[0]) {
            case "FILE_TOO_LARGE", "TOO_MANY_LINES" ->
                    new ApiException(
                            SettlementErrorCode.FILE_TOO_LARGE,
                            "A settlement upload exceeded the file bounds",
                            "decoded content is bounded at 8388608 bytes and 50000 records.");
            default ->
                    new ApiException(
                            SettlementErrorCode.DELIVERY_REFUSED,
                            "A settlement upload was refused by the door screen",
                            "the screen found " + fields[0].toLowerCase(java.util.Locale.ROOT)
                                    + (fields[1].isEmpty() ? "" : " at line " + fields[1])
                                    + (fields[2].isEmpty() ? "" : " in field " + fields[2])
                                    + "; only metadata was recorded.");
        };
    }

    // ----------------------------------------------------------------- the attestation

    /** {@code POST /files/'{id}'/attestation}: the second person's act (`INV-SET-07`). */
    public AttestationView attest(String rawFileId) {
        UUID fileId = parsedIdOrNotFound(rawFileId);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        FileAttestation.Result result;
        try {
            result =
                    inOneTransaction(
                            unitOfWork ->
                                    attestation.attest(unitOfWork, fileId, actor, correlation));
        } catch (FileAttestation.SettlementFileNotFound unknown) {
            throw fileNotFound();
        } catch (FileAttestation.AttestationBySubmitter submitter) {
            throw new ApiException(
                    SettlementErrorCode.ATTESTATION_BY_SUBMITTER,
                    "An uploader tried to attest their own settlement file (INV-SET-07)",
                    "the uploader cannot attest their own file; a second person must.");
        } catch (FileAttestation.SettlementFileNotAttestable notAttestable) {
            throw new ApiException(
                    SettlementErrorCode.FILE_NOT_ATTESTABLE,
                    "A settlement file refused an attestation: " + notAttestable.getMessage(),
                    "this file cannot be attested: it is not an upload awaiting a second"
                            + " person, or another person's attestation already stands.");
        }
        SettlementFileStore.Attestation attested =
                result.file()
                        .attestation()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an attestation result always carries the"
                                                        + " standing attestation"));
        return new AttestationView(
                result.file().id().toString(),
                attested.attestedBy(),
                attested.attestedAt().toString());
    }

    // ----------------------------------------------------------------- the decline

    /**
     * {@code POST /files/'{id}'/decline} (`P8-TSK-008`): a person's reasoned refusal —
     * {@code RECEIVED | PARSED → REJECTED(DECLINED)}, a parsed file's batch with it, the
     * live key freed at commit. Idempotent by state: a terminal file answers {@code 409}.
     */
    public DeclineView decline(String rawFileId, SettlementFileDeclineRequest request) {
        UUID fileId = parsedIdOrNotFound(rawFileId);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        FileDecline.Declined declined;
        try {
            declined =
                    inOneTransaction(
                            unitOfWork ->
                                    fileDecline.decline(
                                            unitOfWork, fileId, request.reason(), actor,
                                            correlation));
        } catch (FileAttestation.SettlementFileNotFound unknown) {
            throw fileNotFound();
        } catch (FileAttestation.SettlementFileNotAttestable terminal) {
            throw new ApiException(
                    SettlementErrorCode.FILE_NOT_ATTESTABLE,
                    "A settlement file refused a decline: " + terminal.getMessage(),
                    "this file is terminal; its verdict already stands on the record.");
        }
        return new DeclineView(
                declined.file().id().toString(),
                declined.file().status().name(),
                RejectionCode.DECLINED.name());
    }

    // ----------------------------------------------------------------- the batch read

    /**
     * {@code GET /batches/'{id}'} (`P8-TSK-008`): the parsed totals an attester reads before
     * attesting — identity, the trailer's declared words and the folded totals; unknown and
     * malformed ids are one {@code 404} recording nothing.
     */
    public BatchView viewBatch(String rawBatchId) {
        UUID batchId;
        try {
            batchId = UUID.fromString(rawBatchId);
        } catch (IllegalArgumentException malformed) {
            throw batchNotFound();
        }
        return inOneTransaction(
                unitOfWork ->
                        batches.batchById(unitOfWork, batchId)
                                .map(
                                        batch ->
                                                batchView(
                                                        batch,
                                                        batches.totalsOf(
                                                                unitOfWork, batch.id()))))
                .orElseThrow(SettlementOperations::batchNotFound);
    }

    // ----------------------------------------------------------------- the reads

    /** {@code GET /sources}: the compiled register joined to each source's seeded state. */
    public SourceList listSources() {
        Map<String, SettlementFileStore.SourceRow> rows =
                inOneTransaction(store::sources).stream()
                        .collect(
                                Collectors.toMap(
                                        SettlementFileStore.SourceRow::code,
                                        Function.identity()));
        List<SourceView> views =
                sources.declared().stream()
                        .map(declared -> sourceView(declared, rows))
                        .toList();
        return new SourceList(views);
    }

    private static SourceView sourceView(
            SettlementSourceDescriptor declared,
            Map<String, SettlementFileStore.SourceRow> rows) {
        SettlementFileStore.SourceRow row = rows.get(declared.code());
        if (row == null) {
            throw new IllegalStateException(
                    "source '" + declared.code() + "' is declared but not seeded: V002 seeds"
                            + " every declared source");
        }
        return new SourceView(
                declared.code(),
                declared.kind().name(),
                declared.format().name(),
                declared.formatVersion(),
                declared.channels().stream().map(Enum::name).sorted().toList(),
                row.active());
    }

    /** {@code GET /files}: the newest 100, `truncated` when more exist — metadata only. */
    public FileList listFiles() {
        List<SettlementFileStore.FileRow> found =
                inOneTransaction(unitOfWork -> store.newestFiles(unitOfWork, LISTING_BOUND + 1));
        boolean truncated = found.size() > LISTING_BOUND;
        return new FileList(
                found.stream().limit(LISTING_BOUND).map(SettlementOperations::fileView).toList(),
                truncated);
    }

    /** {@code GET /files/'{id}'}: one file — unknown and malformed are one 404. */
    public FileView viewFile(String rawFileId) {
        UUID fileId = parsedIdOrNotFound(rawFileId);
        return inOneTransaction(unitOfWork -> store.fileById(unitOfWork, fileId))
                .map(SettlementOperations::fileView)
                .orElseThrow(SettlementOperations::fileNotFound);
    }

    /** {@code GET /refused-deliveries}: the newest 100 metadata rows — never a value. */
    public RefusalList listRefusedDeliveries() {
        List<SettlementFileStore.RefusalRow> found =
                inOneTransaction(
                        unitOfWork -> store.newestRefusals(unitOfWork, LISTING_BOUND + 1));
        boolean truncated = found.size() > LISTING_BOUND;
        return new RefusalList(
                found.stream()
                        .limit(LISTING_BOUND)
                        .map(SettlementOperations::refusalView)
                        .toList(),
                truncated);
    }

    /**
     * {@code POST /files/'{id}'/content-reads}: the one content path (`INV-REC-10`) — the
     * audit record commits with the read, before a byte is rendered; a verification failure
     * commits its {@code FAILED} record and serves nothing.
     */
    public ContentView readContent(String rawFileId, SettlementContentReadRequest request) {
        UUID fileId = parsedIdOrNotFound(rawFileId);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        EvidenceContentReads.Outcome outcome =
                inOneTransaction(
                        unitOfWork ->
                                contentReads.read(
                                        unitOfWork, fileId, actor, request.reason(),
                                        correlation));
        return switch (outcome) {
            case EvidenceContentReads.Outcome.Unknown unknown -> throw fileNotFound();
            case EvidenceContentReads.Outcome.Corrupt corrupt ->
                    // The FAILED audit record committed with the transaction above; the
                    // corruption is reported only after it is on the record.
                    throw new ApiException(
                            PlatformErrorCode.INTERNAL_ERROR,
                            "A settlement file failed verification on an audited content read"
                                    + " (INV-HIST-02)");
            case EvidenceContentReads.Outcome.Served served ->
                    new ContentView(
                            served.file().id().toString(),
                            HexFormat.of().formatHex(served.file().contentSha256()),
                            served.file().contentLength(),
                            Base64.getEncoder().encodeToString(served.content()));
        };
    }

    // ----------------------------------------------------------------- views

    /** The 202 body: the standing file, and {@code duplicateOf} exactly on a convergence. */
    public record UploadAnswer(String fileId, String status, String duplicateOf) {}

    public record AttestationView(String fileId, String attestedBy, String attestedAt) {}

    public record SourceList(List<SourceView> sources) {}

    public record SourceView(
            String code,
            String kind,
            String formatId,
            int formatVersion,
            List<String> channels,
            boolean active) {}

    public record FileList(List<FileView> files, boolean truncated) {}

    public record FileView(
            String fileId,
            String sourceCode,
            String receivedVia,
            String status,
            String businessDate,
            String formatId,
            int formatVersion,
            String contentSha256,
            int contentLength,
            int lineCount,
            String receivedBy,
            String attestedBy,
            String attestedAt,
            String receivedAt) {}

    public record RefusalList(List<RefusalView> refusedDeliveries, boolean truncated) {}

    public record RefusalView(
            String refusalId,
            String sourceCode,
            String contentSha256,
            int contentLength,
            String formatId,
            int formatVersion,
            String reason,
            Integer lineNo,
            String fieldName,
            String channel,
            String deliveredBy,
            String refusedAt) {}

    public record ContentView(
            String fileId, String contentSha256, int contentLength, String content) {}

    public record DeclineView(String fileId, String status, String rejectionCode) {}

    public record BatchView(
            String batchId,
            String fileId,
            String sourceCode,
            String externalBatchRef,
            String currency,
            String status,
            String businessDate,
            String formatId,
            int formatVersion,
            int lineCount,
            int declaredLineCount,
            String declaredNet,
            String remittanceReference,
            List<BatchTotalView> totals,
            String createdAt,
            Long statementSequence) {}

    /** One (type, direction) fold: exact decimal strings, the ADR-0015 amount shape. */
    public record BatchTotalView(
            String lineType, String direction, long lineCount, String amount) {}

    private static BatchView batchView(
            SettlementBatchStore.BatchRow batch, List<SettlementBatchStore.TotalRow> totals) {
        return new BatchView(
                batch.id().toString(),
                batch.fileId().toString(),
                batch.sourceCode(),
                batch.externalBatchRef(),
                batch.currency().code(),
                batch.status().name(),
                batch.businessDate().toString(),
                batch.formatId().name(),
                batch.formatVersion(),
                batch.lineCount(),
                batch.declaredLineCount(),
                java.math.BigDecimal.valueOf(batch.netMinor(), batch.netScale())
                        .toPlainString(),
                // A report's promise to pay; a bank statement has none of its own (P8-TSK-016).
                batch.remittanceReference().orElse(null),
                totals.stream()
                        .map(
                                total ->
                                        new BatchTotalView(
                                                total.lineType().name(),
                                                total.direction().name(),
                                                total.lineCount(),
                                                java.math.BigDecimal.valueOf(
                                                                total.amountMinor(),
                                                                total.amountScale())
                                                        .toPlainString()))
                        .toList(),
                batch.createdAt().toString(),
                // A statement's place in its account's chain; null for a report (P8-TSK-016).
                batch.statement().map(SettlementBatchStore.StatementRow::sequence).orElse(null));
    }

    private static ApiException batchNotFound() {
        return new ApiException(
                SettlementErrorCode.BATCH_NOT_FOUND,
                "No settlement batch matches the requested identifier",
                "no such settlement batch.");
    }

    private static FileView fileView(SettlementFileStore.FileRow row) {
        return new FileView(
                row.id().toString(),
                row.sourceCode(),
                row.receivedVia().name(),
                row.status().name(),
                row.businessDate().map(Object::toString).orElse(null),
                row.formatId().name(),
                row.formatVersion(),
                HexFormat.of().formatHex(row.contentSha256()),
                row.contentLength(),
                row.lineCount(),
                row.receivedBy().orElse(null),
                row.attestation().map(SettlementFileStore.Attestation::attestedBy).orElse(null),
                row.attestation()
                        .map(attested -> attested.attestedAt().toString())
                        .orElse(null),
                row.receivedAt().toString());
    }

    private static RefusalView refusalView(SettlementFileStore.RefusalRow row) {
        return new RefusalView(
                row.id().toString(),
                row.sourceCode(),
                HexFormat.of().formatHex(row.contentSha256()),
                row.contentLength(),
                row.formatId().name(),
                row.formatVersion(),
                row.reason().name(),
                row.lineNo().orElse(null),
                row.fieldName().orElse(null),
                row.channel().name(),
                row.actor(),
                row.refusedAt().toString());
    }

    // ----------------------------------------------------------------- plumbing

    /** Decodes the envelope; not-base64 is a {@code 422} naming the field, nothing written. */
    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notBase64) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A settlement upload carried content that is not valid base64",
                    "content must be base64-encoded");
        }
    }

    /** Unknown and malformed identifiers are ONE answer, and neither records anything. */
    private static UUID parsedIdOrNotFound(String rawFileId) {
        try {
            return UUID.fromString(rawFileId);
        } catch (IllegalArgumentException malformed) {
            throw fileNotFound();
        }
    }

    private static ApiException fileNotFound() {
        return new ApiException(
                SettlementErrorCode.FILE_NOT_FOUND,
                "No settlement file matches the requested identifier",
                "no such settlement file.");
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a settlement command must run inside a correlation"
                                                + " scope"));
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
        }
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return settlementTransactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
