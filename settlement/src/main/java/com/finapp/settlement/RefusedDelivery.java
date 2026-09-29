package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A delivery the door refused, kept as metadata and never as value (`P8-TSK-002`, ADR-0066
 * §4 — the precedence ruling: for a refused delivery, {@code INV-PAY-02} and
 * {@code INV-RAIL-03} outrank {@code INV-HIST-02}, because a refusal decides nothing and the
 * counterparty still holds its own bytes).
 *
 * <p>The checksum is what chains the refusal to any later re-presentation of the same bytes;
 * the reason, line and field say why without saying what. Nothing here — not one field — may
 * carry content, and the schema's own {@code CHECK}s bound the two columns that could.
 */
public record RefusedDelivery(
        UUID id,
        UUID sourceId,
        byte[] contentSha256,
        int contentLength,
        SettlementFormatId formatId,
        int formatVersion,
        RefusalReason reason,
        Optional<Integer> lineNo,
        Optional<String> fieldName,
        DeliveryChannel channel,
        Actor deliveredBy,
        Instant refusedAt,
        CorrelationId correlation) {

    public RefusedDelivery {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
        Objects.requireNonNull(formatId, "formatId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(lineNo, "lineNo must not be null");
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(deliveredBy, "deliveredBy must not be null");
        Objects.requireNonNull(refusedAt, "refusedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!reason.leavesARow()) {
            throw new IllegalArgumentException(
                    "an over-bound delivery stores nothing but its audit record (ADR-0066 §4):"
                            + " only a content refusal leaves this row");
        }
        if (contentSha256.length != 32) {
            throw new IllegalArgumentException("a content address is SHA-256: 32 bytes");
        }
        if (contentLength < 1) {
            throw new IllegalArgumentException("a refused delivery had content");
        }
        contentSha256 = contentSha256.clone();
    }

    @Override
    public byte[] contentSha256() {
        return contentSha256.clone();
    }

    /** Identity and reason only. */
    @Override
    public String toString() {
        return "RefusedDelivery[" + id + ", " + reason + "]";
    }
}
