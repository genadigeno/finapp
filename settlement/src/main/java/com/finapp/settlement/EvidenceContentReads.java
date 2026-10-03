package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one path to a settlement file's raw bytes (`P8-TSK-003`, ADR-0066 §7, `INV-REC-10`):
 * reasoned, audited per read, checksum-verified before a byte is served.
 *
 * <h2>Every outcome is a RESULT, because every outcome must commit</h2>
 *
 * <p>A served read commits its audit record in the read's own transaction, before the caller
 * renders a byte. A read that fails verification — tamper, transplant, truncation — commits
 * the SAME record with outcome {@code FAILED}: an exception here would roll the evidence of
 * the failed access back, which is precisely the record an investigation needs
 * ({@code FileReception}'s refusal-is-a-result rule, applied to reads). Only a guessed
 * identifier is {@link Outcome.Unknown} — there is no file to audit an access against, so
 * nothing is written and the surface stays no oracle.
 */
@RequiredArgsConstructor
public final class EvidenceContentReads<T> {

    @NonNull private final SettlementFileStore<T> store;
    @NonNull private final AuditWriter<T> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one reasoned access came to — each variant has already written what it names. */
    public sealed interface Outcome {
        /** Verified and served; the audit record commits with this transaction. */
        record Served(SettlementFileStore.FileRow file, byte[] content) implements Outcome {
            public Served {
                content = content.clone();
            }

            @Override
            public byte[] content() {
                return content.clone();
            }
        }

        /**
         * The stored evidence failed verification: nothing served, the {@code FAILED} audit
         * record written — the caller commits it and then reports the corruption.
         */
        record Corrupt(SettlementFileStore.FileRow file) implements Outcome {}

        /** No such file: nothing written, nothing audited — a guessed id records nothing. */
        record Unknown() implements Outcome {}
    }

    public Outcome read(
            T unitOfWork, UUID fileId, Actor reader, String reason, Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(reader, "reader must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a content read requires a reason (INV-REC-10): the audited unit is the"
                            + " access, and an unreasoned access is not recordable");
        }
        // The reason reaches the audit record, which is never cleaned: the person-written
        // reason rule, before the file is even looked up (corrected 2026-10-02 by the Phase 8
        // -> 9 transition, SEC-04: blank was the rule).
        FileReadmission.requireReason(reason, "a content read");

        Optional<SettlementFileStore.FileRow> found = store.fileById(unitOfWork, fileId);
        if (found.isEmpty()) {
            return new Outcome.Unknown();
        }
        SettlementFileStore.FileRow file = found.get();
        try {
            byte[] content = store.readContent(unitOfWork, fileId);
            appendReadRecord(unitOfWork, file, reader, reason, correlation,
                    AuditOutcome.SUCCEEDED, "served");
            return new Outcome.Served(file, content);
        } catch (SettlementStorageException corrupt) {
            // The failure class only - never chunk bytes, never the message's storage detail.
            appendReadRecord(unitOfWork, file, reader, reason, correlation,
                    AuditOutcome.FAILED, "verification failed; nothing served (INV-HIST-02)");
            return new Outcome.Corrupt(file);
        }
    }

    private void appendReadRecord(
            T unitOfWork,
            SettlementFileStore.FileRow file,
            Actor reader,
            String reason,
            Correlation correlation,
            AuditOutcome outcome,
            String what) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        reader,
                        clock.instant(),
                        SettlementAuditAction.SETTLEMENT_FILE_CONTENT_READ,
                        "settlement_file",
                        file.id().toString(),
                        Optional.of(reason),
                        outcome,
                        correlation.correlationId(),
                        // Identifiers and the address - never a byte of content (INV-AUD-02).
                        Optional.of(
                                "source=" + file.sourceCode()
                                        + ", sha256="
                                        + HexFormat.of().formatHex(file.contentSha256())
                                        + ", " + what)));
    }
}
