package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A settlement file at birth (`P8-TSK-002`, ADR-0066): the metadata the door committed with
 * the bytes — born {@link FileStatus#RECEIVED}, its checksum the content address
 * ({@code INV-HIST-02}), inert until something downstream parses it.
 *
 * <p>Deliberately a record of the born row, not yet a lifecycle aggregate: the file has no
 * edge until the parse leg exists (`P8-TSK-008`), and a machine with no transitions is a
 * constructor. What is already load-bearing is here: the identity, the channel (which decides
 * what authenticates the bytes — never the receipts), the format version the screen ran under,
 * and who delivered ({@code receivedBy} is the person for {@code UPLOAD}, and empty for
 * {@code PULL}, where the source's credential authenticates instead — the `V002` {@code CHECK}).
 *
 * @param businessDate the date the uploader declared the statement covers — the counterparty's
 *     claim, recorded verbatim; the parse leg reads the file's own dates
 * @param lineCount by the screen's walk — recorded so the parse bound needs no second count
 * @param readmitsFileId the original a {@code READMISSION} re-presents (`P8-TSK-022`) — present
 *     exactly for that channel, the `V002` {@code CHECK}'s mirror
 */
public record SettlementFile(
        UUID id,
        UUID sourceId,
        DeliveryChannel receivedVia,
        Optional<LocalDate> businessDate,
        SettlementFormatId formatId,
        int formatVersion,
        byte[] contentSha256,
        int contentLength,
        int lineCount,
        Optional<Actor> receivedBy,
        Instant receivedAt,
        CorrelationId correlation,
        Optional<UUID> readmitsFileId) {

    public static final int MAX_CONTENT_LENGTH = 8 * 1024 * 1024;

    public static final int MAX_LINES = 50_000;

    public SettlementFile {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(receivedVia, "receivedVia must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(formatId, "formatId must not be null");
        Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
        Objects.requireNonNull(receivedBy, "receivedBy must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(readmitsFileId, "readmitsFileId must not be null");
        if (contentSha256.length != 32) {
            throw new IllegalArgumentException("a content address is SHA-256: 32 bytes");
        }
        if (contentLength < 1 || contentLength > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "a stored file is 1.." + MAX_CONTENT_LENGTH + " bytes; the door refuses the"
                            + " rest before anything is built");
        }
        if (lineCount < 0 || lineCount > MAX_LINES) {
            throw new IllegalArgumentException(
                    "a stored file has at most " + MAX_LINES + " records");
        }
        if (formatVersion < 1) {
            throw new IllegalArgumentException("a format version is 1 or later");
        }
        if (receivedBy.isEmpty() != (receivedVia == DeliveryChannel.PULL)) {
            throw new IllegalArgumentException(
                    "receivedBy is the delivering person for UPLOAD and READMISSION, and empty"
                            + " exactly for PULL, where the source's credential authenticates");
        }
        if (readmitsFileId.isPresent() != (receivedVia == DeliveryChannel.READMISSION)) {
            throw new IllegalArgumentException(
                    "a READMISSION names the original it re-presents, and only a READMISSION"
                            + " does (file_readmission_names_original)");
        }
        contentSha256 = contentSha256.clone();
    }

    /** A file the door received by upload or pull — it re-presents no original. */
    public SettlementFile(
            UUID id,
            UUID sourceId,
            DeliveryChannel receivedVia,
            Optional<LocalDate> businessDate,
            SettlementFormatId formatId,
            int formatVersion,
            byte[] contentSha256,
            int contentLength,
            int lineCount,
            Optional<Actor> receivedBy,
            Instant receivedAt,
            CorrelationId correlation) {
        this(id, sourceId, receivedVia, businessDate, formatId, formatVersion, contentSha256,
                contentLength, lineCount, receivedBy, receivedAt, correlation, Optional.empty());
    }

    @Override
    public byte[] contentSha256() {
        return contentSha256.clone();
    }

    /**
     * Identity and channel only. The metadata itself is not content, but the delivering
     * actor's id and the declared business date have no business in a log line either
     * ({@code INV-AUD-02}'s discipline: the audit record is the record).
     */
    @Override
    public String toString() {
        return "SettlementFile[" + id + ", " + receivedVia + "]";
    }
}
