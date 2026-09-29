package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The second person's act (`P8-TSK-003`, ADR-0066 §2, `INV-SET-07`): attestation is a
 * {@code NULL → value} fact on an uploaded file — never a status edge, which is why it writes
 * no {@code file_event} — recorded once, by somebody other than the uploader, and audited
 * ({@code settlement.SettlementFileAttested}).
 *
 * <h2>Why only an upload is attestable</h2>
 *
 * <p>Attestation is an upload's <em>authentication</em>. A pull is authenticated by its
 * source's own confined credential and waits for nobody; a readmission's attestation has its
 * own rules and arrives with `P8-TSK-022`. Attesting either would record an act that means
 * nothing, so it is refused rather than stored.
 *
 * <h2>Ten instances</h2>
 *
 * <p>The row lock ({@code FOR UPDATE}) serialises the common case, and the conditional
 * {@code NULL → value} write is the arbiter even without it: a racer whose conditional
 * matched no row re-reads and either converges (it was this same attester) or is refused
 * ({@code SettlementFileNotAttestable}). The distinctness {@code CHECK} and the
 * once-and-never-moves trigger clause stand behind every writer.
 */
@RequiredArgsConstructor
public final class FileAttestation<T> {

    @NonNull private final SettlementFileStore<T> store;
    @NonNull private final AuditWriter<T> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The act's verdict — either way, an attestation by exactly one person stands. */
    public sealed interface Result {
        SettlementFileStore.FileRow file();

        /** This call recorded the attestation, and its audit record committed with it. */
        record Attested(SettlementFileStore.FileRow file) implements Result {}

        /** The same attester repeated the act: converged, nothing written, no second audit. */
        record AlreadyAttestedByYou(SettlementFileStore.FileRow file) implements Result {}
    }

    public Result attest(T unitOfWork, UUID fileId, Actor attester, Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(attester, "attester must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");

        SettlementFileStore.FileRow file =
                store.lockFileById(unitOfWork, fileId)
                        .orElseThrow(() -> new SettlementFileNotFound(fileId));
        if (file.receivedVia() != DeliveryChannel.UPLOAD) {
            throw new SettlementFileNotAttestable(
                    "only an upload waits for a second person; a "
                            + file.receivedVia()
                            + " delivery is authenticated by its own channel (ADR-0066 §2)");
        }
        // The explicit relaxation P8-TSK-003 designed for, taken by P8-TSK-008 with the
        // machine's arrival: attestation is settable while RECEIVED or PARSED - the attester
        // may first read the parsed totals - and never on a terminal file (ADR-0066 §2). The
        // V003 trigger holds the terminal half for every writer.
        if (file.status().isTerminal()) {
            throw new SettlementFileNotAttestable(
                    "a " + file.status() + " file is terminal and not attestable");
        }
        if (file.attestation().isPresent()) {
            return convergedOrRefused(file, attester);
        }
        if (file.receivedBy().equals(Optional.of(attester.id()))) {
            // The domain rank of the distinctness rule; V002's CHECK is the second, binding
            // every writer this class is not.
            throw new AttestationBySubmitter(fileId);
        }
        Instant now = clock.instant();
        if (!store.recordAttestation(unitOfWork, fileId, attester.id(), now)) {
            // The conditional found a value: a racer on another instance won between our read
            // and our write (the row lock was absent or not yet ours). The database is the
            // arbiter - re-read and answer as if we had seen the winner first.
            SettlementFileStore.FileRow won =
                    store.fileById(unitOfWork, fileId)
                            .orElseThrow(() -> new SettlementFileNotFound(fileId));
            return convergedOrRefused(won, attester);
        }
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        attester,
                        now,
                        SettlementAuditAction.SETTLEMENT_FILE_ATTESTED,
                        "settlement_file",
                        fileId.toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and the address - never a value (INV-AUD-02).
                        Optional.of(
                                "source=" + file.sourceCode()
                                        + ", sha256="
                                        + HexFormat.of().formatHex(file.contentSha256()))));
        return new Result.Attested(
                store.fileById(unitOfWork, fileId)
                        .orElseThrow(() -> new SettlementFileNotFound(fileId)));
    }

    private static Result convergedOrRefused(SettlementFileStore.FileRow file, Actor attester) {
        SettlementFileStore.Attestation standing =
                file.attestation()
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "a losing conditional attestation found no"
                                                        + " standing value: nothing else"
                                                        + " clears attested_by"));
        if (standing.attestedBy().equals(attester.id())) {
            return new Result.AlreadyAttestedByYou(file);
        }
        throw new SettlementFileNotAttestable(
                "the file is already attested; an attestation is recorded once (INV-SET-07)");
    }

    /** No settlement file has this id — the caller's 404, recording nothing. */
    public static final class SettlementFileNotFound extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SettlementFileNotFound(UUID fileId) {
            super("no settlement file " + fileId + " exists (settlement.FileNotFound)");
        }
    }

    /** The file's own facts refuse the act (settlement.FileNotAttestable). */
    public static final class SettlementFileNotAttestable extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SettlementFileNotAttestable(String why) {
            super(why + " (settlement.FileNotAttestable)");
        }
    }

    /** The uploader tried to attest their own file (`INV-SET-07`, `INV-AUD-04`). */
    public static final class AttestationBySubmitter extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        AttestationBySubmitter(UUID fileId) {
            super(
                    "the uploader cannot attest settlement file " + fileId
                            + " (settlement.AttestationBySubmitter, INV-SET-07)");
        }
    }
}
