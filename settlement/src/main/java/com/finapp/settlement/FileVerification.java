package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.settlement.format.ParsedLine;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The re-parse verification (`P8-TSK-022`, ADR-0066 §8): a stored file re-parsed under its
 * RECORDED format version, its lines' two digests compared with the stored lines'. It answers
 * "would this build parse these bytes into exactly what we hold?" — and it NEVER writes a line:
 * stored lines are evidence (`INV-SET-07`, `INV-HIST-02`), and a verification that could replace
 * them would be an edit with a reason attached.
 *
 * <h2>The verdicts</h2>
 *
 * <ul>
 *   <li>{@link Verdict#NOT_PARSED} — the file has no stored batch: nothing to compare.
 *   <li>{@link Verdict#FORMAT_VERSION_UNAVAILABLE} — this build compiles no format for the
 *       file's family, or a different version: verification is under the RECORDED version only,
 *       never a newer one (a newer version's different answer is a readmission's question).
 *   <li>{@link Verdict#MATCHES} — the same number of lines and, per line number, the same
 *       {@code raw_record_sha256} and {@code canonical_fingerprint}.
 *   <li>{@link Verdict#DIFFERS} — otherwise, naming the first differing line number, or
 *       {@code -1} when the counts differ (a re-parse the recorded version rejects is one).
 *   <li>{@link Verdict#CORRUPT} — the stored bytes failed their authenticated decryption or
 *       checksum: nothing compared, and the access is on the record as {@code FAILED}
 *       ({@code EvidenceContentReads}' rule — an exception would roll that record back).
 * </ul>
 *
 * <h2>Why it is audited</h2>
 *
 * <p>It decrypts the bytes, so — like a content read ({@code INV-REC-10}) — every verification
 * of an existing file is reasoned and audited ({@code settlement.SettlementFileVerified}) in the
 * caller's transaction, whatever the verdict. A guessed id is refused and records nothing.
 *
 * <h2>Ten instances</h2>
 *
 * <p>Read-only over frozen facts: the stored lines, the content and the recorded version never
 * move once written, so ten concurrent verifications answer alike and each writes its own audit
 * record. No lock is taken.
 */
@RequiredArgsConstructor
public final class FileVerification {

    @NonNull private final SettlementFileStore<Connection> files;
    @NonNull private final SettlementBatchStore<Connection> batches;

    /** The compiled formats; a file is verified only under the version it was parsed under. */
    @NonNull private final Map<SettlementFormatId, SettlementFormat> formats;

    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;

    /** What the comparison came to — {@link Verified#verdict()} carries one of these names. */
    public enum Verdict {
        NOT_PARSED,
        FORMAT_VERSION_UNAVAILABLE,
        MATCHES,
        DIFFERS,
        CORRUPT
    }

    /**
     * The verification's answer.
     *
     * @param verdict one of {@link Verdict}'s names
     * @param linesCompared the stored lines compared before the verdict — every one for
     *     {@code MATCHES}, up to and including the first differing one for {@code DIFFERS}
     * @param firstDifferingLine present exactly for {@code DIFFERS}: a line number, or
     *     {@code -1} when the counts differ
     */
    public record Verified(
            String verdict, int linesCompared, Optional<Integer> firstDifferingLine) {

        public Verified {
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(firstDifferingLine, "firstDifferingLine must not be null");
            if (linesCompared < 0) {
                throw new IllegalArgumentException("linesCompared is never negative");
            }
            if (firstDifferingLine.isPresent() != Verdict.DIFFERS.name().equals(verdict)) {
                throw new IllegalArgumentException(
                        "a first differing line is named exactly for DIFFERS");
            }
            Verdict.valueOf(verdict); // One of the closed vocabulary, or refused.
        }
    }

    public Verified verify(
            Connection unitOfWork,
            UUID fileId,
            Actor actor,
            String reason,
            Instant now,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        FileReadmission.requireReason(reason, "a verification");

        SettlementFileStore.FileRow file =
                files.fileById(unitOfWork, fileId)
                        .orElseThrow(() -> new FileAttestation.SettlementFileNotFound(fileId));
        Verified verified = judge(unitOfWork, file);
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        SettlementAuditAction.SETTLEMENT_FILE_VERIFIED,
                        "settlement_file",
                        fileId.toString(),
                        Optional.of(reason),
                        Verdict.CORRUPT.name().equals(verified.verdict())
                                ? AuditOutcome.FAILED
                                : AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers, the verdict and counts - never a value (INV-AUD-02).
                        Optional.of(
                                "file=" + fileId
                                        + ", source=" + file.sourceCode()
                                        + ", formatVersion=" + file.formatVersion()
                                        + ", verdict=" + verified.verdict()
                                        + ", linesCompared=" + verified.linesCompared()
                                        + verified.firstDifferingLine()
                                                .map(line -> ", firstDifferingLine=" + line)
                                                .orElse(""))));
        return verified;
    }

    private Verified judge(Connection unitOfWork, SettlementFileStore.FileRow file) {
        if (batches.batchByFileId(unitOfWork, file.id()).isEmpty()) {
            return verdict(Verdict.NOT_PARSED, 0, Optional.empty());
        }
        SettlementFormat format = formats.get(file.formatId());
        if (format == null || format.version() != file.formatVersion()) {
            return verdict(Verdict.FORMAT_VERSION_UNAVAILABLE, 0, Optional.empty());
        }
        byte[] content;
        try {
            content = files.readContent(unitOfWork, file.id());
        } catch (SettlementStorageException corrupt) {
            // Tampering, a transplanted chunk, truncation: nothing compared, nothing served.
            return verdict(Verdict.CORRUPT, 0, Optional.empty());
        }
        List<SettlementBatchStore.LineDigest> stored = batches.lineDigestsOf(unitOfWork, file.id());
        if (!(format.parse(content) instanceof SettlementFormat.Result.Parsed parsed)) {
            // The recorded version now rejects what it once parsed: no line is reproduced.
            return verdict(Verdict.DIFFERS, 0, Optional.of(-1));
        }
        List<ParsedLine> lines =
                parsed.batch().lines().stream()
                        .sorted(Comparator.comparingInt(ParsedLine::lineNo))
                        .toList();
        if (lines.size() != stored.size()) {
            return verdict(Verdict.DIFFERS, 0, Optional.of(-1));
        }
        for (int i = 0; i < stored.size(); i++) {
            SettlementBatchStore.LineDigest held = stored.get(i);
            ParsedLine reparsed = lines.get(i);
            if (reparsed.lineNo() != held.lineNo()
                    || !MessageDigest.isEqual(reparsed.rawRecordSha256(), held.rawRecordSha256())
                    || !MessageDigest.isEqual(
                            reparsed.canonicalFingerprint(), held.canonicalFingerprint())) {
                return verdict(Verdict.DIFFERS, i + 1, Optional.of(held.lineNo()));
            }
        }
        return verdict(Verdict.MATCHES, stored.size(), Optional.empty());
    }

    private static Verified verdict(
            Verdict verdict, int linesCompared, Optional<Integer> firstDifferingLine) {
        return new Verified(verdict.name(), linesCompared, firstDifferingLine);
    }
}
