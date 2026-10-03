package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A person's reasoned refusal of a settlement file (`P8-TSK-008`, moved from `P8-TSK-003`
 * whose schema had no {@code REJECTED}): {@code RECEIVED | PARSED → REJECTED(DECLINED)}, and
 * a parsed file's batch moves {@code PARSED → REJECTED} in the SAME transaction — the live
 * key frees at commit, so the counterparty's genuine re-issue is admitted.
 *
 * <p>Declining is a judgement, not our validation. Recovery is the counterparty's re-issue —
 * but a byte-identical copy meets this file's content address as its duplicate, answered with
 * {@code duplicateOf} — or, for a mistaken decline, a readmission (`P8-TSK-022`, deciding
 * ADR-0066 §8's recorded question), which inherits NO authentication from the declined file
 * however it was authenticated: it waits for a second person of its own.
 *
 * <h2>Idempotency, by state</h2>
 *
 * <p>No key: the machine is the record. A repeat decline finds a terminal file and is
 * refused ({@code 409 settlement.FileNotAttestable}) — each door refuses before it converges
 * (the `P8-TSK-006` rule), and a refusal of an already-declined file tells the caller the
 * truth: the act they wanted is done, by someone, on the record.
 *
 * <h2>Ten instances</h2>
 *
 * <p>The row lock serialises decline against attestation and the parse leg's claim; the
 * conditional edge and the transition trigger arbitrate for any writer the lock misses.
 */
@RequiredArgsConstructor
public final class FileDecline {

    @NonNull private final SettlementFileStore<Connection> files;
    @NonNull private final SettlementBatchStore<Connection> batches;
    @NonNull private final IntakeOutcomeObserver observer;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The declined file, as it now stands. */
    public record Declined(SettlementFileStore.FileRow file) {}

    public Declined decline(
            Connection unitOfWork, UUID fileId, String reason, Actor decliner,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(decliner, "decliner must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("a decline is reasoned (INV-AUD-03)");
        }

        SettlementFileStore.FileRow file =
                files.lockFileById(unitOfWork, fileId)
                        .orElseThrow(() -> new FileAttestation.SettlementFileNotFound(fileId));
        if (file.status().isTerminal()) {
            throw new FileAttestation.SettlementFileNotAttestable(
                    "a " + file.status() + " file is terminal and cannot be declined");
        }
        Instant now = clock.instant();
        if (!files.markRejected(
                unitOfWork,
                fileId,
                file.status(),
                RejectionCode.DECLINED,
                Optional.of(RejectionCode.DECLINED.name()),
                now)) {
            // Unreachable under the held lock; the conditional is the arbiter regardless.
            throw new FileAttestation.SettlementFileNotAttestable(
                    "the file moved while being declined");
        }
        if (file.status() == FileStatus.PARSED) {
            SettlementBatchStore.BatchRow batch =
                    batches.batchByFileId(unitOfWork, fileId)
                            .orElseThrow(
                                    () ->
                                            new SettlementStorageException(
                                                    "a PARSED file has a batch"
                                                            + " (INV-SET-07): none found for "
                                                            + fileId));
            if (!batches.markBatchRejected(
                    unitOfWork,
                    batch.id(),
                    decliner,
                    Optional.of(reason),
                    now,
                    correlation.correlationId())) {
                throw new SettlementStorageException(
                        "batch " + batch.id() + " moved while its file was being declined");
            }
        }
        files.appendFileEvent(
                unitOfWork,
                fileId,
                file.status(),
                FileStatus.REJECTED,
                decliner,
                Optional.of(reason),
                now,
                correlation.correlationId());
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        decliner,
                        now,
                        SettlementAuditAction.SETTLEMENT_FILE_DECLINED,
                        "settlement_file",
                        fileId.toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and the address - never a value (INV-AUD-02).
                        Optional.of(
                                "source=" + file.sourceCode()
                                        + ", declinedFrom=" + file.status())));
        SettlementFileEvents.rejected(
                outbox,
                unitOfWork,
                ids,
                fileId,
                file.sourceId(),
                RejectionCode.DECLINED,
                now,
                correlation);
        observer.rejected(
                file.sourceCode(),
                RejectionCode.DECLINED,
                Duration.between(file.receivedAt(), now));
        return new Declined(
                files.fileById(unitOfWork, fileId)
                        .orElseThrow(() -> new FileAttestation.SettlementFileNotFound(fileId)));
    }
}
