package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Readmission (`P8-TSK-022`, ADR-0066 §8, `INV-SET-07`): a person's reasoned recovery of a
 * rejected settlement file — a NEW file row naming its original, born {@code RECEIVED}, whose
 * bytes are the original's, parsed by the ordinary parse leg under the source's current format
 * version and accepted by the ordinary accept leg when its channel is satisfied. The original
 * is never touched: it stays {@code REJECTED}, its verdict frozen (`V003`).
 *
 * <h2>Which originals are readmissible</h2>
 *
 * <ul>
 *   <li><strong>Our validation's verdicts</strong> — {@code MALFORMED},
 *       {@code CONTROL_TOTAL_MISMATCH}, {@code UNKNOWN_CURRENCY}, {@code SCALE_MISMATCH},
 *       {@code UNSUPPORTED_FORMAT}: the parse leg was the defect.
 *   <li><strong>{@code DECLINED}</strong> — decided at design: a mistaken decline is otherwise a
 *       dead end, because a byte-identical re-issue meets the declined file's content address.
 *       Such a readmission inherits NOTHING and must be attested.
 *   <li><strong>{@code CONFLICTING_BATCH}</strong> — once the conflict no longer stands: the
 *       original's bytes, re-parsed in memory under the source's current format, declare an
 *       identity no live batch holds (the standing batch declined or repudiated). While one
 *       does, {@link ConflictingBatchStands}. The parse leg's live unique stays the arbiter.
 *   <li><strong>An {@code ACCEPTED} file whose batch is {@code REPUDIATED}</strong> (the Phase
 *       8 -> 9 transition, MI-2; ADR-0065 §10): the evidence's verdict withdrawn by an approved
 *       four-eyes repudiation - for our own adapter's mis-normalisation, the genuine evidence IS
 *       these bytes, and readmission is the only way the same bytes are parsed again. Judged like
 *       a {@code CONFLICTING_BATCH} original: only while no live batch holds the identity the
 *       bytes declare under the current format ({@link ConflictingBatchStands} otherwise). It
 *       inherits NOTHING - exactly the {@code DECLINED} rule: the repudiation's four-eyes
 *       verdict stands against these bytes' effect, so reinstating them waits for its own
 *       second person (the Phase 8 -> 9 transition's re-gate, NEW-SEC-1; `V014`).
 *   <li>Never {@code SOURCE_RETIRED}, never a file still in its machine, never an accepted file
 *       whose batch stands: {@link FileNotRejected}. `V011` holds the same rule for every
 *       writer.
 * </ul>
 *
 * <h2>What authenticates the readmission</h2>
 *
 * <p>It inherits its original's authentication — through the identical checksum — when the
 * original was pulled or attested (or is itself a readmission that inherited), and a
 * {@code DECLINED} original — or one whose batch is {@code REPUDIATED} (the Phase 8 -> 9
 * transition's re-gate, NEW-SEC-1) — passes nothing on. Otherwise it waits for a second person
 * distinct from every submitter along its chain ({@link FileAttestation}). `V009`'s functions
 * (the walk as `V014` re-states it) are the one source of truth for the attestation's domain
 * rank, the accept leg's claim and the trigger beneath every writer.
 *
 * <h2>The door's steps, in the door's order</h2>
 *
 * <p>The bytes are decrypted and verified against the checksum, screened by the door's own
 * screen for the source's CURRENT format version, and re-encrypted under the readmission's own
 * id. A screen finding is a {@link Refused} RESULT, never an exception — like the door's, it
 * commits a {@code refused_delivery} metadata row (channel {@code READMISSION}) and its audit
 * record, and the original stands ({@code FileReception}'s refusal-is-a-result rule).
 *
 * <h2>Ten instances</h2>
 *
 * <p>The original's row lock ({@code FOR UPDATE}) serialises racing readmissions of one file:
 * each loser waits, then reads the winner's committed row and is refused
 * {@link FileAlreadyReadmitted}. {@code UNIQUE (readmits_file_id)} is the arbiter even without
 * the lock — its violation answers the same refusal. Nothing here reads process state.
 */
@RequiredArgsConstructor
public final class FileReadmission {

    /** The bound `platform.audit_record.reason` and `settlement.file_event.reason` share. */
    public static final int MAX_REASON_LENGTH = 1000;

    /** The audit's verdict for an accepted original whose batch was repudiated (MI-2). */
    static final String REPUDIATED_BATCH = "REPUDIATED_BATCH";

    /** Our validation's verdicts — the parse leg was the defect (ADR-0066 §8). */
    private static final Set<RejectionCode> OUR_VALIDATION =
            EnumSet.of(
                    RejectionCode.MALFORMED,
                    RejectionCode.CONTROL_TOTAL_MISMATCH,
                    RejectionCode.UNKNOWN_CURRENCY,
                    RejectionCode.SCALE_MISMATCH,
                    RejectionCode.UNSUPPORTED_FORMAT);

    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<Connection> files;
    @NonNull private final SettlementBatchStore<Connection> batches;

    /** The compiled formats — the {@code CONFLICTING_BATCH} re-parse reads the current one. */
    @NonNull private final Map<SettlementFormatId, SettlementFormat> formats;

    /** The door's field-class screens; a format without one keeps the conservative screen. */
    @NonNull private final Map<SettlementFormatId, DeliveryScreen> formatScreens;

    @NonNull private final ReceptionOutcomeObserver observer;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;

    /** What one readmission came to — each variant has already written what it names. */
    public sealed interface Result {}

    /**
     * The readmission landed: row, re-encrypted chunks, {@code NEW} receipt, reasoned birth
     * event and audit record, in the caller's one transaction.
     *
     * @param inheritsAuthentication read through `V009` after the insert — when false, the
     *     readmission is inert until a second person attests it
     */
    public record Readmitted(UUID fileId, UUID readmitsFileId, boolean inheritsAuthentication)
            implements Result {

        public Readmitted {
            Objects.requireNonNull(fileId, "fileId must not be null");
            Objects.requireNonNull(readmitsFileId, "readmitsFileId must not be null");
        }
    }

    /**
     * The current screen refused the bytes: the named reason's rows committed, the value
     * nowhere, no readmission stored, the original standing. The position — present for a
     * screen finding — is what the caller may tell the client: the line and field, never the
     * value.
     */
    public record Refused(
            RefusalReason reason, Optional<Integer> lineNo, Optional<String> fieldName)
            implements Result {

        public Refused {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(lineNo, "lineNo must not be null");
            Objects.requireNonNull(fieldName, "fieldName must not be null");
        }
    }

    public Result readmit(
            Connection unitOfWork,
            UUID originalFileId,
            Actor actor,
            String reason,
            Instant now,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(originalFileId, "originalFileId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        requireReason(reason, "a readmission");

        // 1. The original, locked: racing readmissions of one file serialise here.
        SettlementFileStore.FileRow original =
                files.lockFileById(unitOfWork, originalFileId)
                        .orElseThrow(
                                () -> new FileAttestation.SettlementFileNotFound(originalFileId));
        String verdict = admissibleVerdict(unitOfWork, original);
        if (files.readmissionOf(unitOfWork, originalFileId).isPresent()) {
            throw new FileAlreadyReadmitted(originalFileId);
        }

        // 2. The source: still declared and ACTIVE - a retired source's door is shut for its
        // readmissions too, and the accept leg would only reject SOURCE_RETIRED.
        SettlementSourceDescriptor declared =
                sources.byCode(original.sourceCode())
                        .orElseThrow(
                                () ->
                                        new FileReception.SettlementSourceUnknown(
                                                original.sourceCode()));
        SettlementFileStore.SourceRow source =
                files.sourceByCode(unitOfWork, original.sourceCode())
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "source '" + original.sourceCode()
                                                        + "' holds a file but has no row"));
        if (!source.active()) {
            throw new FileReception.SettlementSourceRetired(original.sourceCode());
        }

        // 3. The bytes: the original's plaintext, verified against its checksum - or a loud
        // SettlementStorageException, serving nothing (INV-HIST-02).
        byte[] content = files.readContent(unitOfWork, originalFileId);
        byte[] contentSha256 = original.contentSha256();

        // 4. A CONFLICTING_BATCH original - or a repudiated batch's file - only once no live
        // batch holds the identity its bytes declare.
        if ((RejectionCode.CONFLICTING_BATCH.name().equals(verdict)
                        || REPUDIATED_BATCH.equals(verdict))
                && conflictStands(unitOfWork, original, declared, content)) {
            throw new ConflictingBatchStands(originalFileId);
        }

        // 5. The door's bound and screen, under the source's CURRENT format version. The size
        // bound held at the original's door and its CHECK; the record count is this screen's.
        DeliveryScreen screen =
                formatScreens.getOrDefault(declared.format(), ConservativeScreen.INSTANCE);
        DeliveryScreen.Screening screening = screen.screen(content);
        if (screening.lineCount() > SettlementFile.MAX_LINES) {
            auditRefusal(
                    unitOfWork,
                    actor,
                    now,
                    correlation,
                    original.sourceCode(),
                    "source=" + original.sourceCode()
                            + ", channel=" + DeliveryChannel.READMISSION
                            + ", reason=" + RefusalReason.TOO_MANY_LINES
                            + ", length=" + content.length);
            observer.refused(original.sourceCode(), RefusalReason.TOO_MANY_LINES);
            return new Refused(RefusalReason.TOO_MANY_LINES, Optional.empty(), Optional.empty());
        }
        if (screening.finding().isPresent()) {
            DeliveryScreen.Finding finding = screening.finding().get();
            files.recordRefusal(
                    unitOfWork,
                    new RefusedDelivery(
                            ids.next(),
                            source.id(),
                            contentSha256,
                            content.length,
                            declared.format(),
                            declared.formatVersion(),
                            finding.reason(),
                            Optional.of(finding.lineNo()),
                            finding.fieldName(),
                            DeliveryChannel.READMISSION,
                            actor,
                            now,
                            correlation.correlationId()));
            auditRefusal(
                    unitOfWork,
                    actor,
                    now,
                    correlation,
                    original.sourceCode(),
                    "source=" + original.sourceCode()
                            + ", channel=" + DeliveryChannel.READMISSION
                            + ", reason=" + finding.reason()
                            + ", sha256=" + HexFormat.of().formatHex(contentSha256)
                            + ", line=" + finding.lineNo()
                            + finding.fieldName().map(field -> ", field=" + field).orElse(""));
            observer.refused(original.sourceCode(), finding.reason());
            return new Refused(
                    finding.reason(), Optional.of(finding.lineNo()), finding.fieldName());
        }

        // 6. The new row, re-encrypted under its own id, with its receipt, birth and audit.
        UUID fileId = ids.next();
        try {
            files.insertReadmission(
                    unitOfWork,
                    new SettlementFile(
                            fileId,
                            source.id(),
                            DeliveryChannel.READMISSION,
                            original.businessDate(),
                            declared.format(),
                            declared.formatVersion(),
                            contentSha256,
                            content.length,
                            screening.lineCount(),
                            Optional.of(actor),
                            now,
                            correlation.correlationId(),
                            Optional.of(originalFileId)),
                    content);
        } catch (SettlementFileStore.ReadmissionConflict racer) {
            // A racer the lock missed: the unique is the arbiter, and the answer is the same.
            throw new FileAlreadyReadmitted(originalFileId);
        }
        files.appendReceipt(
                unitOfWork,
                ids.next(),
                fileId,
                SettlementFileStore.ReceiptOutcome.NEW,
                DeliveryChannel.READMISSION,
                actor,
                now,
                correlation.correlationId());
        files.appendBirthEvent(
                unitOfWork, fileId, actor, Optional.of(reason), now, correlation.correlationId());
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        SettlementAuditAction.SETTLEMENT_FILE_READMITTED,
                        "settlement_file",
                        fileId.toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and the address - never a value (INV-AUD-02).
                        Optional.of(
                                "source=" + original.sourceCode()
                                        + ", original=" + originalFileId
                                        + ", readmission=" + fileId
                                        + ", originalVerdict=" + verdict
                                        + ", channel=" + DeliveryChannel.READMISSION
                                        + ", sha256=" + HexFormat.of().formatHex(contentSha256))));
        observer.received(original.sourceCode(), SettlementFileStore.ReceiptOutcome.NEW);
        return new Readmitted(
                fileId, originalFileId, files.inheritsAuthentication(unitOfWork, fileId));
    }

    /**
     * The original's verdict, if readmissible - its rejection code, or {@link #REPUDIATED_BATCH}
     * for an accepted file whose batch was repudiated - otherwise the refusal, writing nothing.
     */
    private String admissibleVerdict(
            Connection unitOfWork, SettlementFileStore.FileRow original) {
        if (original.status() == FileStatus.ACCEPTED) {
            // A REPUDIATED batch is terminal (V010), so this unlocked read cannot go stale;
            // V011's trigger re-judges it at the insert for every writer.
            boolean repudiated =
                    batches.batchByFileId(unitOfWork, original.id())
                            .filter(batch -> batch.status() == BatchStatus.REPUDIATED)
                            .isPresent();
            if (repudiated) {
                return REPUDIATED_BATCH;
            }
            throw new FileNotRejected(
                    original.id(), "it is ACCEPTED and its batch stands, not a rejection");
        }
        if (original.status() != FileStatus.REJECTED || original.rejectionCode().isEmpty()) {
            throw new FileNotRejected(
                    original.id(), "it is " + original.status() + ", not a rejection");
        }
        RejectionCode code = original.rejectionCode().get();
        if (OUR_VALIDATION.contains(code)
                || code == RejectionCode.DECLINED
                || code == RejectionCode.CONFLICTING_BATCH) {
            return code.name();
        }
        // SOURCE_RETIRED: the source closed its door; a re-opened source is a NEW source.
        // CURRENCY_NOT_SETTLED: the counterparty settles no such currency; settling one is a new
        // declaration with its own seeded account, and the provider's next delivery.
        throw new FileNotRejected(original.id(), "a " + code + " rejection is never readmitted");
    }

    /**
     * Whether a live batch still holds the identity the bytes declare — the parse leg's own
     * pre-check (live batch by source, reference and currency; live statement sequence), run
     * in memory under the source's current format. Bytes that format rejects declare no
     * identity, so no conflict can stand: the parse leg gives its own verdict.
     */
    private boolean conflictStands(
            Connection unitOfWork,
            SettlementFileStore.FileRow original,
            SettlementSourceDescriptor declared,
            byte[] content) {
        SettlementFormat format = formats.get(declared.format());
        if (format == null || format.version() != declared.formatVersion()) {
            // OUR gap, never the evidence's: the conflict cannot be judged, so nothing is done.
            throw new IllegalStateException(
                    "no compiled format parses " + declared.format() + " v"
                            + declared.formatVersion() + ": the conflict cannot be judged");
        }
        if (!(format.parse(content) instanceof SettlementFormat.Result.Parsed parsed)) {
            return false;
        }
        ParsedBatch batch = parsed.batch();
        CurrencyCode currency = batch.declaredNet().currency();
        if (batches.liveBatchStands(
                unitOfWork, original.sourceId(), batch.externalBatchRef(), currency)) {
            return true;
        }
        return batch.statement().isPresent()
                && batches.liveStatementStands(
                        unitOfWork, original.sourceId(), currency,
                        batch.statement().get().sequence());
    }

    private void auditRefusal(
            Connection unitOfWork,
            Actor actor,
            Instant now,
            Correlation correlation,
            String sourceCode,
            String summary) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        SettlementAuditAction.SETTLEMENT_DELIVERY_REFUSED,
                        "settlement_source",
                        sourceCode,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(summary)));
    }

    /**
     * The reason rule every person-written settlement reason shares — the readmission, the
     * verification, the decline and the content read: present, non-blank, within the bound, and
     * never a card-number or account-identifier shape ({@code INV-PAY-02}, {@code INV-RAIL-03}):
     * the reason reaches the file's history and the audit record, neither of which can be
     * cleaned. The {@code file_event} and {@code batch_event} {@code CHECK}s are its twin for
     * every other writer (`V012`).
     *
     * <p><em>(Corrected 2026-10-02 by the Phase 8 → 9 transition: blank and length were the
     * whole rule, and the decline and the content read did not share it (the audit's
     * {@code SEC-04}).)</em>
     */
    static void requireReason(String reason, String act) {
        if (reason == null || reason.isBlank()) {
            throw new ReasonRequired(act + " requires a reason (INV-AUD-03)");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ReasonRequired(
                    act + "'s reason is at most " + MAX_REASON_LENGTH + " characters");
        }
        if (com.finapp.sharedkernel.security.InstrumentShapes.holdsAny(reason)) {
            throw new ReasonRequired(
                    act + "'s reason must not hold a card-number or bank-account shape"
                            + " (INV-PAY-02, INV-RAIL-03)");
        }
    }

    /**
     * The act was unreasoned, its reason over the bound or holding an instrument shape — nothing
     * was read or written, and the message names the rule, never the value. An
     * {@link IllegalArgumentException}: the caller's request, not the file, is at fault.
     */
    public static final class ReasonRequired extends IllegalArgumentException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRequired(String why) {
            super(why);
        }
    }

    /** The file is not a readmissible rejection (settlement.FileNotRejected) — nothing written. */
    public static final class FileNotRejected extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        FileNotRejected(UUID fileId, String why) {
            super(
                    "settlement file " + fileId + " is not readmissible: " + why
                            + " (settlement.FileNotRejected)");
        }
    }

    /**
     * The original was rejected {@code CONFLICTING_BATCH} and a live batch still holds its
     * identity (settlement.ConflictingBatchStands) — nothing written.
     */
    public static final class ConflictingBatchStands extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ConflictingBatchStands(UUID fileId) {
            super(
                    "a live settlement batch still holds the identity settlement file " + fileId
                            + " declares (settlement.ConflictingBatchStands)");
        }
    }

    /**
     * The original already has a readmission (settlement.FileAlreadyReadmitted) — a file is
     * readmitted once; the caller's transaction is to be rolled back.
     */
    public static final class FileAlreadyReadmitted extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        FileAlreadyReadmitted(UUID fileId) {
            super(
                    "settlement file " + fileId
                            + " is already readmitted (settlement.FileAlreadyReadmitted)");
        }
    }
}
