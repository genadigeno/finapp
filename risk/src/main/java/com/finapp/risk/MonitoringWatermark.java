package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The monitoring intake's position in one source (`P13-TSK-019`; {@code RISK_LIFECYCLES.md} section 4.11: "the
 * watermark a claim, not a machine"): the id of the last journal entry ingested - a keyset over the journal's
 * time-ordered primary key - and its sequence, the id's 48-bit millisecond prefix the database derives from it
 * ({@code risk V004}'s trigger; the journal has no sequence column, {@code DESIGN-019} section 0). {@code nextSweepAt}
 * is the claim's permit.
 *
 * @param source the journal today ({@link #LEDGER_JOURNAL}); a second source is a migration
 */
public record MonitoringWatermark(String source, UUID lastEntryId, long lastSequence, Instant nextSweepAt) {

    /** The one source today. */
    public static final String LEDGER_JOURNAL = "ledger.journal";

    /** The lowest position: sorts before every UUIDv7 ever minted; sequence 0. */
    public static final UUID FLOOR = positionAt(0);

    private static final long MAX_SEQUENCE = (1L << 48) - 1;

    public MonitoringWatermark {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lastEntryId, "lastEntryId");
        Objects.requireNonNull(nextSweepAt, "nextSweepAt");
        if (lastSequence < 0) {
            throw new IllegalArgumentException("a sequence is not negative");
        }
    }

    /**
     * The lowest UUIDv7 of millisecond {@code sequence} - the id generator's layout (ADR-0013: 48 bits of milliseconds,
     * the version nibble, a zero counter, the RFC variant, zero randomness), so every id minted at that millisecond or
     * later sorts at or after it and every earlier one before it. The reprocessing door moves the watermark to this
     * position for the millisecond the operator names, and the keyset read starts just after it.
     */
    public static UUID positionAt(long sequence) {
        if (sequence < 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("a sequence is a 48-bit millisecond: " + sequence);
        }
        long mostSignificant = (sequence << 16) | 0x7000L;
        return new UUID(mostSignificant, 0x8000_0000_0000_0000L);
    }

    /** The millisecond prefix of a UUIDv7 - what the database's {@code risk.uuid7_sequence} derives. */
    public static long sequenceOf(UUID id) {
        return Objects.requireNonNull(id, "id").getMostSignificantBits() >>> 16;
    }
}
